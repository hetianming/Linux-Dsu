package com.mcai.ubuntudsu.core

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * DNA 工具箱运行时（参考 Dsu-Manager DnaTools 移植，UbuntuDSU 本地化）：
 *  - 工具来源：运行期从 GsiManager 的 tools.zip（GitHub 直链 + ghproxy 国内加速线路）下载解压到
 *    app 私有目录 filesDir/dna-tools/，补可执行权限（不内置进 APK）。
 *  - 工程目录双架构（对齐原版）：WORK_ROOT=/sdcard/Linux-Dsu/PDNA 工程根（源文件），TMP_ROOT=/data/Linux-Dsu/PDNA 分解输出（root）。
 *  - 二进制为 Android ARM64 ELF（含 dna 内核），执行走 RootShell（su）；
 *    root 预建伪装包目录（getcwd 校验）+ 工具经 /data/local/tmp 中转，命令 cd 到伪装目录执行，
 *    导出 DNA_DIR/DNA_TMP/DNA_PRO/DNA_DRO/TMPDIR 环境变量。
 */
object DnaTools {

    // GsiManager 的 ROM tag 下的 tools.zip 直链（16 个 ARM64 工具，含 dna 内核）
    const val TOOL_ZIP_URL =
        "https://github.com/hetianming/GsiManager/releases/download/ROM/tools.zip"

