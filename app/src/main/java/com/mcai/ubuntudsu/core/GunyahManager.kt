package com.mcai.ubuntudsu.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * gunyahVM 工具链管理：下载 unixnu-bin.zip、解压到 APP 私有目录、生成可改参数的
 * 启动/停止脚本、持久化配置、检测内核版本并枚举 modules.ko 内核模块。
 */
object GunyahManager {
    private const val TAG = "GunyahManager"

    /** 工具链包直链（与 DNA 工具同源：GitHub Release）。 */
    private const val TOOL_ZIP_URL =
        "https://github.com/hetianming/GsiManager/releases/download/gunyah/unixnu-bin.zip"

    /**
     * 国内加速线路（ghproxy 类公共镜像，与 DNA 工具 / AppUpdater 同源）。
     * 下载前会并发测速，按实际速率从快到慢依次尝试。
     */
    private val MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://ghproxy.cc/",
    )

    private const val TOOLCHAIN_ZIP = "unixnu-bin.zip"
    private const val CONFIG_FILE = "gunyah_config.json"
    private const val BOOT_SCRIPT = "unixnu-boot.sh"
    private const val STOP_SCRIPT = "unixnu-stop.sh"
    private const val MARKER = "gunyah_toolchain_ready"

    data class Config(
        var memMB: Int = 4096,
        var cpus: Int = 4,
        var name: String = "unixnu",
        var gpu: Boolean = true,
        var gpuArg: String = "2d,vulkan=false,displays=[[mode=windowed[720,1584],refresh-rate=120,dpi=[160,160]]]",
        var vnc: Boolean = true,
        var vncHost: String = "127.0.0.1",
        var vncPort: Int = 5905,
        var vncCap: String = "",
        var initEsp: String = "",
        var osImg: String = "",
        var systemosImg: String = "",
        var dataStore: String = "",
        var droidvmDisk: String = "",
        var rootApfs: String = "",
        var systemApfs: String = "",
    )

    private lateinit var privDir: File
    private lateinit var imagesDir: File

    private fun ensureDirs(ctx: Context) {
        privDir = File(ctx.filesDir, "gunyahvm")
        imagesDir = File(privDir, "images")
        privDir.mkdirs()
        imagesDir.mkdirs()
    }

    fun privateDir(ctx: Context): File { ensureDirs(ctx); return privDir }
    fun imagesDir(ctx: Context): File { ensureDirs(ctx); return imagesDir }
    fun bootScript(ctx: Context): File { ensureDirs(ctx); return File(privDir, BOOT_SCRIPT) }
    fun stopScript(ctx: Context): File { ensureDirs(ctx); return File(privDir, STOP_SCRIPT) }
    fun toolchainZip(ctx: Context): File { ensureDirs(ctx); return File(privDir, TOOLCHAIN_ZIP) }
    fun configFile(ctx: Context): File { ensureDirs(ctx); return File(privDir, CONFIG_FILE) }

    // ============================ 工具链下载 ============================

    /**
     * 下载并解压工具链（与 DNA 工具同策略）：
     *
     * 1) 对「直连 + 全部镜像」并发测速 3s，按实际速率排序；
     * 2) 从最快的线路开始下载，后台实时监控速率，10s 窗口低于 50KB/s 自动切下一条；
     * 3) 下载完成校验 zip 魔数 → 解压 → 补执行位。
     *
     * @return (是否成功, 面向用户的说明文案)
     */
    fun downloadAndExtract(
        ctx: Context,
        onProgress: (Long, Long) -> Unit,
        onState: (String) -> Unit,
    ): Pair<Boolean, String> {
        ensureDirs(ctx)
        val zip = toolchainZip(ctx)
        zip.delete()

        val candidates = mutableListOf(TOOL_ZIP_URL)
        MIRRORS.forEach { candidates.add(it + TOOL_ZIP_URL) }

        // 1) 并发测速
        onState("正在并发测速 ${candidates.size} 条线路…")
        val speeds = probeAllSpeeds(candidates)
        val sorted = candidates.sortedByDescending { speeds[it] ?: 0L }
        onState(
            "测速结果：" + sorted.joinToString("  ") { u ->
                "${shortHost(u)}≈${(speeds[u] ?: 0L) / 1024}KB/s"
            },
        )

        // 2) 依次下载，慢线自动切换
        var ok = false
        for ((i, url) in sorted.withIndex()) {
            if (i > 0) onState("上一线路过慢，切换 ${shortHost(url)}（${i + 1}/${sorted.size}）")
            else onState("使用最快线路 ${shortHost(url)} 开始下载")

            val bytes = AtomicLong(0)
            val lastBytes = AtomicLong(0)
            val lastStamp = AtomicLong(System.currentTimeMillis())
            val tooSlow = AtomicBoolean(false)

            val watcher = Thread {
                while (!tooSlow.get()) {
                    try { Thread.sleep(500) } catch (e: InterruptedException) { return@Thread }
                    val now = System.currentTimeMillis()
                    if (now - lastStamp.get() >= 10_000) {
                        val delta = bytes.get() - lastBytes.get()
                        val secs = (now - lastStamp.get()) / 1000.0
                        if (secs > 0 && delta / secs < 50L * 1024) {
                            onState("${shortHost(url)} 速率 ${(delta / secs) / 1024}KB/s 过低，切线…")
                            tooSlow.set(true)
                        }
                        lastBytes.set(bytes.get())
                        lastStamp.set(now)
                    }
                }
            }
            watcher.isDaemon = true
            watcher.start()

            val dl = runCatching {
                downloadHttp(url, zip, onProgress) { delta ->
                    bytes.addAndGet(delta)
                    if (tooSlow.get()) throw RuntimeException("slow route switched")
                }
            }.getOrDefault(false)
            try { watcher.join(1000) } catch (_: Exception) {}

            if (dl && zip.length() > 0) { ok = true; break }
            zip.delete()
        }
        if (!ok) {
            onState("所有线路下载失败")
            return false to "工具链下载失败（已测速并尝试 ${candidates.size} 条线路），\n请检查网络，或用「本地导入」选择已下载的 unixnu-bin.zip"
        }

        // 3) 解压
        onState("正在解压工具链…")
        val res = runCatching { extractZip(zip, privDir) }
        if (res.isFailure) {
            zip.delete()
            val msg = "解压失败：${res.exceptionOrNull()?.message ?: "未知错误"}"
            onState(msg)
            return false to msg
        }
        File(privDir, MARKER).writeText(TOOL_ZIP_URL)
        val bin = detectBinDir(ctx)
        if (bin == null) {
            return false to "解压完成，但未在私有目录找到 crosvm（包结构异常）"
        }
        zip.delete()
        onState("工具链已就绪")
        return true to "工具链已就绪\n$bin"
    }

    /** 对每条线路并发探测 3s，返回各 URL 实际下载速率（字节/秒）。 */
    private fun probeAllSpeeds(urls: List<String>): Map<String, Long> {
        val results = HashMap<String, Long>()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(urls.size)
        try {
            val futures = urls.map { url ->
                executor.submit<Long> {
                    try { probeOne(url, 3000) } catch (e: Exception) { 0L }
                }
            }
            for ((url, f) in urls.zip(futures)) {
                results[url] = try {
                    f.get(6, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) { 0L }
            }
        } finally {
            executor.shutdownNow()
        }
        return results
    }

    /** 单线路探测：GET 读 3s，返回速率（字节/秒），失败返回 0。 */
    private fun probeOne(url: String, timeoutMs: Int): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "UbuntuDSU-GunyahVM-Probe")
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
            val elapsed = System.currentTimeMillis() - start
            if (elapsed <= 0) 0L else total * 1000L / elapsed
        } catch (e: Exception) {
            0L
        } finally {
            conn?.disconnect()
        }
    }

    /** 线路短名：直连 / 镜像域名。 */
    private fun shortHost(url: String): String {
        if (url.startsWith("https://github.com")) return "直连"
        for (m in MIRRORS) {
            if (url.startsWith(m)) return m.removePrefix("https://").removeSuffix("/")
        }
        return url
    }

    /**
     * 单线程顺序下载（慢速切线由外层 watcher 通过抛异常中断）。
     * 写入 .part 临时文件，完整且通过 zip 校验后才改名生效。
     */
    private fun downloadHttp(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
        onBytes: (Long) -> Unit,
    ): Boolean {
        var conn: HttpURLConnection? = null
        val part = File(target.absolutePath + ".part")
        part.delete()
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) UbuntuDSU/1.8.62")
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code !in 200..299) return false
            val type = conn.contentType.orEmpty().lowercase()
            if (type.contains("text/html")) return false   // 线路返回了错误页
            val total = conn.contentLengthLong
            var verified = false
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush()
                        done += n
                        onBytes(n.toLong())
                        onProgress(done, total)
                    }
                    verified = done > 0 && isZip(part)
                }
            }
            if (!verified) { part.delete(); return false }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) { part.delete(); return false }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "downloadHttp $url error: ${e.message}")
            part.delete()
            return false
        } finally {
            conn?.disconnect()
        }
    }

    /** 校验 zip 魔数 PK\003\004，避免把 404 页面当成下载成功。 */
    private fun isZip(f: File): Boolean {
        if (!f.exists() || f.length() < 4) return false
        return runCatching {
            f.inputStream().use { ins ->
                val h = ByteArray(4)
                ins.read(h) == 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /**
     * 从本地文件导入工具链 zip（网络下载不通时的兜底路径）。
     * 直接解压到私有目录，不占用额外的中转空间。
     */
    fun importToolchain(ctx: Context, srcPath: String): Pair<Boolean, String> {
        ensureDirs(ctx)
        val src = File(srcPath)
        if (!src.isFile) return false to "文件不存在：$srcPath"
        if (!isZip(src)) return false to "不是有效的 zip 文件"
        return try {
            extractZip(src, privDir)
            File(privDir, MARKER).writeText("import:$srcPath")
            val bin = detectBinDir(ctx)
            if (bin == null) false to "已解压，但未找到 crosvm（包结构不符）"
            else true to "导入完成\n$bin"
        } catch (e: Exception) {
            Log.e(TAG, "导入失败", e)
            false to "导入失败：${e.message}"
        }
    }

    private fun extractZip(zip: File, dest: File) {
        val buffer = ByteArray(64 * 1024)
        val destPath = dest.canonicalPath
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val outFile = File(dest, name)
                // zip-slip 防护：解压目标必须仍在私有目录内
                if (!outFile.canonicalPath.startsWith(destPath)) {
                    zis.closeEntry(); entry = zis.nextEntry; continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) fos.write(buffer, 0, len)
                    }
                    // 给常见可执行文件加执行权限
                    if (name.endsWith("/crosvm") || name == "crosvm" ||
                        name.endsWith(".so") || name.endsWith(".sh")
                    ) {
                        outFile.setExecutable(true, false)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    // ============================ 路径自动探测 ============================

    /** 在私有目录递归查找 crosvm 可执行文件，返回其所在目录（即 DVM）。 */
    fun detectBinDir(ctx: Context): String? {
        ensureDirs(ctx)
        var found: String? = null
        privDir.walkTopDown().forEach { f ->
            if (found == null && f.isFile && f.name == "crosvm") {
                // 解压工具不一定保留执行位，这里补一次，避免「文件在但不被认为就绪」
                if (!f.canExecute()) f.setExecutable(true, false)
                found = f.parent
            }
        }
        return found
    }

    /** 查找 modules.ko 目录（内核模块集合）。 */
    fun detectModulesDir(ctx: Context): String? {
        ensureDirs(ctx)
        var found: String? = null
        privDir.walkTopDown().forEach { f ->
            if (found == null && f.isDirectory && f.name == "modules.ko") found = f.absolutePath
        }
        return found
    }

    fun isToolchainReady(ctx: Context): Boolean =
        detectBinDir(ctx) != null

    // ============================ 配置读写 ============================

    fun loadConfig(ctx: Context): Config {
        ensureDirs(ctx)
        val cfg = Config()
        val f = configFile(ctx)
        if (!f.exists()) return cfg
        runCatching {
            val j = JSONObject(f.readText())
            cfg.memMB = j.optInt("memMB", cfg.memMB)
            cfg.cpus = j.optInt("cpus", cfg.cpus)
            cfg.name = j.optString("name", cfg.name)
            cfg.gpu = j.optBoolean("gpu", cfg.gpu)
            cfg.gpuArg = j.optString("gpuArg", cfg.gpuArg)
            cfg.vnc = j.optBoolean("vnc", cfg.vnc)
            cfg.vncHost = j.optString("vncHost", cfg.vncHost)
            cfg.vncPort = j.optInt("vncPort", cfg.vncPort)
            cfg.vncCap = j.optString("vncCap", cfg.vncCap)
            cfg.initEsp = j.optString("initEsp", cfg.initEsp)
            cfg.osImg = j.optString("osImg", cfg.osImg)
            cfg.systemosImg = j.optString("systemosImg", cfg.systemosImg)
            cfg.dataStore = j.optString("dataStore", cfg.dataStore)
            cfg.droidvmDisk = j.optString("droidvmDisk", cfg.droidvmDisk)
            cfg.rootApfs = j.optString("rootApfs", cfg.rootApfs)
            cfg.systemApfs = j.optString("systemApfs", cfg.systemApfs)
        }
        return cfg
    }

    fun saveConfig(ctx: Context, cfg: Config) {
        ensureDirs(ctx)
        val j = JSONObject().apply {
            put("memMB", cfg.memMB)
            put("cpus", cfg.cpus)
            put("name", cfg.name)
            put("gpu", cfg.gpu)
            put("gpuArg", cfg.gpuArg)
            put("vnc", cfg.vnc)
            put("vncHost", cfg.vncHost)
            put("vncPort", cfg.vncPort)
            put("vncCap", cfg.vncCap)
            put("initEsp", cfg.initEsp)
            put("osImg", cfg.osImg)
            put("systemosImg", cfg.systemosImg)
            put("dataStore", cfg.dataStore)
            put("droidvmDisk", cfg.droidvmDisk)
            put("rootApfs", cfg.rootApfs)
            put("systemApfs", cfg.systemApfs)
        }
        configFile(ctx).writeText(j.toString())
    }

    // ============================ 脚本生成 ============================

    /**
     * 生成启动脚本（基于参考 启动iOS.sh，所有参数均由配置注入）。
     * 返回写入的脚本文件；若未检测到 crosvm，返回 null。
     */
    fun buildBootScript(ctx: Context, cfg: Config): File? {
        ensureDirs(ctx)
        val dvm = detectBinDir(ctx) ?: return null
        val script = generateBootShell(cfg, dvm, privateDir(ctx).absolutePath)
        val f = bootScript(ctx)
        f.writeText(script)
        f.setExecutable(true, false)
        return f
    }

    fun buildStopScript(ctx: Context): File {
        ensureDirs(ctx)
        val f = stopScript(ctx)
        f.writeText(STOP_SHELL)
        f.setExecutable(true, false)
        return f
    }

    private fun sh(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")

    private fun generateBootShell(cfg: Config, dvm: String, privPath: String): String {
        val gpuArg = if (cfg.gpuArg.isBlank())
            "2d,vulkan=false,displays=[[mode=windowed[720,1584],refresh-rate=120,dpi=[160,160]]]"
        else sh(cfg.gpuArg)
        val vncCap = if (cfg.vncCap.isBlank()) "" else ",transport-cap=${sh(cfg.vncCap)}"
        // 模板规则：
        //  - 赋值行直接写变量名（VAR=...），绝不能带 @D 前缀（否则生成 $VAR= 非法赋值）；
        //  - 引用变量写 @DVAR（替换成 $VAR），带默认值写 @D{VAR:-x}；
        //  - Kotlin 插值只用 ${sh(...)} / ${cfg.xxx} 注入配置值。
        val t = """#!/system/bin/sh
# 由 UbuntuDSU (gunyahVM) 自动生成 —— 参数全部由 APP 配置注入
# 生成时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}

DVM="${sh(dvm)}"
PRIV="${sh(privPath)}"
WORK="@DDVM/../work"
DIR="@DDVM/../images"
MEDIA="@DDVM/../images"

# DVM 兜底：探测目录失效时在私有目录里重新找 crosvm 所在目录
if [ -z "@DDVM" ] || [ ! -d "@DDVM" ]; then
    DVM=$(dirname "$(find "@DPRIV" -name crosvm -type f 2>/dev/null | head -1)" 2>/dev/null)
fi
[ -n "@DDVM" ] && [ -d "@DDVM" ] || { echo "[!!] 找不到工具链目录（先在「工具链」页下载）: @DDVM"; exit 1; }

CROSVM="@DDVM/crosvm"
[ -x "@DCROSVM" ] || CROSVM=$(find "@DDVM" -name crosvm -type f 2>/dev/null | head -1)
[ -n "@DCROSVM" ] || { echo "[!!] 找不到 crosvm"; exit 1; }

BIOS="@DDVM/edk2-gunyah.fd"
[ -f "@DBIOS" ] || BIOS="@DDVM/droidvm/edk2-virtio-gpu.fd"
[ -f "@DBIOS" ] || BIOS=$(find "@DPRIV" -name 'edk2*.fd' 2>/dev/null | head -1)
[ -f "@DBIOS" ] || { echo "[!!] 找不到 UEFI 固件 edk2-*.fd"; exit 1; }

MEM=${cfg.memMB}
CPUS=${cfg.cpus}
NAME="${sh(cfg.name)}"

SYM="_ZNSt3__113__hash_memoryEPKvm"
LIBCXX_SO=""
has_sym() { grep -aq "@DSYM" "@D1" 2>/dev/null; }
for cand in \
    /apex/com.android.runtime/lib64/bionic/libc++.so \
    /system/lib64/libc++.so \
    $(find "@DDVM" -name 'libc++.so' 2>/dev/null); do
    [ -z "@Dcand" ] && continue
    [ -f "@Dcand" ] || continue
    if has_sym "@Dcand"; then
        mkdir -p "@DWORK"
        cp -f "@Dcand" "@DWORK/libc++.so" 2>/dev/null && { LIBCXX_SO="@DWORK/libc++.so"; break; }
    fi
done
[ -n "@DLIBCXX_SO" ] || {
    echo "[!!] 找不到带 @DSYM 的 libc++.so (已查 APEX / 系统 / 工具链目录)"
    echo "[!!] 请确认工具链已完整解压"
    exit 1
}
echo "[*] 使用 libc++.so: @DLIBCXX_SO"

export LD_LIBRARY_PATH="@DDVM/lib:@D{LD_LIBRARY_PATH:-}"
export LD_PRELOAD="@DLIBCXX_SO"

INIT_ESP="${sh(cfg.initEsp)}"
OS_IMG="${sh(cfg.osImg)}"
SYSTEMOS_IMG="${sh(cfg.systemosImg)}"
DATA_STORE="${sh(cfg.dataStore)}"
DROIDVM_DISK="${sh(cfg.droidvmDisk)}"
ROOT_APFS="${sh(cfg.rootApfs)}"
SYSTEM_APFS="${sh(cfg.systemApfs)}"

check_img() {
    if [ -f "@D1" ]; then
        sz=@D(stat -c %s "@D1" 2>/dev/null || echo 0)
        if [ "@Dsz" -gt 0 ] && [ @D((sz % 512)) -ne 0 ]; then
            truncate -s %512 "@D1" 2>/dev/null && \
                echo "[*] 镜像 @D(basename "@D1") 已补齐到 512 对齐"
        fi
        return 0
    fi
    echo "[!!] 缺少镜像(待获取): @D1"
    return 1
}
got=0
check_img "@DINIT_ESP"      && got=@D((@Dgot + 1))
check_img "@DOS_IMG"        && got=@D((@Dgot + 1))
check_img "@DSYSTEMOS_IMG"  && got=@D((@Dgot + 1))
check_img "@DDATA_STORE"    && got=@D((@Dgot + 1))
if [ "@Dgot" -eq 4 ]; then
    echo "[*] 已检测到真镜像四件套, 真启动建议大内存: MEM>=4096"
fi

have_flag() { grep -aq -- "--@D1" "@DCROSVM" 2>/dev/null; }

CMD="@DCROSVM run \
  @DBIOS \
  --name @DNAME \
  --mem @DMEM \
  --cpus @DCPUS \
  --hypervisor gunyah \
  --disable-sandbox"
have_flag no-balloon && CMD="@DCMD --no-balloon"
have_flag protected-vm-without-firmware && CMD="@DCMD --protected-vm-without-firmware"
have_flag prepare-lend-mthp-mode && CMD="@DCMD --prepare-lend-mthp-mode chunked"
VARSFD="@DDVM/usr/share/droidvm/edk2-gunyah.vars.fd"
if have_flag pflash && [ -f "@DVARSFD" ]; then
    CMD="@DCMD --pflash @DVARSFD,block_size=262144"
fi

# 逐个挂镜像：未选择/不存在的跳过，避免 crosvm 拿到空路径直接报错
if [ -n "@DDROIDVM_DISK" ] && [ -f "@DDROIDVM_DISK" ]; then
    CMD="@DCMD --block path=@DDROIDVM_DISK,ro=true"
else
    echo "[!!] 未选择 droidvm-disk.img（到「镜像映射」页选择）"
fi
if [ -n "@DROOT_APFS" ] && [ -f "@DROOT_APFS" ]; then
    CMD="@DCMD --block path=@DROOT_APFS,ro=true"
else
    echo "[!!] 未选择 root.apfs"
fi
if [ -n "@DSYSTEM_APFS" ] && [ -f "@DSYSTEM_APFS" ]; then
    CMD="@DCMD --block path=@DSYSTEM_APFS,ro=true"
else
    echo "[!!] 未选择 system.apfs"
fi
if [ -n "@DDATA_STORE" ] && [ -f "@DDATA_STORE" ]; then
    CMD="@DCMD --block path=@DDATA_STORE"
else
    echo "[!!] 未选择 data-store.img"
fi

GPU=${if (cfg.gpu) 1 else 0}
VNC=${if (cfg.vnc) 1 else 0}
if have_flag gpu && [ "@D{GPU:-1}" = "1" ]; then
    GPU_ARG="${gpuArg}"
    CMD="@DCMD --gpu @DGPU_ARG"
fi
if have_flag vnc-server && [ "@D{VNC:-1}" = "1" ]; then
    VNC_HOST="${sh(cfg.vncHost)}"
    VNC_PORT=${cfg.vncPort}
    i=0
    while [ @Di -lt 10 ] && netstat -tln 2>/dev/null | grep -q "[.:]@DVNC_PORT "; do
        echo "[*] VNC 端口 @DVNC_PORT 已被占用, 换下一个..."
        VNC_PORT=@D((@DVNC_PORT + 1))
        i=@D((i + 1))
    done
    VNC_ARG="host=@DVNC_HOST,port=@DVNC_PORT${vncCap}"
    CMD="@DCMD --vnc-server @DVNC_ARG"
fi
have_flag serial && CMD="@DCMD --serial type=stdout,num=1,hardware=sbsa"

echo "[*] 启动 UniXNU (gunyah) : @DNAME / @DMEM M / @DCPUS cpu / GPU=@DGPU VNC=@DVNC"

OLDPIDS=$(pidof crosvm 2>/dev/null || true)
if [ -n "@DOLDPIDS" ]; then
    echo "[*] 发现残留 crosvm 进程 (pid: @DOLDPIDS), 正在结束..."
    kill @DOLDPIDS 2>/dev/null
    sleep 1
    kill -9 @DOLDPIDS 2>/dev/null
    echo "[*] 残留进程已清理"
fi

mkdir -p "@DWORK" /var/empty 2>/dev/null
echo "[*] LD_LIBRARY_PATH=@DLD_LIBRARY_PATH"
exec @DCMD
"""
        return t.replace("@D", "$")
    }

    private val STOP_SHELL = """#!/system/bin/sh
echo "==> [1] 查找 crosvm 相关进程"
ps -A -o PID,PPID,NAME,COMMAND 2>/dev/null | grep -i crosvm | grep -v grep
echo "==> [2] 先优雅终止（SIGTERM）"
pkill -15 -f "crosvm" 2>/dev/null
pkill -15 -f "droidvm" 2>/dev/null
sleep 1
echo "==> [3] 强制 SIGKILL 兜底"
pkill -9 -f "crosvm" 2>/dev/null
pkill -9 -f "gunyah" 2>/dev/null
pkill -9 -f "droidvm" 2>/dev/null
for pid in $(ls /proc | grep -E '^[0-9]+@D'); do
    if grep -qa "crosvm" "/proc/@Dpid/cmdline" 2>/dev/null; then
        kill -9 "@Dpid" 2>/dev/null
    fi
done
sleep 1
echo "==> [4] 检查是否还有残留"
LEFT=$(ps -A 2>/dev/null | grep -i crosvm | grep -v grep)
if [ -z "@DLEFT" ]; then echo "✅ crosvm 已全部停止"; else echo "⚠️ 仍有残留："; echo "@DLEFT"; fi
echo "==> done."
""".replace("@D", "$")

    // ============================ 内核模块 ============================

    /** 检测当前内核版本（uname -r），用于匹配 modules.ko 下的版本目录。 */
    fun detectKernelVersion(): String {
        val r = RootShell.exec("uname -r", timeoutMs = 10000)
        return r.stdout.trim().lines().firstOrNull()?.trim() ?: ""
    }

    // ============================ 虚拟机环境检测 ============================

    /** 处理器型号：ro.soc.model → ro.board.platform → ro.hardware 依次取（getprop）。 */
    fun socInfo(): String {
        for (p in listOf("ro.soc.model", "ro.board.platform", "ro.hardware")) {
            val v = RootShell.exec("getprop $p", timeoutMs = 10000).stdout.trim()
            if (v.isNotBlank()) return v
        }
        return "未知"
    }

    /** 物理内存总量（GB，向下取整），读 /proc/meminfo。 */
    fun totalMemGB(): String {
        return runCatching {
            File("/proc/meminfo").readLines().firstOrNull { it.startsWith("MemTotal") }
                ?.substringAfter(":")?.trim()?.substringBefore(" ")
                ?.toLongOrNull()?.let { kb -> "${kb / 1048576}GB" }
        }.getOrNull() ?: "未知"
    }

    /** gunyah 虚拟化设备是否可用（/dev/gunyah 存在且 root 可见）。 */
    fun gunyahDevReady(): Boolean {
        val r = RootShell.exec("ls /dev/gunyah", timeoutMs = 10000)
        return r.success && r.stdout.contains("gunyah")
    }

    data class KoModule(
        val path: String,      // 绝对路径
        val fileName: String,  // xxx.ko
        val versionFolder: String, // 所在版本目录名
        val moduleName: String,   // 去掉 .ko 的模块名
    )

    /**
     * 模块名归一化：内核 /proc/modules 里「-」一律显示为「_」，
     * 且名字可能与 .ko 文件名不同，直接用文件名比对会永远「未加载」，
     * 再次 insmod 就报 "File exists"。
     */
    fun modKey(name: String): String = name.removeSuffix(".ko").replace('-', '_').lowercase()

    /** 版本目录是否适配当前内核：如 kernel=6.6.118-android15-8 ↔ folder=android15-6.6 */
    fun versionMatches(kernel: String, folder: String): Boolean {
        if (kernel.isBlank()) return false
        if (folder.contains(kernel, ignoreCase = true)) return true
        val majMin = kernel.split(".").take(2).joinToString(".")   // "6.6"
        val tag = kernel.split("-").getOrNull(1).orEmpty()          // "android15"
        return folder.contains(majMin) && (tag.isBlank() || folder.contains(tag))
    }

    /** 扫描 modules.ko 目录，按版本文件夹分组返回所有 .ko。 */
    fun scanModules(ctx: Context): List<KoModule> {
        val modDir = detectModulesDir(ctx) ?: return emptyList()
        val base = File(modDir)
        val out = mutableListOf<KoModule>()
        base.walkTopDown().forEach { f ->
            if (f.isFile && f.name.endsWith(".ko")) {
                val versionFolder = f.parentFile?.name ?: "unknown"
                out.add(
                    KoModule(
                        path = f.absolutePath,
                        fileName = f.name,
                        versionFolder = versionFolder,
                        moduleName = f.name.removeSuffix(".ko"),
                    )
                )
            }
        }
        return out
    }

    /** 当前已加载模块名集合（/proc/modules，名字已按 [modKey] 归一化）。 */
    fun loadedModules(): Set<String> {
        val r = RootShell.exec("cat /proc/modules", timeoutMs = 10000)
        if (!r.success) return emptySet()
        return r.stdout.lines().mapNotNull { line ->
            line.trim().split("\\s+".toRegex()).firstOrNull()
        }.filter { it.isNotEmpty() }.map { modKey(it) }.toSet()
    }

    /** 模块是否已加载（归一化比对）。 */
    fun isLoaded(m: KoModule, loaded: Set<String>): Boolean = loaded.contains(modKey(m.moduleName))

    /** insmod 中转目录：部分 ROM 的 su 上下文读不了 APP 私有目录，先拷到 /data/local/tmp 再加载 */
    private const val KO_RELAY = "/data/local/tmp/gunyahvm_ko"

    /**
     * 加载内核模块。返回 (是否成功, 失败原因)。
     * 先把 .ko root 拷贝到 /data/local/tmp 中转目录再 insmod；
     * 已加载或返回 File exists（重复加载）都视为成功。
     */
    fun insmod(path: String): Pair<Boolean, String> {
        if (modKey(File(path).name) in loadedModules()) return true to "already"
        val tmp = "$KO_RELAY/${File(path).name}"
        val cp = RootShell.exec(
            "mkdir -p $KO_RELAY && cp -f '$path' '$tmp' && chmod 644 '$tmp' && echo __CP_OK__",
            timeoutMs = 20000,
        )
        if (!cp.stdout.contains("__CP_OK__")) {
            return false to "拷贝到中转目录失败（需要 ROOT）"
        }
        val r = RootShell.exec("insmod '$tmp'", timeoutMs = 20000)
        if (r.success) return true to ""
        val err = r.stderr.ifBlank { r.stdout }
        if (err.contains("File exists", ignoreCase = true)) return true to "already"
        return false to err.ifBlank { "insmod 失败（code=${r.code}）" }
    }

    /** 卸载内核模块。返回 (是否成功, 失败原因)。 */
    fun rmmod(name: String): Pair<Boolean, String> {
        val r = RootShell.exec("rmmod '$name'", timeoutMs = 20000)
        return r.success to if (r.success) ""
        else r.stderr.ifBlank { r.stdout.ifBlank { "rmmod 失败（code=${r.code}）" } }
    }

    /** 启动：生成脚本并以 root 执行。返回 (started, log)。 */
    fun start(ctx: Context, cfg: Config): Pair<Boolean, String> {
        val script = buildBootScript(ctx, cfg) ?: return false to "[!!] 未检测到工具链 crosvm，请先下载"
        val log = File(privateDir(ctx), "boot.log")
        // setsid + 后台 + 重定向，使 crosvm 脱离 su 会话常驻，不被 RootShell 超时等待杀掉
        val cmd = "setsid sh \"${script.absolutePath}\" > \"${log.absolutePath}\" 2>&1 < /dev/null &"
        val r = RootShell.exec(cmd, timeoutMs = 8000)
        return (r.code == 0) to "已下发启动指令（日志: ${log.absolutePath}）\n${r.stdout}"
    }

    /** 当前 crosvm 是否在运行。 */
    fun isVmRunning(): Boolean {
        val r = RootShell.exec("pidof crosvm", timeoutMs = 8000)
        return r.success && r.stdout.trim().isNotEmpty()
    }

    fun stop(ctx: Context): Pair<Boolean, String> {
        val script = buildStopScript(ctx)
        val r = RootShell.exec("sh \"${script.absolutePath}\"", timeoutMs = 20000)
        return r.success to r.stdout
    }
}
