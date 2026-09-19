package com.mcai.ubuntudsu.core

import android.app.ActivityManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.util.concurrent.TimeUnit

data class AppProcessInfo(
    val packageName: String,
    val appLabel: String,
    val isSystemApp: Boolean,
    val isOwnApp: Boolean,
    val pids: List<Int>,
    val processCount: Int,
    val cpuPercent: Float,
    val memoryKb: Long,
    val foregroundTimeMs: Long,
    val lastUsedTime: Long,
    val isRunning: Boolean,
    val hasRunningService: Boolean,
)

enum class SortMode {
    CPU, MEMORY, FOREGROUND_TIME, LAST_USED, NAME
}

object ProcessScanner {

    private data class ProcStat(
        val pid: Int,
        val name: String,
        val utime: Long,
        val stime: Long,
    )

    private data class CpuSnapshot(
        val totalJiffies: Long,
        val procJiffies: Map<Int, Long>,
        val timestamp: Long,
    )

    private var lastSnapshot: CpuSnapshot? = null

    /**
     * 获取所有安装的应用及其进程信息
     */
    fun scanApps(context: Context, sortMode: SortMode = SortMode.CPU): List<AppProcessInfo> {
        val pm = context.packageManager
        val myPkg = context.packageName

        // 1. 获取所有已安装应用
        val installedApps = getInstalledApps(pm)

        // 2. 通过 ROOT 读取所有进程（最完整）
        var allProcesses = readAllProcessesViaRoot()

        // 2.5 如果 ROOT 读取失败，用 ActivityManager 回退
        if (allProcesses.isEmpty()) {
            allProcesses = readProcessesViaActivityManager(context)
        }

        // 2.6 也尝试直接读取 /proc（不需要 root，能读到部分进程）
        if (allProcesses.isEmpty()) {
            allProcesses = readProcessesDirectly()
        }

        // 3. 获取运行中的服务
        val runningServices = getRunningServices(context)

        // 4. 获取使用情况（前台时间等）
        val usageStats = getUsageStats(context)

        // 5. CPU 快照计算
        val cpuPercents = calculateCpuPercents(allProcesses)

        // 5.5 批量读取所有进程内存（一次ROOT调用，速度快很多）
        val memoryMap = readAllProcessMemoryViaRoot(allProcesses.map { it.pid })

        // 6. 按包名聚合
        // 先构建进程名 -> 包名 的映射表（从已安装应用反查）
        val pkgNameMap = mutableMapOf<String, String>()
        for (app in installedApps) {
            pkgNameMap[app.packageName] = app.packageName
            // 子进程格式：com.example.app:service
        }

        val pkgToProcesses = allProcesses.groupBy { proc ->
            resolvePackageFromProcess(proc.name, pm, pkgNameMap)
        }

        // 7. 构建应用信息列表
        val result = installedApps.map { app ->
            val pkg = app.packageName
            val procList = pkgToProcesses[pkg] ?: emptyList()
            val pids = procList.map { it.pid }
            val totalCpu = procList.sumOf { cpuPercents[it.pid]?.toDouble() ?: 0.0 }.toFloat()
            val totalMem = procList.sumOf { memoryMap[it.pid] ?: 0L }
            val usage = usageStats[pkg]

            AppProcessInfo(
                packageName = pkg,
                appLabel = pm.getApplicationLabel(app).toString(),
                isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    || (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                isOwnApp = pkg == myPkg,
                pids = pids,
                processCount = procList.size,
                cpuPercent = totalCpu,
                memoryKb = totalMem,
                foregroundTimeMs = usage?.totalTimeInForeground ?: 0L,
                lastUsedTime = usage?.lastTimeUsed ?: 0L,
                isRunning = procList.isNotEmpty(),
                hasRunningService = runningServices.contains(pkg),
            )
        }

        // 8. 排序
        return when (sortMode) {
            SortMode.CPU -> result.sortedByDescending { it.cpuPercent }
            SortMode.MEMORY -> result.sortedByDescending { it.memoryKb }
            SortMode.FOREGROUND_TIME -> result.sortedByDescending { it.foregroundTimeMs }
            SortMode.LAST_USED -> result.sortedByDescending { it.lastUsedTime }
            SortMode.NAME -> result.sortedBy { it.appLabel.lowercase() }
        }
    }

    /**
     * 获取正在运行的应用（有进程或服务在运行）
     */
    fun getRunningApps(context: Context, sortMode: SortMode = SortMode.CPU): List<AppProcessInfo> {
        return scanApps(context, sortMode).filter { it.isRunning || it.hasRunningService }
    }

    /**
     * 强制停止应用（ROOT 权限）
     */
    fun forceStopApp(packageName: String): Boolean {
        val result = RootShell.exec("am force-stop $packageName", timeoutMs = 5000)
        return result.success
    }

    /**
     * 结束进程（ROOT 权限）
     */
    fun killProcess(pid: Int): Boolean {
        val result = RootShell.exec("kill -9 $pid", timeoutMs = 3000)
        return result.success
    }

    /**
     * 结束应用的所有进程
     */
    fun killAppProcesses(packageName: String, context: Context): Boolean {
        // 先尝试 am force-stop
        val forceStop = forceStopApp(packageName)
        if (forceStop) return true

        // 回退：找到所有进程并 kill
        val processes = readAllProcessesViaRoot()
        val pm = context.packageManager
        var killed = false
        for (proc in processes) {
            val pkg = resolvePackageFromProcess(proc.name, pm)
            if (pkg == packageName) {
                if (killProcess(proc.pid)) killed = true
            }
        }
        return killed
    }

    // ========== 内部方法 ==========

    private fun getInstalledApps(pm: PackageManager): List<ApplicationInfo> {
        return runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
    }

    /**
     * 通过 ROOT 读取所有进程列表
     */
    private fun readAllProcessesViaRoot(): List<ProcStat> {
        // 用一条命令批量读取所有 /proc/*/stat，用 awk 提取 pid/name/utime/stime
        // stat 格式：pid (name) state ... utime(14) stime(15) ...
        // 注意：name 在括号内，可能包含空格和括号，需要特殊处理
        val result = RootShell.exec(
            "cd /proc && for p in [0-9]*; do" +
                "  if [ -r \"\$p/stat\" ]; then" +
                "    cat \"\$p/stat\"" +
                "  fi" +
                "done",
            timeoutMs = 5000
        )

        if (!result.success || result.stdout.isBlank()) return emptyList()

        val stats = mutableListOf<ProcStat>()
        // 每个 stat 一行，直接解析
        for (line in result.stdout.lineSequence()) {
            val stat = parseProcStatLine(line.trim())
            if (stat != null) stats.add(stat)
        }
        return stats
    }

    private fun readProcessesViaPs(): List<ProcStat> {
        val result = RootShell.exec("ps -A -o PID,NAME,TIME", timeoutMs = 5000)
        if (!result.success) return emptyList()

        val stats = mutableListOf<ProcStat>()
        val lines = result.stdout.lineSequence().drop(1) // 跳过标题行
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val name = parts[1]
            // TIME 格式为 "HH:MM:SS" 或 "M:SS"，转成 jiffies（近似）
            val timeStr = parts[2]
            val timeParts = timeStr.split(":")
            var totalSec = 0L
            if (timeParts.size == 3) {
                totalSec = timeParts[0].toLong() * 3600 + timeParts[1].toLong() * 60 + timeParts[2].toLong()
            } else if (timeParts.size == 2) {
                totalSec = timeParts[0].toLong() * 60 + timeParts[1].toLong()
            }
            val jiffies = totalSec * 100 // 近似 100 HZ
            stats.add(ProcStat(pid, name, jiffies, 0L))
        }
        return stats
    }