    // 国内加速线路（ghproxy 类公共镜像，与参考项目 DownloadService 同源）：直链无进展时依次切换
    private val mirrors = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
    )

    // 执行环境（对齐参考项目 DnaTools 常量）
    const val FAKE_HOME = "/data/data/com.dna.tools/files"
    const val RELAY_PATH = "/data/local/tmp/dna-tools"
    const val WORK_ROOT = "/sdcard/Linux-Dsu/PDNA"
    const val TMP_ROOT = "/data/Linux-Dsu/PDNA"
    private const val PREFS = "dna_prefs"
    private const val KEY_CURRENT = "current_project"
    private const val DNA_INI = "/data/local/tmp/DNA.ini"

    /** 执行结果（Java 侧便捷访问：ok()/text()，并保留 code 供 UI 展示退出码） */
    class Result(
        val success: Boolean,
        val output: String,
        val message: String,
        val code: Int = -1,
    ) {
        fun ok(): Boolean = success
        fun text(): String = output
    }

    /** 工具清单：可执行二进制 + 中文名 + 一句话说明（tools.zip 实际 16 个） */
    data class Tool(val bin: String, val name: String, val desc: String)

    val tools = listOf(
        Tool("dna", "DNA 内核", "DNA 命令行内核，串联各工具完成 ROM 处理"),
        Tool("magiskboot", "MagiskBoot", "boot 镜像解包/打包，注入/还原 Magisk"),
        Tool("mkfs.erofs", "制作 EROFS", "生成 EROFS 镜像（system 常用）"),
        Tool("extract.erofs", "解压 EROFS", "从 EROFS 镜像提取文件"),
        Tool("mkfs.f2fs", "制作 F2FS", "生成 F2FS 镜像"),
        Tool("extract.f2fs", "解压 F2FS", "从 F2FS 镜像提取文件"),
        Tool("mke2fs", "制作 ext4", "生成 ext4 镜像"),
        Tool("e2fsdroid", "ext4 定制", "ext4 文件系统 Android 定制工具"),
        Tool("resize2fs", "调整 ext4", "在线调整 ext4 文件系统大小"),
        Tool("simg2img", "SIMG 转 IMG", "稀疏镜像转普通镜像"),
        Tool("img2simg", "IMG 转 SIMG", "普通镜像转稀疏镜像"),
        Tool("lpmake", "LP 镜像", "Android LPDynamic 分区镜像制作"),
        Tool("busybox", "BusyBox", "通用 Unix 工具集（shell/文件/网络）"),
        Tool("zstd", "zstd 压缩", "zstd 压缩/解压"),
        Tool("brotli", "brotli 压缩", "brotli 压缩/解压"),
        Tool("sload_f2fs", "F2FS 校验", "F2FS 分区加载与校验"),
    )

    private fun marker(ctx: Context): File = File(ctx.filesDir, "dna-tools/READY")

    fun toolsDir(ctx: Context): File = File(ctx.filesDir, "dna-tools")
    private fun relayDir(ctx: Context): File = File(RELAY_PATH)

    /** 工具是否已就绪（已下载解压 + 可执行） */
    fun isReady(ctx: Context): Boolean {
        val dir = File(ctx.filesDir, "dna-tools")
        return marker(ctx).exists() && File(dir, "dna").exists()
    }

    /** 某工具二进制是否存在（未就绪时禁用执行） */
    fun toolAvailable(ctx: Context, bin: String): Boolean =
        File(ctx.filesDir, "dna-tools/$bin").exists()

    /** 工具二进制绝对路径（调用方用于构造 su 命令） */
    fun toolPath(ctx: Context, bin: String): String =
        File(ctx.filesDir, "dna-tools/$bin").absolutePath

    // ============ 工程管理（对齐参考项目 DnaTools） ============

    @JvmStatic
    fun isDynamicPartition(ctx: Context): Boolean {
        val r = RootShell.exec("getprop ro.boot.dynamic_partition 2>/dev/null", timeoutMs = 3000)
        return r.stdout.trim() == "true"
    }

    @JvmStatic
    fun slotSuffix(ctx: Context): String {
        val r = RootShell.exec("getprop ro.boot.slot_suffix 2>/dev/null", timeoutMs = 3000)
        return r.stdout.trim()
    }

    @JvmStatic
    fun currentDsuPath(ctx: Context): String? {
        val r = RootShell.exec(
            "mount | grep 'ext4 /data/dsu' 2>/dev/null | head -1 || " +
                "mount | grep 'ext4 /data/data/com.android.du' 2>/dev/null | head -1",
            timeoutMs = 5000)
        val line = r.stdout.lines().firstOrNull { it.isNotBlank() } ?: return null
        val parts = line.split(Regex("\\s+"))
        return if (parts.size >= 3) parts[1] else null
    }

    // ============ 本机分区枚举 / 块设备解析 / dd 提取刷写 ============

    /**
     * 列出本机全部分区名（扫描 /dev/block 下各 by-name 目录，去重排序）。
     * root 不可用或目录缺失时返回空列表。
     */
    @JvmStatic
    fun listPartitions(ctx: Context): List<String> {
        val r = RootShell.exec(
            "for d in $(find /dev/block -name by-name -type d 2>/dev/null); do ls -1 \"\$d\" 2>/dev/null; done | sort -u",
            timeoutMs = 15000)
        return if (r.success) r.stdout.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.distinct().sorted().toList()
        else emptyList()
    }

    /**
     * 解析分区名对应的真实块设备路径（readlink -f），失败回退 /dev/block/by-name/<name>。
     * 不同设备 by-name 目录位置不一（/dev/block/by-name、/dev/block/bootdevice/by-name、
     * /dev/block/platform/<soc>/by-name），此处自动遍历定位。
     */
    @JvmStatic
    fun partitionPath(ctx: Context, name: String): String {
        val r = RootShell.exec(
            "for d in $(find /dev/block -name by-name -type d 2>/dev/null); do p=\"\$d/$name\"; [ -e \"\$p\" ] && { readlink -f \"\$p\" && break; }; done",
            timeoutMs = 10000)
        val line = r.stdout.lines().firstOrNull { it.isNotBlank() }
        return if (line != null) line.trim() else "/dev/block/by-name/$name"
    }

    /**
     * 直接以 root 执行 dd 提取/刷写分区（不依赖 DNA 工具链，仅需设备自带 toybox dd）。
     * 后台启动 dd，并通过 /proc/<pid>/io 的 write_bytes 实时回传进度（百分比 + 已写字节）。
     * @param src 源（提取时为分区块设备，刷写时为本地 img 路径）
     * @param dst 目标（提取时为输出 img，刷写时为分区块设备）
     */
    @JvmStatic
    @JvmOverloads
    fun ddPartition(
        src: String,
        dst: String,
        onProgress: ((Int, Long) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        timeoutMs: Long = 30 * 60_000L,
    ): Result {
        // 计算总字节数用于进度百分比（块设备用 blockdev，普通文件用 stat）
        val sizeR = RootShell.exec(
            "blockdev --getsize64 '$src' 2>/dev/null || stat -c %s '$src' 2>/dev/null || echo 0",
            timeoutMs = 10000)
        val total = sizeR.stdout.trim().toLongOrNull() ?: 0L
        val dir = File(dst).parent
        if (!dir.isNullOrEmpty()) {
            RootShell.exec("mkdir -p '$dir'", timeoutMs = 10000)
        }
        val script = buildString {
            append("exec 2>&1\n")
            append("dd if=").append(quote(src)).append(" of=").append(quote(dst))
            append(" bs=4M conv=fsync &\n")
            append("__pid=\$!\n")
            append("while kill -0 \"\$__pid\" 2>/dev/null; do\n")
            append("  __wb=\$(awk '/^write_bytes:/{print \$2; exit}' /proc/\$__pid/io 2>/dev/null)\n")
            append("  [ -n \"\$__wb\" ] && echo __DNA_DD_\${__wb}__\n")
            append("  sleep 1\n")
            append("done\n")
            append("wait \"\$__pid\"; __rc=\$?\n")
            append("echo __DNA_EXIT_\${__rc}__\n")
        }
        return try {
            val process = ProcessBuilder("su").start()
            process.outputStream.use { it.write(script.toByteArray()); it.flush() }
            val output = StringBuilder()
            val exitCode = AtomicInteger(-1)
            val reader = Thread({
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                        val exit = Regex("__DNA_EXIT_(\\d+)__").find(line)
                        if (exit != null) {
                            exitCode.set(exit.groupValues[1].toIntOrNull() ?: -1)
                            return@forEachLine
                        }
                        val prog = Regex("__DNA_DD_(\\d+)__").find(line)
                        if (prog != null) {
                            val written = prog.groupValues[1].toLongOrNull() ?: 0L
                            val pct = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 100) else 0
                            onProgress?.invoke(pct, written)
                            return@forEachLine
                        }
                        synchronized(output) { output.appendLine(line) }
                        onLog?.invoke(line)
                    }
                }
            }, "dd-io").apply { isDaemon = true; start() }
            val startedAt = System.currentTimeMillis()
            while (true) {
                if (process.waitFor(1, TimeUnit.MILLISECONDS)) break
                if (isCancelled()) {
                    process.destroyForcibly()
                    reader.join(1500)
                    return Result(false, output.toString(), "已取消", -1)
                }
                if (System.currentTimeMillis() - startedAt > timeoutMs) {
                    process.destroyForcibly()
                    reader.join(1500)
                    return Result(false, output.toString(), "执行超时（${timeoutMs / 60000} 分钟）", -1)
                }
            }
            reader.join(2000)
            val out = synchronized(output) { output.toString() }
            val code = exitCode.get()
            Result(code == 0, out, if (code == 0) "完成" else "退出码 $code", code)
        } catch (e: Exception) {
            Result(false, "", e.message ?: "执行失败", -1)
        }
    }

    @JvmStatic
    fun currentProject(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CURRENT, null)
            ?.takeIf { it.startsWith("PDNA_") || it.startsWith("PDMA_") }
    }

    @JvmStatic
    fun setCurrentProject(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CURRENT, name).apply()
        runCatching {
            RootShell.exec(
                if (name.startsWith("PDNA_") || name.startsWith("PDMA_"))
                    "mkdir -p " + quote("$TMP_ROOT/$name") + "; echo " + quote(name) + " > $DNA_INI"
                else "rm -f $DNA_INI", timeoutMs = 10000)
        }
    }

    @JvmStatic
    fun clearCurrentProject(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CURRENT).apply()
        runCatching { RootShell.exec("rm -f $DNA_INI", timeoutMs = 10000) }
    }

    fun projectDir(name: String): File = File(WORK_ROOT, name)

    fun droDir(name: String): File = File(TMP_ROOT, name)

    private fun dirExists(path: String): Boolean =
        RootShell.exec("[ -d '$path' ] && echo __YES__ || echo __NO__", timeoutMs = 10000)
            .stdout.contains("__YES__")

    @JvmStatic
    fun listProjects(): List<String> {
        val r = RootShell.exec("ls -1 '$WORK_ROOT' 2>/dev/null", timeoutMs = 15000)
        return r.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("PDNA_") || it.startsWith("PDMA_") }
            .sorted()
            .toList()
    }

    @JvmStatic
    fun createProject(name: String): Pair<String, String?> {
        val clean = name.trim()
            .replace(Regex("[^\\w.\\-\u4e00-\u9fa5]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', ' ')
            .take(40)
        if (clean.isEmpty()) return "" to "工程名不能为空（仅支持中英文、数字、点、横杠）"
        var final = "PDNA_$clean"
        val stamp = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            .format(java.util.Date())
        if (dirExists("$WORK_ROOT/$final") || dirExists("$TMP_ROOT/$final")) {
            final = "PDNA_${clean}_$stamp"
        }
        val pro = "$WORK_ROOT/$final"
        val dro = "$TMP_ROOT/$final"
        val script = buildString {
            append("mkdir -p ").append(quote(WORK_ROOT)).append(" ").append(quote(TMP_ROOT)).append("\n")
            append("mkdir -p ").append(quote(pro)).append(" ").append(quote(dro)).append("\n")
            append("chmod 777 ").append(quote(WORK_ROOT)).append(" ").append(quote(pro)).append(" ")
                .append(quote(TMP_ROOT)).append(" ").append(quote(dro)).append(" 2>/dev/null\n")
            append("echo ").append(quote(final)).append(" > ").append(DNA_INI).append("\n")
            append("echo __DNA_OK__")
        }
        val r = RootShell.exec(script, timeoutMs = 30000)
        val ok = (r.success && r.stdout.contains("__DNA_OK__")) || dirExists(pro)
        return if (ok) final to null
        else "" to (r.stderr.trim().ifEmpty { "创建失败（需要 ROOT）" })
    }

    @JvmStatic
    fun deleteProject(name: String): Boolean {
        if (!name.startsWith("PDNA_") && !name.startsWith("PDMA_")) return false
        val script = buildString {
            append("rm -rf ").append(quote("$WORK_ROOT/$name")).append(" ")
                .append(quote("$TMP_ROOT/$name")).append(" /data/local/tmp/dna-tools/").append(name).append("\n")
            append("[ -f ").append(DNA_INI).append(" ] && grep -q ^").append(quote(name)).append("$ ").append(DNA_INI)
            append(" && rm -f ").append(DNA_INI).append(" || true\n")
            append("echo __DNA_OK__")
        }
        val r = RootShell.exec(script, timeoutMs = 120000)
        return r.success && r.stdout.contains("__DNA_OK__")
    }

    // ============ 工程内文件清单 / 目录浏览（root 列目录，app 进程无直读权限） ============

    private fun listDirEntries(path: String, type: String): List<String> {
        val r = RootShell.exec("ls -p '$path' 2>/dev/null", timeoutMs = 15000)
        if (!r.success) return emptyList()
        val lines = r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (type == "dir") {
            return lines.filter { it.endsWith("/") }
                .map { it.trimEnd('/') }
                .filter { it != "config" && it != "lost+found" }
                .sorted()
        }
        val files = lines.filter { !it.endsWith("/") }.sortedBy { it.lowercase() }
        if (type == "split_sparse") {
            return files.mapNotNull { n -> Regex("^(.*)\\.[0-9]+$").find(n)?.groupValues?.get(1) }
                .distinct().sorted()
        }
        return when (type) {
            "br" -> files.filter { it.nameEnds(".br") }
            "dat" -> files.filter { it.nameEnds(".new.dat") || it.nameEnds(".dat") }
            "img" -> files.filter { it.nameEnds(".img") }
            "zst" -> files.filter { it.nameEnds(".zst") || it.nameEnds(".zstd") }
            "zip" -> files.filter { it.nameEnds(".zip") || it.nameEnds(".zip2") }
            "bin" -> files.filter { it.nameEnds("payload.bin") }
            "bin_zip" -> files.filter { it.nameEnds("payload.bin") || it.nameEnds(".zip") }
            else -> files
        }
    }

    private fun String.nameEnds(suffix: String): Boolean =
        length >= suffix.length && substring(length - suffix.length).equals(suffix, ignoreCase = true)

    /** 文件浏览器条目（Java 侧 getter：getName / isDir / getSize） */
    class BrowseEntry(val name: String, val isDir: Boolean, val size: Long)

    @JvmStatic
    fun browseDir(path: String): List<BrowseEntry> {
        val r = RootShell.exec("stat -c '%F|%s|%n' '$path'/* 2>/dev/null", timeoutMs = 20000)
        if (r.stdout.isNotBlank()) {
            val entries = r.stdout.lines().mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                BrowseEntry(parts[2].trim().substringAfterLast('/'),
                    parts[0].trim() == "directory", parts[1].trim().toLongOrNull() ?: 0L)
            }
            if (entries.isNotEmpty()) {
                return entries.sortedWith(
                    compareByDescending<BrowseEntry> { it.isDir }.thenBy { it.name.lowercase() })
            }
        }
        val r2 = RootShell.exec("ls -1p '$path' 2>/dev/null", timeoutMs = 15000)
        return r2.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { n -> BrowseEntry(n.trimEnd('/'), n.endsWith("/"), 0L) }
            .sortedWith(compareByDescending<BrowseEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    @JvmStatic
    fun listProjectFiles(project: String?, type: String): List<String> {
        val base = if (project != null) "$WORK_ROOT/$project" else WORK_ROOT
        return listDirEntries(base, type)
    }

    @JvmStatic
    fun listDroDirs(project: String?): List<String> {
        if (project == null) return emptyList()
        return listDirEntries("$TMP_ROOT/$project", "dir")
    }

    // 其他功能脚本（assets 内置 sh，root source 执行）
    private val SCRIPTS = arrayOf(
        "my_partition_merge.sh", "partition_merge.sh",
        "merge_superchunk.sh", "selinux_boot.sh", "selinux_local.sh",
        "vbmeta_boot.sh", "vbmeta_local.sh"
    )

    @JvmStatic
    fun scriptsDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "dna-scripts")
        if (!dir.isDirectory) dir.mkdirs()
        for (name in SCRIPTS) {
            val out = File(dir, name)
            if (out.isFile) continue
            runCatching {
                ctx.assets.open("dna-scripts/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return dir
    }

    @JvmStatic
    fun getFileTypes(project: String?, names: List<String>): Map<String, String> {
        if (project == null || names.isEmpty()) return emptyMap()
        val script = buildString {
            append("mkdir -p '").append(FAKE_HOME).append("' 2>/dev/null; cd '").append(FAKE_HOME).append("' 2>/dev/null; ")
            append("export PATH='").append(RELAY_PATH).append("':\$PATH\n")
            for (n in names) {
                append("echo ").append(quote(n)).append("|$(dna gettype ").append(quote("$WORK_ROOT/$project/$n"))
                    .append(" 2>/dev/null)\n")
            }
        }
        val r = RootShell.exec(script, timeoutMs = 10_000L + names.size * 5_000L)
        if (!r.success) return emptyMap()
        return r.stdout.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('|')
                if (idx <= 0) null
                else line.substring(0, idx) to line.substring(idx + 1).trim()
            }
            .filter { it.second.isNotEmpty() }
            .toMap()
    }

    // ============ 权限放行（root 底层路径 chmod/chown，让 app 进程可直读写 /sdcard） ============

    @JvmStatic
    @JvmOverloads
    fun rootRelaxForApp(path: String, onLog: ((String) -> Unit)? = null): String? {
        if (tryReadHead(path)) return path
        val real = realMediaPath(path) ?: return null
        onLog?.invoke("… root 放行底层文件与目录权限 …")
        RootShell.exec(
            "chmod 664 '$real' 2>/dev/null; " +
                "d=\$(dirname '$real'); " +
                "while [ \"\$d\" != '/' ] && [ \"\$d\" != '/data/media' ]; do " +
                "chmod a+rx \"\$d\" 2>/dev/null; d=\$(dirname \"\$d\"); done; true",
            timeoutMs = 20000,
        )
        if (tryReadHead(path)) return path
        onLog?.invoke("… root 转移属主后重试直读 …")
        RootShell.exec(
            "chown ${android.os.Process.myUid()} '$real' 2>/dev/null && chmod 664 '$real'; true",
            timeoutMs = 20000,
        )
        if (tryReadHead(path)) return path
        return null
    }

    @JvmStatic
    @JvmOverloads
    fun ensureJniReadable(
        ctx: Context,
        path: String,
        onLog: ((String) -> Unit)? = null,
    ): String? {
        rootRelaxForApp(path, onLog)?.let { return it }
        val cache = File(ctx.cacheDir, "payload_input_cache")
        val src = realMediaPath(path) ?: path
        val need = rootFileSize(src)
        if (need > 0) {
            val free = runCatching {
                android.os.StatFs(ctx.cacheDir.absolutePath).availableBytes
            }.getOrDefault(0L)
            if (free in 1 until need) {
                onLog?.invoke("✗ 内部存储空间不足：需 ${fmtGB(need)}，剩余 ${fmtGB(free)}")
                return null
            }
        }
        onLog?.invoke("… 直读不可用，root 底层直拷到内部缓存（同一文件只拷一次） …")
        val cpRc = AtomicInteger(-1)
        val cpDone = AtomicBoolean(false)
        val cpThread = Thread({
            val r = RootShell.exec(
                "rm -f '${cache.absolutePath}'; " +
                    "cp '$src' '${cache.absolutePath}' && chmod 644 '${cache.absolutePath}'",
                timeoutMs = 40 * 60_000L,
            )
            cpRc.set(r.code)
            cpDone.set(true)
        }, "cache-cp").apply { isDaemon = true }
        cpThread.start()
        var lastLen = -1L
        var lastLogMs = 0L
        while (!cpDone.get()) {
            try { Thread.sleep(1500) } catch (e: InterruptedException) { break }
            val len = runCatching { cache.length() }.getOrDefault(0L)
            val now = System.currentTimeMillis()
            if (len != lastLen && now - lastLogMs >= 3000) {
                onLog?.invoke("… 缓存中 ${fmtGB(len)}" +
                    (if (need > 0) " / ${fmtGB(need)}" else "") + " …")
                lastLen = len
                lastLogMs = now
            }
        }
        runCatching { cpThread.join(3000) }
        if (cpDone.get() && cpRc.get() == 0 && cache.isFile && tryReadHead(cache.absolutePath)) {
            onLog?.invoke("✓ 缓存就绪")
            return cache.absolutePath
        }
        onLog?.invoke("✗ 缓存复制失败（空间不足或 root 异常，rc=${cpRc.get()}）")
        return null
    }

    private fun rootFileSize(path: String): Long =
        runCatching {
            RootShell.exec("stat -c %s '$path'", timeoutMs = 10000)
                .stdout.trim().toLongOrNull() ?: 0L
        }.getOrDefault(0L)

    private fun fmtGB(bytes: Long): String = String.format("%.1fG", bytes / 1073741824.0)

    @Volatile
    private var jniReadableMemo: Pair<String, String>? = null

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun resolveJniReadable(
        ctx: Context,
        path: String,
        onLog: ((String) -> Unit)? = null,
    ): String? {
        val memo = jniReadableMemo
        if (memo != null && memo.first == path) {
            if (tryReadHead(path)) {
                jniReadableMemo = path to path
                if (memo.second != path) File(memo.second).delete()
                return path
            }
            if (memo.second != path && File(memo.second).isFile) return memo.second
            if (memo.second == path) return path
        }
        val resolved = ensureJniReadable(ctx, path, onLog) ?: return null
        if (memo != null && memo.first != path && memo.second != memo.first) {
            File(memo.second).delete()
        }
        jniReadableMemo = path to resolved
        return resolved
    }

    @JvmStatic
    @JvmOverloads
    fun ensureAppWritable(dirPath: String, onLog: ((String) -> Unit)? = null): Boolean {
        if (dirWritable(dirPath)) return true
        val real = realMediaPath(dirPath)
        if (real != null) {
            onLog?.invoke("… root 放行输出目录底层权限 …")
            RootShell.exec(
                "mkdir -p '$real'; chmod a+rwx '$real'; " +
                    "d=\$(dirname '$real'); " +
                    "while [ \"\$d\" != '/' ] && [ \"\$d\" != '/data/media' ]; do " +
                    "chmod a+rx \"\$d\" 2>/dev/null; d=\$(dirname \"\$d\"); done; true",
                timeoutMs = 20000,
            )
            if (dirWritable(dirPath)) return true
            RootShell.exec(
                "chown -R ${android.os.Process.myUid()} '$real' 2>/dev/null; " +
                    "chmod -R a+rwX '$real' 2>/dev/null; true",
                timeoutMs = 30000,
            )
            if (dirWritable(dirPath)) return true
        } else {
            RootShell.exec("mkdir -p '$dirPath'; chmod a+rwx '$dirPath'; true", timeoutMs = 15000)
            if (dirWritable(dirPath)) return true
        }
        return false
    }

    private fun dirWritable(dir: String): Boolean = try {
        val probe = File(dir, ".w_probe")
        FileOutputStream(probe).use { it.write(1) }
        probe.delete()
        true
    } catch (e: Exception) {
        false
    }

    private fun tryReadHead(path: String): Boolean = try {
        FileInputStream(path).use { it.read() >= 0 }
    } catch (e: Exception) {
        false
    }

    private fun realMediaPath(path: String): String? {
        if (path.startsWith("/sdcard/")) return "/data/media/0/" + path.substring("/sdcard/".length)
        val m = Regex("^/storage/emulated/(\\d+)/(.*)$").find(path) ?: return null
        return "/data/media/${m.groupValues[1]}/${m.groupValues[2]}"
    }

    // ============ 工具部署（在线下载 tools.zip + 解压 + root 中转 + 伪装目录） ============

    @Volatile
    private var activeDir: File? = null

    @Volatile
    private var downloading = false

    /** root 预建伪装包目录（getcwd 校验目标；不存在则 dna 等二进制报"盗版"退出） */
    private fun ensureFakeHome(): Boolean {
        val r = RootShell.exec(
            "mkdir -p '$FAKE_HOME' && cd '$FAKE_HOME' && echo __DNA_OK__",
            timeoutMs = 15000)
        return r.success && r.stdout.contains("__DNA_OK__")
    }

    /**
     * 下载 tools.zip 并解压到 app 私有目录，chmod +x 全部二进制，写 READY 标记。
     * 直链优先，无进展时切 ghproxy 线路；进度 0..100，onLog 回显线路/错误。失败返回 false。
     */
    fun ensureTools(
        ctx: Context,
        onProgress: (Int) -> Unit,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        if (isReady(ctx)) {
            onLog("工具已就绪")
            onProgress(100)
            return true
        }
        if (downloading) {
            onLog("已有下载任务进行中")
            return false
        }
        downloading = true
        try {
            val workDir = File(ctx.filesDir, "dna-tools")
            if (!workDir.exists() && !workDir.mkdirs()) {
                onLog("无法创建目录 ${workDir.absolutePath}")
                return false
            }
            val zip = File(ctx.cacheDir, "dna-tools.zip")
            val candidates = urlCandidates(TOOL_ZIP_URL)

            // 1) 并发测速：对每条线路探测 3s，记录实际速率
            onLog("正在测速 ${candidates.size} 条线路，挑选最快...")
            val speeds = probeAllSpeeds(candidates, isCancelled)
            val sorted = candidates.sortedByDescending { speeds[it] ?: 0L }
            onLog("测速结果(字节/秒)：" + sorted.map { "${shortHost(it)}≈${(speeds[it] ?: 0L) / 1024}KB/s" }.joinToString(" "))

            // 2) 按速率从高到低下载，每线监控实际速率，慢则切下一线
            var downloaded = false
            for ((i, url) in sorted.withIndex()) {
                if (isCancelled()) {
                    onLog("已取消")
                    return false
                }
                if (i > 0) {
                    onLog("线路 ${i + 1}/${sorted.size} 速率过低，切换至下一线路")
                    zip.delete()
                }
                val shortName = shortHost(url)
                onLog("线路 ${i + 1}/${sorted.size}（$shortName）：开始下载 tools.zip")
                onProgress(0)

                // 实际速率监控
                val bytes = AtomicLong(0)
                val lastBytes = AtomicLong(0)
                val lastStamp = AtomicLong(System.currentTimeMillis())
                val tooSlow = AtomicBoolean(false)

                val watcher = Thread {
                    while (!tooSlow.get() && !isCancelled()) {
                        try { Thread.sleep(500) } catch (e: InterruptedException) { break }
                        val now = System.currentTimeMillis()
                        if (now - lastStamp.get() >= 10_000) {
                            val delta = bytes.get() - lastBytes.get()
                            val secs = (now - lastStamp.get()) / 1000.0
                            if (secs > 0 && delta / secs < 50L * 1024) {
                                onLog("$shortName 实际速率 ${(delta / secs) / 1024}KB/s 过低，切线")
                                tooSlow.set(true)
                            }
                            lastBytes.set(bytes.get())
                            lastStamp.set(now)
                        }
                    }
                }
                watcher.isDaemon = true
                watcher.start()

                val ok = downloadHttp(
                    url, zip, 0, 90, onProgress, isCancelled,
                ) { deltaBytes ->
                    bytes.addAndGet(deltaBytes)
                    if (tooSlow.get()) {
                        // 中断当前线路的读取循环，downloadHttp catch 会清理 target 并返回 false
                        throw RuntimeException("slow route switched")
                    }
                }
                try { watcher.join(1000) } catch (_: Exception) {}

                if (ok && !tooSlow.get() && zip.exists() && zip.length() > 0) {
                    downloaded = true
                    break
                }
                zip.delete()
            }
            if (!downloaded) {
                onLog("所有线路下载失败，请检查网络后重试")
                return false
            }
            onLog("解压 tools.zip")
            onProgress(95)
            val unzipped = unzip(zip, workDir, isCancelled)
            if (!unzipped) {
                onLog("解压失败")
                return false
            }
            zip.delete()
            chmodExec(workDir, onLog)
            marker(ctx).writeText(TOOL_ZIP_URL)
            onProgress(100)
            onLog("工具就绪")
            return true
        } catch (e: Exception) {
            onLog("异常：${e.message}")
            return false
        } finally {
            downloading = false
        }
    }

    private fun urlCandidates(url: String): List<String> {
        val list = mutableListOf(url)
        if (url.startsWith("https://github.com/")) {
            mirrors.forEach { list.add(it + url) }
        }
        return list
    }

    /** 并发对每条线路探测 3s，返回各 URL 的实际下载速率（字节/秒） */
    private fun probeAllSpeeds(urls: List<String>, isCancelled: () -> Boolean): Map<String, Long> {
        val results = HashMap<String, Long>()
        if (isCancelled()) return results
        val executor = java.util.concurrent.Executors.newFixedThreadPool(urls.size)
        try {
            val futures = urls.map { url ->
                executor.submit<Long> {
                    try {
                        probeOne(url, 3000)
                    } catch (e: Exception) {
                        0L
                    }
                }
            }
            for ((url, f) in urls.zip(futures)) {
                results[url] = try {
                    f.get(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) { 0L }
            }
        } finally {
            executor.shutdownNow()
        }
        return results
    }

    /** 单条线路探测：连到 HEAD/GET，3s 内能读多少字节返回速率（字节/秒），失败返回 0 */
    private fun probeOne(url: String, timeoutMs: Int): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "UbuntuDSU-DnaTools-Probe")
            conn.connect()
            if (conn.responseCode !in 200..299) return 0L
            val start = System.currentTimeMillis()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            conn.inputStream.use { input ->
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
            val elapsedMs = System.currentTimeMillis() - start
            if (elapsedMs <= 0) 0L else total * 1000L / elapsedMs
        } catch (e: Exception) {
            0L
        } finally {
            conn?.disconnect()
        }
    }

    /** 缩短 URL 显示：去掉镜像前缀，显示域名部分 */
    private fun shortHost(url: String): String {
        if (url.startsWith("https://github.com")) return "直连"
        for (m in mirrors) {
            if (url.startsWith(m)) return m.removePrefix("https://").removeSuffix("/")
        }
        return url
    }

    /**
     * 确保工具可用：下载解压（如未就绪）+ root 同步到 /data/local/tmp 中转目录 + 预建伪装目录 + 自检。
     * 返回可执行工具目录；root 不可用或失败返回 null。
     */
    @JvmStatic
    @JvmOverloads
    fun ensure(ctx: Context, onLog: ((String) -> Unit)? = null): File? {
        if (!ensureFakeHome()) return null
        val relay = relayDir(ctx)
        if (selfTest(relay)) {
            activeDir = relay
            return relay
        }
        activeDir?.let { dir -> if (selfTest(dir)) return dir }
        // 工具未就绪则下载解压（非阻塞式：这里同步下载，供 root 中转用）
        val appDir = File(ctx.filesDir, "dna-tools")
        if (!isReady(ctx)) {
            if (!ensureTools(ctx, {}, { onLog?.invoke(it) }, { false })) return null
        }
        onLog?.invoke("同步工具链到 $RELAY_PATH ...")
        val synced = relayViaRoot(ctx, appDir, onLog)
        if (synced != null && selfTest(synced)) {
            activeDir = synced
            return synced
        }
        return null
    }

    private fun relayViaRoot(ctx: Context, src: File, onLog: ((String) -> Unit)?): File? {
        val relay = relayDir(ctx)
        val check = RootShell.exec(
            "[ -x '" + relay.absolutePath + "/dna' ] && echo __YES__ || echo __NO__",
            timeoutMs = 15000)
        if (check.stdout.contains("__YES__")) return relay
        val script = buildString {
            append("mkdir -p '").append(relay.absolutePath).append("'\n")
            append("rm -rf '").append(relay.absolutePath).append("'/* 2>/dev/null\n")
            append("cp -f ").append(src.absolutePath).append("/* '").append(relay.absolutePath).append("'/\n")
            append("chmod 755 '").append(relay.absolutePath).append("'/*\n")
            append("echo __DNA_OK__")
        }
        val result = RootShell.exec(script, timeoutMs = 60000)
        if (!result.success || !result.stdout.contains("__DNA_OK__")) return null
        val verify = RootShell.exec(
            "[ -x '" + relay.absolutePath + "/dna' ] && echo __YES__ || echo __NO__",
            timeoutMs = 10000)
        return if (verify.stdout.contains("__YES__")) relay else null
    }

    /** 自检：dna gettype 对自身可执行文件返回非空即认为工具链可用 */
    private fun selfTest(dir: File): Boolean {
        val dna = File(dir, "dna")
        val script = "mkdir -p '" + FAKE_HOME + "' || exit 9; cd '" + FAKE_HOME + "' || exit 9; " +
            "export LD_LIBRARY_PATH='" + dir.absolutePath + "'; export PATH='" + dir.absolutePath + "':\$PATH; " +
            "[ -x '" + dna.absolutePath + "' ] || exit 8; '" + dna.absolutePath + "' gettype '" + dna.absolutePath + "'"
        val result = RootShell.exec(script, timeoutMs = 60000)
        return result.success && result.stdout.isNotBlank()
    }

    /**
     * 执行 DNA 命令（经 su，流式回传输出）。返回 Result（success/output/message/code）。
     * @param command 完整命令行（不含环境变量前缀），如 "dna extract /sdcard/system.img"
     */
    @JvmStatic
    @JvmOverloads
    fun run(
        ctx: Context,
        command: String,
        onLog: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        timeoutMs: Long = 30 * 60_000L,
    ): Result {
        val dir = ensure(ctx, onLog) ?: run {
            val rootOk = runCatching { RootShell.available() }.getOrDefault(false)
            return Result(false, "", if (rootOk)
                "DNA 工具链初始化失败（工具同步异常，请点「下载」重试）"
            else "DNA 工具链初始化失败（需要 ROOT 授权）", -1)
        }
        val project = currentProject(ctx)
        val pro = project?.let { "$WORK_ROOT/$it" } ?: WORK_ROOT
        val dro = project?.let { "$TMP_ROOT/$it" } ?: TMP_ROOT
        val script = buildString {
            append("exec 2>&1\n")
            append("export LD_LIBRARY_PATH='").append(dir.absolutePath).append("'\n")
            append("export PATH='").append(dir.absolutePath).append("':\$PATH\n")
            append("export DNA_DIR=").append(WORK_ROOT).append("\n")
            append("export DNA_TMP=").append(TMP_ROOT).append("\n")
            append("export DNA_PRO='").append(pro).append("'\n")
            append("export DNA_DRO='").append(dro).append("'\n")
            append("export PDNA_DIR=").append(WORK_ROOT).append("\n")
            append("export PDNA_TMP=").append(TMP_ROOT).append("\n")
            append("export TMPDIR=/data/local/tmp\n")
            append("mkdir -p ").append(FAKE_HOME).append("\n")
            append("cd ").append(FAKE_HOME).append(" || cd /data/local/tmp\n")
            append("mkdir -p ").append(WORK_ROOT).append(" ").append(TMP_ROOT).append("\n")
            if (project != null) {
                append("mkdir -p '").append(dro).append("'\n")
                append("echo '").append(project).append("' > ").append(DNA_INI).append("\n")
            }
            append(command.trim()).append("\n")
            append("__rc=\$?\n")
            append("for __d in '").append(WORK_ROOT).append("' '").append(TMP_ROOT).append("'; do cd \"\$__d\" 2>/dev/null")
            append(" && for __f in DNA_*; do [ -e \"\$__f\" ] && mv -f \"\$__f\" \"PDNA_\${__f#DNA_}\"; done; done; cd /\n")
            append("echo __DNA_EXIT_\${__rc}__")
        }
        return try {
            val process = ProcessBuilder("su").start()
            process.outputStream.use { stream ->
                stream.write(script.toByteArray())
                stream.flush()
            }
            val output = StringBuilder()
            val exitCode = AtomicInteger(-1)
            val reader = Thread({
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                        val marker = Regex("__DNA_EXIT_(\\d+)__").find(line)
                        if (marker != null) {
                            exitCode.set(marker.groupValues[1].toIntOrNull() ?: -1)
                            return@forEachLine
                        }
                        synchronized(output) { output.appendLine(line) }
                        onLog?.invoke(line)
                    }
                }
            }).apply { isDaemon = true; start() }
            val startedAt = System.currentTimeMillis()
            while (true) {
                if (process.waitFor(1, TimeUnit.SECONDS)) break
                if (isCancelled()) {
                    process.destroyForcibly()
                    reader.join(1500)
                    return Result(false, output.toString(), "已取消", -1)
                }
                if (System.currentTimeMillis() - startedAt > timeoutMs) {
                    process.destroyForcibly()
                    reader.join(1500)
                    return Result(false, output.toString(), "执行超时（${timeoutMs / 60000} 分钟）", -1)
                }
            }
            reader.join(2000)
            val out = synchronized(output) { output.toString() }
            val code = exitCode.get()
            Result(code == 0, out, if (code == 0) "完成" else "退出码 $code", code)
        } catch (e: Exception) {
            Result(false, "", e.message ?: "执行失败", -1)
        }
    }

    /**
     * 直接以 root 运行内置提取器，无需等待 DNA 工具链下载、解压和中转。
     * 保持 run() 的流式日志、取消和超时行为，但不注入 dna 专用环境变量。
     */
    private fun runPayloadCommand(
        command: String,
        onLog: ((String) -> Unit)?,
        isCancelled: () -> Boolean,
        timeoutMs: Long,
        watchedOutputDir: String? = null,
    ): Result {
        val script = buildString {
            append("exec 2>&1\n")
            // Rust dumper 会在 --list 前创建 --out 目录；su 默认 cwd 可能是只读的 /。
            append("mkdir -p /data/local/tmp/linux-dsu-payload || exit 71\n")
            append("cd /data/local/tmp/linux-dsu-payload || exit 72\n")
            append("export TMPDIR=/data/local/tmp/linux-dsu-payload\n")
            if (watchedOutputDir == null) {
                append(command.trim()).append("\n")
                append("__rc=\$?\n")
            } else {
                // Rust dumper 会对每个镜像先 set_len 再写 extent；监视文件长度不可靠。
                // 在 root shell 中查看 dumper 的 /proc/PID/fd，文件描述符关闭即代表该镜像确实写完。
                append("mkdir -p ").append(quote(watchedOutputDir)).append(" || exit 73\n")
                append("__dna_seen=/data/local/tmp/linux-dsu-payload/seen-\$\$; mkdir -p \"\$__dna_seen\" || exit 74\n")
                append(command.trim()).append(" &\n")
                append("__dna_pid=\$!\n")
                append("while kill -0 \"\$__dna_pid\" 2>/dev/null; do\n")
                // Android 的 mksh 可能在 wait 前保留已退出子进程的 zombie PID；kill -0 对 zombie 仍成功。
                // 识别 Z 状态后跳出扫描并执行 wait，避免所有 img 都完成后最终总结一直不返回。
                append("  __dna_state=\$(awk '\$1 == \"State:\" { print \$2; exit }' /proc/\$__dna_pid/status 2>/dev/null)\n")
                append("  [ \"\$__dna_state\" = Z ] && break\n")
                append("  for __dna_file in ").append(quote(watchedOutputDir)).append("/*.img; do\n")
                append("    [ -f \"\$__dna_file\" ] || continue\n")
                append("    __dna_name=\${__dna_file##*/}; [ -e \"\$__dna_seen/\$__dna_name\" ] && continue\n")
                append("    __dna_open=0\n")
                append("    for __dna_fd in /proc/\$__dna_pid/fd/*; do\n")
                append("      [ -e \"\$__dna_fd\" ] || continue\n")
                append("      __dna_target=\$(readlink \"\$__dna_fd\" 2>/dev/null) || continue\n")
                append("      if [ \"\$__dna_target\" = \"\$__dna_file\" ]; then __dna_open=1; break; fi\n")
                append("    done\n")
                append("    if [ \"\$__dna_open\" -eq 0 ]; then\n")
                append("      __dna_size=\$(wc -c < \"\$__dna_file\" 2>/dev/null | tr -d '[:space:]')\n")
                append("      if [ -n \"\$__dna_size\" ] && [ \"\$__dna_size\" -gt 0 ] 2>/dev/null; then\n")
                append("        : > \"\$__dna_seen/\$__dna_name\"\n")
                append("        printf '__DNA_IMAGE_DONE__%s|%s\\n' \"\$__dna_name\" \"\$__dna_size\"\n")
                append("      fi\n")
                append("    fi\n")
                append("  done\n")
                append("  sleep 1\n")
                append("done\n")
                append("wait \"\$__dna_pid\"; __rc=\$?\n")
                append("rm -rf \"\$__dna_seen\"\n")
            }
            append("echo __DNA_EXIT_\${__rc}__\n")
        }
        return try {
            val process = ProcessBuilder("su").start()
            process.outputStream.use { stream ->
                stream.write(script.toByteArray())
                stream.flush()
            }
            val output = StringBuilder()
            val exitCode = AtomicInteger(-1)
            val reader = Thread({
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                        val marker = Regex("__DNA_EXIT_(\\d+)__").find(line)
                        if (marker != null) {
                            exitCode.set(marker.groupValues[1].toIntOrNull() ?: -1)
                            return@forEachLine
                        }
                        synchronized(output) { output.appendLine(line) }
                        if (line.startsWith("__DNA_IMAGE_DONE__")) {
                            onLog?.invoke(line)
                            return@forEachLine
                        }
                        onLog?.invoke(line)
                    }
                }
            }).apply { isDaemon = true; start() }
            val startedAt = System.currentTimeMillis()
            while (true) {
                if (process.waitFor(1, TimeUnit.SECONDS)) break
                if (isCancelled()) {
                    process.destroyForcibly()
                    reader.join(1500)
                    return Result(false, output.toString(), "已取消", -1)
                }
                if (System.currentTimeMillis() - startedAt > timeoutMs) {
                    process.destroyForcibly()
                    reader.join(1500)
                    return Result(false, output.toString(), "执行超时（${timeoutMs / 60000} 分钟）", -1)
                }
            }
            reader.join(2000)
            val out = synchronized(output) { output.toString() }
            val code = exitCode.get()
            Result(code == 0, out, if (code == 0) "完成" else "退出码 $code", code)
        } catch (e: Exception) {
            Result(false, "", e.message ?: "执行失败", -1)
        }
    }

    /** 使用 APK 内置 Rust payload-dumper 原地列出 payload.bin / OTA ZIP 分区，不初始化 DNA 工具链。 */
    @JvmStatic
    @JvmOverloads
    fun payloadListCli(
        ctx: Context,
        input: String,
        onLog: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        timeoutMs: Long = 120_000L,
    ): Result {
        val bundledTool = File(ctx.applicationInfo.nativeLibraryDir, "libpayload_extract.so")
        if (!bundledTool.isFile) {
            return Result(false, "", "APK 内置 payload 提取器不存在: ${bundledTool.absolutePath}", -1)
        }
        // dumper 在执行 --list 前仍会创建 --out；显式使用应用私有缓存路径，
        // 避免 su 的只读根目录把整次解析提前打断。
        val listOutput = File(ctx.cacheDir, "payload-list").absolutePath
        val command = quote(bundledTool.absolutePath) + " " + quote(realPath(input)) +
            " --list --out " + quote(listOutput)
        return runPayloadCommand(command, onLog, isCancelled, timeoutMs)
    }

    /**
     * 分解 bin / OTA zip：优先使用 APK 内置 payload-dumper-rust（零下载/零中转），
     * 按 CPU 核心数自适应并发；旧安装包缺少内置二进制时才回退 DNA 工具链。
     * 输入输出使用 /data/media 底层真实路径，避免 /sdcard FUSE 成为大包吞吐瓶颈。
     */
    @JvmStatic
    @JvmOverloads
    fun payloadExtractCli(
        ctx: Context,
        input: String,
        outputDir: String,
        partitions: String,
        onLog: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        timeoutMs: Long = 20 * 60_000L,
    ): Result {
        val bundledTool = File(ctx.applicationInfo.nativeLibraryDir, "libpayload_extract.so")
        val useBundledTool = bundledTool.isFile
        val legacyTool = File(ctx.filesDir, "dna-tools/payload_extract")
        val bin = when {
            useBundledTool -> bundledTool.absolutePath
            legacyTool.isFile -> legacyTool.absolutePath
            else -> "payload_extract"
        }
        // payload-dumper-rust 官方默认策略为 CPU 核心数的两倍、最高 32；显式传入以保证一致。
        val threads = (Runtime.getRuntime().availableProcessors() * 2).coerceIn(4, 32)
        val tail = " --threads $threads --no-verify"
        val inReal = realPath(input)
        val outReal = realPath(outputDir)
        if (useBundledTool) onLog?.invoke("… 使用内置高速提取器（$threads 线程）…")
        fun execute(command: String, watchDir: String): Result = if (useBundledTool) {
            runPayloadCommand(command, onLog, isCancelled, timeoutMs, watchDir)
        } else {
            run(ctx, command, onLog, isCancelled, timeoutMs)
        }
        val invocation = quote(bin) + " " + quote(inReal) +
            " --images " + quote(partitions) +
            " --out " + quote(outReal) + tail
        // 内置二进制由 runPayloadCommand 在前台先 mkdir，再直接后台启动可执行文件；
        // 不要将 “mkdir && binary” 整体放入后台，否则 $! 可能是 shell 子进程而非 dumper PID，
        // /proc/PID/fd 看不到真正写镜像的 FD，预分配的目标大小会被误报为已提取。
        val cmd = if (useBundledTool) invocation else "mkdir -p " + quote(outReal) + " && " + invocation
        var r = execute(cmd, outReal)
        // 真实路径一个目标文件都没产出（极端场景 /data/media 不可访问）→ 回退 FUSE 视图路径重跑
        if (!r.success && (inReal != input || outReal != outputDir) && !isCancelled()) {
            val names = partitions.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val anyOutput = names.any { File(outputDir, "$it.img").let { f -> f.isFile && f.length() > 0 } }
            if (!anyOutput) {
                onLog?.invoke("… 真实路径无产物，回退 FUSE 路径重试 ...")
                val fallbackInvocation = quote(bin) + " " +
                    quote(input) + " --images " + quote(partitions) +
                    " --out " + quote(outputDir) + tail
                val cmdFallback = if (useBundledTool) fallbackInvocation else
                    "mkdir -p " + quote(outputDir) + " && " + fallbackInvocation
                r = execute(cmdFallback, outputDir)
            }
        }
        return r
    }

    /**
     * v3.42.16：FUSE 视图路径 → 底层真实路径。root CLI 读写 /storage/emulated/0 或
     * /sdcard（FUSE 视图）时每次读写都要经 sdcard FUSE daemon 转发 —— 大包提取 208s 的
     * 主瓶颈。root 进程直读直写 /data/media/0（FUSE 的 lower fs 本体）绕过 daemon 转发，
     * 读写吞吐大幅提升；FUSE 视图即时反映 lower 变化，文件管理器照常可见。
     */
    @JvmStatic
    fun realPath(p: String): String {
        var s = p
        if (s == "/storage/emulated/0" || s == "/sdcard") return "/data/media/0"
        if (s.startsWith("/storage/emulated/0/")) s = "/data/media/0/" + s.substring(20)
        else if (s.startsWith("/sdcard/")) s = "/data/media/0/" + s.substring(8)
        return s
    }

    @JvmStatic
    fun briefOf(r: Result): String {
        val out = r.output
        var errLine = ""
        val meaningful = ArrayList<String>()
        for (raw in out.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (errLine.isEmpty() && line.startsWith("error:")) errLine = line
            if (!line.startsWith("Usage:") && !line.startsWith("For more information")
                && !line.startsWith("tip:")) meaningful.add(line)
        }
        if (errLine.isNotEmpty()) return errLine.removePrefix("error:").trim()
        return meaningful.lastOrNull() ?: r.message
    }

    /** 单引号 shell 转义（su 脚本拼接防注入） */
    @JvmStatic
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // ============ 下载 / 解压 / 权限 私有实现 ============

    private fun downloadHttp(
        url: String,
        target: File,
        progressLo: Int,
        progressHi: Int,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        onBytes: ((Long) -> Unit)? = null,
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "UbuntuDSU-DnaTools")
            conn.connect()
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) return false
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onBytes?.invoke(n.toLong())
                        if (total > 0) {
                            val frac = (done * 100.0 / total).toInt().coerceIn(0, 100)
                            onProgress(progressLo + (frac * (progressHi - progressLo) / 100))
                        } else {
                            onProgress(progressLo)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            target.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** 解压 zip 到 destDir；tools.zip 顶层是 tools/ 前缀，剥掉后平铺到 destDir/<bin>。isCancelled 可随时中止 */
    private fun unzip(zip: File, destDir: File, isCancelled: () -> Boolean): Boolean {
        return try {
            ZipInputStream(zip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (isCancelled()) return false
                    if (entry.name.startsWith("/") || entry.name.contains("../")) {
                        entry = zis.nextEntry
                        continue
                    }
                    var rel = entry.name
                    if (rel.startsWith("tools/")) rel = rel.removePrefix("tools/")
                    if (rel.isEmpty()) {
                        entry = zis.nextEntry
                        continue
                    }
                    val out = File(destDir, rel)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (isCancelled()) return false
                                val n = zis.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                            }
                        }
                        out.setExecutable(true, false)
                    }
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun chmodExec(dir: File, onLog: (String) -> Unit) {
        val files = dir.listFiles() ?: return
        var ok = 0
        for (f in files) {
            if (f.isFile && f.setExecutable(true, false)) ok++
        }
        onLog("已标记可执行 $ok/${files.size} 个工具")
    }
}
