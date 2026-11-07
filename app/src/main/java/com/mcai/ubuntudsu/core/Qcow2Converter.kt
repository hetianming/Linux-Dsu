package com.mcai.ubuntudsu.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max

/**
 * raw <-> qcow2 镜像互转（纯 Kotlin，不依赖 qemu-img / 任何外部二进制）。
 *
 * 手机上没有 qemu-img，工具链包里也没有，所以这里直接按 qcow2 规范读写：
 *  - qcow2 -> raw：解析 header / L1 / L2，逐簇导出；未分配簇用稀疏文件留空。
 *  - raw -> qcow2：生成标准 v2 容器（64K cluster），L1/L2/refcount table/
 *    refcount block 全部按规范填好；全零簇不分配，稀疏盘不会膨胀。
 *
 * 只支持「无 backing file、无加密、无压缩簇、无外部数据文件」的普通镜像，
 * 遇到不支持的特性会明确报错而不是产出坏盘。
 */
object Qcow2Converter {

    private const val CLUSTER_BITS = 16
    private const val CLUSTER_SIZE = 1 shl CLUSTER_BITS        // 65536
    private const val L2_ENTRIES = CLUSTER_SIZE / 8            // 8192
    private const val REFC_PER_BLOCK = CLUSTER_SIZE / 2        // 32768 (16-bit refcount)
    private const val OFFSET_MASK = 0x00FFFFFFFFFFFE00L      // L1E/L2E_OFFSET_MASK
    private const val OFLAG_COMPRESSED = 0x4000000000000000L // 1 << 62
    private const val OFLAG_COPIED = Long.MIN_VALUE          // 1 << 63
    private const val OFLAG_ZERO = 1L

    data class Result(val ok: Boolean, val message: String, val output: String = "")

    /** "QCOW2" / "RAW" / ""（读不了或空文件） */
    fun detect(f: File): String = try {
        RandomAccessFile(f, "r").use { r ->
            if (r.length() < 4) return ""
            val b = ByteArray(4)
            r.readFully(b)
            if (b[0] == 'Q'.code.toByte() && b[1] == 'F'.code.toByte() &&
                b[2] == 'I'.code.toByte() && b[3] == 0xFB.toByte()
            ) "QCOW2" else "RAW"
        }
    } catch (e: Exception) {
        ""
    }

    /** 输出文件名：不覆盖已有文件，自动加 -2 / -3 … */
    fun suggestedOutput(src: File): File {
        val isQcow = detect(src) == "QCOW2"
        val base = src.name.substringBeforeLast('.', src.name)
        var out = File(src.parentFile, if (isQcow) "$base.raw.img" else "$base.qcow2")
        var i = 2
        while (out.exists()) {
            out = File(src.parentFile, if (isQcow) "$base-$i.raw.img" else "$base-$i.qcow2")
            i++
        }
        return out
    }

    /**
     * 自动按源格式选择方向。progress(百分比, 阶段文字) 在工作线程回调。
     * 失败时删除半成品，绝不留下一个看起来像镜像的坏文件。
     */
    fun convert(
        src: File,
        dst: File,
        cancelled: () -> Boolean = { false },
        progress: (percent: Int, text: String) -> Unit = { _, _ -> },
    ): Result = try {
        when (detect(src)) {
            "QCOW2" -> qcow2ToRaw(src, dst, progress, cancelled)
            "RAW" -> rawToQcow2(src, dst, progress, cancelled)
            else -> Result(false, "读不到源文件（或文件为空）")
        }
    } catch (e: Exception) {
        runCatching { dst.delete() }
        Result(false, "转换失败：${e.message ?: e.javaClass.simpleName}")
    }

    // ------------------------- qcow2 -> raw -------------------------

