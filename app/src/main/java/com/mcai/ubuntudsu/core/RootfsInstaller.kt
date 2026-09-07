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
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

data class InstallProgress(val phase: String, val current: Long, val total: Long) {
    private fun percent(): String = if (total > 0) " ${((current * 100) / total).toInt().coerceIn(0, 100)}%" else ""

    fun text(): String = when (phase) {
        "download" -> String.format("下载中%s  %s / %s", percent(), Env.formatSize(current), if (total > 0) Env.formatSize(total) else "?")
        "read" -> String.format("读取中%s  %s / %s", percent(), Env.formatSize(current), if (total > 0) Env.formatSize(total) else "?")
        "extract" -> String.format("解压中%s  %s / %s", percent(), Env.formatSize(current), if (total > 0) Env.formatSize(total) else "?")
        "backup" -> if (total > 0) String.format("备份中%s  %s / %s", percent(), Env.formatSize(current), Env.formatSize(total)) else "准备备份 rootfs..."
        else -> phase
    }
}

object RootfsInstaller {
    val mirrorPresets = listOf(
        "Ubuntu 26.04.1 arm64" to "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz",
        "Ubuntu 24.04 arm64" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
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
        val total = contentLength(ctx.contentResolver, uri)
        onProgress(InstallProgress("read", 0, total))
        ctx.contentResolver.openInputStream(uri)?.use { rawStream ->
            val stream = CountingInputStream(rawStream) { current ->
                onProgress(InstallProgress("read", current, total))
            }
            TarExtractor.extract(TarExtractor.openStream(stream), 0, Env.rootfs(ctx)).getOrThrow()
        } ?: error("无法读取所选文件")
        validateRootfs(ctx, sourceName)
        deleteSource(ctx.contentResolver, uri)
    }

    fun downloadAndInstall(
        ctx: Context,
        url: String,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> = runCatching {
        cancelled.set(false)
        val target = File(Env.downloads(ctx), "rootfs.tar.${guessExt(url, ctx)}")
        onProgress(InstallProgress("download", 0, 0))
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
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

    fun backup(
        ctx: Context,
        destination: Uri,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> = runCatching {
        cancelled.set(false)
        val root = Env.rootfs(ctx)
        check(Env.ubuntuInstalled(ctx)) { "请先安装 Ubuntu rootfs" }
        val total = Env.dirSize(root)
        val process = ProcessBuilder(
            "/system/bin/su", "0", "/system/bin/toybox", "tar", "-c",
            "-C", root.path, "--exclude=proc", "--exclude=sys",
            "--exclude=dev", "--exclude=run", ".",
        ).redirectErrorStream(false).start()
        val errorOutput = java.io.ByteArrayOutputStream()
        val errorReader = Thread {
            process.errorStream.use { it.copyTo(errorOutput) }
        }.apply { start() }
        var completed = false
        try {
            ctx.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                XZOutputStream(output, LZMA2Options(1)).use { xz ->
                    process.inputStream.use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        while (true) {
                            if (cancelled.get()) error("已取消")
                            val count = input.read(buffer)
                            if (count < 0) break
                            xz.write(buffer, 0, count)
                            written += count
                            onProgress(InstallProgress("backup", written, total))
                        }
                    }
                }
            } ?: error("无法创建备份文件，请确认目标存储仍可写")
            completed = true
        } finally {
            if (!completed && process.isAlive) process.destroyForcibly()
        }
        val exitCode = process.waitFor()
        errorReader.join(5000)
        if (exitCode != 0) {
            val detail = errorOutput.toString(Charsets.UTF_8.name()).trim()
            error("root 权限打包失败${if (detail.isEmpty()) "" else ": $detail"}")
        }
        if (total > 0) {
            onProgress(InstallProgress("backup", total, total))
        }
    }

    private fun extractTo(archive: File, dest: File, onProgress: (InstallProgress) -> Unit): Result<Unit> {
        onProgress(InstallProgress("extract", 0, archive.length()))
        return TarExtractor.extract(archive, dest) { current, total ->
            onProgress(InstallProgress("extract", current, total))
        }.onFailure { error ->
            error("解压失败: ${error.message}")
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
