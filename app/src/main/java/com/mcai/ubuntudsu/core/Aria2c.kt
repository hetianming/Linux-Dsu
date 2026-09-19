package com.mcai.ubuntudsu.core

import android.content.Context
import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// aria2c 多线程直链下载封装：
// - 优先使用 app 内置二进制（jniLibs: libaria2c.so，免 root）
// - 其次使用 root 环境 PATH 中的 aria2c
// - 写入公共目录（/storage/emulated/0/...）且 app 无权限时自动经 su 以 root 运行
object Aria2c {
    // 多连接数：直链大文件满速下载
    private const val CONNECTIONS = 16
    // 进度长时间无变化视为线路假死（GitHub 资产域名在国内常被静默丢包），及时失败以便切换备用线路
    private const val STALL_TIMEOUT_MS = 45_000L
    // 单次尝试硬上限，避免任何异常情况下界面永久卡在下载中
    private const val OVERALL_TIMEOUT_MS = 15 * 60_000L
    private val progressRegex = Regex("""\(([0-9]{1,3})%\)""")
    val progressPattern = progressRegex
    // summary 中已下载字节片段（如 "1.0MiB/"）：服务器无 Content-Length 时据此与预期大小计算百分比
    private val byteRegex = Regex("""\s([0-9]+(?:\.[0-9]+)?)(B|KiB|MiB|GiB)/""")
    private val byteUnits = mapOf("B" to 1L, "KiB" to 1024L, "MiB" to 1048576L, "GiB" to 1073741824L)

    data class Result(
        val success: Boolean,
        val file: File?,
        val message: String,
    )

