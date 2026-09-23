package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Intent
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

/**
 * OTG 助手 - FastbootEnhance 安卓版
 * 
 * 功能：
 * 1. 设备连接检测（Fastboot/ADB/EDL）
 * 2. Fastboot 变量查看
 * 3. 分区表管理与刷写
 * 4. 逻辑分区操作（创建/删除/调整）
 * 5. Payload.bin 刷写
 * 6. 重启控制
 * 7. A/B 卡槽切换
 */
class OtgAssistantPage(
    private val activity: Activity,
    private val onDismiss: (() -> Unit)? = null,
) {
    private val d: Float get() = activity.resources.displayMetrics.density
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(true)

    // === 设备列表 ===
    private lateinit var deviceList: LinearLayout
    private var selectedSerial: String = ""
    private var isFastbootd: Boolean = false
    private var currentSlot: String = ""

    // === Fastboot 变量 ===
    private lateinit var varsListView: LinearLayout

    // === 分区表 ===
    private lateinit var partitionList: LinearLayout
    private lateinit var partitionFilter: EditText
    private var currentPartitions: List<OtgAssistant.Partition> = emptyList()
    private var selectedPartition: OtgAssistant.Partition? = null

    // === 进度条 ===
    private lateinit var progressBar: ProgressBar

    // === 日志 ===
    private lateinit var logView: TextView

    // === Tab ===
    private lateinit var tabContents: List<View>
    private lateinit var tabBtns: List<TextView>

    // === 文件选择回调 ===
    private var pendingFileAction: ((String) -> Unit)? = null

    fun onFilePicked(path: String) {
        pendingFileAction?.invoke(path)
        pendingFileAction = null
    }

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
            addView(backBtn { activity.onBackPressed() })
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

        // 设备选择区
        root.addView(buildDeviceSection())

        // Tab 栏
        val tabs = listOf("变量", "分区", "Payload")
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.neuCard(activity, 12f)
            setPadding(Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(8, d) }
        }
        tabBtns = tabs.map { label ->
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
        tabBtns[0].setTextColor(Ui.buttonPrimary(activity))
        tabBtns.forEach { tabRow.addView(it) }
        root.addView(tabRow)

        // Tab 内容
        tabContents = listOf(
            buildVarsTab(),
            buildPartitionTab(),
            buildPayloadTab().also { it.visibility = View.GONE },
        )
        tabContents.forEachIndexed { i, v ->
            v.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(8, d) }
            root.addView(v)
        }

        // 进度条
        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(4, d)
            ).apply { topMargin = Ui.dp(8, d) }
            visibility = View.GONE
        }
        root.addView(progressBar)

        // 操作按钮区
        root.addView(buildActionBar())

        // 日志
        root.addView(section("日志", "").apply {
            logView = Ui.logTextView(activity)
            addView(logView)
        })

        // 初始检测
        detectDevices()
        startAutoRefresh()
        return root
    }

    // ==================== 设备选择区 ====================

    private fun buildDeviceSection(): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(activity, 16f)
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
        }

        card.addView(TextView(activity).apply {
            text = "设备列表"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })

        deviceList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(100, d)
            )
        }
        card.addView(deviceList)

        card.addView(actionBtn("刷新设备", Ui.buttonPrimary(activity)) { detectDevices() })
        return card
    }

    private fun buildDeviceRow(serial: String, productName: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            setPadding(Ui.dp(8, d), Ui.dp(6, d), Ui.dp(8, d), Ui.dp(6, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(2, d) }
            setOnClickListener { selectDevice(serial) }

            addView(TextView(activity).apply {
                text = serial
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            addView(TextView(activity).apply {
                text = productName
                textSize = 10f
                setTextColor(Ui.secondaryText(activity))
            })

            addView(TextView(activity).apply {
                text = if (selectedSerial == serial) "✓" else "›"
                textSize = 16f
                setTextColor(if (selectedSerial == serial) Ui.buttonSuccess(activity) else Ui.secondaryText(activity))
            })
        }
    }

    private fun selectDevice(serial: String) {
        selectedSerial = serial
        refreshDeviceSelection()
        loadFastbootVars()
    }

    private fun refreshDeviceSelection() {
        deviceList.removeAllViews()
        deviceList.addView(TextView(activity).apply {
            text = "当前: $selectedSerial${if (isFastbootd) " (fastbootd)" else ""}"
            textSize = 11f
            setTextColor(Ui.buttonPrimary(activity))
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(4, d))
        })
    }

    // ==================== Tab 0: Fastboot 变量 ====================

    private fun buildVarsTab(): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(activity, 16f)
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
        }

        card.addView(TextView(activity).apply {
            text = "Fastboot 变量"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })

        varsListView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200, d)
            )
        }
        card.addView(varsListView)

        card.addView(actionBtn("刷新变量", Ui.buttonPrimary(activity)) { loadFastbootVars() })
        return card
    }

    private fun buildVarRow(name: String, value: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(4, d), 0, Ui.dp(4, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )

            addView(TextView(activity).apply {
                text = name
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.secondaryText(activity))
                layoutParams = LinearLayout.LayoutParams(
                    Ui.dp(120, d), ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })

            addView(TextView(activity).apply {
                text = value
                textSize = 12f
                setTextColor(Ui.primaryText(activity))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
        }
    }

    // ==================== Tab 1: 分区管理 ====================

    private fun buildPartitionTab(): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(activity, 16f)
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
        }

        card.addView(TextView(activity).apply {
            text = "分区表"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })

        // 搜索框
        partitionFilter = inputField("搜索分区...", "").apply {
            setPadding(Ui.dp(8, d), Ui.dp(6, d), Ui.dp(8, d), Ui.dp(6, d))
        }
        card.addView(partitionFilter)
        partitionFilter.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) filterPartitions()
            false
        }

        // 分区列表
        partitionList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(180, d)
            )
        }
        card.addView(partitionList)

        card.addView(actionBtn("刷新分区表", Ui.buttonPrimary(activity)) { loadPartitions() })
        return card
    }

    private fun buildPartitionRow(part: OtgAssistant.Partition, isSelected: Boolean): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (isSelected) Ui.glassButton(activity, Ui.buttonPrimary(activity))
                        else Ui.glassButton(activity, null)
            setPadding(Ui.dp(8, d), Ui.dp(4, d), Ui.dp(8, d), Ui.dp(4, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(1, d) }
            setOnClickListener { selectPartition(part) }

            addView(TextView(activity).apply {
                text = part.name
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isSelected) Ui.buttonText(activity) else Ui.primaryText(activity))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            addView(TextView(activity).apply {
                text = part.type
                textSize = 10f
                setTextColor(Ui.secondaryText(activity))
            })

            addView(TextView(activity).apply {
                text = Env.formatSize(part.sizeBytes)
                textSize = 10f
                setTextColor(Ui.secondaryText(activity))
            })
        }
    }

    private fun selectPartition(part: OtgAssistant.Partition) {
        selectedPartition = part
        refreshPartitionSelection()
        updatePartitionActions()
    }

    private fun refreshPartitionSelection() {
        partitionList.removeAllViews()
        val filtered = filterPartitionsList()
        filtered.forEach { part ->
            partitionList.addView(buildPartitionRow(part, part == selectedPartition))
        }
    }

    private fun filterPartitions() {
        refreshPartitionSelection()
    }

    private fun filterPartitionsList(): List<OtgAssistant.Partition> {
        val filter = partitionFilter.text.toString().trim().lowercase()
        return if (filter.isEmpty()) currentPartitions
        else currentPartitions.filter { it.name.lowercase().contains(filter) }
    }

    private fun updatePartitionActions() {
        // 在 action bar 中更新可用操作
    }

    // ==================== Tab 2: Payload ====================

    private fun buildPayloadTab(): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(activity, 16f)
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
        }

        card.addView(TextView(activity).apply {
            text = "Payload.bin 刷写"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })

        card.addView(hint("支持在 fastbootd 模式下刷写 Payload.bin 更新包"))
        card.addView(actionBtn("选择 Payload 文件", Ui.buttonSuccess(activity)) { pickPayload() })
        card.addView(actionBtn("刷写 Payload", Ui.buttonDanger(activity)) { flashPayload() })
        return card
    }

    private fun pickPayload() {
        pendingFileAction = { path ->
            appendLog("已选择: $path")
        }
        val intent = Intent(activity, RootfsFilesActivity::class.java).apply {
            putExtra(RootfsFilesActivity.EXTRA_PICK, true)
            putExtra(RootfsFilesActivity.EXTRA_TITLE, "选择 Payload 文件")
            putExtra(RootfsFilesActivity.EXTRA_EXT, ".bin")
        }
        try { activity.startActivity(intent) } catch (e: Exception) { appendLog("无法打开文件选择器: ${e.message}") }
    }

    private fun flashPayload() {
        appendLog("Payload 刷写功能待实现")
    }

    // ==================== 操作按钮区 ====================

    private fun buildActionBar(): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(activity, 16f)
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
        }

        // 第一行：重启相关
        card.addView(row4btn(
            actionBtn("重启系统", Ui.buttonSuccess(activity)) { rebootSystem() },
            actionBtn("重启 Bootloader", Ui.buttonPrimary(activity)) { rebootBootloader() },
            actionBtn("重启 Recovery", Ui.buttonWarning(activity)) { rebootRecovery() },
            actionBtn("切换卡槽", Ui.buttonSecondary(activity)) { switchSlot() },
        ))

        // 第二行：解锁相关
        card.addView(row2btn(
            actionBtn("解锁 Bootloader", Ui.buttonDanger(activity)) { unlockBootloader() },
            actionBtn("上锁 Bootloader", Ui.buttonDanger(activity)) { lockBootloader() },
        ))

        // 第三行：分区操作（默认禁用）
        card.addView(TextView(activity).apply {
            text = "分区操作"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(8, d) }
        })

        val partitionBtnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val eraseBtn = actionBtn("擦除分区", Ui.buttonDanger(activity)) { erasePartition() }
        val flashBtn = actionBtn("刷写分区", Ui.buttonSuccess(activity)) { flashPartition() }
        partitionBtnRow.addView(eraseBtn)
        partitionBtnRow.addView(flashBtn)
        card.addView(partitionBtnRow)

        return card
    }

    private fun row4btn(a: View, b: View, c: View, d: View): LinearLayout {
        val density = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, density) }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(2, density).toInt() })
            addView(d, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun row2btn(a: View, b: View): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(4, d) }
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(4, d) })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ==================== 检测与刷新 ====================

    private fun detectDevices() {
        executor.execute {
            val fbDevices = OtgAssistant.fastbootDevices(activity)
            activity.runOnUiThread {
                deviceList.removeAllViews()
                if (fbDevices.isEmpty()) {
                    deviceList.addView(TextView(activity).apply {
                        text = "未检测到 Fastboot 设备"
                        textSize = 12f
                        setTextColor(Ui.secondaryText(activity))
                        setPadding(0, Ui.dp(4, d), 0, Ui.dp(4, d))
                    })
                } else {
                    fbDevices.forEach { serial ->
                        deviceList.addView(buildDeviceRow(serial, ""))
                    }
                    if (selectedSerial.isEmpty()) {
                        selectedSerial = fbDevices[0]
                        refreshDeviceSelection()
                        loadFastbootVars()
                    }
                }
            }
        }
    }

    private fun loadFastbootVars() {
        if (selectedSerial.isEmpty()) return
        showProgress(true)
        executor.execute {
            val vars = OtgAssistant.fastbootGetvarAll(activity, selectedSerial)
            isFastbootd = vars.any { (k, _) -> k == "is-logical" || k == "has-slot" }
            vars.find { (k, _) -> k == "current-slot" }?.let { (_, v) -> currentSlot = v }
            activity.runOnUiThread {
                varsListView.removeAllViews()
                if (vars.isEmpty()) {
                    varsListView.addView(TextView(activity).apply {
                        text = "无变量"
                        textSize = 12f
                        setTextColor(Ui.secondaryText(activity))
                    })
                } else {
                    vars.forEach { (name, value) ->
                        varsListView.addView(buildVarRow(name, value))
                    }
                }
                refreshDeviceSelection()
                showProgress(false)
            }
        }
    }

    private fun loadPartitions() {
        if (selectedSerial.isEmpty()) return
        showProgress(true)
        executor.execute {
            val partitions = OtgAssistant.fastbootPartitions(activity, selectedSerial)
            activity.runOnUiThread {
                currentPartitions = partitions
                partitionList.removeAllViews()
                if (partitions.isEmpty()) {
                    partitionList.addView(TextView(activity).apply {
                        text = "无分区信息"
                        textSize = 12f
                        setTextColor(Ui.secondaryText(activity))
                    })
                } else {
                    filterPartitions()
                }
                showProgress(false)
            }
        }
    }

    private fun startAutoRefresh() {
        val self = this
        val refreshTask = object : Runnable {
            override fun run() {
                if (!running.get()) return
                executor.execute {
                    val fb = OtgAssistant.fastbootDevices(activity)
                    activity.runOnUiThread {
                        if (fb.isNotEmpty() && selectedSerial.isEmpty()) {
                            selectedSerial = fb[0]
                            refreshDeviceSelection()
                            loadFastbootVars()
                            loadPartitions()
                        } else if (fb.isEmpty() && selectedSerial.isNotEmpty()) {
                            selectedSerial = ""
                            refreshDeviceSelection()
                            varsListView.removeAllViews()
                            partitionList.removeAllViews()
                        }
                    }
                }
                if (running.get()) executor.execute { Thread.sleep(3000); self.executor.execute { this.run() } }
            }
        }
        executor.execute { refreshTask.run() }
    }

    // ==================== 操作命令 ====================

    private fun rebootSystem() {
        runFastbootCmd("reboot") {
            selectedSerial = ""
            refreshDeviceSelection()
        }
    }

    private fun rebootBootloader() {
        runFastbootCmd(if (isFastbootd) "reboot bootloader" else "reboot-bootloader")
    }

    private fun rebootRecovery() {
        runFastbootCmd("reboot recovery") {
            selectedSerial = ""
            refreshDeviceSelection()
        }
    }

    private fun switchSlot() {
        if (currentSlot.isEmpty()) {
            appendLog("当前设备不支持卡槽切换")
            return
        }
        val newSlot = if (currentSlot == "a") "b" else "a"
        runFastbootCmd("set_active $newSlot") {
            currentSlot = newSlot
            refreshDeviceSelection()
        }
    }

    private fun unlockBootloader() {
        AlertDialog.Builder(activity)
            .setTitle("解锁 Bootloader")
            .setMessage("解锁将清除全部数据，确定继续？")
            .setPositiveButton("解锁") { _, _ ->
                runFastbootCmd("flashing unlock")
            }
            .setNegativeButton("取消", null).show()
    }

    private fun lockBootloader() {
        AlertDialog.Builder(activity)
            .setTitle("上锁 Bootloader")
            .setMessage("上锁将清除数据，确定继续？")
            .setPositiveButton("上锁") { _, _ ->
                runFastbootCmd("flashing lock")
            }
            .setNegativeButton("取消", null).show()
    }

    private fun erasePartition() {
        val part = selectedPartition ?: return
        AlertDialog.Builder(activity)
            .setTitle("擦除分区")
            .setMessage("确定擦除 ${part.name}？")
            .setPositiveButton("擦除") { _, _ ->
                runFastbootCmd("erase ${part.name}")
            }
            .setNegativeButton("取消", null).show()
    }

    private fun flashPartition() {
        val part = selectedPartition ?: return
        pendingFileAction = { path ->
            runFastbootCmd("flash ${part.name} \"$path\"")
        }
        val intent = Intent(activity, RootfsFilesActivity::class.java).apply {
            putExtra(RootfsFilesActivity.EXTRA_PICK, true)
            putExtra(RootfsFilesActivity.EXTRA_TITLE, "选择镜像文件")
            putExtra(RootfsFilesActivity.EXTRA_EXT_ALL, true)
        }
        try { activity.startActivity(intent) } catch (e: Exception) { appendLog("无法打开文件选择器: ${e.message}") }
    }

    private fun runFastbootCmd(cmd: String, onComplete: (() -> Unit)? = null) {
        showProgress(true)
        executor.execute {
            val result = OtgAssistant.run(activity, "fastboot", listOf(cmd), timeoutMs = 120000)
            activity.runOnUiThread {
                showProgress(false)
                val output = (result.stdout + result.stderr).trim()
                appendLog("▶ $cmd")
                appendLog(output.ifBlank { "（退出码 ${result.code}）" })
                appendLog(if (result.success) "✓ 完成" else "✗ 失败(${result.code})")
                onComplete?.invoke()
                if (result.success) {
                    loadFastbootVars()
                    loadPartitions()
                }
            }
        }
    }

    // ==================== UI 辅助 ====================

    private fun selectTab(idx: Int) {
        tabContents.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { i, btn ->
            btn.setTextColor(if (i == idx) Ui.buttonPrimary(activity) else Ui.secondaryText(activity))
        }
        if (idx == 1 && currentPartitions.isEmpty()) loadPartitions()
    }

    private fun showProgress(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun appendLog(line: String) {
        if (::logView.isInitialized) logView.append("$line\n")
    }

    private fun backBtn(onClick: () -> Unit) = TextView(activity).apply {
        text = "‹ 返回"; textSize = 13f; setTextColor(Ui.buttonText(activity))
        background = Ui.glassButton(activity, Ui.buttonPrimary(activity))
        Ui.pressAnimation(this)
        setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
        setOnClickListener { onClick() }
    }

    private fun section(title: String, subtitle: String): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
            background = Ui.neuCard(activity, 16f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(8, d) }
        }
        card.addView(TextView(activity).apply {
            text = title; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        if (subtitle.isNotEmpty()) card.addView(TextView(activity).apply {
            text = subtitle; textSize = 10f; setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(1, d), 0, Ui.dp(6, d))
        })
        return card
    }

    private fun hint(text: String) = TextView(activity).apply {
        this.text = text; textSize = 11f; setTextColor(Ui.secondaryText(activity))
        setPadding(0, Ui.dp(2, d), 0, Ui.dp(4, d))
    }

    private fun inputField(hint: String, initial: String): EditText = EditText(activity).apply {
        this.hint = hint; setText(initial)
        textSize = 13f; setTextColor(Ui.primaryText(activity))
        setHintTextColor(Ui.secondaryText(activity))
        background = Ui.neuInset(activity, 10f)
        setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(4, d) }
    }

    private fun actionBtn(label: String, accent: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, accent)
            Ui.pressAnimation(this)
            setPadding(Ui.dp(10, d), Ui.dp(8, d), Ui.dp(10, d), Ui.dp(8, d))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(4, d) }
        }
}
