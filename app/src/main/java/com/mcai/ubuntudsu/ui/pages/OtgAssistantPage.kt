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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OTG 刷机助手 - 改版
 * 
 * ADB Tab: 日常调试 (设备信息、文件传输、应用安装、重启)
 * Fastboot Tab: 刷机操作 (分区刷入、BL 解锁、擦除、重启)
 * Shell Tab: 命令行终端 (执行任意 shell 命令)
 */
class OtgAssistantPage(
    private val activity: Activity,
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
    
    private var currentTab = 0
    private lateinit var tabLayout: LinearLayout
    
    private var pendingFileAction: ((String) -> Unit)? = null

    fun build(): View {
        val root = ScrollView(activity).apply {
            addView(buildContent())
        }
        return root
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, density), Ui.dp(8, density), Ui.dp(12, density), Ui.dp(4, density))
        }

        root.addView(buildTitleBar())
        tabLayout = buildTabBar()
        root.addView(tabLayout)
        
        root.addView(buildAdbContent())
        val fbContent = buildFastbootContent()
        fbContent.visibility = View.GONE
        root.addView(fbContent)
        val shellContent = buildShellContent()
        shellContent.visibility = View.GONE
        root.addView(shellContent)
        
        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(4, density)
            ).apply { topMargin = Ui.dp(8, density) }
            visibility = View.GONE
        }
        root.addView(progressBar)
        
        return root
    }

    private fun buildTitleBar(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(8, density))

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
        val tabs = listOf("ADB 调试", "Fastboot 刷机", "Shell 终端")
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.neuCard(activity, 12f)
            setPadding(Ui.dp(4, density), Ui.dp(4, density), Ui.dp(4, density), Ui.dp(4, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(8, density) }

            tabs.forEachIndexed { i, label ->
                addView(TextView(activity).apply {
                    text = label
                    textSize = 12f
                    setTypeface(Ui.typeface, Typeface.BOLD)
                    setTextColor(if (i == 0) Ui.buttonPrimary(activity) else Ui.secondaryText(activity))
                    gravity = Gravity.CENTER
                    background = Ui.glassButton(activity, null)
                    Ui.pressAnimation(this)
                    setPadding(Ui.dp(0, density), Ui.dp(10, density), Ui.dp(0, density), Ui.dp(10, density))
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
        val parent = (activity.findViewById<View>(android.R.id.content) as? ViewGroup)?.getChildAt(0) as? ScrollView ?: return
        for (i in 1 until parent.childCount) {
            val v = parent.getChildAt(i)
            if (v is LinearLayout && v !== tabLayout) {
                v.visibility = if (i == 1 + idx) View.VISIBLE else View.GONE
            }
        }
    }

    // ==================== ADB Tab: 日常调试 ====================

    private fun buildAdbContent(): LinearLayout {
        val out = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // 设备选择
        out.addView(buildSection("设备", "选择要操作的 ADB 设备").apply {
            val deviceList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setTag("adbDeviceList")
            }
            addView(deviceList)
            addView(actionBtn("刷新设备", Ui.buttonPrimary(activity)) { refreshAdbDevices() })
        })

        // 设备信息
        out.addView(buildSection("设备信息", "").apply {
            addView(actionBtn("查看设备详情", Ui.buttonSecondary(activity)) {
                if (selectedAdbSerial.isEmpty()) { appendAdbLog("请先选择设备"); return@actionBtn }
                executor.execute {
                    val info = OtgAssistant.adbDeviceInfo(activity, selectedAdbSerial)
                    activity.runOnUiThread {
                        appendAdbLog("=== 设备信息 ===")
                        info.forEach { (k, v) -> appendAdbLog("$k: $v") }
                    }
                }
            })
        })

        // 应用安装
        out.addView(buildSection("安装应用", "").apply {
            val apkInput = mutableEditText("APK 路径", "")
            addView(apkInput)
            addView(row2btn(
                actionBtn("选择 APK", Ui.buttonSecondary(activity)) {
                    pendingFileAction = { apkInput.setText(it) }
                    openFilePicker("选择 APK")
                },
                actionBtn("安装", Ui.buttonPrimary(activity)) {
                    if (selectedAdbSerial.isEmpty()) { appendAdbLog("请先选择设备"); return@actionBtn }
                    val apk = apkInput.text.toString().trim()
                    if (apk.isEmpty()) { appendAdbLog("请选择 APK 文件"); return@actionBtn }
                    showProgress(true)
                    executor.execute {
                        val result = OtgAssistant.adbInstall(activity, selectedAdbSerial, apk)
                        showProgress(false)
                        activity.runOnUiThread { appendAdbLog(OtgAssistant.buildOutput(result)) }
                    }
                }
            ))
        })

        // 文件推送
        out.addView(buildSection("推送文件", "").apply {
            val localInput = mutableEditText("本地路径", "")
            val remoteInput = mutableEditText("设备路径", "/sdcard/")
            addView(localInput)
            addView(remoteInput)
            addView(row2btn(
                actionBtn("选择本地", Ui.buttonSecondary(activity)) {
                    pendingFileAction = { localInput.setText(it) }
                    openFilePicker("选择文件")
                },
                actionBtn("推送到设备", Ui.buttonPrimary(activity)) {
                    if (selectedAdbSerial.isEmpty()) { appendAdbLog("请先选择设备"); return@actionBtn }
                    val local = localInput.text.toString().trim()
                    val remote = remoteInput.text.toString().trim()
                    if (local.isEmpty()) { appendAdbLog("请填写本地路径"); return@actionBtn }
                    showProgress(true)
                    executor.execute {
                        val result = OtgAssistant.adbPush(activity, selectedAdbSerial, local, remote)
                        showProgress(false)
                        activity.runOnUiThread { appendAdbLog(OtgAssistant.buildOutput(result)) }
                    }
                }
            ))
        })

        // 文件拉取
        out.addView(buildSection("拉取文件", "").apply {
            val remoteInput = mutableEditText("设备路径", "/sdcard/")
            val localInput = mutableEditText("保存路径", "")
            addView(remoteInput)
            addView(localInput)
            addView(row2btn(
                actionBtn("选择目录", Ui.buttonSecondary(activity)) {
                    pendingFileAction = { localInput.setText(it) }
                    openFilePicker("选择保存目录", isFolder = true)
                },
                actionBtn("从设备拉取", Ui.buttonPrimary(activity)) {
                    if (selectedAdbSerial.isEmpty()) { appendAdbLog("请先选择设备"); return@actionBtn }
                    val remote = remoteInput.text.toString().trim()
                    val local = localInput.text.toString().trim()
                    if (local.isEmpty()) { appendAdbLog("请选择保存目录"); return@actionBtn }
                    showProgress(true)
                    executor.execute {
                        val result = OtgAssistant.adbPull(activity, selectedAdbSerial, remote, local)
                        showProgress(false)
                        activity.runOnUiThread { appendAdbLog(OtgAssistant.buildOutput(result)) }
                    }
                }
            ))
        })

        // 重启控制
        out.addView(buildSection("重启", "").apply {
            addView(row4btn(
                actionBtn("系统", Ui.buttonSuccess(activity)) { runAdbCmd("reboot") },
                actionBtn("Bootloader", Ui.buttonPrimary(activity)) { runAdbCmd("reboot bootloader") },
                actionBtn("Recovery", Ui.buttonWarning(activity)) { runAdbCmd("reboot recovery") },
                actionBtn("EDL", Ui.buttonDanger(activity)) { runAdbCmd("reboot edl") },
            ))
        })

        adbLogView = Ui.logTextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(120, density)
            ).apply { topMargin = Ui.dp(4, density) }
        }
        out.addView(adbLogView)

        refreshAdbDevices()
        return out
    }

    // ==================== Fastboot Tab: 刷机操作 ====================

    private fun buildFastbootContent(): LinearLayout {
        val out = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // 设备选择
        out.addView(buildSection("设备", "选择 Fastboot 设备").apply {
            val deviceList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setTag("fbDeviceList")
            }
            addView(deviceList)
            addView(actionBtn("刷新设备", Ui.buttonPrimary(activity)) { refreshFbDevices() })
        })

        // 设备信息
        out.addView(buildSection("设备信息", "").apply {
            addView(actionBtn("查看设备信息", Ui.buttonSecondary(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                executor.execute {
                    val vars = OtgAssistant.fastbootGetvarAll(activity, selectedFbSerial)
                    activity.runOnUiThread {
                        appendFbLog("=== 设备信息 ===")
                        vars.forEach { (k, v) -> appendFbLog("$k: $v") }
                    }
                }
            })
            addView(actionBtn("获取分区列表", Ui.buttonSecondary(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                executor.execute {
                    val parts = OtgAssistant.fastbootPartitions(activity, selectedFbSerial)
                    activity.runOnUiThread {
                        appendFbLog("=== 分区 (${parts.size} 个) ===")
                        parts.forEach { p -> appendFbLog("${p.name} (${p.type}, ${p.sizeStr})") }
                    }
                }
            })
        })

        // BL 解锁状态
        out.addView(buildSection("BL 锁状态", "").apply {
            addView(actionBtn("查看解锁状态", Ui.buttonSecondary(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                executor.execute {
                    val unlocked = OtgAssistant.fastbootGetvar(activity, selectedFbSerial, "unlocked")
                    val ability = OtgAssistant.fastbootGetvar(activity, selectedFbSerial, "flashing unlock-ability")
                    activity.runOnUiThread {
                        appendFbLog("unlocked: ${unlocked ?: "无法获取"}")
                        appendFbLog("unlock-ability: ${ability ?: "无法获取"}")
                    }
                }
            })
        })

        // 分区刷入
        out.addView(buildSection("刷入分区", "").apply {
            val partSpinner = Spinner(activity).apply {
                adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, OtgAssistant.COMMON_PARTITIONS)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(partSpinner)
            
            val imgInput = mutableEditText("镜像路径", "")
            addView(imgInput)
            addView(actionBtn("选择镜像", Ui.buttonSecondary(activity)) {
                pendingFileAction = { imgInput.setText(it) }
                openFilePicker("选择 .img 镜像")
            })
            addView(row2btn(
                actionBtn("刷入", Ui.buttonPrimary(activity)) {
                    executeFlash(partSpinner, imgInput.text.toString().trim(), false)
                },
                actionBtn("刷入并重启", Ui.buttonSuccess(activity)) {
                    executeFlash(partSpinner, imgInput.text.toString().trim(), true)
                }
            ))
        })

        // 擦除分区
        out.addView(buildSection("擦除分区", "").apply {
            val eraseSpinner = Spinner(activity).apply {
                adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, OtgAssistant.COMMON_PARTITIONS)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(eraseSpinner)
            addView(actionBtn("擦除", Ui.buttonDanger(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                val part = OtgAssistant.COMMON_PARTITIONS[eraseSpinner.selectedItemPosition]
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.fastbootErase(activity, selectedFbSerial, part)
                    showProgress(false)
                    activity.runOnUiThread { appendFbLog(OtgAssistant.buildOutput(result)) }
                }
            })
        })

        // BL 解锁
        out.addView(buildSection("BL 解锁", "").apply {
            val unlockSpinner = Spinner(activity).apply {
                val labels = OtgAssistant.UNLOCK_OPTIONS.map { it.label }
                adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(unlockSpinner)
            addView(actionBtn("执行解锁", Ui.buttonWarning(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                val opt = OtgAssistant.UNLOCK_OPTIONS[unlockSpinner.selectedItemPosition]
                appendFbLog("警告: ${opt.warning}")
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.fastbootUnlockCmd(activity, selectedFbSerial, opt.commands)
                    showProgress(false)
                    activity.runOnUiThread { appendFbLog(OtgAssistant.buildOutput(result)) }
                }
            })
            addView(infoText("解锁会清除数据，请提前备份"))
        })

        // BL 上锁
        out.addView(buildSection("BL 上锁", "").apply {
            addView(actionBtn("上锁", Ui.buttonDanger(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.fastbootLockCmd(activity, selectedFbSerial)
                    showProgress(false)
                    activity.runOnUiThread { appendFbLog(OtgAssistant.buildOutput(result)) }
                }
            })
            addView(infoText("上锁前确保系统和 Recovery 是官方版本"))
        })

        // 重启控制
        out.addView(buildSection("重启", "").apply {
            addView(row4btn(
                actionBtn("系统", Ui.buttonSuccess(activity)) { executeReboot("system") },
                actionBtn("Bootloader", Ui.buttonPrimary(activity)) { executeReboot("bootloader") },
                actionBtn("Recovery", Ui.buttonWarning(activity)) { executeReboot("recovery") },
                actionBtn("EDL", Ui.buttonDanger(activity)) { executeReboot("edl") },
            ))
        })

        fbLogView = Ui.logTextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(120, density)
            ).apply { topMargin = Ui.dp(4, density) }
        }
        out.addView(fbLogView)

        refreshFbDevices()
        return out
    }

    // ==================== Shell Tab: 命令行终端 ====================

    private fun buildShellContent(): LinearLayout {
        val out = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        out.addView(buildSection("ADB Shell", "选择设备后执行命令").apply {
            val cmdInput = mutableEditText("命令", "getprop")
            addView(cmdInput)
            addView(row2btn(
                actionBtn("执行", Ui.buttonPrimary(activity)) {
                    if (selectedAdbSerial.isEmpty()) { appendShellLog("请先选择 ADB 设备"); return@actionBtn }
                    val cmd = cmdInput.text.toString().trim()
                    if (cmd.isEmpty()) { appendShellLog("请输入命令"); return@actionBtn }
                    showProgress(true)
                    executor.execute {
                        val result = OtgAssistant.adbShell(activity, selectedAdbSerial, cmd)
                        showProgress(false)
                        activity.runOnUiThread { appendShellLog(OtgAssistant.buildOutput(result)) }
                    }
                },
                actionBtn("清空", Ui.buttonSecondary(activity)) { shellLogView.text = "" }
            ))
            addView(row2btn(
                actionBtn("查看进程", Ui.buttonSecondary(activity)) {
                    if (selectedAdbSerial.isEmpty()) { appendShellLog("请先选择设备"); return@actionBtn }
                    executor.execute {
                        val procs = OtgAssistant.adbProcesses(activity, selectedAdbSerial)
                        activity.runOnUiThread { appendShellLog(procs) }
                    }
                },
                actionBtn("Root Shell", Ui.buttonDanger(activity)) {
                    if (selectedAdbSerial.isEmpty()) { appendShellLog("请先选择设备"); return@actionBtn }
                    executor.execute {
                        val r1 = OtgAssistant.adbRoot(activity, selectedAdbSerial)
                        val r2 = OtgAssistant.adbShellSu(activity, selectedAdbSerial, "id")
                        activity.runOnUiThread {
                            appendShellLog(OtgAssistant.buildOutput(r1))
                            appendShellLog(OtgAssistant.buildOutput(r2))
                        }
                    }
                }
            ))
        })

        out.addView(buildSection("快速命令", "").apply {
            val quickCmds = listOf(
                "uname -a" to "系统信息",
                "getprop" to "所有属性",
                "df -h" to "存储信息",
                "cat /proc/cpuinfo" to "CPU 信息",
                "dumpsys battery" to "电池信息",
                "logcat -d" to "当前日志",
            )
            quickCmds.forEach { (cmd, desc) ->
                addView(actionBtn(desc, Ui.buttonSecondary(activity)) {
                    if (selectedAdbSerial.isEmpty()) { appendShellLog("请先选择 ADB 设备"); return@actionBtn }
                    executor.execute {
                        val result = OtgAssistant.adbShell(activity, selectedAdbSerial, cmd)
                        activity.runOnUiThread { appendShellLog("=== $desc ===\n${OtgAssistant.buildOutput(result)}") }
                    }
                })
            }
        })

        shellLogView = Ui.logTextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200, density)
            ).apply { topMargin = Ui.dp(4, density) }
        }
        out.addView(shellLogView)

        return out
    }

    // ==================== 辅助方法 ====================

    private fun buildSection(title: String, hint: String = ""): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(activity, 12f)
            setPadding(Ui.dp(10, density), Ui.dp(8, density), Ui.dp(10, density), Ui.dp(8, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(6, density).toInt() }

            addView(TextView(activity).apply {
                text = title
                textSize = 14f
                setTypeface(Ui.typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
            })
            if (hint.isNotEmpty()) addView(infoText(hint))
        }
    }

    private fun actionBtn(label: String, color: Int, onClick: (View) -> Unit): TextView {
        return TextView(activity).apply {
            this.text = label
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            background = Ui.glassButton(activity, null)
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, density), Ui.dp(8, density), Ui.dp(12, density), Ui.dp(8, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, density).toInt() }
            setOnClickListener { onClick(this) }
        }
    }

    private fun mutableEditText(hint: String, default: String = ""): EditText {
        return EditText(activity).apply {
            this.hint = hint
            this.setText(default)
            textSize = 12f
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(Ui.dp(8, density).toInt(), Ui.dp(4, density).toInt(), Ui.dp(8, density).toInt(), Ui.dp(4, density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, density).toInt() }
        }
    }

    private fun row2btn(a: View, b: View): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, density).toInt() }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(4, density).toInt() })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun row4btn(a: View, b: View, c: View, d: View): LinearLayout {
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
            setPadding(0, Ui.dp(2, density).toInt(), 0, Ui.dp(4, density).toInt())
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
                val parent = findContentContainer() ?: return@runOnUiThread
                val deviceContainer = parent.findViewWithTag<LinearLayout>("adbDeviceList")
                deviceContainer?.removeAllViews()
                if (devices.isEmpty()) {
                    deviceContainer?.addView(infoText("未检测到 ADB 设备"))
                } else {
                    devices.forEach { serial ->
                        deviceContainer?.addView(adbDeviceRow(serial))
                    }
                }
            }
        }
    }

    private fun adbDeviceRow(serial: String): TextView {
        return TextView(activity).apply {
            text = serial
            textSize = 12f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(8, density), Ui.dp(6, density), Ui.dp(8, density), Ui.dp(6, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, density).toInt() }
            setOnClickListener {
                selectedAdbSerial = serial
                appendAdbLog("已选择设备: $serial")
            }
        }
    }

    private fun runAdbCmd(vararg args: String) {
        executor.execute {
            val serialArgs = if (selectedAdbSerial.isNotEmpty()) arrayOf("-s", selectedAdbSerial) else emptyArray()
            val result = OtgAssistant.run(activity, "adb", (serialArgs + args).toList(), timeoutMs = 60000)
            val output = OtgAssistant.buildOutput(result)
            activity.runOnUiThread { appendAdbLog(output) }
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
                val parent = findContentContainer() ?: return@runOnUiThread
                val deviceContainer = parent.findViewWithTag<LinearLayout>("fbDeviceList")
                deviceContainer?.removeAllViews()
                if (devices.isEmpty()) {
                    deviceContainer?.addView(infoText("未检测到 Fastboot 设备"))
                } else {
                    devices.forEach { serial ->
                        deviceContainer?.addView(fbDeviceRow(serial))
                    }
                }
            }
        }
    }

    private fun fbDeviceRow(serial: String): TextView {
        return TextView(activity).apply {
            text = serial
            textSize = 12f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(8, density), Ui.dp(6, density), Ui.dp(8, density), Ui.dp(6, density))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, density).toInt() }
            setOnClickListener {
                selectedFbSerial = serial
                appendFbLog("已选择设备: $serial")
            }
        }
    }

    private fun executeFlash(partitionSpinner: Spinner, imgPath: String, reboot: Boolean) {
        if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return }
        if (imgPath.isEmpty()) { appendFbLog("请填写镜像路径"); return }
        val partition = OtgAssistant.COMMON_PARTITIONS[partitionSpinner.selectedItemPosition]
        if (partition.isEmpty()) { appendFbLog("请选择分区"); return }
        
        showProgress(true)
        executor.execute {
            val result = if (reboot) {
                OtgAssistant.fastbootFlashAndReboot(activity, selectedFbSerial, partition, imgPath)
            } else {
                OtgAssistant.fastbootFlash(activity, selectedFbSerial, partition, imgPath)
            }
            showProgress(false)
            activity.runOnUiThread { appendFbLog(OtgAssistant.buildOutput(result)) }
        }
    }

    private fun executeReboot(target: String) {
        if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return }
        executor.execute {
            val result = OtgAssistant.fastbootReboot(activity, selectedFbSerial, target)
            activity.runOnUiThread { appendFbLog(OtgAssistant.buildOutput(result)) }
        }
    }

    private fun appendFbLog(msg: String) {
        fbLogView.append("$msg\n")
        fbLogView.post { (fbLogView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendShellLog(msg: String) {
        shellLogView.append("$msg\n")
        shellLogView.post { (shellLogView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    // ==================== 通用方法 ====================

    private fun showProgress(show: Boolean) {
        activity.runOnUiThread {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun findContentContainer(): LinearLayout? {
        val sv = (activity.findViewById<View>(android.R.id.content) as? ViewGroup)?.getChildAt(0) as? ScrollView ?: return null
        for (i in 1 until sv.childCount) {
            val v = sv.getChildAt(i)
            if (v is LinearLayout && v.visibility != View.GONE) return v
        }
        return null
    }

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
        pendingFileAction?.invoke(path)
        pendingFileAction = null
    }

    fun onDestroy() {
        executor.shutdown()
    }
}
