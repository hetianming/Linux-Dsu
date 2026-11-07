package com.mcai.ubuntudsu

import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.GunTheme

/** ② 启动配置 独立页面：每个参数可改，底部保存。显示器入口统一放在主界面操作坞。 */
class GunyahBootConfigActivity : GunyahSubActivity() {
    private lateinit var cfg: GunyahManager.Config

    override fun pageTitle() = "启动配置"

    override fun buildContent(parent: LinearLayout) {
        cfg = GunyahManager.loadConfig(this)
        val c = card("② 启动配置")

        val memEdit = editRow("内存 MEM(MB)", cfg.memMB.toString(), InputType.TYPE_CLASS_NUMBER, c)
        c.addView(hintText("Windows / Debian 建议 3072~4096；XNU 需要 4096。2MB 大页池已由「工具链」页的大页内存模块在开机时预留，一般不需要手动降档；若仍报 MEM_ACCEPT 0x9，多为手机用久后物理内存碎片化，重启后趁内存干净再启动即可。"))
        val cpuEdit = editRow("CPU 核心数", cfg.cpus.toString(), InputType.TYPE_CLASS_NUMBER, c)
        val nameEdit = editRow("实例名称", cfg.name, parent = c)
        // 保护模式：auto 自动回退（pseudo→without→none，GPU 开启时含 gpu 组合）
        val protEdit = editRow("保护模式", cfg.prot, parent = c)
        c.addView(hintText("auto=自动回退链（推荐）| pseudo | without | none | gpu"))
        // ---- 启动顺序：从光盘还是硬盘引导 ----
        val bootOptions = listOf(
            "自动（固件决定）" to "auto",
            "优先光盘（装系统 / 进 PE）" to "cdrom",
            "优先硬盘（进已装好的系统）" to "disk",
        )
        selectorRow("启动顺序", bootOptions, cfg.bootOrder.ifBlank { "auto" }, { cfg.bootOrder = it }, c)
        c.addView(hintText("「优先光盘」把光盘排到 bootindex=1，并临时使用一份出厂 NVRAM（不动你自己的 vars.fd），固件会当成新机器重新枚举设备、先扫光盘；「优先硬盘」反过来。改完要点保存再启动。"))
        // ---- LEND 内存的 mTHP 策略：选错必 MEM_ACCEPT 0x9 ----
        val mthpOptions = listOf(
            "自动（按 SoC 判断）" to "auto",
            "single（eager-parcel / sm8650）" to "single",
            "chunked（demand-paging / sm8750）" to "chunked",
        )
        selectorRow("mTHP 策略", mthpOptions, cfg.mthpMode.ifBlank { "auto" }, { cfg.mthpMode = it }, c)
        c.addView(hintText("决定 crosvm 怎么把借出内存折叠成 2MB 大页。选错会 100% 报 MEM_ACCEPT 0x9（启动几秒后自动退出）。自动模式按 ro.board.platform 判断，并会在后半段兼容模式里自动换另一种兜底。"))
        // ---- MMIO 引导：给 Darwin/XNU 这类没有 PCI 宿主驱动的 guest 用 ----
        c.addView(toggleRow("MMIO 引导（Darwin / XNU）", cfg.mmio) { cfg.mmio = it })
        c.addView(hintText("只切传输方式，crosvm 是同一份（APP 内置自编版，PCI + MMIO 双模）：关闭＝设备走 PCI，Windows / Debian 用这个；打开＝块设备走 --virtio-mmio-block，输入/GPU/网卡挂 virtio-mmio，macOS / XNU 用这个。切换后直接重启虚拟机即可，不需要重装任何东西"))
        // 触摸输入设备（--input 注入，留空不注入）
        val inputEdit = editRow("输入设备(可选)", cfg.inputDev, parent = c)
        c.addView(hintText("如 /dev/input/event4（手机触摸屏直触），留空则不注入"))
        // ---- 手机式触摸：纯 VNC 层翻译，任何 guest 都能用 ----
        val avncPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        c.addView(toggleRow("手机式触摸（推荐）", avncPrefs.getBoolean("phone_touch_mode", true)) {
            avncPrefs.edit().putBoolean("phone_touch_mode", it).apply()
        })
        c.addView(hintText("单指点一下=左键、单指上下滑=滚轮、长按=右键、双指滑=快速滚动、双指捏=缩放画面，指针点哪到哪。走 VNC 协议，Windows / Linux / macOS 全部可用，不需要 guest 装任何驱动"))
        // ---- 平板模式：APP 的触摸事件直接进 guest 的多点触摸屏 ----
        c.addView(toggleRow("平板模式（virtio 触摸直通）", cfg.touchPassthrough) { cfg.touchPassthrough = it })
        c.addView(hintText("手指事件绕过 VNC 直接注入 guest 的 virtio-input 多点触摸屏，guest 收到的是真正的触摸事件（能双指缩放网页、能惯性滚动）。只有 Linux 内核自带该驱动；Windows 需装 vioinput，macOS 不支持。链路没建立时会自动回退到上面的手机式触摸，不会把操作弄坏"))
        // ---- UEFI 固件：默认用工具链自带，可指定任意 .fd ----
        c.addView(TextView(this).apply {
            text = "UEFI 固件"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(GunTheme.ACCENT)
            setPadding(0, GunTheme.dp(12, d), 0, GunTheme.dp(2, d))
        })
        val fwEdit = fileRow("自定义固件", cfg.firmwarePath, c, "选择 UEFI 固件 (.fd)") { p ->
            cfg.firmwarePath = p
        }
        c.addView(hintText("任意 UEFI 固件（edk2-gunyah.fd / edk2-qcpeek.fd / uefi-*.fd）。留空=用工具链自带的 bin/edk2-gunyah.fd"))
        val varsEdit = fileRow("vars.fd", cfg.varsPath, c, "选择变量固件 (.fd)") { p ->
            cfg.varsPath = p
        }
        c.addView(hintText("NVRAM 变量固件（--pflash）。留空=用工具链自带的 edk2-gunyah.vars.fd；换固件后建议一并换对应的 vars.fd"))
        c.addView(toggleRow("GPU 加速", cfg.gpu) { cfg.gpu = it })
        // ---- 显示参数：VNC 黑屏（只有鼠标）时把显示模式切到 simplefb ----
        val dispOptions = listOf(
            "simplefb（推荐·UEFI 直出）" to "simplefb",
            "GPU 2D（virtio-gpu）" to "gpu2d",
            "GPU 3D（vulkan）" to "gpu3d",
            "自定义 GPU 参数" to "custom",
        )
        selectorRow("显示模式", dispOptions, cfg.displayMode, { cfg.displayMode = it }, c)
        c.addView(hintText("黑屏只有鼠标 = VNC 抓到的是空 GPU surface；simplefb 走 EDK2 帧缓冲，固件阶段必有画面"))
        val resOptions = listOf(
            "自动（跟随手机屏幕，原生显示推荐）" to "auto",
            "720 × 1584（手机竖屏）" to "720x1584",
            "1080 × 1920" to "1080x1920",
            "1080 × 2400" to "1080x2400",
            "1280 × 720（横屏）" to "1280x720",
            "1920 × 1080（横屏）" to "1920x1080",
        )
        selectorRow("分辨率", resOptions, cfg.resolution, { cfg.resolution = it }, c)
        val resEdit = editRow("自定义分辨率", "", parent = c)
        c.addView(hintText("格式 宽x高（如 720x1584），留空则用上面的预设"))
        val gpuEdit = editRow("GPU 参数", cfg.gpuArg, parent = c)
        c.addView(hintText("仅「自定义 GPU 参数」模式生效；其余模式按分辨率自动生成"))
        // ---- v1.9.5 显示后端 / 网络回归 / MMIO 声音 ----
        val backendOptions = listOf(
            "VNC（内置 AVNC，通用）" to "vnc",
            "Android 原生显示（主程序内置）" to "android",
        )
        selectorRow("显示后端", backendOptions, cfg.displayBackend, { cfg.displayBackend = it }, c)
        c.addView(hintText("选「Android 原生显示」时，PCI 模式会强制使用 UBC 工具链里那份 AOSP crosvm（只有它编了 android_display 后端）。v2.0 起画面和输入全部由主程序自己完成：启动脚本以 root 先起好 4 路输入桥，内置显示器页面直接把 Surface 交给 crosvm，不需要再装任何独立显示器 APP。顺序在脚本里写死，不受「先开哪个页面」影响；crosvm 不支持该参数或输入桥未就绪时自动回退内置 VNC，不会把虚拟机弄挂，也不会留下「有画面、什么都点不了」的死局。MMIO（iOS 启动盘）仍走 VNC。"))
        c.addView(toggleRow("PCI 模式用工具链 crosvm（修网络）", cfg.pciUseToolchainCrosvm) { cfg.pciUseToolchainCrosvm = it })
        c.addView(hintText("v34 起 PCI 也改用 APP 内置自编 crosvm-mmio 后出现网络回归（tap / virtio-net 行为差异）。打开 = 回到之前网络正常的 AOSP 工具链版；关闭 = 两种模式都用自编版。iOS 启动盘的 MMIO 模式不受影响，始终用自编版。"))
        c.addView(toggleRow("MMIO 声音（virtio-mmio-snd）", cfg.mmioSound) { cfg.mmioSound = it })
        c.addView(hintText("Darwin / XNU guest 没有 PCI 宿主驱动，打开后把 virtio-snd 也挂到 virtio-mmio 传输，guest 才能枚举到声卡。"))
        c.addView(toggleRow("VNC 服务", cfg.vnc) { cfg.vnc = it })
        c.addView(toggleRow("启动后自动进入 VNC 全屏", cfg.autoDisplay) { cfg.autoDisplay = it })
        c.addView(hintText("点「启动」后，一旦 boot.log 出现「VNC 已监听」就直接进 AVNC 全屏——UEFI / 开机动画从第一帧就能看到。关掉则停在主界面，手动点「显示器」"))
        c.addView(toggleRow("网络（virtio-net）", cfg.network) { cfg.network = it })
        c.addView(hintText("启动前自动建 tap + bridge，跑内置 DHCP 并做 NAT。客户机仍需装 virtio-net 驱动（Windows 精简版请挂 virtio-win.iso）"))
        c.addView(toggleRow("声音（virtio-snd）", cfg.audio) { cfg.audio = it })
        c.addView(hintText("用本 APP 的 uid 打开 AAudio（Android 会静音 root 的音频）；crosvm 没链真实 libaaudio 时会自动跳过，不影响启动"))
        val hostEdit = editRow("VNC 主机", cfg.vncHost, parent = c)
        val portEdit = editRow("VNC 端口", cfg.vncPort.toString(), InputType.TYPE_CLASS_NUMBER, c)
        val capEdit = editRow("VNC_CAP(可选)", cfg.vncCap, parent = c)

        fun collect() {
            cfg.memMB = memEdit.text.toString().toIntOrNull() ?: cfg.memMB
            cfg.cpus = cpuEdit.text.toString().toIntOrNull() ?: cfg.cpus
            cfg.name = nameEdit.text.toString().ifBlank { "unixnu" }
            cfg.prot = protEdit.text.toString().ifBlank { "auto" }
            cfg.inputDev = inputEdit.text.toString().trim()
            cfg.firmwarePath = fwEdit.text.toString().trim()
            cfg.varsPath = varsEdit.text.toString().trim()
            cfg.gpuArg = gpuEdit.text.toString()
            cfg.resolution = resEdit.text.toString().trim().ifBlank { cfg.resolution }
            cfg.vncHost = hostEdit.text.toString().ifBlank { "127.0.0.1" }
            cfg.vncPort = portEdit.text.toString().toIntOrNull() ?: cfg.vncPort
            cfg.vncCap = capEdit.text.toString()
        }

        parent.addView(c)
        // 记录 collect 供保存用
        this.collectFn = ::collect
    }

    private var collectFn: (() -> Unit)? = null

    override fun buildActionBar(): LinearLayout? {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(GunTheme.dp(12, d), GunTheme.dp(8, d), GunTheme.dp(12, d), GunTheme.dp(12, d))
        }
        bar.addView(actionButton("💾  保存配置", GunTheme.ACCENT) {
            collectFn?.invoke()
            GunyahManager.saveConfig(this, cfg)
            saveAndGoHome("配置已保存")
        })
        return bar
    }
}
