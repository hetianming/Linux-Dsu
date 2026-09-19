package com.mcai.ubuntudsu.core

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

data class AppProcessInfo(
    val packageName: String,
    val appLabel: String,
    val isSystemApp: Boolean,
    val isOwnApp: Boolean,
    val pids: List<Int>,
    val processCount: Int,
    /** 采样区间内的实时 CPU 占用（占整机总算力百分比，双点实测） */
    val cpuPercent: Float,
    /** 开机以来累计 CPU 时间（jiffies，来自 /proc/[pid]/stat 真实内核数据） */
    val cpuTimeJiffies: Long,
    /** 累计耗电占比（按累计 CPU 时间折算，0-100） */
    val powerSharePercent: Float,
    /** 内存（root RSS 实测 / 框架 PSS，KB） */
    val memoryKb: Long,
    val foregroundTimeMs: Long,
    val lastUsedTime: Long,
    val isRunning: Boolean,
    val hasRunningService: Boolean,
)

/** 系统级真实指标：/proc/stat 双点采样 + ActivityManager.MemoryInfo + /proc/meminfo */
data class SystemStats(
    val cpuPercent: Float,
    val perCorePercents: List<Float>,
    val cpuCores: Int,
    val memTotalKb: Long,
    val memUsedKb: Long,
    val memAvailKb: Long,
    val swapTotalKb: Long,
    val swapUsedKb: Long,
    val processCount: Int,
    val loadAvg: String,
)

data class ScanResult(
    val apps: List<AppProcessInfo>,
    val system: SystemStats,
    val usedRoot: Boolean,
    /** 当前前台应用包名（事件流 / ROOT dumpsys 实测，均失败为 null） */
    val foregroundPackage: String?,
    /** 任务栏（最近任务）后台应用：包名 → 最近活跃时间；保持任务栏顺序 */
    val recentPackages: Map<String, Long>,
    /** 是否已授予"使用情况访问"权限（后台应用列表的关键权限） */
    val usageAccessGranted: Boolean,
)

enum class SortMode {
    CPU, MEMORY, POWER, FOREGROUND_TIME, LAST_USED, NAME
}

object ProcessScanner {

    /** CPU 双点采样间隔（Java 侧计时，不依赖 su 内 sleep） */
    private const val SAMPLE_INTERVAL_MS = 1000L

    /**
     * 双点采样最小整机增量（jiffies）：低于该值说明两点几乎同刻，CPU% 无意义。
     * 8 核 × 100Hz × 1s ≈ 800，取保守下限。
     */
    private const val MIN_CPU_DELTA_JIFFIES = 200L

    private class ProcStat(val pid: Int, val name: String, val jiffies: Long)

    /** /proc/stat 聚合行 + 各核心行 */
    private class CpuLine(val total: Long, val idle: Long, val cores: List<Pair<Long, Long>>)

