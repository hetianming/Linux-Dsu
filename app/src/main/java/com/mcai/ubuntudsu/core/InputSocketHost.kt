package com.mcai.ubuntudsu.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileDescriptor
import java.net.SocketAddress

/**
 * root 侧输入 socket 托管（v2.0.11 按 crosvm 源码重写）。
 *
 * 三条来自 crosvm 源码的硬约束（devices/src/virtio/input/）：
 *  1) crosvm 是 connect 方，只在设备创建那一刻连一次；worker 检测到 event_source
 *     的 is_hungup 会 wait_ctx.delete(&event_source)，**永久不再读、绝不重连**。
 *     => 我们这边**绝不能主动 close 任何活连接**，否则该设备当场报废。
 *  2) event_source.rs::send_event 会把 guest 的 status 事件**回写**到这个 socket。
 *     旧桥 accept 后只写不读，回写字节堆积会把 crosvm worker 卡在 write_all，
 *     于是它连我们发过去的输入也不再读 —— 这正是「能显示不能操作」的隐形杀手。
 *     => 必须把 socket 当全双工，POLLIN 时主动读掉回写字节。
 *  3) socket 上是 8 字节 virtio_input_event 流，无握手、无过滤；只要写入成功
 *     guest 必然收到。=> 写入失败只可能是「连接已死」，状态必须如实上报。
 *
 * 因此本实现：
 *  - 每路一个 poll 线程，同时监听 listener(新连接) + 所有 peer(回写/挂断)；
 *  - 多个 peer 全部保留并广播写入（脚本重试链会连多次，不怕误判哪个才是活 VM）；
 *  - 只在 POLLHUP/POLLERR/EOF 时才把该 peer 标记 dead 并回收，平时绝不 close；
 *  - peer fd 一律 O_NONBLOCK，读写都不阻塞，写失败不影响 UI/写线程；
 *  - readyChannels 反映「存在 alive peer」，不再拿 fd!=null 冒充 ✓。
 */
object InputSocketHost {

    private const val TAG = "InputSocketHost"

    const val CH_TOUCH = 0
    const val CH_KEYBOARD = 1
    const val CH_MOUSE = 2
    const val CH_SWITCHES = 3
    const val CHANNEL_COUNT = 4

    private val PATHS = arrayOf(
        "/data/local/tmp/gyah_input_7788.sock",   // multi-touch
        "/data/local/tmp/gyah_input_7789.sock",   // keyboard
        "/data/local/tmp/gyah_input_7790.sock",   // mouse
        "/data/local/tmp/gyah_input_7791.sock",   // switches
    )

    private fun fileSystemAddr(path: String): SocketAddress {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/system/UnixSocketAddress;") }
        val cls = Class.forName("android.system.UnixSocketAddress")
        return cls.getMethod("createFileSystem", String::class.java).invoke(null, path) as SocketAddress
    }

    private class Peer(val fd: FileDescriptor) {
        @Volatile var alive = true
    }

    private class Channel {
        var serverFd: FileDescriptor? = null
        val lock = Any()
        val peers = ArrayList<Peer>()
        @Volatile var connectCount = 0
        @Volatile var hupCount = 0
        @Volatile var rxBytes = 0L
        @Volatile var txOk = 0L
        @Volatile var txFail = 0L
        @Volatile var lastErr = ""
    }

    private val channels = Array(CHANNEL_COUNT) { Channel() }
    @Volatile private var closed = false

    private fun setNonBlocking(fd: FileDescriptor) {
        runCatching {
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }
    }

