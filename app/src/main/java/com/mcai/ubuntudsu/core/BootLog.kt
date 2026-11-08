package com.mcai.ubuntudsu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * boot.log 的读取与去噪。
 *
 * guest 内核的调试串口会持续打印不带换行的 '!'（真机实测约 164 万个 / 分钟），
 * 旧实现用 readText() 把整份日志读进内存再渲染，几十 MB 时直接卡死 / OOM。
 *
 * 这里做两件事：
 *  1) 只读文件尾部窗口，绝不整份读入内存；
 *  2) 把刷屏折叠成可读行（连续 '!'、重复行、超长行），但不改动任何诊断关键字。
 *
 * 磁盘上 boot.log 的体积由 GunyahManager 投放的日志看护脚本按大小轮转，
 * 本类只负责「读」，不碰写入链路，因此不会影响启动脚本的 MARK 偏移判定。
 */
object BootLog {

    private const val WINDOW = 256 * 1024

    /** 看护脚本轮转时保留的上一轮尾部文件名。 */
    const val ROTATED_TAIL = "boot.log.tail"

    /** 只读文件尾部 window 字节；刚被轮转时把上一轮尾部拼回来，保持上下文连续。 */
    fun readTail(f: File?, window: Int = WINDOW): String {
        f ?: return ""
        if (!f.isFile) return ""
        return runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val start = (len - window).coerceAtLeast(0L)
                raf.seek(start)
                val size = (len - start).toInt()
                if (size <= 0) return@use ""
                val buf = ByteArray(size)
                raf.readFully(buf)
                val cur = String(buf, Charsets.UTF_8)
                val prev = if (start == 0L && len < 64 * 1024) {
                    val t = File(f.parentFile, ROTATED_TAIL)
                    runCatching { if (t.isFile) t.readText() else "" }.getOrDefault("")
                } else {
                    ""
                }
                prev + cur
            }
        }.getOrDefault("")
    }

    /**
     * 折叠调试刷屏。只压缩连续感叹号、连续重复行和超长行，
     * 「VNC 已监听」「MEM_ACCEPT」「data: seed ok」等所有诊断串原样保留。
     */
    fun clean(raw: String): String {
        if (raw.isEmpty()) return ""
        val bang = Regex("!{3,}")
        val out = ArrayList<String>()
        var last: String? = null
        var repeats = 0

        fun flushRepeat() {
            if (repeats > 1) out.add("    … 上一行连续重复 ${repeats - 1} 次（已折叠）")
            repeats = 0
        }

        for (line0 in raw.lineSequence()) {
            var line = bang.replace(line0) { m -> " [${m.value.length} 个 '!' 已折叠] " }
            if (line.length > 400) line = line.take(400) + " …[超长行已截断]"
            if (line == last) {
                repeats++
                continue
            }
            flushRepeat()
            out.add(line)
            last = line
            repeats = 1
        }
        flushRepeat()
        return out.joinToString("\n")
    }

    fun readClean(f: File?, window: Int = WINDOW): String = clean(readTail(f, window))

    /**
     * 全文查找关键标记（自动进显示器 / 启动判定用），不受尾部窗口限制。
     * 分块流式扫描 + 块间重叠，内存恒定为 CHUNK 字节 —— 即使看护进程没起来、
     * 日志重新涨到几十 MB，也不会成为新的 OOM 点。同时回查 boot.log.tail。
     */
    private const val CHUNK = 256 * 1024

    fun containsMarker(f: File?, marker: String): Boolean {
        f ?: return false
        val needle = marker.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty()) return false
        if (scan(f, needle)) return true
        f.parentFile?.let { if (scan(File(it, ROTATED_TAIL), needle)) return true }
        return false
    }

    /** 在单个文件里查找字节序列；每次只驻留 CHUNK + needle.size 字节。 */
    private fun scan(f: File, needle: ByteArray): Boolean = runCatching {
        if (!f.isFile) return@runCatching false
        RandomAccessFile(f, "r").use { raf ->
            val overlap = needle.size - 1
            var carry = ByteArray(0)
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                val merged = if (carry.isEmpty()) buf.copyOf(n) else carry + buf.copyOf(n)
                if (indexOfSub(merged, needle) >= 0) return@use true
                carry = if (overlap > 0 && merged.size > overlap) {
                    merged.copyOfRange(merged.size - overlap, merged.size)
                } else {
                    ByteArray(0)
                }
            }
            false
        }
    }.getOrDefault(false)

    /** ByteArray 子序列查找（显式命名，避开标准库 indexOf(Byte) 的解析歧义）。 */
    private fun indexOfSub(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        if (needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
