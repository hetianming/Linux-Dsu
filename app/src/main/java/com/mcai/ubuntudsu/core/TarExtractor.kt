package com.mcai.ubuntudsu.core

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

object TarExtractor {

    fun openStream(file: File): InputStream {
        val name = file.name.lowercase()
        val raw = FileInputStream(file)
        return when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GZIPInputStream(raw, 65536)
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> XZInputStream(raw)
            else -> raw
        }
    }

    fun openStream(source: InputStream): InputStream {
        val buffered = if (source is BufferedInputStream) source else BufferedInputStream(source, 65536)
        buffered.mark(6)
        val magic = ByteArray(6)
        var read = 0
        while (read < magic.size) {
            val count = buffered.read(magic, read, magic.size - read)
            if (count < 0) break
            read += count
        }
        buffered.reset()
        return when {
            read >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> GZIPInputStream(buffered, 65536)
            read >= 6 && magic.contentEquals(byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00)) -> XZInputStream(buffered)
            else -> buffered
        }
    }

    fun extract(archive: File, dest: File): Result<Unit> =
        extract(openStream(archive), dest)

    // 进度由调用方在压缩源流上计数（与压缩包大小同基准），此处不做进度统计
    fun extract(
        source: InputStream,
        dest: File,
    ): Result<Unit> = runCatching {
        dest.mkdirs()
        val reader = TarReader(source)
        val buffer = ByteArray(256 * 1024)
        var longName: String? = null
        var longLink: String? = null

        try {
            while (true) {
                val header = reader.readBlock() ?: break
                if (header.all { it.toInt() == 0 }) break

                var name = readString(header, 0, 100)
                val mode = readOctal(header, 100, 8)
                val size = readOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                var linkName = readString(header, 157, 100)
                if (header[345].toInt() != 0) {
                    val prefix = readString(header, 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                }

                when (type) {
                    'L' -> {
                        longName = reader.readDataString(size)
                        reader.skipPadding(size)
                        continue
                    }
                    'K' -> {
                        longLink = reader.readDataString(size)
                        reader.skipPadding(size)
                        continue
                    }
                }
                longName?.let { name = it; longName = null }
                longLink?.let { linkName = it; longLink = null }

                // ubuntu-base archives use "./"-prefixed entry names; strip the
                // prefix so entries resolve inside dest instead of onto dest itself
                var cleanName = name.trim()
                if (cleanName.startsWith("./")) cleanName = cleanName.substring(2)
                while (cleanName.startsWith("/")) cleanName = cleanName.substring(1)
                if (cleanName.isEmpty() || cleanName == ".") {
                    reader.skipData(size)
                    reader.skipPadding(size)
                    continue
                }

                val relative = cleanName.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
                if (relative.isEmpty()) {
                    reader.skipData(size)
                    reader.skipPadding(size)
                    continue
                }
                val target = File(dest, relative)
                when (type) {
                    '5' -> {
                        if (target.isFile) runCatching { target.delete() }
                        target.mkdirs()
                    }
                    '2' -> {
                        target.parentFile?.mkdirs()
                        runCatching { Os.symlink(linkName, target.path) }
                    }
                    '1' -> {
                        val source = File(dest, linkName)
                        if (source.isFile) source.copyTo(target, overwrite = true)
                    }
                    '0', '\u0000', '7' -> {
                        if (target.isDirectory) runCatching { target.deleteRecursively() }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            var remaining = size
                            while (remaining > 0) {
                                val n = reader.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                        reader.skipPadding(size)
                    }
                    else -> {
                        reader.skipData(size)
                        reader.skipPadding(size)
                    }
                }
                if (mode != 0L) runCatching { Os.chmod(target.path, (mode and 0xFFF).toInt()) }
            }
        } finally {
            reader.close()
        }
    }

    private class TarReader(source: InputStream) {
        private val inner = BufferedInputStream(source, 512 * 1024)
        private val block = ByteArray(512)

        fun readBlock(): ByteArray? {
            var got = 0
            while (got < 512) {
                val n = inner.read(block, got, 512 - got)
                if (n <= 0) return null
                got += n
            }
            return block
        }

        fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

        fun readDataString(size: Long): String {
            val data = ByteArray(size.toInt())
            var got = 0
            while (got < data.size) {
                val n = inner.read(data, got, data.size - got)
                if (n < 0) break
                got += n
            }
            return String(data, Charsets.UTF_8).trimEnd('\u0000')
        }

        fun skipData(size: Long): Long {
            var remaining = size
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = inner.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) break
                remaining -= n
            }
            return size - remaining
        }

        fun skipPadding(size: Long): Long {
            val padding = ((size + 511) / 512 * 512 - size)
            return if (padding > 0) skipData(padding) else 0L
        }

        fun close() = inner.close()
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var started = false
        for (i in offset until offset + length) {
            val c = header[i].toInt().toChar()
            if (c == ' ' || c == '\u0000') {
                if (started) break
                continue
            }
            started = true
            if (c in '0'..'7') value = value * 8 + (c - '0')
        }
        return value
    }
}
