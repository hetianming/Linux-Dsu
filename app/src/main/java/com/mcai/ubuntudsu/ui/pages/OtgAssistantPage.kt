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
import android.widget.Spinner
import android.widget.TextView
import com.mcai.ubuntudsu.RootfsFilesActivity
import com.mcai.ubuntudsu.core.OtgAssistant
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * OTG 刷机助手 - 单屏卡片布局
 * 
 * ADB Tab: 设备状态 + 快捷操作卡片
 * Fastboot Tab: 设备状态 + 刷机操作卡片
 * Shell Tab: 命令输入 + 执行结果
 */
class OtgAssistantPage(
    private val activity: Activity,
    private val pickFileLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    private val onDismiss: (() -> Unit)? = null,
) {
    private val density: Float get() = activity.resources.displayMetrics.density
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var selectedAdbSerial: String = ""
    private var selectedFbSerial: String = ""
    
    private lateinit var adbLogView: TextView
    private lateinit var fbLogView: TextView
    private lateinit var shellLogView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var operationStatus: TextView
    
    private var currentTab = 0
    private lateinit var tabLayout: LinearLayout
    private lateinit var contentContainer: LinearLayout
    
    private var pendingApkDisplay: TextView? = null
    private var pendingPushDisplay: TextView? = null
    private var pendingImgDisplay: TextView? = null

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(buildTitleBar())
        tabLayout = buildTabBar()
        root.addView(tabLayout)

        contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { weight = 1f }
        }
        root.addView(contentContainer)

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(3, density)
            ).apply { topMargin = Ui.dp(4, density) }
            visibility = View.GONE
        }
        root.addView(progressBar)

        // 操作状态提示
        operationStatus = TextView(activity).apply {
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(2, density) }
        }
        root.addView(operationStatus)

        contentContainer.addView(buildAdbContent())
        val fbContent = buildFastbootContent()
        fbContent.visibility = View.GONE
        contentContainer.addView(fbContent)
        val shellContent = buildShellContent()
        shellContent.visibility = View.GONE
        contentContainer.addView(shellContent)

        return root
    }

    fun onFilePicked(path: String) {
        pendingApkDisplay?.text = path
        pendingPushDisplay?.text = path
        pendingImgDisplay?.text = path
        pendingApkDisplay = null
        pendingPushDisplay = null
        pendingImgDisplay = null
    }

    private fun buildTitleBar(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, density), Ui.dp(8, density), Ui.dp(12, density), Ui.dp(8, density))

            addView(TextView(activity).apply {
                text = "<"
                textSize = 20f
                setTypeface(Ui.typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                gravity = Gravity.CENTER
                setPadding(Ui.dp(8, density).toInt(), Ui.dp(4, density).toInt(), Ui.dp(8, density).toInt(), Ui.dp(4, density).toInt())
                background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
                Ui.pressAnimation(this)
                setOnClickListener { onDismiss?.invoke() ?: activity.finish() }
            })

            val t = TextView(activity).apply {
                text = "OTG 刷机助手"
                textSize = 18f
                setTypeface(Ui.typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(t)
        }
    }

    private fun buildTabBar(): LinearLayout {
        val tabs = listOf("ADB", "Fastboot", "Shell")
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(8, density), Ui.dp(4, density), Ui.dp(8, density), Ui.dp(4, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )

            tabs.forEachIndexed { i, label ->
                addView(TextView(activity).apply {
                    text = label
                    textSize = 13f
                    setTypeface(Ui.typeface, Typeface.BOLD)
                    setTextColor(if (i == 0) Ui.buttonPrimary(activity) else Ui.secondaryText(activity))
                    gravity = Gravity.CENTER
                    background = Ui.glassButton(activity, null)
                    Ui.pressAnimation(this)
                    setPadding(Ui.dp(0, density), Ui.dp(8, density), Ui.dp(0, density), Ui.dp(8, density))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { switchTab(i) }
                })
            }
        }
    }

    private fun switchTab(idx: Int) {
        currentTab = idx
        for (i in 0 until tabLayout.childCount) {
            val tv = tabLayout.getChildAt(i) as? TextView ?: continue
            tv.setTextColor(if (i == idx) Ui.buttonPrimary(activity) else Ui.secondaryText(activity))
        }
        for (i in 0 until contentContainer.childCount) {
            val v = contentContainer.getChildAt(i)
            v.visibility = if (i == idx) View.VISIBLE else View.GONE
        }
    }

    // ==================== ADB Tab: 卡片式布局 ====================

    private fun buildAdbContent(): LinearLayout {
        val out = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(8, density), Ui.dp(4, density), Ui.dp(8, density), Ui.dp(4, density))
        }

        // 设备状态卡片
        out.addView(buildStatusBarCard("ADB 设备", "adbDeviceList", "未连接"))

        // 快捷操作网格
        out.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, density).toInt() }
            
            addView(actionBtn("设备信息", Ui.buttonSecondary(activity)) {
                if (selectedAdbSerial.isEmpty()) { showAdbToast("请先选择设备"); return@actionBtn }
                executor.execute {
                    val info = OtgAssistant.adbDeviceInfo(activity, selectedAdbSerial)
                    activity.runOnUiThread {
                        showAdbLog("=== 设备信息 ===")
                        info.forEach { (k, v) -> showAdbLog("$k: $v") }
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            
            addView(actionBtn("刷新", Ui.buttonPrimary(activity)) { refreshAdbDevices() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        // 安装应用卡片
        val apkDisplayCard = buildSection("安装应用").apply {
            val apkDisplay = TextView(activity).apply {
                text = "未选择 APK 文件"
                textSize = 12f
                setTextColor(Ui.secondaryText(activity))
                setPadding(Ui.dp(8, density).toInt(), Ui.dp(6, density).toInt(), Ui.dp(8, density).toInt(), Ui.dp(6, density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(3, density).toInt() }
                background = Ui.glassButton(activity, Ui.border(activity))
            }
            addView(apkDisplay)
            addView(row2btn(
                actionBtn("选择 APK", Ui.buttonSecondary(activity)) {
                    pendingApkDisplay = apkDisplay
                    openFilePicker("选择 APK 文件", ".apk")
                },
                actionBtn("安装", Ui.buttonPrimary(activity)) {
                    val apkPath = apkDisplay.text.toString().trim()
                    if (selectedAdbSerial.isEmpty()) { showAdbToast("请先选择设备"); return@actionBtn }
                    if (apkPath.isEmpty() || apkPath == "未选择 APK 文件") { showAdbToast("请选择 APK 文件"); return@actionBtn }
                    showProgress(true, "正在安装 APK...")
                    executor.execute {
                        val result = OtgAssistant.adbInstall(activity, selectedAdbSerial, apkPath)
                        showProgress(false)
                        activity.runOnUiThread { showAdbLog(OtgAssistant.buildOutput(result)) }
                    }
                }
            ))
        }
        out.addView(apkDisplayCard)

        // 推送文件卡片
        val pushCard = buildSection("推送文件")
        val pushDisplay = TextView(activity).apply {
            text = "未选择文件"
            textSize = 12f
            setTextColor(Ui.secondaryText(activity))
            setPadding(Ui.dp(8, density).toInt(), Ui.dp(6, density).toInt(), Ui.dp(8, density).toInt(), Ui.dp(6, density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(3, density).toInt() }
            background = Ui.glassButton(activity, Ui.border(activity))
        }
        pushCard.addView(pushDisplay)
        val pushRemoteInput = mutableEditText("远程路径", "/sdcard/")
        pushCard.addView(pushRemoteInput)
        pushCard.addView(row2btn(
                actionBtn("选择文件", Ui.buttonSecondary(activity)) {
                    pendingPushDisplay = pushDisplay
                    openFilePicker("选择文件", isFolder = true)
                },
            actionBtn("推送", Ui.buttonPrimary(activity)) {
                val localPath = pushDisplay.text.toString().trim()
                val remotePath = pushRemoteInput.text.toString().trim()
                if (selectedAdbSerial.isEmpty()) { showAdbToast("请先选择设备"); return@actionBtn }
                if (localPath.isEmpty() || localPath == "未选择文件") { showAdbToast("请选择文件"); return@actionBtn }
                showProgress(true, "正在推送文件...")
                executor.execute {
                    val result = OtgAssistant.adbPush(activity, selectedAdbSerial, localPath, remotePath)
                    showProgress(false)
                    activity.runOnUiThread { showAdbLog(OtgAssistant.buildOutput(result)) }
                }
            }
        ))
        out.addView(pushCard)

        // 重启控制卡片
        out.addView(buildSection("重启控制").apply {
            addView(row4btn(
                actionBtn("系统", Ui.buttonSuccess(activity)) { runAdbCmd("reboot") },
                actionBtn("BL", Ui.buttonPrimary(activity)) { runAdbCmd("reboot bootloader") },
                actionBtn("Recovery", Ui.buttonWarning(activity)) { runAdbCmd("reboot recovery") },
                actionBtn("EDL", Ui.buttonDanger(activity)) { runAdbCmd("reboot edl") },
            ))
        })

        // 日志区域
        adbLogView = Ui.logTextView(activity).apply {
            background = Ui.glassButton(activity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(100, density)
            ).apply { topMargin = Ui.dp(4, density) }
        }
        out.addView(adbLogView)

        refreshAdbDevices()
        return out
    }

    // ==================== Fastboot Tab: 卡片式布局 ====================

    private fun buildFastbootContent(): LinearLayout {
        val out = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(8, density), Ui.dp(4, density), Ui.dp(8, density), Ui.dp(4, density))
        }

        // 设备状态卡片
        out.addView(buildStatusBarCard("Fastboot 设备", "fbDeviceList", "未连接"))

        // 设备信息卡片
        out.addView(buildSection("设备信息").apply {
            addView(row2btn(
                actionBtn("详情", Ui.buttonSecondary(activity)) {
                    if (selectedFbSerial.isEmpty()) { showFbToast("请先选择设备"); return@actionBtn }
                    executor.execute {
                        val vars = OtgAssistant.fastbootGetvarAll(activity, selectedFbSerial)
                        activity.runOnUiThread {
                            showFbLog("=== 设备信息 ===")
                            vars.forEach { (k, v) -> showFbLog("$k: $v") }
                        }
                    }
                },
                actionBtn("分区", Ui.buttonSecondary(activity)) {
                    if (selectedFbSerial.isEmpty()) { showFbToast("请先选择设备"); return@actionBtn }
                    executor.execute {
                        val parts = OtgAssistant.fastbootPartitions(activity, selectedFbSerial)
                        activity.runOnUiThread {
                            showFbLog("=== 分区 (${parts.size} 个) ===")
                            parts.forEach { p -> showFbLog("${p.name} (${p.type}, ${p.sizeStr})") }
                        }
                    }
                }
            ))
        })

        // BL 锁状态卡片
        out.addView(buildSection("BL 锁状态").apply {
            addView(actionBtn("查看解锁状态", Ui.buttonSecondary(activity)) {
                if (selectedFbSerial.isEmpty()) { showFbToast("请先选择设备"); return@actionBtn }
                executor.execute {
                    val unlocked = OtgAssistant.fastbootGetvar(activity, selectedFbSerial, "unlocked")
                    val ability = OtgAssistant.fastbootGetvar(activity, selectedFbSerial, "flashing unlock-ability")
                    activity.runOnUiThread {
                        showFbLog("unlocked: ${unlocked ?: "无法获取"}")
                        showFbLog("unlock-ability: ${ability ?: "无法获取"}")
                    }
                }
            })
        })

        // 刷入分区卡片
        val flashCard = buildFlashCard()
        flashCard.tag = "flashCard"
        out.addView(flashCard)
        // 初始化分区列表（异步，设备已选则立即请求）
        if (selectedFbSerial.isNotEmpty()) {
            refreshFbPartitions(flashCard)
        }

        // BL 解锁卡片
        out.addView(buildUnlockCard())

        // 重启控制卡片
        out.addView(buildSection("重启控制").apply {
            addView(row4btn(
                actionBtn("系统", Ui.buttonSuccess(activity)) { executeReboot("system") },
                actionBtn("BL", Ui.buttonPrimary(activity)) { executeReboot("bootloader") },
                actionBtn("Recovery", Ui.buttonWarning(activity)) { executeReboot("recovery") },
                actionBtn("EDL", Ui.buttonDanger(activity)) { executeReboot("edl") },
            ))
        })

        // 日志区域
        fbLogView = Ui.logTextView(activity).apply {
            background = Ui.glassButton(activity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(100, density)
            ).apply { topMargin = Ui.dp(4, density) }
        }
        out.addView(fbLogView)

        refreshFbDevices()
        return out
    }

    // ==================== Shell Tab: 简洁终端 ====================

    private fun buildShellContent(): LinearLayout {
        val out = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(8, density), Ui.dp(4, density), Ui.dp(8, density), Ui.dp(4, density))
        }

        // 设备选择提示
        out.addView(buildSection("设备状态").apply {
            val statusText = TextView(activity).apply {
                text = if (selectedAdbSerial.isNotEmpty()) "已连接: $selectedAdbSerial" else "未连接 ADB 设备"
                textSize = 12f
                setTextColor(Ui.secondaryText(activity))
                tag = "device_status"
            }
            addView(statusText)
            addView(actionBtn("刷新设备", Ui.buttonPrimary(activity)) { refreshAdbDevices() })
        })

        // 命令输入卡片
        out.addView(buildSection("执行命令").apply {
            val cmdInput = mutableEditText("输入命令...", "getprop")
            addView(cmdInput)
            addView(row2btn(
                actionBtn("执行", Ui.buttonPrimary(activity)) {
                    if (selectedAdbSerial.isEmpty()) { showShellToast("请先选择设备"); return@actionBtn }
                    val cmd = cmdInput.text.toString().trim()
                    if (cmd.isEmpty()) { showShellToast("请输入命令"); return@actionBtn }
                    showProgress(true)
                    executor.execute {
                        val result = OtgAssistant.adbShell(activity, selectedAdbSerial, cmd)
                        showProgress(false)
                        activity.runOnUiThread { showShellLog(OtgAssistant.buildOutput(result)) }
                    }
                },
                actionBtn("清空", Ui.buttonSecondary(activity)) { shellLogView.text = "" }
            ))
        })

        // 快速命令
        out.addView(buildSection("快速命令").apply {
            addView(row3btn(
                actionBtn("系统信息", Ui.buttonSecondary(activity)) { runQuickCmd("uname -a", "系统信息") },
                actionBtn("属性", Ui.buttonSecondary(activity)) { runQuickCmd("getprop", "所有属性") },
                actionBtn("存储", Ui.buttonSecondary(activity)) { runQuickCmd("df -h", "存储信息") },
            ))
            addView(row3btn(
                actionBtn("CPU", Ui.buttonSecondary(activity)) { runQuickCmd("cat /proc/cpuinfo", "CPU 信息") },
                actionBtn("电池", Ui.buttonSecondary(activity)) { runQuickCmd("dumpsys battery", "电池信息") },
                actionBtn("日志", Ui.buttonSecondary(activity)) { runQuickCmd("logcat -d", "当前日志") },
            ))
        })

        // 终端输出
        shellLogView = Ui.logTextView(activity).apply {
            background = Ui.glassButton(activity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f; topMargin = Ui.dp(4, density) }
        }
        out.addView(shellLogView)

        return out
    }

    // ==================== 辅助卡片构建 ====================

    private fun buildStatusBarCard(title: String, tag: String, defaultText: String): LinearLayout {
        return buildSection(title).apply {
            val deviceList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setTag(tag)
            }
            addView(deviceList)
            addView(infoText(defaultText))
        }
    }

    private fun buildInputCard(label: String, hint: String, default: String, onAction: (String) -> Unit): LinearLayout {
        return buildSection(label).apply {
            val input = mutableEditText(hint, default)
            addView(input)
            addView(row2btn(
                actionBtn("选择文件", Ui.buttonSecondary(activity)) {
                    openFilePicker("选择文件")
                },
                actionBtn("执行", Ui.buttonPrimary(activity)) {
                    onAction(input.text.toString().trim())
                }
            ))
        }
    }

    private fun buildFlashCard(): LinearLayout {
        return buildSection("刷入分区").apply {
            val spinner = Spinner(activity).apply {
                adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("请选择分区..."))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            spinner.tag = "flashSpinner"
            addView(spinner)

            // 显示已选镜像的只读区域（替代输入框）
            val imgDisplay = TextView(activity).apply {
                text = "未选择镜像文件"
                textSize = 12f
                setTextColor(Ui.secondaryText(activity))
                setPadding(Ui.dp(8, density).toInt(), Ui.dp(6, density).toInt(), Ui.dp(8, density).toInt(), Ui.dp(6, density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(3, density).toInt() }
                background = Ui.glassButton(activity, Ui.border(activity))
            }
            addView(imgDisplay)

            addView(row2btn(
                actionBtn("选择镜像", Ui.buttonSecondary(activity)) {
                    openFilePickerForImg(imgDisplay)
                },
                actionBtn("刷入", Ui.buttonPrimary(activity)) {
                    val imgPath = imgDisplay.text.toString().trim()
                    val partition = spinner.selectedItem.toString()
                    if (selectedFbSerial.isEmpty()) { showFbToast("请先选择设备"); return@actionBtn }
                    if (partition == "请选择分区..." || imgPath.isEmpty() || imgPath == "未选择镜像文件") {
                        showFbToast("请选择分区和镜像文件")
                        return@actionBtn
                    }
                    showProgress(true, "正在刷入分区 $partition...")
                    executor.execute {
                        val result = OtgAssistant.fastbootFlash(activity, selectedFbSerial, partition, imgPath)
                        showProgress(false)
                        activity.runOnUiThread { showFbLog(OtgAssistant.buildOutput(result)) }
                    }
                }
            ))
        }
    }

    private fun buildUnlockCard(): LinearLayout {
        return buildSection("BL 解锁").apply {
            // 品牌分组标题
            addView(infoText("通用品牌"))
            addView(divider())
            addView(row2btn(
                actionBtn("flashing unlock", Ui.buttonSecondary(activity)) { executeUnlock(listOf(listOf("flashing", "unlock"))) },
                actionBtn("oem unlock", Ui.buttonSecondary(activity)) { executeUnlock(listOf(listOf("oem", "unlock"))) },
            ))
            
            addView(infoText("小米/红米"))
            addView(divider())
            addView(actionBtn("oem unlock-go", Ui.buttonSecondary(activity)) { 
                executeUnlock(listOf(listOf("oem", "unlock-go")), "警告: 小米设备专用命令") 
            })
            
            addView(infoText("Google Pixel"))
            addView(divider())
            addView(actionBtn("flashing unlock_critical", Ui.buttonWarning(activity)) { 
                executeUnlock(listOf(listOf("flashing", "unlock"), listOf("flashing", "unlock_critical")), "警告: Pixel需要两步解锁") 
            })
            
            addView(infoText("OPPO/一加/realme/联想/BBK"))
            addView(divider())
            addView(row3btn(
                actionBtn("OPPO/一加", Ui.buttonSecondary(activity)) { executeUnlock(listOf(listOf("flashing", "unlock"))) },
                actionBtn("联想", Ui.buttonSecondary(activity)) { executeUnlock(listOf(listOf("oem", "unlock"), listOf("oem", "unlock-go"))) },
                actionBtn("BBK", Ui.buttonSecondary(activity)) { executeUnlock(listOf(listOf("bbk", "unlock"))) },
            ))
            
            addView(infoText("解锁会清除数据"))
        }
    }

    private fun divider(): View {
        return View(activity).apply {
            background = android.graphics.drawable.ColorDrawable(
                android.graphics.Color.parseColor(if (Ui.isDark(activity)) "#2D2B3D" else "#E2E8F0")
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(1, density).toInt()
            ).apply { topMargin = Ui.dp(4, density).toInt(); bottomMargin = Ui.dp(4, density).toInt() }
        }
    }

    private fun executeUnlock(commands: List<List<String>>, warning: String = "警告: 此操作将解锁Bootloader，清除所有数据") {
        if (selectedFbSerial.isEmpty()) { showFbToast("请先选择设备"); return }
        showFbLog(warning)
        showProgress(true, "正在执行 BL 解锁...")
        executor.execute {
            val result = OtgAssistant.fastbootUnlockCmd(activity, selectedFbSerial, commands)
            showProgress(false)
            activity.runOnUiThread { showFbLog(OtgAssistant.buildOutput(result)) }
        }
    }

    private fun buildSection(title: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(activity, 10f)
            setPadding(Ui.dp(8, density), Ui.dp(6, density), Ui.dp(8, density), Ui.dp(6, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, density).toInt() }

            addView(TextView(activity).apply {
                text = title
                textSize = 13f
                setTypeface(Ui.typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
            })
        }
    }

    private fun actionBtn(label: String, color: Int, onClick: (View) -> Unit): TextView {
        return TextView(activity).apply {
            this.text = label
            textSize = 12f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            background = Ui.glassButton(activity, null)
            Ui.pressAnimation(this)
            setPadding(Ui.dp(8, density), Ui.dp(6, density), Ui.dp(8, density), Ui.dp(6, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, density).toInt() }
            setOnClickListener { onClick(this) }
        }
    }

    private fun mutableEditText(hint: String, default: String = ""): EditText {
        return EditText(activity).apply {
            this.hint = hint
            this.setText(default)
            textSize = 12f
            background = Ui.glassButton(activity)
            setPadding(Ui.dp(6, density).toInt(), Ui.dp(3, density).toInt(), Ui.dp(6, density).toInt(), Ui.dp(3, density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(3, density).toInt() }
        }
    }

    private fun row2btn(a: View, b: View): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, density).toInt() }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun row3btn(a: View, b: View, c: View): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, density).toInt() }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun row4btn(a: View, b: View, c: View, d: View): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, density).toInt() }
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
                val parent = contentContainer.getChildAt(currentTab)
                val deviceContainer = parent?.findViewWithTag<LinearLayout>("adbDeviceList")
                deviceContainer?.removeAllViews()
                
                if (devices.isEmpty()) {
                    deviceContainer?.addView(infoText("未检测到 ADB 设备"))
                    deviceContainer?.addView(actionBtn("刷新", Ui.buttonPrimary(activity)) { refreshAdbDevices() })
                } else {
                    devices.forEach { serial ->
                        deviceContainer?.addView(adbDeviceRow(serial))
                    }
                }
                
                // 更新状态文本
                val statusText = parent?.findViewWithTag<TextView>("device_status")
                statusText?.text = if (devices.isEmpty()) "未连接" else "已连接: ${devices.size} 台"
            }
        }
    }

    private fun adbDeviceRow(serial: String): TextView {
        return TextView(activity).apply {
            text = serial
            textSize = 11f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(6, density), Ui.dp(4, density), Ui.dp(6, density), Ui.dp(4, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, density).toInt() }
            setOnClickListener {
                selectedAdbSerial = serial
                showAdbLog("已选择: $serial")
            }
        }
    }

    private fun runAdbCmd(vararg args: String) {
        executor.execute {
            val serialArgs = if (selectedAdbSerial.isNotEmpty()) arrayOf("-s", selectedAdbSerial) else emptyArray()
            // 通过 su root 执行，确保重启命令生效
            val cmd = args.toList().joinToString(" ")
            val result = OtgAssistant.adbShellSu(activity, selectedAdbSerial, cmd)
            val output = OtgAssistant.buildOutput(result)
            activity.runOnUiThread { showAdbLog(output) }
        }
    }

    private fun showAdbLog(msg: String) {
        adbLogView.append("$msg\n")
    }

    private fun showAdbToast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ==================== Fastboot 操作 ====================

    private fun refreshFbDevices() {
        executor.execute {
            val devices = OtgAssistant.fastbootDevices(activity)
            activity.runOnUiThread {
                val parent = contentContainer.getChildAt(currentTab)
                val deviceContainer = parent?.findViewWithTag<LinearLayout>("fbDeviceList")
                deviceContainer?.removeAllViews()
                
                if (devices.isEmpty()) {
                    deviceContainer?.addView(infoText("未检测到 Fastboot 设备"))
                    deviceContainer?.addView(actionBtn("刷新", Ui.buttonPrimary(activity)) { refreshFbDevices() })
                } else {
                    devices.forEach { serial ->
                        deviceContainer?.addView(fbDeviceRow(serial))
                    }
                }
                
                val statusText = parent?.findViewWithTag<TextView>("device_status")
                statusText?.text = if (devices.isEmpty()) "未连接" else "已连接: ${devices.size} 台"
            }
        }
    }

    private fun fbDeviceRow(serial: String): TextView {
        return TextView(activity).apply {
            text = serial
            textSize = 11f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(6, density), Ui.dp(4, density), Ui.dp(6, density), Ui.dp(4, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, density).toInt() }
            setOnClickListener {
                selectedFbSerial = serial
                showFbLog("已选择: $serial")
                // 选中设备后异步刷新分区列表
                val flashCard = contentContainer.findViewWithTag<LinearLayout>("flashCard")
                refreshFbPartitions(flashCard)
            }
        }
    }

    private fun refreshFbPartitions(flashCard: LinearLayout) {
        if (selectedFbSerial.isEmpty()) return
        executor.execute {
            val partitions = OtgAssistant.fastbootPartitions(activity, selectedFbSerial)
            activity.runOnUiThread {
                val spinner = flashCard.findViewWithTag<Spinner>("flashSpinner")
                if (spinner != null && partitions.isNotEmpty()) {
                    val names = partitions.map { it.name }
                    spinner.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, names)
                    showFbLog("已获取 ${partitions.size} 个分区")
                } else if (partitions.isEmpty()) {
                    showFbLog("无法获取分区列表，请检查设备连接")
                }
            }
        }
    }

    private fun executeReboot(target: String) {
        if (selectedFbSerial.isEmpty()) { showFbToast("请先选择设备"); return }
        executor.execute {
            val result = OtgAssistant.fastbootReboot(activity, selectedFbSerial, target)
            activity.runOnUiThread { showFbLog(OtgAssistant.buildOutput(result)) }
        }
    }

    private fun showFbLog(msg: String) {
        fbLogView.append("$msg\n")
    }

    private fun showFbToast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ==================== Shell 操作 ====================

    private fun runQuickCmd(cmd: String, desc: String) {
        if (selectedAdbSerial.isEmpty()) { showShellToast("请先选择设备"); return }
        showProgress(true, "正在执行命令...")
        executor.execute {
            val result = OtgAssistant.adbShell(activity, selectedAdbSerial, cmd)
            showProgress(false)
            activity.runOnUiThread { showShellLog("=== $desc ===\n${OtgAssistant.buildOutput(result)}") }
        }
    }

    private fun showShellLog(msg: String) {
        shellLogView.append("$msg\n")
        shellLogView.post { shellLogView.parent?.requestLayout() }
    }

    private fun showShellToast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ==================== 通用方法 ====================

    private fun showProgress(show: Boolean, status: String = "") {
        activity.runOnUiThread {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
            operationStatus.text = if (show) status else ""
        }
    }

    private fun openFilePicker(title: String, ext: String = "", isFolder: Boolean = false) {
        val intent = android.content.Intent(activity, RootfsFilesActivity::class.java).apply {
            putExtra(RootfsFilesActivity.EXTRA_PICK, true)
            putExtra(RootfsFilesActivity.EXTRA_TITLE, title)
            if (isFolder) {
                putExtra(RootfsFilesActivity.EXTRA_EXT_ALL, true)
            } else if (ext.isNotEmpty()) {
                putExtra(RootfsFilesActivity.EXTRA_EXT, ext)
            }
        }
        try { pickFileLauncher.launch(intent) }
        catch (e: Exception) { showAdbToast("无法打开文件选择器: ${e.message}") }
    }

    private fun openFilePickerForImg(display: TextView) {
        pendingImgDisplay = display
        openFilePicker("选择 .img 镜像", ".img")
    }

    fun onDestroy() {
        executor.shutdown()
    }
}
