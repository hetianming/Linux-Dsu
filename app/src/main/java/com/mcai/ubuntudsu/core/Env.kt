package com.mcai.ubuntudsu.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

object Env {
    fun rootfs(ctx: Context): File = File(ctx.filesDir, "rootfs/ubuntu")
    fun downloads(ctx: Context): File = File(ctx.filesDir, "downloads").apply { mkdirs() }
    fun logs(ctx: Context): File = File(ctx.filesDir, "logs").apply { mkdirs() }
    fun background(ctx: Context): File = File(ctx.filesDir, "card_bg.jpg")
    // 用户自定义视频背景（mp4）
    fun backgroundVideo(ctx: Context): File = File(ctx.filesDir, "card_bg.mp4")
    // 音频桥接 FIFO：位于 rootfs 内 /run/android-audio.pcm（rootfs 在应用数据目录下，
    // 应用身份可直接读写；容器内 PulseAudio module-pipe-sink 与宿主 AudioBridge 共用此 inode）
    fun audioPipe(ctx: Context): File = File(rootfs(ctx), "run/android-audio.pcm")

    fun ubuntuInstalled(ctx: Context): Boolean =
        File(rootfs(ctx), "bin/bash").isFile || File(rootfs(ctx), "usr/bin/bash").isFile

    // 桌面环境检测结果：kde / xfce / 未安装
    // 通过查 rootfs 内对应启动器是否存在判断（无需 su，app uid 可读）
    fun desktopState(ctx: Context): String {
        if (!ubuntuInstalled(ctx)) return "未安装 rootfs"
        val rootfs = rootfs(ctx)
        val kdeFiles = listOf("usr/bin/startplasma-x11", "usr/bin/plasma-session")
        val xfceFiles = listOf("usr/bin/startxfce4", "usr/bin/xfce4-session")
        val kde = kdeFiles.any { File(rootfs, it).exists() }
        val xfce = xfceFiles.any { File(rootfs, it).exists() }
        return when {
            kde && xfce -> "已安装 KDE + XFCE4 桌面环境"
            kde -> "已安装 KDE 桌面环境"
            xfce -> "已安装 XFCE4 桌面环境"
            else -> "未安装任何桌面环境"
        }
    }

    @Deprecated("No longer used; replaced by -1 sentinel in RootfsInstaller.backup")
    fun dirSize(file: File): Long {
        val virtualDirectories = setOf("proc", "sys", "dev", "run")
        val countedFiles = mutableSetOf<Any>()
        fun sizeOf(entry: File): Long {
            val path = entry.toPath()
            if (Files.isSymbolicLink(path)) return 0L
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                val fileKey = runCatching {
                    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
                }.getOrNull()
                if (fileKey != null && !countedFiles.add(fileKey)) return 0L
                return entry.length().coerceAtLeast(0L)
            }
            if (!entry.isDirectory) return 0L
            if (entry != file && entry.name in virtualDirectories) return 0L
            return entry.listFiles()?.sumOf { child -> sizeOf(child) } ?: 0L
        }
        return sizeOf(file)
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1fMB", mb)
        return String.format("%.2fGB", mb / 1024.0)
    }
}
