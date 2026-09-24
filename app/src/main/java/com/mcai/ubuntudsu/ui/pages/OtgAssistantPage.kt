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
import android.widget.Toast
import com.mcai.ubuntudsu.RootfsFilesActivity
import com.mcai.ubuntudsu.core.OtgAssistant
import com.mcai.ubuntudsu.core.ShellResult
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OTG 刷机助手 - 全能版
 * 
 * 功能：
 * - ADB: 设备管理、文件传输、Shell 执行、应用安装、系统操作
 * - Fastboot: 分区刷入、BL 解锁/上锁、重启控制、信息查看
 * - 设备检测: USB 设备枚举、ADB/Fastboot/EDL 状态
 */
class OtgAssistantPage(
    private val activity: Activity,
    private val onDismiss: (() -> Unit)? = null,
) {
    private val d: Float get() = activity.resources.displayMetrics.density
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(true)

    // 设备选择
    private var selectedAdbSerial: String = ""
    private var selectedFbSerial: String = ""

    // 日志视图
    private lateinit var adbLogView: TextView
    private lateinit var fbLogView: TextView
    private lateinit var shellLogView: TextView

    // 进度条
    private lateinit var progressBar: ProgressBar

    // Tab 状态
    private var currentTab = 0
    private lateinit var tabLayout: LinearLayout

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
        root.addView(buildTitleBar(root))

        // Tab 栏
        tabLayout = buildTabBar()
        root.addView(tabLayout)

        // ADB 内容
        val adbContent = buildAdbContent()
        adbContent.id = View.generateViewId()
        root.addView(adbContent)

        // Fastboot 内容
        val fbContent = buildFastbootContent()
        fbContent.id = View.generateViewId()
        fbContent.visibility = View.GONE
        root.addView(fbContent)

        // Shell 内容
        val shellContent = buildShellContent()
        shellContent.id = View.generateViewId()
        shellContent.visibility = View.GONE
        root.addView(shellContent)

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

    private fun buildTitleBar(root: LinearLayout): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(8, d))

            addView(TextView(activity).apply {
                text = "<"
                textSize = 20f
                setTypeface(Ui.typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                gravity = Gravity.CENTER
                setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
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
            background = Ui.neuCard(activity, 12f)
            setPadding(Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(8, d) }

            tabs.forEachIndexed { i, label ->
                addView(TextView(activity).apply {
                    text = label
                    textSize = 13f
                    setTypeface(Ui.typeface, Typeface.BOLD)
                    setTextColor(if (i == 0) Ui.buttonPrimary(activity) else Ui.secondaryText(activity))
                    gravity = Gravity.CENTER
                    background = Ui.glassButton(activity, null)
                    Ui.pressAnimation(this)
                    setPadding(Ui.dp(0, d), Ui.dp(8, d), Ui.dp(0, d), Ui.dp(8, d))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { switchTab(i) }
                })
            }
        }
    }

    private fun switchTab(idx: Int) {
        currentTab = idx
        // 更新 tab 颜色
        for (i in 0 until tabLayout.childCount) {
            val tv = tabLayout.getChildAt(i) as? TextView ?: continue
            tv.setTextColor(if (i == idx) Ui.buttonPrimary(activity) else Ui.secondaryText(activity))
        }
        rebuildContent()
    }

    private fun rebuildContent() {
        val parent = (activity.findViewById<View>(android.R.id.content) as? ViewGroup)?.getChildAt(0) as? ScrollView
            ?: return
        val root = parent.getChildAt(0) as? LinearLayout ?: return
        
        // 移除内容区域（保留标题和 tab）
        while (root.childCount > 2) root.removeViewAt(2)
        
        val content = when (currentTab) {
            0 -> buildAdbContent()
            1 -> buildFastbootContent()
            else -> buildShellContent()
        }
        content.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(8, d) }
        root.addView(content)
        root.addView(progressBar)
    }

    // ==================== ADB 内容 ====================

    private fun buildAdbContent(): LinearLayout {
        val out = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // 设备列表
        out.addView(buildSection("设备列表", "").apply {
            val deviceList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                id = View.generateViewId()
                setTag("adbDeviceList")
            }
            addView(deviceList)
            addView(actionBtn("刷新设备", Ui.buttonPrimary(activity)) { refreshAdbDevices() })
        })

        // ADB 服务控制
        out.addView(buildSection("ADB 服务", "").apply {
            addView(row3btn(
                actionBtn("启用", Ui.buttonSuccess(activity)) { runAdbCmd("start-server"); appendAdbLog("已启用 ADB 服务") },
                actionBtn("关闭", Ui.buttonDanger(activity)) { runAdbCmd("kill-server"); appendAdbLog("已关闭 ADB 服务") },
                actionBtn("重启", Ui.buttonWarning(activity)) { runAdbCmd("kill-server"); runAdbCmd("start-server"); appendAdbLog("已重启 ADB 服务") },
            ))
        })

        // 无线 ADB
        out.addView(buildSection("无线 ADB 连接", "").apply {
            val ipInput = mutableEditText("IP:端口", "192.168.1.100:5555")
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

        // 设备信息
        out.addView(buildSection("设备信息", "").apply {
            addView(actionBtn("查看设备信息", Ui.buttonSecondary(activity)) {
                if (selectedAdbSerial.isEmpty()) { appendAdbLog("请先选择设备"); return@actionBtn }
                val serial = selectedAdbSerial
                executor.execute {
                    val info = OtgAssistant.adbDeviceInfo(activity, serial)
                    activity.runOnUiThread {
                        appendAdbLog("=== 设备信息 ===")
                        info.forEach { (k, v) -> appendAdbLog("$k: $v") }
                    }
                }
            })
        })

        // 文件推送
        out.addView(buildSection("推送文件", "").apply {
            val localPathInput = mutableEditText("本地路径", "")
            val remotePathInput = mutableEditText("目标路径", "/sdcard")
            addView(localPathInput)
            addView(remotePathInput)
            addView(actionBtn("选择本地文件", Ui.buttonSecondary(activity)) {
                pendingFileAction = { path -> localPathInput.setText(path) }
                openFilePicker("请选择要推送的文件")
            })
            addView(actionBtn("推送到设备", Ui.buttonPrimary(activity)) {
                if (localPathInput.text.toString().trim().isEmpty()) { appendAdbLog("请填写本地路径"); return@actionBtn }
                if (selectedAdbSerial.isEmpty()) { appendAdbLog("请先选择设备"); return@actionBtn }
                val local = localPathInput.text.toString().trim()
                val remote = remotePathInput.text.toString().trim()
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.adbPush(activity, selectedAdbSerial, local, remote)
                    showProgress(false)
                    activity.runOnUiThread { appendAdbLog(OtgAssistant.buildOutput(result)) }
                }
            })
        })

        // 文件拉取
        out.addView(buildSection("从设备复制文件", "").apply {
            val remotePathInput = mutableEditText("设备路径", "/sdcard")
            val localDirInput = mutableEditText("本地目录", "")
            addView(remotePathInput)
            addView(localDirInput)
            addView(actionBtn("选择保存目录", Ui.buttonSecondary(activity)) {
                pendingFileAction = { path -> localDirInput.setText(path) }
                openFilePicker("请选择保存目录", isFolder = true)
            })
            addView(actionBtn("从设备拉取", Ui.buttonPrimary(activity)) {
                if (localDirInput.text.toString().trim().isEmpty()) { appendAdbLog("请选择保存目录"); return@actionBtn }
                if (selectedAdbSerial.isEmpty()) { appendAdbLog("请先选择设备"); return@actionBtn }
                val remote = remotePathInput.text.toString().trim()
                val local = localDirInput.text.toString().trim()
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.adbPull(activity, selectedAdbSerial, remote, local)
                    showProgress(false)
                    activity.runOnUiThread { appendAdbLog(OtgAssistant.buildOutput(result)) }
                }
            })
        })

        // 应用安装
        out.addView(buildSection("安装应用", "").apply {
            val apkPathInput = mutableEditText("APK 路径", "")
            addView(apkPathInput)
            addView(actionBtn("选择 APK", Ui.buttonSecondary(activity)) {
                pendingFileAction = { path -> apkPathInput.setText(path) }
                openFilePicker("请选择 APK 文件")
            })
            addView(actionBtn("安装到设备", Ui.buttonPrimary(activity)) {
                if (apkPathInput.text.toString().trim().isEmpty()) { appendAdbLog("请填写 APK 路径"); return@actionBtn }
                if (selectedAdbSerial.isEmpty()) { appendAdbLog("请先选择设备"); return@actionBtn }
                val apkPath = apkPathInput.text.toString().trim()
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.adbInstall(activity, selectedAdbSerial, apkPath)
                    showProgress(false)
                    activity.runOnUiThread { appendAdbLog(OtgAssistant.buildOutput(result)) }
                }
            })
        })

        // 重启控制
        out.addView(buildSection("重启控制", "").apply {
            addView(row4btn(
                actionBtn("系统", Ui.buttonSuccess(activity)) { runAdbCmd("reboot") },
                actionBtn("Bootloader", Ui.buttonPrimary(activity)) { runAdbCmd("reboot bootloader") },
                actionBtn("Recovery", Ui.buttonWarning(activity)) { runAdbCmd("reboot recovery") },
                actionBtn("EDL", Ui.buttonDanger(activity)) { runAdbCmd("reboot edl") },
            ))
        })

        // 日志
        adbLogView = Ui.logTextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(150, d)
            ).apply { topMargin = Ui.dp(4, d) }
        }
        out.addView(adbLogView)

        refreshAdbDevices()
        return out
    }

    // ==================== Fastboot 内容 ====================

    private fun buildFastbootContent(): LinearLayout {
        val out = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // 设备列表
        out.addView(buildSection("设备列表", "").apply {
            val deviceList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                id = View.generateViewId()
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
                    val partitions = OtgAssistant.fastbootPartitions(activity, selectedFbSerial)
                    activity.runOnUiThread {
                        appendFbLog("=== 可用分区 (${partitions.size} 个) ===")
                        partitions.forEach { p -> appendFbLog("${p.name} (${p.type}, ${p.sizeStr})") }
                    }
                }
            })
        })

        // BL 锁状态
        out.addView(buildSection("BL 锁状态", "").apply {
            addView(actionBtn("查看解锁状态", Ui.buttonSecondary(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                executor.execute {
                    val unlocked = OtgAssistant.fastbootGetvar(activity, selectedFbSerial, "unlocked")
                    val unlockAbility = OtgAssistant.fastbootGetvar(activity, selectedFbSerial, "flashing unlock-ability")
                    activity.runOnUiThread {
                        appendFbLog("unlocked: ${unlocked ?: "无法获取"}")
                        appendFbLog("unlock-ability: ${unlockAbility ?: "无法获取"}")
                    }
                }
            })
        })

        // 分区刷入
        out.addView(buildSection("分区刷入", "").apply {
            val partitionSpinner = Spinner(activity).apply {
                adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, OtgAssistant.COMMON_PARTITIONS)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(partitionSpinner)
            
            val imgPathInput = mutableEditText("镜像路径", "")
            addView(imgPathInput)
            addView(actionBtn("选择镜像文件", Ui.buttonSecondary(activity)) {
                pendingFileAction = { path -> imgPathInput.setText(path) }
                openFilePicker("请选择 .img 镜像文件")
            })
            addView(row2btn(
                actionBtn("刷入", Ui.buttonPrimary(activity)) {
                    executeFlash(partitionSpinner, imgPathInput.text.toString().trim(), false)
                },
                actionBtn("刷入并重启", Ui.buttonSuccess(activity)) {
                    executeFlash(partitionSpinner, imgPathInput.text.toString().trim(), true)
                },
            ))
        })

        // 擦除分区
        out.addView(buildSection("擦除分区", "").apply {
            val eraseSpinner = Spinner(activity).apply {
                adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, OtgAssistant.COMMON_PARTITIONS)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(eraseSpinner)
            addView(actionBtn("擦除分区", Ui.buttonDanger(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                val partition = OtgAssistant.COMMON_PARTITIONS[eraseSpinner.selectedItemPosition]
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.fastbootErase(activity, selectedFbSerial, partition)
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
                val option = OtgAssistant.UNLOCK_OPTIONS[unlockSpinner.selectedItemPosition]
                appendFbLog("警告: ${option.warning}")
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.fastbootUnlockCmd(activity, selectedFbSerial, option.commands)
                    showProgress(false)
                    activity.runOnUiThread { appendFbLog(OtgAssistant.buildOutput(result)) }
                }
            })
            addView(infoText("注意：解锁会清除设备数据，请提前备份"))
        })

        // BL 上锁
        out.addView(buildSection("BL 上锁", "").apply {
            addView(actionBtn("上锁 BL", Ui.buttonDanger(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.fastbootLockCmd(activity, selectedFbSerial)
                    showProgress(false)
                    activity.runOnUiThread { appendFbLog(OtgAssistant.buildOutput(result)) }
                }
            })
            addView(infoText("警告：上锁前请确保系统和 REC 都是官方版本，否则可能变砖"))
        })

        // A/B 卡槽
        out.addView(buildSection("A/B 卡槽", "").apply {
            addView(actionBtn("查看当前槽位", Ui.buttonSecondary(activity)) {
                if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return@actionBtn }
                executor.execute {
                    val slot = OtgAssistant.fastbootGetvar(activity, selectedFbSerial, "current-slot")
                    activity.runOnUiThread { appendFbLog("current-slot: ${slot ?: "无法获取"}") }
                }
            })
            addView(row2btn(
                actionBtn("切换到 A", Ui.buttonPrimary(activity)) { executeSetActive("a") },
                actionBtn("切换到 B", Ui.buttonPrimary(activity)) { executeSetActive("b") },
            ))
        })

        // 重启控制
        out.addView(buildSection("重启控制", "").apply {
            addView(row4btn(
                actionBtn("系统", Ui.buttonSuccess(activity)) { executeReboot("system") },
                actionBtn("Bootloader", Ui.buttonPrimary(activity)) { executeReboot("bootloader") },
                actionBtn("Recovery", Ui.buttonWarning(activity)) { executeReboot("recovery") },
                actionBtn("EDL", Ui.buttonDanger(activity)) { executeReboot("edl") },
            ))
        })

        // 日志
        fbLogView = Ui.logTextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(150, d)
            ).apply { topMargin = Ui.dp(4, d) }
        }
        out.addView(fbLogView)

        refreshFbDevices()
        return out
    }

    // ==================== Shell 内容 ====================

    private fun buildShellContent(): LinearLayout {
        val out = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        out.addView(buildSection("ADB Shell", "").apply {
            val cmdInput = mutableEditText("命令", "getprop")
            addView(cmdInput)
            addView(actionBtn("执行命令", Ui.buttonPrimary(activity)) {
                if (selectedAdbSerial.isEmpty()) { appendShellLog("请先选择 ADB 设备"); return@actionBtn }
                val cmd = cmdInput.text.toString().trim()
                if (cmd.isEmpty()) { appendShellLog("请输入命令"); return@actionBtn }
                showProgress(true)
                executor.execute {
                    val result = OtgAssistant.adbShell(activity, selectedAdbSerial, cmd)
                    showProgress(false)
                    activity.runOnUiThread { appendShellLog(OtgAssistant.buildOutput(result)) }
                }
            })
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
                    val result = OtgAssistant.adbRoot(activity, selectedAdbSerial)
                    appendShellLog(OtgAssistant.buildOutput(result))
                    executor.execute {
                        val r = OtgAssistant.adbShellSu(activity, selectedAdbSerial, "id")
                        activity.runOnUiThread { appendShellLog(OtgAssistant.buildOutput(r)) }
                    }
                },
            ))
        })

        out.addView(buildSection("快速命令", "").apply {
            val quickCmds = listOf(
                "uname -a" to "系统信息",
                "getprop" to "查看所有属性",
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
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(150, d)
            ).apply { topMargin = Ui.dp(4, d) }
        }
        out.addView(shellLogView)

        return out
    }

    // ==================== 辅助方法 ====================

    private fun buildSection(title: String, hint: String = ""): LinearLayout {
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
            if (hint.isNotEmpty()) addView(infoText(hint))
        }
    }

    private fun actionBtn(label: String, color: Int, onClick: (View) -> Unit): TextView {
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
            setOnClickListener { onClick(this) }
        }
    }

    private fun mutableEditText(hint: String, default: String = ""): EditText {
        return EditText(activity).apply {
            this.hint = hint
            this.setText(default)
            textSize = 12f
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt(), Ui.dp(8, d).toInt(), Ui.dp(4, d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, d).toInt() }
        }
    }

    private fun getEditTexts(parent: LinearLayout?): List<EditText> {
        return parent?.let { 
            (0 until it.childCount).mapNotNull { i -> it.getChildAt(i) as? EditText }
        } ?: emptyList()
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

    private fun row4btn(a: View, b: View, c: View, dd: View): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, d).toInt() }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, d).toInt() })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, d).toInt() })
            addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, d).toInt() })
            addView(dd, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                // 找到设备列表容器
                val parent = findContentContainer("adb") ?: return@runOnUiThread
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
            val serialArgs = if (selectedAdbSerial.isNotEmpty()) arrayOf("-s", selectedAdbSerial) else emptyArray()
            val result = OtgAssistant.run(activity, "adb", (serialArgs + args).toList(), timeoutMs = 60000)
            val output = OtgAssistant.buildOutput(result)
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
                val parent = findContentContainer("fastboot") ?: return@runOnUiThread
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

    private fun executeSetActive(slot: String) {
        if (selectedFbSerial.isEmpty()) { appendFbLog("请先选择设备"); return }
        showProgress(true)
        executor.execute {
            val result = OtgAssistant.fastbootSetActive(activity, selectedFbSerial, slot)
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

    // ==================== Shell 操作 ====================

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

    private fun findContentContainer(tabName: String): LinearLayout? {
        val sv = (activity.findViewById<View>(android.R.id.content) as? ViewGroup)?.getChildAt(0) as? ScrollView
            ?: return null
        // 遍历所有子 view 找到对应的内容容器
        for (i in 1 until sv.childCount) {
            val v = sv.getChildAt(i)
            if (v is LinearLayout && v.visibility != View.GONE) return v
        }
        return null
    }

    // ==================== 文件选择 ====================

    private var pendingFileAction: ((String) -> Unit)? = null

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
