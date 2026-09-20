package com.mcai.ubuntudsu.core

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 多线程 HTTP Range 下载器（参考 Dsu-Manager）
 * - 使用 FileChannel 并发写入不同位置（线程安全，无需 synchronized）
 * - 每个线程独立 HTTP 连接 + 独立 RandomAccessFile
 * - 失败自动降级为单线程
 * - 断点续传支持
 */
object JavaDownloader {

    private const val THREAD_COUNT = 8
    private const val BUFFER_BYTES = 256 * 1024  // 256KB
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 60_000
    private const val MAX_RETRIES = 3

    const val MIUI_REFERER = "https://www.miui.com/"
    const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 16; PMA120 Build/BP2A.250605.015)"

    data class Result(val success: Boolean, val file: File?, val message: String)

    /** 暂停信号：暂停时抛出以立即断开 HTTP 连接，恢复后由重试循环从断点重连（不消耗重试次数） */
    private class PausedSignal : Exception()

    fun defaultSaveDir(): File = File("/sdcard/Downloads")

    fun fileNameFromUrl(url: String): String {
        val raw = url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
        return raw.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    /**
     * 文件大小与已下载字节回调（用于 UI 显示「已下载 / 总大小」）
     * 第一个参数为总字节数，第二个为已下载字节数
     */
    fun download(
        ctx: Context,
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean = { false },
        isPaused: () -> Boolean = { false },
        onLog: ((String) -> Unit)? = null,
        onSizeInfo: ((Long, Long) -> Unit)? = null,
    ): Result {
        val cleanUrl = url.replace(Regex("\\s+"), "")
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return Result(false, null, "URL 无效")
        }

        // 确定保存路径：强制使用 /sdcard/Downloads
        var finalTarget = target
        val dir = target.parentFile
        if (dir != null) {
            // 尝试创建目录
            if (!dir.exists()) {
                dir.mkdirs()
            }
            // 检查是否可写
            if (!dir.canWrite()) {
                // 尝试创建测试文件验证可写性
                val testFile = File(dir, ".write_test")
                try {
                    testFile.createNewFile()
                    testFile.delete()
                } catch (e: Exception) {
                    // 公共目录不可写，回退到应用私有目录
                    val fb = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    if (fb != null && (fb.exists() || fb.mkdirs())) {
                        finalTarget = File(fb, target.name)
                        onLog?.invoke("⚠️ /sdcard/Downloads 不可写，回退到: ${fb.absolutePath}")
                        onLog?.invoke("请在系统设置中授予「所有文件访问权限」以使用 /sdcard/Downloads")
                    } else {
                        return Result(false, null, "无法创建保存目录，请检查存储权限")
                    }
                }
            }
        } else {
            return Result(false, null, "保存路径无效")
        }

        onLog?.invoke("下载地址: $cleanUrl")
        onLog?.invoke("保存路径: ${finalTarget.absolutePath}")

        val cancelled = AtomicBoolean(false)
        return try {
            downloadInternal(cleanUrl, finalTarget, onProgress, cancelled, { isCancelled() }, { isPaused() }, onLog, onSizeInfo)
        } catch (e: Exception) {
            onLog?.invoke("下载异常: ${e.message}")
            Result(false, null, e.message ?: "下载失败")
        }
    }

    private fun downloadInternal(
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
        cancelled: AtomicBoolean,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        onLog: ((String) -> Unit)?,
        onSizeInfo: ((Long, Long) -> Unit)?,
    ): Result {
        // 1. 探测文件大小
        onLog?.invoke("正在连接服务器...")
        val (totalSize, rangeSupported) = probeFile(url, onLog)
        if (totalSize <= 0) {
            return Result(false, null, "无法获取文件大小")
        }
        onLog?.invoke("文件大小: ${formatBytes(totalSize)}")
        onSizeInfo?.invoke(totalSize, 0L)

        // 2. 选择下载策略
        val tmpFile = File(target.parentFile, "${target.name}.tmp")
        if (rangeSupported && totalSize > 2 * 1024 * 1024) {
            onLog?.invoke("支持 Range，启动 $THREAD_COUNT 线程并发下载...")
            val result = multiThreadDownload(url, tmpFile, totalSize, onProgress, cancelled, isCancelled, isPaused, onLog, onSizeInfo)
            if (result.success) {
                return finalizeFile(tmpFile, target, onLog, onProgress)
            }
            // 多线程失败，降级为单线程
            onLog?.invoke("多线程下载失败，降级为单线程...")
            tmpFile.delete()
        }

        return singleThreadDownload(url, tmpFile, target, totalSize, onProgress, cancelled, isCancelled, isPaused, onLog, onSizeInfo)
    }

    // ========== 探测 ==========

    private fun probeFile(url: String, onLog: ((String) -> Unit)?): Pair<Long, Boolean> {
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(url, 0)
            val code = conn.responseCode
            onLog?.invoke("服务器响应: HTTP $code")

            if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                val size = conn.contentLengthLong
                val range = conn.getHeaderField("Accept-Ranges") == "bytes" || code == HttpURLConnection.HTTP_PARTIAL
                return Pair(size, range)
            }
            if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                onLog?.invoke("403 被拒，重试不带 Range...")
                conn.disconnect()
                conn = openConnection(url, -1)
                val code2 = conn.responseCode
                onLog?.invoke("重试响应: HTTP $code2")
                if (code2 == HttpURLConnection.HTTP_OK || code2 == HttpURLConnection.HTTP_PARTIAL) {
                    val size = conn.contentLengthLong
                    val range = conn.getHeaderField("Accept-Ranges") == "bytes" || code2 == HttpURLConnection.HTTP_PARTIAL
                    return Pair(size, range)
                }
            }
        } catch (e: Exception) {
            onLog?.invoke("探测失败: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return Pair(-1, false)
    }

    // ========== 多线程下载 ==========

    private fun multiThreadDownload(
        url: String,
        tmpFile: File,
        totalSize: Long,
        onProgress: (Int) -> Unit,
        cancelled: AtomicBoolean,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        onLog: ((String) -> Unit)?,
        onSizeInfo: ((Long, Long) -> Unit)?,
    ): Result {
        // 预分配文件
        try {
            RandomAccessFile(tmpFile, "rw").use { it.setLength(totalSize) }
        } catch (e: Exception) {
            onLog?.invoke("预分配文件失败: ${e.message}")
            return Result(false, null, "预分配失败")
        }

        // 计算分块
        val segSize = totalSize / THREAD_COUNT
        val segments = Array(THREAD_COUNT) { i ->
            val start = i * segSize
            val end = if (i == THREAD_COUNT - 1) totalSize - 1 else (i + 1) * segSize - 1
            Segment(i, start, end, start)
        }

        val done = AtomicLong(0)
        val anyError = AtomicBoolean(false)
        val errorMsg = java.util.concurrent.atomic.AtomicReference("")
        val latch = CountDownLatch(THREAD_COUNT)

        // 启动线程
        for (i in 0 until THREAD_COUNT) {
            val seg = segments[i]
            Thread {
                try {
                    downloadSegment(url, tmpFile, seg, done, totalSize, onProgress, cancelled, isCancelled, isPaused, onLog, onSizeInfo)
                } catch (e: Exception) {
                    if (!cancelled.get()) {
                        anyError.set(true)
                        errorMsg.set("分块${seg.index}: ${e.message}")
                        onLog?.invoke("分块${seg.index} 失败: ${e.message}")
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        // 等待完成
        try {
            latch.await()
        } catch (_: InterruptedException) {}

        if (cancelled.get() || isCancelled()) {
            return Result(false, null, "已取消")
        }
        if (anyError.get()) {
            return Result(false, null, errorMsg.get())
        }
        if (tmpFile.length() < totalSize) {
            onLog?.invoke("文件不完整: ${formatBytes(tmpFile.length())}/${formatBytes(totalSize)}")
            return Result(false, null, "文件不完整")
        }

        return Result(true, tmpFile, "完成")
    }

    private fun downloadSegment(
        url: String,
        tmpFile: File,
        seg: Segment,
        done: AtomicLong,
        totalSize: Long,
        onProgress: (Int) -> Unit,
        cancelled: AtomicBoolean,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        onLog: ((String) -> Unit)?,
        onSizeInfo: ((Long, Long) -> Unit)?,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var retries = 0

        while (retries <= MAX_RETRIES) {
            if (cancelled.get() || isCancelled()) return
            if (seg.cursor > seg.end) return

            var conn: HttpURLConnection? = null
            try {
                conn = openConnection(url, seg.cursor, seg.end)
                val code = conn.responseCode

                if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                    onLog?.invoke("线程${seg.index} 连接成功，下载 ${formatBytes(seg.start)}-${formatBytes(seg.end)}")
                    conn.inputStream.use { input ->
                        RandomAccessFile(tmpFile, "rw").use { raf ->
                            var lastReport = System.currentTimeMillis()
                            var lastBytes = done.get()

                            while (!cancelled.get() && !isCancelled()) {
                                // 暂停：抛信号立即断开连接。
                                // 原地 sleep 等待的话，闲置连接会被服务器/NAT 静默断开，
                                // 恢复后 input.read() 阻塞在死连接上直至读超时——界面显示
                                // "下载中"却零字节（假恢复）。断开重连才能即刻恢复传输。
                                if (isPaused()) throw PausedSignal()
                                val bytesRead = input.read(buffer)
                                if (bytesRead < 0) break

                                // 定位写入（每个线程写不同区域，无需同步）
                                raf.seek(seg.cursor)
                                raf.write(buffer, 0, bytesRead)

                                seg.cursor += bytesRead.toLong()
                                done.addAndGet(bytesRead.toLong())

                                val now = System.currentTimeMillis()
                                if (now - lastReport > 500) {
                                    val total = done.get()
                                    val pct = (total * 100 / totalSize).toInt().coerceIn(0, 100)
                                    val speed = (total - lastBytes) * 1000 / (now - lastReport)
                                    onProgress(pct)
                                    onSizeInfo?.invoke(totalSize, total)
                                    onLog?.invoke("进度: $pct% | ${formatBytes(total)}/${formatBytes(totalSize)} | ${formatBytes(speed)}/s")
                                    lastReport = now
                                    lastBytes = total
                                }
                            }
                        }
                    }
                    onLog?.invoke("线程${seg.index} 完成")
                    return
                } else {
                    onLog?.invoke("线程${seg.index} HTTP $code，重试 ${retries + 1}/$MAX_RETRIES")
                    retries++
                    if (retries > MAX_RETRIES) throw java.io.IOException("HTTP $code")
                    Thread.sleep(2000L * retries)
                }
            } catch (e: PausedSignal) {
                // 连接已断开，原地等待恢复；恢复后回到循环顶部按 seg.cursor 断点重连（不消耗重试次数）
                while (isPaused()) {
                    if (cancelled.get() || isCancelled()) return
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                if (cancelled.get()) return
                onLog?.invoke("线程${seg.index} 异常: ${e.message}，重试 ${retries + 1}/$MAX_RETRIES")
                retries++
                if (retries > MAX_RETRIES) throw e
                Thread.sleep(2000L * retries)
            } finally {
                conn?.disconnect()
            }
        }
    }

    // ========== 单线程下载 ==========

    private fun singleThreadDownload(
        url: String,
        tmpFile: File,
        target: File,
        totalSize: Long,
        onProgress: (Int) -> Unit,
        cancelled: AtomicBoolean,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
        onLog: ((String) -> Unit)?,
        onSizeInfo: ((Long, Long) -> Unit)?,
    ): Result {
        var retries = 0

        while (retries <= MAX_RETRIES) {
            if (cancelled.get() || isCancelled()) return Result(false, null, "已取消")

            var conn: HttpURLConnection? = null
            try {
                var existing = 0L
                if (tmpFile.isFile) {
                    existing = tmpFile.length()
                    if (existing > 0) onLog?.invoke("断点续传: ${formatBytes(existing)}")
                }

                conn = openConnection(url, existing)
                val code = conn.responseCode
                onLog?.invoke("服务器响应: HTTP $code")

                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    val actualTotal = if (code == HttpURLConnection.HTTP_PARTIAL && existing > 0) {
                        existing + conn.contentLengthLong
                    } else {
                        conn.contentLengthLong
                    }

                    if (code == HttpURLConnection.HTTP_OK && existing > 0) {
                        existing = 0
                        tmpFile.delete()
                    }

                    onLog?.invoke("开始下载...")
                    var done = existing
                    onSizeInfo?.invoke(actualTotal, done)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReport = System.currentTimeMillis()
                    var lastBytes = done

                    conn.inputStream.use { input ->
                        RandomAccessFile(tmpFile, "rw").use { raf ->
                            if (existing > 0) raf.seek(existing) else raf.setLength(0)

                            while (!cancelled.get() && !isCancelled()) {
                                // 暂停：抛信号断开连接，避免恢复后阻塞在已被服务端断掉的死连接上（同多线程路径）
                                if (isPaused()) throw PausedSignal()
                                val bytesRead = input.read(buffer)
                                if (bytesRead < 0) break
                                raf.write(buffer, 0, bytesRead)
                                done += bytesRead.toLong()

                                val now = System.currentTimeMillis()
                                if (now - lastReport > 500) {
                                    val pct = (done * 100 / actualTotal).toInt().coerceIn(0, 100)
                                    val speed = (done - lastBytes) * 1000 / (now - lastReport)
                                    onProgress(pct)
                                    onSizeInfo?.invoke(actualTotal, done)
                                    onLog?.invoke("进度: $pct% | ${formatBytes(done)}/${formatBytes(actualTotal)} | ${formatBytes(speed)}/s")
                                    lastReport = now
                                    lastBytes = done
                                }
                            }
                        }
                    }

                    if (cancelled.get() || isCancelled()) return Result(false, null, "已取消")

                    if (actualTotal > 0 && tmpFile.length() < actualTotal) {
                        onLog?.invoke("文件不完整，重试...")
                        retries++
                        continue
                    }

                    return finalizeFile(tmpFile, target, onLog, onProgress)
                } else {
                    onLog?.invoke("HTTP $code，重试 ${retries + 1}/$MAX_RETRIES")
                    retries++
                    if (retries > MAX_RETRIES) return Result(false, null, "HTTP $code")
                    Thread.sleep(2000L * retries)
                }
            } catch (e: PausedSignal) {
                // 连接已断开，原地等待恢复；恢复后回到循环顶部按 tmpFile 长度断点重连（不消耗重试次数）
                while (isPaused()) {
                    if (cancelled.get() || isCancelled()) return Result(false, null, "已取消")
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                onLog?.invoke("异常: ${e.message}，重试 ${retries + 1}/$MAX_RETRIES")
                retries++
                if (retries > MAX_RETRIES) return Result(false, null, e.message ?: "下载失败")
                Thread.sleep(2000L * retries)
            } finally {
                conn?.disconnect()
            }
        }
        return Result(false, null, "下载失败")
    }

    // ========== 工具方法 ==========

    private fun finalizeFile(tmpFile: File, target: File, onLog: ((String) -> Unit)?, onProgress: (Int) -> Unit): Result {
        if (target.exists() && !target.delete()) {
            onLog?.invoke("无法删除旧文件，使用临时文件")
            return Result(true, tmpFile, "完成（临时文件）")
        }
        if (!tmpFile.renameTo(target)) {
            tmpFile.inputStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            tmpFile.delete()
        }
        onLog?.invoke("下载完成！")
        onProgress(100)
        return Result(true, target, "完成")
    }

    private fun openConnection(url: String, rangeFrom: Long, rangeTo: Long = -1): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Connection", "keep-alive")
        conn.useCaches = false
        conn.instanceFollowRedirects = true

        if (url.contains("miui.com") || url.contains("aliyuncs.com")) {
            conn.setRequestProperty("Referer", MIUI_REFERER)
        }

        if (rangeFrom >= 0) {
            val range = if (rangeTo >= 0) "bytes=$rangeFrom-$rangeTo" else "bytes=$rangeFrom-"
            conn.setRequestProperty("Range", range)
        }
        return conn
    }

    private data class Segment(val index: Int, val start: Long, val end: Long, var cursor: Long)

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }
}