    /**
     * 通过 ActivityManager 读取运行中的进程（不需要 ROOT）
     * Android 8+ 只返回自己的进程，但至少能读到一些
     */
    private fun readProcessesViaActivityManager(context: Context): List<ProcStat> {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses ?: emptyList()
            processes.map { pinfo ->
                val pid = pinfo.pid
                val name = pinfo.processName
                // 尝试读取 /proc/pid/stat 获取 utime/stime
                var utime = 0L
                var stime = 0L
                runCatching {
                    val statLine = java.io.File("/proc/$pid/stat").readText()
                    val parsed = parseProcStatLine(statLine)
                    if (parsed != null) {
                        utime = parsed.utime
                        stime = parsed.stime
                    }
                }
                ProcStat(pid, name, utime, stime)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 直接读取 /proc 目录（不需要 ROOT，能读到部分进程）
     */
    private fun readProcessesDirectly(): List<ProcStat> {
        val stats = mutableListOf<ProcStat>()
        val procDir = java.io.File("/proc")
        val dirs = procDir.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d+")) } ?: return emptyList()
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            runCatching {
                val statFile = java.io.File(dir, "stat")
                if (statFile.canRead()) {
                    val line = statFile.readText().lineSequence().firstOrNull()
                    if (line != null) {
                        parseProcStatLine(line)?.let { stats.add(it) }
                    }
                }
            }
        }
        return stats
    }

    private fun parseProcStatBlock(content: String): ProcStat? {
        if (content.isBlank()) return null
        val line = content.lineSequence().firstOrNull() ?: return null
        return parseProcStatLine(line)
    }

    private fun parseProcStatLine(line: String): ProcStat? {
        try {
            val openParen = line.indexOf('(')
            val closeParen = line.lastIndexOf(')')
            if (openParen < 0 || closeParen < 0) return null

            val pid = line.substring(0, openParen).trim().toIntOrNull() ?: return null
            val name = line.substring(openParen + 1, closeParen)
            val rest = line.substring(closeParen + 2).split(Regex("\\s+"))

            if (rest.size < 20) return null

            val utime = rest[11].toLongOrNull() ?: 0L
            val stime = rest[12].toLongOrNull() ?: 0L

            return ProcStat(pid, name, utime, stime)
        } catch (_: Exception) {
            return null
        }
    }

    private fun calculateCpuPercents(processes: List<ProcStat>): Map<Int, Float> {
        val totalJiffies = readTotalCpuJiffies()
        val procJiffies = processes.associate { it.pid to (it.utime + it.stime) }
        val currentSnapshot = CpuSnapshot(totalJiffies, procJiffies, System.currentTimeMillis())

        val result = mutableMapOf<Int, Float>()
        val last = lastSnapshot
        if (last != null) {
            val totalDelta = (currentSnapshot.totalJiffies - last.totalJiffies).coerceAtLeast(1L)
            for ((pid, current) in currentSnapshot.procJiffies) {
                val lastJiffies = last.procJiffies[pid] ?: continue
                val delta = (current - lastJiffies).coerceAtLeast(0L)
                val percent = (delta * 100f) / totalDelta
                result[pid] = percent
            }
        }

        lastSnapshot = currentSnapshot
        return result
    }

    private fun readTotalCpuJiffies(): Long {
        // 通过 ROOT 读取，确保权限足够
        val result = RootShell.exec("grep '^cpu ' /proc/stat", timeoutMs = 2000)
        if (result.success) {
            parseTotalCpuLine(result.stdout)?.let { return it }
        }
        // 回退：直接读
        return runCatching {
            val line = java.io.File("/proc/stat").readLines()
                .firstOrNull { it.startsWith("cpu ") } ?: return 0L
            parseTotalCpuLine(line) ?: 0L
        }.getOrDefault(0L)
    }

    private fun parseTotalCpuLine(line: String): Long? {
        val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.size < 7) return null
        return fields.take(7).sum()
    }

