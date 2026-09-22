package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
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

    // --- Tab 0: 连接状态 ---
    private lateinit var serialInput: EditText
    private lateinit var fastbootStatus: TextView
    private lateinit var adbStatus: TextView
    private lateinit var edlStatus: TextView
    private lateinit var usbStatus: TextView
    private lateinit var lockStatus: TextView
    private lateinit var slotStatus: TextView
    private lateinit var blUnlockBtns: List<TextView>
    private lateinit var blLockSection: LinearLayout

    // --- Tab 1: Fastboot 分区 ---
    private lateinit var partitionBox: LinearLayout
    private lateinit var flashPartitionInput: EditText
    private lateinit var flashImageInput: EditText
    private var selectedPartition: String = ""
    private var currentPartitions: List<OtgAssistant.Partition> = emptyList()
    private var fastbootSerial: String = ""
    private var autoRefreshHandle: Runnable? = null

    // --- Tab 2: ADB ---
    private lateinit var adbHostInput: EditText
    private lateinit var adbPortInput: EditText
    private lateinit var adbDeviceBox: LinearLayout
    private lateinit var pushLocalInput: EditText
    private lateinit var pushRemoteInput: EditText
    private lateinit var pullRemoteInput: EditText
    private lateinit var pullLocalInput: EditText

    // --- Tab 3: OTG 重启 ---
    private lateinit var rebootStatus: TextView

    private lateinit var logView: TextView
    private lateinit var tabContents: List<View>
    private lateinit var tabBtns: List<TextView>

    // 文件选择回调
    private var pendingImageSetter: ((String) -> Unit)? = null

    /** 设置镜像路径选择后的回调（由 OtgAssistantActivity 调用 launcher 后设置）。 */
    fun setImagePicker(setter: (String) -> Unit) { pendingImageSetter = setter }

    // ==================== 入口 ====================

    fun build(): View {
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

        // Tab 栏
        val tabs = listOf("连接", "Fastboot", "ADB", "重启")
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.neuCard(activity, 12f)
            setPadding(Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d), Ui.dp(4, d))
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
            buildConnectionTab(),
            buildFastbootTab().also { it.visibility = View.GONE },
            buildAdbTab().also { it.visibility = View.GONE },
            buildRebootTab().also { it.visibility = View.GONE },
        ).map { v ->
            v.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            v
        }
        tabContents.forEach { root.addView(it) }

        // 日志条
        root.addView(section("日志", "").apply {
            logView = Ui.logTextView(activity)
            addView(logView)
        })

        detectAll()
        startAutoRefresh()
        return root
    }

    private fun selectTab(idx: Int) {
        tabContents.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { i, btn ->
            btn.setTextColor(if (i == idx) Ui.buttonPrimary(activity) else Ui.secondaryText(activity))
        }
        if (idx == 1 && currentPartitions.isEmpty()) loadPartitions()
    }

    // ==================== Tab 0: 连接 / BL / 卡槽 ====================

    private fun buildConnectionTab(): LinearLayout {
        val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        card.addView(section("连接状态", "检测 OTG 外接目标设备"))
        card.addView(hint("通过 OTG 连接目标设备后点击检测；操作均针对外接设备。"))
        serialInput = inputField("目标序列号（可选）", "")
        card.addView(serialInput)

        fastbootStatus = statusRow(card, "Fastboot")
        adbStatus = statusRow(card, "ADB")
        edlStatus = statusRow(card, "9008/EDL")
        usbStatus = statusRow(card, "USB")
        card.addView(actionBtn("检测连接", Ui.buttonPrimary(activity)) { detectAll() })

        // BL 锁
        card.addView(section("Bootloader 锁", "解锁 / 上锁引导程序"))
        lockStatus = statusRow(card, "锁状态")
        card.addView(actionBtn("读取锁状态", Ui.buttonPrimary(activity)) { refreshLockState() })
        blLockSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(blLockSection)
        updateLockButtons(false)

        // 卡槽
        card.addView(section("系统卡槽", "查看与切换 A/B 卡槽"))
        slotStatus = statusRow(card, "当前卡槽")
        card.addView(row2btn(
            actionBtn("读取卡槽", Ui.buttonPrimary(activity)) { refreshSlot() },
            actionBtn("切换卡槽", Ui.buttonWarning(activity)) { showSlotPicker() },
        ))
        return card
    }

    // ==================== Tab 1: Fastboot 分区 ====================

    private fun buildFastbootTab(): LinearLayout {
        val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(section("Fastboot 分区", "自动读取分区表并刷写"))
        val refreshHint = autoRefreshHint(card)
        card.addView(refreshHint)

        partitionBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(partitionBox)
        card.addView(actionBtn("刷新分区表", Ui.buttonPrimary(activity)) { loadPartitions() })

        flashPartitionInput = inputField("分区名（点击行选取）", "").apply {
            setOnClickListener { showPartitionPicker() }
            isFocusable = false
            isClickable = true
        }
        card.addView(flashPartitionInput)

        flashImageInput = inputField("镜像路径（如 /sdcard/boot.img）", "")
        card.addView(flashImageInput)
        card.addView(row2btn(
            actionBtn("选择镜像文件", Ui.buttonSuccess(activity)) { pickImage() },
            actionBtn("刷写分区", Ui.buttonDanger(activity)) { confirmFlash() },
        ))
        return card
    }

    private fun autoRefreshHint(parent: LinearLayout): TextView {
        val tv = TextView(activity).apply {
            text = "自动刷新：开"
            textSize = 11f
            setTextColor(Ui.buttonSuccess(activity))
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(4, d))
            setOnClickListener {
                if (autoRefreshHandle != null) {
                    autoRefreshHandle = null
                    text = "自动刷新：关"
                    setTextColor(Ui.buttonWarning(activity))
                    appendLog("自动刷新已关闭")
                } else { startAutoRefresh(); text = "自动刷新：开"; setTextColor(Ui.buttonSuccess(activity)); appendLog("自动刷新已开启") }
            }
        }
        return tv
    }

    // ==================== Tab 2: ADB ====================

    private fun buildAdbTab(): LinearLayout {
        val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(section("ADB 传输", "无线调试 + OTG 设备文件传输"))

        val hostRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        adbHostInput = weightedField("主机地址", "192.168.x.x", 1f)
        adbPortInput = weightedField("端口", "5555", 0.4f)
        hostRow.addView(adbHostInput)
        hostRow.addView(adbPortInput)
        card.addView(hostRow)
        card.addView(row2btn(
            actionBtn("连接", Ui.buttonSuccess(activity)) { adbConnect() },
            actionBtn("断开", Ui.buttonDanger(activity)) { adbDisconnect() },
        ))

        adbDeviceBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(adbDeviceBox)
        card.addView(actionBtn("刷新设备", Ui.buttonPrimary(activity)) { refreshAdbDevices() })

        card.addView(hint("—— 推送（本机 → 目标设备）——"))
        pushLocalInput = inputField("本机文件路径", "")
        pushRemoteInput = inputField("目标设备路径", "")
        card.addView(pushLocalInput)
        card.addView(pushRemoteInput)
        card.addView(row2btn(
            actionBtn("选择文件", Ui.buttonSuccess(activity)) { pickPushFile() },
            actionBtn("推送", Ui.buttonPrimary(activity)) { adbPush() },
        ))

        card.addView(hint("—— 拉取（目标设备 → 本机）——"))
        pullRemoteInput = inputField("设备文件路径", "")
        pullLocalInput = inputField("保存目录", "")
        card.addView(pullRemoteInput)
        card.addView(pullLocalInput)
        card.addView(row2btn(
            actionBtn("选择目录", Ui.buttonWarning(activity)) { pickPullFolder() },
            actionBtn("拉取", Ui.buttonPrimary(activity)) { adbPull() },
        ))
        return card
    }

    // ==================== Tab 3: OTG 重启 ====================

    private fun buildRebootTab(): LinearLayout {
        val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(section("OTG 高级重启", "重启外接目标设备"))
        rebootStatus = statusRow(card, "状态")
        card.addView(rebootGrid())
        return card
    }

    private fun rebootGrid(): LinearLayout {
        val items = listOf(
            Triple("重启系统", "system", Ui.buttonSuccess(activity)),
            Triple("重启 Recovery", "recovery", Ui.buttonWarning(activity)),
            Triple("重启 Fastboot", "bootloader", Ui.buttonPrimary(activity)),
            Triple("重启 Fastbootd", "fastbootd", Ui.buttonPrimary(activity)),
            Triple("重启 Download", "download", Ui.buttonPrimary(activity)),
            Triple("关机", "poweroff", Ui.buttonDanger(activity)),
        )
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(2).forEach { row ->
            val hr = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, (label, target, accent) ->
                hr.addView(actionBtn(label, accent) { confirmReboot(label, target) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i == 0) marginEnd = Ui.dp(6, d)
                    })
            }
            box.addView(hr)
        }
        return box
    }

    // ==================== 检测 ====================

    private fun detectAll() {
        setStatus(fastbootStatus, "检测中...", null)
        setStatus(adbStatus, "检测中...", null)
        setStatus(edlStatus, "检测中...", null)
        setStatus(usbStatus, "检测中...", null)
        val serial = serial()
        executor.execute {
            val hasFb = OtgAssistant.tool(activity, "fastboot").available
            val hasAdb = OtgAssistant.tool(activity, "adb").available
            val fb = if (hasFb) OtgAssistant.fastbootDevices(activity) else emptyList()
            val adb = if (hasAdb) OtgAssistant.adbDevices(activity) else emptyList()
            val edl = OtgAssistant.edlDevices(activity)
            val usb = OtgAssistant.usbDevices(activity)
            activity.runOnUiThread {
                setStatus(fastbootStatus, connText(hasFb, fb), hasFb && fb.isNotEmpty())
                setStatus(adbStatus, connText(hasAdb, adb), hasAdb && adb.isNotEmpty())
                setStatus(edlStatus, if (edl.isEmpty()) "未检测到" else "已检测：${edl.joinToString("；")}", edl.isNotEmpty())
                setStatus(usbStatus, if (usb.isEmpty()) "无 USB 设备" else "${usb.size} 个设备", usb.isNotEmpty())
                appendLog("检测: fb=${fb.size} adb=${adb.size} EDL=${edl.size} USB=${usb.size}")
                if (serial.isNotBlank()) appendLog("序列号: $serial")
                if (fb.isNotEmpty()) fastbootSerial = fb[0]
                updateLockButtons(fb.isNotEmpty())
                if (fb.isNotEmpty() && tabContents.getOrNull(1)?.visibility != View.GONE) loadPartitions()
            }
        }
    }

    private fun connText(available: Boolean, devices: List<String>): String =
        when { !available -> "未安装工具"; devices.isEmpty() -> "未连接"; else -> "已连接：${devices.joinToString(", ")}" }

    private fun updateLockButtons(hasFastboot: Boolean) {
        blLockSection.removeAllViews()
        if (!hasFastboot) return
        blUnlockBtns = listOf(
            actionBtn("解锁 Bootloader", Ui.buttonDanger(activity)) { confirmUnlock() },
            actionBtn("上锁 Bootloader", Ui.buttonDanger(activity)) { confirmLock() },
        )
        blUnlockBtns.forEach { blLockSection.addView(it) }
    }

    // ==================== BL 锁 ====================

    private fun refreshLockState() {
        if (fastbootSerial.isBlank()) { appendLog("请先连接 fastboot 设备"); return }
        setStatus(lockStatus, "读取中...", null)
        executor.execute {
            val state = OtgAssistant.fastbootGetvar(activity, fastbootSerial, "unlocked")
            activity.runOnUiThread { setStatus(lockStatus, lockText(state), state != null) }
        }
    }

    private fun lockText(s: String?) = when (s?.lowercase()) { "yes" -> "已解锁"; "no" -> "已上锁"; null -> "未知（需 fastboot 模式）"; else -> s }

    private fun confirmUnlock() {
        if (fastbootSerial.isBlank()) { appendLog("请先连接 fastboot 设备"); return }
        val serial = fastbootSerial
        AlertDialog.Builder(activity)
            .setTitle("解锁 Bootloader")
            .setMessage("解锁将清除目标设备全部数据，请选择解锁命令：")
            .setItems(UNLOCK_CMDS) { _, which ->
                runTool("解锁 [$which]") { OtgAssistant.fastbootUnlockCmd(activity, serial, which) }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun confirmLock() {
        if (fastbootSerial.isBlank()) { appendLog("请先连接 fastboot 设备"); return }
        val serial = fastbootSerial
        AlertDialog.Builder(activity)
            .setTitle("上锁 Bootloader")
            .setMessage("上锁将清除数据，非官方系统可能无法启动。")
            .setItems(LOCK_CMDS) { _, which ->
                runTool("上锁 [$which]") { OtgAssistant.fastbootLockCmd(activity, serial, which) }
            }
            .setNegativeButton("取消", null).show()
    }

    companion object {
        private val UNLOCK_CMDS = arrayOf(
            "fastboot oem unlock",
            "fastboot oem unlock-go",
            "fastboot flashing unlock",
            "fastboot flashing unlock_critical",
            "fastboot bbk unlock  (vivo)",
        )
        private val LOCK_CMDS = arrayOf(
            "fastboot flashing lock",
            "fastboot oem lock",
        )
    }

    // ==================== 卡槽 ====================

    private fun refreshSlot() {
        setStatus(slotStatus, "读取中...", null)
        executor.execute {
            val slot = OtgAssistant.fastbootGetvar(activity, fastbootSerial, "current-slot")
            activity.runOnUiThread { setStatus(slotStatus, slot ?: "未知", !slot.isNullOrBlank()) }
        }
    }

    private fun showSlotPicker() {
        if (fastbootSerial.isBlank()) { appendLog("请先连接 fastboot 设备"); return }
        AlertDialog.Builder(activity)
            .setTitle("切换卡槽")
            .setItems(arrayOf("卡槽 A", "卡槽 B")) { _, idx ->
                confirmSetSlot(if (idx == 0) "a" else "b")
            }
            .setNegativeButton("取消", null).show()
    }

    private fun confirmSetSlot(slot: String) {
        AlertDialog.Builder(activity)
            .setTitle("切换卡槽")
            .setMessage("确定将目标设备的活动卡槽切换为 $slot ？")
            .setPositiveButton("切换") { _, _ -> runTool("切换→$slot") { OtgAssistant.fastbootSetActive(activity, fastbootSerial, slot) } }
            .setNegativeButton("取消", null).show()
    }

    // ==================== Fastboot 分区 ====================

    private fun loadPartitions() {
        partitionBox.removeAllViews()
        partitionBox.addView(hint("读取中..."))
        executor.execute {
            val parts = OtgAssistant.fastbootPartitions(activity, fastbootSerial)
            activity.runOnUiThread {
                currentPartitions = parts
                partitionBox.removeAllViews()
                if (parts.isEmpty()) {
                    partitionBox.addView(hint("未读取到分区（请确认 fastboot 已连接）"))
                } else {
                    partitionBox.addView(hint("共 ${parts.size} 个分区，点击选取"))
                    parts.forEach { part ->
                        partitionBox.addView(listRow(part.name, "${part.type}${if (part.sizeBytes > 0) " · ${Env.formatSize(part.sizeBytes)}" else ""}").apply {
                            setPadding(0, Ui.dp(2, d), 0, Ui.dp(2, d))
                            setOnClickListener {
                                selectedPartition = part.name
                                flashPartitionInput.setText(part.name)
                            }
                        })
                    }
                }
            }
        }
    }

    private fun showPartitionPicker() {
        if (currentPartitions.isEmpty()) { appendLog("请先刷新分区表"); return }
        val names = currentPartitions.map { it.name }.toTypedArray()
        val init = names.indexOf(selectedPartition).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("选择分区")
            .setSingleChoiceItems(names, init) { _, idx -> selectedPartition = names[idx] }
            .setPositiveButton("确定") { _, _ -> flashPartitionInput.setText(selectedPartition) }
            .setNegativeButton("取消", null).show()
    }

    private fun confirmFlash() {
        val p = flashPartitionInput.text.toString().trim()
        val img = flashImageInput.text.toString().trim()
        if (p.isBlank()) { appendLog("请填写分区名"); return }
        if (img.isBlank()) { appendLog("请填写镜像路径或点「选择镜像文件」"); return }
        AlertDialog.Builder(activity)
            .setTitle("刷写分区")
            .setMessage("将 $img 刷写到分区 $p ？刷写错误可能导致无法启动。")
            .setPositiveButton("刷写") { _, _ -> runTool("刷写 $p") { OtgAssistant.fastbootFlash(activity, fastbootSerial, p, img) } }
            .setNegativeButton("取消", null).show()
    }

    // ==================== ADB ====================

    private fun adbTarget(): String {
        val host = adbHostInput.text.toString().trim()
        if (host.isBlank()) return ""
        val port = adbPortInput.text.toString().trim().ifBlank { "5555" }
        return if (host.contains(":")) host else "$host:$port"
    }

    private fun adbSerial(): String {
        val t = adbTarget()
        return if (t.isNotBlank()) t else fastbootSerial.ifEmpty { serial() }
    }

    private fun adbConnect() {
        val t = adbTarget()
        if (t.isBlank()) { appendLog("请填写主机地址"); return }
        runTool("连接 $t") { OtgAssistant.adbConnect(activity, t) }
    }

    private fun adbDisconnect() {
        val t = adbTarget()
        runTool("断开 ${t.ifBlank { "全部" }}") { OtgAssistant.adbDisconnect(activity, t) }
    }

    private fun refreshAdbDevices() {
        adbDeviceBox.removeAllViews()
        adbDeviceBox.addView(hint("读取中..."))
        executor.execute {
            val devs = OtgAssistant.adbDevices(activity)
            activity.runOnUiThread {
                adbDeviceBox.removeAllViews()
                if (devs.isEmpty()) adbDeviceBox.addView(hint("未连接 ADB 设备"))
                else devs.forEach { adbDeviceBox.addView(listRow("设备", it)) }
            }
        }
    }

    private fun adbPush() {
        val local = pushLocalInput.text.toString().trim()
        val remote = pushRemoteInput.text.toString().trim()
        if (local.isBlank() || remote.isBlank()) { appendLog("请填写双路径"); return }
        runTool("推送 $local → $remote") { OtgAssistant.adbPush(activity, adbSerial(), local, remote) }
    }

    private fun adbPull() {
        val remote = pullRemoteInput.text.toString().trim()
        val local = pullLocalInput.text.toString().trim()
        if (remote.isBlank() || local.isBlank()) { appendLog("请填写双路径"); return }
        runTool("拉取 $remote → $local") { OtgAssistant.adbPull(activity, adbSerial(), remote, local) }
    }

    // ==================== 重启 ====================

    private fun confirmReboot(label: String, target: String) {
        val serial = fastbootSerial.ifEmpty { serial() }
        AlertDialog.Builder(activity)
            .setTitle("重启目标设备")
            .setMessage("确定 $label ？")
            .setPositiveButton("执行") { _, _ -> runTool(label) { OtgAssistant.fastbootReboot(activity, serial, target) } }
            .setNegativeButton("取消", null).show()
    }

    // ==================== 自动刷新 ====================

    private fun startAutoRefresh() {
        val self = this
        autoRefreshHandle = object : Runnable {
            override fun run() {
                if (!running.get()) return
                executor.execute {
                    val fb = OtgAssistant.fastbootDevices(activity)
                    if (fb.isNotEmpty() && currentPartitions.isEmpty()) {
                        activity.runOnUiThread { if (tabContents.getOrNull(1)?.visibility != View.GONE) self.loadPartitions() }
                    }
                }
                if (running.get()) executor.execute { Thread.sleep(5000); self.executor.execute { self.autoRefreshHandle?.run() } }
            }
        }
        executor.execute { autoRefreshHandle?.run() }
    }

    // ==================== 文件选择 ====================

    private fun pickImage() {
        pendingImageSetter = { path -> flashImageInput.setText(path) }
        launchRootfsPicker("选择镜像文件", null)
    }

    private fun pickPushFile() {
        pendingImageSetter = { path -> pushLocalInput.setText(path) }
        launchRootfsPicker("选择本地文件", null)
    }

    private fun pickPullFolder() {
        appendLog("请在上方「保存目录」输入框中手动填写路径")
    }

    private fun launchRootfsPicker(title: String, ext: String?) {
        val intent = Intent(activity, RootfsFilesActivity::class.java).apply {
            putExtra(RootfsFilesActivity.EXTRA_PICK, true)
            putExtra(RootfsFilesActivity.EXTRA_TITLE, title)
            if (ext != null) putExtra(RootfsFilesActivity.EXTRA_EXT, ext)
            else putExtra(RootfsFilesActivity.EXTRA_EXT_ALL, true)
        }
        try { activity.startActivity(intent) } catch (e: Exception) { appendLog("无法打开文件选择器: ${e.message}") }
    }

    /** 供 OtgAssistantActivity 回调文件选择结果。 */
    fun onFilePicked(path: String) {
        pendingImageSetter?.invoke(path)
        pendingImageSetter = null
    }

    // ==================== 执行封装 ====================

    private fun runTool(label: String, block: () -> ShellResult) {
        appendLog("▶ $label")
        executor.execute {
            val r = block()
            val out = (r.stdout + r.stderr).trim()
            activity.runOnUiThread {
                appendLog(out.ifBlank { "（退出码 ${r.code}）" })
                appendLog(if (r.success) "✓ $label 完成" else "✗ $label 失败(${r.code})")
            }
        }
    }

    private fun appendLog(line: String) {
        if (::logView.isInitialized) logView.append("$line\n")
    }

    private fun serial() = serialInput.text.toString().trim()

    // ==================== UI 构建辅助 ====================

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

    private fun statusRow(parent: LinearLayout, label: String): TextView {
        val value = TextView(activity).apply {
            text = "—"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(2, d))
            addView(TextView(activity).apply {
                text = label; textSize = 12f; setTextColor(Ui.secondaryText(activity))
                layoutParams = LinearLayout.LayoutParams(Ui.dp(80, d), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(value)
        })
        return value
    }

    private fun setStatus(v: TextView, text: String, ok: Boolean?) {
        v.text = text
        v.setTextColor(when (ok) { true -> Ui.buttonSuccess(activity); false -> Ui.buttonWarning(activity); null -> Ui.secondaryText(activity) })
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

    private fun weightedField(hint: String, initial: String, weight: Float): EditText =
        inputField(hint, initial).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                topMargin = Ui.dp(4, d); marginEnd = Ui.dp(4, d)
            }
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

    private fun row2btn(left: View, right: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(4, d) })
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun listRow(label: String, value: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, Ui.dp(2, d), 0, Ui.dp(2, d))
        addView(TextView(activity).apply {
            text = label; textSize = 12f; setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(90, d), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(TextView(activity).apply {
            text = value; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }
}
