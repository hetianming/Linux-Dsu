package com.mcai.ubuntudsu.core

import android.content.Context
import android.app.ActivityManager
import android.os.Environment
import android.os.StatFs
import java.io.File

enum class GsiState { RUNNING, INSTALLED, ENABLED, DISABLED, NORMAL, UNKNOWN }

object StatusDetector {

    data class DeviceMetrics(
        val cpuPercent: Int?,
        val gpuPercent: Int?,
        val storagePercent: Int,
        val storageUsed: String,
        val storageTotal: String,
        val memoryPercent: Int,
        val memoryUsed: String,
        val memoryTotal: String,
    )

    private data class CpuSample(val idle: Long, val total: Long)

    private var lastCpuValue: Int? = null

    fun deviceMetrics(ctx: Context, previousCpu: Any?): Pair<DeviceMetrics, Any?> {
        val currentCpu = readCpuSample()
        val computed = (previousCpu as? CpuSample)?.let { previous ->
            val totalDelta = currentCpu.total - previous.total
            val idleDelta = currentCpu.idle - previous.idle
            if (totalDelta > 0) (((totalDelta - idleDelta) * 100L) / totalDelta).toInt().coerceIn(0, 100) else null
        }
        if (computed != null) lastCpuValue = computed
        val cpuPercent = computed ?: lastCpuValue
        val memory = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val memoryUsed = memory.totalMem - memory.availMem
        val storage = StatFs(Environment.getDataDirectory().path)
        val totalStorage = storage.totalBytes
        val freeStorage = storage.availableBytes
        val usedStorage = totalStorage - freeStorage
        val gpu = readGpuPercent()
        return DeviceMetrics(
            cpuPercent = cpuPercent,
            gpuPercent = gpu,
            storagePercent = if (totalStorage > 0) ((usedStorage * 100L) / totalStorage).toInt().coerceIn(0, 100) else 0,
            storageUsed = formatBytes(usedStorage),
            storageTotal = formatBytes(totalStorage),
            memoryPercent = if (memory.totalMem > 0) ((memoryUsed * 100L) / memory.totalMem).toInt().coerceIn(0, 100) else 0,
            memoryUsed = formatBytes(memoryUsed),
            memoryTotal = formatBytes(memory.totalMem),
        ) to currentCpu
    }

    private var suCpuReadable = false
    private var suProbed = false

    private fun readCpuSample(): CpuSample {
        if (!suProbed) {
            suProbed = true
            val probe = RootShell.exec("grep '^cpu ' /proc/stat", timeoutMs = 1500)
            if (probe.success && parseCpuLine(probe.stdout) != null) {
                suCpuReadable = true
                parseCpuLine(probe.stdout)?.let { return it }
            }
        } else if (suCpuReadable) {
            val res = RootShell.exec("grep '^cpu ' /proc/stat", timeoutMs = 1500)
            parseCpuLine(res.stdout)?.let { return it }
        }
        runCatching { parseCpuLine(File("/proc/stat").readText()) }.getOrNull()?.let { return it }
        return CpuSample(0L, 0L)
    }

    private fun parseCpuLine(raw: String): CpuSample? {
        val fields = raw.lineSequence()
            .firstOrNull { it.startsWith("cpu ") }
            ?.trim()?.split(Regex("\\s+"))
            ?: return null
        val v = fields.drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 5) return null
        val idle = v[3] + v[4]
        val total = v.take(5).sum()
        return CpuSample(idle, total)
    }

    private val gpuPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percent",
        "/sys/devices/platform/kgsl-3d0.0/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/devices/platform/13000000.mali/gpu_busy",
    )
    private var lastGpuRootAttempt = 0L
    private var lastGpuValue: Int? = null

    private fun readGpuPercent(): Int? {
        gpuPaths.forEach { path ->
            runCatching { parseGpuNode(File(path).readText()) }.getOrNull()?.let {
                lastGpuValue = it
                return it
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastGpuRootAttempt >= 5_000L) {
            lastGpuRootAttempt = now
            for (path in gpuPaths) {
                val res = RootShell.exec("cat \"$path\" 2>/dev/null", timeoutMs = 2000)
                parseGpuNode(res.stdout)?.let {
                    lastGpuValue = it
                    return it
                }
            }
        }
        return lastGpuValue
    }

    private fun parseGpuNode(raw: String): Int? = runCatching {
        val text = raw.trim().lowercase().removeSuffix("%")
        val digits = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)
        digits?.toFloatOrNull()?.toInt()?.coerceIn(0, 100)
    }.getOrNull()

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) "%.1f GB".format(java.util.Locale.US, gb) else "%.0f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
    }

    fun rootAvailable(): Boolean = runCatching { RootShell.available() }.getOrDefault(false)

    fun gsiState(): Pair<GsiState, String> {
        val result = RootShell.exec("gsi_tool status 2>&1 || gsi_tool getstatus 2>&1", timeoutMs = 20000)
        val text = result.stdout.trim().lowercase()
        if (result.success && text.isNotEmpty()) {
            val state = when {
                text.contains("running") -> GsiState.RUNNING
                text.contains("enabled") -> GsiState.ENABLED
                text.contains("installed") -> GsiState.INSTALLED
                text.contains("disabled") -> GsiState.DISABLED
                text.contains("normal") -> GsiState.NORMAL
                else -> null
            }
            if (state != null) return state to text
        }
        val fp = RootShell.getprop("ro.build.fingerprint").lowercase()
        val device = RootShell.getprop("ro.product.device").lowercase()
        val dynamic = RootShell.getprop("ro.boot.dynamic_system").lowercase()
        return when {
            fp.contains("generic") || device.contains("gsi") || device.startsWith("generic") ->
                GsiState.RUNNING to "getprop: generic fingerprint"
            dynamic == "1" -> GsiState.INSTALLED to "dynamic_system=1"
            else -> GsiState.UNKNOWN to "gsi_tool unavailable"
        }
    }

    fun dsuSupported(): Boolean {
        val check = RootShell.exec(
            "[ -e /system/priv-app/DynamicSystemInstallationService ] && echo yes; " +
                "ls /system/etc/permissions/android.software.dynamic_system.xml 2>/dev/null",
            timeoutMs = 15000
        )
        return check.stdout.contains("yes") ||
            check.stdout.contains("dynamic_system") ||
            RootShell.getprop("ro.boot.dynamic_partitions") == "true"
    }

    fun ubuntuSummary(ctx: Context): String {
        if (!Env.ubuntuInstalled(ctx)) return "未安装"
        return "已安装"
    }

    private fun systemProperty(name: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    fun deviceSummary(): String {
        val model = systemProperty("ro.product.model")
        val version = systemProperty("ro.build.version.release")
        return listOf(model, "Android $version").filter { it.isNotBlank() }.joinToString(" · ")
    }
}
