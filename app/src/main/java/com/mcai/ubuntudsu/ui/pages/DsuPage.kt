package com.mcai.ubuntudsu.ui.pages

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.mcai.ubuntudsu.core.DsuManager
import com.mcai.ubuntudsu.ui.Ui
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.Executor
import java.util.zip.ZipInputStream

class DsuPage(
    private val activity: AppCompatActivity,
    private val executor: Executor,
    private val pickZipLauncher: ActivityResultLauncher<android.content.Intent>,
) {
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
    private lateinit var installProgressLabel: TextView
    private lateinit var installProgressBar: ProgressBar
    private lateinit var installPercentText: TextView
    private lateinit var customCapacityText: TextView

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

        // 安装参数 + 镜像管理合并卡
        val parameterCard = card(d)
        parameterCard.addView(label("userdata 容量", 13f, bold = true))
        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        DsuManager.userdataOptions.forEachIndexed { index, size ->
            val chip = TextView(activity).apply {
                text = "$size GB"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) Ui.buttonText(activity) else Ui.primaryText(activity))
                background = Ui.glassButton(activity, if (index == 0) Ui.buttonPrimary(activity) else null)
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(30, d), 1f).apply {
                    marginEnd = if (index == DsuManager.userdataOptions.lastIndex) 0 else Ui.dp(5, d)
                }
                setOnClickListener {
                    selectedUserdataGB = size
                    selectSizeChip(sizeRow, size)
                }
            }
            sizeRow.addView(chip)
        }
        parameterCard.addView(sizeRow)
        val customRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(5, d), 0, 0)
        }
        customCapacityText = label("自定义容量 GB", 11f).apply {
            setTextColor(Ui.secondaryText(activity))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(30, d), 1f)
            background = Ui.glassButton(activity)
            setPadding(Ui.dp(10, d), 0, 0, 0)
        }
        customRow.addView(customCapacityText)
        customRow.addView(
            smallAction("自定义", Ui.buttonPrimary(activity), minWidthDp = 64) { showCustomSizeDialog() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(30, d)).apply {
                marginStart = Ui.dp(8, d)
            },
        )
        parameterCard.addView(customRow)

        parameterCard.addView(label("GSI 安装包（zip）", 13f, bold = true).apply { setPadding(0, Ui.dp(8, d), 0, Ui.dp(3, d)) })
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
            smallAction("选择 ZIP", Ui.buttonSecondary(activity), minWidthDp = 72) {
                // 内置文件选择器：根目录 /sdcard，选择 GSI zip
                pickZipLauncher.launch(
                    android.content.Intent(activity, com.mcai.ubuntudsu.RootfsFilesActivity::class.java).apply {
                        putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_PICK, true)
                        putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_TITLE, "选择 GSI 安装包")
                        putExtra(com.mcai.ubuntudsu.RootfsFilesActivity.EXTRA_EXT, ".zip,.img,.gz,.xz")
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(30, d)).apply {
                marginStart = Ui.dp(8, d)
            },
        )
        parameterCard.addView(fileRow)

        parameterCard.addView(CheckBox(activity).apply {
            text = "安装前先清理旧缓存(安装失败必选)"
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
            buttonTintList = android.content.res.ColorStateList.valueOf(Ui.secondaryText(activity))
            setPadding(Ui.dp(2, d), Ui.dp(4, d), 0, Ui.dp(2, d))
            setOnCheckedChangeListener { _, checked -> clearUserdata = checked }
        })
        parameterCard.addView(
            smallAction("开始安装", Ui.buttonSuccess(activity)) { startInstall() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(36, d)).apply { topMargin = Ui.dp(6, d) },
        )
        page.addView(parameterCard)

        // 进度卡
        val progressCard = card(d)
        installProgressLabel = label("开始状态：未开始", 12f).apply { setTextColor(Ui.secondaryText(activity)) }
        installProgressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = Ui.pillProgressDrawable(activity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(22, d)).apply { topMargin = Ui.dp(8, d) }
        }
        installPercentText = Ui.percentTextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(6, d) }
        }
        progressCard.addView(installProgressLabel)
        progressCard.addView(installProgressBar)
        progressCard.addView(installPercentText)
        page.addView(progressCard)

        // 工具区
        page.addView(actionCard("⚒", Ui.buttonSuccess(activity), "修复环境", "清理 DSU 元数据后重新准备安装") {
            confirmAction(
                "修复 DSU 环境",
                "将删除 /metadata/gsi/dsu 和 /metadata/vold/metadata_encryption/dsu。该操作用于清理上一次失败安装留下的状态，不会删除已选择的 GSI 文件。",
            ) {
                executor.execute {
                    val result = DsuManager.restartDsuService(::log)
                    log(if (result.success) "DSU 环境修复完成" else "DSU 环境修复失败：${result.stderr}")
                }
            }
        })
        page.addView(spacer(6))
        page.addView(actionCard("↻", Ui.buttonWarning(activity), "重启到 DSU", "重启进入已安装的 GSI 系统") {
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
        })
        page.addView(spacer(6))
        page.addView(actionCard("▣", Ui.buttonDanger(activity), "撤销已安装", "移除当前 GSI 及其数据，回到原系统") {
            confirmAction("撤销 GSI", "删除 /data/gsi/dsu/dsu，移除已安装的 GSI。") { executor.execute { DsuManager.wipe(::log) } }
        })
        page.addView(spacer(6))
        page.addView(actionCard("⌫", Ui.buttonSecondary(activity), "清理 userdata", "保留 GSI 但清空用户数据分区") {
            confirmAction("清理 userdata", "执行 gsi_tool wipe-data，仅清空 userdata 分区数据。") {
                executor.execute {
                    val result = DsuManager.wipeData(::log)
                    log(if (result.success) "userdata 已清理" else "userdata 清理失败：${result.stderr}")
                }
            }
        })

        return page
    }

    private fun card(d: Float): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
        background = Ui.glassSurface(activity, 18f)
        elevation = Ui.dp(3, d).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(8, d) }
    }

    private fun spacer(height: Int): View {
        val d = activity.resources.displayMetrics.density
        return View(activity).also { it.layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(height, d)) }
    }

    private fun label(text: String, size: Float, bold: Boolean = false): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(Ui.primaryText(activity))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun smallAction(text: String, accent: Int, minWidthDp: Int = 0, onClick: () -> Unit): TextView = TextView(activity).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(Ui.buttonText(activity))
        background = Ui.glassButton(activity, accent)
        val d = activity.resources.displayMetrics.density
        setPadding(Ui.dp(8, d), 0, Ui.dp(8, d), 0)
        if (minWidthDp > 0) minimumWidth = Ui.dp(minWidthDp, d)
        Ui.pressAnimation(this)
        setOnClickListener { onClick() }
    }

    private fun actionCard(icon: String, iconColor: Int, heading: String, detail: String, onClick: () -> Unit): View {
        val d = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(9, d), Ui.dp(10, d), Ui.dp(9, d))
            background = Ui.glassSurface(activity, 18f)
            elevation = Ui.dp(3, d).toFloat()
            setOnClickListener { onClick() }
            Ui.pressAnimation(this)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(TextView(activity).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(iconColor)
                    cornerRadius = Ui.dp(11, d).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(Ui.dp(36, d), Ui.dp(36, d))
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Ui.dp(10, d)
                }
                addView(label(heading, 14f, bold = true))
                addView(label(detail, 11f).apply {
                    setTextColor(Ui.secondaryText(activity))
                    setPadding(0, Ui.dp(1, d), 0, 0)
                })
            })
            addView(label("›", 22f).apply { setTextColor(Ui.secondaryText(activity)) })
        }
    }

    private fun selectSizeChip(row: LinearLayout, selected: Int) {
        for (index in 0 until row.childCount) {
            val chip = row.getChildAt(index) as? TextView ?: continue
            val isSelected = chip.text.toString().removeSuffix(" GB").toIntOrNull() == selected
            chip.setTextColor(if (isSelected) Ui.buttonText(activity) else Ui.primaryText(activity))
            chip.background = Ui.strokeRounded(
                if (isSelected) Ui.buttonPrimary(activity) else Ui.surface(activity),
                if (isSelected) Ui.buttonPrimary(activity) else Ui.border(activity),
                1f,
                activity.resources.displayMetrics.density,
                15f,
            )
        }
    }

    private fun showCustomSizeDialog() {
        val input = EditText(activity).apply {
            hint = "userdata 容量（GB）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle("自定义 userdata 容量")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val value = input.text.toString().toIntOrNull()
                    if (value != null && value > 0) {
                        selectedUserdataGB = value
                        customCapacityText.text = "${value}GB"
                    } else {
                        Toast.makeText(activity, "请输入有效容量", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
        )
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Ui.buttonPrimary(activity))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Ui.secondaryText(activity))
        }
        dialog.show()
    }

    // 统一更新安装进度条与下方百分比
    private fun setInstallProgress(percent: Int, text: String) {
        installProgressBar.progress = percent
        installProgressLabel.text = text
        installPercentText.text = "$percent %"
    }    private fun startInstall() {        val zipUri = selectedZip ?: run {
            Toast.makeText(activity, "请先选择 GSI zip 安装包", Toast.LENGTH_SHORT).show()
            return
        }
        val service = privilegedService ?: run {
            Toast.makeText(activity, "ROOT DSU 安装器尚未连接，请先授予 ROOT 后重试", Toast.LENGTH_SHORT).show()
            return
        }
        confirmAction("开始 DSU 安装", "将通过 ROOT DSU 安装器直接创建分区并写入 GSI 镜像。") {
            executor.execute {
                if (clearUserdata) {
                    log("正在清理旧缓存：/metadata/gsi/dsu/dsu/lp_metadata")
                    val clearResult = DsuManager.clearInstallCache(::log)
                    check(clearResult.success) { "清理旧缓存失败：${clearResult.stderr}" }
                }
                runCatching {
                    val path = zipUri.path ?: error("无法读取 GSI ZIP")
                    // 先试普通流（可直读秒开）；失败或无权限降级 root 流（su cat，零拷贝）
                    val input = runCatching {
                        java.io.File(path).takeIf { it.canRead() }?.inputStream()
                    }.getOrNull()
                        ?: com.mcai.ubuntudsu.core.RootShell.openStream(path)
                    log("正在从所选位置直接读取 GSI ZIP")
                    input.use { installWithRootService(it, service) }
                }.onFailure {
                    log("读取 GSI ZIP 失败：${it.message}")
                    activity.runOnUiThread { installProgressLabel.text = "安装 GSI：读取失败" }
                }
            }
        }
    }

    private fun installWithRootService(input: java.io.InputStream, service: IPrivilegedService) {
        activity.runOnUiThread { setInstallProgress(0, "安装 GSI：解析镜像") }
        var started = false
        var completed = false
        try {
            ZipInputStream(java.io.BufferedInputStream(input, 1024 * 1024)).use { zip ->
                if (!service.isAvailable()) error("系统 dynamic_system 服务不可用")
                if (!service.startInstallation("dsu")) error("dynamic_system 拒绝开始安装")
                started = true
                val partitions = HashSet<String>()
                var imageCount = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.isDirectory || !entry.name.substringAfterLast('/').endsWith(".img", true)) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }
                    val name = entry.name.substringAfterLast('/').removeSuffix(".img").lowercase()
                    if (!name.matches(Regex("[a-z0-9_-]+")) || !partitions.add(name)) error("无效或重复分区：$name")
                    val size = entry.size
                    if (size <= 0) error("镜像为空：$name")
                    val partitionSize = if (name == "userdata") maxOf(size, selectedUserdataGB.toLong() * 1024 * 1024 * 1024) else size
                    val result = service.createPartition(name, partitionSize, name != "userdata")
                    if (result != 0) error("创建分区失败：$name ($result)")
                    streamImage(zip, size, name, service, imageCount)
                    if (!service.closePartition()) error("关闭分区失败：$name")
                    imageCount++
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                if (imageCount == 0) error("ZIP 中没有可用的 img 镜像")
                if (!partitions.contains("userdata")) {
                    if (service.createPartition("userdata", selectedUserdataGB.toLong() * 1024 * 1024 * 1024, false) != 0) error("创建 userdata 失败")
                    if (!service.closePartition()) error("关闭 userdata 失败")
                }
                if (!service.finishInstallation()) error("完成 DSU 安装失败")
                if (!service.setEnable(false, false)) error("停用 DSU 自动启动失败")
                completed = true
            }
            activity.runOnUiThread { setInstallProgress(100, "安装 GSI：完成"); Toast.makeText(activity, "GSI 已安装，请在需要时点击“重启到 DSU”", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            if (started && !completed) runCatching { service.abort() }
            log("ROOT DSU 安装失败：${e.message}")
            activity.runOnUiThread { installProgressLabel.text = "安装 GSI：失败"; Toast.makeText(activity, "安装失败：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun streamImage(input: java.io.InputStream, size: Long, name: String, service: IPrivilegedService, index: Int) {
        val bufferSize = 4 * 1024 * 1024
        SharedMemory.create("ubuntudsu-$name", bufferSize).use { memory ->
            sharedMemoryFd(memory).use { fd ->
                if (!service.setAshmem(fd, bufferSize.toLong())) error("共享内存初始化失败：$name")
                val mapped = memory.mapReadWrite()
                try {
                    val buffer = ByteArray(1024 * 1024)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        mapped.position(0)
                        mapped.put(buffer, 0, count)
                        if (!service.submitFromAshmem(count.toLong())) error("写入失败：$name")
                        written += count
                        val imageProgress = if (size > 0) (written * 80L / size).toInt() else 0
                        val progress = (10 + imageProgress).coerceIn(10, 90)
                        activity.runOnUiThread { setInstallProgress(progress, "安装 GSI：写入 $name") }
                    }
                } finally { SharedMemory.unmap(mapped) }
            }
        }
    }

    private fun sharedMemoryFd(memory: SharedMemory): ParcelFileDescriptor {
        return runCatching { SharedMemory::class.java.getMethod("getFdDup").invoke(memory) as ParcelFileDescriptor }
            .getOrElse { ParcelFileDescriptor.fromFd(SharedMemory::class.java.getDeclaredMethod("getFd").apply { isAccessible = true }.invoke(memory) as Int) }
    }

    private fun log(line: String) {
        android.util.Log.i("DsuPage", line)
    }
}
