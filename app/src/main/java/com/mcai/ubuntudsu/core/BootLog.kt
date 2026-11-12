package com.mcai.ubuntudsu.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * boot.log 的读取与去噪。
 *
 * guest 调试串口在 exec 之后会持续吐不带换行的 '!' 填充（真机实测单段可达
 * 138 万字符 / 全篇 174 万个），并且夹杂在 VNC 心跳行中间。旧实现只读尾部
 * 256 KiB 字节窗口，一旦尾部被这串 '!' 占满，折叠后有效信息为 0 行 ——
 * 启动里程碑全部看不见。
 *
 * v2.0.34：改为「分块流式扫描 + 把连续 '!' 当作换行分隔符打捞夹杂文本」，
 * 内存恒定（CHUNK + 有效行列表），绝不整份读入，也不受洪流长度影响。
 *
 * 本类只负责「读」，不碰写入链路、看护脚本、启动脚本与启动链，
 * 因此 [containsMarker] 的判活语义一字未改。
 */
object BootLog {

    private const val WINDOW = 256 * 1024
    private const val CHUNK = 256 * 1024
    private const val SCAN_CAP = 8 * 1024 * 1024   // 单文件最多扫描 8 MiB
    private const val MAX_LINES = 260              // 展示上限：头 60 + 尾 200

    /** 看护脚本轮转时保留的上一轮尾部文件名。 */
    const val ROTATED_TAIL = "boot.log.tail"

    // ------------------------------------------------------------------ //
    // 展示用：打捞被 '!' 洪流淹没的有效行
    // ------------------------------------------------------------------ //

    /**
     * 分块流式读取，把连续 >=2 个 '!' 折叠成一个换行分隔符，
     * 从而把夹杂在洪流里的真实日志（IONVRAM / autoconf / IOFindBSDRoot …）打捞成独立行。
     * 单个 '!' 保留（可能是真实内容）；UTF-8 多字节按字节缓存后整行解码，中文不乱码。
     *
     * @param onlyTail true 时只读文件尾部 cap 字节（给高频实时页用）。
     */
    private fun usefulLines(f: File?, cap: Int, onlyTail: Boolean): List<String> = runCatching {
        if (f == null || !f.isFile) return@runCatching emptyList<String>()
        val out = ArrayList<String>()
        val line = ByteArrayOutputStream()
        var bangRun = 0
        var scanned = 0
        RandomAccessFile(f, "r").use { raf ->
            if (onlyTail) raf.seek((raf.length() - cap).coerceAtLeast(0L))
            val buf = ByteArray(CHUNK)
            while (scanned < cap) {
                val n = raf.read(buf)
                if (n <= 0) break
                scanned += n
                for (i in 0 until n) {
                    val c = buf[i].toInt() and 0xff
                    if (c == 0x21) {                 // '!'
                        bangRun++
                        continue
                    }
                    if (bangRun >= 2) flushLine(out, line)
                    else if (bangRun == 1) line.write(0x21)
                    bangRun = 0
                    if (c == 0x0a) flushLine(out, line)
                    else if (c != 0x0d) line.write(c)
                }
            }
        }
        if (bangRun == 1) line.write(0x21)
        flushLine(out, line)
        out
    }.getOrDefault(emptyList())

    /** 结算一行：解码、去噪判定、超长截断。 */
    private fun flushLine(out: MutableList<String>, line: ByteArrayOutputStream) {
        val raw = String(line.toByteArray(), Charsets.UTF_8).trim()
        line.reset()
        if (raw.isEmpty()) return
        if (isVncNoise(raw)) return
        out.add(if (raw.length > 400) raw.take(400) + " …[超长行已截断]" else raw)
    }

    /** LibVNCServer 心跳/统计行 —— 每 2 秒一条，纯噪音，在 render 里统一计数。 */
    private fun isVncNoise(s: String): Boolean {
        // 洪流里常混进单个 '!' 前缀；剥掉后再判断。
        val t = if (s.startsWith("!")) s.substring(1) else s
        // 时间戳格式 DD/MM/YYYY HH:MM:SS（不写死具体日期，换天上机照样生效）。
        val hasTs = t.length >= 19 && t[2] == '/' && t[5] == '/' &&
            t[10] == ' ' && t[13] == ':' && t[16] == ':'
        if (!hasTs) {
            // 无时间戳的心跳尾巴（可能被 '!' 截断成 "0 other clients"）。
            return t == "0 other clients" || t.startsWith("0 other clients") ||
                t.contains("other clients")
        }
        return t.contains("Statistics") || t.contains("TOTALS") ||
            t.contains("other clients") || t.contains("rfbProcessClient") ||
            t.contains("Client 127.") || t.contains("Client Protocol Version") ||
            t.contains("Pixel format for client") || t.contains("bpp, depth") ||
            t.contains("true colour") || t.contains("no translation") ||
            t.contains("Enabling LastRect") || t.contains("Enabling NewFBSize") ||
            t.contains("full-color cursor") || t.contains("Using raw encoding") ||
            t.contains("Protocol version sent") || t.contains("securityResult")
    }

    /** 把有效行整理成展示文本：折叠连续重复行、限制总行数。 */
    private fun render(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val out = ArrayList<String>()
        var last: String? = null
        var repeats = 0
        fun flushRepeat() {
            if (repeats > 1) out.add("    … 上一行连续重复 ${repeats - 1} 次（已折叠）")
            repeats = 0
        }
        for (ln in lines) {
            if (ln == last) { repeats++; continue }
            flushRepeat()
            out.add(ln); last = ln; repeats = 1
        }
        flushRepeat()
        if (out.size <= MAX_LINES) return out.joinToString("\n")
        val head = out.take(60)
        val tail = out.takeLast(200)
        return (head + listOf("    … 中间 ${out.size - 260} 行已省略（保留启动里程碑 + 最近输出）…") + tail)
            .joinToString("\n")
    }

    /**
     * 全文打捞（含轮转尾部），供「日志」弹框与「复制日志」使用。
     * window 参数保留以兼容调用点，作为软上限不再截断里程碑。
     */
    fun readClean(f: File?, window: Int = WINDOW): String {
        val lines = ArrayList<String>()
        f?.parentFile?.let { p ->
            val t = File(p, ROTATED_TAIL)
            if (t.isFile) lines.addAll(usefulLines(t, SCAN_CAP, onlyTail = false))
        }
        lines.addAll(usefulLines(f, SCAN_CAP, onlyTail = false))
        return render(lines)
    }

    /**
     * 高频实时页（每 600 ms）用：只读尾部 4×window 字节，
     * 剥离洪流后取有效行，避免每次全文扫描。
     */
    fun readTail(f: File?, window: Int = WINDOW): String =
        render(usefulLines(f, (window * 4).coerceAtMost(SCAN_CAP), onlyTail = true))

    /**
     * 对已打捞文本的二次整理（幂等）：折叠残留连续 '!'、超长行、重复行。
     * GunyahBootActivity.renderLog 仍会调用它，保持兼容。
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
            if (line == last) { repeats++; continue }
            flushRepeat()
            out.add(line); last = line; repeats = 1
        }
        flushRepeat()
        return out.joinToString("\n")
    }

    // ------------------------------------------------------------------ //
    // 判活用：全文分块流式查标记 —— 语义一字未改，不受上面去噪影响
    // ------------------------------------------------------------------ //

    private const val CHUNK_MARK = 256 * 1024

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
            val buf = ByteArray(CHUNK_MARK)
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
