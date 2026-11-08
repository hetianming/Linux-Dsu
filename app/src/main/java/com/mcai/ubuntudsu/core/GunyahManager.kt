package com.mcai.ubuntudsu.core

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
        "https://github.com/hetianming/GsiManager/releases/download/UBC/unixnu-bin.zip"

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

    // ---- 日志体积看护（v2.0.22）：guest 串口刷屏时按体积轮转 boot.log ----
    private const val LOG_GUARD_SCRIPT = "unixnu-log-guard.sh"
    private const val LOG_GUARD_STOP = "log-guard.stop"
    private const val LOG_GUARD_MAX = 4 * 1024 * 1024   // 4 MiB 触发轮转
    private const val LOG_GUARD_KEEP = 256 * 1024        // 保留上一轮尾部 256 KiB

    /** 内置的 MMIO 版 crosvm（自编：gunyah + virtio-mmio，见 assets/）。 */
    const val CROSVM_BIN = "crosvm-mmio"
    private const val CROSVM_ASSET = "crosvm-mmio"

    /** 内置极简 DHCPv4 服务（arm64，纯 libc，见 assets/gunydhcpd）。 */
    const val DHCP_BIN = "gunydhcpd3"
    private const val DHCP_ASSET = "gunydhcpd"

    /**
     * 内置 TAP 准备工具（arm64，纯 ioctl，见 assets/gunytap）。
     *
     * 不能依赖 `ip tuntap add ... persist`：很多 ROM 的 toybox `ip` 不认 persist，
     * 命令一退出 tap 就消失，crosvm 只好自己重建一个不在 bridge 里的 tap —— 这正是
     * 「guest 有网卡、MAC 也对，但永远收不到 DHCP」的根因。
     */
    const val TAP_BIN = "gunytap"
    private const val TAP_ASSET = "gunytap"

    /**
     * 触摸桥：把 APP 的触摸事件转发给 crosvm 的 `--input multi-touch`。
     * crosvm 只认文件系统 unix socket，而 Android Java 层建不了，所以由它 bind。
     */
    const val TOUCHD_BIN = "touchd"
    private const val TOUCHD_ASSET = "touchd"

    /** 新增硬盘（附加块设备） */
    data class ExtraDisk(
        var label: String = "",
        var path: String = "",
        var ro: Boolean = true,
    )

    data class Config(
        var memMB: Int = 4096,
        var cpus: Int = 4,
        var name: String = "unixnu",
        var gpu: Boolean = true,
        var gpuArg: String = "2d,vulkan=false,displays=[[mode=windowed[720,1584],refresh-rate=120,dpi=[160,160]]]",
        // ---- 显示参数（VNC 黑屏的关键）----
        /**
         * 显示模式：
         *  simplefb —— EDK2/UEFI 直接写帧缓冲，VNC 抓 screen=simplefb，固件阶段一定能看到画面
         *  gpu2d    —— virtio-gpu 2D（需要固件内含 virtio-gpu 驱动）
         *  gpu3d    —— virtio-gpu 3D + vulkan
         *  custom   —— 使用 gpuArg 手填参数
         */
        var displayMode: String = "simplefb",
        /** 分辨率 宽x高，如 720x1584；simplefb 与 gpu displays 共用 */
        var resolution: String = "720x1584",
        var vnc: Boolean = true,
        var vncHost: String = "127.0.0.1",
        var vncPort: Int = 5905,
        var vncCap: String = "",
        /** 保护模式：auto(自动回退) | pseudo | without | none | gpu */
        var prot: String = "auto",
        /**
         * MMIO 引导（Darwin / XNU）：
         *  guest 只有 virtio-mmio 驱动、没有 PCI 宿主驱动时，块设备必须走
         *  `--virtio-mmio-block`，输入 / GPU 也要挂到 MMIO 传输上。
         *  开启后使用 APP 自带的 crosvm-mmio（含 gunyah + MMIO 支持），
         *  工具链里那个只认 PCI 的 crosvm 不再参与。
         */
        var mmio: Boolean = false,
        /** 触摸输入设备（--input），留空则不注入 */
        var inputDev: String = "",
        /**
         * 平板模式：把 APP 里的触摸事件通过 unix socket 注入 guest 的 virtio-input
         * 多点触摸屏（`--input multi-touch[...]`），而不是走 VNC 的单指针模拟。
         * 打开后 VNC 只负责画面，触摸/双指手势由 guest 自己处理。
         */
        var touchPassthrough: Boolean = false,
        /**
         * 自定义 UEFI 固件（.fd）。留空则用工具链自带的 bin/edk2-gunyah.fd。
         * 适配其它 UEFI 固件（如 edk2-qcpeek.fd / uefi-*.fd）时在这里指定。
         */
        var firmwarePath: String = "",
        /** 自定义 UEFI 变量固件（vars.fd / NVRAM）。留空则用工具链自带的 edk2-gunyah.vars.fd */
        var varsPath: String = "",
        // ---- 盘位模型：启动盘 / 光盘 / 系统盘 / 数据盘 + 新增硬盘 ----
        /** 启动盘（引导磁盘） */
        var bootDisk: String = "",
        /** 启动盘是否只读；qcow2 增量盘需要可写 */
        var bootDiskRo: Boolean = true,
        /** 光盘（光盘镜像 ISO/IMG，只读挂载，可选） */
        var cdRom: String = "",
        /** 系统盘（OS 系统镜像） */
        var systemDisk: String = "",
        /** 系统盘是否只读；qcow2 增量盘需要可写 */
        var systemDiskRo: Boolean = true,
        /** 数据盘（数据存储，读写挂载） */
        var dataDisk: String = "",
        /** 新增硬盘（附加镜像，动态增删） */
        var extraDisks: MutableList<ExtraDisk> = mutableListOf(),
        // ---- 运行内存适配（gh_hugepage_reserve 内核模块）----
        /** 启动前自动加载适配当前内核的 gh_hugepage_reserve.ko（2MB 大页池）。
         *  默认关闭：自 v2.0.21 起大页池改由 Magisk 模块「gh-hugepage-reserve」在开机时统一预留，
         *  APP 不再自行加载该模块，避免与模块池抢名冲突（见「工具链 → 安装大页池模块」）。 */
        var hugepageAuto: Boolean = false,
        /** 已废弃：池大小由模块按 VM 需求(refill_stat.pool_want)自动决定，不再写死 */
        var hugepageMB: Int = 0,
        /** 采集模式：0 只读 / 1 仅连续块 / 2 扫描+系统回收 / 3 扫描+逐窗驱逐（默认最狠） */
        var hpAcquireMode: Int = 3,
        /** 系统保留内存（MB，写 system_reserve_mb）；0 = 用模块默认（min(RAM/2,6144)） */
        var hpSystemReserveMB: Int = 0,
        /** 宿主内存不足时自动把本次 VM 内存降到可用额度（否则 4096 在后台吃满时起不来） */
        var memAutoFit: Boolean = true,
        /**
         * LEND 内存的 mTHP 准备策略：
         *  - auto   按 SoC 判断（sm8650 这类 eager-parcel 内核用 single，其余用 chunked），
         *           并在后半段兼容模式里自动换另一种，避免一次判错就全盘失败
         *  - single 一次性把整块内存折叠成 2MB 再 LEND（eager-parcel）
         *  - chunked 切成 <=256MB 的分片逐个 LEND（demand-paging，sm8750）
         */
        var mthpMode: String = "auto",
        /**
         * 启动顺序：auto 由固件决定 / cdrom 优先光盘 / disk 优先硬盘。
         * 实现上是给块设备排 bootindex，并在「优先光盘」时使用一份干净的 UEFI
         * 变量存储（否则 NVRAM 里已存的 Windows 启动项会盖过 bootindex）。
         */
        var bootOrder: String = "auto",
        /** 启动后 VNC 一就绪就自动进入全屏显示器（能看到 UEFI / 开机动画） */
        var autoDisplay: Boolean = true,
        /**
         * 启用 virtio-net：启动前自动建 tap + bridge，跑内置 DHCP，并做 NAT，
         * 虚拟机可直接上网（Windows / Linux 都需要客户机装 virtio-net 驱动）。
         */
        var network: Boolean = true,
        /**
         * 启用 virtio-snd：脚本会先探测 crosvm 是否链接了真实 libaaudio，
         * stub 版（没有 libaaudio.so 依赖）会自动跳过，避免把能启动的 VM 弄挂。
         */
        var audio: Boolean = true,
        /**
         * MMIO 模式下把 virtio-snd 挂到 virtio-mmio 传输（--virtio-mmio-snd）。
         * XNU / Darwin guest 没有 PCI 宿主驱动，声音设备和块/网卡一样需要改挂。
         */
        var mmioSound: Boolean = true,
        /**
         * PCI 模式优先使用工具链自带的 crosvm，而不是 APP 内置自编的 crosvm-mmio。
         * v34 起两种模式都改用内置自编版后有网络回归（tap / virtio-net 行为差异），
         * 用户反馈「换成我们自己的 crosvm 后网络不正常」。此开关默认开 = 回到
         * 之前网络正常的组合；MMIO（Darwin/XNU）仍必须用内置自编版。
         */
        var pciUseToolchainCrosvm: Boolean = true,
        /**
         * 显示后端：vnc = 内置 AVNC（默认，全系统可用）；
         * android = crosvm 的 Android 原生 Surface 后端（--android-display-service
         * crosvm_display）。v2.0 起画面与输入全部由主程序内置的
         * NativeDisplayActivity 完成，不需要另装任何显示器 APP。
         * 脚本会先探测 crosvm 是否真的编了 android_display 后端，
         * 不支持时自动保持 VNC 并在 boot.log 里说明，绝不会把 VM 弄挂。
         */
        var displayBackend: String = "vnc",
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

    /**
     * DNA 同款下载（与 [downloadAndExtract] 共用「并发测速 + 慢线自动切换」逻辑）：
     * 直接下载到 [target]，不负责解压。用于从 GitHub Release 拉取任意资产（如大页内存模块）。
     *
     * 1) 对「直连 + 全部镜像」并发测速 3s，按实际速率排序；
     * 2) 从最快线路开始下载，后台监控速率，10s 窗口低于 50KB/s 自动切下一条；
     * 3) 下载过程回调 (done,total) 给进度条，并实时回报「测速 / 使用最快线路 / 切线」等状态。
     *
     * @return 是否成功下载到非空文件
     */
    fun downloadFileTo(
        ctx: Context,
        rawUrl: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
        onState: (String) -> Unit,
    ): Boolean {
        ensureDirs(ctx)
        val candidates = mutableListOf(rawUrl)
        MIRRORS.forEach { candidates.add(it + rawUrl) }

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
        target.delete()
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
                downloadHttp(url, target, onProgress) { delta ->
                    bytes.addAndGet(delta)
                    if (tooSlow.get()) throw RuntimeException("slow route switched")
                }
            }.getOrDefault(false)
            try { watcher.join(1000) } catch (_: Exception) {}
            if (dl && target.length() > 0) { ok = true; break }
            target.delete()
        }
        if (!ok) onState("所有线路下载失败")
        return ok
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

    /**
     * 把内置的 crosvm（assets/crosvm-mmio）投放到私有目录并加执行位。
     *
     * 这份二进制由本项目从源码自编（Droid-VM 的 crosvm fork，Apache-2.0），
     * 同一份同时编了 **PCI 与 MMIO 两种传输**，靠命令行开关切换：
     *   - 不加 `--virtio-mmio-*` → 设备走 PCI（Windows / Debian）
     *   - 加 `--virtio-mmio-*`   → 设备走 MMIO（Darwin / XNU，guest 没有 PCI 宿主驱动）
     *
     * 因此**两种启动模式都用它**，工具链 zip 里那份 crosvm 只作兜底 —— 那份的
     * 版本随发行版漂移，用户换了 rootfs 就可能不认某些参数。
     *
     * 编译记录（feature 组合）见 docs/crosvm-自编说明.md。
     *
     * @return 可执行文件；assets 里没有或复制失败时返回 null
     */
    fun ensureCrosvmBin(ctx: Context): File? {
        ensureDirs(ctx)
        val out = File(privDir, CROSVM_BIN)
        // 已经投放且大小一致就不再搬 11 MB（assets 未压缩，openFd 能拿到真实长度）
        val assetLen = runCatching {
            ctx.assets.openFd(CROSVM_ASSET).use { it.length }
        }.getOrDefault(-1L)
        if (assetLen > 0 && out.exists() && out.length() == assetLen && out.canExecute()) {
            return out
        }
        val fresh = runCatching {
            // 临时文件 + rename：旧 crosvm 可能还在跑，直接覆盖会 ETXTBSY(26)。
            // rename 是原子替换，旧 inode 继续给旧进程用，新启动的进程拿到新文件。
            val tmp = File(privDir, CROSVM_BIN + ".new")
            val src = ctx.assets.open(CROSVM_ASSET)
            src.use { ins ->
                FileOutputStream(tmp).use { fos -> ins.copyTo(fos, 64 * 1024) }
            }
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IllegalStateException("rename failed")
            }
            true
        }.getOrDefault(false)
        if (!fresh) {
            Log.w(TAG, "assets/$CROSVM_ASSET 缺失或复制失败，内置 crosvm 不可用")
            return null
        }
        return out
    }

    /**
     * 把内置的 DHCP 服务投放到私有目录并加执行位。
     * 网络开关打开时使用；失败不影响 VM 启动，只是虚拟机拿不到地址。
     */
    fun ensureDhcpBin(ctx: Context): File? {
        ensureDirs(ctx)
        val out = File(privDir, DHCP_BIN)
        // 【关键修复】以前只判断「文件存在且可执行」就直接复用。
        // 用户升级 APK 后，私有目录里留着的还是第一次安装时那份旧二进制：
        //   旧版要求 -i iface，启动时参数不匹配 → 打印 usage 后立刻退出；
        //   表现是 tap rx 一直在涨、NAT/FORWARD/rule 全对，但 guest 永远拿不到 IP，
        //   net.log 里只有一行 usage —— 看起来像「网络不通」，实际是 DHCP 根本没跑。
        // 必须按 assets 的真实长度校验（noCompress 保证 openFd 能拿到未压缩长度）。
        val assetLen = runCatching {
            ctx.assets.openFd(DHCP_ASSET).use { it.length }
        }.getOrDefault(-1L)
        if (assetLen > 0 && out.exists() && out.length() == assetLen && out.canExecute()) {
            return out
        }
        val fresh = runCatching {
            // 先写临时文件再 rename：旧进程可能还活着，直接覆盖会 ETXTBSY。
            // rename 是原子替换，正在执行的旧 inode 不受影响，下次启动拿到新文件。
            val tmp = File(privDir, DHCP_BIN + ".new")
            ctx.assets.open(DHCP_ASSET).use { ins ->
                FileOutputStream(tmp).use { fos -> ins.copyTo(fos, 8 * 1024) }
            }
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IllegalStateException("rename failed")
            }
            true
        }.getOrDefault(false)
        if (!fresh) {
            Log.w(TAG, "assets/$DHCP_ASSET 缺失或复制失败，虚拟机将无法自动获取 IP")
            return null
        }
        return out
    }

    /**
     * 投放内置 TAP 准备工具（gunytap）。
     *
     * 它直接走 ioctl 做 TUNSETPERSIST + SIOCBRADDIF，不依赖 ROM 的 `ip` 是否支持
     * `persist` —— 这是「网卡在、MAC 对、但永远收不到 DHCP」最常见的根因。
     */
    fun ensureTapTool(ctx: Context): File? {
        ensureDirs(ctx)
        val out = File(privDir, TAP_BIN)
        // 同 ensureDhcpBin：必须按长度校验，否则升级后永远跑旧二进制。
        val assetLen = runCatching {
            ctx.assets.openFd(TAP_ASSET).use { it.length }
        }.getOrDefault(-1L)
        if (assetLen > 0 && out.exists() && out.length() == assetLen && out.canExecute()) {
            return out
        }
        val fresh = runCatching {
            val tmp = File(privDir, TAP_BIN + ".new")
            ctx.assets.open(TAP_ASSET).use { ins ->
                FileOutputStream(tmp).use { fos -> ins.copyTo(fos, 8 * 1024) }
            }
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IllegalStateException("rename failed")
            }
            true
        }.getOrDefault(false)
        if (!fresh) {
            Log.w(TAG, "assets/$TAP_ASSET 缺失或复制失败，将回退到 ip tuntap")
            return null
        }
        return out
    }

    /** 投放触摸桥（同 gunydhcpd：按长度校验，升级后必定重投）。 */
    fun ensureTouchdBin(ctx: Context): File? {
        ensureDirs(ctx)
        val out = File(privDir, TOUCHD_BIN)
        // 必须比对「内容」而不是长度：覆盖安装升级 touchd 时，新旧二进制
        // 长度可能完全相同，只比长度会让旧版（会把 crosvm 输入连接一起关掉
        // 的那个）继续生效，输入桥永远修不好。文件仅 8KB，全量读可忽略。
        val assetBytes = runCatching {
            ctx.assets.open(TOUCHD_ASSET).use { it.readBytes() }
        }.getOrNull()
        if (assetBytes != null && assetBytes.isNotEmpty() &&
            out.exists() && out.canExecute() &&
            runCatching { out.readBytes().contentEquals(assetBytes) }.getOrDefault(false)
        ) {
            return out
        }
        val fresh = runCatching {
            val tmp = File(privDir, TOUCHD_BIN + ".new")
            FileOutputStream(tmp).use { fos -> fos.write(assetBytes!!) }
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IllegalStateException("rename failed")
            }
            true
        }.getOrDefault(false)
        if (!fresh) {
            Log.w(TAG, "assets/$TOUCHD_ASSET 缺失或复制失败，平板模式不可用")
            return null
        }
        return out
    }

    /** 查找 modules.ko 目录（内核模块集合）。 */
    fun detectModulesDir(ctx: Context): String? {        ensureDirs(ctx)
        var found: String? = null
        privDir.walkTopDown().forEach { f ->
            if (found == null && f.isDirectory && f.name == "modules.ko") found = f.absolutePath
        }
        return found
    }

    fun isToolchainReady(ctx: Context): Boolean =
        detectBinDir(ctx) != null

    // ============================ 镜像格式 ============================

    /**
     * 读文件头判断镜像格式（与 crosvm disk::detect_image_type 同一判定）：
     *  - QCOW2：magic "QFI\xfb"
     *  - RAW：其余（含 .img / .bin / 裸分区表）
     * crosvm 的 --block 是按 magic 自动分派的，扩展名无所谓；
     * 这里只用于界面标注与「可写 / 只读」建议。
     */
    fun imageFormat(path: String): String {
        if (path.isBlank()) return ""
        return runCatching {
            java.io.File(path).inputStream().use { ins ->
                val b = ByteArray(4)
                if (ins.read(b) != 4) return@runCatching ""
                if (b[0] == 'Q'.code.toByte() && b[1] == 'F'.code.toByte() &&
                    b[2] == 'I'.code.toByte() && b[3] == 0xFB.toByte()
                ) "QCOW2" else "RAW"
            }
        }.getOrDefault("")
    }

    /**
     * 新建一块空白虚拟硬盘（raw 稀疏文件，落在 APP 私有目录 disks/）。
     *
     * 稀疏是这套方案的关键：ftruncate 只改 inode 的逻辑长度，ext4/f2fs 不会真的
     * 分配 64GB，实际占用随 guest 写入逐块增长。所以手机上也能给 Windows 开出
     * 64GB 的系统盘，装完大约只吃掉 20~30GB。
     *
     * 放私有目录而不是 /sdcard：FUSE 存储对稀疏文件支持不稳定，
     * 部分机型会按整盘 fallocate，64GB 直接 ENOSPC。
     */
    fun createBlankDisk(ctx: Context, name: String, sizeGb: Int): Result<String> {
        ensureDirs(ctx)
        if (sizeGb < 8 || sizeGb > 512) return Result.failure(IllegalArgumentException("大小需在 8~512GB 之间"))
        val safe = name.trim().ifBlank { "disk" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".img", true)) it else "$it.img" }
        val dir = File(privDir, "disks").apply { mkdirs() }
        val f = File(dir, safe)
        if (f.exists()) return Result.failure(IllegalStateException("同名磁盘已存在：${f.absolutePath}"))
        val bytes = sizeGb.toLong() * 1024L * 1024L * 1024L
        return runCatching {
            java.io.RandomAccessFile(f, "rw").use { raf -> raf.setLength(bytes) }
            f.absolutePath
        }
    }

    /**
     * 是不是真正的 ISO9660 光盘镜像：主卷描述符在 32768 偏移，第 1 个字节起是 "CD001"。
     *
     * 只有它才配走 --scsi-block 的 CD-ROM 语义。qcow2 / raw 硬盘走 SCSI 会触发
     * crosvm 重建 refcount 表（整个盘扫一遍），白白吃掉几百 MB page cache，
     * 直接把 Gunyah 的资源借出挤爆（MEM_ACCEPT err=0x9）。
     */
    fun isIsoImage(path: String): Boolean {
        if (path.isBlank()) return false
        return runCatching {
            java.io.RandomAccessFile(path, "r").use { f ->
                f.seek(32769L)
                val b = ByteArray(5)
                if (f.read(b) != 5) false else String(b, Charsets.US_ASCII) == "CD001"
            }
        }.getOrDefault(false)
    }

    /** 私有目录剩余可用空间（字节），用于「新建磁盘」时提示可能不够。 */
    fun freeBytes(ctx: Context): Long {
        ensureDirs(ctx)
        return runCatching { privDir.usableSpace }.getOrDefault(0L)
    }

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
            cfg.displayMode = j.optString("displayMode", cfg.displayMode)
            cfg.resolution = j.optString("resolution", cfg.resolution)
            cfg.vnc = j.optBoolean("vnc", cfg.vnc)
            cfg.vncHost = j.optString("vncHost", cfg.vncHost)
            cfg.vncPort = j.optInt("vncPort", cfg.vncPort)
            cfg.vncCap = j.optString("vncCap", cfg.vncCap)
            cfg.prot = j.optString("prot", cfg.prot)
            cfg.mmio = j.optBoolean("mmio", cfg.mmio)
            cfg.inputDev = j.optString("inputDev", cfg.inputDev)
            cfg.touchPassthrough = j.optBoolean("touchPassthrough", cfg.touchPassthrough)
            cfg.firmwarePath = j.optString("firmwarePath", cfg.firmwarePath)
            cfg.varsPath = j.optString("varsPath", cfg.varsPath)
            // 新盘位模型
            cfg.bootDisk = j.optString("bootDisk", cfg.bootDisk)
            cfg.bootDiskRo = j.optBoolean("bootDiskRo", cfg.bootDiskRo)
            cfg.cdRom = j.optString("cdRom", cfg.cdRom)
            cfg.systemDisk = j.optString("systemDisk", cfg.systemDisk)
            cfg.systemDiskRo = j.optBoolean("systemDiskRo", cfg.systemDiskRo)
            cfg.dataDisk = j.optString("dataDisk", cfg.dataDisk)
            cfg.hugepageAuto = j.optBoolean("hugepageAuto", cfg.hugepageAuto)
            cfg.memAutoFit = j.optBoolean("memAutoFit", cfg.memAutoFit)
            cfg.mthpMode = j.optString("mthpMode", cfg.mthpMode)
            cfg.bootOrder = j.optString("bootOrder", cfg.bootOrder)
            cfg.autoDisplay = j.optBoolean("autoDisplay", cfg.autoDisplay)
            cfg.network = j.optBoolean("network", cfg.network)
            cfg.audio = j.optBoolean("audio", cfg.audio)
            cfg.mmioSound = j.optBoolean("mmioSound", cfg.mmioSound)
            cfg.pciUseToolchainCrosvm = j.optBoolean("pciUseToolchainCrosvm", cfg.pciUseToolchainCrosvm)
            cfg.displayBackend = j.optString("displayBackend", cfg.displayBackend)
            cfg.hugepageMB = j.optInt("hugepageMB", cfg.hugepageMB)
            cfg.hpAcquireMode = j.optInt("hpAcquireMode", cfg.hpAcquireMode)
            cfg.hpSystemReserveMB = j.optInt("hpSystemReserveMB", cfg.hpSystemReserveMB)
            runCatching {
                val arr = j.optJSONArray("extraDisks")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        cfg.extraDisks.add(
                            ExtraDisk(
                                label = e.optString("label", "硬盘${i + 1}"),
                                path = e.optString("path", ""),
                                ro = e.optBoolean("ro", true),
                            ),
                        )
                    }
                }
            }
            // 旧 7 镜像配置自动迁移 → 盘位模型（不丢用户已选镜像）
            if (cfg.bootDisk.isBlank()) cfg.bootDisk = j.optString("droidvmDisk", "")
            if (cfg.cdRom.isBlank()) cfg.cdRom = j.optString("initEsp", "")
            if (cfg.systemDisk.isBlank()) cfg.systemDisk = j.optString("osImg", "")
            if (cfg.dataDisk.isBlank()) cfg.dataDisk = j.optString("dataStore", "")
            if (cfg.extraDisks.isEmpty()) {
                listOf(
                    "systemos.img" to j.optString("systemosImg", ""),
                    "root.apfs" to j.optString("rootApfs", ""),
                    "system.apfs" to j.optString("systemApfs", ""),
                ).forEach { (label, path) ->
                    if (path.isNotBlank()) cfg.extraDisks.add(ExtraDisk(label, path, ro = true))
                }
            }
        }
        return cfg
    }

    /**
     * 只改 mmio 一个字段。
     * 不能拿界面里的 Config 快照整体回写：主界面持有的快照可能比磁盘旧，
     * 整体 save 会把用户在「镜像映射」里刚选的盘覆盖回去。
     */
    fun setMmio(ctx: Context, mmio: Boolean) {
        val c = loadConfig(ctx)
        if (c.mmio == mmio) return
        c.mmio = mmio
        saveConfig(ctx, c)
    }

    fun saveConfig(ctx: Context, cfg: Config) {
        ensureDirs(ctx)
        val j = JSONObject().apply {
            put("memMB", cfg.memMB)
            put("cpus", cfg.cpus)
            put("name", cfg.name)
            put("gpu", cfg.gpu)
            put("gpuArg", cfg.gpuArg)
            put("displayMode", cfg.displayMode)
            put("resolution", cfg.resolution)
            put("vnc", cfg.vnc)
            put("vncHost", cfg.vncHost)
            put("vncPort", cfg.vncPort)
            put("vncCap", cfg.vncCap)
            put("prot", cfg.prot)
            put("mmio", cfg.mmio)
            put("inputDev", cfg.inputDev)
            put("touchPassthrough", cfg.touchPassthrough)
            put("firmwarePath", cfg.firmwarePath)
            put("varsPath", cfg.varsPath)
            put("bootDisk", cfg.bootDisk)
            put("bootDiskRo", cfg.bootDiskRo)
            put("cdRom", cfg.cdRom)
            put("systemDisk", cfg.systemDisk)
            put("systemDiskRo", cfg.systemDiskRo)
            put("dataDisk", cfg.dataDisk)
            put("hugepageAuto", cfg.hugepageAuto)
            put("memAutoFit", cfg.memAutoFit)
            put("mthpMode", cfg.mthpMode)
            put("bootOrder", cfg.bootOrder)
            put("autoDisplay", cfg.autoDisplay)
            put("network", cfg.network)
            put("audio", cfg.audio)
            put("mmioSound", cfg.mmioSound)
            put("pciUseToolchainCrosvm", cfg.pciUseToolchainCrosvm)
            put("displayBackend", cfg.displayBackend)
            put("hugepageMB", cfg.hugepageMB)
            put("hpAcquireMode", cfg.hpAcquireMode)
            put("hpSystemReserveMB", cfg.hpSystemReserveMB)
            put("extraDisks", org.json.JSONArray().apply {
                cfg.extraDisks.forEach { d ->
                    put(JSONObject().apply {
                        put("label", d.label)
                        put("path", d.path)
                        put("ro", d.ro)
                    })
                }
            })
        }
        configFile(ctx).writeText(j.toString())
    }

    // ============================ 脚本生成 ============================

    /** 宿主音频端点：写好的表路径 + 给 crosvm 的 output_device_config 片段。 */
    data class SndInfo(val tablePath: String, val outputConfig: String)

    /**
     * 把宿主当前的音频输出端点写成 crosvm 的 `device_table`（格式同 DroidVM 的
     * HostAudioTable）：
     *
     *     <id>\t<in|out>\t<name>[\t<rate>\t<channels>\t<kind>]
     *
     * AAudio 的设备 id 每次开机都会变，名字不会，所以 crosvm 每次开流都按名字回来
     * 查当前 id。名字**不能带 `|`**：`|` 是 crosvm 命令行解析器认的分隔符，
     * 带 `|` 就必须整体加引号，而这条命令是要经过 `sh -c` 的，引号会被吃掉。
     * 表里的名字和命令行给的名字必须一字不差，所以这里统一用 `TYPE` 或 `TYPE;addr`。
     *
     * 返回的表里第 0 行是「跟随平台默认路由」，其余是宿主真实端点；crosvm 侧的
     * output_device_config 指定用哪个名字（找不到就自动退回流平台路由）。
     */
    fun writeSndTable(ctx: Context): SndInfo? {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val f = File(privateDir(ctx), "snd_devices.tsv")
            val sb = StringBuilder()
            sb.append("0\tout\tDEFAULT\t0\t0\t1\n")
            data class Item(val id: Int, val name: String, val rate: Int, val ch: Int, val kind: Int)
            val items = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { d ->
                val rate = d.sampleRates.firstOrNull { it in 8000..192000 } ?: 48000
                val ch = d.channelCounts.firstOrNull() ?: 2
                val kind = when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 2
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
                    AudioDeviceInfo.TYPE_USB_HEADSET -> 3
                    AudioDeviceInfo.TYPE_USB_DEVICE -> 4
                    AudioDeviceInfo.TYPE_HDMI -> 5
                    else -> 1
                }
                val addr = d.address?.trim().orEmpty()
                val name = if (addr.isEmpty()) audioTypeName(d.type)
                else "${audioTypeName(d.type)};${addr.filter { it.isLetterOrDigit() || it == '.' }}"
                Item(d.id, name, rate, ch, kind)
            }
            for (i in items) sb.append("${i.id}\tout\t${i.name}\t${i.rate}\t${i.ch}\t${i.kind}\n")
            f.writeText(sb.toString())
            // 优先内建扬声器：没有扬声器（蓝牙/耳机）就用第一个输出端点
            val pick = items.firstOrNull { it.name.startsWith("BUILTIN_SPEAKER") } ?: items.firstOrNull()
            // 注意双层方括号：外层是 Vec，内层是一个 PCM 设备的结构体，少一层 crosvm 直接
            // 报 "expected ','" 并把整个 --virtio-snd 丢掉。
            val cfgArg = pick?.let { "output_device_config=[[host_device=${it.name}]]" } ?: ""
            Log.i(TAG, "device_table: ${items.size + 1} 行 → ${f.absolutePath}；选用 ${pick?.name ?: "平台默认"}")
            SndInfo(f.absolutePath, cfgArg)
        } catch (e: Throwable) {
            Log.w(TAG, "生成 device_table 失败（crosvm 会退回平台默认路由）: ${e.message}")
            null
        }
    }

    /** AudioDeviceInfo.type → 稳定名字（不用数字，日志里一眼能认出来）。 */
    private fun audioTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        else -> "TYPE_$type"
    }

    /**
     * 生成启动脚本（基于参考 启动iOS.sh，所有参数均由配置注入）。
     * 返回写入的脚本文件；若未检测到 crosvm，返回 null。
     */
    fun buildBootScript(ctx: Context, cfg: Config): File? {
        ensureDirs(ctx)
        // 无条件投放内置 crosvm：它同时编了 PCI 与 MMIO 两种传输，
        // PCI 模式不再依赖工具链 zip 里那份（来源随发行版变，版本不可控）。
        // 投放失败不致命，脚本会回退到工具链版本并在 boot.log 里说明。
        ensureCrosvmBin(ctx)
        if (cfg.network) { ensureDhcpBin(ctx); ensureTapTool(ctx) }
        // VNC 平板模式（单路多点触摸）：crosvm 是 socket 的 connect 方，touchd 必须先
        // listen，时序错了 crosvm 会 "Connection refused" 直接退出。helper 无条件投放
        // （原生显示后端不用它，但保留投放不影响，且 VNC/回退时随时要用）。
        // v2.0.10：原生显示(android)的 4 路输入已改由主程序 root 服务托管 socket + AIDL
        // 直写，不再经过 touchd；NativeInputBridge 也改为走 AIDL，与 VNC 平板模式互斥。
        ensureTouchdBin(ctx)
        val nativeDisplay = cfg.displayBackend == "android"
        if (!nativeDisplay && cfg.touchPassthrough && cfg.inputDev.isBlank()) {
            val (w, h) = parseResolution(cfg.resolution)
            TouchBridge.start(File(privateDir(ctx), "touch.sock").absolutePath, w, h)
        } else {
            TouchBridge.stop()
        }
        val sndInfo = if (cfg.audio) writeSndTable(ctx) else null
        val dvm = detectBinDir(ctx) ?: return null
        val hostRes = hostResolution(ctx)
        val script = generateBootShell(cfg, dvm, privateDir(ctx).absolutePath,
            ctx.applicationInfo.uid, sndInfo, hostRes)
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

    /** 取宿主屏幕物理尺寸（含状态栏 / 摄像头挖孔区），偶数对齐，供原生显示 1:1 铺满。 */
    private fun hostResolution(ctx: Context): Pair<Int, Int> {
        return try {
            val (w, h) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val b = wm.maximumWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val dm = ctx.resources.displayMetrics
                dm.widthPixels to dm.heightPixels
            }
            val ew = (w / 2 * 2).coerceIn(320, 4096)
            val eh = (h / 2 * 2).coerceIn(320, 4096)
            ew to eh
        } catch (_: Exception) {
            720 to 1584
        }
    }

    /**
     * 把宿主物理分辨率折算成 guest 逻辑分辨率，解决「高分屏下桌面字体极小」。
     * 宽高比保持不变（Surface 仍铺满全屏、无黑边），只降低 guest 帧缓冲像素数，
     * 由 crosvm 等比放大到 Surface —— 固定像素的字体 / 图标 / 光标同步放大。
     * 目标 guest 宽约 800 逻辑像素：1440 宽 → 0.5 档 → 720×1584（项目长期默认档）。
     * 触摸坐标由 native.res 同步换算，缩放后依然 1:1 对应。
     */
    private fun logicalGuestRes(host: Pair<Int, Int>): Pair<Int, Int> {
        val (hw, hh) = host
        if (hw <= 0 || hh <= 0) return 720 to 1584
        // 0.5 步进，下限 1（不放大，小屏手机保持原生清晰度），上限 2.5（避免过度模糊）
        val scale = (Math.round((hw / 800f).coerceIn(1f, 2.5f) * 2f) / 2f).coerceAtLeast(1f)
        val w = ((hw / scale).toInt() / 2 * 2).coerceIn(320, 1920)
        val h = ((hh / scale).toInt() / 2 * 2).coerceIn(320, 4096)
        return w to h
    }

    /** 解析 "720x1584" / "720×1584" / "720*1584"，非法值回落默认。 */
    private fun parseResolution(res0: String): Pair<Int, Int> {
        val res = res0.trim().lowercase().replace("×", "x").replace("*", "x")
        val w = res.substringBefore("x").toIntOrNull()?.coerceIn(320, 4096) ?: 720
        val h = res.substringAfter("x", "1584").toIntOrNull()?.coerceIn(320, 4096) ?: 1584
        return w to h
    }

    private fun sh(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")

    private fun generateBootShell(
        cfg: Config,
        dvm: String,
        privPath: String,
        appUid: Int,
        snd: SndInfo? = null,
        hostRes: Pair<Int, Int> = 720 to 1584,
    ): String {
        // 「适应宿主」= 宽高比与宿主一致、Surface 铺满无黑边，而不是照搬宿主物理像素。
        // 手机典型 1440×3168 / 560dpi，guest 若同样按 1440×3168 渲染，桌面按固定
        // 像素字号画的 UI 在这么密的屏幕上会小到看不清（真机反馈「字体很小」）。
        // 这里折算成逻辑分辨率，crosvm 把较小的帧缓冲等比放大到全屏，字体/图标随之放大。
        val autoRes = cfg.displayBackend == "android" || cfg.resolution.equals("auto", true)
        val (rw, rh) = if (autoRes) logicalGuestRes(hostRes) else parseResolution(cfg.resolution)
        // simplefb: EDK2/UEFI 直接把像素写进这片 framebuffer，VNC 抓 screen=simplefb 才有画面
        val simplefbArg = "width=$rw,height=$rh,format=a8r8g8b8,poll-hz=60"
        // 平板模式：crosvm 从这个 unix socket 读 virtio_input_event（8 字节/条）。
        // crosvm 是 connect 方，所以 socket 必须由 APP 先监听起来（见 buildBootScript）。
        val touchSock = "$privPath/touch.sock"
        val touchArg = sh("multi-touch[path=$touchSock,width=$rw,height=$rh]")
        val touchOn = if (cfg.displayBackend != "android" && cfg.touchPassthrough && cfg.inputDev.isBlank()) 1 else 0
        // GPU: 按显示模式生成；custom 用用户手填的 gpuArg
        val gpuDefault = "2d,vulkan=false,displays=[[mode=windowed[$rw,$rh],refresh-rate=60,dpi=[160,160]]]"
        val gpuArgRaw = when (cfg.displayMode) {
            "gpu2d" -> "2d,vulkan=false,displays=[[mode=windowed[$rw,$rh],refresh-rate=60,dpi=[160,160]]]"
            "gpu3d" -> "3d,vulkan=true,displays=[[mode=windowed[$rw,$rh],refresh-rate=60,dpi=[160,160]]]"
            "custom" -> cfg.gpuArg.ifBlank { gpuDefault }
            else -> gpuDefault
        }
        val gpuArg = sh(gpuArgRaw)
        // 只有明确选了 GPU 模式才把 gpu 放在组合链前面；simplefb 模式全程 simplefb
        val preferGpu = cfg.gpu && (cfg.displayMode == "gpu2d" || cfg.displayMode == "gpu3d" || cfg.displayMode == "custom")
        // 盘位挂载标志在「生成期」就定好，绝不依赖手机 shell 的 od/cmp/head 是否齐全：
        // qcow2 一律可写打开 —— crosvm 打开 qcow2 会校验 refcount 表，镜像带 dirty 位 /
        // lazy_refcounts / 首 cluster refcount=0 时会重建并回写 header，只读 fd 写不了，
        // 直接 EBADF(9)：failure in qcow: failed to write header: Bad file descriptor。
        val bootQcow = cfg.bootDisk.isNotBlank() && imageFormat(cfg.bootDisk) == "QCOW2"
        val cdQcow = cfg.cdRom.isNotBlank() && imageFormat(cfg.cdRom) == "QCOW2"
        val sysQcow = cfg.systemDisk.isNotBlank() && imageFormat(cfg.systemDisk) == "QCOW2"
        val bootFlag = if (cfg.bootDiskRo && !bootQcow) ",ro=true" else ""
        val cdFlag = if (!cdQcow) ",ro=true" else ""
        val sysFlag = if (cfg.systemDiskRo && !sysQcow) ",ro=true" else ""
        // "raw 只读 / qcow2 可写" 直接写进日志，出问题一眼能看出来挂的是哪种
        fun modeText(qcow: Boolean, ro: Boolean) =
            if (qcow) "qcow2 → 可写(强制)" else if (ro) "raw → 只读" else "raw → 可写"
        val vncCap = if (cfg.vncCap.isBlank()) "" else ",transport-cap=${sh(cfg.vncCap)}"
        // 保护模式 × 显示方式 组合链。
        // 对齐参考脚本启动iOS.sh / GunyahiOS unixnu-boot.sh 的 PLANS 监督重试机制。
        // 第 4 段 = 声音（1 带 --virtio-snd / 0 不带），第 5 段 = 网络（1 带 --net / 0 不带）。
        // --virtio-snd 要在宿主开 AAudio 流，--net 要打开宿主 tap；任一失败 crosvm 都会整个
        // 退出（不是静音/断网，是起不来）。所以第一组「网+声」、第二组「只网」、第三组「都不带」，
        // 保证最差也能开机进桌面。
        val plans = when (cfg.prot.ifBlank { "auto" }) {
            "pseudo" -> if (preferGpu)
                "pseudo:gpu:0:1:1 pseudo:gpu:0:0:1 pseudo:gpu:0:0:0 pseudo:simplefb:0:0:1"
            else "pseudo:simplefb:0:1:1 pseudo:simplefb:0:0:1 pseudo:simplefb:0:0:0"
            "without" -> if (preferGpu)
                "without:gpu:0:1:1 without:gpu:0:0:1 without:gpu:0:0:0 without:simplefb:0:0:1"
            else "without:simplefb:0:1:1 without:simplefb:0:0:1 without:simplefb:0:0:0"
            "none" -> if (preferGpu)
                "none:gpu:0:1:1 none:gpu:0:0:1 none:gpu:0:0:0 none:simplefb:0:0:1"
            else "none:simplefb:0:1:1 none:simplefb:0:0:1 none:simplefb:0:0:0"
            "gpu" -> "without:gpu:1:0:1 without:gpu:0:1:1 without:gpu:0:0:1 without:gpu:0:0:0 pseudo:gpu:0:0:1"
            // without(保护) 下 host 无法访问 lent 内存 → 磁盘/输入全废（假成功），
            // 因此 auto 链把 none(非保护) 放在 without 之前，避免白等一轮。
            else -> if (preferGpu)
                "pseudo:gpu:0:1:1 pseudo:gpu:0:0:1 pseudo:gpu:0:0:0 pseudo:simplefb:0:0:1 none:gpu:0:0:1 none:simplefb:0:0:1 without:simplefb:0:0:1"
            else "pseudo:simplefb:0:1:1 pseudo:simplefb:0:0:1 pseudo:simplefb:0:0:0 none:simplefb:0:0:1 without:simplefb:0:0:1"
        }
        // 模板规则：
        //  - 赋值行直接写变量名（VAR=...），绝不能带 @D 前缀（否则生成 $VAR= 非法赋值）；
        //  - 引用变量写 @DVAR（替换成 $VAR），带默认值写 @D{VAR:-x}；
        //  - Kotlin 插值只用 ${sh(...)} / ${cfg.xxx} 注入配置值。
        val t = """#!/system/bin/sh
# 由 GunyaniOS (UbuntuDSU gunyahVM) 自动生成 —— 参数全部由 APP 配置注入
# 生成时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}
# 启动链: Gunyah RM → crosvm(gunyah) → edk2 → UniXNU.efi → XNU(iOS)

set -u
# 顶层兜底: set -u 下引用一个从未赋值的变量会让整个脚本**立刻退出**。
# 最典型的是 build_cmd 里算出来的 SND/NET/SLIM —— 它在 $(...) 子 shell 里执行,
# 赋值传不回来, 于是 try_start 里一句 [ "@DSND" = "1" ] 就能把脚本打死。
# 脚本一死 EXIT trap 会拆 bridge/tap, 而 crosvm 还活着 -> tap fd 变 EBADFD(77),
# guest 从此一个包都发不出去（正是「网卡在、IP 拿不到」的真因）。
: "@D{SND:=0}" "@D{NET:=0}" "@D{SLIM:=0}" "@D{NETON:=0}" "@D{CUR_NET:=0}" "@D{UPDEV:=}" "@D{UPGW:=}"
: "@D{DHCP_PID:=}" "@D{SND_TABLE:=}" "@D{VNC_PORT:=5905}"
: "@D{WAITED:=0}" "@D{TAP_DONE:=0}" "@D{_hold:=0}" "@D{PID:=}" "@D{MARK:=0}" "@D{IDX:=0}" "@D{TOTAL:=1}" "@D{LABEL:=默认}"
PRINT=0
[ "@D{1:-}" = "--print" ] && PRINT=1

# ---------- 0. 环境校验: 必须在安卓宿主侧 (bionic) ----------
if [ ! -d /data ] && [ ! -d /system ]; then
    echo "[!!] 当前环境不是 Android 宿主 (看不到 /data /system), crosvm 是 bionic 程序无法运行"
    exit 1
fi

DVM="${sh(dvm)}"
PRIV="${sh(privPath)}"
WORK="@DDVM/../work"
BOOTORDER="${sh(cfg.bootOrder.ifBlank { "auto" })}"
# LEND 内存的 mTHP 准备策略，主流程里会按 SoC 重新判定后再覆盖
MTHP_MODE="${sh(if (cfg.mthpMode == "single" || cfg.mthpMode == "chunked") cfg.mthpMode else "chunked")}"
# 上一轮的端口记录作废: 否则 5905 被占用顺延后, APP 会一直拿旧端口
rm -f "@DWORK/vnc.port" 2>/dev/null
# 本脚本的 stdout 由 APP 重定向到 boot.log; 这里记录同一路径, 供「shim 是否已死」检测
BOOTLOG="${sh(File(privPath, "boot.log").absolutePath)}"
# 运行状态文件: starting / running / failed|<原因> / stopped / unknown
STATE="${sh(File(privPath, "vm.state").absolutePath)}"

# ---------- 0.4 单实例锁: 禁止两份启动脚本并存 ----------
# 一个脚本会依次尝试 4~6 组「保护/显示/声音/网络」组合, 每组失败都会 pkill -9 crosvm。
# 如果同时跑两份 (重复点启动 / 上一次没退干净), 两份会不停杀对方的 crosvm:
# 表现就是「虚拟机反复启动」+ VNC 端口开开关关, 连上去只会是 Connection refused。
LOCKDIR="@DPRIV/vm.lock"
if ! mkdir "@DLOCKDIR" 2>/dev/null; then
    _lp=@D(cat "@DLOCKDIR/pid" 2>/dev/null)
    # 只认「这个 pid 确实是本脚本」的锁：Android 上 pid 复用很快，
    # 只看 kill -0 会把一个无关进程当成「上一次启动还在跑」，导致永远启动不了。
    _lok=0
    if [ -n "@D_lp" ] && kill -0 "@D_lp" 2>/dev/null \
        && tr -d '\0' < "/proc/@D_lp/cmdline" 2>/dev/null | grep -q "unixnu-boot"; then
        _lok=1
    fi
    if [ "@D_lok" = "1" ]; then
        echo "[!] 已有一个启动流程在跑 (pid=@D_lp), 本次启动忽略 (避免两份脚本互相杀 crosvm)"
        exit 0
    fi
    # 陈旧锁 (上次被 kill -9 / 断电留下): 清掉再抢
    rm -rf "@DLOCKDIR" 2>/dev/null
    mkdir "@DLOCKDIR" 2>/dev/null || { echo "[!] 抢启动锁失败, 本次忽略"; exit 0; }
fi
echo @D@D > "@DLOCKDIR/pid"
cleanup_lock() { rm -rf "@DLOCKDIR" 2>/dev/null; }
trap 'cleanup_lock' EXIT INT TERM

# 状态格式: starting:<第几组>/<总组数>:<组标签> ; running ; failed|<原因> ; stopped
# 界面靠这个显示「启动中 2/5」, 而不是在 pidof 的 true/false 之间来回跳。
echo "starting:0/0:初始化" > "@DSTATE" 2>/dev/null

# 提前失败: 写入状态文件并退出 (APP 据此显示「启动失败 + 原因」)
fail() {
    echo "failed|@D1" > "@DSTATE" 2>/dev/null
    echo "[!!] @D1"
    exit 1
}

# DVM 兜底：探测目录失效时在私有目录里重新找 crosvm 所在目录
if [ -z "@DDVM" ] || [ ! -d "@DDVM" ]; then
    DVM=$(dirname "$(find "@DPRIV" -name crosvm -type f 2>/dev/null | head -1)" 2>/dev/null)
fi
[ -n "@DDVM" ] && [ -d "@DDVM" ] || fail "找不到工具链目录（先在「工具链」页下载）: @DDVM"

# ---------- 1. 定位 crosvm ----------
# 内置的 crosvm（assets/crosvm-mmio）同一份二进制同时编了 PCI 与 MMIO 两种传输：
#   PCI  → 默认行为，不加任何 --virtio-mmio-*（Windows / Debian / 常规 Linux）
#   MMIO → 加 --virtio-mmio-block / -gpu / -input / -net（Darwin / XNU，guest 没有 PCI 宿主驱动）
# 所以两种模式都优先用内置这份，工具链里那份只在内置缺失时兜底。
MMIO=${if (cfg.mmio) 1 else 0}
MMIO_SND=${if (cfg.mmioSound) 1 else 0}
PCI_TOOLCHAIN=${if (cfg.pciUseToolchainCrosvm) 1 else 0}
ANDROID_DISP=${if (cfg.displayBackend == "android") 1 else 0}
CROSVM=""
# v1.9.5 网络回归修复：之前 v34 起 PCI 模式也强制用 APP 内置自编 crosvm-mmio，
# 那份是 DroidVM fork（带 virtio-mmio 扩展），tap/virtio-net 行为与 AOSP 原版有差异，
# 用户反馈「换成我们自己的 crosvm 后网络不正常」。现在 PCI 默认回到工具链自带
# 的 AOSP crosvm（UBC 包），也就是之前网络正常的那份；它同时带
# --android-display-service 原生显示后端，v2.0 起由主程序内置显示器直接使用。
# MMIO（Darwin/XNU, 即 iOS 启动盘）没有 PCI 宿主驱动，仍必须用内置自编版。
if [ "@DMMIO" = "0" ] && [ -x "@DDVM/crosvm" ] && { [ "@DPCI_TOOLCHAIN" = "1" ] || [ "@DANDROID_DISP" = "1" ]; }; then
    CROSVM="@DDVM/crosvm"
    echo "[*] PCI 模式: 使用工具链 crosvm (AOSP 版, 网络栈正常)" >&2
elif [ -x "@DPRIV/crosvm-mmio" ]; then
    CROSVM="@DPRIV/crosvm-mmio"
elif [ -x "@DDVM/crosvm-mmio" ]; then
    CROSVM="@DDVM/crosvm-mmio"
else
    echo "[!] 内置 crosvm 不可用（assets 投放失败），回退到工具链版本" >&2
    CROSVM="@DDVM/crosvm"
    if [ "@DMMIO" = "1" ]; then
        echo "[!] 工具链 crosvm 不支持 --virtio-mmio-*, MMIO 引导无法进行 → 降级为 PCI" >&2
        echo "    Darwin/XNU 会因为没有块设备而卡住; 请重装 APK 或检查 assets/crosvm-mmio" >&2
        MMIO=0
    fi
fi
[ -x "@DCROSVM" ] || CROSVM=$(find "@DDVM" -name crosvm -type f 2>/dev/null | head -1)
[ -n "@DCROSVM" ] && [ -x "@DCROSVM" ] || fail "找不到可执行的 crosvm（工具链未下载或解压不完整）"
if [ "@DMMIO" = "1" ]; then
    echo "[*] 引导模式: MMIO (Darwin/XNU) → @D(basename "@DCROSVM") @D(md5sum "@DCROSVM" 2>/dev/null | cut -c1-8)" >&2
else
    echo "[*] 引导模式: PCI (Windows/Linux/Debian) → @D(basename "@DCROSVM") @D(md5sum "@DCROSVM" 2>/dev/null | cut -c1-8)" >&2
fi

# ---------- 2. 定位 EDK2 固件 (bin/edk2-gunyah.fd + bin/edk2-gunyah.vars.fd) ----------
BIOS="@DDVM/edk2-gunyah.fd"
[ -f "@DBIOS" ] || BIOS=$(find "@DPRIV" -name 'edk2-gunyah.fd' 2>/dev/null | head -1)
[ -f "@DBIOS" ] || BIOS=$(find "@DPRIV" -name 'edk2*.fd' 2>/dev/null | grep -v vars | head -1)
[ -f "@DBIOS" ] || fail "找不到 UEFI 固件: 请把 edk2-*.fd 放进工具链, 或在启动配置里指定自定义 .fd"

VARSFD="@DDVM/edk2-gunyah.vars.fd"
[ -f "@DVARSFD" ] || VARSFD=$(find "@DPRIV" -name 'edk2-gunyah.vars.fd' 2>/dev/null | head -1)

# ---------- 2.3 内核模块: 只检查，不加载 ----------
# 【绝不在启动脚本里 insmod】
#   不同 KMI 的 .ko（android12-5.10 / android14-6.1 / android16-6.12 …）是给别的内核编的，
#   硬 insmod 进去会直接内核 oops/panic —— 表现就是手机蓝屏重启。
#   模块改由 APP 开机自启时加载，且只加载 uname -r 对应的那一个 KMI 目录。
KMI=$(uname -r | sed -n 's/^\([0-9][0-9]*\.[0-9][0-9]*\).*\(android[0-9][0-9]*\).*/\2-\1/p')
if [ -e /dev/gunyah ]; then
    echo "[✓] /dev/gunyah 已就绪（本机内核模块已由 APP 自动加载）"
else
    echo "[!] /dev/gunyah 不存在：Gunyah 依赖模块还没加载"
    echo "    处理: 回主界面重开一次 APP（会自动补本机 @DKMI 目录下的模块），或到「内核模块」页点加载"
fi
# 大页模块只检查：已加载 = 2MB 大页池已由 Magisk 模块在开机时预留，无需 APP 再做任何事；
# 没加载就提示去「工具链」装模块并重启，绝不在启动脚本里 insmod（错 KMI 会蓝屏）。
# v2.0.23：把检测结果存成 HP_LOADED，供 2.4 节判断要不要沿用旧的 2029 保底。
HP_LOADED=0
if ls -d /sys/module/*hugepage* >/dev/null 2>&1; then
    HP_LOADED=1
    echo "[✓] 运行内存适配模块: 已加载（2MB 大页池已就位，由 Magisk 模块接管）"
else
    echo "[!] 运行内存适配模块未加载：在「工具链」页安装大页内存模块并重启手机后生效"
fi

# ---------- 2.4 宿主内存体检 ----------
# 大页池只能在「系统还有空闲物理内存」时采出来。手机用久了后台吃满，acquire 扫到
# system_reserve 下限也凑不够 → pool_avail < pool_want → MEM_ACCEPT err=0x9。
# 所以先看 MemAvailable：不够就回收缓存 + 压缩内存；仍不够再按可用额度降本次 VM 内存。
# 以「启动配置里填的 MEM」为上限动态适配，不再写死任何固定值。
MEMFIT=${if (cfg.memAutoFit) 1 else 0}
VMEM=${cfg.memMB}
# v2.0.23：取消「>2048 无条件压到 2029」的旧钳位。
# 旧钳位来自「没有大页池模块、靠 MADV_COLLAPSE 现采 2MB 大页」的时期：4GB 要 ~2045 页，
# 这台机一次采不满 → SHARE-BLOB OOM 12，于是写死 2029 保底。代价是 Win11 只拿到 2GB，
# boot.wim 展开就要 2.5~3GB，直接蓝屏（v32 归档「内存 2029 MB 是蓝屏的直接推手」）。
# 现在大页池由 gh_hugepage_reserve 在开机时预留，>2048MB 可以正常借出；
# 只有「模块确实没加载」时才沿用 2029 保底，避免退回 OOM 12。
if [ "@DHP_LOADED" != "1" ] && [ "@DVMEM" -gt 2048 ] 2>/dev/null; then
    echo "[!] 未检测到大页池模块：沿用 2029 MB 保底（>2GB 会在 SHARE-BLOB 阶段 OOM 12）" >&2
    echo "    要 4GB+ 内存：到「工具链」页安装大页内存模块并重启手机" >&2
    VMEM=2029
fi

# 不依赖 awk：部分 ROM 的 toybox 没编 awk，旧写法会返回空 → 宿主内存检测整段失效，
# 于是「按可用内存自动适配」根本不触发，用户填 7G 就真给 7G。改走 sed 取 kB + shell 算术。
mem_avail() { _kb=$(sed -n 's/^MemAvailable:[[:space:]]*\([0-9][0-9]*\)[[:space:]]*kB.*/\1/p' /proc/meminfo 2>/dev/null | head -n1); [ -n "@D_kb" ] && echo @D((_kb / 1024)); }
mem_total() { _kb=$(sed -n 's/^MemTotal:[[:space:]]*\([0-9][0-9]*\)[[:space:]]*kB.*/\1/p' /proc/meminfo 2>/dev/null | head -n1); [ -n "@D_kb" ] && echo @D((_kb / 1024)); }
MT=$(mem_total); MA=$(mem_avail)
[ -n "@DMT" ] && echo "[*] 宿主内存: 可用 @D{MA:-?} MB / 共 @D{MT:-?} MB, 本次 VM 申请 @D{VMEM} MB"
if [ -n "@DMA" ] && [ "@DMA" -lt "@D((VMEM + 768))" ] 2>/dev/null; then
    echo "[!] 可用内存不够（VM @D{VMEM} MB + 768 MB 余量），回收缓存并压缩内存..."
    sync 2>/dev/null
    echo 1 > /proc/sys/vm/drop_caches 2>/dev/null
    echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
    sleep 2
    MA=$(mem_avail)
    echo "[*] 回收后可用: @D{MA:-?} MB"
fi
if [ -n "@DMA" ] && [ "@DMA" -lt "@D((VMEM + 768))" ] 2>/dev/null; then
    if [ "@DMEMFIT" = "1" ]; then
        FIT=@D(( (MA - 512) / 256 * 256 ))
        [ "@DFIT" -gt "@DVMEM" ] 2>/dev/null && FIT="@DVMEM"
        # v2.0.23：目标 >=4G 时，降档下限从 1024 抬到 4096 —— Win11 至少要 4G，
        # 别再出现「宿主稍微一紧就掉回 2G、WinPE 蓝屏」。
        # 若宿主连 4.5G 可用都拿不出，硬撑 4G 只会 MEM_ACCEPT 0x9，
        # 就按实际可用降并明确告警（清后台 / 重启后立刻启动）。
        if [ "@DVMEM" -ge 4096 ] 2>/dev/null && [ "@DFIT" -lt 4096 ] 2>/dev/null; then
            if [ "@DMA" -ge 4608 ] 2>/dev/null; then
                FIT=4096
            else
                echo "[!!] 宿主可用内存仅 @D{MA} MB，撑不住 4GB：本次降到 @D{FIT} MB" >&2
                echo "    Win11 需要 4096MB 以上，建议清后台或重启手机后立刻启动" >&2
            fi
        fi
        [ "@DFIT" -lt 1024 ] 2>/dev/null && FIT=1024
        # MMIO/Darwin 的 ADT 里已经把 DRAM 写死成 2016 MB，VM 内存一旦低于它，
        # 内核会按 ADT 去访问根本不存在的物理页 → data abort（比 0x9 更难查）。
        # 所以 MMIO 模式只允许在 2016 以上浮动，宁可不降也不能降到 ADT 之下。
        if [ "@DMMIO" = "1" ] && [ "@DFIT" -lt 2016 ] 2>/dev/null; then
            echo "[!] MMIO/Darwin: ADT 固定 2016 MB DRAM，VM 内存不能降到 2016 以下；保持 @DVMEM MB 不变"
            FIT="@DVMEM"
        fi
        if [ "@DFIT" -lt "@DVMEM" ] 2>/dev/null; then
            echo "[!] 内存吃紧: 本次 VM 内存 @D{VMEM} → @D{FIT} MB（可在「运行内存适配」里关掉自动降档）"
            VMEM="@DFIT"
        fi
        [ "@DFIT" -lt 1536 ] 2>/dev/null && echo "[!!] 可用内存过低: 建议清掉后台应用后重开本 APP 再启动（VM 内存已降到下限）"
    else
        echo "[!] 内存吃紧且自动降档已关闭: 大概率在 MEM_ACCEPT 报 0x9"
    fi
fi

# ---------- 2.5 运行内存适配（GH-Hugepage-Reserve） ----------
# 大页内存已交由 Magisk 模块在开机时统一预留（见「工具链 → 安装大页池模块」）。
# 模块模式下 APP 只检查、不采集、不降内存、不切 none —— 交给模块池即可，避免重复管理引发冲突。
# 旧版「APP 自行采集 / 降内存 / 切 none」逻辑仅当用户显式开启 hugepageAuto 时保留为兜底。
HP_AUTO=${if (cfg.hugepageAuto) 1 else 0}
# 1 = 大页池填不满，本次直接走 none（仅 legacy hugepageAuto 开启时生效）
HP_SHORT=0
HP_MODE=${cfg.hpAcquireMode}
HP_SYS_MB=${cfg.hpSystemReserveMB}
if [ "@DHP_AUTO" = "1" ]; then
    HPP=$(ls -d /sys/module/*hugepage*/parameters 2>/dev/null | head -n1)
    if [ -z "@DHPP" ]; then
        # 这里绝不 insmod：加载错 KMI 的 .ko 会内核 panic（蓝屏）。模块由 APP 开机自启加载。
        echo "[!] 运行内存适配模块未加载（@DKMI）：本次跳过采集"
        echo "    不是致命错误，但 pseudo 组合大概率因 MEM_ACCEPT 0x9 失败。"
        echo "    处理: 「内核模块」页 → 一键加载（只会加载本机 @DKMI 的那一份）"
    else
        # 采池前先压实。acquire 是按 order-9 向 buddy 要页的：物理内存被切碎成
        # 4KB 时，扫一圈只能凑出零头，池子永远填不满 —— 后面无论把 VM 内存调成
        # 多少，MEM_ACCEPT 都还是 0x9。这一步比「调低内存」有用得多。
        echo "[*] 采池前整理物理内存（放缓存 + 两轮压实）..."
        _r=0
        while [ @D_r -lt 2 ]; do
            sync 2>/dev/null
            echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
            echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
            _r=@D((_r+1))
            sleep 1
        done

        if [ "@DHP_SYS_MB" -gt 0 ] 2>/dev/null && [ -f "@DHPP/system_reserve_mb" ]; then
            echo "@DHP_SYS_MB" > "@DHPP/system_reserve_mb" 2>/dev/null \
                && echo "[✓] 系统保留 @DHP_SYS_MB MB（池上限 = RAM - 该值）"
        fi

        # 上一次 VM 退出后模块要 3~10 秒把页收回池；没收完就开新机 = 池不够 → 0x9
        _n=0
        while [ @D_n -lt 12 ]; do
            _av=$(sed -n 's/.*active_vms=\([0-9]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
            [ "@D_av" = "0" ] && break
            _n=@D((_n+1))
            sleep 1
        done
        [ -n "@D_av" ] && [ "@D_av" != "0" ] && echo "[!] 仍有 @D_av 个 VM 占着大页（上次未正常关机？）"

        _avail=$(sed -n 's/.*pool_avail=\([0-9]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
        _want=$(sed -n 's/.*pool_want=\([0-9]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
        # 模块的 pool_want 要等 VM 真正开始 accept 才登记，启动前它基本恒为 0。
        # 于是「avail < want」永远不成立 → 池子一个页都不采 → shim 拿空池去 MEM_ACCEPT，
        # 4 秒后 err=0x9 放弃。这就是那 4 组 pseudo 组合全部失败的直接原因。
        # 这里自己按本次 VM 内存算出目标页数（1 页 = 2MB），只抬不降。
        _need=@D(( (VMEM + 1) / 2 ))
        if [ -z "@D_want" ] || [ "@D_want" -lt "@D_need" ] 2>/dev/null; then _want=@D_need; fi
        echo "[*] 大页池: avail=@D{_avail:-?} 页 / want=@D{_want:-?} 页（1 页 = 2MB, 本次 VM 需 @D_need 页）"

        if [ -n "@D_avail" ] && [ -n "@D_want" ] && [ "@D_avail" -lt "@D_want" ] 2>/dev/null; then
            _round=0
            while [ @D_round -lt 2 ]; do
                # 采集前先压实物理内存: 碎片化的 4KB 页能不能折成 2MB, 直接决定采不采得够
                echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
                if [ @D_round -gt 0 ]; then
                    echo "[*] 第 2 轮: 回收缓存 + 重新压实后再采集（后台吃内存时这一步最关键）..."
                    sync 2>/dev/null
                    echo 1 > /proc/sys/vm/drop_caches 2>/dev/null
                    sleep 1
                else
                    echo "[*] 池不足，采集大页（mode=@DHP_MODE，最多等 90 秒，此间屏幕可能卡顿）..."
                fi
                echo "@DHP_MODE" > "@DHPP/acquire" 2>/dev/null
                _n=0
                _lim=90
                [ @D_round -gt 0 ] && _lim=45
                while [ @D_n -lt "@D_lim" ]; do
                    _ac=$(sed -n 's/.*acquire_active=\([0-9]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
                    [ "@D_ac" = "0" ] && break
                    _n=@D((_n+1))
                    sleep 1
                done
                _avail=$(sed -n 's/.*pool_avail=\([0-9]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
                _want=$(sed -n 's/.*pool_want=\([0-9]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
                [ -n "@D_avail" ] && [ -n "@D_want" ] && [ "@D_avail" -ge "@D_want" ] 2>/dev/null && break
                _round=@D((_round+1))
            done
            _rsn=$(sed -n 's/.*acquire_stop_reason=\([^ ]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
            echo "[*] 采集结束: avail=@D{_avail:-?} / want=@D{_want:-?} 页 @D{_rsn:+(@D_rsn)}"
            if [ -n "@D_avail" ] && [ -n "@D_want" ] && [ "@D_avail" -lt "@D_want" ] 2>/dev/null; then
                # 多轮普通采集都填不满，说明剩下的可用物理页被在跑的进程攥着。
                # 清掉可回收后台 + 深度回收缓存，再给池子最后一次机会。
                echo "[!] 池只有 @D{_avail:-0} / @D_want 页，清理后台进程后再采一次..."
                am kill-all 2>/dev/null || am kill-all-background 2>/dev/null || true
                sleep 2
                sync 2>/dev/null
                echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
                echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
                sleep 1
                echo "@DHP_MODE" > "@DHPP/acquire" 2>/dev/null
                _n=0
                while [ @D_n -lt 60 ]; do
                    _ac=$(sed -n 's/.*acquire_active=\([0-9]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
                    [ "@D_ac" = "0" ] && break
                    _n=@D((_n+1))
                    sleep 1
                done
                _avail=$(sed -n 's/.*pool_avail=\([0-9]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
                echo "[*] 清理后: avail=@D{_avail:-?} / want=@D_want 页"
            fi

            if [ -n "@D_avail" ] && [ -n "@D_want" ] && [ "@D_avail" -lt "@D_want" ] 2>/dev/null; then
                # 池子就是填不满了。Gunyah 只按 2MB 页接受 LEND，池里有多少才能借多少，
                # 硬拿 4GB 去 MEM_ACCEPT 只会 err=0x9（这就是「启动几秒就退出」）。
                # 两条出路，优先保证「一定能起来」：
                #   ① 池还能撑 2GB 以上：按实际供给降内存，继续走保护模式
                #   ② 连 2GB 都撑不住：不降内存，直接改走 none —— 非保护 VM 不借大页，
                #      对物理连续性没有要求，能正常开机，只是少了内存保护
                _fit=$(( _avail * 2 - 128 ))
                _fit=$(( _fit / 256 * 256 ))
                # 只有池能撑到「用户设定值的 3/4」才值得降档 —— 降太少（比如 4GB
                # 只降到 1.5GB）系统虽然进得去，但跑不动任何东西，不如直接走 none
                # 保住内存。1.2GB 是绝对下限，比这还小的 guest 没意义。
                _thr=$(( VMEM * 3 / 4 ))
                [ "@D_thr" -lt 1280 ] 2>/dev/null && _thr=1280
                if [ "@D_fit" -ge "@D_thr" ] 2>/dev/null && [ "@DMEMFIT" = "1" ] && [ "@D_fit" -lt "@DVMEM" ] 2>/dev/null; then
                    echo "[*] 按大页池实际供给降内存: @D{VMEM} → @D{_fit} MB（池 @D_avail 页 = @D(( _avail * 2 )) MB）"
                    echo "    想要更大内存: 先关后台应用（或重开本 APP 后立刻启动），池子才采得满"
                    VMEM="@D_fit"
                    echo "[✓] 降档后池够用（@D_avail / @D(( (VMEM + 1) / 2 )) 页）"
                else
                    HP_SHORT=1
                    echo "[!!] 大页池只有 @D{_avail:-0} 页（@D(( @D{_avail:-0} * 2 )) MB），撑不起保护模式的内存借出。"
                    echo "     本次直接走 none（非保护 VM，不借大页），内存保持 @DVMEM MB 不变。"
                    echo "     想恢复保护模式: 先重开本 APP 再试；仍不行才重启，开机后立刻启动别先开别的 APP。"
                fi
            else
                echo "[✓] 大页池已就绪"
            fi
        fi
    fi
fi

MEM=@DVMEM
CPUS=${cfg.cpus}
NAME="${sh(cfg.name)}"
PROT="${sh(cfg.prot.ifBlank { "auto" })}"
INPUT_DEV="${sh(cfg.inputDev)}"
TOUCHON=${touchOn}
TOUCH_ARG="${touchArg}"
TOUCH_SOCK="${touchSock}"
# v2.0.12 原生显示的 4 通道输入总开关 —— 输入是刚需，绝不因偶发启动失败降级。
# 旧版一旦启动失败就把它置 0 去掉 --input 重试，VM 里从此没有 virtio 输入设备，
# 正是连续多版「能显示不能操作」的真正根因（boot.log 里第二条 CMD 无 --input）。
ANDROID_INPUT=1
# 启动失败时允许「原样重试同一组」一次，专治 Gunyah MEM_ACCEPT 0x9 偶发失败。
RETRY_SAME_ONCE=0
GPU=${if (cfg.gpu) 1 else 0}
VNC=${if (cfg.vnc) 1 else 0}
# APP 自己的 uid：crosvm 以 root 跑，但 Android 会静音 uid 0 的 AAudio，
# 所以 --virtio-snd 要用这个 uid 重新起 snd 后端进程去开音频流。
APP_UID=${appUid}
AUDIOON=${if (cfg.audio) 1 else 0}
# 宿主音频端点表（与 DroidVM 的 HostAudioTable 同格式）：
#   <id>\t<in|out>\t<TYPE|address>[\t<rate>\t<channels>\t<kind>]
# crosvm 的 aaudio 后端按这张表把「扬声器」解析成当前 boot 的 AAudio device id。
# 文件为空/不存在时 crosvm 会跟随平台默认路由，所以不是硬依赖。
SND_TABLE="${sh(snd?.tablePath ?: "")}"
# crosvm 侧指定「用哪个宿主端点」; 空 = 跟随平台默认路由
SND_DEVS="${sh(snd?.outputConfig ?: "")}"

# ---------- 3. 解析 C++ 运行时 (libc++.so) ----------
SYM="_ZNSt3__113__hash_memoryEPKvm"
mkdir -p "@DWORK"
LIBCXX_SO=""
has_sym() { grep -aq "@DSYM" "@D1" 2>/dev/null; }
for cand in \
    /apex/com.android.runtime/lib64/bionic/libc++.so \
    /system/lib64/libc++.so \
    $(find "@DDVM" -name 'libc++.so' 2>/dev/null); do
    [ -z "@Dcand" ] && continue
    [ -f "@Dcand" ] || continue
    if has_sym "@Dcand"; then
        cp -f "@Dcand" "@DWORK/libc++.so" 2>/dev/null && { LIBCXX_SO="@DWORK/libc++.so"; break; }
    fi
done
[ -n "@DLIBCXX_SO" ] || {
    echo "[!!] 找不到带 @DSYM 的 libc++.so (已查 APEX / 系统 / 工具链目录)"
    echo "[!!] 请确认工具链已完整解压"
    exit 1
}
echo "[*] 使用 libc++.so: @DLIBCXX_SO"
# APP 自带 crosvm 运行时依赖 (libgfxstream_backend.so / libvirglrenderer.so / libvncserver.so) 在 bin/lib
export LD_LIBRARY_PATH="@DDVM/lib:@D{LD_LIBRARY_PATH:-}"
export LD_PRELOAD="@DLIBCXX_SO"

# ---------- 4. 盘位 (由 APP「镜像映射」页注入; 选了盘位才生效) ----------
# 固定四盘位: 启动盘(只读) / 光盘(只读) / 系统盘(只读) / 数据盘(读写)
#
# /sdcard 与 /storage/emulated/0 都是 FUSE 挂载, 不支持 flock():
#   crosvm 打开磁盘时报 "failed to lock file: Function not implemented (os error 38)"
#   然后 open_disk_file failed 直接退出 (与镜像内容无关, 任何放在 /sdcard 的镜像都会中招)
# 解决: 把同一文件映射成 /data/media/N 真实路径 (ext4/f2fs, 支持 flock), 不需要复制文件
real_path() {
    p="@D1"
    case "@Dp" in
        /sdcard/*)             rp="/data/media/0/@D{p#/sdcard/}" ;;
        /storage/emulated/0/*) rp="/data/media/0/@D{p#/storage/emulated/0/}" ;;
        /storage/emulated/*)   rp="/data/media/@D{p#/storage/emulated/}" ;;
        *)                     rp="@Dp" ;;
    esac
    if [ "@Drp" != "@Dp" ] && [ -f "@Drp" ] && head -c 512 "@Drp" >/dev/null 2>&1; then
        echo "@Drp"
    else
        echo "@Dp"
    fi
}

# ---------- 4.0 自定义 UEFI 固件 (「启动配置」里选定则覆盖工具链自带) ----------
# 支持任意 UEFI 固件 (edk2-qcpeek.fd / uefi-xxx.fd 等), .fd 不是块设备,
# 不加文件锁, 直接读即可; 放在 /sdcard 也做了真实路径映射以避开 FUSE。
FW_CUSTOM="${sh(cfg.firmwarePath)}"
if [ -n "@DFW_CUSTOM" ]; then
    FWR=@D(real_path "@DFW_CUSTOM")
    if [ -f "@DFWR" ]; then
        FWSZ=@D(stat -c %s "@DFWR" 2>/dev/null || echo 0)
        if [ "@DFWSZ" -lt 262144 ] 2>/dev/null; then
            echo "[!!] 自定义固件只有 @DFWSZ 字节, 不像是完整 UEFI 固件 (通常 2MB+), 仍按指定使用"
        fi
        BIOS="@DFWR"
        echo "[*] 使用自定义 UEFI 固件: @DBIOS (@DFWSZ 字节)"
    else
        echo "[!!] 自定义固件不存在: @DFWR"
        echo "[!!] 已回退工具链自带固件: @DBIOS"
    fi
fi
VARS_CUSTOM="${sh(cfg.varsPath)}"
if [ -n "@DVARS_CUSTOM" ]; then
    VFR=@D(real_path "@DVARS_CUSTOM")
    if [ -f "@DVFR" ]; then
        VARSFD="@DVFR"
        echo "[*] 使用自定义 vars.fd: @DVARSFD"
    else
        echo "[!!] 自定义 vars.fd 不存在: @DVFR (回退默认 @DVARSFD)"
    fi
fi
[ -n "@DVARSFD" ] && [ -f "@DVARSFD" ] || echo "[!] 未找到 vars.fd, 将不带 --pflash 启动 (NVRAM 无法持久化)"

PFLASH_FD="@DVARSFD"
# ---------- 4.2 启动顺序：优先光盘/硬盘时需要一份干净的 UEFI 变量存储 ----------
# UEFI 的启动顺序存在 vars.fd 里。只要里面记了一条 Windows Boot Manager，
# 固件开机就直奔它，命令行里的 bootindex 完全不起作用。
# 选了「优先光盘 / 优先硬盘」时，从工具链 zip 里重新抽一份出厂 vars 到 work/ 下
# （不动你自己的 vars.fd），固件就会当成新机器，按 bootindex 重新枚举设备。
    if [ "@DBOOTORDER" != "auto" ]; then
    mkdir -p "@DWORK" 2>/dev/null
    _vz=$(find "@DPRIV" -maxdepth 1 -name '*.zip' 2>/dev/null | head -1)
    _cf="@DWORK/vars-clean.fd"
    rm -f "@D_cf" 2>/dev/null
    if [ -n "@D_vz" ] && command -v unzip >/dev/null 2>&1; then
        rm -rf "@DWORK/vz" 2>/dev/null
        unzip -o -j "@D_vz" '*/edk2-gunyah.vars.fd' -d "@DWORK/vz" >/dev/null 2>&1 \
            && mv -f "@DWORK/vz/edk2-gunyah.vars.fd" "@D_cf" 2>/dev/null
        rm -rf "@DWORK/vz" 2>/dev/null
    fi
    # 兜底：工具链自带的出厂 vars.fd（DVM 目录里的那份，前提是它还没被 Windows 写过启动项）
    if [ ! -f "@D_cf" ] && [ -f "@DVARSFD" ]; then
        cp -f "@DVARSFD" "@D_cf" 2>/dev/null
    fi
    if [ -f "@D_cf" ]; then
        PFLASH_FD="@D_cf"
        echo "[*] 启动顺序=@DBOOTORDER: 已用出厂 NVRAM，固件会重新枚举设备（光盘排在最前）"
    else
        echo "[!] 启动顺序=@DBOOTORDER: 没能准备干净的 NVRAM，沿用现有 vars.fd"
        echo "    若 UEFI 里已经存过 Windows 启动项，它会盖过 bootindex —— 开机时连点 ESC/F2 进 Boot Manager 手动选 DVD"
    fi
fi

BOOT_DISK=@D(real_path "${sh(cfg.bootDisk)}")
# 启动盘位放 ISO 且没设只读的话，guest 一写就把光盘镜像写坏了
BOOT_IS_ISO=${if (cfg.bootDisk.isNotBlank() && isIsoImage(cfg.bootDisk)) 1 else 0}
CD_ROM=@D(real_path "${sh(cfg.cdRom)}")
# 只有真 ISO 才走 SCSI 光驱：qcow2/raw 走 SCSI 会触发 refcount 重建，白烧内存
CD_IS_ISO=${if (cfg.cdRom.isNotBlank() && isIsoImage(cfg.cdRom)) 1 else 0}
SYSTEM_DISK=@D(real_path "${sh(cfg.systemDisk)}")
DATA_DISK=@D(real_path "${sh(cfg.dataDisk)}")
# 系统盘/数据盘位放 ISO 的识别：ISO 一旦被当硬盘可写挂上，guest 随便写一下就废了
SYSTEM_IS_ISO=${if (cfg.systemDisk.isNotBlank() && isIsoImage(cfg.systemDisk)) 1 else 0}
DATA_IS_ISO=${if (cfg.dataDisk.isNotBlank() && isIsoImage(cfg.dataDisk)) 1 else 0}

# ---- 盘位挂载参数 ----
# qcow2 不能和 raw 共用一套参数：crosvm 打开 qcow2 时会校验 refcount 表，
# 只要镜像带 dirty 位 / lazy refcounts / 首个 refcount 为 0，就会重建 refcount 表并
# 回写 header。此时若按 ro=true 以只读方式打开，写 header 直接 EBADF(os error 9)：
#   failure in qcow: failed to write header: Bad file descriptor
# 所以 qcow2 一律可写打开；raw 才按用户设置决定 ro。
is_qcow() {
    [ -n "@D1" ] && [ -f "@D1" ] || return 1
    # 首选 cmp 直接比 magic（toybox 自带，不依赖 od）
    if printf 'QFI\373' | cmp -s -n 4 - "@D1" 2>/dev/null; then return 0; fi
    # 兜底：head + od（部分 ROM 的 toybox 没编 cmp -n）
    _mg=@D(head -c 4 "@D1" 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')
    [ "@D_mg" = "514649fb" ]
}
BOOT_RO=${if (cfg.bootDiskRo) 1 else 0}
SYSTEM_RO=${if (cfg.systemDiskRo) 1 else 0}
BOOT_FLAG="${bootFlag}"
CD_FLAG="${cdFlag}"
SYSTEM_FLAG="${sysFlag}"
BOOT_MODE="${modeText(bootQcow, cfg.bootDiskRo)}"
CD_MODE="${modeText(cdQcow, true)}"
SYSTEM_MODE="${modeText(sysQcow, cfg.systemDiskRo)}"

# 二次保险：Kotlin 侧读不到文件头时（content:// 转换、权限不足），运行时再判一次
if [ -n "@DBOOT_FLAG" ] && is_qcow "@DBOOT_DISK"; then
    BOOT_FLAG=""; BOOT_MODE="qcow2 → 可写(运行时判定)"
    echo "[!] 启动盘运行时检测到 qcow2，已改为可写挂载（只读会 EBADF 9）"
fi
if [ -n "@DSYSTEM_FLAG" ] && is_qcow "@DSYSTEM_DISK"; then
    SYSTEM_FLAG=""; SYSTEM_MODE="qcow2 → 可写(运行时判定)"
    echo "[!] 系统盘运行时检测到 qcow2，已改为可写挂载"
fi
# ISO 不管放在哪个盘位都必须是只读：它是安装镜像，被 guest 写一次就再也装不了系统。
# 用户把 ISO 填进「系统盘」时，界面默认给的是可写，这里兜住。
if [ "@DBOOT_IS_ISO" = "1" ] && [ "@DBOOT_FLAG" != ",ro=true" ]; then
    BOOT_FLAG=",ro=true"; BOOT_MODE="ISO → 只读(强制)"
fi
if [ "@DSYSTEM_IS_ISO" = "1" ] && [ "@DSYSTEM_FLAG" != ",ro=true" ]; then
    SYSTEM_FLAG=",ro=true"; SYSTEM_MODE="ISO → 只读(强制)"
fi
${cfg.extraDisks.mapIndexed { i, d ->
    """EXTRA${i + 1}_PATH=@D(real_path "${sh(d.path)}")
EXTRA${i + 1}_RO=${if (d.ro) 1 else 0}
EXTRA${i + 1}_IS_ISO=${if (d.path.isNotBlank() && isIsoImage(d.path)) 1 else 0}
EXTRA${i + 1}_FLAG="${if (d.ro && imageFormat(d.path) != "QCOW2") ",ro=true" else ""}"
EXTRA${i + 1}_BI=""
[ "@DBOOTORDER" != "auto" ] && EXTRA${i + 1}_BI=",bootindex=${10 + i}"
EXTRA${i + 1}_MODE="${modeText(d.path.isNotBlank() && imageFormat(d.path) == "QCOW2", d.ro)}"
EXTRA${i + 1}_NAME="${sh(d.label)}""""
}.joinToString("\n")}

check_img() {
    # $1=路径 $2=盘位名; 选了且存在→对齐补齐后返回 0; 未选→提示跳过返回 2; 选了但缺失→警告返回 1
    if [ -z "@D1" ]; then
        echo "[*] @D2 未选择, 跳过挂载"
        return 2
    fi
    if [ -f "@D1" ]; then
        sz=@D(stat -c %s "@D1" 2>/dev/null || echo 0)
        if [ "@Dsz" -gt 0 ] && [ @D((sz % 512)) -ne 0 ]; then
            truncate -s %512 "@D1" 2>/dev/null && \
                echo "[*] @D2 @D(basename "@D1") 已补齐到 512 对齐"
        fi
        case "@D1" in
            /data/media/*)
                echo "[*] @D2 已映射到 /data/media 真实路径 (绕过 FUSE 文件锁限制)" ;;
            /sdcard/*|/storage/emulated/*)
                echo "[!!] @D2 仍在 FUSE 路径, 文件锁会失败; 请把镜像移出 /sdcard" ;;
        esac
        return 0
    fi
    echo "[!!] @D2 已选择但文件不存在(待获取): @D1"
    return 1
}

# 盘位预检: 选中即生效
echo "[*] ---- 盘位检测 ----"
check_img "@DBOOT_DISK"   "启动盘"
check_img "@DCD_ROM"      "光盘"
check_img "@DSYSTEM_DISK" "系统盘"
check_img "@DDATA_DISK"   "数据盘"
${cfg.extraDisks.mapIndexed { i, d ->
    """check_img "@DEXTRA${i + 1}_PATH" "硬盘 ${sh(d.label)}""""
}.joinToString("\n")}
if [ -f "@DBOOT_DISK" ] && [ -f "@DSYSTEM_DISK" ]; then
    echo "[*] 启动盘+系统盘已激活 (当前 MEM=@DMEM MB；已按宿主可用内存自动适配，Win11 建议 4096 MB 以上)"
fi

# ---------- 4.6 网络: tap + bridge + 内置 DHCP + NAT ----------
# crosvm 只接受一个「已存在」的 tap 名 (--net tap-name=...)。宿主负责：
#   建 tap → 挂到 bridge → bridge 上配网关 → 内置 DHCP 发地址 → iptables SNAT 出网。
# 任何一步失败都不阻止 VM 启动，只是 guest 里没有网络（日志会写明原因）。
NETON=${if (cfg.network) 1 else 0}
NET_TAP="gunytap0"
NET_BR="gunybr0"
NET_GW="192.168.100.1"
NET_PREFIX=24
NET_DHCP_START="192.168.100.10"
NET_DHCP_END="192.168.100.200"
NET_MAC="52:54:00:12:34:56"
# tap 的 MAC 必须和 guest 的 MAC 不同。
# 相同的话，DHCP 从 tap 发出的 OFFER 源 MAC = guest 自己的 MAC，guest 协议栈
# 会当成「本机发出的回环帧」丢掉 —— 表现就是 DISCOVER 发了、OFFER 收不到。
NET_TAP_MAC="52:54:00:12:34:57"
DHCP_BIN="@DPRIV/gunydhcpd3"
DHCP_PID=""

net_teardown() {
    [ -n "@D{DHCP_PID:-}" ] && kill "@DDHCP_PID" 2>/dev/null
    DHCP_PID=""
    iptables -w 5 -D INPUT -i "@DNET_BR" -p udp --dport 67 -j ACCEPT 2>/dev/null
    iptables -w 5 -D INPUT -i "@DNET_BR" -p udp --dport 68 -j ACCEPT 2>/dev/null
    iptables -w 5 -D INPUT -i "@DNET_TAP" -p udp --dport 67 -j ACCEPT 2>/dev/null
    iptables -w 5 -D INPUT -i "@DNET_TAP" -p udp --dport 68 -j ACCEPT 2>/dev/null
    ip link set "@DNET_TAP" down 2>/dev/null
    ip link del "@DNET_TAP" 2>/dev/null
    ip link set "@DNET_BR" down 2>/dev/null
    ip link del "@DNET_BR" 2>/dev/null
    iptables -w 5 -t nat -D POSTROUTING -s 192.168.100.0/24 ! -o "@DNET_BR" -j MASQUERADE 2>/dev/null
    iptables -w 5 -t nat -D POSTROUTING -s 192.168.100.0/24 -o "@D{UPDEV:-none}" -j MASQUERADE 2>/dev/null
    iptables -w 5 -D FORWARD -i "@DNET_BR" -j ACCEPT 2>/dev/null
    iptables -w 5 -D FORWARD -o "@DNET_BR" -j ACCEPT 2>/dev/null
    # 独立路由表 + 策略规则（移动数据上行时用），必须一起清，否则下次起来 rule 重复
    ip rule del from 192.168.100.0/24 lookup 200 pref 12000 2>/dev/null
    ip rule del from 192.168.100.0/24 lookup 200 2>/dev/null
    # 回程规则：SNAT 之后回包的 src 是公网地址，按 src 匹配不到上面那条，
    # 必须再按目的网段匹配一次，否则回包会落到 Android 默认表里查不到路由 = unreachable
    ip rule del to 192.168.100.0/24 lookup 200 pref 11999 2>/dev/null
    ip rule del to 192.168.100.0/24 lookup 200 2>/dev/null
    ip route flush table 200 2>/dev/null
}

net_setup() {
    command -v ip >/dev/null 2>&1 || { echo "[!] 系统没有 ip 命令, 跳过网络"; return 1; }
    # SELinux：AF_PACKET(SOCK_RAW) 和 iptables 的 nat 表都会受策略限制。
    # 这是真机上「DHCP 一个接口都绑不上」最常见的宿主侧原因，先记下来别让用户猜。
    _se=@D(getenforce 2>/dev/null)
    [ -n "@D{_se:-}" ] && echo "[*] SELinux=@D{_se:-?}"
    if [ "@D{_se:-}" = "Enforcing" ]; then
        echo "[!] SELinux 处于 Enforcing：gunydhcpd 的原始套接字 / iptables NAT 可能被拦"
        echo "    若下面出现「DHCP 一个接口都没绑上」，执行 setenforce 0 再启动一次"
    fi
    # 【关键】上一轮 crosvm 还活着时，绝对不能 teardown。
    # 删掉 tap 后内核会注销 tun 设备，crosvm 手里的 fd 立刻变成 EBADFD(77)，
    # 之后 guest 发出的**每一个包**都写不出去 —— 表现就是「网卡在、MAC 也对，
    # 但 DHCP 永远失败」，而且跟 guest 装的是 Windows 还是 Debian 毫无关系。
    # 脚本重跑（shim 重启 / 二次点启动 / set -u 提前退出后重启）都会走到这里。
    _cv=@D(pidof crosvm 2>/dev/null; pidof crosvm-mmio 2>/dev/null)
    if [ -n "@D_cv" ] && [ -e "/sys/class/net/@DNET_TAP" ]; then
        echo "[*] 检测到运行中的 crosvm (@D_cv) 与既有 tap @DNET_TAP，复用现有网络（不重建）"
        # 旧版本可能把 tap 的 MAC 设成了 guest 的 MAC，复用时纠正回来
        ip link set "@DNET_TAP" address "@DNET_TAP_MAC" 2>/dev/null
        ip link set "@DNET_TAP" up 2>/dev/null
        if [ -e "/sys/class/net/@DNET_TAP/master" ]; then
            echo "[*] tap 仍挂在 bridge: @D(basename "@D(readlink /sys/class/net/@DNET_TAP/master)")"
        elif [ -e "/sys/class/net/@DNET_BR" ]; then
            echo "[*] tap 不在 bridge 里，重新挂回 @DNET_BR"
            ip link set "@DNET_TAP" master "@DNET_BR" 2>/dev/null
        else
            # 没有 bridge：退回 tap 直连，网关直接配在 tap 上
            echo "[*] 无 bridge，保持 tap 直连模式"
            NET_BR="@DNET_TAP"
            ip addr add "@DNET_GW/@DNET_PREFIX" dev "@DNET_TAP" 2>/dev/null
            ip link set "@DNET_TAP" mtu 1400 2>/dev/null
        fi
        if [ -x "@DDHCP_BIN" ] && [ -z "@D(pidof gunydhcpd3 2>/dev/null)" ]; then
            echo "[!] DHCP 进程不在了，重新拉起"
            "@DDHCP_BIN" -a "@DNET_GW/@DNET_PREFIX" \
                -r "@DNET_DHCP_START-@DNET_DHCP_END" -d 223.5.5.5,114.114.114.114 -l 86400 \
                >> "@DWORK/net.log" 2>&1 &
            DHCP_PID=@D!
        fi
        return 0
    fi
    net_teardown
    # bridge 是「可选的交换机层」。Android 的 toybox `ip` 很多版本不支持
    # `link add type bridge`，部分内核也没编 CONFIG_BRIDGE；这一步失败**不能让网络瘫痪**。
    # tap 本身就是二层设备，直接在上面配网关 IP，DHCP / ARP / NAT 全部照常工作。
    _blerr=@D(ip link add name "@DNET_BR" type bridge 2>&1 | head -n1)
    if [ ! -e "/sys/class/net/@DNET_BR" ]; then
        echo "[!] 建 bridge @DNET_BR 失败: @D{_blerr:-未知错误}"
        echo "[!] 降级为 tap 直连模式（不依赖 bridge，功能等价）"
        NET_BR="@DNET_TAP"
    else
        # 关键 1: bridge 默认 forward_delay 是 15 秒，tap 刚挂进去时端口仍在 listening，
        # 这段时间 guest 发的 DHCP 广播过不去 → Windows 一直卡在「正在识别…」。
        # 关掉 STP 并把转发延迟压到 0，tap 一挂上就能转发。
        ip link set "@DNET_BR" type bridge stp_state 0 forward_delay 0 2>/dev/null
        ip link set "@DNET_BR" type bridge multicast_snooping 0 2>/dev/null
        ip addr add "@DNET_GW/@DNET_PREFIX" dev "@DNET_BR" 2>/dev/null
        ip link set "@DNET_BR" up 2>/dev/null
        # MTU 1400：移动数据（rmnet）常见 MTU 就是 1400，bridge 用 1500 会让大包
        # 在 PMTUD 失效时变黑洞（ping 小包通、网页打不开的典型症状）
        ip link set "@DNET_BR" mtu 1400 2>/dev/null
    fi
    # ---- tap 必须是 persistent 的，crosvm 的 --net tap-name= 只认这种 ----
    # 关键 0: 很多 ROM 的 toybox `ip` 不认 `persist` 关键字，命令失败后回退到不带
    # persist 的版本 → 命令一退出 tap 立刻消失 → crosvm 打开时设备已不在，只好
    # 自己重建一个「没挂到 bridge」的 tap。表现就是 guest 有网卡、MAC 也对，
    # 但 DHCP 永远收不到。内置 gunytap 直接走 ioctl（TUNSETPERSIST + SIOCBRADDIF），
    # 一步做完，不依赖 ROM 的 ip 实现。
    TAPTOOL="@DPRIV/gunytap"
    if [ -x "@DTAPTOOL" ]; then
        "@DTAPTOOL" "@DNET_TAP" "@DNET_BR" "@DNET_TAP_MAC" 1400 2>&1 | sed 's/^/    /'
    fi
    if [ ! -e "/sys/class/net/@DNET_TAP" ]; then
        echo "[!] gunytap 未生效，回退到 ip tuntap（persist 可能不被支持）"
        ip tuntap add dev "@DNET_TAP" mode tap persist 2>/dev/null || \
            ip tuntap add dev "@DNET_TAP" mode tap 2>/dev/null || {
            echo "[!] 建 tap @DNET_TAP 失败, 跳过网络"; ip link del "@DNET_BR" 2>/dev/null; return 1; }
        ip link set "@DNET_TAP" master "@DNET_BR" 2>/dev/null
        # tap 的 MAC 和 guest 网卡必须不同：DHCP 的 OFFER 源 MAC 若等于 guest 自己的 MAC，
        # guest 会当成自己的回环帧丢掉，表现为「DISCOVER 发了但拿不到地址」。
        ip link set "@DNET_TAP" address "@DNET_TAP_MAC" 2>/dev/null
        ip link set "@DNET_TAP" mtu 1400 2>/dev/null
    fi
    # 直连模式：没有 bridge，网关地址直接配在 tap 上
    if [ "@DNET_BR" = "@DNET_TAP" ]; then
        ip addr add "@DNET_GW/@DNET_PREFIX" dev "@DNET_TAP" 2>/dev/null
        ip link set "@DNET_TAP" mtu 1400 2>/dev/null
    fi
    # gunytap 自己会尝试 up, 但它只 warn 不重试；这里再显式拉一次，
    # tap 处于 DOWN 时 dev_queue_xmit 会直接 ENETDOWN，DHCP 同样发不出去。
    ip link set "@DNET_TAP" up 2>/dev/null
    sleep 1
    # 诊断快照：只要这几行，就能判断问题在 L2 还是 NAT
    echo "[*] tap @DNET_TAP: state=@D(cat /sys/class/net/@DNET_TAP/operstate 2>/dev/null || echo -) mtu=@D(cat /sys/class/net/@DNET_TAP/mtu 2>/dev/null || echo -)"
    if [ "@DNET_BR" = "@DNET_TAP" ]; then
        echo "[*] 网络模式: tap 直连 (@DNET_TAP @DNET_GW/@DNET_PREFIX, 无 bridge)"
    elif [ -e "/sys/class/net/@DNET_TAP/master" ]; then
        echo "[*] tap 已挂到 bridge: @D(basename "@D(readlink /sys/class/net/@DNET_TAP/master)")"
    else
        echo "[!!] tap 不在任何 bridge 里（guest 一定收不到 DHCP）"
    fi
    echo "[*] bridge @DNET_BR 成员: @D(ls /sys/class/net/@DNET_BR/brif 2>/dev/null | tr '\n' ' ')"
    echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null
    # 关键 3: bridge 上的 DHCP 广播会走 iptables INPUT，Android 的 fw_INPUT/bw_INPUT
    # 可能直接丢掉。显式放行 67/68，否则 DHCP 进程收不到 DISCOVER。
    iptables -w 5 -I INPUT -i "@DNET_BR" -p udp --dport 67 -j ACCEPT 2>/dev/null
    iptables -w 5 -I INPUT -i "@DNET_BR" -p udp --dport 68 -j ACCEPT 2>/dev/null
    iptables -w 5 -I INPUT -i "@DNET_TAP" -p udp --dport 67 -j ACCEPT 2>/dev/null
    iptables -w 5 -I INPUT -i "@DNET_TAP" -p udp --dport 68 -j ACCEPT 2>/dev/null
    if command -v iptables >/dev/null 2>&1; then
        # SNAT 只作用于「离开 bridge」的流量；! -o 避免把 VM 间流量也 NAT
        iptables -w 5 -t nat -C POSTROUTING -s 192.168.100.0/24 ! -o "@DNET_BR" -j MASQUERADE 2>/dev/null || \
            iptables -w 5 -t nat -A POSTROUTING -s 192.168.100.0/24 ! -o "@DNET_BR" -j MASQUERADE 2>/dev/null
        # -I 插到最前：Android 的 fw_FORWARD / oem_fwd 末尾有 DROP 兜底，
        # 用 -A 追加会被那条 DROP 先命中，表现就是「能发不能收」
        iptables -w 5 -C FORWARD -i "@DNET_BR" -j ACCEPT 2>/dev/null || \
            iptables -w 5 -I FORWARD 1 -i "@DNET_BR" -j ACCEPT 2>/dev/null
        iptables -w 5 -C FORWARD -o "@DNET_BR" -j ACCEPT 2>/dev/null || \
            iptables -w 5 -I FORWARD 1 -o "@DNET_BR" -j ACCEPT 2>/dev/null
        # 写没写进去不能靠 2>/dev/null 猜。Android 的 netd 常年占着 xtables 锁，
        # 以前失败时一点痕迹都没有 —— 表现就是 guest 有 IP、能 ping 网关、外网全丢。
        if ! iptables -w 5 -t nat -S POSTROUTING 2>/dev/null | grep -q "192.168.100.0/24"; then
            _ne=@D(iptables -w 5 -t nat -A POSTROUTING -s 192.168.100.0/24 ! -o "@DNET_BR" -j MASQUERADE 2>&1 | head -1)
            echo "[!!] NAT 规则写入失败: @D{_ne:-未知错误} → guest 会有 IP 但上不了网"
        fi
        if ! iptables -w 5 -S FORWARD 2>/dev/null | grep -q "@DNET_BR"; then
            _fe=@D(iptables -w 5 -I FORWARD 1 -i "@DNET_BR" -j ACCEPT 2>&1 | head -1)
            echo "[!!] FORWARD 放行失败: @D{_fe:-未知错误} → 只能发不能收"
        fi
    else
        echo "[!] 系统没有 iptables, NAT 不可用 (guest 只能访问宿主)"
    fi

    # ---- 关键 5: 上行网卡自适应（移动数据 rmnet 必须单独建表） ----
    # Android 的策略路由里，转发包的 fwmark=0，会一路落到 "32000: from all unreachable"。
    # 所以手机用 5G/4G（rmnet_data*）时，不做这一步的表现是：
    # guest 有 IP、能 ping 通网关 192.168.100.1，但 ping 外网 100% 丢包。
    UPDEV=""; UPGW=""
    _r=$(ip route get 223.5.5.5 2>/dev/null | head -n1)
    UPDEV=$(echo "@D_r" | sed -n 's/.* dev \([^ ]*\).*/\1/p')
    UPGW=$(echo "@D_r" | sed -n 's/.* via \([^ ]*\).*/\1/p')
    case "@DUPDEV" in ""|"@DNET_BR"|"@DNET_TAP"|lo|tun*) UPDEV=""; UPGW="" ;; esac
    if [ -z "@DUPDEV" ]; then
        # 回退：main 表没有默认路由时（Android 常见），遍历所有表找第一条能用的
        UPDEV=$(ip route show table all 2>/dev/null | awk '
            /^default/ { d="";
                for (i=1;i<=NF;i++) if (@Di=="dev") d=@D(i+1)
                if (d!="" && d !~ /^(guny|lo$|tun)/) { print d; exit } }')
        UPGW=$(ip route show table all 2>/dev/null | awk '
            /^default/ { d=""; g="";
                for (i=1;i<=NF;i++) { if (@Di=="dev") d=@D(i+1); if (@Di=="via") g=@D(i+1) }
                if (d!="" && d !~ /^(guny|lo$|tun)/) { print (g==""?"-":g); exit } }')
    fi
    if [ -n "@DUPDEV" ]; then
        ip route replace 192.168.100.0/24 dev "@DNET_BR" table 200 2>/dev/null
        if [ -z "@DUPGW" ] || [ "@DUPGW" = "-" ]; then
            ip route replace default dev "@DUPDEV" table 200 2>/dev/null
        else
            # rmnet 是点对点接口，网关有时不带 onlink 会被内核拒绝，逐级回退
            ip route replace default via "@DUPGW" dev "@DUPDEV" table 200 2>/dev/null || \
                ip route replace default via "@DUPGW" dev "@DUPDEV" table 200 onlink 2>/dev/null || \
                ip route replace default dev "@DUPDEV" table 200 2>/dev/null
        fi
        ip rule del from 192.168.100.0/24 lookup 200 pref 12000 2>/dev/null
        ip rule add from 192.168.100.0/24 lookup 200 pref 12000 2>/dev/null
        # 回程：SNAT 后回包的 src 是公网 IP，按 src 匹配不进表 200，必须按目的网段再匹配一次。
        # 少了这条的典型表现：guest 能拿到 IP、能 ping 网关，但 ping 外网 100% 丢包。
        ip rule del to 192.168.100.0/24 lookup 200 pref 11999 2>/dev/null
        ip rule add to 192.168.100.0/24 lookup 200 pref 11999 2>/dev/null
        # 表 200 里也要有到 guest 网段的直连路由（部分 ROM 的 ip route replace 不加这条）
        ip route replace 192.168.100.0/24 dev "@DNET_BR" table 200 2>/dev/null || \
            ip route replace 192.168.100.0/24 dev "@DNET_TAP" table 200 2>/dev/null
        # 明确对上行口也做一次 SNAT（比 ! -o 那条更早命中，避免多网卡时选错出口）
        iptables -w 5 -t nat -C POSTROUTING -s 192.168.100.0/24 -o "@DUPDEV" -j MASQUERADE 2>/dev/null || \
            iptables -w 5 -t nat -I POSTROUTING 1 -s 192.168.100.0/24 -o "@DUPDEV" -j MASQUERADE 2>/dev/null
        if [ -z "@DUPGW" ] || [ "@DUPGW" = "-" ]; then
            echo "[*] 上行: @DUPDEV（点对点/无网关），VM 子网走独立路由表 200"
        else
            echo "[*] 上行: @DUPDEV via @DUPGW，VM 子网走独立路由表 200（绕过 netd 策略路由）"
        fi
    else
        echo "[!] 没探测到上行网卡（手机可能没联网），VM 只能访问宿主"
    fi
    if [ -x "@DDHCP_BIN" ]; then
        # 每次启动清空日志：否则 head -2 读到的是上一轮启动的横幅，误导排错
        : > "@DWORK/net.log" 2>/dev/null
        # 新版 gunydhcpd 会枚举 /sys/class/net 下所有接口，每个接口建一个 AF_PACKET
        # socket 并 bind 到 ifindex，poll 多路复用 —— 不再依赖 -i 指定单个接口。
        # 原因：实测「AF_PACKET 绑 any 收不到 veth/tap 的 RX 帧」，而 tap 被 bridge
        # 接管后帧会从 bridge 设备重新上来，只绑 tap 就收不到 → guest 永远等不到 IP。
        "@DDHCP_BIN" -a "@DNET_GW/@DNET_PREFIX" \
            -r "@DNET_DHCP_START-@DNET_DHCP_END" -d 223.5.5.5,114.114.114.114 -l 86400 \
            >> "@DWORK/net.log" 2>&1 &
        DHCP_PID=@D!
        sleep 1
        if kill -0 "@D{DHCP_PID:-0}" 2>/dev/null; then
            echo "[*] 网络就绪: @DNET_BR @DNET_GW/@DNET_PREFIX, DHCP 已启动 (pid @DDHCP_PID)"
            # 把 DHCP 自报的参数抄进 boot.log，确认 iface / 地址池没写错
            head -3 "@DWORK/net.log" 2>/dev/null | sed 's/^/    /'
            if grep -q "ready, .*iface(s) bound" "@DWORK/net.log" 2>/dev/null; then
                echo "[*] DHCP 已绑定全部接口（tap/bridge 都能收包）"
                grep "watching iface=@DNET" "@DWORK/net.log" 2>/dev/null | sed 's/^/    /'
            elif grep -q "没有任何可用接口" "@DWORK/net.log" 2>/dev/null; then
                echo "[!!] DHCP 一个接口都没绑上（SELinux 限制 AF_PACKET？）"
            else
                echo "[!] DHCP 进程在但日志里看不出绑定结果，看 @DWORK/net.log"
            fi
        else
            echo "[!] DHCP 没起来, guest 可能拿不到 IP; 见 @DWORK/net.log"
            # 三种失败长得一模一样，但处置完全不同，直接在这里分掉
            if grep -qa "usage:" "@DWORK/net.log" 2>/dev/null; then
                echo "[!!] 内置 DHCP 与脚本参数不匹配 = 私有目录里是旧版残留二进制"
                echo "     重装 APK 会自动重投（v37 起文件名带版本，必定覆盖）"
            elif grep -qa "Address already in use" "@DWORK/net.log" 2>/dev/null; then
                echo "[!!] 67 端口被占用：先 pkill -f gunydhcpd 再启动"
            elif grep -qa "Permission denied" "@DWORK/net.log" 2>/dev/null; then
                echo "[!!] 权限不足：SELinux 拦了 AF_PACKET，临时 setenforce 0 后重试"
            fi
            head -5 "@DWORK/net.log" 2>/dev/null | sed 's/^/    /'
        fi
    else
        echo "[!] 内置 DHCP 未投放 (@DDHCP_BIN 不存在/不可执行), guest 需手动配置:"
        echo "    IP @DNET_DHCP_START / 255.255.255.0 / 网关 @DNET_GW / DNS 223.5.5.5"
    fi
    return 0
}

# crosvm 启动后再确认一次 tap 在 bridge 里。
# 有些情况下 crosvm 打开 tap 失败会自己重建一个同名设备，新 tap 不在 bridge 上——
# 表现就是 guest 里网卡、MAC 全对，但 DHCP 永远等不到，Windows 一直「正在识别…」。
ensure_tap_on_bridge() {
    [ "@DNETON" = "1" ] || return 0
    # tap 直连模式没有 bridge，不需要（也不能）挂
    [ "@DNET_BR" = "@DNET_TAP" ] && return 0
    [ -d "/sys/class/net/@DNET_TAP" ] || {
        echo "[!] crosvm 启动后 tap @DNET_TAP 不存在（crosvm 可能没建网卡）"; return 0; }
    if [ ! -e "/sys/class/net/@DNET_TAP/master" ]; then
        echo "[!] crosvm 启动后 tap 不在 bridge 里，重新挂载..."
        ip link set "@DNET_TAP" master "@DNET_BR" 2>/dev/null
        ip link set "@DNET_TAP" up 2>/dev/null
        if [ -e "/sys/class/net/@DNET_TAP/master" ]; then
            echo "[✓] tap 已挂回 @DNET_BR"
        else
            echo "[!!] tap 仍挂不上 @DNET_BR（bridge 被 SELinux 限制？）"
        fi
    fi
    # DHCP 进程要是被杀了也顺手拉起来
    if { [ -z "@D{DHCP_PID:-}" ] || ! kill -0 "@D{DHCP_PID:-0}" 2>/dev/null; } && [ -x "@DDHCP_BIN" ]; then
        echo "[!] DHCP 进程已不在，重新拉起"
        "@DDHCP_BIN" -a "@DNET_GW/@DNET_PREFIX" \
            -r "@DNET_DHCP_START-@DNET_DHCP_END" -d 223.5.5.5,114.114.114.114 -l 86400 \
            >> "@DWORK/net.log" 2>&1 &
        DHCP_PID=@D!
    fi
}

# 脚本退出钩子。
# 关键: 脚本因为任何意外（set -u 命中未定义变量 / 被 SIGTERM）提前退出时,
# 只要 crosvm 还活着, 就**绝不能**拆 bridge/tap —— 内核会注销 tun 设备, crosvm
# 手里的 fd 立刻变 EBADFD(77), 之后每个包都写不出去。表现正是:
#   VM 起来了、网卡在、驱动正常, 但永远拿不到 IP;
#   boot.log 里全是 "failed to write frame to tap: File descriptor in bad state"。
on_exit() {
    set +u
    # 无论 crosvm 是否还活着，这里都**不拆网络**。
    # 原因：脚本退出和 crosvm 退出不是同一件事（shim 重启、二次点启动、set -u 命中
    # 未定义变量都会让脚本先走一步）。一旦在这里删掉 tap，还活着的 crosvm 立刻
    # EBADFD(77)，guest 的 DHCP 就永远失败。真正的清理交给 STOP_SHELL（杀完 crosvm 再拆）。
    _alive=$(pidof crosvm 2>/dev/null; pidof crosvm-mmio 2>/dev/null)
    if [ -n "@D_alive" ]; then
        echo "[!] 启动脚本退出但 crosvm 仍在运行 (pid @D_alive): 保留网络设备 (拆掉会让 guest 彻底断网)"
    else
        echo "[*] 启动脚本退出: 网络设备保留, 下次启动或停止时再清理"
    fi
    cleanup_lock
    return 0
}

# Windows 安装镜像 + 小内存 = WinPE 蓝屏 / 自动重启。
# 2GB 左右能进 UEFI，但 boot.wim 展开后极易 MEMORY_MANAGEMENT 蓝屏，
# 蓝屏会触发 system reset，crosvm 随即退出 —— 表现就是「进 VNC 几秒就退出」。
case "@D(echo "@DCD_ROM @DSYSTEM_DISK" 2>/dev/null | tr 'A-Z' 'a-z')" in
    *win*)
        if [ "@D{MEM:-0}" -lt 4096 ] 2>/dev/null; then
            echo "[!!] 检测到 Windows 镜像，但内存仅 @D{MEM}MB：Win11 安装环境建议 4096MB 以上" >&2
            echo "[!!] 内存不足时 WinPE 容易蓝屏并触发自动重启，看起来就像「VNC 几秒后退出」" >&2
        fi
        ;;
esac

# ---------- 4.6 显示后端能力探测（必须在起输入桥之前） ----------
# 内置 crosvm-mmio（MMIO / iOS 启动盘）没有编 android_display 后端，只有 UBC
# 工具链那份 AOSP crosvm 有。必须先判清楚再决定起「4 路」还是「1 路」输入桥：
# 否则原生显示不支持时回退 VNC，7788 已被 4 路桥占住，VNC 平板触摸桥就起不来，
# 等于又回到「能看不能点」。这里判完直接把 ANDROID_DISP 改回 0，让 4.7 走 VNC 分支。
have_flag() { grep -aq -- "--@D1" "@DCROSVM" 2>/dev/null; }
# v2.0.14：absolute-mouse 是 --input 的【子类型】（help 里形如 absolute-mouse[path=...]，
# 不带 -- 前缀），不能用 have_flag。直接查二进制里有没有这个解析器：没有就回退相对 mouse，
# 绝不让一个不认识的参数把整台 VM 拖垮。
ABS_MOUSE=0
if grep -aq "absolute-mouse" "@DCROSVM" 2>/dev/null; then ABS_MOUSE=1; fi
echo "[*] crosvm 能力: absolute-mouse(usb-tablet)=@DABS_MOUSE" >&2
NATIVE_ACTIVE=0
if [ "@DANDROID_DISP" = "1" ]; then
    if have_flag android-display-service; then
        NATIVE_ACTIVE=1
    else
        echo "[!] 当前 crosvm 未编 android_display 后端 (MMIO/iOS 启动盘常见) -> 自动回退 VNC（保留原有 VNC 输入桥）" >&2
        ANDROID_DISP=0
        VNC=1
        # 回退后行为要和「一开始就选 VNC」完全一致：touchOn 在生成期是按
        # displayBackend != android 判的，选了 android 会被置 0，这里要还原。
        TOUCHON=${if (cfg.touchPassthrough && cfg.inputDev.isBlank()) 1 else 0}
    fi
fi

# ---------- 4.6.1 把「运行时真正生效的后端」告诉 APP ----------
# 4.6 能力探测失败会把 ANDROID_DISP 改回 0（脚本实际走 VNC），但 Java 侧配置
# 仍是 android，自动跳转照样进原生显示页 —— 那里根本没有 crosvm_display 服务，
# 于是黑屏，还要白等 25 秒才兜底。写清楚后 APP 立刻改走 VNC，不再干等黑屏。
mkdir -p "@DPRIV/work" 2>/dev/null
if [ "@DNATIVE_ACTIVE" = "1" ]; then
    echo native > "@DPRIV/work/display.backend" 2>/dev/null
else
    echo vnc > "@DPRIV/work/display.backend" 2>/dev/null
fi

# ---------- 4.7 输入桥（v2.0.10：root 服务托管 socket，去掉 touchd/TCP） ----------
# crosvm 是 unix socket 的 **connect** 方，且只在启动那一刻连一次，
# 所以 listener 必须在启动 crosvm **之前**就 bind 好。
# v2.0.10 架构（对齐已验证可用的 Crosvm-Android 参考实现）：
#   4 路 unix socket 由主程序 root 服务进程(InputSocketHost, uid=0)在
#   GunyahManager.start() 里、exec crosvm 之前就 bind+listen 好（APP 侧同步等待）；
#   APP 通过 AIDL writeInput 把 evdev 字节交给 root 进程**直写 socket fd**。
# 彻底删除 native touchd + APP 直连 127.0.0.1 TCP 这一层：untrusted_app→本地 TCP
# 在真机被 SELinux 拦截，正是连续多版「画面正常但触摸/键盘/鼠标全不能操作」的根因。
# 【铁律】本脚本绝不能 rm 这些 .sock —— 那会删掉 root 服务正在监听的 socket 文件路径，
# 导致 crosvm connect 不上、输入永久失效。只能检查、不能删。
NATIVE_INPUT_OK=0
if [ "@DNATIVE_ACTIVE" = "1" ]; then
    pkill -f "@DPRIV/touchd" 2>/dev/null   # 仅清理旧版残留 touchd（新版已不再启动它）
    # 轮询确认 root 服务已把 4 路 socket 托管好（最多 ~5s）。APP 侧已先同步等待
    # ensureListeningAndWait，正常情况第一次循环即 4/4；这里是顺序双保险。
    _nok=0
    for _i in $(seq 1 10); do
        _nok=0
        for _p in 7788 7789 7790 7791; do [ -S "/data/local/tmp/gyah_input_@D{_p}.sock" ] && _nok=@D((_nok+1)); done
        [ "@D_nok" -eq 4 ] && break
        sleep 0.5
    done
    if [ "@D_nok" -eq 4 ]; then
        NATIVE_INPUT_OK=1
        echo "[*] 内置原生显示: 4 路输入 socket 已由 root 服务托管 (AIDL 直写, 无 touchd/TCP 桥)"
    else
        echo "[!] 输入 socket 只就绪 @D_nok/4 路 (root 服务未连? 见显示器状态栏 服务✓/✗、写入✓/✗)"
    fi
elif [ "@DTOUCHON" = "1" ]; then
    # VNC 平板模式（单路多点触摸），保持原有行为不变。
    pkill -f "@DPRIV/touchd" 2>/dev/null
    _tw=0
    while pgrep -f "@DPRIV/touchd" >/dev/null 2>&1 && [ "@D_tw" -lt 25 ]; do sleep 0.2; _tw=@D((_tw+1)); done
    rm -f "@DTOUCH_SOCK" 2>/dev/null
    : > "@DWORK/touch.log" 2>/dev/null
    "@DPRIV/touchd" 7788 "@DTOUCH_SOCK" >> "@DWORK/touch.log" 2>&1 &
    sleep 1
    if grep -q "touchd: unix=" "@DWORK/touch.log" 2>/dev/null; then
        echo "[*] 触摸桥已就绪: @DTOUCH_SOCK <-> 127.0.0.1:7788"
    else
        TOUCHON=0
        echo "[!] touchd 没起来，本次不启用平板模式（不影响虚拟机启动）; 见 @DWORK/touch.log"
        head -3 "@DWORK/touch.log" 2>/dev/null | sed 's/^/    /'
    fi
fi

if [ "@DNETON" = "1" ]; then
    net_setup || NETON=0
else
    echo "[*] 网络开关已关闭, 跳过虚拟网卡"
fi
# 脚本退出（crosvm 结束 / 被 stop 脚本终止）时拆网络并释放单实例锁
trap 'on_exit' EXIT INT TERM

# ---------- 5. 参数探测 ----------
# have_flag() 已在 4.6 定义（能力探测前置到起输入桥之前）。

# ---------- 5.1 原生显示输入桥就绪校验 + 自动兜底 (v2.0.10) ----------
# 4.6 判过 crosvm 能力、4.7 确认过 root 服务托管的 4 个 unix socket 都在。
# 万一没就绪（root 服务没连上），切回 VNC 并补起 VNC 单路平板触摸桥。
# 注意：绝不能 rm gyah_input_*.sock —— 那是 root 服务持有的 listen socket 路径，
# 删了文件但 fd 还在，root 服务的 ensureListening 幂等会以为「已监听」不再 bind，
# 下次切回原生显示时这 4 路就永久连不上了。让它留着空转即可（VNC 后端不连它们）。
if [ "@DNATIVE_ACTIVE" = "1" ] && [ "@DNATIVE_INPUT_OK" != "1" ]; then
    echo "[!] 内置输入桥未就绪 -> 自动回退 VNC（不会留下『有画面没输入』的死局）" >&2
    NATIVE_ACTIVE=0
    ANDROID_DISP=0
    VNC=1
    NATIVE_INPUT_OK=0
    TOUCHON=${if (cfg.touchPassthrough && cfg.inputDev.isBlank()) 1 else 0}
    if [ "@DTOUCHON" = "1" ]; then
        pkill -f "@DPRIV/touchd" 2>/dev/null
        rm -f "@DTOUCH_SOCK" 2>/dev/null
        "@DPRIV/touchd" 7788 "@DTOUCH_SOCK" >> "@DWORK/touch.log" 2>&1 &
        sleep 1
    fi
elif [ "@DNATIVE_ACTIVE" = "1" ]; then
    echo "[*] 显示后端: 主程序内置原生 Surface (crosvm_display), 4 路输入已就绪" >&2
    # 必须用 Kotlin 插值把宿主分辨率写成字面量：脚本开了 set -u，
    # 若误写成 shell 变量引用，变量未定义会直接把整个启动脚本打死。
    # 目录用 PRIV/work，与 APP 侧 privateDir()/work/native.res 保持一致。
    mkdir -p "@DPRIV/work" 2>/dev/null
    echo "${rw} ${rh}" > "@DPRIV/work/native.res" 2>/dev/null
    # 把本次实际采用的指针设备类型告诉 APP：1=absolute-mouse(发 EV_ABS)，0=相对 mouse。
    echo "@DABS_MOUSE" > "@DPRIV/work/input.abs" 2>/dev/null
fi

# 5.1 可能在「4 路输入桥没起全」时把 NATIVE_ACTIVE 再改回 0（回退 VNC），
# 所以这里用最终值覆盖 4.6.1 写的记录，APP 读到的才是真正生效的后端。
if [ "@DNATIVE_ACTIVE" = "1" ]; then
    echo native > "@DPRIV/work/display.backend" 2>/dev/null
else
    echo vnc > "@DPRIV/work/display.backend" 2>/dev/null
fi

if [ "@DPRINT" = "1" ]; then
    echo "==== crosvm run --help ===="; "@DCROSVM" run --help 2>&1 | head -120
    echo "==== 探测 -- 参数 ===="; grep -aoE -- '--[a-z][a-z0-9-]{2,30}' "@DCROSVM" | sort -u | head -80
    exit 0
fi

# ---------- 6. 组装 crosvm 命令 ----------
# build_cmd <保护模式> <显示> <精简 0|1> <声音 0|1> <网络 0|1>
# 精简模式去掉全部可选增强参数, 只留最基础配置 (最后手段, 最大化成功率)
# 声音/网络单独成档: 它们的宿主侧准备(AAudio 开流 / tap)失败会让 crosvm 整体退出,
# 所以允许「有网无声」「无网无声」逐级降级
build_cmd() {
    PMODE="@D1"; DISP="@D2"; SLIM="@D3"; SND="@D{4:-0}"; NET="@D{5:-0}"
    # ---- 启动顺序 ----
    # bootindex 是 crosvm 写进 fw_cfg 的启动优先级，固件（EDK2）会按它排序。
    # 同一 index 不能重复，否则 crosvm 直接报 duplicate values 退出。
    # 优先光盘：光盘=1 启动盘=2 系统盘=3；优先硬盘：启动盘=1 光盘=2 系统盘=3；auto 不排。
    BOOT_BI=""; CD_BI=""; SYS_BI=""; DATA_BI=""
    if [ "@DBOOTORDER" = "cdrom" ]; then
        CD_BI=",bootindex=1"; BOOT_BI=",bootindex=2"; SYS_BI=",bootindex=3"; DATA_BI=",bootindex=8"
    elif [ "@DBOOTORDER" = "disk" ]; then
        BOOT_BI=",bootindex=1"; CD_BI=",bootindex=2"; SYS_BI=",bootindex=3"; DATA_BI=",bootindex=8"
    fi
    C="@DCROSVM run @DBIOS --name @DNAME --mem @DMEM --cpus @DCPUS --hypervisor gunyah --disable-sandbox"
    # 块设备传输: PCI (默认) 或 MMIO (guest 没有 PCI 宿主驱动时)
    BLK="--block"
    [ "@DMMIO" = "1" ] && BLK="--virtio-mmio-block"
    [ "@DSLIM" = "0" ] && have_flag no-balloon && C="@DC --no-balloon"
    if [ "@DPMODE" = "pseudo" ] && have_flag protected-vm-pseudo-unprotected; then
        C="@DC --protected-vm-pseudo-unprotected"
    elif [ "@DPMODE" = "without" ] && have_flag protected-vm-without-firmware; then
        C="@DC --protected-vm-without-firmware"
    fi
    # none 模式: 非保护 VM, 不加任何保护参数 (最宽松)
    # none 模式没有 lend 内存, 加 mthp 准备参数反而会干扰
    if [ "@DSLIM" = "0" ] && [ "@DPMODE" != "none" ]; then
        have_flag prepare-lend-mthp-mode && C="@DC --prepare-lend-mthp-mode @DMTHP_MODE"
    fi
    if [ "@DSLIM" = "0" ]; then
        if have_flag pflash && [ -n "@DPFLASH_FD" ] && [ -f "@DPFLASH_FD" ]; then
            C="@DC --pflash @DPFLASH_FD,block_size=262144"
        fi
    fi
    # 挂盘位: 选了且存在才挂载 (启动盘/光盘/系统盘只读, 数据盘读写, 新增硬盘按配置)
    # 注意: 警告一律走 stderr, 避免污染 @D(build_cmd) 捕获的命令串
    # ---- 同一镜像不能在两个盘位各挂一次 ----
    # crosvm 给每个块设备都 flock 目标文件；同一个文件第二次 flock 直接 EAGAIN
    # (os error 11)，整个 VM 起不来。最常见是把 ISO 同时填进「光盘」和「系统盘」。
    # 用 |path| 分隔串 + case 匹配做去重，不依赖换行/grep，绝对可靠。
    MOUNTED_LIST="|"
    mark_mounted() { MOUNTED_LIST="@DMOUNTED_LIST@D1|"; }
    already_mounted() {
        case "@DMOUNTED_LIST" in
            *"|@D1|"*) return 0 ;;
            *) return 1 ;;
        esac
    }
    # 某盘位是否按 SCSI 光驱挂载：非精简 + 该盘位文件是 ISO + crosvm 支持 scsi-block
    # 这样「光盘」之外的盘位放 ISO（如 virtio 驱动盘）也会变成真正的 CD-ROM，
    # Windows 安装程序的「加载驱动程序」才能浏览到里面的 .inf。
    # 注意: scsi-block 不接受 bootindex，所以走光驱语义时一律不带 bootindex。
    is_cdrom_slot() { # $1 = 该盘位的 IS_ISO 标记(0/1)
        [ "@DSLIM" = "0" ] && [ "@D1" = "1" ] && have_flag scsi-block
    }

    # 光盘（优先光驱语义）
    if [ -f "@DCD_ROM" ]; then
        if [ "@DSLIM" = "0" ] && [ "@DCD_IS_ISO" = "1" ] && have_flag scsi-block; then
            C="@DC --scsi-block path=@DCD_ROM,type=cdrom,ro=true,block_size=2048"
            echo "[*] 光盘: @D(basename "@DCD_ROM") [SCSI CD-ROM]" >&2
        else
            C="@DC @DBLK path=@DCD_ROM@DCD_FLAG@DCD_BI"
            if [ "@DCD_IS_ISO" = "1" ]; then
                echo "[*] 光盘: @D(basename "@DCD_ROM") [@DCD_MODE@DCD_BI · 本机 crosvm 无 scsi-block, 会显示为裸盘]" >&2
            else
                echo "[!] 光盘位放的不是 ISO（qcow2/raw 硬盘），已按普通硬盘挂载 —— 它不是光驱，不会有盘符；想当系统盘请挪到「启动盘」位" >&2
            fi
        fi
        mark_mounted "@DCD_ROM"
    else
        echo "[*] 光盘未选择, 跳过" >&2
    fi

    # 启动盘
    if [ -f "@DBOOT_DISK" ]; then
        if already_mounted "@DBOOT_DISK"; then
            echo "[!] 启动盘与光盘/系统盘是同一文件，已跳过（同一镜像不能挂两次）" >&2
        else
            C="@DC @DBLK path=@DBOOT_DISK@DBOOT_FLAG@DBOOT_BI"
            echo "[*] 启动盘: @D(basename "@DBOOT_DISK") [@DBOOT_MODE@DBOOT_BI]" >&2
            mark_mounted "@DBOOT_DISK"
            if [ "@DBOOT_IS_ISO" = "1" ]; then
                echo "[!!] 启动盘位放的是 ISO：virtio-blk 只有磁盘语义，固件不会把它当光驱，Windows PE 里照样一块盘都看不到。装系统请把 ISO 挪到「光盘」位，启动盘选已有系统盘或留空。" >&2
            fi
        fi
    else
        echo "[!!] 启动盘未选择/不存在" >&2
    fi

    # 系统盘
    if [ -f "@DSYSTEM_DISK" ]; then
        if already_mounted "@DSYSTEM_DISK"; then
            echo "[!] 系统盘与光盘/启动盘是同一文件，已跳过系统盘 —— 同一镜像不能挂两次" >&2
        else
            if is_cdrom_slot "@DSYSTEM_IS_ISO"; then
                C="@DC --scsi-block path=@DSYSTEM_DISK,type=cdrom,ro=true,block_size=2048"
                echo "[*] 系统盘(ISO): @D(basename "@DSYSTEM_DISK") [SCSI CD-ROM · 真正的光驱, 安装程序可浏览]" >&2
            else
                C="@DC @DBLK path=@DSYSTEM_DISK@DSYSTEM_FLAG@DSYS_BI"
                echo "[*] 系统盘: @D(basename "@DSYSTEM_DISK") [@DSYSTEM_MODE@DSYS_BI]" >&2
            fi
            mark_mounted "@DSYSTEM_DISK"
        fi
    else
        echo "[!!] 系统盘未选择/不存在" >&2
        echo "[!!] 若根分区在系统盘上, guest 会卡在 dracut 等待 /dev/disk/by-uuid (屏幕停在 Loading initial ramdisk)" >&2
    fi

    # 数据盘
    if [ -f "@DDATA_DISK" ]; then
        if already_mounted "@DDATA_DISK"; then
            echo "[!] 数据盘与其它盘位重复，已跳过" >&2
        else
            if is_cdrom_slot "@DDATA_IS_ISO"; then
                C="@DC --scsi-block path=@DDATA_DISK,type=cdrom,ro=true,block_size=2048"
                echo "[*] 数据盘(ISO): @D(basename "@DDATA_DISK") [SCSI CD-ROM · 真正的光驱, 安装程序可浏览]" >&2
            else
                C="@DC @DBLK path=@DDATA_DISK@DDATA_BI"
                echo "[*] 数据盘: @D(basename "@DDATA_DISK")" >&2
            fi
            mark_mounted "@DDATA_DISK"
        fi
    else
        echo "[*] 数据盘未选择, 跳过" >&2
    fi
${cfg.extraDisks.mapIndexed { i, d ->
    """    if [ -f "@DEXTRA${i + 1}_PATH" ]; then
        if already_mounted "@DEXTRA${i + 1}_PATH"; then
            echo "[!] 硬盘 ${sh(d.label)} 与其它盘位重复，已跳过" >&2
        else
            if is_cdrom_slot "@DEXTRA${i + 1}_IS_ISO"; then
                C="@DC --scsi-block path=@DEXTRA${i + 1}_PATH,type=cdrom,ro=true,block_size=2048"
                echo "[*] 硬盘 ${sh(d.label)}(ISO): @D(basename "@DEXTRA${i + 1}_PATH") [SCSI CD-ROM · 真正的光驱, 安装程序可浏览]" >&2
            else
                C="@DC @DBLK path=@DEXTRA${i + 1}_PATH@DEXTRA${i + 1}_FLAG@DEXTRA${i + 1}_BI"
                echo "[*] 硬盘 ${sh(d.label)}: @D(basename "@DEXTRA${i + 1}_PATH") [@DEXTRA${i + 1}_MODE@DEXTRA${i + 1}_BI]" >&2
            fi
            mark_mounted "@DEXTRA${i + 1}_PATH"
        fi
    else
        echo "[*] 硬盘 ${sh(d.label)} 未选择, 跳过" >&2
    fi"""
}.joinToString("\n")}
    if [ "@DDISP" = "gpu" ] && [ "@DGPU" = "1" ] && have_flag gpu; then
        # --virtio-mmio-gpu 只是「把 --gpu 设备改挂到 MMIO 传输」的开关, 设备本身仍由 --gpu 创建
        if [ "@DMMIO" = "1" ] && have_flag virtio-mmio-gpu; then
            C="@DC --gpu ${gpuArg} --virtio-mmio-gpu"
        else
            C="@DC --gpu ${gpuArg}"
        fi
        SCREEN="gpu-0"
    else
        have_flag simplefb && C="@DC --simplefb ${simplefbArg}"
        SCREEN="simplefb"
    fi
    # v1.9.8: 原生显示(android)下**绝不能**再开 vnc-server。
    # simplefb 模式只有一个 screen，crosvm 会直接拒绝启动：
    #   error 1: screen `simplefb` has 2 exporters (vnc-server ...; android-display-service ...);
    #   a screen drives at most one ... Give each a `screen=` of its own.
    # 帮助里 screen 只有 "gpu-0" / "simplefb" 二选一，simplefb 模式没有第二个 screen 可分，
    # 所以 v1.9.7 的「VNC 只做输入通道」方案在这台设备上不成立，已回退。
    # 输入改走由主程序 root 服务托管的四通道 unix socket，与 display exporter 完全无关。
    if have_flag vnc-server && [ "@DVNC" = "1" ] && [ "@DNATIVE_ACTIVE" != "1" ]; then
        VNC_ARG="host=@D{VNC_HOST:-127.0.0.1},port=@D{VNC_PORT:-5905},view-only=false,screen=@DSCREEN${vncCap}"
        C="@DC --vnc-server @DVNC_ARG"
    fi
    # v2.0 Android 原生显示后端（主程序内置，不再需要任何独立显示器 APP）：
    # crosvm 注册名为 crosvm_display 的 binder 服务，NativeDisplayActivity 通过
    # 主程序自有 root 服务拿到 ICrosvmAndroidDisplayService 并 setSurface，
    # 画面直接进 Android Surface，零 VNC 编码开销。只有带 android_display 后端
    # 的 crosvm（UBC 工具链 AOSP 版）才有这个 flag；内置自编 crosvm-mmio 没有
    # -> 4.6 段已提前判定并回退 VNC，绝不会因为一个不认识的参数把 VM 弄挂。
    if [ "@DNATIVE_ACTIVE" = "1" ]; then
        C="@DC --android-display-service crosvm_display"
        echo "[*] 显示后端: 内置原生 Surface (crosvm_display) 已启用" >&2
    fi
    # v2.0.10 原生显示：4 个 unix socket 由主程序 root 服务进程(InputSocketHost)在
    # crosvm 启动前 bind+listen 好（APP 侧 start() 同步等待），与本段 --input 的 path
    # 一一对应。APP 通过 AIDL 把 evdev 字节交给 root 进程直写，不经 touchd/TCP。
    # width/height 用宿主分辨率（autoRes），触摸 1:1，不需要换算。
    if [ "@DNATIVE_ACTIVE" = "1" ] && [ "@DANDROID_INPUT" = "1" ] && have_flag input; then
        # 注意 C="@DC ..." 是「追加」不是覆盖（@D 前缀会还原成 shell 的 C 变量），四行叠出四路设备。
        C="@DC --input multi-touch[path=/data/local/tmp/gyah_input_7788.sock,width=${rw},height=${rh}]"
        C="@DC --input keyboard[path=/data/local/tmp/gyah_input_7789.sock]"
        # v2.0.14: 7790 由相对 mouse 换成 absolute-mouse（qemu usb-tablet profile）。
        # crosvm 源码 sys/linux.rs:250 明确：原生显示(NATIVELY exported screen)下 DroidVM
        # 官方 APP 用的就是 `--input absolute-mouse[...] fed over a daemon socket`，与 VNC
        # binding 建的表是同一类设备（"guest must not be able to tell the two apart"）。
        # 相对 mouse + multi-touch 在 SDDM/Wayland 下指针不被接受，键盘却正常 —— 这正是
        # 「自带键盘能用、其他一无用处」的根因。绝对指针是 UEFI/安装器/X11/Wayland 全认的。
        # 显式 width/height = ${rw}x${rh} 让设备 ABS 范围与 APP 坐标基准严格一致，防 clamp。
        if [ "@DABS_MOUSE" = "1" ]; then
            C="@DC --input absolute-mouse[path=/data/local/tmp/gyah_input_7790.sock,width=${rw},height=${rh}]"
            echo "[*] 输入: 四通道已接入 (触摸 ${rw}x${rh}; 7790=usb-tablet 绝对指针)" >&2
        else
            C="@DC --input mouse[path=/data/local/tmp/gyah_input_7790.sock]"
            echo "[!] 此 crosvm 无 absolute-mouse 子类型, 7790 回退相对 mouse (APP 自动改发相对事件)" >&2
        fi
        C="@DC --input switches[path=/data/local/tmp/gyah_input_7791.sock]"
    elif [ "@DSLIM" = "0" ] && have_flag input && [ -n "@DINPUT_DEV" ]; then
        # 同上: --virtio-mmio-input 只改传输, --input 才是设备本体
        if [ "@DMMIO" = "1" ] && have_flag virtio-mmio-input; then
            C="@DC --input @DINPUT_DEV --virtio-mmio-input"
        else
            C="@DC --input @DINPUT_DEV"
        fi
    elif [ "@DSLIM" = "0" ] && [ "@DTOUCHON" = "1" ] && have_flag input; then
        # 平板模式：真多点触摸屏，坐标范围 = simplefb 分辨率。
        # 与 VNC 的 virtio-tablet 并存，但触摸事件只走这个设备，pointer 事件被 APP 丢弃。
        if [ "@DMMIO" = "1" ] && have_flag virtio-mmio-input; then
            C="@DC --input \"@DTOUCH_ARG\" --virtio-mmio-input"
        else
            C="@DC --input \"@DTOUCH_ARG\""
        fi
    fi
    # ---- 网络: --net 是设备本体; MMIO 下再加 --virtio-mmio-net 把它改挂到 MMIO ----
    if [ "@DSLIM" = "0" ] && [ "@DNET" = "1" ] && [ "@DNETON" = "1" ] && have_flag net; then
        # 致命坑：--virtio-mmio-net 是**无参数开关**（crosvm 帮助原文：
        #   "put the --net devices on the virtio-mmio transport instead"），
        # 它本身不带 tap 参数。之前写成 C="@DC --virtio-mmio-net" 会把整个 --net
        # 覆盖掉，crosvm 收不到 tap-name → **压根没有创建网卡**，
        # guest 里任何系统（Debian/Windows）都必然「DHCP 自动配置失败」。
        # 必须追加，不能覆盖。
        C="@DC --net tap-name=@DNET_TAP,mac=@DNET_MAC"
        if [ "@DMMIO" = "1" ] && have_flag virtio-mmio-net; then
            # 这里只能追加传输开关，绝不能再写一遍 --net。
            # MMIO 下若 --net 出现两次，crosvm 会两次打开同一个 tap，第二次报
            # "failed to create tap interface: Device or resource busy (os error 16)"
            # 然后整个进程退出 —— 这正是之前 MMIO 模式 5/5 全失败、连固件都进不去的原因。
            # --virtio-mmio-net 是无参数开关，它把「已经由 --net 创建的网卡」改挂到 MMIO。
            C="@DC --virtio-mmio-net"
        fi
    fi
    # ---- 声音: 只有链接了真实 libaaudio 的 crosvm 才传；stub 版会 panic ----
    # uid 必须是 APP 自己的 uid，否则 Android 静音 root 的 AAudio（播放无声、采集全零）
    # device_table: APP 枚举的宿主音频端点表，让 crosvm 精确选到扬声器/耳机；
    #   没有这张表时 crosvm 会「跟随平台默认路由」(见 virtio-snd: no host device config)
    if [ "@DSLIM" = "0" ] && [ "@DSND" = "1" ] && [ "@DAUDIOON" = "1" ] && have_flag virtio-snd \
        && grep -aq "libaaudio.so" "@DCROSVM" 2>/dev/null; then
        SNDOPT=""
        [ -n "@DSND_TABLE" ] && [ -f "@DSND_TABLE" ] && SNDOPT=",device_table=@DSND_TABLE"
        [ -n "@DSND_DEVS" ] && SNDOPT="@DSNDOPT,@DSND_DEVS"
        # 整个参数加引号: output_device_config 带方括号, 不加引号会被 shell 当成通配符
        C="@DC --virtio-snd \"backend=aaudio,capture=false,num_output_devices=1,num_input_devices=0,uid=@DAPP_UID@DSNDOPT\""
        # MMIO guest (Darwin/XNU) 没有 PCI 宿主驱动，声音设备和块/网卡一样要改挂到
        # virtio-mmio 传输上，否则 guest 里根本枚举不到 snd 设备。
        if [ "@DMMIO" = "1" ] && [ "@DMMIO_SND" = "1" ] && have_flag virtio-mmio-snd; then
            C="@DC --virtio-mmio-snd"
        fi
    fi
    [ "@DSLIM" = "0" ] && have_flag serial && C="@DC --serial type=stdout,num=1,hardware=sbsa"
    echo "@DC"
}

if [ "@DPRINT" = "2" ]; then
    echo "==== 环境 ===="
    echo "crosvm : @DCROSVM"; echo "固件   : @DBIOS"; echo "libc++ : @DLIBCXX_SO"
    echo "==== 启动命令(首选) ===="
    build_cmd pseudo simplefb 0
    exit 0
fi

echo "[*] 启动 GunyaniOS (gunyah) : @DNAME / @DMEM M / @DCPUS cpu / GPU=@DGPU VNC=@DVNC"

# 清理残留 crosvm (占端口 / Gunyah VM)
OLDPIDS=@D(pidof crosvm 2>/dev/null; pidof crosvm-mmio 2>/dev/null || true)
if [ -n "@DOLDPIDS" ]; then
    echo "[*] 清理残留 crosvm (pid: @DOLDPIDS)"; kill @DOLDPIDS 2>/dev/null; sleep 1; kill -9 @DOLDPIDS 2>/dev/null
fi
mkdir -p "@DWORK" /var/empty 2>/dev/null
echo "[*] LD_LIBRARY_PATH=@DLD_LIBRARY_PATH"

# ---------- 7. 启动 + 自动回退监督 ----------
# 不同固件/驱动组合对 crosvm 参数支持不一: 某些参数字符串存在于二进制但运行时
# 会直接报错退出 (ECONNREFUSED = VNC 没人监听 = crosvm 已死)。
# 因此按多组配置依次尝试, 每组起后检测「进程存活 + VNC 端口监听」;
# 失败自动 kill 换下一组, 直到找到能跑的组合。
port_listening() {
    netstat -tln 2>/dev/null | grep -q "[.:]@D1 " && return 0
    # 兜底: 直接解析 /proc/net/tcp (本地端口为 4 位十六进制, 如 5905 -> 1709)
    HEXP=@D(printf '%04X' "@D1" 2>/dev/null)
    # 监听地址可能是 0.0.0.0 (00000000) 也可能是 127.0.0.1 (0100007F)
    [ -n "@DHEXP" ] && grep -qiE "(00000000|0100007F):@DHEXP " /proc/net/tcp 2>/dev/null && return 0
    return 1
}

kill_vm() {
    [ -n "@D{1:-}" ] && kill -9 "@D1" 2>/dev/null
    pkill -9 -f "@DCROSVM" 2>/dev/null
    sleep 1
}

# try_start <标签> <完整命令>
# 成功 (进程活着 + VNC 在听 + 没有致命错误) 才 wait 前台挂住; 失败返回 1
# 关键: crosvm 进程活着 ≠ 虚拟机起来了。Gunyah shim 可能在十几秒内就
#   "SHIM DIED: the resource manager refused MEM_ACCEPT" 退出, 而 crosvm 仍然
#   挂着 VNC 端口 -> 画面永远是黑的。所以必须扫日志判定, 不能只看进程。
log_since() {
    [ -f "@DBOOTLOG" ] || return 0
    tail -c +@D((MARK + 1)) "@DBOOTLOG" 2>/dev/null
}

# 组合不可用判定：只认「VM 真的建不起来」的信号。
#  - SHIM DIED / gave up / refused MEM_ACCEPT：Gunyah 侧直接放弃，crosvm 随后必退
#  - panicked at：crosvm 自己 panic
# 注意：以下两条曾经也被当成致命，实测是误杀：
#  - host access to lent memory region (in protected VM)
#  - xhci controller stopped working
# 它们只是「某个设备读不到 lent 内存」，crosvm 照旧活着、guest 照旧启动
# （deepin 已经进到 GRUB 菜单了）。原来的判据会在这里把好好的虚拟机 kill 掉，
# 表现就是「画面出来了又退出」。现在只记一条警告，不换组。
shim_dead() {
    log_since | grep -aqE "SHIM DIED|SHIM: gave up|refused MEM_ACCEPT|panicked at|GH: gave up"
}

# 设备级降级（非致命）：lent 内存访问失败会让 USB 控制器、个别 virtio 设备停摆，
# 但 guest 主流程仍可继续。只提示一次，绝不 kill。
io_degraded() {
    log_since | grep -aqE "host access to lent memory region|xhci controller stopped working"
}

# 网络自检: 打印 tap 计数与 guest 是否拿到地址。
#   rx_packets = crosvm 从 guest 收到的帧 (guest 真的发出去了才会涨)
#   tx_packets = 内核/ DHCP 发给 guest 的帧
# 这两个数比「邻居表里有没有 IP」更能说明是谁没干活: rx 一直是 0 = guest 没发过一个包。
net_stat() {
    _gip=$(ip -4 neigh show dev "@DNET_BR" 2>/dev/null | grep -v FAILED | awk '{print @D1}' | head -1)
    _rx=$(cat "/sys/class/net/@DNET_TAP/statistics/rx_packets" 2>/dev/null)
    _tx=$(cat "/sys/class/net/@DNET_TAP/statistics/tx_packets" 2>/dev/null)
    if [ -n "@D_gip" ]; then
        echo "[✓] 网络(@D1): guest 已拿到地址 @D_gip (tap rx=@D{_rx:-?} tx=@D{_tx:-?})"
        return 0
    fi
    echo "[*] 网络(@D1): 还没有 guest 地址 (tap rx=@D{_rx:-?} tx=@D{_tx:-?})"
    return 1
}

# 120s 仍没地址时给结论。三种情况分开说，别让用户去猜是宿主还是 guest：
#   rx=0                  → guest 一个包都没发，没驱动
#   有 DISCOVER 没 OFFER  → 宿主 DHCP 侧的事
#   rx 在涨但没有 DISCOVER→ guest 的 DHCP 客户端没跑（精简版 Windows 常被删服务）
net_verdict() {
    _rx=@D(cat "/sys/class/net/@DNET_TAP/statistics/rx_packets" 2>/dev/null)
    _tx=@D(cat "/sys/class/net/@DNET_TAP/statistics/tx_packets" 2>/dev/null)
    echo "[!] 120s 内 guest 仍未拿到 IP (tap rx=@D{_rx:-?} tx=@D{_tx:-?})"
    if [ "@D{_rx:-0}" -eq 0 ] 2>/dev/null; then
        echo "    诊断 A: guest 一个以太网帧都没发出来 —— guest 里没有 virtio-net 驱动。"
        echo "            Windows: 设备管理器「以太网控制器」带感叹号 → 装驱动盘里的 NetKVM\\w11\\ARM64"
    elif grep -qa "\[dd\] DISCOVER" "@DWORK/net.log" 2>/dev/null; then
        echo "    诊断 B: guest 发了 DISCOVER，但没完成 OFFER/ACK —— 看下面 DHCP 日志里的 xid 轨迹"
    else
        echo "    诊断 C: guest 在发包(tap rx 在涨)，却一个 DHCP DISCOVER 都没发 —— 不是宿主的锅。"
        echo "            guest 里 DHCP 客户端没跑，精简版 Windows 常见："
        echo "            · 服务里把「DHCP Client」设为自动并启动：sc config Dhcp start=auto && net start Dhcp"
        echo "            · 网卡 IPv4 属性要选「自动获得 IP 地址」"
        echo "            · 设备管理器确认网卡不是「环回适配器」这类虚拟网卡"
    fi
    echo "    临时可用: 手动设 IP @DNET_DHCP_START / 255.255.255.0 / 网关 @DNET_GW / DNS 223.5.5.5"
    echo "    ---- DHCP 日志（最后 10 行）----"
    tail -n 10 "@DWORK/net.log" 2>/dev/null | sed 's/^/    /'
}

# ---------- 网络取证 ----------
# 真机「没网」至少有四种完全不同的原因，靠描述分不清：
#   A. tap 没建 / 没挂到 bridge   → tap.master
#   B. DHCP 收不到包              → tap.rx_pkt + net.log 里没有 DISCOVER
#   C. 发了 OFFER 但 guest 不接    → 有 OFFER 没 ACK
#   D. 有 IP 但出不了网            → nat / fwd / rule 计数为 0
# 这段只记录证据，不改变任何配置；一次回传就能定位，不用再来回猜。
net_selftest() {
    [ "@DNETON" = "1" ] || return 0
    _ms=@D(readlink /sys/class/net/@DNET_TAP/master 2>/dev/null)
    [ -n "@D{_ms:-}" ] || _ms="none"
    echo "---- 网络自检 ----"
    echo "  selinux=@D(getenforce 2>/dev/null || echo ?) ip_forward=@D(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null || echo ?)"
    echo "  tap @DNET_TAP state=@D(cat /sys/class/net/@DNET_TAP/operstate 2>/dev/null || echo MISSING) mtu=@D(cat /sys/class/net/@DNET_TAP/mtu 2>/dev/null || echo -) master=@D_ms"
    echo "  tap rx_pkt=@D(cat /sys/class/net/@DNET_TAP/statistics/rx_packets 2>/dev/null || echo -) tx_pkt=@D(cat /sys/class/net/@DNET_TAP/statistics/tx_packets 2>/dev/null || echo -) rx_drop=@D(cat /sys/class/net/@DNET_TAP/statistics/rx_dropped 2>/dev/null || echo -) tx_drop=@D(cat /sys/class/net/@DNET_TAP/statistics/tx_dropped 2>/dev/null || echo -)"
    echo "  bridge @DNET_BR brif=@D(ls /sys/class/net/@DNET_BR/brif 2>/dev/null | tr '\n' ' ')"
    echo "  nat=@D(iptables -w 5 -t nat -S POSTROUTING 2>/dev/null | grep -c 192.168.100) fwd=@D(iptables -w 5 -S FORWARD 2>/dev/null | grep -c "@DNET_BR") rule=@D(ip rule show 2>/dev/null | grep -c 192.168.100)"
    echo "  neigh=@D(ip -4 neigh show dev "@DNET_BR" 2>/dev/null | head -2 | tr '\n' ';')"
    tail -n 6 "@DWORK/net.log" 2>/dev/null | sed 's/^/  dhcp| /'
    _rx=@D(cat /sys/class/net/@DNET_TAP/statistics/rx_packets 2>/dev/null || echo 0)
    if [ "@D{_rx:-0}" -eq 0 ] 2>/dev/null; then
        echo "  => guest 一帧都没发：网卡没被 guest 识别（缺 virtio-net 驱动，或 crosvm 没带上 --net）"
    elif ! grep -qa "DISCOVER" "@DWORK/net.log" 2>/dev/null; then
        echo "  => guest 在发包但没有 DISCOVER：guest 的 DHCP Client 服务没跑"
    elif grep -qa "OFFER" "@DWORK/net.log" 2>/dev/null && ! grep -qa "ACK" "@DWORK/net.log" 2>/dev/null; then
        echo "  => 已发 OFFER 但没等到 ACK：guest 侧没接受（IP 冲突/静态地址/防火墙）"
    else
        echo "  => DHCP 有应答：若 guest 仍无网，问题在 NAT/转发（看上面 nat/fwd/rule）"
    fi
}

# 触摸直通取证：区分「touchd 没跑」「crosvm 没连」「都连了但 guest 没驱动」
# 后两种在 APP 侧看起来完全一样（点了没反应），但处置完全不同。
touch_stat() {
    [ "@DTOUCHON" = "1" ] || return 0
    echo "---- 触摸直通 ----"
    if pgrep -f "@DPRIV/touchd" >/dev/null 2>&1; then
        if [ -S "@DTOUCH_SOCK" ]; then _tss=ok; else _tss=MISSING; fi
        echo "  touchd=running sock=@D_tss"
    else
        echo "  touchd=NOT_RUNNING"
    fi
    if grep -qa "crosvm 已连接" "@DWORK/touch.log" 2>/dev/null; then
        echo "  crosvm 已连上触摸 socket"
    else
        echo "  crosvm 还没连上触摸 socket => guest 未创建 virtio-input 设备（多半缺驱动），APP 已回退 VNC 鼠标"
    fi
    if grep -qa "APP 已连接" "@DWORK/touch.log" 2>/dev/null; then
        echo "  APP 已连接"
    fi
    tail -n 4 "@DWORK/touch.log" 2>/dev/null | sed 's/^/  log| /'
}

# 后台观察者：只写日志，不阻塞启动。
#   shim 崩溃检测留在 120s 内（实测多在 8~20s 才崩），网络按 30/60/120s 打点。
watch_vm() {
    _i=0
    _io_warned=0
    while [ "@D_i" -lt 60 ]; do
        sleep 2
        _i=@D((_i + 1))
        kill -0 "@DPID" 2>/dev/null || return 0
        if [ "@D_i" -ge 4 ] && shim_dead; then
            echo "[!!] Gunyah shim @D((_i * 2))s 后崩溃 (guest 没起来, 画面会是空的)"
            log_since | grep -aE "SHIM DIED|gave up|refused MEM_ACCEPT" | head -3
            kill_vm "@DPID"
            return 0
        fi
        if [ "@D_i" -ge 4 ] && [ "@D_io_warned" != "1" ] && io_degraded; then
            _io_warned=1
            echo "[!] 检测到 lent 内存访问异常: USB 控制器/个别设备可能不可用"
            echo "    guest 仍可继续启动, 不中断本次运行（这是警告, 不是失败）"
        fi
        case "@D_i" in
            15)
                if [ "@DNETON" = "1" ] && [ "@DCUR_NET" = "1" ]; then net_stat "30s"; net_selftest; fi
                touch_stat
                ;;
            30)
                if [ "@DNETON" = "1" ] && [ "@DCUR_NET" = "1" ]; then net_stat "60s"; fi
                touch_stat
                ;;
            60)
                if [ "@DNETON" = "1" ] && [ "@DCUR_NET" = "1" ]; then
                    net_stat "120s" || { net_verdict; net_selftest; }
                fi
                ;;
        esac
    done
}

try_start() {
    LABEL="@D1"; RUN="@D2"
    echo "[*] 尝试启动 [@DLABEL] ..."
    echo "[*] CMD: @DRUN"
    MARK=0
    # v2.0.19：每组参数只写一次「UEFI 已就绪」标记，换下一组时重置。
    UEFI_MARKED=0
    [ -f "@DBOOTLOG" ] && MARK=@D(wc -c < "@DBOOTLOG" 2>/dev/null || echo 0)
    ( sh -c "exec @DRUN" ) &
    PID=@D!
    # 等「VNC 端口开始监听」, 而不是固定睡 6+8 秒。
    #   4GB 的 --prepare-lend-mthp 预填充本身就要 10~15 秒 (日志里 GH: Phase 2/3 那几行),
    #   端口是在内存共享 + SHIM 起来之后才 listen 的。固定 14 秒会把「还在准备内存」
    #   判成「这台固件不支持 VNC」然后 kill 掉 —— 被 kill 的恰好是第一组
    #   (唯一带声音的那组), 于是用户看到「声音开关是开的, VM 里却没有声卡」。
    WAITED=0
    TAP_DONE=0
    while [ "@DWAITED" -lt 75 ]; do
        sleep 1
        WAITED=@D((WAITED + 1))
        if [ "@DTAP_DONE" = "0" ] && [ "@DWAITED" -ge 5 ]; then
            # crosvm 打开(或重建)过 tap 了, 这时再确认它确实还挂在 bridge 上
            ensure_tap_on_bridge
            TAP_DONE=1
        fi
        if ! kill -0 "@DPID" 2>/dev/null; then
            wait "@DPID" 2>/dev/null
            echo "[!!] [@DLABEL] crosvm @D{WAITED}s 内已退出, 看上方 stderr 定位原因"
            echo "starting:@DIDX/@DTOTAL:@DLABEL" > "@DSTATE" 2>/dev/null
            if log_since | grep -aq "open_disk_file failed"; then
                echo "[!!] 镜像打不开——这是镜像/挂载参数问题，换保护模式或显示方式都没用"
                log_since | grep -aA4 "open_disk_file failed" | head -8
                if log_since | grep -aq "failed to lock file"; then
                    echo "[!!] 具体原因是文件锁冲突：同一个镜像被挂到了两个盘位。"
                    echo "     去看「光盘 / 启动盘 / 系统盘」是不是选了同一个文件 —— 光驱位放 ISO 时，系统盘必须留空。"
                fi
                kill_vm ""
                return 2
            fi
            kill_vm ""
            return 1
        fi
        if [ "@DWAITED" -ge 5 ] && shim_dead; then
            echo "[!!] [@DLABEL] Gunyah shim 已中断 (虚拟机没起来, 只有 VNC 空画面), 换下一组"
            log_since | grep -aE "SHIM DIED|gave up|refused MEM_ACCEPT" | head -3
            kill_vm "@DPID"
            return 1
        fi
        # v2.0.19：原生显示一旦确认 crosvm 存活、Gunyah shim 没崩，就立刻写
        # vm.state=uefi。APP 轮询读到（或看到下面这行日志）会马上进显示器，
        # 不再等后面 14 秒判活观察 —— UEFI 菜单 / GRUB 从第一帧就能看到并能按 ESC/F2。
        # 只写一次，避免每轮循环刷状态文件；状态之后仍会正常推进到 running / starting。
        if [ "@DNATIVE_ACTIVE" = "1" ] && [ "@DWAITED" -ge 5 ] && [ "@D{UEFI_MARKED:-0}" = "0" ]; then
            UEFI_MARKED=1
            echo "[✓] [@DLABEL] 检测到 uefi: UEFI 帧缓冲已就绪, 立即进入显示器"
            echo uefi > "@DSTATE" 2>/dev/null
        fi
        if [ "@DNATIVE_ACTIVE" = "1" ]; then
            # 原生显示没有 VNC 端口可等；crosvm 进程活着就继续（后面还有 14s 观察窗口）
            [ "@DWAITED" -ge 15 ] && break
        elif [ "@DVNC" = "1" ]; then
            port_listening "@D{VNC_PORT:-5905}" && break
        else
            [ "@DWAITED" -ge 15 ] && break
        fi
    done
    if [ "@DNATIVE_ACTIVE" != "1" ] && [ "@DVNC" = "1" ] && ! port_listening "@D{VNC_PORT:-5905}"; then
        echo "[!!] [@DLABEL] crosvm 存活但 @D{WAITED}s 内 VNC 端口 @D{VNC_PORT:-5905} 仍未监听 (显示/VNC 参数不被支持)"
        kill_vm "@DPID"
        return 1
    fi
    [ "@DTAP_DONE" = "0" ] && ensure_tap_on_bridge

    # ---- VNC 就绪 ≠ guest 起来了 ----
    # Gunyah 的 shim 是「先跑起来，再向资源管理器 ACCEPT 内存」。ACCEPT 被拒（err=0x9）
    # 时 VNC 端口早就 listen 了、画面全黑，8~20 秒之后才打 SHIM DIED。
    # 以前这里看到端口在听就 break 出去判「启动成功」，主流程 wait 住、不再换组合 ——
    # 用户看到的就是「VNC 连上几秒就断」，而且后面还有 4 组配置根本没试过。
    # 所以端口就绪后再守 14 秒：shim 死了就判失败、接着试下一组。
    _hold=0
    while [ "@D_hold" -lt 14 ]; do
        sleep 2
        _hold=@D((_hold + 2))
        if ! kill -0 "@DPID" 2>/dev/null; then
            echo "[!!] [@DLABEL] crosvm 在 VNC 就绪后又退出，换下一组"
            kill_vm ""
            return 1
        fi
        if shim_dead; then
            echo "[!!] [@DLABEL] VNC 就绪 @D_hold 秒后 Gunyah shim 崩溃，guest 没起来，换下一组"
            log_since | grep -aE "SHIM DIED|gave up|refused MEM_ACCEPT" | head -3
            kill_vm "@DPID"
            return 1
        fi
    done
    echo "[✓] [@DLABEL] 观察 @D_hold 秒无异常，guest 已进入固件阶段"
    echo "[✓] [@DLABEL] 启动成功, crosvm pid=@DPID, VNC 已监听 @D{VNC_PORT:-5905}"
    if [ "@DNETON" = "1" ] && [ "@DCUR_NET" = "1" ]; then
        echo "[*] 网络: bridge @DNET_BR (@DNET_GW/@DNET_PREFIX) → tap @DNET_TAP, guest 走 DHCP @DNET_DHCP_START~@DNET_DHCP_END"
        _le=$(ip -4 neigh show dev "@DNET_BR" 2>/dev/null | grep -v FAILED | head -3)
        [ -n "@D_le" ] && echo "[*] 已学到 guest: @D_le"
    elif [ "@DNETON" = "1" ]; then
        echo "[*] 本组合未启用 virtio-net（网络=关）: guest 里不会有网卡, 联网自检跳过"
    fi
    if [ "@DAUDIOON" = "1" ] && [ "@D{SND:-0}" = "1" ]; then
        echo "[*] 声音: virtio-snd/aaudio uid=@DAPP_UID"
        echo "    端点表: @D{SND_TABLE:-未生成}  @D{SND_DEVS:+(选用 @D{SND_DEVS#output_device_config=})}"
    elif [ "@DAUDIOON" = "1" ]; then
        echo "[!] 声音未就绪：本次运行跳过声音（VM 继续启动，画面/输入不受影响）。"
    fi
    echo running > "@DSTATE" 2>/dev/null
    echo "[✓] [@DLABEL] 已就绪 —— 启动流程到此结束（后台只做观察，不拖时间）"
    # ---------- 输入链路诊断 (v2.0.10: root 服务托管) ----------
    # v2.0.10 起没有 touchd / native_input.log：4 路 socket 由主程序 root 服务进程
    # (InputSocketHost) 托管，crosvm 是否连上、APP 写入成功/失败，都在**显示器状态栏**
    # 实时显示（服务✓/✗、触摸/键盘/鼠标 ✓/✗、写入✓N ✗N），比脚本日志更准。
    # 脚本这里只核对 4 个被托管的 socket 文件都在。
    if [ "@DNATIVE_ACTIVE" = "1" ]; then
        echo "[*] ---- 输入链路 (root 服务 AIDL 直写, 无 touchd/TCP 桥) ----"
        _dgin=0
        for _dgp in 7788 7789 7790 7791; do
            _case=触摸; [ "@D{_dgp}" = "7789" ] && _case=键盘; [ "@D{_dgp}" = "7790" ] && _case=鼠标; [ "@D{_dgp}" = "7791" ] && _case=开关
            if [ -S "/data/local/tmp/gyah_input_@D{_dgp}.sock" ]; then
                echo "    [@D_dgp] @D_case: 托管 socket ✓"
                _dgin=@D((_dgin+1))
            else
                echo "    [@D_dgp] @D_case: 托管 socket ✗ (root 服务未连)"
            fi
        done
        echo "[*] 输入 socket: @D_dgin/4 由 root 服务托管; 连接/写入实况见显示器状态栏"
        # v2.0.12：socket 文件在 ≠ VM 里有输入设备。真正决定权在本次 CMD 是否带 --input。
        # 旧版第一组失败后把 ANDROID_INPUT 置 0 再重试，这里 4 个 socket 照样显示
        # 「托管 ✓」，但 CMD 里一个 --input 都没有 —— 正是「全绿却不能操作」的误导源。
        _din=@D(echo "@DCMD" | grep -o -- "--input" | wc -l)
        if [ "@D_din" -ge 4 ]; then
            echo "[*] 本次 CMD: 4 路 --input 已注入 (VM 有 virtio 输入设备)"
        else
            echo "[!!] 输入设备未全部就绪（@D_din/4 路 --input）：已跳过缺失的部分，画面正常但部分输入可能不可用。"
            echo "     把本日志发回开发者；检查启动组合是否触发了输入降级。"
        fi
    fi
    # 观察放到后台：主流程立刻 wait crosvm，启动耗时不增加。
    # 之前是前台 sleep 120 秒，控制台要等 2 分钟才「跑完」，看着像启动很慢。
    watch_vm &
    wait "@DPID"
    echo "[*] crosvm 退出"
    echo stopped > "@DSTATE" 2>/dev/null
    exit 0
}
VNC_HOST="${sh(cfg.vncHost.ifBlank { "127.0.0.1" })}"
VNC_PORT=${cfg.vncPort}
# VNC 端口被占用时自动换下一个 (最多 10 次)
i=0
while [ @Di -lt 10 ] && [ "@DNATIVE_ACTIVE" != "1" ] && [ "@DVNC" = "1" ] && port_listening "@DVNC_PORT"; do
    echo "[*] VNC 端口 @DVNC_PORT 已被占用, 换下一个..."
    VNC_PORT=@D((@DVNC_PORT + 1))
    i=@D((i + 1))
done
# 实际端口落盘: 5905 被上次残留的进程占着时会顺延到 5906…
# APP 读这个文件去连, 否则界面一直显示「5905 未就绪」, 而画面其实在 5906。
echo "@DVNC_PORT" > "@DWORK/vnc.port" 2>/dev/null
[ "@DVNC_PORT" != "5905" ] && echo "[*] VNC 端口 5905 被占用，本次使用 @DVNC_PORT"

# ---------- v2.0: 输入桥已在 4.7 段由本脚本以 root 起好，无需任何 APP 预热 ----------
# 显示器（主程序内置 NativeDisplayActivity）什么时候打开都能连上 TCP 7788-7791。

# 模式链: 保护:显示:精简 (由 APP 保护模式配置注入, auto 时全链回退)
#  1. pseudo + gpu/simplefb —— DroidVM iOS 同款 (保护 VM 但宿主可读帧缓冲)
#  2. without + gpu/simplefb —— 保守保护模式 + EDK2 帧缓冲
#  3. none + simplefb     —— 非保护 VM (最宽松)
#  4. without + gpu 精简  —— 最早直跑脚本的配置 (仅最基础参数)
PLANS="${plans}"
# 组总数：界面用它显示「启动中 2/5」，用户就不会把「换下一组参数」误认为反复重启。
# 组合顺序由「启动配置 → 保护模式」注入；大页池模块正常时第一组(pseudo+gpu)就成，
# 不会走到后面几组。APP 侧不锁定模式、不改内存，把选择权交回配置与模块。
TOTAL=0
for _p in @DPLANS; do TOTAL=@D((TOTAL+1)); done
IDX=0

# ---------- LEND 内存的 mTHP 策略 ----------
# --prepare-lend-mthp-mode 只有两个值，选错就直接 MEM_ACCEPT 0x9：
#   single  一次性把整块折叠成 2MB 再 LEND —— eager-parcel 内核（sm8650 这一代）
#   chunked 切成 <=256MB 的分片逐个 LEND —— demand-paging 内核（sm8750 这一代）
# auto 时按 SoC 判一次，并把「另一个模式」留给后半段兼容模式兜底。
MTHP_ALT="single"
MTHP_CFG="${sh(cfg.mthpMode.ifBlank { "auto" })}"
if [ "@DMTHP_CFG" = "single" ]; then
    MTHP_MODE="single"; MTHP_ALT="chunked"
elif [ "@DMTHP_CFG" = "chunked" ]; then
    MTHP_MODE="chunked"; MTHP_ALT="single"
else
    _soc=$(getprop ro.board.platform 2>/dev/null; getprop ro.soc.model 2>/dev/null)
    case "@D_soc" in
        *8650*|*8550*) MTHP_MODE="single"; MTHP_ALT="chunked" ;;
        *) MTHP_MODE="chunked"; MTHP_ALT="single" ;;
    esac
    echo "[*] mTHP 策略: @DMTHP_MODE（SoC=@D{_soc:-未知}），后半段兜底 @DMTHP_ALT"
fi

# ---------- 启动前整理一次内存 ----------
# 上一次运行读过的镜像会留在 page cache 里（一块 8GB 的 ISO 就够把空闲页切碎），
# Gunyah 资源管理器在 MEM_ACCEPT 时拿不到成片的页就直接拒绝（err=0x9），
# 表现就是 shim 起来几秒后崩溃、VNC 刚连上就断。先回收缓存再压实一遍。
sync 2>/dev/null
if echo 3 > /proc/sys/vm/drop_caches 2>/dev/null; then
    echo "[*] 已回收镜像缓存 (drop_caches 3)"
else
    echo "[!] 无法写 drop_caches（没有 root？），跳过缓存回收"
fi
echo 1 > /proc/sys/vm/compact_memory 2>/dev/null && echo "[*] 已压实物理内存 (compact_memory)"

# crosvm 的准备阶段会给借出区做 mlock（锁住物理页，防止被回收）。
# RLIMIT_MEMLOCK 太小（部分 ROM 默认 64KB）时 mlock 直接 ENOMEM，
# 表现同样是 "2MB pass 0: 0 OK, N failed"。root 下解掉这个限制。
ulimit -l unlimited 2>/dev/null
# 被 mlock 住的页是 unevictable，默认允许压缩迁移；有些 ROM 把它关成 0，
# 于是 MADV_COLLAPSE 找不到可迁移的邻居页 → 折叠全失败。这条必须打开。
if [ -w /proc/sys/vm/compact_unevictable_allowed ]; then
    echo 1 > /proc/sys/vm/compact_unevictable_allowed 2>/dev/null && \
        echo "[*] compact_unevictable_allowed=1（允许压缩 mlock 页，否则 2MB 折叠必然失败）"
fi
_vmm=$(cat /proc/sys/vm/max_map_count 2>/dev/null)
if [ -n "@D_vmm" ] && [ "@D_vmm" -lt 655300 ] 2>/dev/null; then
    echo 655300 > /proc/sys/vm/max_map_count 2>/dev/null && \
        echo "[*] vm.max_map_count: @D_vmm → 655300（VMA 太少时折叠/映射会 ENOMEM）"
fi

# ---------- THP / mTHP：MEM_ACCEPT 0x9 的真正成因 ----------
# crosvm 的 --prepare-lend-mthp 用 MADV_COLLAPSE 把借出区折叠成 2MB 大页。
# 内核 transparent_hugepage/enabled 一旦是 never，MADV_COLLAPSE 直接失败，
# boot.log 里就是 "2MB pass 0: 0 OK, N failed"；Gunyah 拿不到 2MB 页 →
# MEM_ACCEPT err=0x9 → shim 起来几秒就退出，VNC 刚连上就断。
# **这条和「给多少内存」无关**，所以把 4096 降到 3072 不会有任何区别。
# Android 12 起很多 ROM 出厂就把 THP 关掉（省电、降内存放大），root 下能开回来。
THP_DIR=/sys/kernel/mm/transparent_hugepage
if [ -r "@DTHP_DIR/enabled" ]; then
    _thp=$(sed -n 's/.*\[\(.*\)\].*/\1/p' "@DTHP_DIR/enabled" 2>/dev/null)
    echo "[*] THP 主开关: @D{_thp:-未知}"
    if [ "@D_thp" != "always" ] && [ "@D_thp" != "madvise" ]; then
        if echo madvise > "@DTHP_DIR/enabled" 2>/dev/null; then
            echo "[*] THP 主开关: @D_thp → madvise（打开 2MB 折叠能力）"
        else
            echo "[!!] THP 主开关写不进去（当前 @D_thp）：2MB 折叠必然失败，VM 起不来。"
            echo "     这一项需要 root；若已经是 root 还写不进，说明内核编译时没开 THP。"
        fi
    fi
fi
# defrag：折叠时要能把散页迁移/压实，设成 never 时 MADV_COLLAPSE 会稳定 ENOMEM
if [ -w "@DTHP_DIR/defrag" ]; then
    _df=$(sed -n 's/.*\[\(.*\)\].*/\1/p' "@DTHP_DIR/defrag" 2>/dev/null)
    case "@D_df" in
        always|defer|madvise|defer+madvise) ;;
        *) echo always > "@DTHP_DIR/defrag" 2>/dev/null && echo "[*] THP defrag: @D{_df:-未知} → always" ;;
    esac
fi
# Android 14 / 6.6 内核的 mTHP 分档开关：2MB、1MB 两档要允许折叠
for _hf in "@DTHP_DIR"/hugepages-*/enabled; do
    [ -f "@D_hf" ] || continue
    _hm=$(sed -n 's/.*\[\(.*\)\].*/\1/p' "@D_hf" 2>/dev/null)
    case "@D_hf" in
        */hugepages-2048kB/*|*/hugepages-1024kB/*)
            if [ "@D_hm" != "always" ] && [ "@D_hm" != "madvise" ] && [ "@D_hm" != "inherit" ]; then
                echo madvise > "@D_hf" 2>/dev/null && \
                    echo "[*] mTHP @D(basename "@D(dirname "@D_hf")") : @D_hm → madvise"
            fi ;;
    esac
done

# 连续内存体检：buddy 的 order-9 就是 2MB 连续块的数量。
# 这个数是 0 的时候，无论内存给多大都凑不出 2MB，启动一定失败——先把这条看清，
# 就不用再反复试内存大小了。
# 注意：toybox 没有 awk（Android 默认没有），这里用 tr + sed 取列。
# buddyinfo 每行是 "Node 0, zone   Normal  1 2 3 ..."，前 4 列是标题，
# 第 5 列才是 order-0，所以 order-9 = 第 14 列、order-10 = 第 15 列。
ord9_count() {
    _o9s=0
    while read -r _o9l; do
        [ -n "@D_o9l" ] || continue
        _o9c=$(printf '%s\n' "@D_o9l" | tr -s ' ' '\n' | sed -n '14p')
        case "@D_o9c" in ''|*[!0-9]*) ;; *) _o9s=@D((_o9s + _o9c)) ;; esac
    done < /proc/buddyinfo 2>/dev/null
    echo "@D{_o9s:-0}"
}
_o9=$(ord9_count)
echo "[*] 连续内存: order-9(2MB)=@D_o9 块（碎片严重时会是 0）"

# ---------- 2.6 主动整理物理内存：把「重启手机」变成「多压几轮」 ----------
# 重启能解决，是因为开机后物理页是连续的一片；手机用久了，4KB 页把物理内存切碎，
# MADV_COLLAPSE 找不到对齐的 2MB 窗口 → 日志里 "2MB pass 0: 0 OK" → MEM_ACCEPT 0x9。
# 注意上面 2225 行那次 compact 是在 THP 还没打开时做的，等于白做；这里 THP 已经开好，
# 再压才有效。三件事：
#   ① drop_caches 3 —— 放掉 page cache / dentry / inode（刚读过几 GB 镜像时最有效）
#   ② compact_memory 多轮 —— 同步压实，一轮常常不够（要迁移可移动页，需要时间）
#   ③ 仍不够就 am kill-all —— 清掉可回收的后台进程，腾出成片物理页
if [ "@D_o9" -lt 96 ] 2>/dev/null; then
    echo "[*] 2MB 连续块偏少 (@D_o9 < 96)，开始深度整理（不用重启手机）..."
    # 允许压缩 mlock / unevictable 页：crosvm 借出区里的页默认迁不动，
    # 不放开这一项，compact 跑多少轮都是白跑。
    [ -w /proc/sys/vm/compact_unevictable_allowed ] && \
        echo 1 > /proc/sys/vm/compact_unevictable_allowed 2>/dev/null
    # 默认 extfrag_threshold=500 太保守：碎片一多，内核判定「压不动」直接放弃，
    # 降到 100 让它更愿意迁移页而不是原地失败。
    [ -w /proc/sys/vm/extfrag_threshold ] && \
        echo 100 > /proc/sys/vm/extfrag_threshold 2>/dev/null
    _prev=-1
    _r=0
    _n="@D_o9"
    while [ @D_r -lt 8 ]; do
        sync 2>/dev/null
        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
        echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
        # compact_memory 触发的迁移是异步的：kcompactd 要把可移动页搬到连续窗口，
        # 固定 sleep 太短会读到迁移完成前的旧 buddyinfo。拉长到 3 秒，且压满 8 轮——
        # 宁可启动前多等约 20 秒，也要把 order-9 尽量抬上去，避免「凑不出 2MB 只能重启」。
        sleep 3
        _r=@D((_r + 1))
        _n=$(ord9_count)
        echo "    第 @D_r 轮: order-9=@D_n 块"
        [ "@D_n" -ge 96 ] 2>/dev/null && break
        # 去掉「连续两轮不长就停」：compact 是异步的，读数抖动会误判，导致没压够就放弃。
        # 改为压满整 8 轮（达到 96 才提前停）——多等几十秒，换「不用重启手机」。
        _prev=@D_n
    done
    if [ "@D_n" -lt 24 ] 2>/dev/null; then
        echo "[!] 压实收益有限，清掉后台进程再压一轮..."
        am kill-all 2>/dev/null || true
        sleep 3
        sync 2>/dev/null
        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
        echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
        sleep 2
        _n=$(ord9_count)
        echo "    清理后: order-9=@D_n 块"
        # 给 kcompactd 一次完整收尾窗口（压实异步，最后再压一次并多等）：
        echo 1 > /proc/sys/vm/compact_memory 2>/dev/null; sleep 4; _n=$(ord9_count)
        echo "    收尾压实后: order-9=@D_n 块"
    fi
    if [ "@D_n" -lt 8 ] 2>/dev/null; then
        echo "[!!] 整理后只有 @D_n 块 2MB 连续内存，这次大概率凑不齐。"
        echo "     先别重启手机：本次会跳过 pseudo/without 直接走 none 再试一次；"
        echo "     仍失败就把 APP 从最近任务划掉、重新打开再点「启动」——"
        echo "     APP 退出会放掉自己占的 Java 堆和上次失败残留的 memfd，比重启快得多。"
        echo "     连做两次 order-9 都是 0~5，才是真要重启。"
    fi
fi
_avail=$(sed -n 's/^MemAvailable: *\([0-9]*\).*/\1/p' /proc/meminfo 2>/dev/null)
[ -n "@D_avail" ] && echo "[*] 宿主可用内存: @D((_avail / 1024)) MB"

# 上一次尝试失败后，RM 可能还攥着那块 parcel 没放：马上开下一组会
# GH_VM_START 直接 ENODEV。这里等大页模块报 active_vms=0（最多 12 秒）再继续。
wait_prev_gone() {
    _gp=$(ls -d /sys/module/*hugepage*/parameters 2>/dev/null | head -n1)
    [ -n "@D_gp" ] || { sleep 3; return 0; }
    _gn=0
    while [ @D_gn -lt 12 ]; do
        _ga=$(sed -n 's/.*active_vms=\([0-9]*\).*/\1/p' "@D_gp/refill_stat" 2>/dev/null | head -n1)
        [ "@D_ga" = "0" ] && break
        _gn=@D((_gn+1))
        sleep 1
    done
    [ "@D_ga" = "0" ] || echo "[!] 上一轮内存仍未释放 (active_vms=@D_ga)，继续尝试"
    # 上一组刚把 2MB 大页还回池，但物理内存往往还是碎的：开下一组前压实一次，
    # 比直接开成功率高得多（这是启动脚本「释放可用空间」职责的一部分）。
    echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
    sleep 2
}

# ---------- 7. 启动（依次试配置注入的各组合，成功即退出） ----------
# 教训: 曾自作主张把启动收敛成单组、锁定 none / pseudo、擅自折半 guest 内存，
#   结果每次只是把失败点从 GH_VM_START(ENODEV 19) 挪到 SHARE-BLOB(OOM 12)，
#   再挪到 MEM_ACCEPT(0x9) —— 全是 Gunyah RM / 大页池层的内核行为，脚本碰不到。
# 本版回到 APP 原本的设计：只按「启动配置」的组合依次尝试，失败清理后试下一组。
# 不再由脚本挑模式、不再改 --mem、不再硬编码任何一组。

FIRST=1
for PLAN in @DPLANS; do
    IDX=@D((IDX + 1))
    PM=@D(echo "@DPLAN" | cut -d: -f1)
    DP=@D(echo "@DPLAN" | cut -d: -f2)
    SL=@D(echo "@DPLAN" | cut -d: -f3)
    SN=@D(echo "@DPLAN" | cut -d: -f4)
    NT=@D(echo "@DPLAN" | cut -d: -f5)
    # build_cmd 在子 shell 里执行，SND/NET 传不回来；在主脚本设好供判活读取。
    SND="@DSN"; NET="@DNT"; CUR_NET="@DNT"
    [ "@DFIRST" = "1" ] || wait_prev_gone
    FIRST=0
    CMD=@D(build_cmd "@DPM" "@DDP" "@DSL" "@DSN" "@DNT")
    echo "[*] ===== 启动组 @DIDX/@DTOTAL: 保护=@DPM 显示=@DDP 声音=@DSN 网络=@DNT ====="
    echo "starting:@DIDX/@DTOTAL:@DPM/@DDP" > "@DSTATE" 2>/dev/null
    try_start "@DPM/@DDP" "@DCMD"
    RC=@D?
    [ "@DRC" = "0" ] && exit 0
    echo "[!] [@DPM/@DDP] 没起来，清理后试下一组..."
    kill_vm ""
done

echo "[!!] 启动失败：以上各组都没能起来。请把本日志全部内容发给开发者。"
REASON="未知原因"
if grep -aqE "refused MEM_ACCEPT|MEM_ACCEPT err=0x9|SHIM DIED" "@DBOOTLOG" 2>/dev/null; then
    REASON="Gunyah 资源管理器拒绝借出内存(MEM_ACCEPT err=0x9)"
    echo "[!!] 诊断: shim 去 ACCEPT 共享内存时被 RM 拒绝，属内核/大页池层，不是镜像/盘位/网络/参数问题。"
    echo "     ① 到「内核模块」页确认 gh-hugepage-reserve 已启用并加载、装机后重启过一次"
    echo "     ② 看日志「2MB pass 0」：若 2MB 全 failed、只靠 1MB 兜底，说明池没供上 2MB 页"
    echo "     ③ 换引导模式/crosvm 二进制后要重新确认大页池模块与它的 lend 布局匹配"
elif grep -aqE "GUNYAH-SHARE-BLOB.*Out of memory|SHARE-BLOB.*os error 12" "@DBOOTLOG" 2>/dev/null; then
    REASON="GUNYAH-SHARE-BLOB 阶段 Out of memory(os error 12)：guest RAM 的 2MB 大页没备齐"
    echo "[!!] 诊断: GH_VM_START 已过、LEND 已准备，倒在 SHARE-BLOB（内核/大页池层）。"
    echo "     先确认大页池模块已加载并重启过一次；guest 内存大小请到「启动配置」里调。"
elif grep -aqE "GH_VM_START failed|os error 19" "@DBOOTLOG" 2>/dev/null; then
    REASON="GH_VM_START 被 Gunyah 拒绝(ENODEV 19)：guest 内存未被预填充"
    echo "[!!] 诊断: crosvm 在 hypervisor 层退出。确认保护模式不是 none（none 不走 lend 预填充）。"
elif grep -aqE "2MB pass [0-9]+: 0 OK" "@DBOOTLOG" 2>/dev/null; then
    REASON="宿主凑不出 2MB 连续页：THP 关着 / mlock 受限 / 物理内存太碎"
    echo "[!!] 日志里「2MB pass 0: 0 OK, N failed」= 2MB 大页一个都没折叠成功。"
    echo "     ① THP 主开关是不是 never ② order-9 块数 ③ 大页池模块是否已加载并重启过"
    echo "[不用重启也能再试] 把本 APP 从最近任务划掉重开，再点一次「启动」。"
elif grep -aq "arg parsing failed" "@DBOOTLOG" 2>/dev/null; then
    REASON="本机 crosvm 不认识某个启动参数（arg parsing failed）"
    echo "[!!] 诊断: @DREASON"
    grep -a "arg parsing failed" "@DBOOTLOG" | tail -2
elif grep -aq "failed to write header" "@DBOOTLOG" 2>/dev/null; then
    REASON="qcow2 以只读方式打开，但镜像需要重建 refcount 表并回写 header(EBADF 9)"
    echo "[!!] 诊断: @DREASON"
    echo "[!!] 说明: 该盘请设为「可写」；本版本已对 qcow2 自动强制可写，若仍失败请用 qemu-img check 修复镜像"
elif grep -aq "open_disk_file failed" "@DBOOTLOG" 2>/dev/null; then
    REASON="镜像打不开（qcow2 格式/refcount 损坏，或文件不可写）"
    echo "[!!] 诊断: @DREASON"
elif grep -aq "failed to lock file" "@DBOOTLOG" 2>/dev/null; then
    REASON="镜像文件锁失败(FUSE): 镜像请放在内置存储而非外置/SD 卡"
    echo "[!!] 诊断: @DREASON"
elif grep -aq "找不到 UEFI 固件\|无效的固件\|not a valid" "@DBOOTLOG" 2>/dev/null; then
    REASON="UEFI 固件不可用: 请在启动配置里指定正确的 .fd"
    echo "[!!] 诊断: @DREASON"
fi
echo "failed|@DREASON" > "@DSTATE" 2>/dev/null
exit 1
"""
        return t.replace("@D", "$")
    }

    private val STOP_SHELL = """#!/system/bin/sh
# 注意: 本脚本自身路径在 .../gunyahvm/ 下, 所以绝不能用 pkill -f "gunyah"
#       (会把正在执行的本脚本一起 SIGKILL 掉 -> 上层拿到非 0 退出码 -> 误报「停止失败」)
SELF=@D@D
PARENT=@D{PPID:-0}
# 状态文件与脚本同目录 (privDir), 用 $0 反推, 避免脚本构造期引用 Context
STATE="@D(dirname "@D0")/vm.state"

kill_pat() {
    for pid in @D(pgrep -f "@D1" 2>/dev/null); do
        [ "@Dpid" = "@DSELF" ] && continue
        [ "@Dpid" = "@DPARENT" ] && continue
        kill -9 "@Dpid" 2>/dev/null
    done
}

# 先发 SIGTERM 让 crosvm 自己收尾：可写 qcow2 的 refcount / L2 表要落盘，
# 直接 SIGKILL 会让增量镜像处于不一致状态（下一次打开报 qcow 错误）。
kill_term() {
    for pid in @D(pgrep -f "@D1" 2>/dev/null); do
        [ "@Dpid" = "@DSELF" ] && continue
        [ "@Dpid" = "@DPARENT" ] && continue
        kill "@Dpid" 2>/dev/null
    done
}

echo "==> [1] 当前 crosvm 进程"
PIDOF1=@D(pidof crosvm 2>/dev/null; pidof crosvm-mmio 2>/dev/null)
[ -n "@DPIDOF1" ] && echo "    @DPIDOF1" || echo "    (无)"

echo "==> [2] 优雅终止 (SIGTERM) — 给可写 qcow2 留出落盘时间"
kill_term "/crosvm"
kill_term "crosvm run"
sleep 4

echo "==> [3] 强制终止 (SIGKILL)"
kill_pat "/crosvm"
kill_pat "crosvm run"
# pgrep 不可用时的兜底; "/crosvm" 不会命中本脚本 (脚本在 .../gunyahvm/ 下)
pkill -9 -f "/crosvm" 2>/dev/null
sleep 1

# 大页池是「按 VM 归还」回收的：退出后模块还要 3~10 秒才把页接回池。
# 不在这里等，用户立刻再点启动就会遇到池不够 -> 0x9 黑屏。
echo "==> [3.5] 等大页模块回收（最多 8 秒）"
HPP=$(ls -d /sys/module/*hugepage*/parameters 2>/dev/null | head -n1)
if [ -n "@DHPP" ] && [ -f "@DHPP/refill_stat" ]; then
    _n=0
    while [ @D_n -lt 8 ]; do
        _av=$(sed -n 's/.*active_vms=\([0-9]*\).*/\1/p' "@DHPP/refill_stat" 2>/dev/null | head -n1)
        [ "@D_av" = "0" ] && break
        _n=@D((_n+1))
        sleep 1
    done
    if [ "@D_av" = "0" ]; then
        echo "    大页已归还池（active_vms=0）"
    else
        echo "    ⚠️ 仍有 @D{_av:-?} 个 VM 占着大页，下次启动会自动补采"
    fi
fi

echo "==> [3.8] 拆掉 VM 网络（boot 脚本异常退出时兜底）"
pkill -f "gunydhcpd" 2>/dev/null
ip link set gunytap0 down 2>/dev/null
ip link del gunytap0 2>/dev/null
ip link set gunybr0 down 2>/dev/null
ip link del gunybr0 2>/dev/null
iptables -t nat -D POSTROUTING -s 192.168.100.0/24 ! -o gunybr0 -j MASQUERADE 2>/dev/null
iptables -D FORWARD -i gunybr0 -j ACCEPT 2>/dev/null
iptables -D FORWARD -o gunybr0 -j ACCEPT 2>/dev/null

echo "==> [4] 收尾校验"
LEFT=@D(pidof crosvm 2>/dev/null; pidof crosvm-mmio 2>/dev/null)
if [ -z "@DLEFT" ]; then
    echo "✅ crosvm 已全部停止"
    echo stopped > "@DSTATE" 2>/dev/null
    echo "==> done."
    exit 0
else
    echo "⚠️ 仍有残留: @DLEFT"
    exit 1
fi
""".replace("@D", "$")

    /**
     * 生成日志看护脚本。独立进程，只在「启动判定结束」之后才按体积轮转 boot.log。
     * 用 __XXX__ 占位符注入绝对路径（与项目原有的 @D → $ 约定不冲突）。
     */
    private fun buildLogGuard(ctx: Context): String = LOG_GUARD_SHELL
        .replace("__LOG__", File(privateDir(ctx), "boot.log").absolutePath)
        .replace("__TAIL__", File(privateDir(ctx), BootLog.ROTATED_TAIL).absolutePath)
        .replace("__STATE__", File(privateDir(ctx), "vm.state").absolutePath)
        .replace("__STOP__", File(privateDir(ctx), LOG_GUARD_STOP).absolutePath)
        .replace("__MAX__", LOG_GUARD_MAX.toString())
        .replace("__KEEP__", LOG_GUARD_KEEP.toString())
        .replace("@D", "$")

    private val LOG_GUARD_SHELL = """#!/system/bin/sh
# 日志体积看护 v2.0.22 —— 独立 root 进程，与 crosvm 生命周期解耦。
#
# 背景：guest 内核调试串口会持续打印不带换行的 '!'（实测约 164 万个/分钟），
#       boot.log 裸重定向会无限增长。
# 铁律：启动判定阶段绝不动文件！boot 脚本的 try_start 用 MARK=wc -c 记录字节偏移，
#       再用 tail -c +MARK+1 读本次增量判活；此时截断会让判定错位、误杀能启动的组合。
#       只在 vm.state=running 或日志出现「启动流程到此结束」之后才允许轮转。
# 前提：APP 以 >> (O_APPEND) 打开 boot.log，:> 截断后写指针自然回到文件头，
#       不产生稀疏空洞；启动脚本的判定与诊断完全不受影响。
LOG="__LOG__"
TAIL="__TAIL__"
STATE="__STATE__"
STOP="__STOP__"
MAX=__MAX__
KEEP=__KEEP__
READY=0
TICKS=0

while :; do
    sleep 5
    TICKS=@D((TICKS + 1))
    [ -f "@DSTOP" ] && exit 0                 # APP 点「停止」后写此标记
    [ "@DTICKS" -gt 1440 ] && exit 0          # 兜底：最长 2 小时
    ST=@D(cat "@DSTATE" 2>/dev/null)
    case "@DST" in failed*|stopped) exit 0 ;; esac
    [ -f "@DLOG" ] || continue

    if [ "@DREADY" = "0" ]; then
        case "@DST" in running) READY=1 ;; esac
        grep -q "启动流程到此结束" "@DLOG" 2>/dev/null && READY=1
        [ "@DREADY" = "1" ] || continue
    fi

    # 判定结束之后：VM 不在了就收工，避免残留进程
    if ! pidof crosvm >/dev/null 2>&1 && ! pidof crosvm-mmio >/dev/null 2>&1; then
        exit 0
    fi

    SZ=@D(wc -c < "@DLOG" 2>/dev/null | tr -d ' ')
    case "@DSZ" in ''|*[!0-9]*) continue ;; esac
    [ "@DSZ" -gt "@DMAX" ] || continue

    tail -c "@DKEEP" "@DLOG" > "@DTAIL" 2>/dev/null
    chmod 644 "@DTAIL" 2>/dev/null
    : > "@DLOG"
    echo "[log-guard] 日志超过 __MAX__ 字节，已轮转；上一轮尾部保留在 boot.log.tail" >> "@DLOG"
done
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
        val versionFolder: String, // 所在目录名
        val moduleName: String,   // 去掉 .ko 的模块名
        val kmi: String = "",     // 文件名推导出的 KMI，如 android15-6.6
    )

    /**
     * 模块名归一化：内核 /proc/modules 里「-」一律显示为「_」，
     * 且名字可能与 .ko 文件名不同，直接用文件名比对会永远「未加载」，
     * 再次 insmod 就报 "File exists"。
     */
    fun modKey(name: String): String = name.removeSuffix(".ko").replace('-', '_').lowercase()

    /**
     * 从 uname -r 解析 KMI（Kernel Module Interface）标签：
     * `6.6.118-android15-8-g1a2b3c` → `android15-6.6`
     * `5.10.209-android12-9-00001` → `android12-5.10`
     * 无 androidNN 标签时退化为 `6.6`。
     */
    fun kmiOf(kernel: String): String {
        if (kernel.isBlank()) return ""
        val majMin = kernel.split(".").take(2).joinToString(".")
        val tag = Regex("android\\d+", RegexOption.IGNORE_CASE).find(kernel)?.value?.lowercase()
        return if (tag.isNullOrBlank()) majMin else "$tag-$majMin"
    }

    /** 版本目录是否适配当前内核：如 kernel=6.6.118-android15-8 ↔ folder=android15-6.6 */
    fun versionMatches(kernel: String, folder: String): Boolean {
        if (kernel.isBlank()) return false
        if (folder.contains(kernel, ignoreCase = true)) return true
        val kmi = kmiOf(kernel)
        if (kmi.isNotBlank() && folder.equals(kmi, ignoreCase = true)) return true
        val majMin = kernel.split(".").take(2).joinToString(".")   // "6.6"
        val tag = kernel.split("-").getOrNull(1).orEmpty()          // "android15"
        return folder.contains(majMin) && (tag.isBlank() || folder.contains(tag))
    }

    /** 模块（.ko）是否适配当前内核：文件名即 KMI（android15-6.6.ko）时精确匹配；否则回退目录名匹配。 */
    fun moduleMatches(m: KoModule, kernel: String): Boolean {
        if (kernel.isBlank()) return false
        val kmi = kmiOf(kernel)
        if (m.kmi.isNotBlank() && kmi.isNotBlank() && m.kmi.equals(kmi, ignoreCase = true)) return true
        if (versionMatches(kernel, m.fileName.removeSuffix(".ko"))) return true
        return versionMatches(kernel, m.versionFolder)
    }

    /** 扫描 modules.ko 目录，按版本目录分组返回所有 .ko。 */
    fun scanModules(ctx: Context): List<KoModule> {
        val modDir = detectModulesDir(ctx) ?: return emptyList()
        val base = File(modDir)
        val out = mutableListOf<KoModule>()
        base.walkTopDown().forEach { f ->
            if (f.isFile && f.name.endsWith(".ko")) {
                val versionFolder = f.parentFile?.name ?: "unknown"
                val baseName = f.name.removeSuffix(".ko")
                // 文件名形如 android15-6.6 / android14-6.1 → 提取 KMI；否则留空
                val kmi = if (baseName.startsWith("android", ignoreCase = true)) baseName.lowercase() else ""
                out.add(
                    KoModule(
                        path = f.absolutePath,
                        fileName = f.name,
                        versionFolder = versionFolder,
                        moduleName = baseName,
                        kmi = kmi,
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

    /**
     * 模块是否已加载。
     *
     * 大页模块（gh_hugepage_reserve）的文件名是 KMI（android15-6.6.ko），真名却是
     * gh_hugepage_reserve —— 所有 KMI 版本共用同一个真名，所以**不能**只看
     * `ls /sys/module` 里有没有 hugepage 目录：那样 6 个版本会全部显示「已加载」，用户会以为
     * 自己装了一堆模块（之前的误报就是这么来的）。
     *
     * 正确判断：只有「本机 KMI 对应的那一份」才可能是当前加载的那个。
     * 其他版本一律「未加载 / 非本机」，并且禁止 insmod（加载错版本直接内核 panic）。
     */
    fun isLoaded(m: KoModule, loaded: Set<String>, kernel: String = ""): Boolean {
        val k = kernel.ifBlank { detectKernelVersion() }
        if (m.versionFolder.contains("hugepage", true) || m.fileName.contains("hugepage", true)) {
            return moduleMatches(m, k) && hugepageLoaded()
        }
        return loaded.contains(modKey(m.moduleName))
    }

    /**
     * 这份 .ko 是不是给本机内核编的（决定「加载」按钮能不能点）。
     *
     * 判断顺序：
     *  1. 文件名本身就是 KMI（android15-6.6.ko）→ 必须精确等于本机 KMI；
     *  2. 所在目录是 KMI 目录（android15-6.6/）→ 按目录匹配；
     *  3. 目录没有任何版本信息（平铺在 modules.ko 根下）→ 无法判断，放行，
     *     由 [insmod] 的路径校验兜底。
     */
    fun isNativeModule(m: KoModule, kernel: String = ""): Boolean {
        val k = kernel.ifBlank { detectKernelVersion() }
        if (k.isBlank()) return false
        if (m.kmi.isNotBlank()) return m.kmi.equals(kmiOf(k), true)
        val folder = m.versionFolder
        return if (folder.matches(Regex("android\\d+-\\d+\\.\\d+"))) {
            folder.equals(kmiOf(k), true)
        } else {
            true
        }
    }

    /** insmod 中转目录：部分 ROM 的 su 上下文读不了 APP 私有目录，先拷到 /data/local/tmp 再加载 */
    private const val KO_RELAY = "/data/local/tmp/gunyahvm_ko"

    /**
     * 加载内核模块。返回 (是否成功, 失败原因)。
     * 先把 .ko root 拷贝到 /data/local/tmp 中转目录再 insmod；
     * 已加载或返回 File exists（重复加载）都视为成功。
     */
    fun insmod(path: String): Pair<Boolean, String> {
        val f = File(path)
        // ★ 硬闸门：KMI 不匹配的 .ko 一律不许 insmod。
        // 不同 android 版本的内核模块 ABI 完全不同，强行加载会直接内核 oops/panic，
        // 表现就是手机蓝屏、自动重启 —— 比“模块没加载”严重得多。
        val kmi = kmiOf(detectKernelVersion())
        val base = f.name.removeSuffix(".ko")
        if (kmi.isNotBlank() && base.matches(Regex("android\\d+-\\d+\\.\\d+"))) {
            if (!base.equals(kmi, true)) {
                return false to "已拦截：$base 是给别的内核编的（本机 $kmi）。加载错版本会导致内核崩溃/蓝屏。"
            }
        }
        if (kmi.isNotBlank()) {
            val folder = f.parentFile?.name.orEmpty()
            if (folder.matches(Regex("android\\d+-\\d+\\.\\d+")) && !folder.equals(kmi, true)) {
                return false to "已拦截：$folder 目录不是本机内核（$kmi），加载会蓝屏。"
            }
        }
        if (modKey(f.name) in loadedModules()) return true to "already"
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

    /**
     * APP 启动时静默补齐 Gunyah 依赖的内核模块。
     *
     * Android 没有 init.d，也没有 Magisk 模块接管；手机一重启 /proc/modules 就是空的，
     * gunyah-host-share 不回来 → /dev/gunyah 不存在 → crosvm 起不来。
     * 这里按当前 KMI 把 modules.ko/<kmi>/ 下的依赖顺手加载一遍（已加载的自动跳过，
     * 所以反复调用无副作用）。
     *
     * @return 本次新加载的模块文件名（空 = 已齐全 / 无模块包 / 无 root）
     */
    fun autoLoadModules(ctx: Context): List<String> {
        val kernel = detectKernelVersion()
        val kmi = kmiOf(kernel)
        if (kmi.isBlank()) return emptyList()
        val modDir = detectModulesDir(ctx) ?: return emptyList()
        val dir = File(modDir, kmi)

        val loaded = loadedModules().toMutableSet()
        val out = mutableListOf<String>()
        // 顺序不能乱：host-share 暴露 /dev/gunyah，必须最先。
        // 目录不存在也要往下走：有些模块包不按 KMI 建目录，直接按模块名分（gh_hugepage_reserve/），
        // 早退会让大页模块永远补不上。
        if (dir.isDirectory) {
            for (prefix in listOf("gunyah-host-share", "gh-unmovable", "nproc-guard", "udmabuf")) {
                val f = dir.listFiles()?.firstOrNull {
                    it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".ko")
                } ?: continue
                // gunyah-host-share-gki-6.6 → gunyah_host_share
                val key = modKey(f.name).replace(Regex("_gki.*$"), "")
                if (loaded.contains(key)) continue
                val (ok, _) = insmod(f.absolutePath)
                if (ok) {
                    loaded.add(key)
                    out.add(f.name)
                }
            }
        }
        // 运行内存适配（gh_hugepage_reserve）大页池：改由 Magisk 模块在开机时统一预留，
        // APP 不再加载/卸载它，避免与模块池抢同一个模块名导致冲突（见「工具链 → 安装大页池模块」）。
        return out
    }

    // ==================== 运行内存适配（GH-Hugepage-Reserve） ====================
    //
    // 模块不是「占多少 MB」的静态预留池，而是一个按需供给池（2MB folio 供给钩子）：
    //   · 目标量 pool_want 由 VM 实际借走的内存决定，crosvm 每次 LEND 后模块自动补池；
    //   · 池不够时 MADV_COLLAPSE 会 ENOMEM，stage-2 建不起来 → MEM_ACCEPT err=0x9；
    //   · 唯一正确的干预方式是 `echo 3 > parameters/acquire` 触发采集，而不是写某个 *mb 参数。
    // 觀測点全部在 /sys/module/<name>/parameters/refill_stat，键值如下：
    //   pool_avail / pool_want（页，1 页 = 2MB）、active_vms、acquire_active、acquire_stop_reason

    /** 模块参数目录（模块真名未必等于文件名，按 *hugepage* 模糊匹配）。 */
    private fun hugepageSysDir(): String? {
        val r = RootShell.exec("ls -d /sys/module/*hugepage*/parameters 2>/dev/null", timeoutMs = 8000)
        return r.stdout.lines().map { it.trim() }
            .firstOrNull { it.isNotBlank() && it.contains("hugepage") }
    }

    /** 旧调用点兼容：返回模块根目录（不带 /parameters）。 */
    private fun hugepageModDir(): String? = hugepageSysDir()?.removeSuffix("/parameters")

    /** 工具链里是否已有适配当前内核的模块（不加载，仅匹配）。 */
    fun resolveHugepageModule(ctx: Context, kernel: String = detectKernelVersion()): KoModule? {
        val list = scanModules(ctx).filter { it.versionFolder.contains("hugepage", true) || it.fileName.contains("hugepage", true) }
        val kmi = kmiOf(kernel)
        return list.firstOrNull { m -> m.kmi.isNotBlank() && kmi.isNotBlank() && m.kmi.equals(kmi, true) }
            ?: list.firstOrNull { moduleMatches(it, kernel) }
    }

    /** 运行内存适配模块是否已加载：优先看 sysfs（模块真名未必等于文件名），再退回 /proc/modules。 */
    fun hugepageLoaded(): Boolean {
        if (hugepageSysDir() != null) return true
        return loadedModules().any { it.contains("hugepage") }
    }

    /** 读取模块参数目录下的 name=value（用于展示）。 */
    fun hugepageParams(): Map<String, String> {
        val sys = hugepageSysDir() ?: return emptyMap()
        val r = RootShell.exec("ls $sys 2>/dev/null", timeoutMs = 8000)
        val names = r.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (n in names.take(24)) {
            val v = RootShell.exec("cat $sys/$n 2>/dev/null", timeoutMs = 6000)
            out[n] = v.stdout.trim().lines().firstOrNull().orEmpty()
        }
        return out
    }

    /**
     * 读 refill_stat（模块自报的池状态）。键：pool_avail / pool_want / active_vms /
     * acquire_active / acquire_stop_reason / state / served / tracked。
     * 未加载或读不到时返回空 map。
     */
    fun hugepageStat(): Map<String, String> {
        val sys = hugepageSysDir() ?: return emptyMap()
        val r = RootShell.exec("cat $sys/refill_stat 2>/dev/null", timeoutMs = 8000)
        if (r.stdout.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        Regex("([A-Za-z_]+)=([^\\s]+)").findAll(r.stdout).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2].trim()
        }
        return out
    }

    /** 运行内存适配状态快照（模块页 / 主界面展示用）。 */
    data class HugepageState(
        val kernel: String,
        val kmi: String,
        val loaded: Boolean,
        val koPath: String,
        val koName: String,
        val poolAvailPages: Int,
        val poolWantPages: Int,
        val activeVms: Int,
        val acquireActive: Boolean,
        val stopReason: String,
        val sysReserveMB: Int,
        /** 宿主物理内存（MB，-1 = 读不到） */
        val hostTotalMB: Int = -1,
        val hostAvailMB: Int = -1,
    ) {
        val reserveMB: Int get() = if (poolAvailPages >= 0) poolAvailPages * 2 else -1
        /** 池是否够本次启动（want 为 0 表示还没有 VM 来过，按够处理）。 */
        val poolReady: Boolean get() = loaded && poolAvailPages >= 0 && poolAvailPages >= poolWantPages
        /** 池够了但物理内存依然紧张：这个才是「用久了起不来」的真凶。 */
        fun memTight(vmMB: Int): Boolean =
            hostAvailMB >= 0 && vmMB > 0 && hostAvailMB < vmMB + 768
    }

    /** 宿主物理内存（MB）。读不到时各字段为 -1。 */
    data class HostMem(val totalMB: Int, val availMB: Int, val freeMB: Int)

    fun hostMem(): HostMem {
        val r = RootShell.exec("cat /proc/meminfo 2>/dev/null", timeoutMs = 6000)
        fun kb(key: String): Int =
            Regex("^" + key + ":\\s+(\\d+)", RegexOption.MULTILINE)
                .find(r.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        fun mb(v: Int) = if (v < 0) -1 else v / 1024
        return HostMem(mb(kb("MemTotal")), mb(kb("MemAvailable")), mb(kb("MemFree")))
    }

    fun hugepageState(ctx: Context): HugepageState {
        val kernel = detectKernelVersion()
        val kmi = kmiOf(kernel)
        val mod = resolveHugepageModule(ctx, kernel)
        val loaded = hugepageLoaded()
        val mem = hostMem()
        val st = if (loaded) hugepageStat() else emptyMap()
        fun num(k: String) = st[k]?.toIntOrNull() ?: -1
        return HugepageState(
            kernel = kernel,
            kmi = kmi,
            loaded = loaded,
            koPath = mod?.path.orEmpty(),
            koName = mod?.fileName.orEmpty(),
            poolAvailPages = num("pool_avail"),
            poolWantPages = num("pool_want"),
            activeVms = num("active_vms"),
            acquireActive = st["acquire_active"] == "1",
            stopReason = st["acquire_stop_reason"].orEmpty(),
            sysReserveMB = hugepageSysDir()?.let { sys ->
                RootShell.exec("cat $sys/system_reserve_mb 2>/dev/null", timeoutMs = 6000)
                    .stdout.trim().toIntOrNull()
            } ?: -1,
            hostTotalMB = mem.totalMB,
            hostAvailMB = mem.availMB,
        )
    }

    /**
     * 触发大页采集（echo mode > parameters/acquire），并等到 acquire_active 归零。
     * mode：0 中断 / 1 仅连续块 / 2 扫描+系统回收 / 3 扫描+逐窗驱逐（最彻底，默认）。
     * 返回 (是否拿到足够的大页, 说明)。
     */
    fun hugepageAcquire(mode: Int = 3, waitSec: Int = 90): Pair<Boolean, String> {
        val sys = hugepageSysDir() ?: return false to "模块未加载"
        if (mode <= 0) return false to "采集模式为 0（只读）"

        fun once(sec: Int): Map<String, String>? {
            val w = RootShell.exec("echo $mode > $sys/acquire 2>&1", timeoutMs = 10000)
            if (!w.success && w.stderr.contains("Busy", true)) return null
            var last: Map<String, String> = emptyMap()
            for (i in 0 until sec) {
                last = hugepageStat()
                if (last.isEmpty()) return null
                if (last["acquire_active"] != "1") break
                Thread.sleep(1000)
            }
            return last
        }

        // 采集前先压实物理内存：碎片化才是「手机用久了采不出来」的主因，
        // 模块自己只能扫空闲块，而碎片要靠 compact 才拼得回连续 2MB。
        RootShell.exec("echo 1 > /proc/sys/vm/compact_memory 2>/dev/null", timeoutMs = 20000)
        var last = once(waitSec) ?: return false to "采集已在进行中，或读不到 refill_stat"
        var avail = last["pool_avail"]?.toIntOrNull() ?: -1
        var want = last["pool_want"]?.toIntOrNull() ?: -1
        if (avail >= 0 && want >= 0 && avail >= want) {
            return true to "池已够用：${avail * 2} MB / 需要 ${want * 2} MB"
        }

        // 第二轮：回收页缓存后再压实一次。后台吃内存时这一步决定成败。
        RootShell.exec(
            "sync; echo 1 > /proc/sys/vm/drop_caches; echo 1 > /proc/sys/vm/compact_memory 2>/dev/null",
            timeoutMs = 40000,
        )
        Thread.sleep(1000)
        last = once((waitSec / 2).coerceAtLeast(30)) ?: last
        avail = last["pool_avail"]?.toIntOrNull() ?: avail
        want = last["pool_want"]?.toIntOrNull() ?: want
        val reason = last["acquire_stop_reason"].orEmpty()
        val mem = hostMem()
        val hint = if (mem.availMB in 0..(want * 2 + 768)) {
            "；宿主仅剩 ${mem.availMB} MB 可用，请调低 VM 内存或关掉后台"
        } else ""
        return if (avail >= 0 && want >= 0 && avail >= want) {
            true to "池已够用：${avail * 2} MB / 需要 ${want * 2} MB"
        } else {
            false to "采集后仍不足：${avail * 2} MB / 需要 ${want * 2} MB" +
                (if (reason.isNotBlank()) "（停止原因：$reason）" else "") + hint
        }
    }

    /** 写 system_reserve_mb：留给 Android 的内存，池最大只吃到 RAM 减它。0 / 负数 = 不改。 */
    private fun applySystemReserve(mb: Int): String {
        if (mb <= 0) return ""
        val sys = hugepageSysDir() ?: return ""
        if (!File("$sys/system_reserve_mb").exists()) return ""
        RootShell.exec("echo $mb > $sys/system_reserve_mb 2>/dev/null", timeoutMs = 8000)
        val now = hugepageParams()["system_reserve_mb"]
        return if (now != null) "系统保留 ${now} MB" else ""
    }

    /**
     * 加载运行内存适配模块（自动选 ko）→ 写系统保留 → 池不足则采集。
     * 已加载时只做「系统保留 + 采集」，不会盲目重载（重载会丢池）。
     * 返回 (是否成功, 说明文案)。
     */
    fun loadHugepageReserve(ctx: Context, reserveMB: Int = 0, acquireMode: Int = 3): Pair<Boolean, String> {
        val kernel = detectKernelVersion()
        val kmi = kmiOf(kernel)
        val notes = mutableListOf<String>()
        if (!hugepageLoaded()) {
            val mod = resolveHugepageModule(ctx, kernel)
                ?: return false to "工具链内没有适配 $kmi 的模块（modules.ko 下缺少对应版本），请先在「工具链」页更新内核模块包"
            // resolve 的兜底可能挑到别的版本：这里必须再确认一次，错版本 insmod = 蓝屏
            if (!isNativeModule(mod, kernel)) {
                return false to "只找到 ${mod.fileName}（不是本机 $kmi），不能加载 —— 加载会蓝屏。请更新工具链里的 $kmi 模块包"
            }
            val (ok, err) = insmod(mod.path)
            if (!ok) return false to err
            Thread.sleep(500)
            if (!hugepageLoaded()) return false to "insmod 返回成功但模块未出现在 /sys/module（内核拒载，检查 dmesg）"
            notes.add("已加载 ${mod.fileName}")
        }
        applySystemReserve(reserveMB).takeIf { it.isNotBlank() }?.let { notes.add(it) }
        val st = hugepageStat()
        val avail = st["pool_avail"]?.toIntOrNull() ?: -1
        val want = st["pool_want"]?.toIntOrNull() ?: -1
        if (avail >= 0 && want > 0 && avail < want) {
            val (ok, msg) = hugepageAcquire(acquireMode)
            notes.add(msg)
            if (!ok) return false to notes.joinToString("；")
        } else if (avail >= 0) {
            notes.add("池 ${avail * 2} MB / 需要 ${if (want > 0) want * 2 else avail * 2} MB")
        }
        return true to notes.joinToString("；").ifBlank { "模块已加载" }
    }

    /** 卸载运行内存适配模块（还有 VM 在跑时会被拒绝，属正常）。 */
    fun unloadHugepageReserve(): Pair<Boolean, String> {
        // 虚拟机还在跑时卸载：crosvm 的内存还挂在这个模块的 2MB folio 供给钩子上，
        // rmmod 会直接内核崩溃（蓝屏）。这是硬拦截，不给“确认继续”。
        if (isVmRunning()) {
            return false to "虚拟机还在运行：先点「停止」，等 crosvm 退出后再卸载（运行中卸载会导致内核崩溃）"
        }
        val names = loadedModules().filter { it.contains("hugepage") }
        val candidates = (listOf("gh_hugepage_reserve") + names).distinct()
        var lastErr = ""
        for (n in candidates) {
            val (ok, err) = rmmod(n)
            if (ok || !hugepageLoaded()) return true to ""
            lastErr = err
        }
        return false to lastErr.ifBlank { "rmmod 失败（模块可能仍被占用）" }
    }

    // ==================== gh-hugepage-reserve Magisk 模块管理（搬自 Droid-VM APP 设置页） ====================
    //
    // 这套接口管的是「已刷入系统 /data/adb/modules/gh-hugepage-reserve 的 Magisk 模块本体」，
    // 与上面 resolveHugepageModule / hugepageLoaded 不同 —— 后者管的是 APP 工具链目录里还没加载的
    // *.ko 文件，本段管的是装好并开机自启的完整模块（含 settings.prop / load.sh / disable 文件）。
    // 它复用同一个 /sys/module/gh_hugepage_reserve sysfs（hugepageStat / hugepageAcquire），
    // 让「内核模块」界面可以像 Droid-VM 设置页那样直接管理该模块：启停、改池容量、重新加载、采集、卸载。

    const val HP_MODULE_ID = "gh-hugepage-reserve"
    const val HP_MODULE_DIR = "/data/adb/modules/gh-hugepage-reserve"
    const val HP_MODULE_DIR_UPDATE = "/data/adb/modules_update/gh-hugepage-reserve"
    const val HP_SETTINGS = "$HP_MODULE_DIR/settings.prop"

    /** 已刷入系统的 gh-hugepage-reserve 模块信息快照（内核模块界面管理卡用）。 */
    data class HpModuleInfo(
        val installed: Boolean = false,
        val enabled: Boolean = false,   // 不存在 disable 文件 = 启用
        val loaded: Boolean = false,     // /sys/module/gh_hugepage_reserve 存在
        val version: String = "",
        val versionCode: Int = 0,
        val koKmi: String = "",          // 实际 insmod 的 .ko 内核版本
        val poolWantMB: Int = 0,         // settings.prop -> pool_want（目标池容量，MB）
        val poolAvailMB: Int = -1,       // 当前池实测（pages*2），-1 = 读不到
        val cmaWith: Boolean = false,    // pool_want_with_cma == 1（开 CMA 蓄水池）
        val cmaLever: String = "",       // cma_movable_lever（hook|flag|""）
        val bootAcquire: Int = 0,        // boot_acquire 0-3
    )

    fun hpModuleInstalled(): Boolean =
        RootShell.exec("ls -d '$HP_MODULE_DIR' >/dev/null 2>&1 && echo Y || echo N", timeoutMs = 8000)
            .stdout.contains("Y")

    fun hpModuleInfo(): HpModuleInfo {
        if (!hpModuleInstalled()) return HpModuleInfo()
        val prop = RootShell.exec("cat '$HP_MODULE_DIR/module.prop' 2>/dev/null", timeoutMs = 8000).stdout
        fun prop(key: String) =
            Regex("^$key=(.*)$", RegexOption.MULTILINE).find(prop)?.groupValues?.get(1)?.trim().orEmpty()
        val disabled = RootShell.exec(
            "ls '$HP_MODULE_DIR/disable' >/dev/null 2>&1 && echo Y || echo N", timeoutMs = 6000,
        ).stdout.contains("Y")
        val settings = readHpSettings()
        val pool = (settings["pool_want"] ?: settings["pool_target"]).orEmpty().toIntOrNull() ?: 0
        val stat = if (hugepageLoaded()) hugepageStat() else emptyMap()
        val availPages = stat["pool_avail"]?.toIntOrNull() ?: -1
        val ko = RootShell.exec(
            "ls '$HP_MODULE_DIR'/*.ko 2>/dev/null | head -n1 | sed 's#.*/##;s#\\.ko##'", timeoutMs = 6000,
        ).stdout.trim()
        return HpModuleInfo(
            installed = true,
            enabled = !disabled,
            loaded = hugepageLoaded(),
            version = prop("version"),
            versionCode = prop("versionCode").toIntOrNull() ?: 0,
            koKmi = ko,
            poolWantMB = pool,
            poolAvailMB = if (availPages >= 0) availPages * 2 else -1,
            cmaWith = (settings["pool_want_with_cma"] ?: "0") == "1",
            cmaLever = (settings["cma_movable_lever"] ?: "").trim(),
            bootAcquire = (settings["boot_acquire"] ?: "0").toIntOrNull() ?: 0,
        )
    }

    /** 读取 settings.prop（KEY=VALUE 行，忽略注释与空行）。 */
    fun readHpSettings(): Map<String, String> {
        val r = RootShell.exec("cat '$HP_SETTINGS' 2>/dev/null", timeoutMs = 8000)
        val out = linkedMapOf<String, String>()
        r.stdout.lines().forEach { line ->
            val t = line.trim()
            val i = t.indexOf('=')
            if (i > 0 && !t.startsWith("#")) out[t.substring(0, i).trim()] = t.substring(i + 1).trim()
        }
        return out
    }

    /** 更新 settings.prop 里某个键（保留其它键），写入模块目录。 */
    fun writeHpSetting(key: String, value: String): Pair<Boolean, String> {
        val cur = readHpSettings().toMutableMap()
        cur[key] = value
        val content = cur.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
        // 经 /data/local/tmp 中转：APP 可写、root 可读，避免直接在 su 里拼 heredoc 引号地狱。
        val tmp = "/data/local/tmp/hp_settings_${System.currentTimeMillis()}.prop"
        val w = RootShell.exec("echo ${content.toHexEscaped()} > '$tmp' 2>/dev/null && echo OK", timeoutMs = 8000)
        if (!w.stdout.contains("OK")) return false to "写入临时文件失败"
        val r = RootShell.exec(
            "cp '$tmp' '$HP_SETTINGS' && chmod 644 '$HP_SETTINGS' && rm -f '$tmp' && echo OK",
            timeoutMs = 8000,
        )
        return (r.stdout.contains("OK")) to if (r.stdout.contains("OK")) "" else "写回 settings.prop 失败"
    }

    /** 字符串 -> 单引号转义后拼成可被 shell echo 的字面量（仅 ASCII 安全）。 */
    private fun String.toHexEscaped(): String {
        val sb = StringBuilder("'")
        for (c in this) {
            when (c) {
                '\'' -> sb.append("'\\''")
                '\\' -> sb.append("\\\\")
                else -> sb.append(c)
            }
        }
        sb.append("'")
        return sb.toString()
    }

    /** 启用/停用模块（对标 Droid-VM 设置里的 Enable）。
     *  停用 = 建 disable 文件 + rmmod（立即生效，且下次开机不再自启）；
     *  启用 = 删 disable 文件 + 运行 load.sh 重新 insmod（立即生效）。 */
    fun hpModuleSetEnabled(enabled: Boolean): Pair<Boolean, String> {
        val r = if (enabled) {
            RootShell.exec(
                "rm -f '$HP_MODULE_DIR/disable'; sh '$HP_MODULE_DIR/load.sh' >/dev/null 2>&1; echo RC=\$?",
                timeoutMs = 45000,
            )
        } else {
            RootShell.exec(
                "touch '$HP_MODULE_DIR/disable'; rmmod gh_hugepage_reserve 2>/dev/null; echo RC=\$?",
                timeoutMs = 20000,
            )
        }
        val ok = if (enabled) hugepageLoaded() else !hugepageLoaded()
        return ok to (if (ok) "" else r.stdout.lines().takeLast(3).joinToString(" "))
    }

    /** 运行 load.sh 重新加载（对标 Droid-VM 运行时「重新应用」）：按当前 settings.prop 重新 insmod。
     *  forceReinsmod=true 时先 rmmod 再 insmod，使修改后的 pool_want 等参数立即生效；
     *  注意 rmmod 会清掉整个大页池，VM 正在跑时会崩溃，调用方须先确认 isVmRunning() 为否。 */
    fun hpModuleReload(forceReinsmod: Boolean = false): Pair<Boolean, String> {
        val pre = if (forceReinsmod && hugepageLoaded()) {
            "rmmod gh_hugepage_reserve 2>/dev/null; "
        } else ""
        val r = RootShell.exec("${pre}sh '$HP_MODULE_DIR/load.sh' 2>&1; echo RC=\$?", timeoutMs = 60000)
        val tail = r.stdout.lines().filter { it.isNotBlank() }.takeLast(5).joinToString("\n").trim()
        return (hugepageLoaded()) to tail
    }

    /** 卸载已刷入的模块（含 modules_update 副本）。VM 正在跑时拒绝，否则会丢池 / 蓝屏。 */
    fun hpModuleUninstall(): Pair<Boolean, String> {
        if (isVmRunning()) {
            return false to "虚拟机正在运行：先点「停止」，等 crosvm 完全退出后再卸载（运行中卸载会丢池并可能内核崩溃）"
        }
        val r = RootShell.exec(
            "rm -rf '$HP_MODULE_DIR' '$HP_MODULE_DIR_UPDATE'; ls -d '$HP_MODULE_DIR' >/dev/null 2>&1 && echo STILL || echo OK",
            timeoutMs = 15000,
        )
        return (r.stdout.contains("OK")) to if (r.stdout.contains("OK")) "" else "卸载失败"
    }

    /** 启动：生成脚本并以 root 执行。返回 (started, log)。 */
    fun start(ctx: Context, cfg: Config): Pair<Boolean, String> {
        // v2.0.10：原生显示的 4 路输入 socket 由主程序 root 服务托管（不再用 touchd/TCP）。
        // crosvm 只在启动那一刻 connect 一次，故必须在 exec crosvm **之前**让 root 服务
        // bind+listen 好 4 个 socket。start() 是唯一启动入口且已在后台线程，这里同步等
        // listener 就绪（最多 9s，脚本 4.7 段还有一轮轮询兜底），顺序由代码保证。
        if (cfg.displayBackend == "android") {
            val ready = InputServiceClient.ensureListeningAndWait(ctx)
            Log.i("GunyahManager", "input listener ready before boot: $ready")
        }
        val script = buildBootScript(ctx, cfg) ?: return false to "[!!] 未检测到工具链 crosvm，请先下载"
        val log = File(privateDir(ctx), "boot.log")
        val tail = File(privateDir(ctx), BootLog.ROTATED_TAIL)
        val guard = File(privateDir(ctx), LOG_GUARD_SCRIPT)
        val guardStop = File(privateDir(ctx), LOG_GUARD_STOP)
        // 先清空：自动进入显示器的逻辑靠匹配日志里的「VNC 已监听」，
        // 上一次启动留下的这行会让它在新机还没起来时就跳转。
        runCatching { log.writeText("") }
        runCatching { tail.delete() }
        runCatching { guardStop.delete() }
        // 投放日志看护脚本（独立进程，只在启动判定结束后按体积轮转）
        runCatching {
            guard.writeText(buildLogGuard(ctx))
            guard.setExecutable(true, false)
        }
        // setsid + 后台 + O_APPEND 重定向：
        //  ① 脱离 su 会话常驻，不被 RootShell 超时等待杀掉；
        //  ② >> 带 O_APPEND，日志看护用 :> 截断后写指针自然回到文件头，
        //     不产生稀疏空洞；启动脚本的 MARK / tail -c +N 判定一字节不受影响。
        val cmd = "setsid sh \"${script.absolutePath}\" >> \"${log.absolutePath}\" 2>&1 < /dev/null &"
        val r = RootShell.exec(cmd, timeoutMs = 8000)
        if (r.code != 0) return false to "启动指令下发失败（code=${r.code}）：${r.stderr}"
        // 看护进程与 VM 同时后台常驻；它自己会等「启动流程到此结束」才动手
        runCatching {
            RootShell.exec(
                "setsid sh \"${guard.absolutePath}\" >/dev/null 2>&1 < /dev/null &",
                timeoutMs = 5000,
            )
        }
        // 脚本有单实例锁：上一次启动还在跑时它会直接退出。这里读一下日志确认，
        // 否则用户会以为「启动成功」，实际什么都不会发生。
        Thread.sleep(700)
        val head = runCatching { if (log.exists()) log.readText().take(800) else "" }.getOrDefault("")
        if (head.contains("已有一个启动流程在跑")) {
            return false to "上一次启动还在进行中（或未完全退出）。请先点「停止」，等 3 秒再启动。"
        }
        return true to "已下发启动指令（日志: ${log.absolutePath}）"
    }

    /** 当前 crosvm 是否在运行（PCI 版或 MMIO 版）。 */
    fun isVmRunning(): Boolean {
        // 两个进程名都查。末尾 true 是必须的：
        // 分号连接时退出码取最后一条，PCI 模式下 pidof crosvm-mmio 返回 1，
        // 会把「前面已经拿到了 crosvm 的 PID」整体判成失败。
        val r = RootShell.exec(
            "pidof crosvm 2>/dev/null; pidof crosvm-mmio 2>/dev/null; true",
            timeoutMs = 8000
        )
        return r.stdout.trim().isNotEmpty()
    }

    /**
     * 停止虚拟机。以「crosvm 进程是否真的消失」为准，而不是脚本退出码
     * （旧实现脚本 pkill -f gunyah 会把自己杀掉，退出码非 0 → 明明停了却提示停止失败）。
     */
    fun stop(ctx: Context): Pair<Boolean, String> {
        TouchBridge.stop()
        // 通知日志看护进程收工（它每 5 秒检查一次该标记）
        runCatching { File(privateDir(ctx), LOG_GUARD_STOP).createNewFile() }
        val script = buildStopScript(ctx)
        val r = RootShell.exec("sh \"${script.absolutePath}\"", timeoutMs = 32000)
        if (!isVmRunning()) {
            runCatching { File(privateDir(ctx), "vm.state").writeText("stopped") }
            return true to r.stdout
        }
        // 兜底: 脚本没搞定就按可执行文件路径再杀一遍
        repeat(3) {
            RootShell.exec("pkill -9 -f '/crosvm'", timeoutMs = 6000)
            Thread.sleep(600)
            if (!isVmRunning()) {
                runCatching { File(privateDir(ctx), "vm.state").writeText("stopped") }
                return true to r.stdout
            }
        }
        return false to r.stdout
    }

    /** 读取 vm.state: 返回 "running" / "starting:i/n:标签" / "stopped" / "failed|原因" / ""(未知)。 */
    fun vmState(ctx: Context): String = runCatching {
        File(privateDir(ctx), "vm.state").readText().trim()
    }.getOrDefault("")

    /**
     * 解析后的 VM 状态。
     *
     * 为什么需要它：启动脚本是「一组参数失败就换下一组」的监督重试链，每组失败都会
     * pkill crosvm 再等 3~14 秒才起下一组。界面如果只看 pidof（进程在/不在），就会在
     * 3 秒轮询里反复跳「未运行 ↔ crosvm 运行中」，看起来像启动命令在反复重启，
     * 实际只是脚本在换参数组合。所以状态以 vm.state 为准，pidof 只做辅助。
     */
    data class VmStatus(
        val kind: String,          // running / starting / failed / stopped / unknown
        val idx: Int = 0,          // 当前第几组
        val total: Int = 0,        // 总组数
        val label: String = "",    // 组标签，如 pseudo/simplefb
        val reason: String = "",   // 失败原因
        /** 状态文件长时间没更新：脚本多半已被系统杀掉，不能一直显示「启动中」 */
        val stale: Boolean = false,
    ) {
        val isBooting: Boolean get() = kind == "starting" && !stale
    }

    /** 脚本实际使用的 VNC 端口（5905 被占用时会顺延）；没有则 null。 */
    fun actualVncPort(ctx: Context): Int? = runCatching {
        File(File(privateDir(ctx), "work"), "vnc.port").readText().trim().toIntOrNull()
            ?.takeIf { it in 1024..65535 }
    }.getOrNull()

    fun vmStatus(ctx: Context): VmStatus {
        val f = File(privateDir(ctx), "vm.state")
        val raw = runCatching { if (f.exists()) f.readText().trim() else "" }.getOrDefault("")
        if (raw.startsWith("failed|")) return VmStatus("failed", reason = raw.removePrefix("failed|"))
        // v2.0.19：固件/帧缓冲刚就绪的早期状态，APP 据此立刻进显示器（不等 14s 判活）。
        if (raw == "uefi") return VmStatus("uefi")
        if (raw == "running") return VmStatus("running")
        if (raw == "stopped") return VmStatus("stopped")
        if (raw.startsWith("starting")) {
            // starting:<idx>/<total>:<label>
            val parts = raw.split(":", limit = 3)
            val num = parts.getOrNull(1).orEmpty().split("/")
            val idx = num.getOrNull(0)?.toIntOrNull() ?: 0
            val total = num.getOrNull(1)?.toIntOrNull() ?: 0
            val label = parts.getOrNull(2).orEmpty()
            // 单组最长约 45 秒（6s 粗检 + 8s 等 VNC + 30s 守护）+ 12s 等内存释放；
            // 超过 8 分钟没动静 = 脚本已经没了，别继续显示「启动中」。
            val age = runCatching { System.currentTimeMillis() - f.lastModified() }.getOrDefault(0L)
            return VmStatus("starting", idx, total, label, stale = age > 8 * 60_000L)
        }
        return VmStatus("unknown")
    }
}