    // rootfs / 直链下载默认保存目录：/storage/emulated/0/Downloads/Aria2c下载文件
    fun defaultSaveDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Aria2c下载文件",
    )

    // 从 URL 推断文件名（去掉 query/fragment，URL 解码，非法字符替换）
    fun fileNameFromUrl(url: String): String {
        val raw = url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val safe = decoded.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        return safe.ifBlank { "aria2_${System.currentTimeMillis()}" }
    }

    // 探测可用的 aria2c：
    // 1) nativeLibraryDir 已解压的 libaria2c.so（extractNativeLibs=true 时存在）
    // 2) APK 内嵌的 lib/arm64-v8a/libaria2c.so 运行时解压到 filesDir（useLegacyPackaging=false 时 so 不落盘，必须解压）
    // 3) root 环境 PATH 中的 aria2c
    fun candidates(ctx: Context): List<String> {
        val list = mutableListOf<String>()
        runCatching {
            val so = File(ctx.applicationInfo.nativeLibraryDir, "libaria2c.so")
            if (so.isFile) list.add(so.absolutePath)
        }
        runCatching {
            val extracted = extractBundled(ctx)
            if (extracted != null) list.add(extracted.absolutePath)
        }
        runCatching {
            if (RootShell.available()) {
                RootShell.exec("command -v aria2c", timeoutMs = 10000)
                    .stdout.trim().lineSequence()
                    .firstOrNull { it.isNotBlank() && it.startsWith("/") }
                    ?.let { list.add(it) }
            }
        }
        return list.distinct()
    }

    // 从 APK 内解压 aria2c 到 filesDir/aria2c/aria2c 并赋予执行权限
    // useLegacyPackaging=false 时 so 不解压落盘，nativeLibraryDir 下无文件，必须从 APK zip 读取
    // 首次解压后缓存复用；APK 更新（版本变化）时自动重新解压
    private fun extractBundled(ctx: Context): File? {
        val outDir = File(ctx.filesDir, "aria2c")
        if (!outDir.exists() && !outDir.mkdirs()) return null
        val bin = File(outDir, "aria2c")
        val versionCode = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        }.getOrDefault(0)
        val marker = File(outDir, ".v$versionCode")
        if (bin.isFile && bin.canExecute() && marker.isFile) return bin

        return try {
            val apkPath = ctx.applicationInfo.sourceDir
            java.util.zip.ZipFile(apkPath).use { zip ->
                val entry = zip.entries().asSequence()
                    .firstOrNull { it.name == "lib/arm64-v8a/libaria2c.so" }
                    ?: return null
                val tmp = File(outDir, "aria2c.tmp")
                zip.getInputStream(entry).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                }
                if (!tmp.setExecutable(true, false)) {
                    runCatching { RootShell.exec("chmod 755 '${tmp.absolutePath}'", timeoutMs = 10000) }
                }
                if (!tmp.renameTo(bin)) return null
            }
            // 清理旧版本标记（保留当前）
            outDir.listFiles()?.forEach {
                if (it.name.startsWith(".v") && it.name != ".v$versionCode") it.delete()
            }
            marker.createNewFile()
            bin
        } catch (e: Exception) {
            null
        }
    }

    fun available(ctx: Context): Boolean = candidates(ctx).isNotEmpty()

    // 下载直链到 target；forceRoot=true 时强制经 su 运行（用于 app 无权限的公共目录）
    // onLog 回调实时输出 aria2c 的 summary 行（下载速度/ETA 等诊断信息）
    fun download(
        ctx: Context,
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
        forceRoot: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onLog: ((String) -> Unit)? = null,
        expectedSize: Long = -1L,
        referer: String? = null,
        userAgent: String? = null,
    ): Result {
        val dir = target.parentFile ?: return Result(false, null, "无效的保存路径")
        // 目录准备：app 可写则直建，否则经 root 创建
        val appCanWrite = ensureAppDir(dir)
        if (!appCanWrite) {
            runCatching { RootShell.exec("mkdir -p '${dir.absolutePath}'", timeoutMs = 15000) }
        }

        val binaries = candidates(ctx)
        if (binaries.isEmpty()) {
            return Result(false, null, "未找到可用的 aria2c（内置组件缺失且系统未安装）")
        }
        onLog?.invoke("使用 ${binaries.first()}")

        var lastError = "下载失败"
        val tried = mutableSetOf<String>()
        for (binary in binaries) {
            val appBinary = isAppBinary(binary)
            // app 内置且 app 有写权限：先免 root 直跑；其余/失败后经 su 以 root 兜底
            val plans = mutableListOf<Boolean>()
            if (!forceRoot && appCanWrite && appBinary) plans.add(false)
            plans.add(true)
            for (useRoot in plans) {
                if (!tried.add("$binary|$useRoot")) continue
                val result = runOnce(ctx, binary, useRoot, url, target, onProgress, isCancelled, onLog, expectedSize, referer, userAgent)
                if (result.success) return result
                if (result.message == "已取消") return result
                lastError = result.message
                onLog?.invoke("尝试失败（${if (useRoot) "root" else "直跑"}）：${result.message.take(120)}")
                // 网络类故障与运行身份无关，直接交给外层换线路，不做无意义的 root 重试
                if (result.message.startsWith("网络无进展") || result.message.startsWith("下载超时")) break
            }
        }
        return Result(false, null, lastError)
    }

    private fun runOnce(
        ctx: Context,
        binary: String,
        useRoot: Boolean,
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        onLog: ((String) -> Unit)? = null,
        expectedSize: Long = -1L,
        referer: String? = null,
        userAgent: String? = null,
    ): Result {
        val dir = target.parentFile?.absolutePath ?: return Result(false, null, "无效的保存路径")
        if (isCancelled()) return Result(false, null, "已取消")
        // URL/文件名清洗：屏幕 OCR/粘贴常混入空格与换行，aria2c 对畸形 URL 会静默 0B 退出
        val cleanUrl = url.replace(Regex("\\s+"), "")
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return Result(false, null, "URL 无效（清洗后：$cleanUrl）")
        }
        // aria2c 日志放 app 私有目录（任何运行身份都经 ctx 可写），失败时读取首条 ERROR
        val logDir = File(ctx.filesDir, "aria2c")
        if (!logDir.exists()) runCatching { logDir.mkdirs() }
        val logFile = File(logDir, "session.log")
        runCatching { logFile.delete() }
        val args = mutableListOf(
            "--no-conf=true",
            "--log=${logFile.absolutePath}", "--log-level=notice",
            "--allow-overwrite=true", "--auto-file-renaming=false", "--continue=true",
            "--max-connection-per-server=$CONNECTIONS", "--split=$CONNECTIONS", "--min-split-size=1M",
            "--file-allocation=none", "--summary-interval=1",
            "--connect-timeout=30", "--timeout=60",
            "--max-tries=5", "--retry-wait=3",
        )
        if (userAgent != null) {
            args.add("--user-agent=$userAgent")
        } else {
            args.add("--user-agent=Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
        }
        if (referer != null) {
            args.add("--referer=$referer")
        }
        args.addAll(listOf(
            "--dir=$dir", "--out=${target.name}",
            cleanUrl,
        ))
        return try {
            val process = if (useRoot) {
                ProcessBuilder("su", "-c", shellCommand(binary, args))
                    .redirectErrorStream(true).start()
            } else {
                ProcessBuilder(binary, *args.toTypedArray())
                    .redirectErrorStream(true).start()
            }
            val lastLine = AtomicReference("")
            val lastBytes = AtomicLong(0L)
            val lastPercent = AtomicInteger(-1)
            val lastProgressAt = AtomicLong(System.currentTimeMillis())
            val startedAt = System.currentTimeMillis()
            val reader = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                        if (line.isNotBlank()) {
                            lastLine.set(line.trim())
                            // summary 行含速度/连接数，实时回传界面，避免看起来像卡死
                            if (onLog != null && line.contains("CN:")) onLog(line.trim())
                        }
                        // 已下载字节：直链被墙时空转行（0B/0B）不会变化，只有真实数据才推进看门狗与进度
                        val bytesNow = byteRegex.find(line)?.let { m ->
                            val value = m.groupValues[1].toDoubleOrNull() ?: 0.0
                            (value * (byteUnits[m.groupValues[2]] ?: 0L)).toLong()
                        }
                        if (bytesNow != null && bytesNow != lastBytes.get()) {
                            lastBytes.set(bytesNow)
                            lastProgressAt.set(System.currentTimeMillis())
                        }
                        // 百分比优先取 (N%)；无 Content-Length 时回退为已下载字节 / 预期大小
                        val percent = progressRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
                            ?: bytesNow?.let { bytes ->
                                if (expectedSize <= 0) null else ((bytes * 100 / expectedSize).toInt()).coerceIn(0, 100)
                            }
                        if (percent != null) {
                            if (percent != lastPercent.get()) {
                                lastPercent.set(percent)
                                lastProgressAt.set(System.currentTimeMillis())
                            }
                            // 连接阶段（0 字节）不推进百分比，界面保持"连接中"状态
                            if (percent > 0 || (bytesNow ?: 0L) > 0L) onProgress(percent.coerceIn(0, 100))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            while (true) {
                if (process.waitFor(1, TimeUnit.SECONDS)) break
                if (isCancelled()) {
                    process.destroyForcibly()
                    reader.join(1000)
                    return Result(false, null, "已取消")
                }
                val now = System.currentTimeMillis()
                if (now - startedAt > OVERALL_TIMEOUT_MS) {
                    process.destroyForcibly()
                    reader.join(1000)
                    return Result(false, null, "下载超时（超过 15 分钟）")
                }
                if (now - lastProgressAt.get() > STALL_TIMEOUT_MS) {
                    process.destroyForcibly()
                    reader.join(1000)
                    return Result(false, null, "网络无进展（${STALL_TIMEOUT_MS / 1000} 秒无数据），切换线路重试")
                }
            }
            reader.join(2000)
            val code = process.exitValue()
            if (code == 0 && target.isFile && target.length() > 0) {
                runCatching { controlFile(target).delete() }
                runCatching { logFile.delete() }
                onProgress(100)
                Result(true, target, "完成")
            } else {
                val detail = readAria2Error(logFile)
                Result(false, null, detail ?: lastLine.get().ifBlank { "aria2c 退出码 $code" })
            }
        } catch (e: Exception) {
            Result(false, null, e.message ?: "执行 aria2c 失败")
        } finally {
            runCatching { logFile.delete() }
        }
    }

    // 从 aria2c log 文件提取真实失败原因（errorCode 描述行优先，其次首条 ERROR）
    private fun readAria2Error(logFile: File): String? = runCatching {
        if (!logFile.isFile) return@runCatching null
        val lines = logFile.readLines().map { it.trim() }
        // 优先取人话描述：errorCode=N <描述>，如 "errorCode=3 Resource not found"
        val codeDesc = lines.mapNotNull { line ->
            Regex("""errorCode=\d+ (.+)$""").find(line)?.groupValues?.get(1)?.trim()
        }.firstOrNull { it.isNotBlank() }
        if (codeDesc != null) {
            // errorCode=1 的描述只有 URI=...，对用户无意义，转成连接类失败描述
            val clean = if (codeDesc.startsWith("URI=")) "连接失败或超时" else codeDesc
            return@runCatching clean.take(160)
        }
        val errorLine = lines.firstOrNull {
            it.startsWith("ERROR") || it.startsWith("[ERROR]") || it.contains("Exception")
        }
        // 去掉 "2026-09-14 20:27:51.292541 [ERROR] [AbstractCommand.cc:349]" 时间戳/位置前缀
        errorLine?.replace(Regex("""^\[?\d{4}-\d{2}-\d{2} [\d:.]+\]?\s*"""), "")
            ?.replace(Regex("""\[(ERROR|NOTICE)\] \[[^\]]+\] ?"""), "")
            ?.take(160)
    }.getOrNull()

    // su -c 命令拼接：二进制与参数统一单引号转义，防注入
    private fun shellCommand(binary: String, args: List<String>): String = buildString {
        append('"').append(binary).append('"')
        args.forEach { arg -> append(" '").append(arg.replace("'", "'\\''")).append("'") }
    }

    private fun controlFile(target: File): File = File(target.parentFile, "${target.name}.aria2")

    private fun isAppBinary(binary: String): Boolean =
        binary.startsWith("/data/app/") || binary.startsWith("/data/data/")

    // 尝试 app 侧创建并写入目录；返回 app 是否可直接写入
    private fun ensureAppDir(dir: File): Boolean {
        if (dir.isDirectory && dir.canWrite()) return true
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return dir.isDirectory && dir.canWrite()
    }
}