    /**
     * 批量读取所有进程的内存占用（一次 ROOT 调用，大幅提速）
     */
    private fun readAllProcessMemoryViaRoot(pids: List<Int>): Map<Int, Long> {
        if (pids.isEmpty()) return emptyMap()

        // 用一条命令批量读取所有 /proc/pid/status 中的 VmRSS
        // 输出格式：PID: VmRSS值
        val result = RootShell.exec(
            "cd /proc && for p in [0-9]*; do" +
                "  if [ -r \"\$p/status\" ]; then" +
                "    rss=\$(grep 'VmRSS:' \"\$p/status\" 2>/dev/null | awk '{print \$2}')" +
                "    if [ -n \"\$rss\" ]; then echo \"\$p:\$rss\"; fi" +
                "  fi" +
                "done",
            timeoutMs = 6000
        )

        if (!result.success || result.stdout.isBlank()) return emptyMap()

        val memoryMap = mutableMapOf<Int, Long>()
        val regex = Regex("""(\d+):(\d+)""")
        for (line in result.stdout.lineSequence()) {
            val match = regex.find(line.trim()) ?: continue
            val pid = match.groupValues[1].toIntOrNull() ?: continue
            val kb = match.groupValues[2].toLongOrNull() ?: continue
            memoryMap[pid] = kb
        }
        return memoryMap
    }

