package com.mcai.ubuntudsu.core.ota

import android.util.Log
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 在线提取（远程直读）引擎。
 *
 * 设计目标：**不下载整包**。通过 HTTP Range 只读取：
 *   1. zip 尾部中央目录（几百 KB）→ 定位 payload.bin 的绝对偏移；
 *   2. payload 头部 + manifest（几百 KB）→ 分区表与每个分区的安装操作；
 *   3. 用户勾选分区的数据块（几十 MB 级别），边下边解压边写盘。
 *
 * 一个 12GB 的卡刷包，只取 boot / dtbo / vbmeta 时实际流量通常只有几十 MB。
 */
class CancelledException : IOException("任务已取消")

// ==================== HTTP Range ====================

/**
 * 断点式 HTTP Range 读取器。所有读取都落在 [offset, offset+len) 区间，
 * 不会把整个文件读入内存或磁盘。
 */
class HttpRangeReader(private val url: String) : Closeable {

    var size: Long = -1
        private set

    /** 累计通过本读取器下载的字节数（用于进度显示） */
    val downloaded = AtomicLong(0)

    private var opened = false

    fun connect() {
        if (opened) return
        val conn = openConnection(0, 0)
        try {
            val code = conn.responseCode
            when {
                code == 206 -> {
                    val cr = conn.getHeaderField("Content-Range")
                    val total = cr?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1
                    if (total <= 0) throw IOException("服务器未返回文件大小（Content-Range=$cr）")
                    size = total
                }
                code == 200 -> {
                    // 服务器忽略了 Range：说明不支持随机读取，不能在线提取
                    throw IOException("该下载服务器不支持分段(range)请求，无法在线提取")
                }
                else -> throw IOException("HTTP $code")
            }
        } finally {
            runCatching { conn.inputStream.close() }
            conn.disconnect()
        }
        opened = true
        Log.d(TAG, "connect ok size=$size")
    }

    fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (!opened) connect()
        if (offset < 0 || (size > 0 && offset + length > size)) throw IOException("读取越界")
        var attempt = 0
        while (true) {
            try {
                val conn = openConnection(offset, offset + length - 1)
                try {
                    val code = conn.responseCode
                    if (code != 206) throw IOException("HTTP $code")
                    val buf = ByteArray(length)
                    var read = 0
                    conn.inputStream.use { ins ->
                        while (read < length) {
                            val n = ins.read(buf, read, length - read)
                            if (n < 0) throw IOException("连接提前结束")
                            read += n
                        }
                    }
                    downloaded.addAndGet(length.toLong())
                    return buf
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                attempt++
                if (attempt >= 3) throw IOException("读取失败：${e.message}", e)
                runCatching { Thread.sleep(350L * attempt) }
            }
        }
    }

    /** 顺序流：[offset, offset+length)，内部按 [CHUNK] 分块请求，带流量计数 */
    fun openStream(offset: Long, length: Long): InputStream = RangeStream(offset, length)

    private inner class RangeStream(private val start: Long, private val total: Long) : InputStream() {
        private var consumed = 0L
        private var buf = ByteArray(0)
        private var bufPos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (consumed >= total) return -1
            if (bufPos >= buf.size) {
                val remain = total - consumed
                if (remain <= 0) return -1
                buf = readAt(start + consumed, minOf(CHUNK.toLong(), remain).toInt())
                bufPos = 0
                if (buf.isEmpty()) return -1
            }
            val n = minOf(len, buf.size - bufPos, (total - consumed).toInt())
            System.arraycopy(buf, bufPos, b, off, n)
            bufPos += n
            consumed += n
            return n
        }

        override fun close() {
            buf = ByteArray(0)
        }
    }

    private fun openConnection(from: Long, to: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=$from-$to")
        }

    override fun close() {
        // 每个请求独立连接，这里无需释放；保留接口便于日后复用连接池
    }

    companion object {
        private const val TAG = "HttpRange"
        private const val CHUNK = 2 * 1024 * 1024
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }
}

// ==================== 包结构 / 分区表 ====================