    /** 幂等：4 路 listener 建好并启动 poll 线程。必须在 crosvm 启动前调用。 */
    @Synchronized
    fun ensureListening() {
        if (channels.all { it.serverFd != null }) return
        for (ch in 0 until CHANNEL_COUNT) {
            val c = channels[ch]
            if (c.serverFd != null) continue
            val path = PATHS[ch]
            try {
                File(path).delete()   // 仅清残留文件；已有活 peer 时本函数会提前 return，不会走到这
                val fd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)
                Os.bind(fd, fileSystemAddr(path))
                Os.listen(fd, 16)     // 重试链会连多次，backlog 1 会丢连接
                Os.chmod(path, 0b111_111_111)
                setNonBlocking(fd)
                c.serverFd = fd
                startIoThread(ch)
                Log.i(TAG, "listening: $path")
            } catch (e: Exception) {
                Log.e(TAG, "failed to listen on $path", e)
            }
        }
    }

    private fun startIoThread(ch: Int) {
        val c = channels[ch]
        val server = c.serverFd ?: return
        Thread({
            val buf = ByteArray(4096)
            while (!closed) {
                try {
                    val fds = ArrayList<StructPollfd>()
                    fds.add(StructPollfd().apply {
                        fd = server
                        events = OsConstants.POLLIN.toShort()
                        revents = 0
                    })
                    val snapshot: List<Peer> = synchronized(c.lock) {
                        c.peers.filter { it.alive }
                    }
                    for (p in snapshot) {
                        fds.add(StructPollfd().apply {
                            fd = p.fd
                            events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort()
                            revents = 0
                        })
                    }

                    val rc = Os.poll(fds.toTypedArray(), 200)
                    if (rc <= 0) continue

                    // 新连接：只加入，绝不为它关掉任何旧连接（crosvm 断一次就永久废）
                    if ((fds[0].revents.toInt() and OsConstants.POLLIN) != 0) {
                        val nfd = runCatching { Os.accept(server, null) }.getOrNull()
                        if (nfd != null) {
                            setNonBlocking(nfd)
                            synchronized(c.lock) {
                                c.peers.add(Peer(nfd))
                                c.connectCount++
                            }
                            Log.i(TAG, "crosvm connected ch=$ch total=${c.connectCount}")
                        }
                    }

                    // 已建连接：读回写（防止 crosvm worker 阻塞）+ 检测挂断
                    for (i in 1 until fds.size) {
                        val rev = fds[i].revents.toInt()
                        if (rev == 0) continue
                        val peer = snapshot.getOrNull(i - 1) ?: continue
                        if (peer.fd !== fds[i].fd) continue

                        if ((rev and OsConstants.POLLIN) != 0) {
                            val n = try {
                                Os.read(peer.fd, buf, 0, buf.size)
                            } catch (e: ErrnoException) {
                                if (e.errno == OsConstants.EAGAIN) 0 else -1
                            } catch (e: Exception) {
                                -1
                            }
                            if (n > 0) c.rxBytes += n       // 丢弃即可，本该由 host 消费
                            else if (n == 0) peer.alive = false   // EOF
                        }
                        if ((rev and (OsConstants.POLLHUP or OsConstants.POLLERR)) != 0) {
                            c.hupCount++
                            peer.alive = false
                            Log.w(TAG, "crosvm hangup ch=$ch rev=$rev (累计 ${c.hupCount})")
                        }
                    }

                    // 只回收已判定 dead 的 fd；活连接一个都不碰
                    synchronized(c.lock) {
                        val it = c.peers.iterator()
                        while (it.hasNext()) {
                            val p = it.next()
                            if (!p.alive) {
                                runCatching { Os.close(p.fd) }
                                it.remove()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (closed) break
                    Log.w(TAG, "io loop ch=$ch: ${e.message}")
                    runCatching { Thread.sleep(100) }
                }
            }
        }, "InputIo-$ch").apply { isDaemon = true }.start()
    }

    /** 各路是否有 alive 的 crosvm 事件源连接（真实状态，不是 fd 非空）。 */
    fun readyChannels(): BooleanArray {
        ensureListening()
        return BooleanArray(CHANNEL_COUNT) { ch ->
            val c = channels[ch]
            synchronized(c.lock) { c.peers.any { it.alive } }
        }
    }

    /** 向该路所有 alive peer 广播；任一写完整即算成功。非阻塞，失败不拖累调用方。 */
    fun write(channel: Int, data: ByteArray): Boolean {
        if (channel !in 0 until CHANNEL_COUNT || data.isEmpty()) return false
        val c = channels[channel]
        val targets: List<Peer> = synchronized(c.lock) { c.peers.filter { it.alive } }
        if (targets.isEmpty()) {
            c.lastErr = "no-peer"
            return false
        }
        var any = false
        for (p in targets) {
            try {
                var off = 0
                while (off < data.size) {
                    val n = Os.write(p.fd, data, off, data.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off >= data.size) {
                    any = true
                    c.txOk++
                    c.lastErr = ""
                } else {
                    c.txFail++
                    c.lastErr = "short-write"
                }
            } catch (e: ErrnoException) {
                c.txFail++
                c.lastErr = "errno=${e.errno}"
                if (e.errno == OsConstants.EPIPE || e.errno == OsConstants.EBADF) p.alive = false
            } catch (e: Exception) {
                c.txFail++
                c.lastErr = e.javaClass.simpleName
            }
        }
        return any
    }

    /** 供状态栏/boot.log 使用的真实链路诊断。 */
    fun diagnostics(): String = buildString {
        for (ch in 0 until CHANNEL_COUNT) {
            if (ch > 0) append(" | ")
            val c = channels[ch]
            val n = synchronized(c.lock) { c.peers.count { it.alive } }
            append("${7788 + ch}:连${n}/累计${c.connectCount} 断${c.hupCount} 收${c.rxBytes}B 发${c.txOk}/${c.txFail}")
            if (c.lastErr.isNotEmpty()) append(" ${c.lastErr}")
        }
    }

    @Synchronized
    fun closeAll() {
        closed = true
        for (ch in 0 until CHANNEL_COUNT) {
            val c = channels[ch]
            synchronized(c.lock) {
                c.peers.forEach { runCatching { Os.close(it.fd) } }
                c.peers.clear()
            }
            c.serverFd?.let { runCatching { Os.close(it) } }
            c.serverFd = null
            runCatching { File(PATHS[ch]).delete() }
        }
    }
}
