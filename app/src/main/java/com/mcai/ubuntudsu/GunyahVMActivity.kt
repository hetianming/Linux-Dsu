package com.mcai.ubuntudsu

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gaurav.avnc.ui.vnc.startVncActivity
import com.gaurav.avnc.vnc.VncUri
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.Ui
import kotlin.concurrent.thread

/**
 * gunyahVM 主页面：只放 4 张入口卡片 + 底部启动/停止栏。
 * 每个功能区（工具链 / 启动配置 / 镜像映射 / 内核模块）都是独立子页面，
 * 点卡片进入，状态摘要实时显示在卡片右侧。
 */
class GunyahVMActivity : AppCompatActivity() {
    private val d: Float get() = resources.displayMetrics.density

    private lateinit var toolchainChip: TextView
    private lateinit var vmChip: TextView
    private lateinit var cardToolchain: LinearLayout
    private lateinit var cardConfig: LinearLayout
    private lateinit var cardImage: LinearLayout
    private lateinit var cardModule: LinearLayout

    // 环境检测行
    private lateinit var envKernel: TextView
    private lateinit var envSoc: TextView
    private lateinit var envMem: TextView
    private lateinit var envGunyah: TextView
    private lateinit var envModules: TextView
    private lateinit var envChain: TextView
    private lateinit var envVm: TextView

