package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.core.OtgAssistantCore
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui

/**
 * OTG 刷机助手 - 单屏卡片式布局
 *
 * 复用主应用的拟态（Neumorphism）+ 液态玻璃渲染架构（[Ui] / [LiquidGlass]），
 * 全部控件经 Ui 取色取形，震动反馈经 [Ui.pressAnimation] / [Haptics]，
 * 日间/夜间主题经 [Ui.isDark] 自动切换。底层命令/文件/OTA 逻辑由 [OtgAssistantCore] 提供。
 */
class OtgAssistantPage(
    private val activity: Activity,
    private val onBack: () -> Unit,
) {
    private val d: Float get() = activity.resources.displayMetrics.density
    private val ctx: Context get() = activity
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var alive = true
    private var operationActive = false
    private var refreshing = false
    private var receiverRegistered = false
    private var dialogRef: androidx.appcompat.app.AlertDialog? = null

    private var parsedPartitions = emptyList<OtgAssistantCore.PartitionInfo>()
    private var selectedPartition: String? = null
    private var fullImages = mutableListOf<OtgAssistantCore.ImageInfo>()
    private val confirmationPhrase = "我确认固件包匹配当前设备，并允许清除全部用户数据"

    private data class RebootMode(
        val title: String,
        val subtitle: String,
        val command: String,
        val iconRes: Int,
        val accent: Int,
    )

    // ===== 文件选择（调用 APP 内置文件选择器 RootfsFilesActivity）=====
    private val singleImageRequest = 402
    private val otaRequest = 403
    private val adbPushRequest = 404

    /** 启动内置文件选择器（RootfsFilesActivity，根目录 /sdcard），按扩展名过滤 */
    private fun launchBuiltInPicker(requestCode: Int, title: String, ext: String = "", extAll: Boolean = true) {
        activity.startActivityForResult(
            Intent(activity, com.mcai.ubuntudsu.RootfsFilesActivity::class.java).apply {
                putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_PICK, true)
                putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_TITLE, title)
                if (extAll) putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_EXT_ALL, true)
                else putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_EXT, ext)
            },
            requestCode,
        )
    }

    private fun singleImageLauncher() {
        launchBuiltInPicker(singleImageRequest, "选择单分区镜像", extAll = false, ext = ".img,.bin,.dat")
    }

    private fun otaLauncher() {
        launchBuiltInPicker(otaRequest, "选择 OTA 全量包（payload.bin / zip）")
    }

    private fun adbPushLauncher() {
        launchBuiltInPicker(adbPushRequest, "选择要推送的文件")
    }

    /** 宿主 Activity.onActivityResulT 派发到本类，取内置选择器返回的本地文件路径 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK) return
        val path = data?.getStringExtra(com.mcai.ubuntudsu.RootfsFilesActivity.RESULT_FILE_PATH) ?: return
        val file = java.io.File(path)
        when (requestCode) {
            singleImageRequest -> prepareSingleImage(file)
            otaRequest -> extractAndScanOta(file)
            adbPushRequest -> prepareAdbPush(file)
        }
    }

    private lateinit var deviceListText: TextView
    private lateinit var protocolText: TextView
    private lateinit var commandInput: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progressBar: View
    private lateinit var progressStatus: TextView
    private lateinit var btnRefreshDevice: TextView
    private lateinit var btnExecute: TextView
    private lateinit var btnPartitions: TextView
    private lateinit var btnSingleFlash: TextView
    private lateinit var btnFullFlash: TextView
    private lateinit var btnReboot: TextView
    private lateinit var btnAdbPush: TextView
    private lateinit var btnAdbInfo: TextView

    // ===== USB 插拔自动刷新 =====
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    postUi { appendLog("\n[自动] 检测到设备接入\n"); refreshDevices() }
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    postUi { appendLog("\n[自动] 检测到设备断开\n"); refreshDevices() }
            }
        }
    }

    fun build(): View {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(8, d))
        }

        page.addView(buildTitleRow())
        page.addView(buildDeviceCard())
        page.addView(buildCommandCard())
        page.addView(buildActionGrid())
        page.addView(buildLogCard())

        // 初始化：清理旧 OTA + 加载工具
        OtgAssistantCore.deleteOtaDirectory(activity.applicationContext)
        logView.text = "正在初始化...\n"
        refreshDevices()

        return page
    }

    // ==================== 标题栏 ====================

    private fun softButtonColors(accent: Int): Pair<Int, Int> =
        if (Ui.isDark(activity)) Pair(accent, accent) else Pair(Color.rgb(247, 239, 200), Color.rgb(201, 225, 248))

    private fun softButtonText(): Int = if (Ui.isDark(activity)) Color.WHITE else Color.BLACK

    private fun solidButtonText(): Int = if (Ui.isDark(activity)) Color.WHITE else Color.BLACK

    private fun buildTitleRow(): View {
        val row = FrameLayout(activity).apply {
            setPadding(0, 0, 0, Ui.dp(12, d))
        }
        row.addView(TextView(activity).apply {
            text = "OTG 刷机助手"
            textSize = 18f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        btnRefreshDevice = TextView(activity).apply {
            text = "刷新"
            textSize = 12f
            setTextColor(Ui.buttonText(activity))
            background = Ui.neuSolidButton(softButtonColors(Ui.buttonSecondary(activity)).first, softButtonColors(Ui.buttonSecondary(activity)).second, 10f, activity)
            Ui.pressAnimation(this)
            setPadding(Ui.dp(10, d), Ui.dp(6, d), Ui.dp(10, d), Ui.dp(6, d))
            setOnClickListener {
                Haptics.perform(this)
                refreshDevices()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        }
        row.addView(btnRefreshDevice)
        return row
    }

    // ==================== 设备状态卡片 ====================

    private fun buildDeviceCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        card.addView(TextView(activity).apply {
            text = "设备状态"
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        deviceListText = Ui.logTextView(activity).apply {
            text = "等待 USB 设备连接..."
            setPadding(0, Ui.dp(6, d), 0, 0)
        }
        card.addView(deviceListText)
        protocolText = TextView(activity).apply {
            text = "ADB: —    Fastboot: —"
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        card.addView(protocolText)
        return card
    }

    // ==================== 命令执行卡片 ====================

    private fun buildCommandCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 2f, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        card.addView(TextView(activity).apply {
            text = "输入命令"
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        commandInput = EditText(activity).apply {
            hint = "例如：adb devices / fastboot getvar all"
            textSize = 12f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = Ui.neuInset(activity, 12f)
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(6, d) }
        }
        card.addView(commandInput)
        btnExecute = TextView(activity).apply {
            text = "执行命令"
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(solidButtonText())
            gravity = Gravity.CENTER
            background = Ui.neuSolidButton(softButtonColors(Ui.buttonPrimary(activity)).first, softButtonColors(Ui.buttonPrimary(activity)).second, 14f, activity)
            Ui.pressAnimation(this)
            setPadding(Ui.dp(0, d), Ui.dp(8, d), Ui.dp(0, d), Ui.dp(8, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(8, d) }
            setOnClickListener {
                Haptics.perform(this)
                executeCommand()
            }
        }
        card.addView(btnExecute)
        return card
    }

    // ==================== 操作按钮网格（2 列） ====================

    private fun buildActionGrid(): View {
        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        btnPartitions = actionBtn("读取分区表", Ui.buttonSecondary(activity)) { readPartitions() }
        btnSingleFlash = actionBtn("单分区刷写", Ui.buttonWarning(activity)) { chooseSinglePartition() }
        btnFullFlash = actionBtn("全量包刷写", Ui.buttonDanger(activity)) { chooseFirmwareDirectory() }
        btnReboot = actionBtn("高级重启", Ui.buttonSuccess(activity)) { showRebootMenu() }
        btnAdbPush = actionBtn("ADB 推送", Ui.buttonSecondary(activity)) { chooseAdbPushFile() }
        btnAdbInfo = actionBtn("ADB 设备信息", Ui.buttonPrimary(activity)) { showAdbDeviceInfo() }

        grid.addView(gridRow(btnPartitions, btnSingleFlash))
        grid.addView(gridRow(btnFullFlash, btnReboot))
        grid.addView(gridRow(btnAdbPush, btnAdbInfo))
        return grid
    }

    private fun gridRow(a: View, b: View): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(6, d) }
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(6, d) })
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun actionBtn(label: String, accent: Int, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(solidButtonText())
            gravity = Gravity.CENTER
            background = Ui.neuSolidButton(softButtonColors(accent).first, softButtonColors(accent).second, 14f, activity)
            Ui.pressAnimation(this)
            setPadding(0, Ui.dp(9, d), 0, Ui.dp(9, d))
            setOnClickListener {
                Haptics.perform(this)
                onClick()
            }
        }
    }

    // ==================== 日志卡片 ====================

    private fun buildLogCard(): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.neuInset(activity, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        card.addView(TextView(activity).apply {
            text = "输出日志"
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        })
        logScroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(200, d).toInt(),
            )
            isNestedScrollingEnabled = false
        }
        logView = Ui.logTextView(activity).apply {
            text = "日志将显示在这里...\n"
            setPadding(0, 0, 0, 0)
        }
        logScroll.addView(logView)
        card.addView(logScroll)

        // 进度条 + 状态
        progressBar = View(activity).apply {
            background = Ui.rounded(Ui.border(activity), 2f, d)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(3, d),
            ).apply { topMargin = Ui.dp(8, d) }
        }
        card.addView(progressBar)
        progressStatus = TextView(activity).apply {
            textSize = 10f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        card.addView(progressStatus)
        return card
    }

    // ==================== 设备扫描 ====================

    private fun refreshDevices() {
        if (refreshing) return
        refreshing = true
        appendLog("\n正在扫描设备...\n")
        Thread {
            if (!alive) return@Thread
            val devices = OtgAssistantCore.listUsbDevices(ctx)
            val protocol = OtgAssistantCore.detectProtocolDevices(ctx)
            postUi {
                if (devices.isEmpty()) {
                    deviceListText.text = "未找到 USB 设备"
                    appendLog("未发现设备\n")
                } else {
                    val shown = devices.take(8)
                    val text = shown.joinToString("\n") { deviceInfoString(it) }
                    deviceListText.text = if (devices.size > 8) "$text\n...共 ${devices.size} 个设备" else text
                    appendLog("发现 ${devices.size} 个设备\n")
                }
                protocolText.text = "ADB: ${protocol.adb}\nFastboot: ${protocol.fastboot}"
                appendLog("协议检测：ADB=${protocol.adb != "未发现设备"}，Fastboot=${protocol.fastboot != "未发现设备"}\n")
                refreshing = false
            }
        }.start()
    }

    private fun deviceInfoString(dv: OtgAssistantCore.DeviceInfo): String {
        val vid = String.format("%04x", dv.vendorId)
        val pid = String.format("%04x", dv.productId)
        return "VID:$vid  PID:$pid  ${dv.deviceName}"
    }

    // ==================== 命令执行 ====================

    private fun executeCommand() {
        val command = commandInput.text.toString().trim()
        if (command.isEmpty()) {
            toast("请输入命令")
            return
        }
        appendLog("\n>$command\n")
        Thread {
            val adb = OtgAssistantCore.getAdbPath(ctx)
            val fastboot = OtgAssistantCore.getFastbootPath(ctx)
            val toolPath = if (command.startsWith("fastboot")) fastboot else adb
            OtgAssistantCore.executeCommand(toolPath, command) { line ->
                postUi {
                    appendLog("$line\n")
                }
            }
        }.start()
    }

    private fun readPartitions() {
        if (operationActive) return
        appendLog("\n>fastboot getvar all\n")
        Thread {
            val output = StringBuilder()
            OtgAssistantCore.executeCommandDetailed(OtgAssistantCore.getFastbootPath(ctx), "fastboot getvar all") { line ->
                synchronized(output) { output.append(line).append('\n') }
                postUi { appendLog("$line\n") }
            }
            val partitions = OtgAssistantCore.parseFastbootPartitions(output.toString())
            postUi {
                parsedPartitions = partitions
                appendLog("解析到 ${partitions.size} 个分区\n")
            }
        }.start()
    }

    // ==================== 单分区刷写 ====================

    private fun chooseSinglePartition() {
        if (operationActive) return
        if (parsedPartitions.isEmpty()) {
            toast("请先读取分区表")
            return
        }
        val names = parsedPartitions.map { it.name }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("选择目标分区")
            .setItems(names) { _, which ->
                selectedPartition = names[which]
                singleImageLauncher()
            }
            .show()
    }

    private fun prepareSingleImage(file: java.io.File) {
        val partition = selectedPartition ?: return
        Thread {
            try {
                val name = displayName(file)
                val digest = OtgAssistantCore.sha256(file)
                val size = file.length()
                postUi {
                    AlertDialog.Builder(activity)
                        .setTitle("确认单分区刷写")
                        .setMessage("分区: $partition\n文件: $name\n大小: ${formatBytes(size)}\nSHA-256: $digest")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("刷写") { _, _ -> Haptics.perform(activity.window.decorView); flashSingle(file, partition, name) }
                        .show()
                }
            } catch (e: Exception) {
                postUi { appendLog("读取镜像失败: ${e.message}\n") }
            }
        }.start()
    }

    private fun flashSingle(file: java.io.File, partition: String, name: String) {
        setOperationActive(true)
        Thread {
            try {
                val image = OtgAssistantCore.copyLocalFileToCache(ctx, file, name)
                runFastbootBlocking("fastboot flash $partition ${image.absolutePath}")
            } catch (e: Exception) {
                postUi { appendLog("准备镜像失败: ${e.message}\n") }
            } finally {
                postUi { setOperationActive(false) }
            }
        }.start()
    }

    // ==================== 全量包（OTA payload） ====================

    private fun chooseFirmwareDirectory() {
        if (operationActive) return
        otaLauncher()
    }

    private fun extractAndScanOta(file: java.io.File) {
        val progressAnimal = TextView(activity).apply {
            text = "已完成 0%\n准备读取 BIN/ZIP..."
            textSize = 13f
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
        }
        val hint = TextView(activity).apply {
            text = "解包速度取决于设备性能与存储速度，通常需要 2-5 分钟。"
            textSize = 12f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(10, d), 0, 0)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(24, d), Ui.dp(20, d), Ui.dp(24, d), Ui.dp(20, d))
            addView(progressAnimal)
            addView(hint)
        }
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在解包 OTA")
            .setView(content)
            .setCancelable(false)
            .create()
        progressDialog.show()
        Thread {
            try {
                val extraction = OtgAssistantCore.extractOtaPackage(
                    ctx,
                    file,
                ) { percent, line ->
                    postUi {
                        if (percent >= 0) progressAnimal.text = "已完成 $percent%\n$line"
                        else progressAnimal.text = line
                    }
                }
                val images = OtgAssistantCore.listExtractedImages(extraction.root)
                postUi {
                    progressDialog.dismiss()
                    fullImages = images.toMutableList()
                    if (images.isEmpty()) {
                        appendLog("OTA 包中没有可用的 .img 镜像\n")
                        OtgAssistantCore.deleteOtaDirectory(ctx)
                    } else {
                        showImageSelection()
                    }
                }
            } catch (e: Exception) {
                OtgAssistantCore.deleteOtaDirectory(ctx)
                postUi {
                    progressDialog.dismiss()
                    appendLog("解压或扫描 OTA 包失败: ${e.message}\n")
                }
            }
        }.start()
    }

    private fun showImageSelection() {
        val checks = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(8, d), Ui.dp(8, d), Ui.dp(8, d), Ui.dp(8, d))
        }
        fullImages.forEach { image ->
            android.widget.CheckBox(activity).apply {
                isChecked = image.selected
                setText("  ${image.partition}  ${formatBytes(image.sizeBytes)}${if (image.highRisk) "  [高风险]" else ""}")
                setTextColor(Ui.primaryText(activity))
                setOnCheckedChangeListener { _, checked -> image.selected = checked }
                checks.addView(this)
            }
        }
        val box = ScrollView(activity).apply {
            addView(checks)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(300, d).toInt())
        }
        val nextBtn = TextView(activity).apply {
            text = "下一步：进入 FastbootD"
            textSize = 14f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(solidButtonText())
            gravity = Gravity.CENTER
            background = Ui.neuSolidButton(softButtonColors(Ui.buttonPrimary(activity)).first, softButtonColors(Ui.buttonPrimary(activity)).second, 14f, activity)
            Ui.pressAnimation(this)
            setPadding(0, Ui.dp(12, d), 0, Ui.dp(12, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(12, d) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), 0, Ui.dp(20, d), Ui.dp(16, d))
            addView(TextView(activity).apply {
                text = "共发现 ${fullImages.size} 个 .img 文件（已解压到 APP 私有 files/ota 目录）"
                textSize = 12f
                setTextColor(Ui.secondaryText(activity))
                setPadding(0, 0, 0, Ui.dp(8, d))
            })
            addView(box)
            addView(nextBtn)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("选择 OTA 全量包分区（默认全选）")
            .setView(content)
            .setNegativeButton("取消") { _, _ ->
                OtgAssistantCore.deleteOtaDirectory(ctx)
                appendLog("已取消选择并清理 files/ota 目录\n")
            }
            .create()
        dialog.setOnCancelListener {
            OtgAssistantCore.deleteOtaDirectory(ctx)
            appendLog("已取消选择并清理 files/ota 目录\n")
        }
        nextBtn.setOnClickListener {
            val selected = fullImages.filter { it.selected }
            if (selected.isEmpty()) {
                toast("至少选择一个分区")
                return@setOnClickListener
            }
            dialog.dismiss()
            enterFastbootDThenConfirm(selected)
        }
        dialog.show()
    }

    private fun showFullFlashConfirmation(selected: List<OtgAssistantCore.ImageInfo>) {
        if (selected.isEmpty()) {
            toast("至少选择一个分区")
            return
        }
        val summary = selected.joinToString("\n") { "${it.partition}: ${it.name}" }
        val input = EditText(activity).apply {
            hint = confirmationPhrase
            setSingleLine(false)
            background = Ui.neuInset(activity, 12f)
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(24, d), Ui.dp(8, d), Ui.dp(24, d), Ui.dp(8, d))
            addView(TextView(activity).apply {
                text = "重要警告：必须确认固件包匹配当前设备型号、地区、版本与存储规格。" +
                    "刷写成功后将执行 fastboot -w，全部用户数据会被清除。\n\n待刷分区:\n$summary\n\n请输入确认短语："
                textSize = 12f
                setTextColor(Ui.primaryText(activity))
                setLineSpacing(Ui.dp(3, d).toFloat(), 1f)
            })
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("确认全量包刷写")
            .setView(box)
            .setNegativeButton("取消") { _, _ ->
                OtgAssistantCore.deleteOtaDirectory(ctx)
                appendLog("已取消刷写并清理 files/ota 目录\n")
            }
            .setPositiveButton("开始刷写", null)
            .create()
        dialog.setOnCancelListener {
            OtgAssistantCore.deleteOtaDirectory(ctx)
            appendLog("已取消刷写并清理 files/ota 目录\n")
        }
        dialog.setOnShowListener {
            dialog.getAlertDialogPositive()?.setOnClickListener {
                if (input.text.toString() == confirmationPhrase) {
                    dialog.dismiss()
                    Haptics.perform(activity.window.decorView)
                    flashFullPackage(selected)
                } else {
                    toast("确认短语不匹配")
                }
            }
        }
        dialog.show()
    }

    private fun flashFullPackage(images: List<OtgAssistantCore.ImageInfo>) {
        setOperationActive(true)
        Thread {
            var success = true
            try {
                for (image in images) {
                    postUi { appendLog("\n开始刷写 ${image.partition}: ${image.name}\n") }
                    val file = image.file ?: throw IllegalStateException("镜像文件不存在")
                    val result = runFastbootBlocking("fastboot flash ${image.partition} ${file.absolutePath}")
                    if (result.exitCode != 0) {
                        success = false
                        postUi { appendLog("分区 ${image.partition} 刷写失败，已停止后续操作\n") }
                        break
                    }
                }
                if (success) {
                    postUi { appendLog("全部分区刷写成功，开始执行 fastboot -w\n") }
                    val wipe = runFastbootBlocking("fastboot -w")
                    postUi { appendLog(if (wipe.exitCode == 0) "数据清除成功\n" else "数据清除失败，退出码 ${wipe.exitCode}\n") }
                }
            } catch (e: Exception) {
                postUi { appendLog("全量刷写失败: ${e.message}\n") }
            } finally {
                OtgAssistantCore.deleteOtaDirectory(ctx)
                postUi {
                    appendLog("已清理 files/ota 目录\n")
                    setOperationActive(false)
                }
            }
        }.start()
    }

    private fun enterFastbootDThenConfirm(images: List<OtgAssistantCore.ImageInfo>) {
        if (operationActive) return
        setOperationActive(true)
        Thread {
            val result = runFastbootBlocking("fastboot reboot fastboot")
            postUi {
                setOperationActive(false)
                if (result.exitCode == 0) {
                    appendLog("设备正在进入 FastbootD，请等待设备重新枚举\n")
                    handler.postDelayed({
                        if (alive) showFullFlashConfirmation(images)
                    }, 1500)
                } else {
                    appendLog("进入 FastbootD 失败，请检查设备连接和当前模式\n")
                }
            }
        }.start()
    }

    // ==================== ADB 推送 / 设备信息 ====================

    private fun chooseAdbPushFile() {
        if (operationActive) return
        adbPushLauncher()
    }

    private fun prepareAdbPush(file: java.io.File) {
        val name = displayName(file)
        val destination = EditText(activity).apply {
            setText("/sdcard/Download/$name")
            setSelectAllOnFocus(true)
            background = Ui.neuInset(activity, 12f)
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
        }
        AlertDialog.Builder(activity)
            .setTitle("ADB 推送文件")
            .setMessage("文件: $name\n请输入设备上的目标路径")
            .setView(destination)
            .setNegativeButton("取消", null)
            .setPositiveButton("推送") { _, _ -> adbPush(file, destination.text.toString().trim()) }
            .show()
    }

    private fun adbPush(sourceFile: java.io.File, destination: String) {
        if (destination.isEmpty()) {
            toast("请输入目标路径")
            return
        }
        setOperationActive(true)
        Thread {
            try {
                val name = displayName(sourceFile)
                val file = OtgAssistantCore.copyLocalFileToCache(ctx, sourceFile, name)
                postUi { appendLog("\n>adb push ${file.name} $destination\n") }
                val result = OtgAssistantCore.executeCommandDetailed(
                    OtgAssistantCore.getAdbPath(ctx),
                    "adb push ${file.absolutePath} $destination",
                ) { line -> postUi { appendLog("$line\n") } }
                file.delete()
                postUi { appendLog("ADB 推送${if (result.exitCode == 0) "成功" else "失败，退出码 ${result.exitCode}"}\n") }
            } catch (e: Exception) {
                postUi { appendLog("ADB 推送失败: ${e.message}\n") }
            } finally {
                postUi { setOperationActive(false) }
            }
        }.start()
    }

    private fun showAdbDeviceInfo() {
        if (operationActive) return
        setOperationActive(true)
        Thread {
            val commands = listOf(
                "adb get-state",
                "adb shell getprop ro.product.manufacturer",
                "adb shell getprop ro.product.model",
                "adb shell getprop ro.build.version.release",
            )
            val output = StringBuilder()
            commands.forEach { command ->
                val result = OtgAssistantCore.executeCommandDetailed(OtgAssistantCore.getAdbPath(ctx), command)
                synchronized(output) { output.append(command).append(": ").append(result.output.trim()).append('\n') }
            }
            postUi {
                appendLog("\nADB 设备信息:\n${output.toString().ifBlank { "未检测到 ADB 设备\n" }}")
                setOperationActive(false)
            }
        }.start()
    }

    // ==================== 高级重启 ====================

    private fun showRebootMenu() {
        val topItems = listOf(
            RebootMode("正常重启", "返回 Android 系统", "reboot", com.mcai.ubuntudsu.R.drawable.ic_reboot_normal, Ui.buttonSuccess(activity)),
            RebootMode("正常关机", "通过 ADB 关闭电源", "reboot poweroff", com.mcai.ubuntudsu.R.drawable.ic_reboot_power_off, Ui.buttonWarning(activity)),
        )
        val fullItems = listOf(
            RebootMode("热重启", "重启系统界面而不重新引导系统", "reboot", com.mcai.ubuntudsu.R.drawable.ic_reboot_hot, Ui.buttonSuccess(activity)),
            RebootMode("Recovery", "重启到 Recovery 模式（卡刷模式）", "reboot recovery", com.mcai.ubuntudsu.R.drawable.ic_reboot_recovery, Ui.buttonSecondary(activity)),
            RebootMode("Fastboot", "重启到 Fastboot 模式（USB 线刷模式）", "reboot bootloader", com.mcai.ubuntudsu.R.drawable.ic_reboot_fastboot, Ui.buttonSecondary(activity)),
            RebootMode("9008 (EDL)", "重启到 9008 模式（仅限骁龙设备）", "reboot edl", com.mcai.ubuntudsu.R.drawable.ic_reboot_9008, Ui.buttonSecondary(activity)),
        )

        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(14, d), Ui.dp(14, d), Ui.dp(6, d))
            background = Ui.glassSurface(activity, 20f)
        }
        Ui.applyNeuShadow(grid, 3f, 20f)

        // "选择操作" 标题套进玻璃边框内
        grid.addView(TextView(activity).apply {
            text = "选择操作"
            textSize = 18f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, Ui.dp(10, d))
        })

        fun makeCard(mode: RebootMode): View {
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                        if (Ui.isDark(activity)) android.graphics.Color.argb(60, 255, 255, 255) else android.graphics.Color.argb(50, 47, 124, 246)
                    ),
                    Ui.frostedSurface(activity, 18f) as android.graphics.drawable.Drawable,
                    null,
                )
                setPadding(Ui.dp(12, d), Ui.dp(12, d), Ui.dp(12, d), Ui.dp(12, d))
            }
            Ui.applyNeuShadow(cell, 1.5f, 18f)
            cell.addView(FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(40, d), Ui.dp(40, d)).apply {
                    marginEnd = Ui.dp(10, d)
                }
                addView(android.widget.ImageView(activity).apply {
                    setImageResource(mode.iconRes)
                    setImageTintList(android.content.res.ColorStateList.valueOf(mode.accent))
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                    layoutParams = FrameLayout.LayoutParams(Ui.dp(40, d), Ui.dp(40, d), Gravity.CENTER)
                })
                background = run {
                    val gd = android.graphics.drawable.GradientDrawable()
                    gd.shape = android.graphics.drawable.GradientDrawable.OVAL
                    gd.setColor(
                        if (Ui.isDark(activity)) Color.argb(150, 30, 28, 44) else Color.argb(140, 210, 220, 240)
                    )
                    gd
                }
            })
            cell.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = mode.title
                    textSize = 14f
                    setTypeface(Ui.typeface, Typeface.BOLD)
                    setTextColor(Ui.primaryText(activity))
                })
                addView(TextView(activity).apply {
                    text = mode.subtitle
                    textSize = 10.5f
                    setTextColor(Ui.secondaryText(activity))
                    setPadding(0, Ui.dp(2, d), 0, 0)
                })
            })
            cell.setOnClickListener {
                Haptics.perform(cell)
                executeRebootMode(mode)
            }
            Ui.pressAnimation(cell)
            return cell
        }

        val topRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        topItems.forEachIndexed { i, mode ->
            topRow.addView(makeCard(mode), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = Ui.dp(8, d)
            })
        }
        // 等高对齐：测量后统一两个卡片高度，保证左右视觉一致
        topRow.post {
            var maxH = 0
            for (i in 0 until topRow.childCount) {
                val c = topRow.getChildAt(i)
                if (c is View && c.height > maxH) maxH = c.height
            }
            for (i in 0 until topRow.childCount) {
                val c = topRow.getChildAt(i)
                if (c is View && c.layoutParams is LinearLayout.LayoutParams) {
                    (c.layoutParams as LinearLayout.LayoutParams).height = maxH
                    c.requestLayout()
                }
            }
        }
        grid.addView(topRow)

        fullItems.forEach { mode ->
            val card = makeCard(mode)
            grid.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(8, d)
            })
        }

        val cancelBtn = TextView(activity).apply {
            text = "取消"
            textSize = 13f
            setTypeface(Ui.typeface, Typeface.BOLD)
            setTextColor(Ui.secondaryText(activity))
            gravity = Gravity.CENTER
            background = Ui.glassButton(activity)
            setPadding(0, Ui.dp(11, d), 0, Ui.dp(11, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(10, d) }
            setOnClickListener {
                Haptics.perform(this)
                dialogRef?.dismiss()
            }
        }
        grid.addView(cancelBtn)

        val dialog = AlertDialog.Builder(activity)
            .setView(grid)
            .setCancelable(true)
            .create()
        dialogRef = dialog
        dialog.show()
    }

    private fun executeRebootMode(mode: RebootMode) {
        setOperationActive(true)
        Thread {
            val status = OtgAssistantCore.detectProtocolDevices(ctx)
            val adbConnected = status.adb != "未发现设备" && !status.adb.contains("等待USB授权")
            val fastbootConnected = status.fastboot != "未发现设备"
            when {
                adbConnected -> {
                    val tool = OtgAssistantCore.getAdbPath(ctx)
                    val target = mode.command.removePrefix("reboot ")
                    val full = if (target.isEmpty()) "adb reboot" else "adb $target"
                    val result = runToolBlocking(tool, full)
                    postUi {
                        appendLog("退出码: ${result.exitCode}\n")
                        if (result.exitCode == 0 && target.isNotEmpty()) {
                            appendLog("设备正在切换启动模式，等待重新枚举\n")
                            waitForUsbReenumeration()
                        }
                    }
                }
                fastbootConnected -> {
                    val tool = OtgAssistantCore.getFastbootPath(ctx)
                    val target = mode.command.removePrefix("reboot ")
                    val full = if (target.isEmpty()) "fastboot reboot" else "fastboot $target"
                    val result = runToolBlocking(tool, full)
                    postUi { appendLog("退出码: ${result.exitCode}\n") }
                }
                else -> postUi { appendLog("高级重启失败：未检测到可用的 ADB/Fastboot 设备\n") }
            }
            postUi { setOperationActive(false) }
        }.start()
    }

    private fun waitForUsbReenumeration() {
        repeat(8) {
            Thread.sleep(750)
            if (!alive) return
            val status = OtgAssistantCore.detectProtocolDevices(ctx)
            if (status.fastboot != "未发现设备") {
                postUi { appendLog("Fastboot 状态：${status.fastboot}\n") }
                return
            }
        }
        postUi {
            appendLog("切换模式后 Fastboot 未发现设备，请检查连接与 fastboot 输出\n")
            refreshDevices()
        }
    }

    // ==================== 底层命令执行 ====================

    private fun runFastbootBlocking(command: String): OtgAssistantCore.CommandResult {
        postUi { appendLog(">$command\n") }
        return OtgAssistantCore.executeCommandDetailed(OtgAssistantCore.getFastbootPath(ctx), command) { line ->
            postUi { appendLog("$line\n") }
        }.also { result ->
            postUi { appendLog("退出码: ${result.exitCode}\n") }
        }
    }

    private fun runToolBlocking(toolPath: String?, command: String): OtgAssistantCore.CommandResult {
        postUi { appendLog(">$command\n") }
        return OtgAssistantCore.executeCommandDetailed(toolPath, command) { line ->
            postUi { appendLog("$line\n") }
        }.also { result ->
            postUi { appendLog("退出码: ${result.exitCode}\n") }
        }
    }

    // ==================== 通用工具 ====================

    private fun setOperationActive(active: Boolean) {
        operationActive = active
        val enabled = !active
        listOf(btnExecute, btnPartitions, btnSingleFlash, btnFullFlash, btnReboot, btnAdbPush, btnAdbInfo, btnRefreshDevice).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun appendLog(text: String) {
        logView.append(text)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun displayName(file: java.io.File): String = file.name

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.2f MiB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.2f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun toast(msg: String) {
        postUi { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun postUi(action: () -> Unit) {
        if (!alive) return
        handler.post { if (alive) action() }
    }

    /** 获取 AlertDialog 的确认按钮（兼容平台/主题对话框） */
    private fun AlertDialog.getAlertDialogPositive(): android.widget.Button? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val field = javaClass.getDeclaredField("mButtonPositive").apply { isAccessible = true }
            @Suppress("DEPRECATION")
            return field.get(this) as? android.widget.Button
        }
        return getButton(AlertDialog.BUTTON_POSITIVE)
    }

    // ==================== 生命周期 ====================

    fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    fun destroy() {
        alive = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
    }
}
