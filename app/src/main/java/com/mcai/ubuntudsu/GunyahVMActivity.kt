package com.mcai.ubuntudsu

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.GunTheme
import java.io.File
import kotlin.concurrent.thread

/**
 * GunyaniOS 主页面（多功能界面）：
 * 顶部仅标题（不挂徽章）+ 环境检测 2 列网格 + 四入口卡片（右侧实时状态徽章）+ 底部操作坞。
 * 每个功能区（工具链 / 启动配置 / 镜像映射 / 内核模块）都是独立子页面，点卡片进入。
 */
class GunyahVMActivity : HapticAppCompatActivity() {
    private val d: Float get() = resources.displayMetrics.density

    private lateinit var cardToolchain: LinearLayout
    private lateinit var cardConfig: LinearLayout
    private lateinit var cardImage: LinearLayout
    private lateinit var cardModule: LinearLayout

    // 环境检测网格项
    private lateinit var envKernel: TextView
    private lateinit var envSoc: TextView
    private lateinit var envMem: TextView
    private lateinit var envGunyah: TextView
    private lateinit var envModules: TextView
    private lateinit var envVm: TextView

    /** 上次点「启动」的时间：挡住连点/双击造成的两份启动脚本 */
    private var lastStartAt = 0L

    /** 启动方式 chip（PCI / MMIO），onResume 时按磁盘配置重绘 */
    private var chipPci: TextView? = null
    private var chipMmio: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GunTheme.applyTheme(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.background = GunTheme.background()

        // 顶栏：仅返回 + 标题
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(14, d), GunTheme.dp(12, d), GunTheme.dp(14, d), GunTheme.dp(8, d))
        }
        bar.addView(backButton())
        bar.addView(TextView(this).apply {
            text = "GunyaniOS"; textSize = 21f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT)
            setPadding(GunTheme.dp(10, d), 0, 0, 0)
        })
        root.addView(bar)

        // 滚动内容
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(GunTheme.dp(14, d), GunTheme.dp(2, d), GunTheme.dp(14, d), GunTheme.dp(12, d))
        }
        buildEnvCard(content)
        cardToolchain = entryCard(content, "🔗", "① 工具链", "crosvm · edk2 固件 · libc++ · 内核模块包") {
            open(GunyahToolchainActivity::class.java)
        }
        cardConfig = entryCard(content, "⚙", "② 启动配置", "内存 / CPU / 保护模式 / GPU / VNC") {
            open(GunyahBootConfigActivity::class.java)
        }
        cardImage = entryCard(content, "💾", "③ 镜像映射", "启动盘 / 光盘 / 系统盘 / 数据盘 · raw / qcow2 可写挂载") {
            open(GunyahImageActivity::class.java)
        }
        cardModule = entryCard(content, "🧩", "④ 内核模块", "大页内存模块（开机预留 2MB 大页池）") {
            open(GunyahModuleActivity::class.java)
        }
        scroll.addView(content)
        root.addView(scroll)

        // 启动方式（双模式）：PCI 走已调通的 virtio-pci（Windows / Debian），
        // MMIO 走 virtio-mmio（macOS / XNU 这类 guest 没有 PCI 宿主驱动）。
        val modeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(GunTheme.dp(14, d), GunTheme.dp(6, d), GunTheme.dp(14, d), 0)
        }
        val pChip = modeChip("PCI · Win/Debian")
        val mChip = modeChip("MMIO · macOS/XNU")
        chipPci = pChip
        chipMmio = mChip
        pChip.setOnClickListener {
            GunyahManager.setMmio(this, false)
            paintMode()
            Toast.makeText(this, "启动方式：PCI（Windows / Debian）", Toast.LENGTH_SHORT).show()
        }
        mChip.setOnClickListener {
            GunyahManager.setMmio(this, true)
            paintMode()
            Toast.makeText(this, "启动方式：MMIO（macOS / XNU）", Toast.LENGTH_SHORT).show()
        }

        modeBar.addView(pChip)
        modeBar.addView(dockSpace())
        modeBar.addView(mChip)
        root.addView(modeBar)
        paintMode()

        // 底部操作坞：启动 / 停止 / 显示器 / 日志（统一纯文字，等宽）
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(14, d), GunTheme.dp(8, d), GunTheme.dp(14, d), GunTheme.dp(14, d))
        }
        dock.addView(dockButton("启动", primary = true) { doStart() })
        dock.addView(dockSpace())
        dock.addView(dockButton("停止") { doStop() })
        dock.addView(dockSpace())
        dock.addView(dockButton("显示器") { openDisplay() })
        dock.addView(dockSpace())
        dock.addView(dockButton("日志") { showLog() })
        root.addView(dock)
        setContentView(root)

        com.mcai.ubuntudsu.ui.Ui.enableEdgeToEdge(this, root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun backButton(): TextView = TextView(this).apply {
        text = "‹"; textSize = 24f
        setTextColor(GunTheme.TEXT2)
        gravity = Gravity.CENTER
        background = GunTheme.iconTile(d, GunTheme.CHIP, 12f)
        layoutParams = LinearLayout.LayoutParams(GunTheme.dp(34, d), GunTheme.dp(34, d))
        setOnClickListener { finish() }
    }

    private fun dockSpace(): LinearLayout = LinearLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(GunTheme.dp(8, d), 1)
    }

    private fun dockButton(text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (primary) GunTheme.ON_ACCENT else GunTheme.TEXT)
            gravity = Gravity.CENTER
            background = if (primary) GunTheme.primaryButton(d) else GunTheme.iconTile(d, GunTheme.CHIP, 15f)
            setPadding(GunTheme.dp(6, d), GunTheme.dp(13, d), GunTheme.dp(6, d), GunTheme.dp(13, d))
            // 四按钮等宽统一
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
            GunTheme.pressAnimation(this)
        }

    /** 按磁盘上的真实配置重绘启动方式 chip，避免界面状态与配置脱节。 */
    private fun paintMode() {
        val mmio = GunyahManager.loadConfig(this).mmio
        chipPci?.apply {
            setTextColor(if (mmio) GunTheme.TEXT2 else GunTheme.ON_ACCENT)
            background =
                if (mmio) GunTheme.iconTile(d, GunTheme.CHIP, 15f) else GunTheme.primaryButton(d)
        }
        chipMmio?.apply {
            setTextColor(if (mmio) GunTheme.ON_ACCENT else GunTheme.TEXT2)
            background =
                if (mmio) GunTheme.primaryButton(d) else GunTheme.iconTile(d, GunTheme.CHIP, 15f)
        }
    }

    /** 启动方式选择 chip（PCI / MMIO 互斥，选中态由 paintMode 绘制）。 */
    private fun modeChip(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(GunTheme.dp(6, d), GunTheme.dp(9, d), GunTheme.dp(6, d), GunTheme.dp(9, d))
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        GunTheme.pressAnimation(this)
    }

    private fun <T : AppCompatActivity> open(cls: Class<T>) {
        startActivity(Intent(this, cls))
        overridePendingTransition(R.anim.zoom_out, R.anim.zoom_in)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }

    /** 入口卡片：左图标块 + 标题/副标题，右状态徽章 + 箭头。返回容器供刷新状态。 */
    private fun entryCard(
        parent: LinearLayout,
        icon: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GunTheme.card(16f, d)
            setPadding(GunTheme.dp(12, d), GunTheme.dp(12, d), GunTheme.dp(12, d), GunTheme.dp(12, d))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = GunTheme.dp(10, d)
            }
            setOnClickListener { onClick() }
            GunTheme.pressAnimation(this)
        }
        // 左：图标块
        c.addView(TextView(this).apply {
            text = icon; textSize = 17f
            gravity = Gravity.CENTER
            background = GunTheme.iconTile(d, GunTheme.CARD_ALT, 12f)
            layoutParams = LinearLayout.LayoutParams(GunTheme.dp(38, d), GunTheme.dp(38, d))
        })
        // 中：标题 + 副标题
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = GunTheme.dp(11, d); marginEnd = GunTheme.dp(6, d)
            }
            addView(TextView(this@GunyahVMActivity).apply {
                text = title; textSize = 14f; setTypeface(null, Typeface.BOLD)
                setTextColor(GunTheme.TEXT)
            })
            addView(TextView(this@GunyahVMActivity).apply {
                text = subtitle; textSize = 10f
                setTextColor(GunTheme.MUTED)
                setPadding(0, GunTheme.dp(2, d), 0, 0)
            })
        })
        // 右：状态徽章
        c.addView(TextView(this).apply {
            id = R.id.gunyah_card_state
            text = "…"; textSize = 10f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.ACCENT)
            gravity = Gravity.CENTER
            background = GunTheme.pill(d)
            setPadding(GunTheme.dp(9, d), GunTheme.dp(4, d), GunTheme.dp(9, d), GunTheme.dp(4, d))
        })
        c.addView(TextView(this).apply {
            text = "›"; textSize = 16f
            setTextColor(GunTheme.MUTED)
            setPadding(GunTheme.dp(4, d), 0, 0, 0)
        })
        parent.addView(c)
        return c
    }

    private fun stateView(card: LinearLayout): TextView = card.findViewById(R.id.gunyah_card_state)

    // ------------------------- 环境检测卡片（2 列网格） -------------------------

    private fun buildEnvCard(parent: LinearLayout) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GunTheme.card(16f, d)
            setPadding(GunTheme.dp(12, d), GunTheme.dp(11, d), GunTheme.dp(12, d), GunTheme.dp(11, d))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = GunTheme.dp(10, d)
            }
        }
        c.addView(TextView(this).apply {
            text = "虚拟机环境检测"; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT)
            setPadding(0, 0, 0, GunTheme.dp(8, d))
        })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        envKernel = envCell(row1, "内核版本")
        envSoc = envCell(row1, "处理器")
        envMem = envCell(row2, "物理内存")
        envGunyah = envCell(row2, "gunyah 设备")
        envModules = envCell(row3, "内核模块")
        envVm = envCell(row3, "虚拟机")
        grid.addView(row1); grid.addView(row2); grid.addView(row3)
        c.addView(grid)
        parent.addView(c)
    }

    private fun envCell(row: LinearLayout, label: String): TextView {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GunTheme.cardAlt(11f, d)
            setPadding(GunTheme.dp(9, d), GunTheme.dp(7, d), GunTheme.dp(9, d), GunTheme.dp(7, d))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginEnd = GunTheme.dp(6, d); bottomMargin = GunTheme.dp(6, d)
            }
        }
        cell.addView(TextView(this).apply {
            text = label; textSize = 9f
            setTextColor(GunTheme.MUTED)
        })
        val v = TextView(this).apply {
            text = "检测中…"; textSize = 11f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT)
            setPadding(0, GunTheme.dp(2, d), 0, 0)
        }
        cell.addView(v)
        row.addView(cell)
        return v
    }

    private fun setEnv(v: TextView?, text: String, color: Int? = null) {
        if (v == null || !::envKernel.isInitialized) return
        v.text = text
        if (color != null) v.setTextColor(color)
    }

    private fun refreshEnv() {
        thread {
            val k = GunyahManager.detectKernelVersion()
            runOnUiThread { setEnv(envKernel, k.ifBlank { "-" }) }
        }
        thread {
            val soc = GunyahManager.socInfo()
            runOnUiThread { setEnv(envSoc, soc) }
        }
        thread {
            val mem = GunyahManager.totalMemGB()
            runOnUiThread { setEnv(envMem, mem) }
        }
        thread {
            val ok = GunyahManager.gunyahDevReady()
            runOnUiThread {
                setEnv(envGunyah, if (ok) "可用 ✓" else "未检测到 ✗",
                    if (ok) GunTheme.ACCENT else GunTheme.DANGER)
            }
        }
        thread {
            val modules = GunyahManager.scanModules(this)
            val loaded = GunyahManager.loadedModules()
            val hpSt = GunyahManager.hugepageState(this)
            val memCfg = GunyahManager.loadConfig(this)
            // 只数「本机 KMI」的模块，别的版本躺在目录里不算（也不算已加载）
            val native = modules.filter { GunyahManager.isNativeModule(it, hpSt.kernel) }
            val n = native.count { GunyahManager.isLoaded(it, loaded, hpSt.kernel) }
            runOnUiThread {
                when {
                    hpSt.poolReady -> setEnv(envModules, "大页池已就绪 ✓", GunTheme.ACCENT)
                    hpSt.loaded -> setEnv(envModules, "大页模块已加载", GunTheme.TEXT2)
                    hpSt.memTight(memCfg.memMB) -> setEnv(envModules, "宿主内存偏紧", GunTheme.WARN)
                    modules.isEmpty() -> setEnv(envModules, "未检测模块", GunTheme.TEXT2)
                    else -> setEnv(envModules, "$n / ${native.size} 本机模块",
                        if (n > 0) GunTheme.ACCENT else GunTheme.TEXT2)
                }
            }
        }
    }

    // ------------------------- 显示器（内嵌 AVNC） -------------------------

    /**
     * 显示器：v2.0.7 起【直接进显示器】，不再经过 GunyahDisplayActivity 预览页
     * （那个「进入桌面」中间页）。点一下就去画面（UEFI/GRUB 引导界面），
     * 原生显示自动兜底 VNC，不用多一次点击。
     */
    private fun openDisplay() {
        com.mcai.ubuntudsu.core.DisplayLauncher.enter(this)
    }

    // ------------------------- 日志 -------------------------

    private fun showLog() {
        thread {
            val log = File(GunyahManager.privateDir(this), "boot.log")
            val text = if (log.exists()) log.readText().takeLast(8000)
            else "暂无日志（先启动一次虚拟机）"
            runOnUiThread {
                val dlg = AlertDialog.Builder(this)
                    .setTitle("启动日志 boot.log")
                    .setMessage(text)
                    .setPositiveButton("关闭", null)
                    .setNegativeButton("复制") { _, _ ->
                        runCatching {
                            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("boot.log", text))
                        }
                        Toast.makeText(this, "日志已复制，可直接粘贴发送", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("清空") { _, _ ->
                        runCatching { log.writeText("") }
                        Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
                    }
                    .show()
                com.mcai.ubuntudsu.ui.Ui.styleDialog(this, dlg)
            }
        }
    }

    // ------------------------- 动作 -------------------------

    private fun doStart() {
        if (!GunyahManager.isToolchainReady(this)) {
            Toast.makeText(this, "请先在「工具链」页下载", Toast.LENGTH_SHORT).show()
            open(GunyahToolchainActivity::class.java)
            return
        }
        // 连点会下发出两份启动脚本。两份脚本都带「失败就换下一组」的重试链，
        // 而且每轮重试都会 pkill -9 crosvm —— 互相杀对方刚起来的 crosvm，
        // 表现就是虚拟机反复重启、VNC 端口开开关关（连上就是 Connection refused）。
        val now = System.currentTimeMillis()
        if (now - lastStartAt < 8000) {
            Toast.makeText(this, "正在启动中，请稍候…（重复点击会互相打断启动）", Toast.LENGTH_SHORT).show()
            return
        }
        lastStartAt = now
        val cfg = GunyahManager.loadConfig(this)
        Toast.makeText(
            this,
            if (cfg.mmio) "正在启动（MMIO / virtio-mmio）…" else "正在启动（PCI / virtio-pci）…",
            Toast.LENGTH_SHORT
        ).show()
        thread {
            // 已在跑就别再下发（会在脚本里变成两份重试链互相杀进程）。
            // 「启动中」也要拦：脚本正在换参数组合重试，crosvm 可能刚好不在，
            // 但启动流程还活着 —— 这时再下发只会被单实例锁挡掉。
            val st0 = GunyahManager.vmStatus(this)
            if (GunyahManager.isVmRunning() || st0.isBooting) {
                runOnUiThread {
                    if (st0.isBooting) {
                        Toast.makeText(this, "启动流程还在进行中，打开控制台看进度", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, GunyahBootActivity::class.java))
                    } else {
                        Toast.makeText(this, "虚拟机已在运行，如需重启用「停止」再启动", Toast.LENGTH_SHORT).show()
                    }
                }
                return@thread
            }
            // 内存吃紧时先给个明确提示：否则用户只会看到"起不来"，不知道是 4096 开太大
            val mem = GunyahManager.hostMem()
            if (mem.availMB in 0..(cfg.memMB + 768)) {
                val tip = if (cfg.memAutoFit)
                    "可用内存 ${mem.availMB} MB，本次 VM 内存会自动降档（启动日志有具体值）"
                else
                    "可用内存仅 ${mem.availMB} MB，本次 ${cfg.memMB} MB 很可能起不来（可打开自动降档）"
                runOnUiThread { Toast.makeText(this, tip, Toast.LENGTH_LONG).show() }
            }
            val (ok, log) = GunyahManager.start(this, cfg)
            runOnUiThread {
                if (ok) {
                    // 直接进启动控制台：日志实时滚动，VNC 端口真通了才自动切到画面。
                    // 这样「启动中」的每一秒都有反馈，也不会再跳进假 VNC 黑屏。
                    startActivity(Intent(this, GunyahBootActivity::class.java))
                } else {
                    Toast.makeText(this, "启动失败：$log", Toast.LENGTH_LONG).show()
                    // 被单实例锁挡住 = 上一次启动还在跑，这时候日志最有用
                    if (log.contains("还在进行中")) {
                        startActivity(Intent(this, GunyahBootActivity::class.java))
                    }
                }
                refreshStates()
            }
        }
    }

    private fun doStop() {
        thread {
            val wasRunning = GunyahManager.isVmRunning()
            val (ok, _) = GunyahManager.stop(this)
            val stillRunning = GunyahManager.isVmRunning()
            runOnUiThread {
                val msg = when {
                    !stillRunning && wasRunning -> "已停止"
                    !stillRunning -> "虚拟机未在运行"
                    ok -> "已发送停止指令"
                    else -> "停止失败：crosvm 仍在运行，请稍后重试"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                refreshStates()
            }
        }
    }

    // ------------------------- 状态刷新（卡片徽章 + VM 实时轮询） -------------------------

    /** VM 状态轮询：每 3 秒检测一次 crosvm 进程，界面停留期间实时更新（不再需要离开再回来）。 */
    private val vmPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 上一次已提示过的失败原因，避免每 3 秒弹一次 Toast */
    private var lastFailReason: String = ""
    /** pidof 连续探测失败次数：crosvm 刚 fork / su 偶发超时都会漏一次，不能立刻判死 */
    private var vmMissCount = 0

    private val vmPollTask = object : Runnable {
        override fun run() {
            thread {
                // 以 vm.state 为准，pidof 只做辅助。
                // 只用 pidof 的话，脚本「失败 → pkill → 等 3~14 秒 → 起下一组」这段
                // 空窗会被读成「未运行」，下一组起来又变成「运行中」，每 3 秒反复跳。
                val st = GunyahManager.vmStatus(this@GunyahVMActivity)
                val running = GunyahManager.isVmRunning()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when {
                        st.kind == "failed" -> {
                            vmMissCount = 0
                            setEnv(envVm, "启动失败", GunTheme.DANGER)
                            val reason = st.reason
                            if (reason.isNotBlank() && reason != lastFailReason) {
                                lastFailReason = reason
                                Toast.makeText(this@GunyahVMActivity, "启动失败：$reason", Toast.LENGTH_LONG).show()
                            }
                        }
                        st.isBooting -> {
                            vmMissCount = 0
                            setEnv(
                                envVm,
                                if (st.total > 0) "兼容 ${st.idx}/${st.total}" else "启动中…",
                                GunTheme.WARN
                            )
                        }
                        st.kind == "running" && running -> {
                            vmMissCount = 0
                            setEnv(envVm, "crosvm 运行中", GunTheme.ACCENT)
                        }
                        st.kind == "running" && !running -> {
                            // 状态说 running 但进程不见了：可能是刚崩、脚本还没写到失败，
                            // 连续 3 次（约 9 秒）都探不到才改显示「未运行」
                            vmMissCount++
                            if (vmMissCount >= 3) setEnv(envVm, "未运行", GunTheme.TEXT2)
                            else setEnv(envVm, "crosvm 运行中", GunTheme.ACCENT)
                        }
                        running -> {
                            vmMissCount = 0
                            setEnv(envVm, "crosvm 运行中", GunTheme.ACCENT)
                        }
                        else -> {
                            vmMissCount = 0
                            setEnv(envVm, "未运行", GunTheme.TEXT2)
                        }
                    }
                }
            }
            vmPollHandler.postDelayed(this, 3000)
        }
    }

    private fun refreshStates() {
        refreshEnv()
        paintMode()
        thread {
            val ready = GunyahManager.isToolchainReady(this)
            runOnUiThread {
                val tv = stateView(cardToolchain)
                tv.text = if (ready) "已就绪" else "未下载"
                tv.setTextColor(if (ready) GunTheme.ON_ACCENT else GunTheme.ON_WARN)
                tv.background = GunTheme.pill(d, if (ready) GunTheme.ACCENT else GunTheme.WARN)
            }
        }
        thread {
            val cfg = GunyahManager.loadConfig(this)
            val s = "${cfg.memMB}M · ${cfg.cpus}核"
            runOnUiThread { stateView(cardConfig).text = s }
        }
        thread {
            val cfg = GunyahManager.loadConfig(this)
            // 盘位徽章：四个固定盘位激活数 + 新增硬盘数
            var active = 0
            if (cfg.bootDisk.isNotBlank()) active++
            if (cfg.cdRom.isNotBlank()) active++
            if (cfg.systemDisk.isNotBlank()) active++
            if (cfg.dataDisk.isNotBlank()) active++
            val extras = cfg.extraDisks.size
            val total = 4 + extras
            runOnUiThread {
                val tv = stateView(cardImage)
                tv.text = if (extras > 0) "$active/$total +${extras}盘" else "$active / 4"
                if (cfg.bootDisk.isBlank() || cfg.systemDisk.isBlank()) {
                    tv.setTextColor(GunTheme.ON_WARN)
                    tv.background = GunTheme.pill(d, GunTheme.WARN)
                }
            }
        }
        thread {
            val modules = GunyahManager.scanModules(this)
            val loaded = GunyahManager.loadedModules()
            val hpSt = GunyahManager.hugepageState(this)
            val hp = hpSt.poolReady
            val hpLoaded = hpSt.loaded
            val hpTight = hpSt.memTight(GunyahManager.loadConfig(this).memMB)
            runOnUiThread {
                val tv = stateView(cardModule)
                tv.text = when {
                    hp && hpTight -> "内存偏紧"
                    hp -> "大页已就绪"
                    hpLoaded -> "大页模块就绪"
                    modules.isEmpty() -> "未检测"
                    else -> "无大页模块"
                }
                val good = hp || hpLoaded
                val neutral = modules.isEmpty() || (!hp && !hpLoaded && !hpTight)
                if (good) {
                    tv.setTextColor(GunTheme.ON_ACCENT)
                    tv.background = GunTheme.pill(d, GunTheme.ACCENT)
                } else if (neutral) {
                    tv.setTextColor(GunTheme.TEXT)
                    tv.background = GunTheme.pill(d, GunTheme.CARD_ALT)
                } else {
                    tv.setTextColor(GunTheme.ON_WARN)
                    tv.background = GunTheme.pill(d, GunTheme.WARN)
                }
                if (tv.text.isEmpty()) tv.visibility = android.view.View.GONE else tv.visibility = android.view.View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
        // 实时轮询 VM 状态（先立即刷一次，再每 3 秒）
        vmPollHandler.removeCallbacks(vmPollTask)
        vmPollHandler.post(vmPollTask)
    }

    override fun onPause() {
        vmPollHandler.removeCallbacks(vmPollTask)
        super.onPause()
    }
}