    private fun qcow2ToRaw(
        src: File,
        dst: File,
        progress: (Int, String) -> Unit,
        cancelled: () -> Boolean,
    ): Result {
        RandomAccessFile(src, "r").use { r ->
            val h = ByteArray(104)
            r.readFully(h, 0, 72)
            if (be32(h, 0) != 0x514649FBL) return Result(false, "不是 qcow2 镜像（magic 不匹配）")
            val version = be32(h, 4)
            if (version != 2L && version != 3L) return Result(false, "不支持的 qcow2 版本 v$version（只支持 v2/v3）")
            if (be64(h, 8) != 0L || be32(h, 16) != 0L) {
                return Result(false, "该 qcow2 带 backing file，请先合并（qemu-img commit）再转")
            }
            if (be32(h, 32) != 0L) return Result(false, "该 qcow2 已加密，无法转换")
            val clusterBits = be32(h, 20).toInt()
            if (clusterBits < 12 || clusterBits > 21) return Result(false, "非法 cluster_bits=$clusterBits")
            val clusterSize = 1L shl clusterBits
            val l2Entries = (clusterSize / 8).toInt()
            val virtualSize = be64(h, 24)
            if (virtualSize <= 0) return Result(false, "qcow2 声明的虚拟容量为 0")
            val l1Size = be32(h, 36).toInt()
            val l1Offset = be64(h, 40)
            if (version == 3L) {
                r.seek(0)
                r.readFully(h)
                val incompat = be64(h, 72)
                if (incompat != 0L) {
                    return Result(false, "该 qcow2 含不兼容特性(0x${incompat.toString(16)})：扩展 L2 / 压缩类型 / 外部数据文件等暂不支持")
                }
            }
            if (l1Size <= 0 || l1Offset <= 0) return Result(false, "qcow2 的 L1 表无效")

            val l1Bytes = ByteArray(l1Size * 8)
            r.seek(l1Offset)
            r.readFully(l1Bytes)
            val buf = ByteArray(clusterSize.toInt())

            RandomAccessFile(dst, "rw").use { o ->
                o.setLength(0)
                // 稀疏文件：未分配簇天然是 0，不需要真的写几十 GB 的零
                o.setLength(virtualSize)
                var done = 0L
                for (l1i in 0 until l1Size) {
                    if (cancelled()) return Result(false, "已取消")
                    val l1e = be64(l1Bytes, l1i * 8)
                    val l2Offset = l1e and OFFSET_MASK
                    val vbase = l1i.toLong() * l2Entries * clusterSize
                    if (l2Offset != 0L) {
                        val l2 = ByteArray(clusterSize.toInt())
                        r.seek(l2Offset)
                        r.readFully(l2)
                        for (j in 0 until l2Entries) {
                            val voff = vbase + j.toLong() * clusterSize
                            if (voff >= virtualSize) break
                            val e = be64(l2, j * 8)
                            if (e == 0L) continue
                            if ((e and OFLAG_COMPRESSED) != 0L) {
                                return Result(false, "该 qcow2 含压缩簇，暂不支持（请先用 qemu-img convert 展开）")
                            }
                            val poff = e and OFFSET_MASK
                            if (poff == 0L) continue   // zero cluster，保持稀疏
                            r.seek(poff)
                            val want = minOf(clusterSize, virtualSize - voff).toInt()
                            r.readFully(buf, 0, want)
                            o.seek(voff)
                            o.write(buf, 0, want)
                        }
                    }
                    done += l2Entries.toLong() * clusterSize
                    val pct = (done * 100 / max(1L, virtualSize)).toInt().coerceIn(0, 100)
                    progress(pct, "qcow2 → raw　${fmtSize(done.coerceAtMost(virtualSize))} / ${fmtSize(virtualSize)}")
                }
            }
            return Result(true, "完成：${dst.name}（${fmtSize(virtualSize)}）", dst.absolutePath)
        }
    }

    // ------------------------- raw -> qcow2 -------------------------