class RemotePackage private constructor(
    val reader: HttpRangeReader,
    val url: String,
    /** 整个卡刷包大小 */
    val packageSize: Long,
    /** payload.bin 数据在文件中的绝对起始偏移 */
    val payloadStart: Long,
    /** payload 数据区相对 payload 起点的偏移（头部 + manifest + metadata 签名之后） */
    val dataBase: Long,
    val blockSize: Int,
    val partitions: List<Partition>,
) {

    data class Op(
        val type: Int,
        val dataOffset: Long,
        val dataLength: Long,
        val dstExtents: List<Extent>,
    ) {
        val outBlocks: Long get() = dstExtents.sumOf { it.numBlocks }
    }

    data class Extent(val startBlock: Long, val numBlocks: Long)

    class Partition(
        val name: String,
        val size: Long,
        val hash: String?,
        val ops: List<Op>,
        val incremental: Boolean,
    ) {
        /** 该分区需要从网络拉取的字节数（压缩后） */
        val downloadBytes: Long = ops.sumOf { it.dataLength }

        /** 不支持的安装操作类型（增量/差分镜像） */
        val unsupportedTypes: List<Int> = ops.map { it.type }.filter { it !in RemotePackage.SUPPORTED_TYPES }.distinct()

        val supported: Boolean get() = unsupportedTypes.isEmpty() && !incremental
    }

    fun dataOffsetOf(op: Op): Long = payloadStart + dataBase + op.dataOffset

    /**
     * 抽取单个分区到 [outFile]，返回写入字节数。
     * 全程流式：下载一块 → 解压一块 → 写一块，内存占用固定。
     */
    fun extract(
        part: Partition,
        outFile: File,
        isCancelled: () -> Boolean,
    ): Long {
        if (!part.supported) {
            throw IOException("该分区使用了增量/不支持的压缩格式（操作类型 ${part.unsupportedTypes}）")
        }
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()
        var written = 0L
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(0)
            var i = 0
            while (i < part.ops.size) {
                if (isCancelled()) throw CancelledException()
                val op = part.ops[i]
                if (op.dataLength <= 0L) {
                    // ZERO / DISCARD：不占网络流量
                    if (op.type == TYPE_ZERO) written += writeZeros(raf, op, blockSize, part.size)
                    i++
                    continue
                }
                // 把「数据区连续」的后续操作合并成一次网络请求，显著减少请求数
                var j = i
                while (j + 1 < part.ops.size) {
                    val next = part.ops[j + 1]
                    if (next.dataLength <= 0L) break
                    if (next.dataOffset != part.ops[j].dataOffset + part.ops[j].dataLength) break
                    j++
                }
                val runStart = part.ops[i].dataOffset
                val runEnd = part.ops[j].dataOffset + part.ops[j].dataLength
                val stream = reader.openStream(payloadStart + dataBase + runStart, runEnd - runStart)
                try {
                    for (k in i..j) {
                        if (isCancelled()) throw CancelledException()
                        val cur = part.ops[k]
                        val limited = LimitedInputStream(stream, cur.dataLength)
                        val input: InputStream = when (cur.type) {
                            TYPE_REPLACE -> limited
                            TYPE_REPLACE_BZ -> BZip2CompressorInputStream(limited, false)
                            TYPE_REPLACE_XZ -> XZInputStream(limited)
                            else -> throw IOException("不支持的操作类型 ${cur.type}")
                        }
                        written += writeExtents(raf, cur, blockSize, part.size, input, isCancelled)
                        runCatching { if (input !== limited) input.close() }
                        limited.drain()
                    }
                } finally {
                    runCatching { stream.close() }
                }
                i = j + 1
            }
            if (part.size > 0 && raf.length() != part.size) raf.setLength(part.size)
        }
        return written
    }

    /** 校验输出镜像 sha256 是否与 payload 声明一致（null 表示无法校验） */
    fun verify(part: Partition, file: File): Boolean? {
        val expect = part.hash ?: return null
        if (!file.isFile || file.length() == 0L) return false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == expect.lowercase()
    }

    // ---- 写入 ----

    private fun writeExtents(
        raf: RandomAccessFile,
        op: Op,
        blockSize: Int,
        partSize: Long,
        input: InputStream,
        isCancelled: () -> Boolean,
    ): Long {
        val buf = ByteArray(1 shl 20)
        var written = 0L
        for (ext in op.dstExtents) {
            val pos = ext.startBlock * blockSize
            if (partSize > 0 && pos >= partSize) continue
            var remain = ext.numBlocks * blockSize
            if (partSize > 0 && pos + remain > partSize) remain = partSize - pos
            raf.seek(pos)
            while (remain > 0) {
                if (isCancelled()) throw CancelledException()
                val want = minOf(remain, buf.size.toLong()).toInt()
                val n = input.read(buf, 0, want)
                if (n <= 0) {
                    // 数据比块少：剩余补零，保证镜像长度正确
                    val zeros = ByteArray(want)
                    raf.write(zeros, 0, want)
                    written += want
                    remain -= want
                    continue
                }
                raf.write(buf, 0, n)
                written += n
                remain -= n
            }
        }
        // 解压后多余字节丢弃（截断由 setLength 兜底）
        return written
    }

    private fun writeZeros(raf: RandomAccessFile, op: Op, blockSize: Int, partSize: Long): Long {
        val buf = ByteArray(1 shl 16)
        var written = 0L
        for (ext in op.dstExtents) {
            val pos = ext.startBlock * blockSize
            if (partSize > 0 && pos >= partSize) continue
            var remain = ext.numBlocks * blockSize
            if (partSize > 0 && pos + remain > partSize) remain = partSize - pos
            raf.seek(pos)
            while (remain > 0) {
                val n = minOf(remain, buf.size.toLong()).toInt()
                raf.write(buf, 0, n)
                written += n
                remain -= n
            }
        }
        return written
    }

    /** 限制长度的子流：保证每个压缩块只读到自己的数据边界 */
    private class LimitedInputStream(private val src: InputStream, var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val v = src.read()
            if (v >= 0) remaining--
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        fun drain() {
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = src.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) { remaining = 0; break }
                remaining -= n
            }
        }
    }

    companion object {
        private const val TAG = "RemotePackage"

        const val TYPE_REPLACE = 0
        const val TYPE_REPLACE_BZ = 1
        const val TYPE_ZERO = 6
        const val TYPE_DISCARD = 7
        const val TYPE_REPLACE_XZ = 8

        val SUPPORTED_TYPES = setOf(TYPE_REPLACE, TYPE_REPLACE_BZ, TYPE_REPLACE_XZ, TYPE_ZERO, TYPE_DISCARD)

        /**
         * 打开远程固件包（不下载整包）。
         * @param onStage 阶段提示回调（在调用线程执行，通常是 IO 线程）
         */
        fun open(url: String, onStage: (String) -> Unit = {}): RemotePackage {
            val reader = HttpRangeReader(url)
            onStage("正在连接服务器…")
            reader.connect()
            val total = reader.size

            onStage("正在读取固件包结构…")
            val head = reader.readAt(0, 4)
            val payloadStart: Long
            val payloadSize: Long
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                val entry = findPayloadEntry(reader, total)
                payloadStart = entry.first
                payloadSize = entry.second
                Log.d(TAG, "payload.bin offset=$payloadStart size=$payloadSize")
            } else if (head[0] == 'C'.code.toByte() && head[1] == 'r'.code.toByte()) {
                payloadStart = 0
                payloadSize = total
            } else {
                throw IOException("暂不支持该固件格式（既不是 zip 卡刷包，也不是裸 payload.bin）")
            }

            onStage("正在解析 payload 分区表…")
            val hdr = reader.readAt(payloadStart, 24)
            if (hdr[0] != 'C'.code.toByte() || hdr[1] != 'r'.code.toByte() ||
                hdr[2] != 'A'.code.toByte() || hdr[3] != 'U'.code.toByte()
            ) {
                throw IOException("payload 头部无效，可能不是标准 OTA 包")
            }
            // 头部布局：magic(4) + 版本(8) + manifest_len(8) + metadata_signature_len(4)
            // v1 没有版本字段：magic(4) + manifest_len(8)
            val version = be64(hdr, 4)
            val headerLen: Long
            val manifestLen: Long
            var metaSigLen = 0L
            if (version in 2..10) {
                headerLen = 24
                manifestLen = be64(hdr, 12)
                metaSigLen = be32(hdr, 20)
            } else {
                headerLen = 12
                manifestLen = version
            }
            if (manifestLen <= 0 || manifestLen > 64L * 1024 * 1024) throw IOException("manifest 长度异常：$manifestLen")
            if (metaSigLen < 0 || metaSigLen > 16L * 1024 * 1024) throw IOException("签名长度异常：$metaSigLen")
            val manifest = reader.readAt(payloadStart + headerLen, manifestLen.toInt())
            // 数据区 = 头部 + manifest + metadata 签名；payload 签名位于文件末尾，不参与偏移计算
            val dataBase = headerLen + manifestLen + metaSigLen
            val parsed = parseManifest(manifest)
            if (parsed.second.isEmpty()) throw IOException("未解析到任何分区，可能是非 payload 型固件包")
            if (payloadStart + payloadSize > reader.size) throw IOException("payload 大小与文件不符")
            return RemotePackage(reader, url, total, payloadStart, dataBase, parsed.first, parsed.second)
        }

        // ---- zip 解析 ----

        /** 返回 payload.bin 在文件中的 (数据起始绝对偏移, 数据长度) */
        private fun findPayloadEntry(reader: HttpRangeReader, total: Long): Pair<Long, Long> {
            val tailLen = minOf(total, 512L * 1024).toInt()
            val tailOff = total - tailLen
            val tail = reader.readAt(tailOff, tailLen)

            val eocdPos = lastIndexOf(tail, byteArrayOf(0x50, 0x4b, 0x05, 0x06))
            if (eocdPos < 0) throw IOException("不是有效的 zip 包（未找到目录结尾标记）")
            var cdSize = le32(tail, eocdPos + 12).toLong() and 0xFFFFFFFFL
            var cdOffset = le32(tail, eocdPos + 16).toLong() and 0xFFFFFFFFL

            // Zip64：目录偏移/大小写成 0xFFFFFFFF 时，真实值在 Zip64 EOCD 里
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
                val z64 = lastIndexOf(tail, byteArrayOf(0x50, 0x4b, 0x06, 0x06))
                if (z64 >= 0) {
                    cdSize = le64(tail, z64 + 40)
                    cdOffset = le64(tail, z64 + 48)
                }
            }
            if (cdOffset <= 0 || cdSize <= 0 || cdSize > 64L * 1024 * 1024) {
                throw IOException("zip 中央目录异常（offset=$cdOffset size=$cdSize）")
            }

            val cd = reader.readAt(cdOffset, cdSize.toInt())
            var p = 0
            var fallback: Pair<Long, Long>? = null
            while (p + 46 <= cd.size) {
                if (!(cd[p] == 0x50.toByte() && cd[p + 1] == 0x4b.toByte() &&
                        cd[p + 2] == 0x01.toByte() && cd[p + 3] == 0x02.toByte())
                ) break
                val method = le16(cd, p + 10)
                var csize = le32(cd, p + 20).toLong() and 0xFFFFFFFFL
                var usize = le32(cd, p + 24).toLong() and 0xFFFFFFFFL
                val nameLen = le16(cd, p + 28)
                val extraLen = le16(cd, p + 30)
                val commentLen = le16(cd, p + 32)
                var lho = le32(cd, p + 42).toLong() and 0xFFFFFFFFL
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                val extraStart = p + 46 + nameLen
                if (usize == 0xFFFFFFFFL || csize == 0xFFFFFFFFL || lho == 0xFFFFFFFFL) {
                    val z = parseZip64(extra = cd, start = extraStart, len = extraLen,
                        needUsize = usize == 0xFFFFFFFFL, needCsize = csize == 0xFFFFFFFFL,
                        needOffset = lho == 0xFFFFFFFFL)
                    if (z.size >= 1) {
                        var idx = 0
                        if (usize == 0xFFFFFFFFL) { usize = z[idx]; idx++ }
                        if (csize == 0xFFFFFFFFL) { csize = z[idx]; idx++ }
                        if (lho == 0xFFFFFFFFL) { lho = z[idx]; idx++ }
                    }
                }
                val isPayload = name.equals("payload.bin", true)
                val candidate = name.lowercase().endsWith(".bin") && csize > 64L * 1024 * 1024
                if (isPayload || candidate) {
                    // 读本地文件头，拿到真实数据偏移
                    val lh = reader.readAt(lho, 30)
                    if (!(lh[0] == 0x50.toByte() && lh[1] == 0x4b.toByte() &&
                            lh[2] == 0x03.toByte() && lh[3] == 0x04.toByte())
                    ) throw IOException("payload.bin 本地头无效")
                    val lNameLen = le16(lh, 26)
                    val lExtraLen = le16(lh, 28)
                    val dataOff = lho + 30 + lNameLen + lExtraLen
                    val dataLen = if (csize > 0) csize else usize
                    if (isPayload) {
                        if (method != 0) {
                            throw IOException("该包的 payload.bin 被压缩存储，无法随机读取（不支持在线提取）")
                        }
                        return dataOff to dataLen
                    }
                    if (fallback == null && method == 0) fallback = dataOff to dataLen
                }
                p = extraStart + extraLen + commentLen
            }
            return fallback ?: throw IOException("固件包内没有找到 payload.bin")
        }

        private fun parseZip64(extra: ByteArray, start: Int, len: Int,
                               needUsize: Boolean, needCsize: Boolean, needOffset: Boolean): LongArray {
            var p = start
            val end = start + len
            while (p + 4 <= end) {
                val id = le16(extra, p)
                val sz = le16(extra, p + 2)
                val dataStart = p + 4
                if (id == 0x0001) {
                    val out = ArrayList<Long>()
                    var q = dataStart
                    if (needUsize && q + 8 <= dataStart + sz) { out.add(le64(extra, q)); q += 8 }
                    if (needCsize && q + 8 <= dataStart + sz) { out.add(le64(extra, q)); q += 8 }
                    if (needOffset && q + 8 <= dataStart + sz) { out.add(le64(extra, q)); q += 8 }
                    return out.toLongArray()
                }
                p = dataStart + sz
            }
            return LongArray(0)
        }

        // ---- manifest 解析 ----

        private fun parseManifest(m: ByteArray): Pair<Int, List<Partition>> {
            var blockSize = 4096
            val parts = ArrayList<Partition>()
            var i = 0
            while (i < m.size) {
                val tag = readVarint(m, i) ?: break
                i = tag.second
                val field = (tag.first ushr 3).toInt()
                val wt = (tag.first and 7).toInt()
                if (wt == 2) {
                    val len = readVarint(m, i) ?: break
                    i = len.second
                    val l = len.first.toInt()
                    if (l < 0 || i + l > m.size) break
                    if (field == 13) parsePartition(m, i, i + l)?.let { parts.add(it) }
                    i += l
                } else if (wt == 0) {
                    val v = readVarint(m, i) ?: break
                    i = v.second
                    if (field == 3) blockSize = v.first.toInt().takeIf { it in 512..1024 * 1024 } ?: 4096
                } else {
                    val skipped = skipField(m, i, wt) ?: break
                    i = skipped
                }
            }
            return blockSize to parts
        }

        private fun parsePartition(b: ByteArray, from: Int, to: Int): Partition? {
            var name: String? = null
            var size = 0L
            var hash: String? = null
            var incremental = false
            val ops = ArrayList<Op>()
            var i = from
            while (i < to) {
                val tag = readVarint(b, i) ?: break
                i = tag.second
                val field = (tag.first ushr 3).toInt()
                val wt = (tag.first and 7).toInt()
                if (wt == 2) {
                    val len = readVarint(b, i) ?: break
                    i = len.second
                    val l = len.first.toInt()
                    if (l < 0 || i + l > to) break
                    when (field) {
                        1 -> if (name == null) name = String(b, i, l, Charsets.UTF_8)
                        6 -> incremental = true
                        7 -> {
                            var k = i
                            while (k < i + l) {
                                val t2 = readVarint(b, k) ?: break
                                k = t2.second
                                val f2 = (t2.first ushr 3).toInt()
                                val w2 = (t2.first and 7).toInt()
                                if (w2 == 0) {
                                    val v = readVarint(b, k) ?: break
                                    k = v.second
                                    if (f2 == 1) size = v.first
                                } else if (w2 == 2) {
                                    val ln = readVarint(b, k) ?: break
                                    k = ln.second
                                    val ll = ln.first.toInt()
                                    if (f2 == 2 && ll in 1..64) hash = b.copyOfRange(k, k + ll).joinToString("") { "%02x".format(it) }
                                    k += ll
                                } else {
                                    k = skipField(b, k, w2) ?: break
                                }
                            }
                        }
                        8, 2 -> parseOp(b, i, i + l)?.let { ops.add(it) }
                    }
                    i += l
                } else if (wt == 0) {
                    val v = readVarint(b, i) ?: break
                    i = v.second
                } else {
                    i = skipField(b, i, wt) ?: break
                }
            }
            val n = name ?: return null
            return Partition(n, size, hash, ops, incremental)
        }

        private fun parseOp(b: ByteArray, from: Int, to: Int): Op? {
            var type = -1
            var dataOffset = 0L
            var dataLength = 0L
            val dst = ArrayList<Extent>()
            var i = from
            while (i < to) {
                val tag = readVarint(b, i) ?: break
                i = tag.second
                val field = (tag.first ushr 3).toInt()
                val wt = (tag.first and 7).toInt()
                if (wt == 0) {
                    val v = readVarint(b, i) ?: break
                    i = v.second
                    when (field) {
                        1 -> type = v.first.toInt()
                        2 -> dataOffset = v.first
                        3 -> dataLength = v.first
                    }
                } else if (wt == 2) {
                    val len = readVarint(b, i) ?: break
                    i = len.second
                    val l = len.first.toInt()
                    if (l < 0 || i + l > to) break
                    if (field == 6) {
                        var sb = 0L
                        var nb = 0L
                        var k = i
                        while (k < i + l) {
                            val t2 = readVarint(b, k) ?: break
                            k = t2.second
                            val f2 = (t2.first ushr 3).toInt()
                            val w2 = (t2.first and 7).toInt()
                            if (w2 == 0) {
                                val v = readVarint(b, k) ?: break
                                k = v.second
                                if (f2 == 1) sb = v.first else if (f2 == 2) nb = v.first
                            } else {
                                k = skipField(b, k, w2) ?: break
                            }
                        }
                        if (nb > 0) dst.add(Extent(sb, nb))
                    }
                    i += l
                } else {
                    i = skipField(b, i, wt) ?: break
                }
            }
            if (type < 0) return null
            return Op(type, dataOffset, dataLength, dst)
        }

        // ---- 基础读取 ----

        private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int>? {
            var i = start
            var shift = 0
            var v = 0L
            while (i < b.size && shift < 64) {
                val byte = b[i++].toInt() and 0xFF
                v = v or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return v to i
                shift += 7
            }
            return null
        }

        private fun skipField(b: ByteArray, i: Int, wt: Int): Int? = when (wt) {
            1 -> (i + 8).takeIf { it <= b.size }
            5 -> (i + 4).takeIf { it <= b.size }
            else -> null
        }

        private fun lastIndexOf(data: ByteArray, pat: ByteArray): Int {
            for (i in data.size - pat.size downTo 0) {
                var ok = true
                for (k in pat.indices) if (data[i + k] != pat[k]) { ok = false; break }
                if (ok) return i
            }
            return -1
        }

        private fun be64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
            return v
        }

        private fun be32(b: ByteArray, off: Int): Long {
            var v = 0L
            for (k in 0 until 4) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
            return v
        }

        private fun le16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun le64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (k in 7 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
            return v
        }
    }
}
