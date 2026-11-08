package com.mcai.ubuntudsu

import com.mcai.ubuntudsu.core.BootLog

import android.content.Intent
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.GunTheme
import com.mcai.ubuntudsu.ui.Ui
import java.io.File
import kotlin.concurrent.thread

/**
 * v2.0.29：GunyaniOS 主页的可复用 View 页。
 *
 * 由 [GunyahVMActivity] 逐行抽出（UI / 状态刷新 / 3s 轮询 / 启动停止动作全部原样保留，
 * 仅把 Activity 上下文 `this` 换成宿主 [act]），使同一套界面能同时用于两处：
 *  1. [MainActivity] 底部第 3 格「VMiOS」——内嵌 tab 页，能停留 / 横滑，点或滑到就用（showBack=false）
 *  2. [GunyahVMActivity] 独立薄壳——供子页面 saveAndGoHome()/返回栈使用（showBack=true）
 *
 * 边界：GunyahManager / 启动脚本 / 子页面 / crosvm / 启动盘一律未动。
 * 生命周期由宿主转发 [onResume] / [onPause] / [onDestroy]，切离 tab 即停轮询。
 */
class VmPage(private val act: AppCompatActivity, private val showBack: Boolean) {
    private val d: Float get() = act.resources.displayMetrics.density

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