    /** 内核时钟频率（USER_HZ），jiffies → 秒换算 */
    private val clockHz: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrNull()?.takeIf { it > 0 } ?: 100L
    }

    /**
     * 扫描全部应用（参考系统任务管理器架构）：
     * 1. 进程清单：ROOT 读取 /proc 下全部进程 stat（全量）→ 失败回退框架 runningAppProcesses + getRunningServices
     * 2. CPU：/proc 双点采样（ROOT 小命令或直读，间隔由 Java 计时）
     * 3. 内存：ROOT RSS / 框架 getProcessMemoryInfo(PSS) / 直读 VmRSS
     * 4. 后台应用（任务栏）：UsageStats 事件流（需使用情况权限）→ ROOT dumpsys recents
     */
    fun scan(context: Context, sortMode: SortMode = SortMode.CPU): ScanResult {
        val pm = context.packageManager
        val myPkg = context.packageName
        val installedApps = getInstalledApps(pm)
        val pkgSet = HashSet<String>(installedApps.map { it.packageName })
        val usageStats = getUsageStats(context)
        val usageAccessGranted = usageStats.isNotEmpty() || hasUsageStatsPermission(context)

        // ===== 1. ROOT 双点采样：拆成独立小命令，单条失败不拖累整体 =====
        var usedRoot = false
        var cpuA: CpuLine? = null
        var cpuB: CpuLine? = null
        var procA: Map<Int, ProcStat>? = null
        var procB: Map<Int, ProcStat>? = null
        var rssMap: Map<Int, Long> = emptyMap()
        var pidUid: Map<Int, Int> = emptyMap()

        val rootA = readRootSnapshot()
        if (rootA != null) {
            SystemClock.sleep(SAMPLE_INTERVAL_MS)
            val rootB = readRootSnapshot()
            if (rootB != null && rootB.first.total - rootA.first.total >= MIN_CPU_DELTA_JIFFIES) {
                cpuA = rootA.first
                cpuB = rootB.first
                procA = rootA.second
                procB = rootB.second
                val status = readRootStatus()
                rssMap = status.first
                pidUid = status.second
                usedRoot = true
            }
        }

        // ===== 2. 无 ROOT 回退：直读 /proc（/proc/stat 世界可读，系统 CPU 必有值）=====
        val frameworkNames = mutableMapOf<Int, String>()
        val frameworkUids = mutableMapOf<Int, Int>()
        if (!usedRoot) {
            cpuA = readCpuLineDirect()
            val directA = readDirectProcStats()
            SystemClock.sleep(SAMPLE_INTERVAL_MS)
            cpuB = readCpuLineDirect()
            val directB = readDirectProcStats()
            if (directA.isNotEmpty() && directB.isNotEmpty()) {
                procA = directA.associateBy { it.pid }
                procB = directB.associateBy { it.pid }
            }
            rssMap = readAllMemoryDirect(procB?.keys ?: emptySet())
            // 框架进程清单（直读受限时仍能列出运行中的应用）
            collectFrameworkProcesses(context, frameworkNames, frameworkUids)
        }

        // ===== 3. pid → 包名：uid 精确映射优先 =====
        // /proc/[pid]/stat 的 comm 被内核截断为 15 字符（如 com.google.andr），
        // 直接按进程名匹配必然失败，这正是列表全 0 的根因；
        // uid → 包名是系统级唯一映射（readRootStatus / RunningAppProcessInfo 提供），
        // 隔离进程 uid(99000+) 查不到包时回退截断名前缀匹配。
        val uidPkgCache = mutableMapOf<Int, String?>()
        fun pkgForUid(uid: Int): String? = uidPkgCache.getOrPut(uid) {
            runCatching { pm.getPackagesForUid(uid) }.getOrNull()
                ?.firstOrNull { it in pkgSet }
        }
        val allProcs = procB ?: procA ?: emptyMap()
        val pidPkg = mutableMapOf<Int, String>()
        for ((pid, stat) in allProcs) {
            val byUid = pidUid[pid]?.let { pkgForUid(it) }
            if (byUid != null) {
                pidPkg[pid] = byUid
                continue
            }
            resolvePackageFromProcess(stat.name, pm, pkgSet)?.let { pidPkg[pid] = it }
        }
        for ((pid, name) in frameworkNames) {
            if (pidPkg.containsKey(pid)) continue
            val byUid = frameworkUids[pid]?.let { pkgForUid(it) }
            if (byUid != null) {
                pidPkg[pid] = byUid
                continue
            }
            resolvePackageFromProcess(name, pm, pkgSet)?.let { pidPkg[pid] = it }
        }

        // ===== 4. 内存：框架 PSS 批量接口（系统设置同款，部分 ROM 对已授权应用返回真实值）=====
        val pssMap = if (usedRoot) emptyMap() else readPssBatch(context, pidPkg.keys)

        // ===== 5. CPU%：进程 jiffies 增量 / 整机 jiffies 增量 =====
        val sysDelta = if (cpuA != null && cpuB != null && cpuB.total > cpuA.total) {
            cpuB.total - cpuA.total
        } else 0L
        val cpuPct = mutableMapOf<Int, Float>()
        if (procA != null && procB != null && sysDelta > 0) {
            for ((pid, now) in procB) {
                val before = procA[pid] ?: continue
                val delta = (now.jiffies - before.jiffies).coerceAtLeast(0L)
                if (delta > 0) cpuPct[pid] = delta * 100f / sysDelta
            }
        }

        // ===== 6. 累计 CPU 时间 → 耗电占比 =====
        val cumulative = procB ?: procA ?: emptyMap()
        val totalAppJiffies = pidPkg.keys.sumOf { cumulative[it]?.jiffies ?: 0L }.coerceAtLeast(1L)

        // ===== 7. 运行中的服务 =====
        val runningServices = getRunningServices(context)

        // ===== 8. 按包名聚合 =====
        val pkgPids = pidPkg.entries.groupBy({ it.value }, { it.key })
        val result = installedApps.map { app ->
            val pkg = app.packageName
            val pids = pkgPids[pkg].orEmpty()
            val totalJiffies = pids.sumOf { cumulative[it]?.jiffies ?: 0L }
            AppProcessInfo(
                packageName = pkg,
                appLabel = pm.getApplicationLabel(app).toString(),
                isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                isOwnApp = pkg == myPkg,
                pids = pids,
                processCount = pids.size,
                cpuPercent = pids.sumOf { cpuPct[it]?.toDouble() ?: 0.0 }.toFloat(),
                cpuTimeJiffies = totalJiffies,
                powerSharePercent = totalJiffies * 100f / totalAppJiffies,
                memoryKb = pids.sumOf { pid ->
                    rssMap[pid]?.takeIf { it > 0 } ?: pssMap[pid] ?: 0L
                },
                foregroundTimeMs = usageStats[pkg]?.totalTimeInForeground ?: 0L,
                lastUsedTime = usageStats[pkg]?.lastTimeUsed ?: 0L,
                isRunning = pids.isNotEmpty(),
                hasRunningService = runningServices.contains(pkg),
            )
        }

        val apps = when (sortMode) {
            SortMode.CPU -> result.sortedWith(
                compareByDescending<AppProcessInfo> { it.cpuPercent }.thenByDescending { it.isRunning }
            )
            SortMode.MEMORY -> result.sortedByDescending { it.memoryKb }
            SortMode.POWER -> result.sortedByDescending { it.powerSharePercent }
            SortMode.FOREGROUND_TIME -> result.sortedByDescending { it.foregroundTimeMs }
            SortMode.LAST_USED -> result.sortedByDescending { it.lastUsedTime }
            SortMode.NAME -> result.sortedBy { it.appLabel.lowercase() }
        }

        return ScanResult(
            apps,
            buildSystemStats(context, cpuA, cpuB, allProcs.size + frameworkNames.size),
            usedRoot,
            getForegroundPackage(context),
            getRecentTaskPackages(context),
            usageAccessGranted,
        )
    }

    /** 强制停止应用（ROOT 权限） */
    fun forceStopApp(packageName: String): Boolean =
        RootShell.exec("am force-stop $packageName", timeoutMs = 5000).success

    /** 结束进程（ROOT 权限） */
    fun killProcess(pid: Int): Boolean =
        RootShell.exec("kill -9 $pid", timeoutMs = 3000).success

    /** 结束应用的所有进程：force-stop 优先，回退按进程名匹配逐个 kill */
    fun killAppProcesses(packageName: String, context: Context): Boolean {
        if (forceStopApp(packageName)) return true
        val pm = context.packageManager
        val pkgSet = setOf(packageName)
        var killed = false
        for ((pid, name) in readProcessNamesOnce()) {
            if (resolvePackageFromProcess(name, pm, pkgSet) == packageName) {
                if (killProcess(pid)) killed = true
            }
        }
        return killed
    }

    // ========== ROOT 采样（小命令独立执行，互不拖累）==========

    /** 一次 su 小命令：/proc/stat + 全部进程 stat（输出 ~200KB，秒级完成） */
    private fun readRootSnapshot(): Pair<CpuLine, Map<Int, ProcStat>>? {
        // cat 失败的进程只报 stderr，不影响 stdout
        val result = RootShell.exec(
            "cat /proc/stat; echo __P__; cat /proc/[0-9]*/stat 2>/dev/null; echo __END__",
            timeoutMs = 8000,
            log = {},
        )
        if (!result.success) return null
        val out = result.stdout
        val idxP = out.indexOf("__P__")
        val idxEnd = out.indexOf("__END__")
        if (idxP < 0 || idxEnd <= idxP) return null

        val cpu = parseCpuLines(out.substring(0, idxP).lineSequence()) ?: return null
        val procs = parseProcStats(out.substring(idxP + 5, idxEnd).lineSequence())
        if (procs.isEmpty()) return null
        return cpu to procs
    }

    /**
     * 一次 su 小命令：全部进程 Uid + VmRSS（单次 grep，避免逐进程 spawn）。
     * Uid 用于 pid → 包名精确映射（comm 被内核截断为 15 字符，不可靠）。
     */
    private fun readRootStatus(): Pair<Map<Int, Long>, Map<Int, Int>> {
        val result = RootShell.exec(
            "grep -H -E '^(Uid|VmRSS):' /proc/[0-9]*/status 2>/dev/null; echo __END__",
            timeoutMs = 8000,
            log = {},
        )
        if (!result.success) return emptyMap<Int, Long>() to emptyMap<Int, Int>()
        // 输出格式：/proc/1234/status:Uid:	10234	10234 ... 与 /proc/1234/status:VmRSS:	5678 kB
        val regex = Regex("""/proc/(\d+)/status:(Uid|VmRSS):\s+(\d+)""")
        val rss = mutableMapOf<Int, Long>()
        val uid = mutableMapOf<Int, Int>()
        for (m in regex.findAll(result.stdout)) {
            val pid = m.groupValues[1].toIntOrNull() ?: continue
            when (m.groupValues[2]) {
                "Uid" -> m.groupValues[3].toIntOrNull()?.let { if (it > 0) uid[pid] = it }
                "VmRSS" -> m.groupValues[3].toLongOrNull()?.let { if (it > 0) rss[pid] = it }
            }
        }
        return rss to uid
    }

    // ========== 直读 /proc（无需 ROOT）==========

    private fun readCpuLineDirect(): CpuLine? =
        runCatching { File("/proc/stat").useLines { parseCpuLines(it) } }.getOrNull()

    private fun readDirectProcStats(): List<ProcStat> {
        val stats = mutableListOf<ProcStat>()
        val dirs = runCatching {
            File("/proc").listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
        }.getOrNull() ?: return stats
        for (dir in dirs) {
            val line = runCatching { File(dir, "stat").readText() }.getOrNull()?.trim() ?: continue
            parseProcStatLine(line)?.let { stats.add(it) }
        }
        return stats
    }

    private fun readAllMemoryDirect(pids: Collection<Int>): Map<Int, Long> {
        val memory = mutableMapOf<Int, Long>()
        for (pid in pids) {
            val kb = runCatching {
                File("/proc/$pid/status").useLines { seq ->
                    seq.firstOrNull { it.startsWith("VmRSS:") }
                        ?.trim()?.split(Regex("\\s+"))
                        ?.getOrNull(1)?.toLongOrNull()
                }
            }.getOrNull() ?: continue
            if (kb > 0) memory[pid] = kb
        }
        return memory
    }

    // ========== 框架 API ==========

    /** 框架进程清单：runningAppProcesses + getRunningServices（无 ROOT 时兜底，含 uid 精确映射） */
    private fun collectFrameworkProcesses(
        context: Context,
        names: MutableMap<Int, String>,
        uids: MutableMap<Int, Int>,
    ) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.forEach {
                names[it.pid] = it.processName
                if (it.uid > 0) uids[it.pid] = it.uid
            }
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningServices(200)?.forEach { svc ->
                if (svc.pid > 0) {
                    svc.service?.packageName?.let { names[svc.pid] = it }
                    if (svc.uid > 0) uids[svc.pid] = svc.uid
                }
            }
        }
    }

    /** 框架内存接口（系统设置同款）：批量取各进程 PSS，部分 ROM 需使用情况权限 */
    private fun readPssBatch(context: Context, pids: Collection<Int>): Map<Int, Long> {
        if (pids.isEmpty()) return emptyMap()
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = am.getProcessMemoryInfo(pids.toIntArray())
            val map = mutableMapOf<Int, Long>()
            pids.forEachIndexed { i, pid ->
                val pss = infos.getOrNull(i)?.totalPss ?: 0
                if (pss > 0) map[pid] = pss.toLong()
            }
            map
        }.getOrDefault(emptyMap())
    }

    /** 一次性 pid → 进程名（ROOT 优先，直读回退），用于结束进程 */
    private fun readProcessNamesOnce(): Map<Int, String> {
        val result = RootShell.exec(
            "cat /proc/[0-9]*/stat 2>/dev/null; echo __END__",
            timeoutMs = 5000,
            log = {},
        )
        if (result.success) {
            val names = mutableMapOf<Int, String>()
            parseProcStats(result.stdout.lineSequence()).forEach { (pid, stat) -> names[pid] = stat.name }
            if (names.isNotEmpty()) return names
        }
        return readDirectProcStats().associate { it.pid to it.name }
    }

    // ========== 解析 ==========

    /** 解析 /proc/stat：聚合行 + 各核心行（"cpu  123 0 456 ..." 双空格安全） */
    private fun parseCpuLines(lines: Sequence<String>): CpuLine? {
        var total = 0L
        var idle = 0L
        val cores = mutableListOf<Pair<Long, Long>>()
        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("cpu")) continue
            val fields = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (fields.size < 5) continue
            val t = fields.take(8).sum()
            val i = fields[3] + fields[4] // idle + iowait
            if (line.startsWith("cpu ")) {
                total = t
                idle = i
            } else {
                cores.add(t to i)
            }
        }
        if (total <= 0L) return null
        return CpuLine(total, idle, cores)
    }

    private fun parseProcStats(lines: Sequence<String>): Map<Int, ProcStat> {
        val map = mutableMapOf<Int, ProcStat>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || !line[0].isDigit()) continue
            parseProcStatLine(line)?.let { map[it.pid] = it }
        }
        return map
    }

    private fun parseProcStatLine(line: String): ProcStat? {
        return try {
            val openParen = line.indexOf('(')
            val closeParen = line.lastIndexOf(')')
            if (openParen < 1 || closeParen < openParen) return null
            val pid = line.substring(0, openParen).trim().toIntOrNull() ?: return null
            val name = line.substring(openParen + 1, closeParen)
            val rest = line.substring(closeParen + 2).split(Regex("\\s+"))
            if (rest.size < 20) return null
            val utime = rest[11].toLongOrNull() ?: 0L
            val stime = rest[12].toLongOrNull() ?: 0L
            ProcStat(pid, name, utime + stime)
        } catch (_: Exception) {
            null
        }
    }

    // ========== 系统级指标 ==========

    private fun buildSystemStats(context: Context, a: CpuLine?, b: CpuLine?, processCount: Int): SystemStats {
        val cores = b?.cores?.size ?: a?.cores?.size ?: Runtime.getRuntime().availableProcessors()

        // 整机 CPU：聚合行增量（total - idle）
        val cpuPercent = if (a != null && b != null && b.total > a.total) {
            val dTotal = b.total - a.total
            val dIdle = (b.idle - a.idle).coerceIn(0L, dTotal)
            ((dTotal - dIdle) * 100f / dTotal).coerceIn(0f, 100f)
        } else 0f

        // 各核心：cpu0..cpuN 行增量
        val perCore = if (a != null && b != null && a.cores.isNotEmpty() && a.cores.size == b.cores.size) {
            a.cores.zip(b.cores).map { (ca, cb) ->
                val dTotal = (cb.first - ca.first).coerceAtLeast(1L)
                val dIdle = (cb.second - ca.second).coerceIn(0L, dTotal)
                ((dTotal - dIdle) * 100f / dTotal).coerceIn(0f, 100f)
            }
        } else {
            List(cores.coerceAtLeast(1)) { 0f }
        }

        val mi = ActivityManager.MemoryInfo()
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        }

        var swapTotal = 0L
        var swapFree = 0L
        runCatching {
            for (line in File("/proc/meminfo").readLines()) {
                when {
                    line.startsWith("SwapTotal:") ->
                        swapTotal = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                    line.startsWith("SwapFree:") ->
                        swapFree = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                }
            }
        }

        return SystemStats(
            cpuPercent = cpuPercent,
            perCorePercents = perCore,
            cpuCores = cores.coerceAtLeast(1),
            memTotalKb = mi.totalMem / 1024,
            memUsedKb = (mi.totalMem - mi.availMem).coerceAtLeast(0) / 1024,
            memAvailKb = mi.availMem / 1024,
            swapTotalKb = swapTotal,
            swapUsedKb = (swapTotal - swapFree).coerceAtLeast(0),
            processCount = processCount,
            loadAvg = readLoadAvg(),
        )
    }

    private fun readLoadAvg(): String = runCatching {
        File("/proc/loadavg").readText().trim().split(Regex("\\s+")).take(3).joinToString(" ")
    }.getOrDefault("--")

    // ========== 前台 / 任务栏后台应用 ==========

    /**
     * 当前前台应用：UsageStatsManager 事件流最近一次 ACTIVITY_RESUMED；
     * 无权限回退 ROOT dumpsys（mResumedActivity）。
     */
    fun getForegroundPackage(context: Context): String? {
        val viaUsage = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - TimeUnit.HOURS.toMillis(2)
            var foreground: String? = null
            val events = usm.queryEvents(beginTime, endTime)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    foreground = event.packageName
                }
            }
            foreground
        }.getOrNull()
        if (viaUsage != null) return viaUsage

        val result = RootShell.exec(
            "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | tail -1",
            timeoutMs = 10000,
            log = {},
        )
        if (result.success) {
            Regex("""\bu[0-9]+\s+([a-zA-Z0-9_.]+)/""").find(result.stdout)
                ?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    /**
     * 任务栏（最近任务）后台应用：包名 → 最近活跃时间（墙钟时间），保持任务栏顺序。
     * 途径1 UsageStatsManager 事件流（需"使用情况访问"权限）；
     * 途径2 ROOT dumpsys recents（含包名与 lastActiveTime）。
     */
    fun getRecentTaskPackages(context: Context, hours: Int = 24): Map<String, Long> {
        val viaEvents = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - TimeUnit.HOURS.toMillis(hours.toLong())
            val recents = LinkedHashMap<String, Long>()
            val events = usm.queryEvents(beginTime, endTime)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    recents[event.packageName] = event.timeStamp
                }
            }
            recents
        }.getOrDefault(emptyMap())
        if (viaEvents.isNotEmpty()) return viaEvents

        // ROOT dumpsys：Recent #0 = 最近使用
        val result = RootShell.exec(
            "dumpsys activity recents | grep -E 'Recent #'",
            timeoutMs = 10000,
            log = {},
        )
        if (result.success) {
            val recents = LinkedHashMap<String, Long>()
            val bootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val pkgRegex = Regex("""A=(?:\d+:)?([a-zA-Z0-9_.]+)""")
            val timeRegex = Regex("""lastActiveTime=(\d+)""")
            for (line in result.stdout.lineSequence()) {
                val pkg = pkgRegex.find(line)?.groupValues?.get(1) ?: continue
                val elapsed = timeRegex.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val wallTime = if (elapsed > 0L) bootEpoch + elapsed else 0L
                if (!recents.containsKey(pkg)) recents[pkg] = wallTime
            }
            if (recents.isNotEmpty()) return recents
        }
        return emptyMap()
    }

    // ========== 权限与工具 ==========

    /** 是否已授予"使用情况访问"权限（参考系统任务管理器检测方式） */
    fun hasUsageStatsPermission(context: Context): Boolean = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - TimeUnit.HOURS.toMillis(1)
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime).isNotEmpty()
    }.getOrDefault(false)

    private fun getInstalledApps(pm: PackageManager): List<ApplicationInfo> =
        runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrDefault(emptyList())

    private fun getRunningServices(context: Context): Set<String> {
        val result = mutableSetOf<String>()
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningServices(200).mapNotNull { it.service?.packageName }.forEach { result.add(it) }
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.forEach { pinfo ->
                if (pinfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) {
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
            val beginTime = endTime - TimeUnit.DAYS.toMillis(1)
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime)
                .associateBy { it.packageName }
        }.getOrDefault(emptyMap())
    }

    private fun resolvePackageFromProcess(
        processName: String,
        pm: PackageManager,
        pkgNameMap: Set<String>,
    ): String? {
        if (pkgNameMap.contains(processName)) return processName

        val colonIdx = processName.indexOf(':')
        if (colonIdx > 0) {
            val basePkg = processName.substring(0, colonIdx)
            if (pkgNameMap.contains(basePkg)) return basePkg
        }

        if (processName.contains(".")) {
            runCatching {
                pm.getPackageInfo(processName, 0)
                return processName
            }
            if (colonIdx > 0) {
                val basePkg = processName.substring(0, colonIdx)
                runCatching {
                    pm.getPackageInfo(basePkg, 0)
                    return basePkg
                }
            }
        }

        // 内核 comm 截断为 15 字符：截断名按前缀匹配（packageName 或 packageName+":" 的前缀）
        // 仅在 uid 映射不可用时兜底（如隔离进程），正常路径不依赖此匹配
        if (processName.length >= 15 && processName.contains('.')) {
            for (pkg in pkgNameMap) {
                if (pkg.startsWith(processName) || (pkg + ":").startsWith(processName)) {
                    return pkg
                }
            }
        }
        return null
    }

    // ========== 格式化 ==========

    fun formatMemory(kb: Long): String = when {
        kb >= 1024 * 1024 -> "%.1f GB".format(kb / (1024.0 * 1024.0))
        kb >= 1024 -> "%.1f MB".format(kb / 1024.0)
        kb > 0 -> "$kb KB"
        else -> "0 KB"
    }

    fun formatCpu(percent: Float): String =
        if (percent < 0.1f) "0.0%" else "%.1f%%".format(percent)

    fun formatCpuTime(jiffies: Long): String {
        if (jiffies <= 0L) return "0秒"
        val totalSec = jiffies / clockHz
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }

    fun formatPower(percent: Float): String =
        if (percent < 0.1f) "0.0%" else "%.1f%%".format(percent)

    fun formatForegroundTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        return when {
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分钟"
            else -> "少于1分钟"
        }
    }

    fun formatLastUsed(timeMs: Long): String {
        if (timeMs <= 0L) return "—"
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
