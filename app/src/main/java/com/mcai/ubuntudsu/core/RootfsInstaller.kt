package com.mcai.ubuntudsu.core

import android.content.Context
import android.content.ContentResolver
import android.provider.DocumentsContract
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption

data class InstallProgress(val phase: String, val current: Long, val total: Long) {
    // 百分比由进度条下方的 percentText 统一展示，这里只保留阶段与大小
    fun text(): String = when (phase) {
        "download" -> String.format("下载中  %s / %s", Env.formatSize(current), if (total > 0) Env.formatSize(total) else "?")
        "read" -> String.format("读取中  %s / %s", Env.formatSize(current), if (total > 0) Env.formatSize(total) else "?")
        "extract" -> String.format("解压中  %s / %s", Env.formatSize(current), if (total > 0) Env.formatSize(total) else "?")
        "backup" -> if (total > 0) String.format("备份中  %s / %s", Env.formatSize(current), Env.formatSize(total)) else "准备备份 rootfs..."
        else -> phase
    }
}

object RootfsInstaller {
    // TUNA LXC 镜像目录：下载时自动检测目录下最新日期中的 rootfs.tar.xz
    val mirrorPresets = listOf(
        "Ubuntu arm64 最新" to "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/ubuntu/resolute/arm64/default/",
        "Debian 13 arm64 最新" to "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/",
    )

    @Volatile
    var cancelled = AtomicBoolean(false)

    fun installFromLocal(
        ctx: Context,
        uri: Uri,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> = runCatching {
        cancelled.set(false)
        val sourceName = uri.toString().substringAfterLast('/').substringBefore('?').ifEmpty { "rootfs.tar.gz" }
        // file:// uri：先取文件真实大小（可直读用 stat，否则 root stat）
        val path = uri.path
        val file = path?.let { java.io.File(it) }
        val total = if (file != null) {
            file.length().takeIf { it > 0 } ?: rootFileSize(path)
        } else {
            contentLength(ctx.contentResolver, uri)
        }
        onProgress(InstallProgress("read", 0, total))
        // 普通流优先（可直读秒开）；无权限降级 root 流（su cat 零拷贝）
        val rawStream: java.io.InputStream? = if (file != null) {
            runCatching { file.takeIf { it.canRead() }?.inputStream() }.getOrNull()
                ?: path?.let { RootShell.openStream(it) }
        } else {
            ctx.contentResolver.openInputStream(uri)
        }
        rawStream?.use { stream ->
            val counting = CountingInputStream(stream) { current ->
                // 解压进度按已读取的压缩字节计，与 total 同基准
                if (total > 0) onProgress(InstallProgress("extract", current.coerceAtMost(total), total))
            }
            TarExtractor.extract(TarExtractor.openStream(counting), Env.rootfs(ctx)).getOrThrow()
        } ?: error("无法读取所选文件")
        validateRootfs(ctx, sourceName)
        // 安装成功后删除安装包（app 可直删用 File.delete；无权限（/sdcard 等）降级 root 删除；
        // content:// 经 DocumentsContract 删除）。删除失败不阻断安装流程。
        if (uri.scheme == "file" && path != null) {
            val deleted = runCatching { file?.delete() == true }.getOrDefault(false)
            if (!deleted && (file?.exists() == true || !canStat(path))) {
                runCatching { RootShell.exec("rm -f \"$path\"", timeoutMs = 15000) }
            }
        } else if (uri.scheme != "file") {
            runCatching { deleteSource(ctx.contentResolver, uri) }
        }
    }

    // root 取文件大小（app 无直读权限时）
    private fun rootFileSize(path: String): Long =
        RootShell.exec("stat -c %s \"$path\" 2>/dev/null || wc -c < \"$path\"", timeoutMs = 20000)
            .stdout.trim().toLongOrNull() ?: -1L

    // app 能否 stat 该路径（false 说明无权限，删除需走 root）
    private fun canStat(path: String): Boolean =
        runCatching { java.io.File(path).exists() }.getOrDefault(false)

    fun downloadAndInstall(
        ctx: Context,
        url: String,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> = runCatching {
        cancelled.set(false)
        // TUNA LXC 目录：自动检测最新日期目录中的 rootfs.tar.xz
        val resolvedUrl = resolveDirectoryUrl(url)
        val target = File(Env.downloads(ctx), "rootfs.tar.${guessExt(resolvedUrl, ctx)}")
        onProgress(InstallProgress("download", 0, 0))
        val connection = URL(resolvedUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        // TUNA 对浏览器 UA 也有反爬拦截，仅放行包管理器 UA
        connection.setRequestProperty("User-Agent", "Debian APT-HTTP/1.3 (2.6.1)")
        connection.connect()
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val total = connection.contentLengthLong.takeIf { it > 0 }
            ?: connection.getHeaderFieldLong("Content-Length", -1L)
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    if (cancelled.get()) error("已取消")
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    onProgress(InstallProgress("download", done, total))
                }
            }
        }
        extractTo(target, Env.rootfs(ctx), onProgress).getOrThrow()
        validateRootfs(ctx, target.name)
        check(target.delete()) { "安装成功，但无法删除安装包" }
    }

