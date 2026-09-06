package com.mcai.ubuntudsu.core

import android.content.Context
import android.content.ContentResolver
import android.provider.DocumentsContract
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class InstallProgress(val phase: String, val current: Long, val total: Long) {
    private fun percent(): String = if (total > 0) " ${((current * 100) / total).toInt().coerceIn(0, 100)}%" else ""

    fun text(): String = when (phase) {
        "download" -> String.format("下载中%s  %s / %s", percent(), Env.formatSize(current), if (total > 0) Env.formatSize(total) else "?")
        "read" -> String.format("读取中%s  %s / %s", percent(), Env.formatSize(current), if (total > 0) Env.formatSize(total) else "?")
        "extract" -> String.format("解压中%s  %s / %s", percent(), Env.formatSize(current), if (total > 0) Env.formatSize(total) else "?")
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
}
