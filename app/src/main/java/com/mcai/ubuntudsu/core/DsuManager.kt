package com.mcai.ubuntudsu.core

import android.os.Build
import java.util.Locale

data class DsuImage(val path: String, val size: Long, val file: String) {
    fun label(): String = "$file  (${Env.formatSize(size)})"
}

data class DsuPartition(val path: String, val size: Long, val detail: String)

object DsuManager {
    private const val DSU_PACKAGE = "com.android.dynsystem"
    private const val VERIFY_ACTIVITY = "$DSU_PACKAGE/com.android.dynsystem.VerificationActivity"
    val userdataOptions = listOf(8, 16, 32, 64)

    fun status(log: ((String) -> Unit)? = null): ShellResult =
        RootShell.exec("gsi_tool status 2>&1 || gsi_tool getstatus 2>&1", timeoutMs = 20000, log = log)

    fun wipe(log: ((String) -> Unit)? = null): ShellResult =
        RootShell.exec("rm -rf /data/gsi/dsu/dsu", timeoutMs = 60000, log = log)

    fun wipeData(log: ((String) -> Unit)? = null): ShellResult =
        RootShell.exec("gsi_tool wipe-data", timeoutMs = 120000, log = log)

    fun clearInstallCache(log: ((String) -> Unit)? = null): ShellResult =
        RootShell.exec("rm -f /metadata/gsi/dsu/dsu/lp_metadata", timeoutMs = 60000, log = log)

    fun reboot(log: ((String) -> Unit)? = null): ShellResult =
        RootShell.exec("gsi_tool enable\nsetprop sys.powerctl reboot,dsu", timeoutMs = 30000, log = log)

    fun restartDsuService(log: ((String) -> Unit)? = null): ShellResult =
        RootShell.exec(
            "rm -rf /metadata/gsi/dsu\nrm -rf /metadata/vold/metadata_encryption/dsu",
            timeoutMs = 60000,
            log = log,
        )

    fun startDsuInstall(
        url: String,
        userdataGB: Int,
        log: (String) -> Unit,
    ): ShellResult {
        val command = "am start-activity -n $VERIFY_ACTIVITY " +
            "-a android.os.image.action.START_INSTALL " +
            "-d ${shellQuote(url)} " +
            "--es android.os.image.extra.STREAMING_SOURCE ${shellQuote(url)} " +
            "--el KEY_USERDATA_SIZE ${userdataGB.toLong() * 1024L * 1024L * 1024L}"
        log("-> $command")
        val result = RootShell.exec(command, timeoutMs = 120000, log = log)
        if (result.success) log("已交给系统 DSU 安装，正在读取安装进度。")
        else log("启动 DSU 安装失败，退出码 ${result.code}")
        return result
    }

    fun installProgress(zipSize: Long): Int? {
        val status = status()
        val text = (status.stdout + "\n" + status.stderr).lowercase(Locale.ROOT)
        Regex("(\\d{1,3})\\s*%").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return it.coerceIn(0, 100)
        }
        val installed = RootShell.exec(
            "du -b /data/gsi/dsu/dsu /metadata/gsi/dsu 2>/dev/null || true",
            timeoutMs = 15000,
        ).stdout.lines().sumOf { line ->
            line.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
        }
        return if (zipSize > 0 && installed > 0) {
            (installed * 100 / zipSize).toInt().coerceIn(1, 99)
        } else null
    }

    fun listImages(log: ((String) -> Unit)? = null): List<DsuImage> {
        val images = mutableListOf<DsuImage>()
        for (dir in listOf("/data/unencrypted/dsu", "/data/dsu", "/metadata/dsu")) {
            val result = RootShell.exec("ls -l $dir 2>/dev/null || true", timeoutMs = 15000, log = log)
            result.stdout.lines().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 7 && !parts[0].startsWith("total") && parts[0].startsWith("-")) {
                    val size = parts[4].toLongOrNull() ?: return@forEach
                    val name = parts.drop(6).joinToString(" ")
                    if (name.endsWith(".img") || name.endsWith(".gz") || name.endsWith(".zip") || name.endsWith(".json")) {
                        images.add(DsuImage("$dir/$name", size, name))
                    }
                }
            }
        }
        return images.distinctBy { it.path }
    }

    fun listPartitions(log: ((String) -> Unit)? = null): List<DsuPartition> {
        return listOf("/data/gsi/dsu/dsu", "/metadata/gsi/dsu", "/data/unencrypted/dsu").mapNotNull { path ->
            val result = RootShell.exec("du -sb ${shellQuote(path)} 2>/dev/null || true", timeoutMs = 15000, log = log)
            val size = result.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            DsuPartition(path, size, "${Env.formatSize(size)} · ${if (path.endsWith("/dsu")) "DSU 镜像" else "DSU 元数据"}")
        }
    }

    fun listImagePartitions(log: ((String) -> Unit)? = null): List<DsuImage> {
        val directory = "/data/gsi/dsu/dsu"
        val result = RootShell.exec("ls -l ${shellQuote(directory)} 2>/dev/null || true", timeoutMs = 15000, log = log)
        return result.stdout.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 7 || parts[0].startsWith("total") || !parts[0].startsWith("-")) return@mapNotNull null
            val size = parts[4].toLongOrNull() ?: return@mapNotNull null
            val name = parts.drop(6).joinToString(" ")
            DsuImage("$directory/$name", size, name)
        }
    }

    fun deleteImage(path: String, log: ((String) -> Unit)? = null): ShellResult =
        RootShell.exec("rm -f ${shellQuote(path)}", timeoutMs = 60000, log = log)

    fun replaceImage(source: java.io.File, target: String, log: ((String) -> Unit)? = null): ShellResult {
        val temp = "/data/local/tmp/ubuntudsu-replacement.img"
        val command = "cp ${shellQuote(source.absolutePath)} ${shellQuote(temp)} && " +
            "chmod 600 ${shellQuote(temp)} && " +
            "mkdir -p $(dirname ${shellQuote(target)}) && " +
            "cp ${shellQuote(temp)} ${shellQuote(target)}"
        return RootShell.exec(command, timeoutMs = 180000, log = log)
    }

    fun abiSummary(): String = "${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} · API ${Build.VERSION.SDK_INT}"

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