    private fun readProcessMemory(pid: Int): Long {
        return runCatching {
            val file = java.io.File("/proc/$pid/status")
            if (file.canRead()) {
                var totalKb = 0L
                file.forEachLine { line ->
                    if (line.startsWith("VmRSS:")) {
                        val kb = line.removePrefix("VmRSS:").trim().removeSuffix("kB").trim().toLongOrNull() ?: 0L
                        totalKb += kb
                    }
                }
                totalKb
            } else {
                0L
            }
        }.getOrDefault(0L)
    }

    /**
     * 获取运行中的服务（多途径）
     */
    private fun getRunningServices(context: Context): Set<String> {
        val result = mutableSetOf<String>()

        // 途径1: ActivityManager.getRunningServices（Android 8+ 只返回自己的）
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = am.getRunningServices(200)
            services.mapNotNull { it.service?.packageName }.forEach { result.add(it) }
        }

        // 途径2: ActivityManager.getRunningAppProcesses（能返回更多进程）
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses
            processes?.forEach { pinfo ->
                // importance <= RUNNING_SERVICE_STATE 表示在运行
                if (pinfo.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) {
                    val pkg = if (pinfo.processName.contains(':')) {
                        pinfo.processName.substring(0, pinfo.processName.indexOf(':'))
                    } else {
                        pinfo.processName
                    }
                    result.add(pkg)
                }
            }
        }

        return result
    }

    private fun getUsageStats(context: Context): Map<String, UsageStats> {
        return runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - TimeUnit.DAYS.toMillis(1) // 过去24小时
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime)
            stats.associateBy { it.packageName }
        }.getOrDefault(emptyMap())
    }

    private fun resolvePackageFromProcess(
        processName: String,
        pm: PackageManager,
        pkgNameMap: Map<String, String> = emptyMap(),
    ): String? {
        // 先查映射表（精确匹配）
        if (pkgNameMap.containsKey(processName)) {
            return pkgNameMap[processName]
        }

        // 进程名通常就是包名（对主进程而言）
        if (processName.contains(".")) {
            runCatching {
                pm.getPackageInfo(processName, 0)
                return processName
            }
        }

        // 对于子进程（如 com.app:push），提取主包名
        val colonIdx = processName.indexOf(':')
        if (colonIdx > 0) {
            val basePkg = processName.substring(0, colonIdx)
            if (pkgNameMap.containsKey(basePkg)) return pkgNameMap[basePkg]
            runCatching {
                pm.getPackageInfo(basePkg, 0)
                return basePkg
            }
        }

        // 尝试模糊匹配：如果进程名包含已知包名
        for (pkg in pkgNameMap.keys) {
            if (processName.startsWith(pkg)) return pkg
        }

        return null
    }

    /**
     * 格式化内存大小
     */
    fun formatMemory(kb: Long): String {
        return when {
            kb >= 1024 * 1024 -> "%.1f GB".format(kb / (1024.0 * 1024.0))
            kb >= 1024 -> "%.1f MB".format(kb / 1024.0)
            else -> "$kb KB"
        }
    }

    /**
     * 格式化 CPU 占用率
     */
    fun formatCpu(percent: Float): String {
        return if (percent < 0.1f) "0.0%" else "%.1f%%".format(percent)
    }

    /**
     * 格式化前台运行时间
     */
    fun formatForegroundTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        return when {
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分钟"
            else -> "少于1分钟"
        }
    }

    /**
     * 格式化最后使用时间
     */
    fun formatLastUsed(timeMs: Long): String {
        if (timeMs == 0L) return "从未使用"
        val diff = System.currentTimeMillis() - timeMs
        val minutes = diff / 60000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 7 -> "${days}天前"
            else -> "${days / 7}周前"
        }
    }
}
