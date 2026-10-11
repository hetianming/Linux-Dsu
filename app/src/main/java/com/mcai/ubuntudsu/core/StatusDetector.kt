package com.mcai.ubuntudsu.core

import android.content.Context
import android.app.ActivityManager
import android.os.Build
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
        // /proc/stat 应用直读即可（每 2s 轮询，直读零开销）；su 仅在直读被 SELinux 拦截时兜底，
        // 且探测一次后缓存，避免每次轮询反复拉起 su 拖慢实时刷新。
        runCatching { parseCpuLine(File("/proc/stat").readText()) }.getOrNull()?.let { return it }
        if (!suProbed) {
            suProbed = true
            val probe = RootShell.exec("grep '^cpu ' /proc/stat", timeoutMs = 3000)
            if (probe.success) {
                parseCpuLine(probe.stdout)?.let { suCpuReadable = true; return it }
            }
        } else if (suCpuReadable) {
            val res = RootShell.exec("grep '^cpu ' /proc/stat", timeoutMs = 3000)
            parseCpuLine(res.stdout)?.let { return it }
        }
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
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/kernel/ged/hal/gpu_loading",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/class/misc/mali0/device/utilisation",
        "/proc/gpufreq/gpufreq_var_dump",
        "/sys/devices/platform/13000000.mali/gpu_busy",
        "/sys/devices/platform/kgsl-3d0.0/kgsl/kgsl-3d0/gpu_busy_percentage",
    )
    private var lastGpuRootAttempt = 0L
    private var lastGpuValue: Int? = null
    // root 读 GPU 只在首次探测；失败（无 ROOT/节点均不存在）后不再反复拉起 su
    @Volatile private var gpuRootUsable: Boolean? = null

    private fun readGpuPercent(): Int? {
        gpuPaths.forEach { path ->
            runCatching {
                if (path.endsWith("gpufreq_var_dump")) parseGpuLoadingField(File(path).readText())
                else parseGpuNode(File(path).readText())
            }.getOrNull()?.let {
                lastGpuValue = it
                return it
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastGpuRootAttempt >= 10_000L && gpuRootUsable != false) {
            lastGpuRootAttempt = now
            val script = StringBuilder()
            gpuPaths.forEachIndexed { i, path ->
                script.append("echo __GPU").append(i).append("__\n")
                script.append("cat \"").append(path).append("\" 2>/dev/null\n")
            }
            val res = RootShell.exec(script.toString(), timeoutMs = 6000)
            var parsed: Int? = null
            if (res.success) {
                val segments = res.stdout.split(Regex("__GPU\\d+__")).drop(1)
                segments.forEachIndexed { i, seg ->
                    val path = gpuPaths.getOrNull(i) ?: return@forEachIndexed
                    val raw = seg.trim()
                    if (raw.isEmpty()) return@forEachIndexed
                    val v = if (path.endsWith("gpufreq_var_dump")) parseGpuLoadingField(raw) else parseGpuNode(raw)
                    if (v != null && parsed == null) parsed = v
                }
            }
            if (parsed != null) {
                gpuRootUsable = true
                lastGpuValue = parsed
                return parsed
            }
            gpuRootUsable = false
        }
        return lastGpuValue
    }

    /** 简单数值节点：内容形如 "42 %"、"8"。 */
    private fun parseGpuNode(raw: String): Int? = runCatching {
        val text = raw.trim().lowercase().removeSuffix("%")
        val digits = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)
        digits?.toFloatOrNull()?.toInt()?.coerceIn(0, 100)
    }.getOrNull()

    /** MTK gpufreq_var_dump：整行含 "loading = 17%"，需按字段提取（首个数字是频率）。 */
    private fun parseGpuLoadingField(raw: String): Int? = runCatching {
        Regex("loading\\s*[=:]?\\s*(\\d+)")
            .find(raw.lowercase())?.groupValues?.get(1)
            ?.toIntOrNull()?.coerceIn(0, 100)
    }.getOrNull()

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) "%.1f GB".format(java.util.Locale.US, gb) else "%.0f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
    }

    /** 首页刷新使用较短超时，避免未授权 su 让状态卡长期停在“检测中”。 */
    fun rootAvailable(timeoutMs: Long = 15_000L): Boolean =
        runCatching { RootShell.available(timeoutMs) }.getOrDefault(false)

    /**
     * ROOT 可用时优先调用 gsi_tool；未授权时仅使用无需 su 的系统属性兜底。
     * 这样在首次启动、Magisk 授权弹窗未处理或设备没有 ROOT 时也能快速结束检测。
     */
    fun gsiState(rootAccess: Boolean = rootAvailable()): Pair<GsiState, String> {
        if (rootAccess) {
            val result = RootShell.exec("gsi_tool status 2>&1 || gsi_tool getstatus 2>&1", timeoutMs = 7_000L)
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
        }
        val fp = systemProperty("ro.build.fingerprint").ifBlank { Build.FINGERPRINT }.lowercase()
        val device = systemProperty("ro.product.device").ifBlank { Build.DEVICE }.lowercase()
        val dynamic = systemProperty("ro.boot.dynamic_system").lowercase()
        return when {
            fp.contains("generic") || device.contains("gsi") || device.startsWith("generic") ->
                GsiState.RUNNING to "getprop: generic fingerprint"
            dynamic == "1" -> GsiState.INSTALLED to "dynamic_system=1"
            else -> GsiState.UNKNOWN to if (rootAccess) "gsi_tool unavailable" else "root unavailable"
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
