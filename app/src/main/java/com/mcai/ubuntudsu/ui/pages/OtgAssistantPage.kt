package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.RootfsFilesActivity
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.OtgAssistant
import com.mcai.ubuntudsu.core.ShellResult
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class OtgAssistantPage(
    private val activity: Activity,
    private val onDismiss: (() -> Unit)? = null,
) {
    private val d: Float get() = activity.resources.displayMetrics.density
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(true)

    // === ADB ===
    private lateinit var adbDeviceList: LinearLayout
    private var selectedAdbSerial: String = ""
    private lateinit var adbLogView: TextView
    private var pendingAdbFileAction: ((String) -> Unit)? = null

    // === Fastboot ===
    private lateinit var fbDeviceList: LinearLayout
    private var selectedFbSerial: String = ""
    private lateinit var fbLogView: TextView
    private var pendingFbFileAction: ((String) -> Unit)? = null

    // === 进度条 ===
    private lateinit var progressBar: ProgressBar

    // === Tab 选中 ===
    private var currentTab = 0
    private var tabBtns: List<TextView> = emptyList()

    // ==================== 入口 ====================

    fun build(): View {
        val root = ScrollView(activity).apply {
            addView(buildContent())
        }
        return root
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(4, d))
        }

        // 标题栏
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(8, d))
            addView(TextView(activity).apply {
                text = "<"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                gravity = Gravity.CENTER
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
                Ui.pressAnimation(this)
                setOnClickListener { activity.onBackPressed() }
            })
            val t = TextView(activity).apply {
                text = "OTG 助手"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(t)
        })

        // Tab 栏
        val tabs = listOf("ADB", "Fastboot")
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.neuCard(activity, 12f)
            setPadding(Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(8, d) }
        }
        val tabBtnsList = tabs.map { label ->
            TextView(activity).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.secondaryText(activity))
                gravity = Gravity.CENTER
                background = Ui.glassButton(activity, null)
                Ui.pressAnimation(this)
                setPadding(Ui.dp(0, d), Ui.dp(8, d), Ui.dp(0, d), Ui.dp(8, d))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(tabs.indexOf(label)) }
            }
        }
        tabBtns = tabBtnsList
        tabBtns[0].setTextColor(Ui.buttonPrimary(activity))
        tabBtns.forEach { tabRow.addView(it) }
        root.addView(tabRow)

        // ADB 内容
        val adbContent = buildAdbContent()
        adbContent.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(8, d) }
        root.addView(adbContent)

        // Fastboot 内容
        val fbContent = buildFbContent()
        fbContent.visibility = View.GONE
        fbContent.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(8, d) }
        root.addView(fbContent)

        // 进度条
        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(4, d)
            ).apply { topMargin = Ui.dp(8, d) }
            visibility = View.GONE
        }
        root.addView(progressBar)

        currentTab = 0
        return root
    }

    private fun selectTab(idx: Int) {
        currentTab = idx
        tabBtns?.forEachIndexed { i, btn ->
            btn.setTextColor(if (i == idx) Ui.buttonPrimary(activity) else Ui.secondaryText(activity))
        }
        // 重新构建内容（简单方式）
        rebuildTabs()
    }

    private fun rebuildTabs() {
        // 清空并重建 tab 内容区域（由于 ScrollView 直接 addView，需要替换）
        val root = findRoot() ?: return
        // 移除第 2 个及以后的子 view（tab content + progress）
        while (root.childCount > 2) root.removeViewAt(2)
        val tabs = listOf("ADB", "Fastboot")
        if (currentTab == 0) {
            val c = buildAdbContent()
            c.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(8, d) }
            root.addView(c)
        } else {
            val c = buildFbContent()
            c.visibility = View.GONE
            c.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(8, d) }
            root.addView(c)
        }
        root.addView(progressBar)
    }

    private fun findRoot(): LinearLayout? {
        // 从 scrollView 的 child 找到根 LinearLayout
        val sv = (activity.findViewById<View>(android.R.id.content) as? ViewGroup)?.getChildAt(0) as? ScrollView
            ?: return null
        return sv.getChildAt(0) as? LinearLayout
    }

    // ==================== ADB 区 ====================

    private fun buildAdbContent(): LinearLayout {
        val out = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // --- 设备列表 ---
        out.addView(section("设备列表", "").apply {
            adbDeviceList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(80, d)
                )
            }
            addView(adbDeviceList)
            addView(actionBtn("刷新设备", Ui.buttonPrimary(activity)) { refreshAdbDevices() })
        })

        // --- ADB 服务控制 ---
        out.addView(section("ADB 服务", "").apply {
            addView(row3btn(
                actionBtn("启用", Ui.buttonSuccess(activity)) { runAdbCmd("start-server"); appendAdbLog("已启用 ADB 服务") },
                actionBtn("关闭", Ui.buttonDanger(activity)) { runAdbCmd("kill-server"); appendAdbLog("已关闭 ADB 服务") },
                actionBtn("重启", Ui.buttonWarning(activity)) { runAdbCmd("kill-server"); runAdbCmd("start-server"); appendAdbLog("已重启 ADB 服务") },
            ))
        })

        // --- 无线 ADB ---
        out.addView(section("无线 ADB 连接", "").apply {
            val ipInput = EditText(activity).apply {
                hint = "请输入 IP:端口，如 192.168.1.100:5555"
                textSize = 12f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(4, d).toInt() }
                isFocusable = false
            }
            addView(ipInput)
            addView(row2btn(
                actionBtn("连接", Ui.buttonPrimary(activity)) {
                    val ip = ipInput.text.toString().trim()
                    if (ip.isEmpty()) { appendAdbLog("请输入 IP:端口"); return@actionBtn }
                    runAdbCmd("connect $ip") { appendAdbLog(it) }
                },
                actionBtn("断开", Ui.buttonDanger(activity)) {
                    val ip = ipInput.text.toString().trim()
                    runAdbCmd("disconnect $ip") { appendAdbLog(it) }
                },
            ))
        })

        // --- 设备信息 ---
        out.addView(section("设备信息", "").apply {
            addView(actionBtn("查看设备信息", Ui.buttonSecondary(activity)) {
                runAdbCmd("get-serialno") { appendAdbLog("序列号: $it") }
                runAdbCmd("get-state") { appendAdbLog("状态: $it") }
                runAdbCmd("shell getprop ro.product.model") { appendAdbLog("型号: $it") }
                runAdbCmd("shell getprop ro.build.version.release") { appendAdbLog("Android 版本: $it") }
                runAdbCmd("shell getprop ro.build.version.sdk") { appendAdbLog("SDK 版本: $it") }
                runAdbCmd("shell getprop ro.product.device") { appendAdbLog("设备代号: $it") }
                runAdbCmd("shell getprop ro.board.platform") { appendAdbLog("平台: $it") }
            })
        })

        // --- 推送文件 ---
        out.addView(section("推送文件", "").apply {
            val localInput = EditText(activity).apply {
                hint = "本地文件路径"
                textSize = 12f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(4, d).toInt() }
                isFocusable = false
            }
            val remoteInput = EditText(activity).apply {
                hint = "目标路径（默认 /sdcard）"
                textSize = 12f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(4, d).toInt() }
                isFocusable = false
            }
            addView(localInput)
            addView(remoteInput)
            addView(actionBtn("选择本地文件", Ui.buttonSecondary(activity)) {
                pendingAdbFileAction = { path -> localInput.setText(path) }
                openFilePicker("请选择要推送的文件")
            })
            addView(actionBtn("推送到设备", Ui.buttonPrimary(activity)) {
                val local = localInput.text.toString().trim()
                val remote = (remoteInput.text.toString().trim()).ifEmpty { "/sdcard" }
                if (local.isEmpty()) { appendAdbLog("请先选择本地文件"); return@actionBtn }
                runAdbCmd("push \"$local\" \"$remote\"") { appendAdbLog(it) }
            })
        })

        // --- 拉取文件 ---
        out.addView(section("从设备复制文件", "").apply {
            val remoteInput = EditText(activity).apply {
                hint = "设备文件/目录路径（默认 /sdcard）"
                textSize = 12f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(4, d).toInt() }
                isFocusable = false
            }
            val localInput = EditText(activity).apply {
                hint = "本地保存目录"
                textSize = 12f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(4, d).toInt() }
                isFocusable = false
            }
            addView(remoteInput)
            addView(localInput)
            addView(actionBtn("选择保存目录", Ui.buttonSecondary(activity)) {
                pendingAdbFileAction = { path -> localInput.setText(path) }
                openFilePicker("请选择保存目录", isFolder = true)
            })
            addView(actionBtn("从设备拉取", Ui.buttonPrimary(activity)) {
                val remote = (remoteInput.text.toString().trim()).ifEmpty { "/sdcard" }
                val local = localInput.text.toString().trim()
                if (local.isEmpty()) { appendAdbLog("请先选择保存目录"); return@actionBtn }
                runAdbCmd("pull \"$remote\" \"$local\"") { appendAdbLog(it) }
            })
        })

        // --- 解锁屏幕密码 ---
        out.addView(section("解锁屏幕密码", "").apply {
            addView(actionBtn("ADB Root 解锁", Ui.buttonDanger(activity)) {
                appendAdbLog("正在执行 root 解锁...")
                runAdbCmd("root") { appendAdbLog(it) }
                runAdbCmd("wait-for-device") { appendAdbLog(it) }
                runAdbCmd("""shell rm -f /data/system/gatekeeper.password.key /data/system/gatekeeper.pattern.key /data/system/locksettings.db /data/system/gesture.key /data/system/password.key""") { appendAdbLog(it) }
                appendAdbLog("✅ 解锁完成，请重启设备")
            })
        })

        // --- 日志 ---
        adbLogView = Ui.logTextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(150, d)
            ).apply { topMargin = Ui.dp(4, d) }
        }
        out.addView(adbLogView)

        // 初始检测
        refreshAdbDevices()
        return out
    }

    // ==================== Fastboot 区 ====================

    private fun buildFbContent(): LinearLayout {
        val out = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // --- 设备列表 ---
        out.addView(section("设备列表", "").apply {
            fbDeviceList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(80, d)
                )
            }
            addView(fbDeviceList)
            addView(actionBtn("刷新设备", Ui.buttonPrimary(activity)) { refreshFbDevices() })
        })

        // --- BL 锁状态 ---
        out.addView(section("BL 锁状态", "").apply {
            addView(actionBtn("查看 BL 锁状态", Ui.buttonSecondary(activity)) {
                runFbCmd("getvar unlocked") { appendFbLog("unlocked: $it") }
                runFbCmd("oem device-info") { appendFbLog(it) }
                runFbCmd("flashing get_unlock_ability") { appendFbLog("get_unlock_ability: $it") }
            })
        })

        // --- 品牌解锁选项 ---
        out.addView(section("品牌解锁选项", "").apply {
            val brands = listOf("Lenovo", "oppo", "Google_Pixel")
            val brandLabels = listOf("联想", "OPPO/一加/realme", "Google Pixel")
            val brandBtns = brands.mapIndexed { i, b ->
                actionBtn(brandLabels[i], Ui.buttonSecondary(activity)) {
                    runFbCmd("flash unlock", b = selectedFbSerial) { appendFbLog(it) }
                    when (b) {
                        "Lenovo" -> runFbCmd("oem unlock-go", b = selectedFbSerial) { appendFbLog(it) }
                        "oppo", "Google_Pixel" -> {
                            runFbCmd("flashing unlock", b = selectedFbSerial) { appendFbLog(it) }
                            if (b == "Google_Pixel") runFbCmd("flashing unlock_critical", b = selectedFbSerial) { appendFbLog(it) }
                        }
                    }
                    appendFbLog("✅ 已发送解锁指令，按提示操作")
                }
            }
            brandBtns.forEach { addView(it) }
        })

        // --- 解锁 BL（多方案） ---
        out.addView(section("解锁 BL（多方案）", "").apply {
            val options = listOf(
                "fastboot oem unlock-go",
                "fastboot oem unlock",
                "fastboot flashing unlock",
                "fastboot flashing unlock_critical",
                "fastboot bbk unlock_vivo",
            )
            options.forEachIndexed { i, cmd ->
                addView(actionBtn("方案${i + 1}", Ui.buttonSecondary(activity)) {
                    val args = cmd.substringAfter("fastboot ").split(" ")
                    runFbCmd(args[0], *args.drop(1).toTypedArray()) { appendFbLog(it) }
                })
            }
        })

        // --- 上锁 BL ---
        out.addView(section("上锁 BL", "").apply {
            addView(actionBtn("方案① flashing lock", Ui.buttonDanger(activity)) {
                runFbCmd("flashing", "lock") { appendFbLog(it) }
            })
            addView(actionBtn("方案② oem lock", Ui.buttonDanger(activity)) {
                runFbCmd("oem", "lock") { appendFbLog(it) }
            })
            addView(infoText("⚠️  上锁前请确保 REC 和系统都是官方的，否则变砖自负！"))
        })

        // --- A/B 卡槽 ---
        out.addView(section("A/B 卡槽切换", "").apply {
            addView(actionBtn("查看当前激活分区", Ui.buttonSecondary(activity)) {
                runFbCmd("getvar", "current-slot") { appendFbLog(it) }
            })
            addView(row2btn(
                actionBtn("切换至 A", Ui.buttonPrimary(activity)) {
                    runFbCmd("--set-active=a") { appendFbLog(it) }
                },
                actionBtn("切换至 B", Ui.buttonPrimary(activity)) {
                    runFbCmd("--set-active=b") { appendFbLog(it) }
                },
            ))
        })

        // --- 线刷单个镜像 ---
        out.addView(section("线刷单个镜像", "").apply {
            val partitionInput = EditText(activity).apply {
                hint = "分区名（如 boot/system/vendor）"
                textSize = 12f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(4, d).toInt() }
                isFocusable = false
            }
            val imgInput = EditText(activity).apply {
                hint = "镜像文件路径"
                textSize = 12f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(4, d).toInt() }
                isFocusable = false
            }
            addView(partitionInput)
            addView(imgInput)
            addView(actionBtn("选择镜像文件", Ui.buttonSecondary(activity)) {
                pendingFbFileAction = { path -> imgInput.setText(path) }
                openFilePicker("请选择 .img 镜像文件")
            })
            addView(actionBtn("刷入分区", Ui.buttonPrimary(activity)) {
                val part = partitionInput.text.toString().trim()
                val img = imgInput.text.toString().trim()
                if (part.isEmpty() || img.isEmpty()) { appendFbLog("请填写分区名和镜像路径"); return@actionBtn }
                runFbCmd("flash", part, img) { appendFbLog(it) }
            })
        })

        // --- 动态获取分区刷入 ---
        out.addView(section("动态分区刷入", "").apply {
            addView(actionBtn("获取可用分区列表", Ui.buttonSecondary(activity)) {
                runFbCmd("getvar", "all") { 
                    val parts = it.lines().map { l -> l.trim() }
                        .filter { l -> l.contains("partition-type:") }
                        .map { l -> l.replaceBefore(":", "").trim() }
                    appendFbLog("可用分区:\n${parts.joinToString("\n") { "  $it" }}")
                }
            })
            val partInput = EditText(activity).apply {
                hint = "输入分区名刷入"
                textSize = 12f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(4, d).toInt() }
                isFocusable = false
            }
            val imgInput = EditText(activity).apply {
                hint = "镜像文件路径"
                textSize = 12f
                setBackgroundResource(android.R.drawable.edit_text)
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(4, d).toInt() }
                isFocusable = false
            }
            addView(partInput)
            addView(imgInput)
            addView(actionBtn("选择镜像文件", Ui.buttonSecondary(activity)) {
                pendingFbFileAction = { path -> imgInput.setText(path) }
                openFilePicker("请选择 .img 镜像文件")
            })
            addView(actionBtn("刷入", Ui.buttonPrimary(activity)) {
                val part = partInput.text.toString().trim()
                val img = imgInput.text.toString().trim()
                if (part.isEmpty() || img.isEmpty()) { appendFbLog("请填写分区名和镜像路径"); return@actionBtn }
                runFbCmd("flash", part, img) { appendFbLog(it) }
            })
        })

        // --- 重启控制 ---
        out.addView(section("重启控制", "").apply {
            addView(row4btn(
                actionBtn("系统", Ui.buttonSuccess(activity)) { runFbCmd("reboot") { appendFbLog(it) } },
                actionBtn("Bootloader", Ui.buttonPrimary(activity)) { runFbCmd("reboot-bootloader") { appendFbLog(it) } },
                actionBtn("Recovery", Ui.buttonWarning(activity)) { runFbCmd("reboot", "recovery") { appendFbLog(it) } },
                actionBtn("EDL", Ui.buttonDanger(activity)) { runFbCmd("oem", "edl") { appendFbLog(it) } },
            ))
        })

        // --- 日志 ---
        fbLogView = Ui.logTextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(150, d)
            ).apply { topMargin = Ui.dp(4, d) }
        }
        out.addView(fbLogView)

        // 初始检测
        refreshFbDevices()
        return out
    }

    // ==================== 工具方法 ====================

    private fun section(title: String, hint: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(activity, 12f)
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(6, d).toInt() }

            addView(TextView(activity).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
            })
            if (hint.isNotEmpty()) {
                addView(infoText(hint))
            }
        }
    }

    private fun actionBtn(label: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            this.text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            background = Ui.glassButton(activity, null)
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, d).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun row2btn(a: View, b: View): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, d).toInt() }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(4, d).toInt() })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun row3btn(a: View, b: View, c: View): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, d).toInt() }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, d).toInt() })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, d).toInt() })
            addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun row4btn(a: View, b: View, c: View, d: View): LinearLayout {
        val density = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, density).toInt() }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            addView(d, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun infoText(txt: String): TextView {
        return TextView(activity).apply {
            this.text = txt
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d).toInt(), 0, Ui.dp(4, d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // ==================== ADB 操作 ====================

    private fun refreshAdbDevices() {
        executor.execute {
            val devices = OtgAssistant.adbDevices(activity)
            activity.runOnUiThread {
                adbDeviceList.removeAllViews()
                if (devices.isEmpty()) {
                    adbDeviceList.addView(infoText("未检测到 ADB 设备"))
                } else {
                    devices.forEach { serial ->
                        adbDeviceList.addView(adbDeviceRow(serial))
                    }
                }
            }
        }
    }

    private fun adbDeviceRow(serial: String): TextView {
        return TextView(activity).apply {
            text = serial
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(8, d), Ui.dp(6, d), Ui.dp(8, d), Ui.dp(6, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, d).toInt() }
            setOnClickListener {
                selectedAdbSerial = serial
                appendAdbLog("已选择设备: $serial")
            }
        }
    }

    private fun runAdbCmd(vararg args: String, onResult: ((String) -> Unit)? = null) {
        executor.execute {
            val result = OtgAssistant.run(activity, "adb", args.toList(), timeoutMs = 60000)
            val output = buildOutput(result)
            activity.runOnUiThread {
                onResult?.invoke(output)
            }
        }
    }

    private fun appendAdbLog(msg: String) {
        adbLogView.append("$msg\n")
        adbLogView.post { (adbLogView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    // ==================== Fastboot 操作 ====================

    private fun refreshFbDevices() {
        executor.execute {
            val devices = OtgAssistant.fastbootDevices(activity)
            activity.runOnUiThread {
                fbDeviceList.removeAllViews()
                if (devices.isEmpty()) {
                    fbDeviceList.addView(infoText("未检测到 Fastboot 设备"))
                } else {
                    devices.forEach { serial ->
                        fbDeviceList.addView(fbDeviceRow(serial))
                    }
                }
            }
        }
    }

    private fun fbDeviceRow(serial: String): TextView {
        return TextView(activity).apply {
            text = serial
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(8, d), Ui.dp(6, d), Ui.dp(8, d), Ui.dp(6, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, d).toInt() }
            setOnClickListener {
                selectedFbSerial = serial
                appendFbLog("已选择设备: $serial")
            }
        }
    }

    private fun runFbCmd(vararg args: String, b: String = selectedFbSerial, onResult: ((String) -> Unit)? = null) {
        executor.execute {
            val serialized = if (b.isNotBlank()) listOf("-s", b) else emptyList()
            val result = OtgAssistant.run(activity, "fastboot", serialized + args.toList(), timeoutMs = 120000)
            val output = buildOutput(result)
            activity.runOnUiThread {
                onResult?.invoke(output)
            }
        }
    }

    private fun appendFbLog(msg: String) {
        fbLogView.append("$msg\n")
        fbLogView.post { (fbLogView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    // ==================== 文件选择 ====================

    private fun openFilePicker(title: String, isFolder: Boolean = false) {
        val intent = android.content.Intent(activity, RootfsFilesActivity::class.java).apply {
            putExtra(RootfsFilesActivity.EXTRA_PICK, true)
            putExtra(RootfsFilesActivity.EXTRA_TITLE, title)
            putExtra(RootfsFilesActivity.EXTRA_EXT_ALL, isFolder)
        }
        try { activity.startActivity(intent) } 
        catch (e: Exception) { appendAdbLog("无法打开文件选择器: ${e.message}") }
    }

    fun onFilePicked(path: String) {
        pendingAdbFileAction?.invoke(path)
        pendingFbFileAction?.invoke(path)
        pendingAdbFileAction = null
        pendingFbFileAction = null
    }

    // ==================== 工具 ====================

    private fun buildOutput(result: ShellResult): String {
        return buildString {
            if (result.stdout.isNotEmpty()) appendLine(result.stdout)
            if (result.stderr.isNotEmpty()) appendLine("[错误] ${result.stderr}")
            if (result.code != 0 && result.code != 127) appendLine("[退出码: ${result.code}]")
            if (result.code == 127) appendLine("[命令未找到]")
        }.trimEnd()
    }
}
