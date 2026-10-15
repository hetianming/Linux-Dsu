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
import androidx.core.content.ContextCompat
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.PopupWindow
import android.content.Intent
import android.widget.EditText
import android.text.InputType
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.core.ChrootRunner
import com.mcai.ubuntudsu.core.AudioBridge
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.TerminalSessionStore
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.R
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
        val packagesDebian: String? = null,
        val commandCheck: String,
        val startup: String,
    )

    private val desktopOptions = listOf(
        DesktopOption(id = "xfce", name = "XFCE4", packages = "xfce4 xfce4-goodies xfce4-terminal", commandCheck = "startxfce4", startup = "exec startxfce4"),
        DesktopOption(id = "kde", name = "KDE Plasma", packages = "kde-full konsole plasma-session-x11", packagesDebian = "kde-full konsole plasma-workspace plasma-desktop", commandCheck = "startplasma-x11", startup = "exec dbus-launch startplasma-x11"),
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
                        val rootfsDir = Env.rootfs(this)
                        val candidates = listOf(
                            File(rootfsDir, "usr/bin/startplasma-x11"),
                            File(rootfsDir, "usr/bin/startxfce4"),
                            File(rootfsDir, "usr/bin/xsession"),
                        )
                        candidates.any { it.isFile }
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

        // 深色玻璃按键背景工厂
        fun darkKey(active: Boolean = false): android.graphics.drawable.GradientDrawable {
            val radius = 7f * density
            return android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius
                setColor(if (active) Color.argb(255, 40, 180, 100) else Color.argb(190, 34, 44, 68))
                setStroke(
                    (1 * density).toInt(),
                    if (active) Color.argb(180, 80, 220, 140) else Color.argb(90, 140, 170, 200),
                )
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(235, 20, 28, 50), Color.argb(235, 14, 21, 38)),
            ).apply {
                cornerRadius = 10f * density
                setStroke((1 * density).toInt(), Color.argb(60, 168, 214, 255))
            }
            setPadding((4 * density).toInt(), (5 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = (6 * density).toInt()
                marginEnd = (6 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        }
        val keys = listOf(
            listOf("Ctrl" to null, "Tab" to byteArrayOf(9), "PgUp" to byteArrayOf(27, 91, 53, 126), "Home" to byteArrayOf(27, 91, 72), "↑" to byteArrayOf(27, 91, 65), "End" to byteArrayOf(27, 91, 70), "Esc" to byteArrayOf(27)),
            listOf("Alt" to null, "PgDn" to byteArrayOf(27, 91, 54, 126), "←" to byteArrayOf(27, 91, 68), "↓" to byteArrayOf(27, 91, 66), "→" to byteArrayOf(27, 91, 67), "Enter" to byteArrayOf(13)),
        )
        var altActive = false
        val keyHeight = (24 * density).toInt()
        val rowGap = (4 * density).toInt()
        val keyGap = (2 * density).toInt()
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        // 第一行：安装桌面 + 启动桌面 + 菜单（与下方按键行同高）
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, rowGap, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, keyHeight + rowGap,
            )
        }
        fun actionPill(label: String, bgTop: Int, bgBottom: Int, onClick: (View) -> Unit): View {
            return TextView(this).apply {
                text = label
                textSize = 11f
                setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                includeFontPadding = false
                maxLines = 1
                isSingleLine = true
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(bgTop, bgBottom),
                ).apply {
                    cornerRadius = 7f * density
                    setStroke((1 * density).toInt(), Color.argb(110, 255, 255, 255))
                }
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(0, keyHeight, 1f).apply {
                    marginEnd = keyGap
                }
                setOnClickListener { onClick(this) }
                Ui.pressAnimation(this)
            }
        }
        actionRow.addView(
            actionPill("菜单", Color.rgb(96, 108, 134), Color.rgb(58, 68, 92)) { showTerminalMenu(it) },
        )
        actionRow.addView(actionPill("安装桌面", Color.rgb(200, 70, 70), Color.rgb(150, 45, 45)) { installVncDesktop() })
        actionRow.addView(actionPill("启动桌面", Color.rgb(70, 165, 150), Color.rgb(45, 125, 110)) { startVnc() })
        rows.addView(actionRow)
        // 分隔线：隔开功能排与按键排
        val divider = View(this).apply {
            background = android.graphics.drawable.ColorDrawable(Color.argb(80, 150, 190, 235))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt(),
            ).apply {
                topMargin = rowGap
                bottomMargin = rowGap
            }
        }
        rows.addView(divider)
        keys.forEachIndexed { rowIdx, keyRow ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                // 行高含行间距，按键贴顶，避免 clipToPadding 裁掉按键顶部
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (rowIdx < keys.lastIndex) keyHeight + rowGap else keyHeight,
                )
            }
            keyRow.forEach { (label, bytes) ->
                val key = TextView(this).apply {
                    text = label
                    textSize = 10f
                    setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                    includeFontPadding = false
                    maxLines = 1
                    isSingleLine = true
                    setHorizontallyScrolling(false)
                    gravity = Gravity.CENTER
                    setTextColor(Color.argb(230, 210, 225, 245))
                    background = darkKey(false)
                    layoutParams = LinearLayout.LayoutParams(0, keyHeight, 1f).apply {
                        marginEnd = if (label == keyRow.last()?.first) 0 else keyGap
                    }
                    setOnClickListener {
                        if (label == "Ctrl") {
                            ctrlKeyActive = !ctrlKeyActive
                            background = darkKey(ctrlKeyActive)
                        } else if (label == "Alt") {
                            altActive = !altActive
                            background = darkKey(altActive)
                        } else {
                            val output = if (ctrlKeyActive && bytes != null && bytes.size == 1) {
                                byteArrayOf((bytes[0].toInt() and 0x1f).toByte())
                            } else if (altActive && bytes != null) {
                                byteArrayOf(27) + bytes
                            } else bytes
                            output?.let { sendShortcut(it) }
                            if (ctrlKeyActive) {
                                ctrlKeyActive = false
                                background = darkKey(false)
                            }
                            if (altActive) {
                                altActive = false
                                background = darkKey(false)
                            }
                        }
                        terminalView.requestFocus()
                    }
                    Ui.pressAnimation(this)
                }
                row.addView(key)
            }
            rows.addView(row)
        }
        bar.addView(rows)
        return bar
    }

    private fun installVncDesktop() {
        Ui.showGlassChoiceDialog(
            this,
            "安装桌面环境",
            "选择要安装的桌面，安装完成后即可启动 VNC",
            desktopOptions.map { it.name },
            onPick = { which -> installVncDesktop(desktopOptions[which]) },
        )
    }

    private fun installVncDesktop(option: DesktopOption) {
                   val script = "export DEBIAN_FRONTEND=noninteractive; export LANG=C.UTF-8; export LC_ALL=C.UTF-8; . /etc/os-release 2>/dev/null; result=0; if [ \"${'$'}ID\" = ubuntu ]; then DESKTOP_EXTRA='language-pack-zh-hans language-pack-zh-hans-base language-pack-kde-zh-hans'; PKGS='${option.packages}'; elif [ \"${'$'}ID\" = debian ]; then DESKTOP_EXTRA=''; PKGS='${option.packagesDebian ?: option.packages}'; else echo \"[错误] 不支持的系统: ${'$'}ID\"; result=1; fi; if [ \"${'$'}result\" -eq 0 ]; then echo '[1/8] 修复 dpkg 状态'; dpkg --configure -a || true; echo '[2/8] 更新软件包索引'; apt-get update || result=1; echo '[3/8] 修复 dpkg 依赖'; apt-get install -y --fix-broken || result=1; echo '[3b/8] 安装桌面、VNC 和音频组件'; apt-get install -y ${'$'}PKGS dbus-x11 dbus-user-session locales ${'$'}DESKTOP_EXTRA fonts-noto-cjk fonts-wqy-microhei tigervnc-standalone-server tigervnc-common pulseaudio pulseaudio-utils || result=1; echo '[4/8] 完成 dpkg 配置'; dpkg --configure -a || result=1; echo '[5/8] 生成中文 locale'; sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' /etc/locale.gen || result=1; grep -q '^zh_CN.UTF-8 UTF-8' /etc/locale.gen || printf '%s\\n' 'zh_CN.UTF-8 UTF-8' >> /etc/locale.gen; locale-gen zh_CN.UTF-8 || result=1; echo '[5b/8] 固化全中文环境'; printf '%s\\n' 'LANG=zh_CN.UTF-8' 'LANGUAGE=zh_CN:en' 'LC_ALL=' > /etc/environment; update-locale LANG=zh_CN.UTF-8 || true; echo '[5c/8] 设 KDE 默认语言为中文'; printf '%s\\n' '[KDE]' 'Language=zh_CN' > /root/.config/kdeglobals; sed -i 's/^LANG=.*/LANG=zh_CN.UTF-8/' /root/.bashrc 2>/dev/null || true; grep -q '^export LANG=zh_CN.UTF-8' /root/.bashrc || printf '%s\\n' 'export LANG=zh_CN.UTF-8' 'export LANGUAGE=zh_CN:en' 'export LC_ALL=' >> /root/.bashrc; echo '[7/8] 配置 VNC 启动脚本'; mkdir -p /root/.vnc /root/.config/tigervnc || result=1; printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'export HOME=/root' 'export XDG_VNC_SESSION=1' 'export DISPLAY=${'$'}{DISPLAY:-:1}' 'export LANG=zh_CN.UTF-8' 'export LANGUAGE=zh_CN:en' 'export LC_ALL=' 'export KDE_FULL_SESSION=1' 'export QT_X11_NO_MITSHM=1' 'export QT_QPA_PLATFORM=xcb' 'if command -v ${option.commandCheck} >/dev/null 2>&1; then exec ${option.startup.removePrefix("exec ")}; fi' 'command -v startplasma-x11 >/dev/null 2>&1 && exec dbus-launch startplasma-x11; command -v startxfce4 >/dev/null 2>&1 && exec startxfce4; exit 1' > /root/.vnc/xstartup || result=1; cp -f /root/.vnc/xstartup /root/.config/tigervnc/ 2>/dev/null || true; chmod +x /root/.vnc/xstartup || result=1; echo '[8/8] 配置 VNC 免密'; mkdir -p /root/.config/tigervnc; printf 'securitytypes=none\\n' > /root/.config/tigervnc/config 2>/dev/null || true; printf 'securitytypes=none\\n' > /root/.vnc/config 2>/dev/null || true; fi; if [ \"${'$'}result\" -eq 0 ]; then echo '[成功] ${option.name} 桌面、VNC 和音频支持安装完成'; else echo '[失败] ${option.name} 桌面、VNC 或音频安装'; fi"
         sendVisibleCommand(script)
        Toast.makeText(this, "已发送 ${option.name} 和中文环境安装脚本", Toast.LENGTH_SHORT).show()
        focusTerminalAndShowKeyboard()
    }

    private fun startVnc() {
        Ui.showGlassChoiceDialog(
            this,
            "启动 VNC 桌面",
            "选择桌面环境",
            desktopOptions.map { it.name },
            onPick = { which -> chooseVncResolution(desktopOptions[which]) },
        )
    }

    private fun chooseVncResolution(option: DesktopOption) {
        Ui.showGlassChoiceDialog(
            this,
            "VNC 分辨率",
            option.name,
            listOf("竖屏：720x1584", "横屏：1584x720", "自定义分辨率"),
            onPick = { which ->
                when (which) {
                    0 -> startVnc(720, 1584, "portrait", option)
                    1 -> startVnc(1584, 720, "landscape", option)
                    2 -> showCustomResolutionDialog(option)
                }
            },
        )
    }

    private fun showCustomResolutionDialog(option: DesktopOption) {
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.frostedSurface(this@TerminalActivity, 24f)
            setPadding(Ui.dp(16, density), Ui.dp(16, density), Ui.dp(16, density), Ui.dp(12, density))
            layoutParams = android.view.ViewGroup.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.6f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        panel.addView(TextView(this).apply {
            text = "自定义 VNC 分辨率"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(this@TerminalActivity))
        })
        panel.addView(TextView(this).apply {
            text = "输入宽度和高度（像素），如 1920 x 1080"
            textSize = 11f
            setTextColor(Ui.secondaryText(this@TerminalActivity))
            setPadding(0, 0, 0, Ui.dp(8, density))
        })
        val widthInput = android.widget.EditText(this).apply {
            hint = "宽度"
            textSize = 14f
            setTextColor(Ui.primaryText(this@TerminalActivity))
            setHintTextColor(Ui.secondaryText(this@TerminalActivity))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = Ui.neuInset(this@TerminalActivity, 10f)
            setPadding(Ui.dp(12, density), Ui.dp(10, density), Ui.dp(12, density), Ui.dp(10, density))
            isSingleLine = true
        }
        panel.addView(widthInput, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(8, density) })
        val heightInput = android.widget.EditText(this).apply {
            hint = "高度"
            textSize = 14f
            setTextColor(Ui.primaryText(this@TerminalActivity))
            setHintTextColor(Ui.secondaryText(this@TerminalActivity))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = Ui.neuInset(this@TerminalActivity, 10f)
            setPadding(Ui.dp(12, density), Ui.dp(10, density), Ui.dp(12, density), Ui.dp(10, density))
            isSingleLine = true
        }
        panel.addView(heightInput)
        val dialog = android.app.Dialog(this).apply {
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setDimAmount(0.45f)
            setContentView(panel)
            setCanceledOnTouchOutside(true)
        }
        val okBtn = TextView(this).apply {
            text = "确定"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor(Ui.buttonText(this@TerminalActivity))
            background = Ui.neuSolidButton(
                0xFF4A90E2.toInt(), 0xFF2E5FB8.toInt(), 12f, this@TerminalActivity,
            )
            setPadding(0, Ui.dp(10, density), 0, Ui.dp(10, density))
            setOnClickListener {
                val w = widthInput.text.toString().toIntOrNull() ?: 0
                val h = heightInput.text.toString().toIntOrNull() ?: 0
                if (w < 320 || h < 200 || w > 3840 || h > 2160) {
                    android.widget.Toast.makeText(this@TerminalActivity, "分辨率范围 320x200 ~ 3840x2160", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                val orientation = if (w > h) "landscape" else "portrait"
                startVnc(w, h, orientation, option)
            }
        }
        panel.addView(okBtn, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(10, density) })
        val cancelBtn = TextView(this).apply {
            text = "取消"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor(Ui.secondaryText(this@TerminalActivity))
            background = Ui.neuInset(this@TerminalActivity, 10f)
            setPadding(0, Ui.dp(8, density), 0, Ui.dp(8, density))
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(cancelBtn, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(6, density) })
        dialog.show()
    }

    private fun startVnc(width: Int, height: Int, orientation: String, option: DesktopOption) {
        terminalView.postDelayed({
         audioBridge?.stop()
          audioBridge = AudioBridge(Env.audioPipe(this)).also { it.start() }
                   val startupCommand = "result=0; audio=0; command -v ${option.commandCheck} >/dev/null 2>&1 || { echo \"[警告] 找不到桌面启动命令 ${option.commandCheck}，仍将尝试启动 VNC\"; }; command -v vncserver >/dev/null 2>&1 || { echo '[错误] 找不到 vncserver'; result=1; }; if [ \"${'$'}result\" -eq 0 ]; then export XDG_RUNTIME_DIR=/run/user/0; export VNCUSERCONFIGDIR=/root/.vnc; export PULSE_SERVER=unix:/run/pulse/native; mkdir -p \"${'$'}XDG_RUNTIME_DIR\" /run/pulse; echo '[音频] 检查 Android PCM FIFO'; if [ ! -p /run/android-audio.pcm ]; then echo '[音频错误] /run/android-audio.pcm 不是 FIFO'; audio=1; fi; echo '[音频] 启动 PulseAudio 用户模式'; command -v pulseaudio >/dev/null 2>&1 || { echo '[音频错误] 找不到 pulseaudio'; audio=1; }; if [ \"${'$'}audio\" -eq 0 ]; then pulseaudio --check >/dev/null 2>&1 || pulseaudio --daemonize=true --exit-idle-time=-1 --load='module-native-protocol-unix socket=/run/pulse/native auth-anonymous=1' 2>&1 || { echo '[音频错误] PulseAudio 启动失败'; audio=1; }; echo '[音频] 等待 PulseAudio socket'; ready=0; for attempt in 1 2 3 4 5; do pactl --server=unix:/run/pulse/native info >/dev/null 2>&1 && ready=1 && break; sleep 0.2; done; if [ \"${'$'}ready\" -eq 0 ]; then echo '[音频错误] pactl 无法连接 PulseAudio'; audio=1; else echo '[音频] 创建 android_audio sink'; pactl --server=unix:/run/pulse/native list short sinks; pactl --server=unix:/run/pulse/native load-module module-pipe-sink sink_name=android_audio format=s16le rate=44100 channels=2 file=/run/android-audio.pcm 2>&1 || true; pactl --server=unix:/run/pulse/native set-default-sink android_audio 2>&1 || true; echo '[音频] 当前 sink'; pactl --server=unix:/run/pulse/native list short sinks; fi; fi; echo '[VNC] 清理旧的 :1 display'; vncserver -kill :1 2>/dev/null || true; rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 /root/.vnc/*:1.log /root/.vnc/*:1.pid; mkdir -p /root/.config/tigervnc /root/.vnc || result=1; cp -f /root/.vnc/xstartup /root/.config/tigervnc/ 2>/dev/null || true; printf 'securitytypes=none\\n' > /root/.config/tigervnc/config 2>/dev/null || true; printf 'securitytypes=none\\n' > /root/.vnc/config 2>/dev/null || true; printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'export HOME=/root' 'export XDG_VNC_SESSION=1' 'export DISPLAY=${'$'}{DISPLAY:-:1}' 'export PULSE_SERVER=unix:/run/pulse/native' 'export PULSE_SINK=android_audio' 'export KDE_FULL_SESSION=1' 'export QT_X11_NO_MITSHM=1' 'export QT_QPA_PLATFORM=xcb' 'if command -v ${option.commandCheck} >/dev/null 2>&1; then exec ${option.startup.removePrefix("exec ")}; fi' 'command -v startplasma-x11 >/dev/null 2>&1 && exec dbus-launch startplasma-x11; command -v startxfce4 >/dev/null 2>&1 && exec startxfce4; exit 1' > /root/.config/tigervnc/xstartup || result=1; printf '%s\\n' '#!/bin/sh' 'unset SESSION_MANAGER' 'unset DBUS_SESSION_BUS_ADDRESS' 'export HOME=/root' 'export XDG_VNC_SESSION=1' 'export DISPLAY=${'$'}{DISPLAY:-:1}' 'export PULSE_SERVER=unix:/run/pulse/native' 'export PULSE_SINK=android_audio' 'export KDE_FULL_SESSION=1' 'export QT_X11_NO_MITSHM=1' 'export QT_QPA_PLATFORM=xcb' 'if command -v ${option.commandCheck} >/dev/null 2>&1; then exec ${option.startup.removePrefix("exec ")}; fi' 'command -v startplasma-x11 >/dev/null 2>&1 && exec dbus-launch startplasma-x11; command -v startxfce4 >/dev/null 2>&1 && exec startxfce4; exit 1' > /root/.vnc/xstartup || result=1; chmod +x /root/.vnc/xstartup || result=1; echo '[VNC] 启动 display :1'; nohup vncserver :1 -SecurityTypes none -geometry ${width}x${height} -depth 24 -xstartup /root/.vnc/xstartup </dev/null >/tmp/vnc-start.log 2>&1 & echo \"[VNC] 已后台启动，日志 /tmp/vnc-start.log\"; fi; if [ \"${'$'}result\" -eq 0 ]; then echo '[成功] ${option.name} 桌面已启动'; [ \"${'$'}audio\" -eq 0 ] && echo '[成功] 音频桥接已启用' || echo '[警告] 桌面已启动，但 PulseAudio 音频桥接失败'; else echo '[失败] ${option.name} 桌面启动'; fi"
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
                     password = "",
                     securityType = 0,
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
            val commandBytes = (clearPreviousLine + command + " ; stty echo 2>/dev/null\n").toByteArray()
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
            // 拟态深色玻璃菜单：渐变底 + 霓虹高光边
            background = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.rgb(24, 33, 55), Color.rgb(16, 23, 41)),
                    ).apply { cornerRadius = (14 * density) },
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = (14 * density)
                        setStroke((1 * density).toInt(), Color.argb(60, 111, 168, 255))
                    },
                ),
            )
            // 圆角 outline：PopupWindow 投影随 content view outline 走，否则是方形影子
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 14 * density)
                }
            }
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
            // 按菜单按钮实际屏幕坐标向上弹：弹窗底部悬在按钮上方 8dp，左缘与按钮对齐，完全避开快捷键栏
            val anchorLoc = IntArray(2)
            anchor.getLocationOnScreen(anchorLoc)
            val rootLoc = IntArray(2)
            anchor.rootView.getLocationOnScreen(rootLoc)
            val y = (rootLoc[1] + anchor.rootView.height) - anchorLoc[1] + Ui.dp(8, density)
            showAtLocation(anchor, Gravity.BOTTOM or Gravity.START, anchorLoc[0], y)
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
            val (executable, args) = ChrootRunner.terminalLaunchSpec(this, rootfs)
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