    private fun spacer(): LinearLayout = LinearLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(Ui.dp(8, d), WRAP_CONTENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.background = Ui.liquidBackground(this)
        Ui.animateLiquidBackground(root)

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(6, d))
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f
            setTextColor(Ui.primaryText(this@GunyahVMActivity))
            setPadding(0, 0, Ui.dp(10, d), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "gunyahVM"; textSize = 19f; setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@GunyahVMActivity))
        })
        root.addView(bar)

        // 状态条：工具链 chip 点击直达工具链页
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(12, d), 0, Ui.dp(12, d), Ui.dp(8, d))
        }
        toolchainChip = TextView(this).apply {
            text = "工具链  检测中…"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.buttonWarning(this@GunyahVMActivity))
            background = Ui.glassButton(this@GunyahVMActivity)
            setPadding(Ui.dp(10, d), Ui.dp(6, d), Ui.dp(10, d), Ui.dp(6, d))
            setOnClickListener { open(GunyahToolchainActivity::class.java) }
        }
        vmChip = TextView(this).apply {
            text = "虚拟机  检测中…"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.secondaryText(this@GunyahVMActivity))
            background = Ui.glassButton(this@GunyahVMActivity)
            setPadding(Ui.dp(10, d), Ui.dp(6, d), Ui.dp(10, d), Ui.dp(6, d))
        }
        statusRow.addView(toolchainChip)
        statusRow.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1).apply { weight = 1f }
        })
        statusRow.addView(vmChip)
        root.addView(statusRow)

        // 入口卡片
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(4, d), Ui.dp(12, d), Ui.dp(12, d))
        }
        buildEnvCard(content)
        cardToolchain = entryCard(content, "①", "工具链", "crosvm / edk2 固件 / libc++ 在线下载与本地导入") {
            open(GunyahToolchainActivity::class.java)
        }
        cardConfig = entryCard(content, "②", "启动配置", "内存 / CPU / GPU / VNC 全参数可改") {
            open(GunyahBootConfigActivity::class.java)
        }
        cardImage = entryCard(content, "③", "镜像映射", "7 个镜像 · 内设文件管理选择 · 格式不限") {
            open(GunyahImageActivity::class.java)
        }
        cardModule = entryCard(content, "④", "内核模块", "自动检测内核版本 · 手动加载 / 卸载") {
            open(GunyahModuleActivity::class.java)
        }
        scroll.addView(content)
        root.addView(scroll)

        // 底部操作栏：启动 / 停止 / 显示桌面 / 保存
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(12, d))
        }
        actionBar.addView(actionButton("▶ 启动", Ui.buttonSuccess(this)) { doStart() })
        actionBar.addView(spacer())
        actionBar.addView(actionButton("■ 停止", Ui.buttonDanger(this)) { doStop() })
        actionBar.addView(spacer())
        actionBar.addView(actionButton("🖥 桌面", Ui.buttonSecondary(this)) { openVncDisplay() })
        actionBar.addView(spacer())
        actionBar.addView(actionButton("💾 保存", Ui.buttonPrimary(this)) {
            GunyahManager.saveConfig(this, GunyahManager.loadConfig(this))
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        })
        root.addView(actionBar)
        setContentView(root)

        Ui.enableEdgeToEdge(this, root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun <T : AppCompatActivity> open(cls: Class<T>) {
        startActivity(Intent(this, cls))
        overridePendingTransition(R.anim.zoom_out, R.anim.zoom_in)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }

    /** 入口卡片：左编号+标题+副标题，右侧状态值 + 箭头。返回卡片容器供刷新状态。 */
    private fun entryCard(
        parent: LinearLayout,
        no: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.neuCard(this@GunyahVMActivity, 18f)
            setPadding(Ui.dp(14, d), Ui.dp(14, d), Ui.dp(14, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(12, d)
            }
            setOnClickListener { onClick() }
            Ui.pressAnimation(this)
        }
        // 左：编号圆标 + 文案
        c.addView(TextView(this).apply {
            text = no
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#3a6df0"))
            setPadding(0, 0, Ui.dp(12, d), 0)
        })
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(this@GunyahVMActivity).apply {
                text = title
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Ui.primaryText(this@GunyahVMActivity))
            })
            addView(TextView(this@GunyahVMActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Ui.secondaryText(this@GunyahVMActivity))
                setPadding(0, Ui.dp(2, d), 0, 0)
            })
        })
        // 右：状态值 + 箭头
        c.addView(TextView(this).apply {
            id = R.id.gunyah_card_state
            text = "…"
            textSize = 11f
            setTextColor(Ui.secondaryText(this@GunyahVMActivity))
            setPadding(0, 0, Ui.dp(6, d), 0)
        })
        c.addView(TextView(this).apply {
            text = "›"
            textSize = 18f
            setTextColor(Ui.secondaryText(this@GunyahVMActivity))
        })
        parent.addView(c)
        return c
    }

    private fun stateView(card: LinearLayout): TextView = card.findViewById(R.id.gunyah_card_state)

    // ------------------------- ⓪ 虚拟机环境检测卡片 -------------------------

    /** 完整环境检测：内核 / 处理器 / 内存 / gunyah 设备 / 内核模块 / 工具链 / 虚拟机 */
    private fun buildEnvCard(parent: LinearLayout) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(this@GunyahVMActivity, 18f)
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(12, d)
            }
        }
        c.addView(TextView(this).apply {
            text = "⓪ 虚拟机环境检测"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@GunyahVMActivity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        })
        envKernel = envRow(c); envSoc = envRow(c); envMem = envRow(c)
        envGunyah = envRow(c); envModules = envRow(c)
        envChain = envRow(c); envVm = envRow(c)
        parent.addView(c)
    }

    private fun envRow(parent: LinearLayout): TextView {
        val t = TextView(this).apply {
            text = "检测中…"
            textSize = 11.5f
            setTextColor(Ui.secondaryText(this@GunyahVMActivity))
            setPadding(0, Ui.dp(3, d), 0, Ui.dp(3, d))
        }
        parent.addView(t)
        return t
    }

    private fun setEnv(v: TextView?, text: String, color: Int? = null) {
        if (v == null || !::envKernel.isInitialized) return
        v.text = text
        if (color != null) v.setTextColor(color)
    }

    private fun refreshEnv() {
        thread {
            val k = GunyahManager.detectKernelVersion()
            runOnUiThread { setEnv(envKernel, "内核版本：${k.ifBlank { "-" }}") }
        }
        thread {
            val soc = GunyahManager.socInfo()
            runOnUiThread { setEnv(envSoc, "处理器：$soc") }
        }
        thread {
            val mem = GunyahManager.totalMemGB()
            runOnUiThread { setEnv(envMem, "物理内存：$mem") }
        }
        thread {
            val ok = GunyahManager.gunyahDevReady()
            runOnUiThread {
                setEnv(
                    envGunyah,
                    if (ok) "gunyah 设备：可用 ✓" else "gunyah 设备：未检测到 ✗",
                    if (ok) Ui.buttonSuccess(this) else Ui.buttonDanger(this),
                )
            }
        }
        thread {
            val modules = GunyahManager.scanModules(this)
            val loaded = GunyahManager.loadedModules()
            val n = modules.count { GunyahManager.isLoaded(it, loaded) }
            runOnUiThread {
                setEnv(
                    envModules,
                    if (modules.isEmpty()) "内核模块：未检测到（先下载工具链）"
                    else "内核模块：已加载 $n / ${modules.size}",
                    if (n > 0) Ui.buttonSuccess(this) else Ui.secondaryText(this),
                )
            }
        }
        thread {
            val bin = GunyahManager.detectBinDir(this)
            runOnUiThread {
                setEnv(
                    envChain,
                    if (bin != null) "工具链：已就绪" else "工具链：未下载",
                    if (bin != null) Ui.buttonSuccess(this) else Ui.buttonWarning(this),
                )
            }
        }
    }

    /** 内置 VNC 显示器（avnc 查看器，失败回退自带查看器）。 */
    private fun openVncDisplay() {
        val cfg = GunyahManager.loadConfig(this)
        val host = cfg.vncHost.ifBlank { "127.0.0.1" }
        val port = cfg.vncPort
        val ok = runCatching {
            startVncActivity(this, VncUri("vnc://$host:$port"))
        }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(Intent(this, VncViewerActivity::class.java).apply {
                    putExtra(VncViewerActivity.EXTRA_HOST, host)
                    putExtra(VncViewerActivity.EXTRA_PORT, port)
                })
            }.onFailure {
                Toast.makeText(this, "内置 VNC 查看器启动失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun actionButton(text: String, accent: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.buttonText(this@GunyahVMActivity))
            gravity = Gravity.CENTER
            background = Ui.glassButton(this@GunyahVMActivity, accent)
            setPadding(Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
    }

    // ------------------------- 动作 -------------------------

    private fun doStart() {
        if (!GunyahManager.isToolchainReady(this)) {
            Toast.makeText(this, "请先在「工具链」页下载", Toast.LENGTH_SHORT).show()
            open(GunyahToolchainActivity::class.java)
            return
        }
        Toast.makeText(this, "正在启动…", Toast.LENGTH_SHORT).show()
        val cfg = GunyahManager.loadConfig(this)
        thread {
            val (ok, log) = GunyahManager.start(this, cfg)
            runOnUiThread {
                Toast.makeText(this, if (ok) "启动指令已下发" else "启动失败：$log", Toast.LENGTH_LONG).show()
                refreshStates()
            }
        }
    }

    private fun doStop() {
        thread {
            val (ok, _) = GunyahManager.stop(this)
            runOnUiThread {
                Toast.makeText(this, if (ok) "已发送停止指令" else "停止失败", Toast.LENGTH_SHORT).show()
                refreshStates()
            }
        }
    }

    // ------------------------- 状态刷新（卡片摘要） -------------------------

    private fun refreshStates() {
        refreshEnv()
        thread {
            val ready = GunyahManager.isToolchainReady(this)
            runOnUiThread {
                toolchainChip.text = "工具链  ${if (ready) "已就绪" else "未下载"}"
                toolchainChip.setTextColor(if (ready) Ui.buttonSuccess(this) else Ui.buttonWarning(this))
                stateView(cardToolchain).text = if (ready) "已就绪" else "未下载"
                stateView(cardToolchain).setTextColor(
                    if (ready) Ui.buttonSuccess(this) else Ui.buttonWarning(this),
                )
            }
        }
        thread {
            val running = GunyahManager.isVmRunning()
            runOnUiThread {
                vmChip.text = "虚拟机  ${if (running) "运行中" else "未运行"}"
                vmChip.setTextColor(if (running) Ui.buttonSuccess(this) else Ui.secondaryText(this))
                setEnv(
                    envVm,
                    "虚拟机：${if (running) "运行中" else "未运行"}",
                    if (running) Ui.buttonSuccess(this) else Ui.secondaryText(this),
                )
            }
        }
        // ② 配置摘要
        thread {
            val cfg = GunyahManager.loadConfig(this)
            val s = "${cfg.memMB}MB · ${cfg.cpus}核 · VNC ${if (cfg.vnc) "开" else "关"}"
            runOnUiThread { stateView(cardConfig).text = s }
        }
        // ③ 镜像摘要：已填写的镜像数
        thread {
            val cfg = GunyahManager.loadConfig(this)
            var filled = 0
            listOf(cfg.initEsp, cfg.osImg, cfg.systemosImg, cfg.dataStore, cfg.droidvmDisk, cfg.rootApfs, cfg.systemApfs)
                .forEach { if (it.isNotBlank()) filled++ }
            runOnUiThread { stateView(cardImage).text = "$filled / 7 已选" }
        }
        // ④ 模块摘要
        thread {
            val modules = GunyahManager.scanModules(this)
            val loaded = GunyahManager.loadedModules()
            val s = if (modules.isEmpty()) "未检测到模块" else "已加载 ${modules.count { GunyahManager.isLoaded(it, loaded) }} / ${modules.size}"
            runOnUiThread {
                stateView(cardModule).text = s
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }
}