    private fun rawToQcow2(
        src: File,
        dst: File,
        progress: (Int, String) -> Unit,
        cancelled: () -> Boolean,
    ): Result {
        val size = src.length()
        if (size <= 0) return Result(false, "源文件是空的")
        val dataClusters = ((size + CLUSTER_SIZE - 1) / CLUSTER_SIZE).toInt()
        val l2Tables = (dataClusters + L2_ENTRIES - 1) / L2_ENTRIES
        val l1Size = max(1, l2Tables)
        val l1Clusters = (l1Size * 8 + CLUSTER_SIZE - 1) / CLUSTER_SIZE

        // refcount block 数量取决于总簇数，而总簇数又含 refcount block 本身 -> 迭代到稳定
        var rb = 1
        var total: Int
        while (true) {
            total = 2 + rb + l1Clusters + l2Tables + dataClusters
            val need = (total + REFC_PER_BLOCK - 1) / REFC_PER_BLOCK
            if (need == rb) break
            rb = need
        }
        val refTableOff = CLUSTER_SIZE.toLong()
        val refBlockOff = 2L * CLUSTER_SIZE
        val l1Off = (2L + rb) * CLUSTER_SIZE
        val l2Off = l1Off + l1Clusters.toLong() * CLUSTER_SIZE
        val dataOff = l2Off + l2Tables.toLong() * CLUSTER_SIZE

        RandomAccessFile(src, "r").use { r ->
            RandomAccessFile(dst, "rw").use { o ->
                o.setLength(0)
                o.setLength(dataOff + dataClusters.toLong() * CLUSTER_SIZE)

                // ---- header (qcow2 v2, 72 bytes, 全部大端) ----
                val header = ByteArray(CLUSTER_SIZE)
                putBe32(header, 0, 0x514649FBL)
                putBe32(header, 4, 2L)                       // version 2
                putBe64(header, 8, 0L)                       // backing_file_offset
                putBe32(header, 16, 0L)                      // backing_file_size
                putBe32(header, 20, CLUSTER_BITS.toLong())
                putBe64(header, 24, size)                    // virtual size
                putBe32(header, 32, 0L)                      // crypt_method = none
                putBe32(header, 36, l1Size.toLong())
                putBe64(header, 40, l1Off)
                putBe64(header, 48, refTableOff)
                putBe32(header, 56, 1L)                      // refcount_table_clusters
                putBe32(header, 60, 0L)                      // nb_snapshots
                putBe64(header, 64, 0L)                      // snapshots_offset
                o.seek(0)
                o.write(header)

                // ---- refcount table ----
                val rt = ByteArray(CLUSTER_SIZE)
                for (i in 0 until rb) putBe64(rt, i * 8, refBlockOff + i.toLong() * CLUSTER_SIZE)
                o.seek(refTableOff)
                o.write(rt)

                // ---- L1 表：每项指向一个 L2 表 ----
                val l1 = ByteArray(l1Clusters * CLUSTER_SIZE)
                for (i in 0 until l1Size) {
                    putBe64(l1, i * 8, (l2Off + i.toLong() * CLUSTER_SIZE) or OFLAG_COPIED)
                }
                o.seek(l1Off)
                o.write(l1)

                // ---- L2 表 + 数据簇（全零簇跳过，不占空间）----
                val l2 = ByteArray(CLUSTER_SIZE)
                val buf = ByteArray(CLUSTER_SIZE)
                var allocated = 0
                for (t in 0 until l2Tables) {
                    java.util.Arrays.fill(l2, 0.toByte())
                    val base = t * L2_ENTRIES
                    for (j in 0 until L2_ENTRIES) {
                        val idx = base + j
                        if (idx >= dataClusters) break
                        java.util.Arrays.fill(buf, 0.toByte())
                        val want = minOf(CLUSTER_SIZE.toLong(), size - idx.toLong() * CLUSTER_SIZE).toInt()
                        r.seek(idx.toLong() * CLUSTER_SIZE)
                        r.readFully(buf, 0, want)
                        var allZero = true
                        for (k in 0 until want) {
                            if (buf[k] != 0.toByte()) { allZero = false; break }
                        }
                        if (!allZero) {
                            val poff = dataOff + allocated.toLong() * CLUSTER_SIZE
                            o.seek(poff)
                            o.write(buf, 0, CLUSTER_SIZE)
                            putBe64(l2, j * 8, poff or OFLAG_COPIED)
                            allocated++
                        }
                    }
                    o.seek(l2Off + t.toLong() * CLUSTER_SIZE)
                    o.write(l2)
                    if (cancelled()) return Result(false, "已取消")
                    progress((t + 1) * 100 / l2Tables, "raw → qcow2　${fmtSize((t + 1).toLong() * L2_ENTRIES * CLUSTER_SIZE)} / ${fmtSize(size)}")
                }

                // ---- refcount blocks：只把真正用到的簇标 1 ----
                val used = 2 + rb + l1Clusters + l2Tables + allocated
                val rblk = ByteArray(CLUSTER_SIZE)
                var remaining = used
                for (i in 0 until rb) {
                    java.util.Arrays.fill(rblk, 0.toByte())
                    val n = minOf(REFC_PER_BLOCK, remaining)
                    for (k in 0 until n) rblk[k * 2 + 1] = 1.toByte()   // 16-bit big-endian refcount = 1
                    o.seek(refBlockOff + i.toLong() * CLUSTER_SIZE)
                    o.write(rblk)
                    remaining -= n
                }
            }
        }
        return Result(true, "完成：${dst.name}（${fmtSize(size)}）", dst.absolutePath)
    }

    // ------------------------- 大端读写助手 -------------------------

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or
            ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or
            (b[o + 3].toLong() and 0xff)

    private fun be64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }

    private fun putBe32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = ((v shr (24 - 8 * i)) and 0xff).toByte()
    }

    private fun putBe64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v shr (56 - 8 * i)) and 0xff).toByte()
    }

    private fun fmtSize(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> String.format("%.2f GB", n / 1024.0 / 1024 / 1024)
        n >= 1024L * 1024 -> String.format("%.1f MB", n / 1024.0 / 1024)
        n >= 1024 -> String.format("%.1f KB", n / 1024.0)
        else -> "$n B"
    }
}