    // URL 指向目录（以 / 结尾）时，按参考脚本逻辑解析最新镜像：
    // 1. 目录索引中提取形如 YYYYMMDD_HH:MM 的版本子目录
    // 2. 按字典序取最大（即最新可用构建）
    // 3. 直链拼接 <版本>/rootfs.tar.xz 下载；若该目录缺失（同步中/404）则回退次新版本
    private fun resolveDirectoryUrl(url: String): String {
        if (!url.endsWith("/")) return url
        val html = fetchText(url)
        val dirRegex = Regex("""([0-9]{8}_[0-9]{2}:[0-9]{2})/""")
        val versions = dirRegex.findAll(html).map { it.groupValues[1] }.distinct().sortedDescending().toList()
        check(versions.isNotEmpty()) { "目录页未找到版本文件夹（${url}）" }
        for (version in versions) {
            val candidate = url + version + "/rootfs.tar.xz"
            if (existsHttp(candidate)) return candidate
        }
        error("最新版本目录均无 rootfs.tar.xz（最新: ${versions.first()}）")
    }

    // 探测直链是否可下载：用 Range 0-0 的 GET（部分镜像不支持/限制 HEAD）
    private fun existsHttp(url: String): Boolean = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Debian APT-HTTP/1.3 (2.6.1)")
        connection.setRequestProperty("Range", "bytes=0-0")
        connection.connect()
        // 206 Partial Content 或 200 均视为存在；404/403 视为缺失
        connection.responseCode in 200..299
    }.getOrDefault(false)

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = true
        // TUNA 目录页对浏览器 UA 有反爬拦截，仅放行包管理器 UA
        connection.setRequestProperty("User-Agent", "Debian APT-HTTP/1.3 (2.6.1)")
        connection.connect()
        if (connection.responseCode !in 200..299) error("读取目录失败: HTTP ${connection.responseCode} ($url)")
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun backup(
        ctx: Context,
        destination: Uri,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> = runCatching {
        cancelled.set(false)
        val root = Env.rootfs(ctx)
        check(Env.ubuntuInstalled(ctx)) { "请先安装 Ubuntu rootfs" }
        val total = -1L // 备份进度按输出字节估算，不依赖目录大小探测
        // root 侧 tar + gzip -1 管道：压缩在 native 进程并行完成，Java 仅搬运计数
        //（此前 Java XZOutputStream 单线程约 2MB/s，7GB 需近 1 小时；现在可达闪存速度）
        val shell = ProcessBuilder("/system/bin/su", "0", "/system/bin/sh", "-c",
            "/system/bin/toybox tar -c -C '${root.path}' --exclude=proc --exclude=sys --exclude=dev --exclude=run . | /system/bin/toybox gzip -1",
        ).redirectErrorStream(false).start()
        val errorOutput = java.io.ByteArrayOutputStream()
        val errorReader = Thread {
            shell.errorStream.use { it.copyTo(errorOutput) }
        }.apply { start() }
        var completed = false
        var written = 0L
        try {
            ctx.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                shell.inputStream.use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (cancelled.get()) error("已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        // 进度按输出字节估算：gzip 输出约 50% 输入，用输出量*2 与总量对齐
                        onProgress(InstallProgress("backup", (written * 2).coerceAtLeast(0), if (total > 0) total else written))
                    }
                }
            } ?: error("无法创建备份文件，请确认目标存储仍可写")
            completed = true
        } finally {
            if (!completed && shell.isAlive) shell.destroyForcibly()
        }
        val exitCode = shell.waitFor()
        errorReader.join(5000)
        if (exitCode != 0) {
            val detail = errorOutput.toString(Charsets.UTF_8.name()).trim()
            error("root 权限打包失败${if (detail.isEmpty()) "" else ": $detail"}")
        }
        if (written > 0) {
            onProgress(InstallProgress("backup", written, written))
        }
    }

    private fun extractTo(archive: File, dest: File, onProgress: (InstallProgress) -> Unit): Result<Unit> {
        val total = archive.length()
        onProgress(InstallProgress("extract", 0, total))
        // 进度按压缩包已读字节计（CountingInputStream 包装 FileInputStream），与 total 同基准
        java.io.FileInputStream(archive).use { raw ->
            val stream = CountingInputStream(raw) { current ->
                if (total > 0) onProgress(InstallProgress("extract", current.coerceAtMost(total), total))
            }
            return TarExtractor.extract(TarExtractor.openStream(stream), dest)
                .onFailure { error("解压失败: ${it.message}") }
        }
    }

    private fun validateRootfs(ctx: Context, sourceName: String) {
        if (!Env.ubuntuInstalled(ctx)) {
            error("$sourceName 解压完成，但未找到 bin/bash 或 usr/bin/bash，请确认这是 Ubuntu rootfs 压缩包")
        }
    }

    private fun guessExt(source: String, ctx: Context): String = when {
        source.endsWith(".tar.xz", true) || source.endsWith(".txz", true) -> "xz"
        source.endsWith(".tar.gz", true) || source.endsWith(".tgz", true) -> "gz"
        else -> "gz"
    }

    private fun guessExt(uri: Uri, ctx: Context): String = guessExt(uri.path ?: uri.toString(), ctx)

    private fun contentLength(resolver: ContentResolver, uri: Uri): Long {
        val assetLength = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (assetLength > 0) return assetLength
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
    }

    private class CountingInputStream(
        private val source: java.io.InputStream,
        private val onRead: (Long) -> Unit,
    ) : java.io.FilterInputStream(source) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                count++
                onRead(count)
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                count += read
                onRead(count)
            }
            return read
        }
    }

    private fun deleteSource(resolver: ContentResolver, uri: Uri) {
        val deleted = when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> runCatching {
                DocumentsContract.deleteDocument(resolver, uri)
            }.getOrElse {
                runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            }
            ContentResolver.SCHEME_FILE -> File(uri.path ?: "").delete()
            else -> false
        }
        check(deleted) { "安装成功，但无法删除安装包" }
    }

    private class TarWriter(
        private val output: OutputStream,
        private val root: File,
        private val total: Long,
        private val onProgress: (InstallProgress) -> Unit,
    ) {
        private var written = 0L
        private val excluded = setOf("proc", "sys", "dev", "run")

        fun write() {
            root.listFiles()?.sortedBy { it.name }?.forEach { writeEntry(it, it.name) }
            output.write(ByteArray(1024))
            onProgress(InstallProgress("backup", total, total))
        }

        private fun writeEntry(file: File, name: String) {
            if (file.parentFile == root && file.name in excluded) return
            if (cancelled.get()) error("已取消")
            val path = file.toPath()
            val relative = name.trimStart('/')
            when {
                Files.isSymbolicLink(path) -> {
                    val link = Files.readSymbolicLink(path).toString()
                    writeHeader(relative, 0, '2', link)
                }
                file.isDirectory -> {
                    writeHeader("$relative/", 0, '5', "")
                    file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                        writeEntry(child, "$relative/${child.name}")
                    }
                }
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                    writeHeader(relative, file.length(), '0', "")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelled.get()) error("已取消")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                            onProgress(InstallProgress("backup", written, total))
                        }
                    }
                    pad(file.length())
                }
            }
        }

        private fun writeHeader(name: String, size: Long, type: Char, link: String) {
            val header = ByteArray(512)
            putString(header, 0, 100, name)
            putString(header, 100, 8, "0000755\u0000")
            putString(header, 108, 8, "0000000\u0000")
            putString(header, 116, 8, "0000000\u0000")
            putString(header, 124, 12, "%011o\u0000".format(size))
            putString(header, 136, 12, "%011o\u0000".format(System.currentTimeMillis() / 1000))
            putString(header, 148, 8, "        ")
            header[156] = type.code.toByte()
            putString(header, 157, 100, link)
            putString(header, 257, 6, "ustar\u0000")
            putString(header, 263, 2, "00")
            putString(header, 265, 32, "root")
            putString(header, 297, 32, "root")
            val checksum = header.sumOf { it.toInt() and 0xff }
            putString(header, 148, 8, "%06o\u0000 ".format(checksum))
            output.write(header)
        }

        private fun putString(target: ByteArray, offset: Int, length: Int, value: String) {
            value.toByteArray(Charsets.UTF_8).copyInto(target, offset, 0, minOf(value.toByteArray(Charsets.UTF_8).size, length))
        }

        private fun pad(size: Long) {
            val padding = ((512 - size % 512) % 512).toInt()
            if (padding > 0) output.write(ByteArray(padding))
        }
    }
}