    /** 构建并返回整页根 View。宿主负责 setContentView 或塞进 tab 页容器（不再自己 setContentView）。 */
    fun build(): View {
        GunTheme.applyTheme(act)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        // v2.0.30：内嵌 tab 页保持透明，直接透出 MainActivity 的 APP 全局液态渐变背景
        // （与首页 / 多功能 / DSU 完全同一张皮）；独立 Activity 薄壳没有宿主背景，才铺全局底。
        if (showBack) root.background = GunTheme.background()

        // 顶栏：内嵌 tab 页不需要返回键（tab 本身就是导航）；独立 Activity 薄壳保留返回
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(14, d), GunTheme.dp(12, d), GunTheme.dp(14, d), GunTheme.dp(8, d))
        }
        if (showBack) bar.addView(backButton())
        bar.addView(TextView(act).apply {
            text = "GunyaniOS"; textSize = 21f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT)
            setPadding(GunTheme.dp(10, d), 0, 0, 0)
        })
        root.addView(bar)

        // 滚动内容
        val scroll = ScrollView(act).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val content = LinearLayout(act).apply {
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

        // v2.0.31：启动方式（PCI/MMIO）+ 启动/停止/显示器/日志 合并为一张玻璃操作卡。
        // 原来两行各自带一套 padding 散在背景上，现在同一个卡片内上下两排 + 细线分隔。
        val opCard = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GunTheme.card(16f, d)
            setPadding(GunTheme.dp(10, d), GunTheme.dp(9, d), GunTheme.dp(10, d), GunTheme.dp(9, d))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(GunTheme.dp(14, d), GunTheme.dp(2, d), GunTheme.dp(14, d), GunTheme.dp(10, d))
            }
        }
        // 上排：启动方式标签 + 两个严格等大的模式 chip
        // PCI 走已调通的 virtio-pci（Windows / Debian），MMIO 走 virtio-mmio（macOS / XNU）
        val modeBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeBar.addView(TextView(act).apply {
            text = "启动方式"; textSize = 9f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.MUTED)
            setPadding(0, 0, GunTheme.dp(6, d), 0)
        })
        val pChip = modeChip("PCI · Win/Debian")
        val mChip = modeChip("MMIO · macOS/XNU")
        chipPci = pChip
        chipMmio = mChip
        pChip.setOnClickListener {
            GunyahManager.setMmio(act, false)
            paintMode()
            Toast.makeText(act, "启动方式：PCI（Windows / Debian）", Toast.LENGTH_SHORT).show()
        }
        mChip.setOnClickListener {
            GunyahManager.setMmio(act, true)
            paintMode()
            Toast.makeText(act, "启动方式：MMIO（macOS / XNU）", Toast.LENGTH_SHORT).show()
        }
        modeBar.addView(pChip)
        modeBar.addView(dockSpace())
        modeBar.addView(mChip)
        opCard.addView(modeBar)
        // 细分隔线
        opCard.addView(View(act).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, GunTheme.dp(1, d))).apply {
                topMargin = GunTheme.dp(8, d); bottomMargin = GunTheme.dp(8, d)
            }
            setBackgroundColor(if (GunTheme.isNight) 0x1FFFFFFF else 0x14000000)
        })
        // 下排：启动 / 停止 / 显示器 / 日志（统一纯文字，等宽）
        val dock = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dock.addView(dockButton("启动", primary = true) { doStart() })
        dock.addView(dockSpace())
        dock.addView(dockButton("停止") { doStop() })
        dock.addView(dockSpace())
        dock.addView(dockButton("显示器") { openDisplay() })
        dock.addView(dockSpace())
        dock.addView(dockButton("日志") { showLog() })
        opCard.addView(dock)
        root.addView(opCard)
        paintMode()
        return root
    }

    private fun backButton(): TextView = TextView(act).apply {
        text = "‹"; textSize = 24f
        setTextColor(GunTheme.TEXT2)
        gravity = Gravity.CENTER
        background = GunTheme.iconTile(d, GunTheme.CHIP, 12f)
        layoutParams = LinearLayout.LayoutParams(GunTheme.dp(34, d), GunTheme.dp(34, d))
        setOnClickListener { act.finish() }
    }

    private fun dockSpace(): LinearLayout = LinearLayout(act).apply {
        layoutParams = LinearLayout.LayoutParams(GunTheme.dp(8, d), 1)
    }

    private fun dockButton(text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(act).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (primary) GunTheme.ON_ACCENT else GunTheme.TEXT)
            gravity = Gravity.CENTER
            background = if (primary) GunTheme.primaryButton(d) else GunTheme.iconTile(d, GunTheme.CHIP, 15f)
            setPadding(GunTheme.dp(6, d), GunTheme.dp(10, d), GunTheme.dp(6, d), GunTheme.dp(10, d))
            // 四按钮等宽统一
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
            GunTheme.pressAnimation(this)
        }

    /** 按磁盘上的真实配置重绘启动方式 chip，避免界面状态与配置脱节。 */
    private fun paintMode() {
        val mmio = GunyahManager.loadConfig(act).mmio
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

    /**
     * 启动方式选择 chip（PCI / MMIO 互斥，选中态由 paintMode 绘制）。
     * v2.0.31：宽度 weight=1f 等分、高度固定 34dp —— 两个 chip 像素级同尺寸，
     * 不再因文字长短 / WRAP_CONTENT 出现一大一小。文字单行居中，过长尾部省略。
     */
    private fun modeChip(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 10.5f
        setTypeface(null, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(GunTheme.dp(4, d), GunTheme.dp(8, d), GunTheme.dp(4, d), GunTheme.dp(8, d))
        layoutParams = LinearLayout.LayoutParams(0, GunTheme.dp(34, d), 1f)
        GunTheme.pressAnimation(this)
    }

    private fun <T : AppCompatActivity> open(cls: Class<T>) {
        act.startActivity(Intent(act, cls))
        act.overridePendingTransition(R.anim.zoom_out, R.anim.zoom_in)
    }

    /**
     * 入口卡片：左图标块 + 标题/副标题，右状态徽章 + 箭头。返回容器供刷新状态。
     * v2.0.31：padding 12→9、图标 38→32、卡间距 10→7、副标题单行省略，整体约 62→48dp。
     */
    private fun entryCard(
        parent: LinearLayout,
        icon: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GunTheme.card(16f, d)
            setPadding(GunTheme.dp(10, d), GunTheme.dp(9, d), GunTheme.dp(10, d), GunTheme.dp(9, d))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = GunTheme.dp(7, d)
            }
            setOnClickListener { onClick() }
            GunTheme.pressAnimation(this)
        }
        // 左：图标块
        c.addView(TextView(act).apply {
            text = icon; textSize = 15f
            gravity = Gravity.CENTER
            background = GunTheme.iconTile(d, GunTheme.CARD_ALT, 11f)
            layoutParams = LinearLayout.LayoutParams(GunTheme.dp(32, d), GunTheme.dp(32, d))
        })
        // 中：标题 + 副标题（副标题单行，避免长文案换行把卡片撑高）
        c.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = GunTheme.dp(9, d); marginEnd = GunTheme.dp(4, d)
            }
            addView(TextView(act).apply {
                text = title; textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(GunTheme.TEXT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(act).apply {
                text = subtitle; textSize = 9.5f
                setTextColor(GunTheme.MUTED)
                setPadding(0, GunTheme.dp(2, d), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })
        // 右：状态徽章
        c.addView(TextView(act).apply {
            id = R.id.gunyah_card_state
            text = "…"; textSize = 9f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.ACCENT)
            gravity = Gravity.CENTER
            background = GunTheme.pill(d)
            setPadding(GunTheme.dp(8, d), GunTheme.dp(4, d), GunTheme.dp(8, d), GunTheme.dp(4, d))
        })
        c.addView(TextView(act).apply {
            text = "›"; textSize = 16f
            setTextColor(GunTheme.MUTED)
            setPadding(GunTheme.dp(4, d), 0, 0, 0)
        })
        parent.addView(c)
        return c
    }

    private fun stateView(card: LinearLayout): TextView = card.findViewById(R.id.gunyah_card_state)

    // ------------------------- 环境检测卡片（2 列网格） -------------------------

    /**
     * v2.0.31：环境检测由「3 行×2 列独立凹槽块」改为「2 行×3 列细线紧凑网格」。
     * 去掉 6 个格子各自的背景与 margin（那正是卡片偏高的主因），整卡内只用细分隔线，
     * 少一整行，约 176dp → 108dp，给「④ 内核模块」入口卡腾出首屏空间。
     * 标题右侧是中性「6 项」计数徽章，不假装全部检测通过。
     */
    private fun buildEnvCard(parent: LinearLayout) {
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GunTheme.card(16f, d)
            setPadding(GunTheme.dp(12, d), GunTheme.dp(9, d), GunTheme.dp(12, d), GunTheme.dp(7, d))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = GunTheme.dp(8, d)
            }
        }
        // 标题行：左侧标题 + 右侧中性计数徽章
        c.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, GunTheme.dp(6, d))
            addView(TextView(act).apply {
                text = "虚拟机环境检测"; textSize = 12.5f; setTypeface(null, Typeface.BOLD)
                setTextColor(GunTheme.TEXT)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(act).apply {
                text = "6 项"; textSize = 9f; setTypeface(null, Typeface.BOLD)
                setTextColor(GunTheme.TEXT2)
                gravity = Gravity.CENTER
                background = GunTheme.pill(d, GunTheme.CARD_ALT)
                setPadding(GunTheme.dp(8, d), GunTheme.dp(2, d), GunTheme.dp(8, d), GunTheme.dp(2, d))
            })
        })
        val divColor = if (GunTheme.isNight) 0x24FFFFFF else 0x1A000000
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setBaselineAligned(false)
        }
        val row2 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setBaselineAligned(false)
        }
        envKernel = envCell(row1, "内核版本")
        gridVLine(row1, divColor)
        envSoc = envCell(row1, "处理器")
        gridVLine(row1, divColor)
        envMem = envCell(row1, "物理内存")
        envGunyah = envCell(row2, "gunyah 设备")
        gridVLine(row2, divColor)
        envModules = envCell(row2, "内核模块")
        gridVLine(row2, divColor)
        envVm = envCell(row2, "虚拟机")
        grid.addView(row1)
        grid.addView(View(act).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, GunTheme.dp(1, d)))
            setBackgroundColor(divColor)
        })
        grid.addView(row2)
        c.addView(grid)
        parent.addView(c)
    }

    /** 环境网格竖细线（插入两个格子之间）。 */
    private fun gridVLine(row: LinearLayout, color: Int) {
        row.addView(View(act).apply {
            layoutParams = LinearLayout.LayoutParams(maxOf(1, GunTheme.dp(1, d)), MATCH_PARENT)
            setBackgroundColor(color)
        })
    }

    private fun envCell(row: LinearLayout, label: String): TextView {
        val cell = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(GunTheme.dp(7, d), GunTheme.dp(4, d), GunTheme.dp(7, d), GunTheme.dp(4, d))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        cell.addView(TextView(act).apply {
            text = label; textSize = 8.5f
            setTextColor(GunTheme.MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val v = TextView(act).apply {
            text = "检测中…"; textSize = 10.5f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT)
            setPadding(0, GunTheme.dp(1, d), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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
            act.runOnUiThread { setEnv(envKernel, k.ifBlank { "-" }) }
        }
        thread {
            val soc = GunyahManager.socInfo()
            act.runOnUiThread { setEnv(envSoc, soc) }
        }
        thread {
            val mem = GunyahManager.totalMemGB()
            act.runOnUiThread { setEnv(envMem, mem) }
        }
        thread {
            val ok = GunyahManager.gunyahDevReady()
            act.runOnUiThread {
                setEnv(envGunyah, if (ok) "可用 ✓" else "未检测到 ✗",
                    if (ok) GunTheme.ACCENT else GunTheme.DANGER)
            }
        }
        thread {
            val modules = GunyahManager.scanModules(act)
            val loaded = GunyahManager.loadedModules()
            val hpSt = GunyahManager.hugepageState(act)
            val memCfg = GunyahManager.loadConfig(act)
            // 只数「本机 KMI」的模块，别的版本躺在目录里不算（也不算已加载）
            val native = modules.filter { GunyahManager.isNativeModule(it, hpSt.kernel) }
            val n = native.count { GunyahManager.isLoaded(it, loaded, hpSt.kernel) }
            act.runOnUiThread {
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
        com.mcai.ubuntudsu.core.DisplayLauncher.enter(act)
    }

    // ------------------------- 日志 -------------------------

    private fun showLog() {
        thread {
            val log = File(GunyahManager.privateDir(act), "boot.log")
            val text = if (log.exists()) BootLog.readClean(log).takeLast(8000)
            else "暂无日志（先启动一次虚拟机）"
            act.runOnUiThread {
                val dlg = AlertDialog.Builder(act)
                    .setTitle("启动日志 boot.log")
                    .setMessage(text)
                    .setPositiveButton("关闭", null)
                    .setNegativeButton("复制") { _, _ ->
                        runCatching {
                            val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("boot.log", text))
                        }
                        Toast.makeText(act, "日志已复制，可直接粘贴发送", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("清空") { _, _ ->
                        runCatching {
                            log.writeText("")
                            log.parentFile?.let { File(it, BootLog.ROTATED_TAIL).delete() }
                        }
                        Toast.makeText(act, "日志已清空", Toast.LENGTH_SHORT).show()
                    }
                    .show()
                Ui.styleDialog(act, dlg)
            }
        }
    }

    // ------------------------- 动作 -------------------------

    private fun doStart() {
        if (!GunyahManager.isToolchainReady(act)) {
            Toast.makeText(act, "请先在「工具链」页下载", Toast.LENGTH_SHORT).show()
            open(GunyahToolchainActivity::class.java)
            return
        }
        // 连点会下发出两份启动脚本。两份脚本都带「失败就换下一组」的重试链，
        // 而且每轮重试都会 pkill -9 crosvm —— 互相杀对方刚起来的 crosvm，
        // 表现就是虚拟机反复重启、VNC 端口开开关关（连上就是 Connection refused）。
        val now = System.currentTimeMillis()
        if (now - lastStartAt < 8000) {
            Toast.makeText(act, "正在启动中，请稍候…（重复点击会互相打断启动）", Toast.LENGTH_SHORT).show()
            return
        }
        lastStartAt = now
        val cfg = GunyahManager.loadConfig(act)
        Toast.makeText(
            act,
            if (cfg.mmio) "正在启动（MMIO / virtio-mmio）…" else "正在启动（PCI / virtio-pci）…",
            Toast.LENGTH_SHORT
        ).show()
        thread {
            // 已在跑就别再下发（会在脚本里变成两份重试链互相杀进程）。
            // 「启动中」也要拦：脚本正在换参数组合重试，crosvm 可能刚好不在，
            // 但启动流程还活着 —— 这时再下发只会被单实例锁挡掉。
            val st0 = GunyahManager.vmStatus(act)
            if (GunyahManager.isVmRunning() || st0.isBooting) {
                act.runOnUiThread {
                    if (st0.isBooting) {
                        Toast.makeText(act, "启动流程还在进行中，打开控制台看进度", Toast.LENGTH_SHORT).show()
                        act.startActivity(Intent(act, GunyahBootActivity::class.java))
                    } else {
                        Toast.makeText(act, "虚拟机已在运行，如需重启用「停止」再启动", Toast.LENGTH_SHORT).show()
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
                act.runOnUiThread { Toast.makeText(act, tip, Toast.LENGTH_LONG).show() }
            }
            val (ok, log) = GunyahManager.start(act, cfg)
            act.runOnUiThread {
                if (ok) {
                    // 直接进启动控制台：日志实时滚动，VNC 端口真通了才自动切到画面。
                    // 这样「启动中」的每一秒都有反馈，也不会再跳进假 VNC 黑屏。
                    act.startActivity(Intent(act, GunyahBootActivity::class.java))
                } else {
                    Toast.makeText(act, "启动失败：$log", Toast.LENGTH_LONG).show()
                    // 被单实例锁挡住 = 上一次启动还在跑，这时候日志最有用
                    if (log.contains("还在进行中")) {
                        act.startActivity(Intent(act, GunyahBootActivity::class.java))
                    }
                }
                refreshStates()
            }
        }
    }

    private fun doStop() {
        thread {
            val wasRunning = GunyahManager.isVmRunning()
            val (ok, _) = GunyahManager.stop(act)
            val stillRunning = GunyahManager.isVmRunning()
            act.runOnUiThread {
                val msg = when {
                    !stillRunning && wasRunning -> "已停止"
                    !stillRunning -> "虚拟机未在运行"
                    ok -> "已发送停止指令"
                    else -> "停止失败：crosvm 仍在运行，请稍后重试"
                }
                Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
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
                val st = GunyahManager.vmStatus(act)
                val running = GunyahManager.isVmRunning()
                act.runOnUiThread {
                    if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                    when {
                        st.kind == "failed" -> {
                            vmMissCount = 0
                            setEnv(envVm, "启动失败", GunTheme.DANGER)
                            val reason = st.reason
                            if (reason.isNotBlank() && reason != lastFailReason) {
                                lastFailReason = reason
                                Toast.makeText(act, "启动失败：$reason", Toast.LENGTH_LONG).show()
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
            val ready = GunyahManager.isToolchainReady(act)
            act.runOnUiThread {
                val tv = stateView(cardToolchain)
                tv.text = if (ready) "已就绪" else "未下载"
                tv.setTextColor(if (ready) GunTheme.ON_ACCENT else GunTheme.ON_WARN)
                tv.background = GunTheme.pill(d, if (ready) GunTheme.ACCENT else GunTheme.WARN)
            }
        }
        thread {
            val cfg = GunyahManager.loadConfig(act)
            val s = "${cfg.memMB}M · ${cfg.cpus}核"
            act.runOnUiThread { stateView(cardConfig).text = s }
        }
        thread {
            val cfg = GunyahManager.loadConfig(act)
            // 盘位徽章：四个固定盘位激活数 + 新增硬盘数
            var active = 0
            if (cfg.bootDisk.isNotBlank()) active++
            if (cfg.cdRom.isNotBlank()) active++
            if (cfg.systemDisk.isNotBlank()) active++
            if (cfg.dataDisk.isNotBlank()) active++
            val extras = cfg.extraDisks.size
            val total = 4 + extras
            act.runOnUiThread {
                val tv = stateView(cardImage)
                tv.text = if (extras > 0) "$active/$total +${extras}盘" else "$active / 4"
                if (cfg.bootDisk.isBlank() || cfg.systemDisk.isBlank()) {
                    tv.setTextColor(GunTheme.ON_WARN)
                    tv.background = GunTheme.pill(d, GunTheme.WARN)
                }
            }
        }
        thread {
            val modules = GunyahManager.scanModules(act)
            val loaded = GunyahManager.loadedModules()
            val hpSt = GunyahManager.hugepageState(act)
            val hp = hpSt.poolReady
            val hpLoaded = hpSt.loaded
            val hpTight = hpSt.memTight(GunyahManager.loadConfig(act).memMB)
            act.runOnUiThread {
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

    /** 宿主 onResume / 切到该 tab 时调用：刷新一次 + 启动 3s 轮询（可重入，先移除旧任务）。 */
    fun onResume() {
        refreshStates()
        vmPollHandler.removeCallbacks(vmPollTask)
        vmPollHandler.post(vmPollTask)
    }

    /** 宿主 onPause / 切离该 tab 时调用：停轮询，避免后台空跑耗电。 */
    fun onPause() {
        vmPollHandler.removeCallbacks(vmPollTask)
    }

    /** 宿主 onDestroy 时调用：彻底摘掉回调，防泄漏。 */
    fun onDestroy() {
        vmPollHandler.removeCallbacks(vmPollTask)
    }
}
