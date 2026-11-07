package com.mcai.ubuntudsu.core

import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * v2.0.11 内置原生显示的输入桥（APP 侧）。
 *
 * 架构：4 个输入 unix socket 由 root 服务进程 [InputSocketHost] 托管，本桥把
 * [EvdevEncoder] 编好的 8 字节 virtio_input_event 记录通过 AIDL 交给 root 进程
 * 直写 socket fd（没有 touchd / 本地 TCP，那层在真机上被 SELinux 拦截）。
 *
 * v2.0.11 关键修复（针对 v2.0.10「检测全绿但仍不能操作」）：
 *  - 旧的 sink() 每次 write 都同步跨进程 binder 调用，而 setOnTouchListener 在 UI
 *    线程，高频滑动（ACTION_MOVE ~120Hz）会把 UI 线程拖死、binder 事务堆积后大面积
 *    失败 —— 用户截图的「写入✓86 ✗669」正是这个现象。现在改为**纯入队**：触摸回调
 *    零跨进程、零阻塞，由专用写线程按 3ms 聚合窗口把同 channel 的多批字节合并后
 *    一次写出，binder 调用次数下降一到两个数量级。
 *  - evdev 是定长 8 字节流、SYN_REPORT 自带分帧，同 channel 顺序拼接不破坏语义，
 *    合并安全；不同 channel 之间是独立 virtqueue，先后次序无关。
 *  - lastError 如实保留，不再被 writeOk>0 掩盖；root 侧真实链路诊断
 *    [inputDiagnostics] 由写线程周期性拉取并缓存，状态栏只读缓存，不额外占用 binder。
 *
 * channel 索引与脚本 --input / InputSocketHost.PATHS 严格一致：
 *   0=7788 多点触控 | 1=7789 键盘 | 2=7790 鼠标 | 3=7791 开关
 */
object NativeInputBridge {

    private const val TAG = "NativeInputBridge"
    const val CH_TOUCH = 0
    const val CH_KEYBOARD = 1
    const val CH_MOUSE = 2
    const val CH_SWITCHES = 3

    private const val MAX_PENDING = 512          // 队列上限：触摸事件宁丢旧帧也不积压
    private const val BATCH_WINDOW_MS = 3L       // 聚合窗口，降低 binder 次数
    private const val DIAG_INTERVAL_MS = 800L    // root 侧诊断拉取间隔

    private class Frame(val ch: Int, val data: ByteArray)

    private val queue = ConcurrentLinkedDeque<Frame>()
    private val pending = AtomicInteger(0)
    private val generation = AtomicLong(0)
    private val wake = Object()

    @Volatile private var running = false
    private var writer: Thread? = null

    // 链路自证：写入 root 服务的成功/失败累计（按合并后的批次计数），状态栏实时显示。
    @Volatile var writeOk: Long = 0L; private set
    @Volatile var writeFail: Long = 0L; private set
    @Volatile var lastError: String = ""; private set

    /** root 侧真实链路诊断缓存（每路 alive 连接/累计连接/挂断/回读/收发/最后错误）。 */
    @Volatile var inputDiagnostics: String = "诊断采集中…"; private set

    /** ✓/✗ 数组：索引 0=触摸 1=键盘 2=鼠标 3=开关，来自 root 服务的 alive 快照。 */
    val channelsReady: BooleanArray
        get() = InputServiceClient.ready()

    val connected: Boolean
        get() = InputServiceClient.isConnected()

    @Synchronized
    fun start() {
        if (running) return
        // 上一次 stop 可能残留半截手势，重开时丢弃，避免 guest 卡在按下状态
        queue.clear()
        pending.set(0)
        running = true
        val g = generation.incrementAndGet()
        writer = Thread({ writeLoop(g) }, "NativeInputWriter").apply {
            isDaemon = true; start()
        }
        Log.i(TAG, "async input bridge started (gen=$g)")
    }

    /** 给 EvdevEncoder 用的 OutputStream：一批 evdev 字节 → 入队，绝不在这里跨进程。 */
    fun sink(ch: Int): OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            enqueue(ch, data)
        }
        override fun flush() { /* 入队即完成；真正的写出由写线程负责 */ }
    }

    private fun enqueue(ch: Int, data: ByteArray) {
        if (!running || data.isEmpty()) return
        while (pending.get() >= MAX_PENDING) {
            if (queue.pollFirst() == null) break
            pending.decrementAndGet()
        }
        queue.addLast(Frame(ch, data))
        pending.incrementAndGet()
        synchronized(wake) { wake.notifyAll() }
    }

    private fun writeLoop(g: Long) {
        var lastDiag = 0L
        while (running && generation.get() == g) {
            // 无数据时阻塞等待（最长 200ms 醒来一次拉诊断）
            if (queue.isEmpty()) {
                synchronized(wake) {
                    if (queue.isEmpty()) runCatching { wake.wait(200) }
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastDiag >= DIAG_INTERVAL_MS) {
                inputDiagnostics = InputServiceClient.diagnostics()
                lastDiag = now
            }
            if (queue.isEmpty()) continue

            // 聚合窗口：把紧接着的事件一起并进同一批
            runCatching { Thread.sleep(BATCH_WINDOW_MS) }

            val perCh = HashMap<Int, ByteArrayOutputStream>()
            while (true) {
                val f = queue.pollFirst() ?: break
                pending.decrementAndGet()
                perCh.getOrPut(f.ch) { ByteArrayOutputStream() }
                    .write(f.data, 0, f.data.size)
            }
            for ((ch, bos) in perCh) {
                val merged = bos.toByteArray()
                val ok = InputServiceClient.write(ch, merged)
                if (ok) {
                    writeOk++
                    lastError = ""
                } else {
                    writeFail++
                    lastError = if (!connected) "root 服务未连接"
                                else "ch$ch 写失败/无事件源"
                }
            }
        }
        Log.i(TAG, "async input bridge stopped (gen=$g); ok=$writeOk fail=$writeFail")
    }

    fun stop() {
        synchronized(this) {
            if (!running) return
            running = false
            generation.incrementAndGet()
        }
        synchronized(wake) { wake.notifyAll() }
        // 不 unbind：root 服务连接是进程级常驻，解绑会杀掉输入 socket（crosvm 只连一次）。
        Log.i(TAG, "stop requested (root service kept alive)")
    }
}
