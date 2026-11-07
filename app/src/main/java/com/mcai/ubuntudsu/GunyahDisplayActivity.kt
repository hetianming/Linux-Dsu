package com.mcai.ubuntudsu

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gaurav.avnc.ui.vnc.startVncActivity
import com.gaurav.avnc.vnc.VncUri
import com.mcai.ubuntudsu.core.DisplayLauncher
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.GunTheme
import kotlin.concurrent.thread

/**
 * 🖥 显示器（独立页面）：
 * 顶部连接芯片（主机端口 / 屏幕模式 / LIVE 状态）+ 画面区（点击进入桌面）+
 * 悬浮工具栏（全屏 / 旋转 / 触控 / 键盘 / 断开）。
 * 「进入桌面」使用 APP 内置 AVNC 查看器（非外部 App），screen 参数按 simplefb↔gpu 自适应。
 */
class GunyahDisplayActivity : HapticAppCompatActivity() {
    private val d: Float get() = resources.displayMetrics.density

    private lateinit var statusChip: TextView
    private lateinit var screenChip: TextView
    private lateinit var liveChip: TextView
    private lateinit var enterBtn: TextView

    private var landscape = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GunTheme.applyTheme(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.background = GunTheme.background()

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(12, d), GunTheme.dp(10, d), GunTheme.dp(12, d), GunTheme.dp(6, d))
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 26f
            setTextColor(GunTheme.TEXT)
            setPadding(0, 0, GunTheme.dp(10, d), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "显示器"; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT)
        })
        root.addView(bar)

        // 连接芯片行
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(12, d), GunTheme.dp(4, d), GunTheme.dp(12, d), GunTheme.dp(8, d))
        }
        val cfg = GunyahManager.loadConfig(this)
        val host = cfg.vncHost.ifBlank { "127.0.0.1" }
        val port = GunyahManager.actualVncPort(this) ?: cfg.vncPort
        statusChip = chip(chips, "$host:$port")
        screenChip = chip(chips, "simplefb")
        liveChip = chip(chips, "● 检测中", GunTheme.CHIP)
        (liveChip.layoutParams as LinearLayout.LayoutParams).marginStart = GunTheme.dp(6, d)
        (screenChip.layoutParams as LinearLayout.LayoutParams).marginStart = GunTheme.dp(6, d)
        root.addView(chips)

        // 画面区：点击进入桌面（内置 AVNC）
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GunTheme.cardAlt(18f, d)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                marginStart = GunTheme.dp(12, d); marginEnd = GunTheme.dp(12, d)
                topMargin = GunTheme.dp(4, d)
            }
        }
        frame.addView(TextView(this).apply {
            text = "🖥"; textSize = 42f; gravity = Gravity.CENTER
            setTextColor(GunTheme.MUTED)
        })
        enterBtn = TextView(this).apply {
            text = "进入桌面"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.ON_ACCENT)
            gravity = Gravity.CENTER
            background = GunTheme.primaryButton(d)
            setPadding(GunTheme.dp(30, d), GunTheme.dp(12, d), GunTheme.dp(30, d), GunTheme.dp(12, d))
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.topMargin = GunTheme.dp(14, d)
            layoutParams = lp
            setOnClickListener { enterDisplay() }
            GunTheme.pressAnimation(this)
        }
        frame.addView(enterBtn)
        frame.addView(TextView(this).apply {
            text = "使用 APP 内置 AVNC 查看器 · 失败自动重连"
            textSize = 10f; gravity = Gravity.CENTER
            setTextColor(GunTheme.MUTED)
            setPadding(0, GunTheme.dp(10, d), 0, 0)
        })
        root.addView(frame)

        // 悬浮工具栏
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(12, d), GunTheme.dp(10, d), GunTheme.dp(12, d), GunTheme.dp(14, d))
        }
        toolbar.addView(toolButton("⟳", "旋转") { toggleOrientation() })
        toolbar.addView(toolSpace())
        toolbar.addView(toolButton("👆", "触控") { enterDisplay() })
        toolbar.addView(toolSpace())
        toolbar.addView(toolButton("⌨", "键盘") { enterDisplay() })
        toolbar.addView(toolSpace())
        toolbar.addView(toolButton("⛶", "全屏") { enterDisplay() })
        toolbar.addView(toolSpace())
        toolbar.addView(toolButton("⏏", "断开", danger = true) { doStopVm() })
        root.addView(toolbar)
        setContentView(root)

        com.mcai.ubuntudsu.ui.Ui.enableEdgeToEdge(this, root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        refreshStatus()
    }

    private fun chip(parent: LinearLayout, text: String, fill: Int = GunTheme.CHIP): TextView {
        val t = TextView(this).apply {
            this.text = text
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.TEXT2)
            background = GunTheme.pill(d, fill)
            setPadding(GunTheme.dp(11, d), GunTheme.dp(5, d), GunTheme.dp(11, d), GunTheme.dp(5, d))
        }
        parent.addView(t)
        return t
    }

    private fun toolSpace(): LinearLayout = LinearLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(GunTheme.dp(8, d), 1)
    }

    private fun toolButton(icon: String, label: String, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = "$icon\n$label"
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setTextColor(if (danger) GunTheme.DANGER else GunTheme.TEXT)
            gravity = Gravity.CENTER
            background = GunTheme.iconTile(d, if (danger) 0x26FF6B6B else GunTheme.CHIP, 13f)
            setPadding(GunTheme.dp(4, d), GunTheme.dp(10, d), GunTheme.dp(4, d), GunTheme.dp(10, d))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
            GunTheme.pressAnimation(this)
        }

    /** 进入桌面：v2.0.7 起复用 DisplayLauncher.enter（与 VM 主界面「显示器」按钮同一路由，
     *  原生显示直接进画面，启动中→控制台，未运行→提示，否则进内置 AVNC） */
    private fun enterDisplay() {
        DisplayLauncher.enter(this)
    }

    private fun toggleOrientation() {
        landscape = !landscape
        requestedOrientation = if (landscape)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun doStopVm() {
        thread {
            val (ok, _) = GunyahManager.stop(this)
            runOnUiThread {
                Toast.makeText(this, if (ok) "已发送停止指令" else "停止失败", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
    }

    /** 刷新芯片状态：端口监听（LIVE）+ crosvm 存活 + screen 模式 */
    private fun refreshStatus() {
        thread {
            val cfg = GunyahManager.loadConfig(this)
            val st = GunyahManager.vmStatus(this)
            val running = GunyahManager.isVmRunning()
            val listening = portListening(GunyahManager.actualVncPort(this) ?: cfg.vncPort)
            // screen 模式：跟随「启动配置 → 显示模式」
            val screen = when (cfg.displayMode) {
                "gpu2d", "gpu3d", "custom" -> "gpu-0 / simplefb"
                else -> "simplefb"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                screenChip.text = screen
                if (running && listening) {
                    liveChip.text = "● LIVE"
                    liveChip.setTextColor(GunTheme.ACCENT)
                    liveChip.background = GunTheme.pill(d, 0x263DDC84)
                    enterBtn.alpha = 1f
                    enterBtn.text = "进入桌面"
                } else if (st.isBooting) {
                    liveChip.text = if (st.total > 0) "● 兼容 ${st.idx}/${st.total}" else "● 启动中…"
                    liveChip.setTextColor(GunTheme.WARN)
                    liveChip.background = GunTheme.pill(d, 0x26E0A458)
                    enterBtn.text = "查看启动日志"
                } else if (running) {
                    liveChip.text = "● 启动中…"
                    liveChip.setTextColor(GunTheme.WARN)
                    liveChip.background = GunTheme.pill(d, 0x26E0A458)
                } else if (st.kind == "failed") {
                    liveChip.text = "● 启动失败"
                    liveChip.setTextColor(GunTheme.DANGER)
                    liveChip.background = GunTheme.pill(d, GunTheme.CHIP)
                    enterBtn.text = "进入桌面"
                } else {
                    liveChip.text = "○ 未运行"
                    liveChip.setTextColor(GunTheme.MUTED)
                    liveChip.background = GunTheme.pill(d, GunTheme.CHIP)
                    enterBtn.text = "进入桌面"
                }
            }
        }
    }

    private fun portListening(port: Int): Boolean {
        val r = com.mcai.ubuntudsu.core.RootShell.exec(
            "netstat -tln 2>/dev/null | grep -q '[.:]$port ' && echo __UP__ || echo __DOWN__",
            timeoutMs = 8000,
        )
        return r.stdout.contains("__UP__")
    }

    /** 3 秒轮询：停在显示器页时也能看到「启动中 2/5 → LIVE」的变化 */
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollTask = object : Runnable {
        override fun run() {
            refreshStatus()
            pollHandler.postDelayed(this, 3000)
        }
    }

    override fun onResume() {
        super.onResume()
        pollHandler.removeCallbacks(pollTask)
        pollHandler.post(pollTask)
    }

    override fun onPause() {
        pollHandler.removeCallbacks(pollTask)
        super.onPause()
    }
}
