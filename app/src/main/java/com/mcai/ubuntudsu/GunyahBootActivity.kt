package com.mcai.ubuntudsu

import com.mcai.ubuntudsu.core.BootLog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
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
import com.mcai.ubuntudsu.core.DisplayLauncher
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.ui.GunTheme
import java.io.File
import kotlin.concurrent.thread

/**
 * 🖥 启动控制台（Boot Console）
 *
 * 点「启动」后直接进这里：全屏黑底 + 实时滚动的 crosvm / Gunyah / EDK2 日志。
 * 等 VNC 端口真的能连上（不是日志里写了「已监听」就算），自动切到 AVNC ——
 * 视觉上就是「日志刷到最后一行 → UEFI 画面 → 系统桌面」。
 *
 * 为什么要有这一页：
 *  启动脚本是「一组参数失败就换下一组」的监督重试链，每组失败都会 pkill crosvm
 *  再起下一组。以前界面只能看到「未运行 / crosvm 运行中」来回跳，用户以为启动
 *  命令在反复重启。这里把「第 2/5 组」「换参数的原因」直接打出来，同时避免在
 *  端口没就绪时跳进假 VNC（黑屏只有一个鼠标）。
 */
class GunyahBootActivity : HapticAppCompatActivity() {
    private val d: Float get() = resources.displayMetrics.density

    private lateinit var stateView: TextView
    private lateinit var portView: TextView
    private lateinit var timeView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var hintView: TextView

    private var cfg: GunyahManager.Config? = null
    private var logFile: File? = null

