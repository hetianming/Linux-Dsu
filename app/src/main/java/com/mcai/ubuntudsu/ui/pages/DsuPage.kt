package com.mcai.ubuntudsu.ui.pages

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.IPrivilegedService
import com.mcai.ubuntudsu.PrivilegedRootService
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.core.DsuInstallState
import com.mcai.ubuntudsu.core.DsuManager
import com.mcai.ubuntudsu.service.DsuInstallService
import com.mcai.ubuntudsu.ui.Ui
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.Executor
import java.util.zip.ZipInputStream
import android.os.Environment
import android.os.StatFs

class DsuPage(
    private val activity: AppCompatActivity,
    private val executor: Executor,
    private val pickZipLauncher: ActivityResultLauncher<android.content.Intent>,
) : DsuInstallState.Listener {
    private var selectedUserdataGB = DsuManager.userdataOptions.first()
    private var clearUserdata = false
    private var selectedZip: Uri? = null
    private var selectedZipName: String? = null
    private var privilegedService: IPrivilegedService? = null
    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            privilegedService = IPrivilegedService.Stub.asInterface(service)
            log("ROOT DSU 安装器已连接")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            privilegedService = null
            log("ROOT DSU 安装器连接断开")
        }
    }
    private lateinit var fileNameText: TextView
    private lateinit var installProgressCard: LinearLayout
    private lateinit var installProgressLabel: TextView
    private lateinit var installPercentText: TextView
    private lateinit var customCapacityInput: EditText

    private fun availableGB(): Long = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        (stat.availableBytes / 1024 / 1024 / 1024)
    }.getOrDefault(0L)

    /** 参考 DSU-Sideloader：Android 限制 userdata 最多占用 40% 剩余空间，超过则警告 */
    private fun userdataWarning(GB: Int): String {
        val availGB = availableGB()
        if (availGB <= 0) return ""
        val maxSafeGB = (availGB * 40 / 100).toInt()
        return when {
            GB > maxSafeGB * 2 -> "警告：${GB} GB 远超设备剩余空间（${availGB} GB），安装极可能失败，建议改用 ${maxSafeGB.coerceAtLeast(8)} GB"
            GB > maxSafeGB -> "注意：${GB} GB 超过设备剩余安全容量（约 ${maxSafeGB} GB），安装可能失败"
            else -> ""
        }
    }

    /** 动态上限：剩余空间的 40%（Android 官方限制），下限 8 GB，不设硬顶（覆盖 1TB 设备） */
    private fun maxAllowedGB(): Int = ((availableGB() * 40 / 100).toInt()).coerceAtLeast(8)

    fun onZipPicked(uri: Uri?) {
        uri?.let {
            selectedZip = it
            selectedZipName = it.path?.substringAfterLast('/') ?: "gsi.zip"
            // 持久化选中路径：选择器期间进程被杀重建后仍能恢复
            activity.getPreferences(android.app.Activity.MODE_PRIVATE).edit()
                .putString("dsu_selected_zip", it.path).apply()
            if (::fileNameText.isInitialized) fileNameText.text = selectedZipName
            log("已选择 GSI 包: $selectedZipName")
            Toast.makeText(activity, "已选择 $selectedZipName", Toast.LENGTH_SHORT).show()
        }
    }

    // 进程重建后恢复上次选中的 GSI 包路径
    private fun restoreSelectedZip() {
        val path = activity.getPreferences(android.app.Activity.MODE_PRIVATE).getString("dsu_selected_zip", null)
        if (path != null && java.io.File(path).isFile && selectedZip == null) {
            selectedZip = Uri.fromFile(java.io.File(path))
            selectedZipName = path.substringAfterLast('/')
        }
    }

    fun bindRootService() {
        RootService.bind(Intent(activity, PrivilegedRootService::class.java), rootConnection)
    }

    fun unbindRootService() {
        runCatching { RootService.unbind(rootConnection) }
    }

    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }
        // 标题：左侧"DSU 管理"（设置入口仅在首页）
        page.addView(TextView(activity).apply {
            text = "DSU 管理"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, 0, 0, Ui.dp(14, d))
        })

        // 恢复上次选中的 GSI 包（进程重建场景，fileNameText 创建时同步显示）
        restoreSelectedZip()

        // 安装进度卡（紧凑单行：百分比在进度条右侧同行 + 细进度条，直接作为安装进度条卡片使用）
        val progressCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            background = Ui.glassSurface(activity, 18f)
            Ui.applyNeuShadow(this, 3f, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(8, d) }
        }
        installProgressCard = progressCard
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        installProgressLabel = label("", 12f).apply {
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        installPercentText = Ui.percentTextView(activity).apply {
            gravity = Gravity.END
        }
        statusRow.addView(installProgressLabel)
        statusRow.addView(installPercentText)
        progressCard.addView(statusRow)
        page.addView(progressCard)

        // 卡片一：userdata 容量
        val userdataCard = card(d)
        userdataCard.addView(label("userdata 容量", 13f, bold = true))
        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        DsuManager.userdataOptions.forEachIndexed { index, size ->
            val chip = TextView(activity).apply {
                text = "$size GB"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) Color.WHITE else Ui.primaryText(activity))
                background = if (index == 0) Ui.chipSelected(activity) else Ui.glassButton(activity)
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(30, d), 1f).apply {
                    marginEnd = if (index == DsuManager.userdataOptions.lastIndex) 0 else Ui.dp(5, d)
                }
                setOnClickListener {
                    selectedUserdataGB = size
                    selectSizeChip(sizeRow, size)
                    // 点选预设时清空自定义输入，保证「唯一生效值」清晰
                    customCapacityInput.setText("")
                    val w = userdataWarning(size)
                    if (w.isNotEmpty()) {
                        Toast.makeText(activity, w, Toast.LENGTH_LONG).show()
                    }
                }
            }
            sizeRow.addView(chip)
        }
        userdataCard.addView(sizeRow)
        // 剩余空间提示（参考 DSU-Sideloader 的 40% 安全限制说明）
        userdataCard.addView(label(
            "设备剩余空间：${availableGB()} GB · 建议上限：${maxAllowedGB()} GB（剩余空间的 40%）",
            11f,
        ).apply {
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        })
        // 自定义容量：输入框 + 确定按钮二合一（免弹框，直接输入 GB 数）
        val customRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(5, d), 0, 0)
        }
        customCapacityInput = EditText(activity).apply {
            hint = "自定义容量（GB）"
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
            setHintTextColor(Ui.secondaryText(activity))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            background = Ui.glassButton(activity)
            setPadding(Ui.dp(10, d), 0, Ui.dp(10, d), 0)
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(30, d), 1f)
        }
        customRow.addView(customCapacityInput)
        customRow.addView(
            smallAction("确定容量", Ui.buttonPrimary(activity), minWidthDp = 88, solid = true) {
                val value = customCapacityInput.text.toString().toIntOrNull()
                when {
                    value == null || value <= 0 -> {
                        Toast.makeText(activity, "请输入有效容量", Toast.LENGTH_SHORT).show()
                    }
                    value > maxAllowedGB() -> {
                        Toast.makeText(activity, "容量 ${value} GB 超过建议上限 ${maxAllowedGB()} GB（剩余空间 ${availableGB()} GB 的 40%），安装可能失败", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        selectedUserdataGB = value
                        selectSizeChip(sizeRow, value)
                        val w = userdataWarning(value)
                        Toast.makeText(activity, if (w.isNotEmpty()) "已设定 ${value} GB · $w" else "已设定 userdata 容量：${value} GB", Toast.LENGTH_LONG).show()
                    }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(8, d)
            },
        )
        userdataCard.addView(customRow)

        page.addView(userdataCard)

        // 卡片二：GSI 安装包（zip）
        val gsiCard = card(d)
        gsiCard.addView(label("GSI 安装包（zip）", 13f, bold = true))
        val fileRow = LinearLayout(activity)
        fileNameText = label(selectedZipName ?: "未选择文件", 12f).apply {
            setTextColor(if (selectedZipName != null) Ui.primaryText(activity) else Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(30, d), 1f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(10, d), 0, 0, 0)
            background = Ui.glassButton(activity)
        }
        fileRow.addView(fileNameText)
        fileRow.addView(
            smallAction("选择 ZIP", Ui.buttonSecondary(activity), minWidthDp = 88) {
                // 内置文件选择器：根目录 /sdcard，选择 GSI zip
                pickZipLauncher.launch(
                    android.content.Intent(activity, com.mcai.ubuntudsu.RootfsFilesActivity::class.java).apply {
                        putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_PICK, true)
                        putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_TITLE, "选择 GSI 安装包")
                        putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_EXT, ".zip,.img,.gz,.xz")
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(8, d)
            },
        )
        gsiCard.addView(fileRow)

        gsiCard.addView(CheckBox(activity).apply {
            text = "安装前先清理旧缓存(安装失败必选)"
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
            buttonTintList = android.content.res.ColorStateList.valueOf(Ui.secondaryText(activity))
            setPadding(Ui.dp(2, d), Ui.dp(4, d), 0, Ui.dp(2, d))
            setOnCheckedChangeListener { _, checked -> clearUserdata = checked }
        })
        gsiCard.addView(
            smallAction("开始安装", Ui.buttonSuccess(activity)) { startInstall() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(6, d) },
        )
        page.addView(gsiCard)

        // 工具入口：2×2 卡片网格（与「多功能 / Linux」页同一套卡片语言）
        // 上行=常规操作，下行=破坏性操作（红色文字 + 红色图标块作为警示）
        page.addView(
            label("DSU 工具", 12f, bold = true).apply { setTextColor(Ui.secondaryText(activity)) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(10, d); bottomMargin = Ui.dp(8, d) },
        )
        val tools = listOf(
            Tool(R.drawable.ic_dsu_restart, "#E07B39", "重启到 DSU", false) {
                confirmAction("重启进入 DSU", "设备将立即重启并进入 GSI 系统。") {
                    executor.execute {
                        val service = privilegedService
                        if (service == null) {
                            log("ROOT DSU 服务尚未连接")
                        } else if (!service.setEnable(true, true)) {
                            log("设置一次性 DSU 启动失败")
                        } else if (!service.boot()) {
                            log("请求重启到 DSU 失败")
                        }
                    }
                }
            },
            Tool(R.drawable.ic_dsu_repair, "#2D64AA", "修复环境", false) {
                confirmAction(
                    "修复 DSU 环境",
                    "将删除 /metadata/gsi/dsu 和 /metadata/vold/metadata_encryption/dsu。该操作用于清理上一次失败安装留下的状态，不会删除已选择的 GSI 文件。",
                ) {
                    executor.execute {
                        val result = DsuManager.restartDsuService(::log)
                        log(if (result.success) "DSU 环境修复完成" else "DSU 环境修复失败：${result.stderr}")
                    }
                }
            },
            Tool(R.drawable.ic_dsu_undo, "#D93838", "撤销已安装", true) {
                confirmAction("撤销 GSI", "删除 /data/gsi/dsu/dsu，移除已安装的 GSI。") { executor.execute { DsuManager.wipe(::log) } }
            },
            Tool(R.drawable.ic_dsu_clear_userdata, "#B5473B", "清理 userdata", true) {
                confirmAction("清理 userdata", "执行 gsi_tool wipe-data，仅清空 userdata 分区数据。") {
                    executor.execute {
                        val result = DsuManager.wipeData(::log)
                        log(if (result.success) "userdata 已清理" else "userdata 清理失败：${result.stderr}")
                    }
                }
            },
        )
        tools.chunked(2).forEach { rowItems ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = Ui.dp(10, d) }
            }
            rowItems.forEachIndexed { colIndex, t ->
                row.addView(
                    Ui.featureCard(activity, t.iconRes, Color.parseColor(t.tint), t.title, null, false, t.danger) { t.action() },
                    LinearLayout.LayoutParams(0, Ui.dp(76, d), 1f).apply {
                        if (colIndex > 0) marginStart = Ui.dp(10, d)
                    },
                )
            }
            page.addView(row)
        }

        DsuInstallState.addListener(this)
        return page
    }

    /** DSU 工具卡数据：图标 / 品牌色 / 标题 / 是否危险操作（红字警示） / 点击动作 */
    private data class Tool(
        val iconRes: Int,
        val tint: String,
        val title: String,
        val danger: Boolean,
        val action: () -> Unit,
    )

    private fun card(d: Float): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
        background = Ui.glassSurface(activity, 18f)
        // 圆角 outline 投影：裸 elevation 对 LayerDrawable 背景会渲染成方形影子
        Ui.applyNeuShadow(this, 3f, 18f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(8, d) }
    }

    private fun label(text: String, size: Float, bold: Boolean = false): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(Ui.primaryText(activity))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun smallAction(text: String, accent: Int, minWidthDp: Int = 0, solid: Boolean = false, onClick: () -> Unit): TextView = TextView(activity).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (solid) Color.WHITE else Ui.buttonText(activity))
        // solid：固定亮蓝渐变实心（夜间主色是暗灰绿，玻璃 accent 在深色卡上几乎看不出来）
        background = if (solid) Ui.chipSelected(activity) else Ui.glassButton(activity, accent)
        val d = activity.resources.displayMetrics.density
        setPadding(Ui.dp(14, d), Ui.dp(10, d), Ui.dp(14, d), Ui.dp(10, d))
        if (minWidthDp > 0) minimumWidth = Ui.dp(minWidthDp, d)
        Ui.pressAnimation(this)
        setOnClickListener { onClick() }
    }

    private fun selectSizeChip(row: LinearLayout, selected: Int) {
        for (index in 0 until row.childCount) {
            val chip = row.getChildAt(index) as? TextView ?: continue
            val isSelected = chip.text.toString().removeSuffix(" GB").toIntOrNull() == selected
            chip.setTextColor(if (isSelected) Color.WHITE else Ui.primaryText(activity))
            chip.background = if (isSelected) {
                // 固定亮蓝渐变实心：夜间主色是暗灰绿，压在深色卡上看不出选中
                Ui.chipSelected(activity)
            } else {
                Ui.strokeRounded(
                    Ui.surfaceGlass(activity),
                    Ui.border(activity),
                    1f,
                    activity.resources.displayMetrics.density,
                    15f,
                )
            }
        }
    }

    private fun confirmAction(title: String, message: String, action: () -> Unit) {
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定") { _, _ -> action() }
                .setNegativeButton("取消", null)
        )
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(Ui.rounded(Ui.dsuCard(activity), 24f, activity.resources.displayMetrics.density))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Ui.secondaryText(activity))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Ui.dialogAction(activity))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Ui.secondaryText(activity))
        }
        dialog.show()
    }

    // 统一更新安装进度条与下方百分比
    private fun setInstallProgress(percent: Int, text: String) {
        installProgressLabel.text = text
        installPercentText.text = "$percent %"
    }

    // ==================== 安装（前台服务执行，离开页面不中断） ====================

    private var lastPhase: DsuInstallState.Phase = DsuInstallState.Phase.IDLE

    private fun startInstall() {
        val zipUri = selectedZip ?: run {
            Toast.makeText(activity, "请先选择 GSI zip 安装包", Toast.LENGTH_SHORT).show()
            return
        }
        val path = zipUri.path
        if (path.isNullOrBlank() || !java.io.File(path).isFile) {
            Toast.makeText(activity, "无法读取所选 GSI 文件，请重新选择", Toast.LENGTH_SHORT).show()
            return
        }
        if (privilegedService == null) {
            Toast.makeText(activity, "ROOT DSU 安装器尚未连接，请先授予 ROOT 后重试", Toast.LENGTH_SHORT).show()
            return
        }
        confirmAction(
            "开始 DSU 安装",
            "将通过 ROOT DSU 安装器直接创建分区并写入 GSI 镜像。\n\n安装会转入前台服务，离开应用或锁屏后仍会继续。",
        ) {
            val intent = Intent(activity, DsuInstallService::class.java).apply {
                putExtra(DsuInstallService.EXTRA_ZIP, path)
                putExtra(DsuInstallService.EXTRA_USERDATA_GB, selectedUserdataGB)
                putExtra(DsuInstallService.EXTRA_CLEAR_CACHE, clearUserdata)
            }
            androidx.core.content.ContextCompat.startForegroundService(activity, intent)
            installProgressCard.visibility = View.VISIBLE
            setInstallProgress(0, "安装状态：准备中")
        }
    }

    /** 订阅安装服务状态：页面可见时同步刷新进度卡，页面销毁不影响安装 */
    override fun onState(state: DsuInstallState.Snapshot) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            if (!::installProgressCard.isInitialized) return@runOnUiThread
            when (state.phase) {
                DsuInstallState.Phase.RUNNING -> {
                    installProgressCard.visibility = View.VISIBLE
                    setInstallProgress(state.percent, "安装状态：" + state.message)
                }
                DsuInstallState.Phase.SUCCESS -> {
                    installProgressCard.visibility = View.GONE
                    setInstallProgress(100, "安装状态：完成")
                    if (lastPhase != DsuInstallState.Phase.SUCCESS) {
                        Toast.makeText(activity, "GSI 已安装，请在需要时点击“重启到 DSU”", Toast.LENGTH_LONG).show()
                    }
                }
                DsuInstallState.Phase.FAILED -> {
                    installProgressCard.visibility = View.VISIBLE
                    installProgressLabel.text = "安装状态：失败"
                    if (lastPhase != DsuInstallState.Phase.FAILED) {
                        Toast.makeText(activity, "安装失败：" + state.message, Toast.LENGTH_LONG).show()
                    }
                }
                else -> Unit
            }
            lastPhase = state.phase
        }
    }

    /** 页面销毁时解绑监听（安装服务继续运行） */
    fun release() {
        DsuInstallState.removeListener(this)
    }

    private fun log(line: String) {
        android.util.Log.i("DsuPage", line)
    }
}
