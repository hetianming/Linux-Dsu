package com.mcai.ubuntudsu

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.PopupWindow
import android.content.Intent
import android.widget.EditText
import android.text.InputType
import java.net.InetSocketAddress
import java.net.Socket
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.core.ChrootRunner
import com.mcai.ubuntudsu.core.AudioBridge
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.RootShell
import com.mcai.ubuntudsu.core.TerminalSessionStore
import com.mcai.ubuntudsu.ui.Ui
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalActivity : AppCompatActivity(), TerminalSessionClient, TerminalViewClient {
    companion object {
        const val EXTRA_DESKTOP = "extra_desktop"
    }

    private data class DesktopOption(
        val id: String,
        val name: String,
        val packages: String,
        val commandCheck: String,
        val startup: String,
    )

    private val desktopOptions = listOf(
        DesktopOption("xfce", "XFCE4", "xfce4 xfce4-goodies xfce4-terminal", "startxfce4", "exec startxfce4"),
        DesktopOption("kde", "KDE Plasma", "kde-full konsole", "startplasma-x11", "exec startplasma-x11"),
        DesktopOption("gnome", "GNOME", "ubuntu-desktop gnome-terminal", "gnome-shell", "exec env GNOME_SHELL_SESSION_MODE=ubuntu XDG_CURRENT_DESKTOP=ubuntu:GNOME XDG_SESSION_DESKTOP=ubuntu XDG_SESSION_TYPE=x11 dbus-run-session -- gnome-shell --x11"),
    )
    private var session: TerminalSession? = null
    private lateinit var terminalView: TerminalView
    private var audioBridge: AudioBridge? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TerminalSessionStore.setTarget(this)
        title = "Linux - Dsu 终端"
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        if (!Env.ubuntuInstalled(this)) {
            Toast.makeText(this, "请先安装 Ubuntu rootfs，请回到主页 Linux 页签完成安装", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        terminalView = TerminalView(this, null)
        terminalView.setTextSize(Ui.dp(12, resources.displayMetrics.density))
        terminalView.setTerminalViewClient(this)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        val terminalContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        terminalContainer.addView(
            terminalView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        terminalContainer.addView(createShortcutBar())
        frame.addView(terminalContainer)
        setContentView(frame)

        terminalView.post {
            startSession()
        }
        // 从 Linux 页"桌面环境"入口进入时：已装桌面则直接弹启动菜单，未装才弹安装菜单
        if (intent.getBooleanExtra(EXTRA_DESKTOP, false)) {
            terminalView.postDelayed({
                Thread {
                    val installed = runCatching {
                        val xstartup = java.io.File(Env.rootfs(this), "root/.vnc/xstartup")
                        RootShell.exec("test -f '${xstartup.absolutePath}' && test -x '${xstartup.absolutePath}'", timeoutMs = 15000).success
                    }.getOrDefault(false)
                    runOnUiThread {
                        if (installed) startVnc() else installVncDesktop()
                    }
                }.start()
            }, 600)
        }
    }

    private fun createShortcutBar(): View {
        val density = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(30, 30, 30))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt(),
            )
        }
        val keys = listOf(
            listOf("Esc" to byteArrayOf(27), "Tab" to byteArrayOf(9), "PgUp" to byteArrayOf(27, 91, 53, 126), "Home" to byteArrayOf(27, 91, 72), "↑" to byteArrayOf(27, 91, 65), "End" to byteArrayOf(27, 91, 70), "Ctrl" to null),
            listOf("Alt" to null, "PgDn" to byteArrayOf(27, 91, 54, 126), "←" to byteArrayOf(27, 91, 68), "↓" to byteArrayOf(27, 91, 66), "→" to byteArrayOf(27, 91, 67), "Enter" to byteArrayOf(13)),
        )
        var altActive = false
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        keys.forEach { keyRow ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            keyRow.forEach { (label, bytes) ->
                val key = TextView(this).apply {
                    text = label
                    textSize = 8f
                    includeFontPadding = false
                    maxLines = 1
                    isSingleLine = true
                    setHorizontallyScrolling(false)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = Ui.glassButton(this@TerminalActivity)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = (2 * density).toInt()
                    }
                    setOnClickListener {
                        if (label == "Ctrl") {
                            ctrlKeyActive = !ctrlKeyActive
                            background = Ui.glassButton(this@TerminalActivity, if (ctrlKeyActive) Color.rgb(80, 220, 140) else null)
                        } else if (label == "Alt") {
                            altActive = !altActive
                            background = Ui.glassButton(this@TerminalActivity, if (altActive) Color.rgb(80, 220, 140) else null)
                        } else {
                            val output = if (ctrlKeyActive && bytes != null && bytes.size == 1) {
                                byteArrayOf((bytes[0].toInt() and 0x1f).toByte())
                            } else if (altActive && bytes != null) {
                                byteArrayOf(27) + bytes
                            } else bytes
                            output?.let { sendShortcut(it) }
                            if (ctrlKeyActive) {
                                ctrlKeyActive = false
                                background = Ui.glassButton(this@TerminalActivity)
                            }
                            if (altActive) altActive = false
                        }
                        terminalView.requestFocus()
                    }
                    Ui.pressAnimation(this)
                }
                row.addView(key)
            }
            rows.addView(row)
        }
        val menu = TextView(this).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            setOnClickListener { showTerminalMenu(this) }
        }
        val vncInstall = TextView(this).apply {
            text = "桌面 + VNC"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ui.rounded(Color.rgb(45, 100, 170), 3f, density)
            layoutParams = LinearLayout.LayoutParams((54 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = (2 * density).toInt()
            }
            setOnClickListener { installVncDesktop() }
        }
        val vncOpen = TextView(this).apply {
            text = "启动 VNC"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ui.rounded(Color.rgb(45, 130, 100), 3f, density)
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = (2 * density).toInt()
            }
            setOnClickListener { startVnc() }
        }
        bar.addView(rows)
        bar.addView(vncInstall)
        bar.addView(vncOpen)
        bar.addView(menu, LinearLayout.LayoutParams((44 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        return bar
    }

    private fun installVncDesktop() {
        val labels = desktopOptions.map { option -> option.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要安装的桌面环境")
            .setItems(labels) { _, which -> installVncDesktop(desktopOptions[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun installVncDesktop(option: DesktopOption) {
         val script = "export DEBIAN_FRONTEND=noninteractive; export LANG=C.UTF-8; export LC_ALL=C.UTF-8; . /etc/os-release 2>/dev/null; result=0; if [ \"${'$'}ID\" = ubuntu ]; then DESKTOP_EXTRA='language-pack-zh-hans language-pack-zh-hans-base language-pack-gnome-zh-hans language-pack-kde-zh-hans'; elif [ \"${'$'}ID\" = debian ]; then DESKTOP_EXTRA=''; else echo \"[错误] 不支持的系统: ${'$'}ID\"; result=1; fi; if [ \"${'$'}result\" -eq 0 ]; then echo '[1/8] 修复 dpkg 状态'; dpkg --configure -a || true; echo '[2/8] 更新软件包索引'; apt-get update || result=1; echo '[3/8] 安装桌面、VNC 和音频组件'; apt-get install -y --fix-broken ${option.packages} dbus-x11 dbus-user-session locales ${'$'}DESKTOP_EXTRA fonts-noto-cjk fonts-wqy-microhei tigervnc-standalone-server tigervnc-common pulseaudio pulseaudio-utils || result=1; echo '[4/8] 完成 dpkg 配置'; dpkg --configure -a || result=1; echo '[5/8] 生成中文 locale'; sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' /etc/locale.gen || result=1; grep -q '^zh_CN.UTF-8 UTF-8' /etc/locale.gen || printf '%s\\n' 'zh_CN.UTF-8 UTF-8' >> /etc/locale.gen; locale-gen zh_CN.UTF-8 || result=1; echo '[6/8] 配置 hosts'; printf '%s\\n' '127.0.0.1 localhost localhost.localdomain Ubuntu' '127.0.1.1 Ubuntu' '::1 localhost localhost.localdomain ip6-localhost ip6-loopback' > /etc/hosts || result=1; echo '[7/8] 配置 VNC 启动脚本'; mkdir -p /root/.vnc || result=1; printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'exec ${option.startup.removePrefix("exec ")}' > /root/.vnc/xstartup || result=1; chmod +x /root/.vnc/xstartup || result=1; echo '[8/8] 设置 VNC 密码'; printf '123456\\n123456\\nn\\n' | vncpasswd || result=1; fi; if [ \"${'$'}result\" -eq 0 ]; then echo '[成功] ${option.name} 桌面、VNC 和音频支持安装完成'; else echo '[失败] ${option.name} 桌面、VNC 或音频安装'; fi"
         sendVisibleCommand(script)
        Toast.makeText(this, "已发送 ${option.name} 和中文环境安装脚本", Toast.LENGTH_SHORT).show()
        focusTerminalAndShowKeyboard()
    }

    private fun startVnc() {
        AlertDialog.Builder(this)
            .setTitle("选择桌面环境")
            .setItems(desktopOptions.map { option -> option.name }.toTypedArray()) { _, which ->
                chooseVncResolution(desktopOptions[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseVncResolution(option: DesktopOption) {
        AlertDialog.Builder(this)
            .setTitle("VNC 分辨率")
            .setItems(arrayOf("竖屏：720x1584", "横屏：1584x720")) { _, which ->
                if (which == 0) startVnc(720, 1584, "portrait", option)
                else startVnc(1584, 720, "landscape", option)
            }
            .show()
    }

    private fun startVnc(width: Int, height: Int, orientation: String, option: DesktopOption) {
        terminalView.postDelayed({
         audioBridge?.stop()
         audioBridge = AudioBridge(Env.audioPipe(this)).also { it.start() }
         val startupCommand = "result=0; audio=0; command -v ${option.commandCheck} >/dev/null 2>&1 || { echo '[错误] 找不到桌面启动命令'; result=1; }; command -v vncserver >/dev/null 2>&1 || { echo '[错误] 找不到 vncserver'; result=1; }; if [ \"${'$'}result\" -eq 0 ]; then export XDG_RUNTIME_DIR=/run/user/0; export PULSE_SERVER=unix:/run/pulse/native; mkdir -p \"${'$'}XDG_RUNTIME_DIR\" /run/pulse; echo '[音频] 检查 Android PCM FIFO'; if [ ! -p /run/android-audio.pcm ]; then echo '[音频错误] /run/android-audio.pcm 不是 FIFO'; audio=1; fi; echo '[音频] 启动 PulseAudio 用户模式'; command -v pulseaudio >/dev/null 2>&1 || { echo '[音频错误] 找不到 pulseaudio'; audio=1; }; if [ \"${'$'}audio\" -eq 0 ]; then pulseaudio --check >/dev/null 2>&1 || pulseaudio --daemonize=true --exit-idle-time=-1 --load='module-native-protocol-unix socket=/run/pulse/native auth-anonymous=1' 2>&1 || { echo '[音频错误] PulseAudio 启动失败'; audio=1; }; echo '[音频] 等待 PulseAudio socket'; ready=0; for attempt in 1 2 3 4 5; do pactl --server=unix:/run/pulse/native info >/dev/null 2>&1 && ready=1 && break; sleep 0.2; done; if [ \"${'$'}ready\" -eq 0 ]; then echo '[音频错误] pactl 无法连接 PulseAudio'; audio=1; else echo '[音频] 创建 android_audio sink'; pactl --server=unix:/run/pulse/native list short sinks; pactl --server=unix:/run/pulse/native load-module module-pipe-sink sink_name=android_audio format=s16le rate=44100 channels=2 file=/run/android-audio.pcm || audio=1; pactl --server=unix:/run/pulse/native set-default-sink android_audio || audio=1; echo '[音频] 当前 sink'; pactl --server=unix:/run/pulse/native list short sinks; fi; fi; echo '[VNC] 清理旧的 :1 display'; vncserver -kill :1 || true; rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 ~/.vnc/*:1.log ~/.vnc/*:1.pid; mkdir -p ~/.vnc || result=1; printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'export PULSE_SERVER=unix:/run/pulse/native' 'export PULSE_SINK=android_audio' 'exec ${option.startup.removePrefix("exec ")}' > ~/.vnc/xstartup || result=1; chmod +x ~/.vnc/xstartup || result=1; echo '[VNC] 启动 display :1'; vncserver :1 -geometry ${width}x${height} -depth 24 || result=1; fi; if [ \"${'$'}result\" -eq 0 ]; then echo '[成功] ${option.name} 桌面已启动'; [ \"${'$'}audio\" -eq 0 ] && echo '[成功] 音频桥接已启用' || echo '[警告] 桌面已启动，但 PulseAudio 音频桥接失败'; else echo '[失败] ${option.name} 桌面启动'; fi"
            sendHiddenCommand(startupCommand)
            Toast.makeText(this, "正在启动 ${option.name} VNC", Toast.LENGTH_SHORT).show()
            return@postDelayed

         }, 200)
        Thread {
            var ready = false
            Thread.sleep(1500)
            for (attempt in 0 until 30) {
                if (runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", 5901), 500)
                    }
                    true
                }.getOrDefault(false)) {
                    ready = true
                    break
                }
                if (attempt < 29) Thread.sleep(500)
            }
            runOnUiThread {
                 if (!ready) {
                    Toast.makeText(this, "VNC 服务未监听 5901 端口，请查看终端错误信息", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                 }
                 val profile = com.gaurav.avnc.model.ServerProfile(
                    name = "Ubuntu DSU",
                    host = "127.0.0.1",
                     port = 5901,
                     password = "123456",
                     securityType = 2,
                     useRawEncoding = false,
                    gestureStyle = "touchpad",
                     resizeRemoteDesktop = false,
                     screenOrientation = orientation,
                )
                com.gaurav.avnc.ui.vnc.startVncActivity(this, profile)
            }
        }.start()
    }

    private fun sendHiddenCommand(command: String) {
        val disableEcho = "stty -echo 2>/dev/null\n"
        session?.write(disableEcho.toByteArray(), 0, disableEcho.toByteArray().size)
        terminalView.postDelayed({
            val clearPreviousLine = "\u001b[2J\u001b[H"
            val commandBytes = (clearPreviousLine + command + "\n").toByteArray()
            session?.write(commandBytes, 0, commandBytes.size)
        }, 150)
    }

    private fun sendVisibleCommand(command: String) {
        val commandBytes = (command + "\n").toByteArray()
        session?.write(commandBytes, 0, commandBytes.size)
    }

    private var ctrlKeyActive = false

    private fun sendShortcut(bytes: ByteArray) {
        session?.takeIf { it.isRunning }?.write(bytes, 0, bytes.size)
    }

    private fun showTerminalMenu(anchor: View) {
        val density = resources.displayMetrics.density
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (8 * density).toInt(), (18 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.rgb(52, 52, 52))
        }
        fun item(label: String, action: () -> Unit) {
            menu.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (12 * density).toInt(), (42 * density).toInt(), (12 * density).toInt())
                setOnClickListener { action() }
            })
        }
        var popup: PopupWindow? = null
        item("唤醒锁") { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); popup?.dismiss() }
        item("结束") {
            session?.write("exit\n".toByteArray(), 0, 5)
            terminalView.postDelayed({ session?.finishIfRunning() }, 3000)
            popup?.dismiss()
        }
        item("重置") { session?.reset(); terminalView.onScreenUpdated(); popup?.dismiss() }
        item("粘贴") { onPasteTextFromClipboard(session); popup?.dismiss() }
        item("后台运行") {
            popup?.dismiss()
            finish()
        }
        popup = PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = (8 * density)
            showAtLocation(anchor, Gravity.BOTTOM or Gravity.END, 0, anchor.height + Ui.dp(8, density))
        }
    }

    private fun focusTerminalAndShowKeyboard() {
        terminalView.requestFocus()
        terminalView.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun startSession() {
        val existing = TerminalSessionStore.takeRunning()
        if (existing != null) {
            session = existing
            existing.updateTerminalSessionClient(TerminalSessionStore.client)
        } else {
            val rootfs = Env.rootfs(this)
            val (executable, args) = ChrootRunner.terminalLaunchSpec(rootfs)
            session = TerminalSession(executable, "/", args, ChrootRunner.terminalEnvironment(), 2000, TerminalSessionStore.client)
            TerminalSessionStore.session = session
        }
        terminalView.attachSession(session)
        terminalView.requestFocus()
    }

    override fun onDestroy() {
        audioBridge?.stop()
        TerminalSessionStore.setTarget(null)
        super.onDestroy()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (TerminalSessionStore.session === finishedSession) {
            TerminalSessionStore.session = null
        }
        val code = finishedSession.exitStatus
        runOnUiThread {
            Toast.makeText(this, "进程已结束 (code $code)", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int =
        com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {}

    override fun logWarn(tag: String, message: String) {}

    override fun logInfo(tag: String, message: String) {}

    override fun logDebug(tag: String, message: String) {}

    override fun logVerbose(tag: String, message: String) {}

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}

    override fun logStackTrace(tag: String, e: Exception) {}

    override fun onSingleTapUp(e: MotionEvent?) {
        focusTerminalAndShowKeyboard()
    }

    override fun onScale(scale: Float): Float = scale

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = ctrlKeyActive

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        terminalView.onScreenUpdated()
    }
}