    @Volatile private var alive = true
    @Volatile private var jumped = false
    private var autoFollow = true
    private var lastRendered = ""
    private val startedAt = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GunTheme.applyTheme(this)
        cfg = GunyahManager.loadConfig(this)
        logFile = File(GunyahManager.privateDir(this), "boot.log")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GunTheme.background()
        }

        // 顶栏：返回 + 标题 + 计时
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(12, d), GunTheme.dp(0, d), GunTheme.dp(12, d), GunTheme.dp(4, d))
        }
        bar.addView(TextView(this).apply {
            text = "‹"; textSize = 24f
            includeFontPadding = false
            setTextColor(GunTheme.TEXT)
            setPadding(0, 0, GunTheme.dp(10, d), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "启动控制台"; textSize = 16f; setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
            setTextColor(GunTheme.TEXT)
        })
        timeView = TextView(this).apply {
            text = "00:00"; textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(GunTheme.TEXT2)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        bar.addView(timeView)
        root.addView(bar)

        // 状态行：阶段 + 端口
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(12, d), GunTheme.dp(2, d), GunTheme.dp(12, d), GunTheme.dp(6, d))
        }
        stateView = TextView(this).apply {
            text = "● 准备中"; textSize = 11f; setTypeface(null, Typeface.BOLD)
            setTextColor(GunTheme.WARN)
            background = GunTheme.pill(d, 0x26E0A458)
            setPadding(GunTheme.dp(10, d), GunTheme.dp(5, d), GunTheme.dp(10, d), GunTheme.dp(5, d))
        }
        portView = TextView(this).apply {
            text = "端口检测中"; textSize = 10f
            setTextColor(GunTheme.TEXT2)
            setPadding(GunTheme.dp(8, d), 0, 0, 0)
        }
        statusRow.addView(stateView)
        statusRow.addView(portView)
        root.addView(statusRow)

        // 日志区（等宽、可选中、自动跟随）
        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setPadding(GunTheme.dp(12, d), 0, GunTheme.dp(12, d), 0)
            setOnScrollChangeListener { v, _, _, _, _ ->
                autoFollow = !v.canScrollVertically(1)
            }
        }
        logView = TextView(this).apply {
            text = "正在等待 crosvm 输出…"
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(GunTheme.TEXT2)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.06f)
            setPadding(0, GunTheme.dp(4, d), 0, GunTheme.dp(8, d))
        }
        scroll.addView(logView)
        root.addView(scroll)

        // 底部提示 + 操作
        hintView = TextView(this).apply {
            text = ""
            textSize = 10.5f
            setTextColor(GunTheme.MUTED)
            setPadding(GunTheme.dp(12, d), GunTheme.dp(6, d), GunTheme.dp(12, d), 0)
            visibility = View.GONE
        }
        root.addView(hintView)

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GunTheme.dp(10, d), GunTheme.dp(8, d), GunTheme.dp(10, d), GunTheme.dp(12, d))
        }
        dock.addView(btn("复制日志") { copyLog() })
        dock.addView(space())
        dock.addView(btn("直接进画面", primary = true) { jumpVnc(force = true) })
        dock.addView(space())
        dock.addView(btn("停止虚拟机", danger = true) { stopVm() })
        root.addView(dock)

        setContentView(root)
        com.mcai.ubuntudsu.ui.Ui.enableEdgeToEdge(this, root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        startPolling()
    }

    // ------------------------------ 视图工具 ------------------------------

    private fun space(): LinearLayout = LinearLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(GunTheme.dp(6, d), 1)
    }

    private fun btn(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(
                when {
                    danger -> GunTheme.DANGER
                    primary -> GunTheme.ON_ACCENT
                    else -> GunTheme.TEXT
                }
            )
            gravity = Gravity.CENTER
            background = when {
                primary -> GunTheme.primaryButton(d)
                danger -> GunTheme.iconTile(d, 0x26FF6B6B, 14f)
                else -> GunTheme.iconTile(d, GunTheme.CHIP, 14f)
            }
            setPadding(GunTheme.dp(6, d), GunTheme.dp(11, d), GunTheme.dp(6, d), GunTheme.dp(11, d))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
            GunTheme.pressAnimation(this)
        }

    // ------------------------------ 轮询 ------------------------------

    /** 脚本实际使用的端口（5905 被占用时会顺延），0 = 还不知道 */
    @Volatile private var activePort = 0

    private fun portNow(c: GunyahManager.Config): Int =
        if (activePort > 0) activePort else c.vncPort

    private fun startPolling() {
        val c = cfg ?: return
        val host = c.vncHost.ifBlank { "127.0.0.1" }
        thread {
            var portOk = false
            var lastPortCheck = 0L
            while (alive) {
                val txt = runCatching {
                    val f = logFile
                    BootLog.readTail(f)
                }.getOrDefault("")
                val st = GunyahManager.vmStatus(this)

                val now = System.currentTimeMillis()
                // v2.0 原生显示：VNC 端口根本不监听，不能再用 portOk 判就绪。
                // 脚本在 crosvm 判活成功后写 vm.state=running，以此为准自动进内置显示器。
                // v2.0.19：新增更早期的 uefi 状态 / 日志标记，一检测到 UEFI 帧缓冲就绪
                // 就立刻跳，不等后面 14 秒判活观察，UEFI 菜单第一帧就能看到并可按 ESC/F2。
                // 日志标记是唯一新串，不会和历史日志里的「自定义 UEFI 固件」等字样混淆。
                val earlyUefi = st.kind == "uefi" ||
                    BootLog.containsMarker(logFile, "检测到 uefi: UEFI 帧缓冲已就绪")
                if (DisplayLauncher.wantsNative(c) && c.autoDisplay && !jumped &&
                    (st.kind == "running" || earlyUefi)
                ) {
                    jumped = true
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            stateView.text = if (earlyUefi) "● 检测到 UEFI · 正在进显示器" else "● 原生显示已就绪"
                            stateView.setTextColor(GunTheme.ON_ACCENT)
                            stateView.background = GunTheme.pill(d, GunTheme.ACCENT)
                            portView.text = "内置原生显示 · 输入四通道"
                        }
                    }
                    Thread.sleep(if (earlyUefi) 150 else 600)
                    runOnUiThread { if (!isFinishing && !isDestroyed) jumpVnc(force = false) }
                    return@thread
                }
                // 端口探测本身要建 socket，别每 600ms 一次；1.5 秒一次足够
                if (c.vnc && now - lastPortCheck > 1500) {
                    lastPortCheck = now
                    activePort = GunyahManager.actualVncPort(this) ?: c.vncPort
                    portOk = portOpen(host, activePort)
                    if (portOk && c.autoDisplay && !jumped) {
                        jumped = true
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                stateView.text = "● 画面已就绪"
                                stateView.setTextColor(GunTheme.ON_ACCENT)
                                stateView.background = GunTheme.pill(d, GunTheme.ACCENT)
                                portView.text = "$host:$activePort 已连接"
                            }
                        }
                        Thread.sleep(900)   // 让用户看清「已就绪」再切，避免突兀
                        runOnUiThread { if (!isFinishing && !isDestroyed) jumpVnc(force = false) }
                        return@thread
                    }
                }

                val elapsed = (now - startedAt) / 1000
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    timeView.text = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                    renderLog(txt)
                    applyState(st, portOk, host, c)
                }
                Thread.sleep(600)
            }
        }
    }

    private fun portOpen(host: String, port: Int, timeoutMs: Int = 600): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    // ------------------------------ 渲染 ------------------------------

    private fun renderLog(raw: String) {
        if (raw.isEmpty()) {
            if (lastRendered.isEmpty()) {
                logView.text = "正在等待 crosvm 输出…\n\n（若长时间没有内容，脚本可能在抢启动锁或加载内核模块）"
                lastRendered = ""
            }
            return
        }
        // 只保留尾部 220 行：crosvm 的 stderr 会持续刷，全量渲染会越来越卡
        val lines = BootLog.clean(raw).lineSequence().toList()
        val tail = if (lines.size > 220) lines.subList(lines.size - 220, lines.size) else lines
        val key = tail.joinToString("\n")
        if (key == lastRendered) return
        lastRendered = key

        val sb = SpannableStringBuilder()
        for (ln in tail) {
            val start = sb.length
            sb.append(ln).append('\n')
            sb.setSpan(
                ForegroundColorSpan(colorOf(ln)), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        logView.text = sb
        if (autoFollow) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 日志着色：一眼能分出「失败/警告/进度/普通输出」。 */
    private fun colorOf(line: String): Int = when {
        line.contains("[✓]") || line.contains("启动成功") || line.contains("已拿到地址") ->
            GunTheme.ACCENT
        line.contains("[!!]") || line.contains("panicked") || line.contains("SHIM DIED") ||
            line.contains("failed") || line.contains("失败") -> GunTheme.DANGER
        line.contains("[!]") || line.contains("未监听") || line.contains("换下一组") -> GunTheme.WARN
        line.contains("[*]") || line.contains("=====") -> GunTheme.TEXT2
        else -> GunTheme.MUTED
    }

    private fun applyState(
        st: GunyahManager.VmStatus,
        portOk: Boolean,
        host: String,
        c: GunyahManager.Config,
    ) {
        if (DisplayLauncher.wantsNative(c)) {
            portView.text = "内置原生显示 · 无需 VNC"
        } else if (!c.vnc) {
            portView.text = "VNC 已关闭 · 仅显示日志"
        } else {
            val p = portNow(c)
            portView.text = if (portOk) "$host:$p 可连接" else "$host:$p 等待监听"
            portView.setTextColor(if (portOk) GunTheme.ACCENT else GunTheme.TEXT2)
        }

        when {
            st.kind == "failed" -> {
                stateView.text = "● 启动失败"
                stateView.setTextColor(GunTheme.ON_ACCENT)
                stateView.background = GunTheme.pill(d, GunTheme.DANGER)
                hintView.visibility = View.VISIBLE
                hintView.setTextColor(GunTheme.DANGER)
                hintView.text = "原因：" + st.reason.ifBlank { "见上方日志最后几行" } +
                        "\n可以点「复制日志」把完整内容发出来，或改启动配置后重新启动。"
            }
            // v2.0.19：UEFI 帧缓冲刚就绪、正在自动进显示器的短暂状态。
            st.kind == "uefi" -> {
                stateView.text = "● 检测到 UEFI · 正在进显示器"
                stateView.setTextColor(GunTheme.ON_ACCENT)
                stateView.background = GunTheme.pill(d, GunTheme.ACCENT)
                hintView.visibility = View.GONE
            }
            st.kind == "running" -> {
                stateView.text = "● 运行中"
                stateView.setTextColor(GunTheme.ON_ACCENT)
                stateView.background = GunTheme.pill(d, GunTheme.ACCENT)
                hintView.visibility = View.GONE
            }
            st.isBooting -> {
                // 「1/5」不是重试第几次，而是「第几种启动配置」。
                // 文案写成「兼容模式 2/5」，避免被理解成脚本在反复重启。
                val tag = if (st.total > 0) "兼容模式 ${st.idx}/${st.total}" else "启动中"
                stateView.text = "● $tag"
                stateView.setTextColor(GunTheme.ON_WARN)
                stateView.background = GunTheme.pill(d, GunTheme.WARN)
                hintView.visibility = View.VISIBLE
                hintView.setTextColor(GunTheme.TEXT2)
                hintView.text = "正在尝试第 ${st.idx} 种启动配置（共 ${st.total} 种）：保护模式 × 显示方式 × 声音 × 网络\n" +
                    "不同固件支持度不同，脚本按优先级依次试，成功就停 —— 不是在反复重启。"
            }
            else -> {
                stateView.text = "● 未运行"
                stateView.setTextColor(GunTheme.MUTED)
                stateView.background = GunTheme.pill(d, GunTheme.CHIP)
            }
        }
    }

    // ------------------------------ 动作 ------------------------------

    private fun copyLog() {
        val txt = runCatching {
            val f = logFile
            BootLog.readClean(f, 4 * 1024 * 1024)
        }.getOrDefault("")
        if (txt.isBlank()) {
            Toast.makeText(this, "日志还是空的，稍等一下", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("boot.log", txt))
        }
        Toast.makeText(this, "boot.log 已复制（${txt.length} 字符）", Toast.LENGTH_SHORT).show()
    }

    private fun jumpVnc(force: Boolean) {
        val c = cfg ?: return
        // v2.0: 原生显示后端优先，直接进主程序内置 NativeDisplayActivity；
        // 拉不起来（或用户手动切回）再走 VNC。
        // 注意这里不能用 !c.vnc 提前拦掉：android 后端下 VNC 只留作判活兜底。
        if (DisplayLauncher.wantsNative(c) && DisplayLauncher.launch(this)) {
            finish()
            return
        }
        if (!c.vnc) {
            Toast.makeText(this, "启动配置里 VNC 是关闭的", Toast.LENGTH_SHORT).show()
            return
        }
        val host = c.vncHost.ifBlank { "127.0.0.1" }
        val port = portNow(c)
        if (force) {
            Toast.makeText(this, "正在连接 $host:$port", Toast.LENGTH_SHORT).show()
        }
        val ok = runCatching {
            startVncActivity(this, VncUri("vnc://$host:$port"))
        }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(Intent(this, VncViewerActivity::class.java).apply {
                    putExtra(VncViewerActivity.EXTRA_HOST, host)
                    putExtra(VncViewerActivity.EXTRA_PORT, port)
                })
            }.onFailure { Toast.makeText(this, "内置查看器启动失败", Toast.LENGTH_SHORT).show() }
        }
        finish()
    }

    private fun stopVm() {
        thread {
            GunyahManager.stop(this)
            runOnUiThread {
                Toast.makeText(this, "已发送停止指令", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }
}
