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

    fun ubuntuInstalled(ctx: Context): Boolean =
        File(rootfs(ctx), "bin/bash").isFile || File(rootfs(ctx), "usr/bin/bash").isFile

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
