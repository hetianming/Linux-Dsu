package com.mcai.ubuntudsu

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.core.DsuManager
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.core.GsiState
import com.mcai.ubuntudsu.core.StatusDetector
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import java.nio.ByteBuffer
import com.topjohnwu.superuser.ipc.RootService
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder

class DsuActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var supportText: TextView
    private var selectedUserdataGB = DsuManager.userdataOptions.first()
    private var clearUserdata = false
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var selectedZip: Uri? = null
    private var selectedZipName: String? = null
    private var zipSize = -1L
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
    private lateinit var customCapacityText: TextView
    private var replacementTarget: String? = null
    private val pickReplacement =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            val target = replacementTarget ?: return@registerForActivityResult
            uri ?: return@registerForActivityResult
            executor.execute {
                val source = java.io.File(Env.downloads(this@DsuActivity), "replacement-${System.currentTimeMillis()}.img")
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        source.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    } ?: error("无法读取替换镜像")
                    val result = DsuManager.replaceImage(source, target, ::log)
                    log(if (result.success) "镜像替换完成: $target" else "镜像替换失败: ${result.stderr}")
                }.onFailure { log("镜像替换失败: ${it.message}") }
            }
        }

    private val pickZip =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
             uri?.let {
                  runCatching {
                      contentResolver.takePersistableUriPermission(
                          it,
                          Intent.FLAG_GRANT_READ_URI_PERMISSION,
                      )
                  }
                  selectedZip = it
                  selectedZipName = it.lastPathSegment ?: "gsi.zip"
                  if (::fileNameText.isInitialized) fileNameText.text = selectedZipName
                log("已选择 GSI 包: $selectedZipName")
                zipSize = contentResolver.openAssetFileDescriptor(it, "r")?.use { descriptor -> descriptor.length } ?: -1L
                Toast.makeText(this, "已选择 $selectedZipName", Toast.LENGTH_SHORT).show()
             }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "DSU 管理"
        buildUi()
        RootService.bind(Intent(this, PrivilegedRootService::class.java), rootConnection)
        refreshStatus()
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        // Use the activity context so every color follows the active night mode.
        val uiContext = this
        val scroll = ScrollView(this).apply {
            Ui.animateLiquidBackground(this)
            clipToPadding = false
        }
        statusText = TextView(this)
        supportText = TextView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(18, d), Ui.dp(16, d), Ui.dp(18, d))
        }

        fun label(text: String, size: Float, color: String? = null) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color?.let(Color::parseColor) ?: Ui.primaryText(this@DsuActivity))
        }
        fun actionCard(icon: String, iconColor: String, heading: String, detail: String, onClick: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(16, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
              background = Ui.glassSurface(uiContext, 28f)
                elevation = Ui.dp(5, d).toFloat()
                 setOnClickListener { onClick() }
                addView(label(icon, 30f, iconColor).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(Ui.dp(44, d), Ui.dp(42, d))
                })
                     addView(LinearLayout(this@DsuActivity).apply {
                         orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = Ui.dp(12, d)
                    }
                     addView(label(heading, 16f).apply {
                         setTypeface(typeface, Typeface.BOLD)
                     })
                     addView(label(detail, 13f, String.format("#%06X", 0xFFFFFF and Ui.secondaryText(uiContext))).apply { setPadding(0, Ui.dp(2, d), 0, 0) })
                })
                addView(label("›", 28f, "#9AA2AE"))
            }

        val parameterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(10, d), Ui.dp(10, d), Ui.dp(10, d), Ui.dp(10, d))
               background = Ui.glassSurface(uiContext, 28f)
                 elevation = Ui.dp(5, d).toFloat()
        }
        val installTitle = label("安装 GSI 参数", 18f)
        installTitle.setTypeface(installTitle.typeface, Typeface.BOLD)
        parameterCard.addView(installTitle)
         parameterCard.addView(label("userdata 容量", 14f, String.format("#%06X", 0xFFFFFF and Ui.secondaryText(uiContext))).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(4, d))
        })
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sizeLabels = DsuManager.userdataOptions.map { "$it" }
        sizeLabels.forEachIndexed { index, size ->
            val chip = TextView(this).apply {
                text = "$size GB"
                textSize = 15f
                gravity = Gravity.CENTER
                  setTextColor(if (index == 0) Ui.buttonText(uiContext) else Ui.primaryText(uiContext))
                  background = Ui.glassButton(uiContext, if (index == 0) Ui.buttonPrimary(uiContext) else null)
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(52, d), 1f).apply {
                    marginEnd = if (index == sizeLabels.lastIndex) 0 else Ui.dp(6, d)
                }
                setOnClickListener {
                    selectedUserdataGB = size.toInt()
                    selectSizeChip(sizeRow, selectedUserdataGB)
                }
            }
            sizeRow.addView(chip)
        }
        parameterCard.addView(sizeRow)
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(7, d), 0, 0)
        }
          customCapacityText = label("自定义容量 GB", 12f, String.format("#%06X", 0xFFFFFF and Ui.secondaryText(uiContext))).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(38, d), 1f)
              background = Ui.glassButton(uiContext)
            setPadding(Ui.dp(12, d), 0, 0, 0)
        }
        customRow.addView(customCapacityText)
        customRow.addView(TextView(this).apply {
            text = "使用自定义"
            textSize = 12f
            gravity = Gravity.CENTER
             setTextColor(Ui.buttonText(uiContext))
             background = Ui.glassButton(uiContext, Ui.buttonPrimary(uiContext))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(98, d), Ui.dp(38, d)).apply {
                marginStart = Ui.dp(10, d)
            }
            setOnClickListener { showCustomSizeDialog() }
        })
        parameterCard.addView(customRow)
          parameterCard.addView(label("GSI 安装包（zip）", 14f, String.format("#%06X", 0xFFFFFF and Ui.secondaryText(uiContext))).apply {
            setPadding(0, Ui.dp(10, d), 0, Ui.dp(4, d))
        })
        val fileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
          val fileText = label("未选择文件", 14f, String.format("#%06X", 0xFFFFFF and Ui.secondaryText(uiContext))).apply {
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(38, d), 1f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), 0, 0, 0)
              background = Ui.glassButton(uiContext)
        }
        fileNameText = fileText
        fileRow.addView(fileText)
        fileRow.addView(TextView(this).apply {
            text = "选择 ZIP"
            textSize = 12f
            gravity = Gravity.CENTER
             setTextColor(Ui.buttonText(uiContext))
             background = Ui.glassButton(uiContext, Ui.buttonSecondary(uiContext))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(90, d), Ui.dp(38, d)).apply {
                marginStart = Ui.dp(10, d)
            }
            setOnClickListener {
                pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }
        })
        parameterCard.addView(fileRow)
        parameterCard.addView(CheckBox(this).apply {
            text = "安装前先清理旧缓存(安装失败必选)"
            textSize = 13f
             setTextColor(Ui.primaryText(this@DsuActivity))
            buttonTintList = android.content.res.ColorStateList.valueOf(Ui.secondaryText(uiContext))
            setPadding(Ui.dp(2, d), Ui.dp(5, d), 0, Ui.dp(4, d))
            setOnCheckedChangeListener { _, checked -> clearUserdata = checked }
        })
        parameterCard.addView(TextView(this).apply {
            text = "开始安装"
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
             setTextColor(Ui.buttonText(uiContext))
             background = Ui.glassButton(uiContext, Ui.buttonSuccess(uiContext))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40, d))
            setOnClickListener { startInstall() }
        })
        root.addView(parameterCard)
          installProgressLabel = label("开始状态：未开始", 13f, String.format("#%06X", 0xFFFFFF and Ui.secondaryText(uiContext))).apply {
            setPadding(0, Ui.dp(8, d), 0, Ui.dp(3, d))
        }
        installProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(8, d))
        }
        root.addView(installProgressLabel)
        root.addView(installProgressBar)
        root.addView(spacer(8))
        root.addView(TextView(this).apply {
            text = "镜像管理"
            textSize = 15f
            gravity = Gravity.CENTER
             setTextColor(Ui.primaryText(this@DsuActivity))
               background = Ui.glassSurface(uiContext, 28f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(46, d))
            setOnClickListener { showImageManager() }
        })
        root.addView(spacer(10))
             root.addView(actionCard("⚒", "#20C55A", "修复环境", "清理 DSU 元数据后重新准备安装") {
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
        root.addView(spacer(8))
        root.addView(actionCard("↻", "#F0A010", "重启到 DSU", "重启进入已安装的 GSI 系统") {
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
        root.addView(spacer(8))
        root.addView(actionCard("▣", "#EF454A", "撤销已安装", "移除当前 GSI 及其数据，回到原系统") {
            confirmAction("撤销 GSI", "删除 /data/gsi/dsu/dsu，移除已安装的 GSI。") { executor.execute { DsuManager.wipe(::log) } }
        })

        logText = Ui.logTextView(this)
        logScroll = ScrollView(this)
        logScroll.addView(logText)

        scroll.addView(root)
        setContentView(scroll)
        Ui.enableEdgeToEdge(this, scroll)

        for (index in 0 until root.childCount) {
            root.getChildAt(index).takeIf { it.isClickable }?.let(Ui::pressAnimation)
        }
    }

    private fun spacer(height: Int): View {
        val d = resources.displayMetrics.density
        return View(this).also { it.layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(height, d)) }
    }

    private fun selectSizeChip(row: LinearLayout, selected: Int) {
        for (index in 0 until row.childCount) {
            val chip = row.getChildAt(index) as? TextView ?: continue
            val isSelected = chip.text.toString().removeSuffix(" GB").toIntOrNull() == selected
                  chip.setTextColor(if (isSelected) Ui.buttonText(this@DsuActivity) else Ui.primaryText(this@DsuActivity))
              chip.background = Ui.strokeRounded(
                   if (isSelected) Ui.buttonPrimary(this@DsuActivity) else Ui.surface(this@DsuActivity),
                   if (isSelected) Ui.buttonPrimary(this@DsuActivity) else Ui.border(this@DsuActivity),
                1f,
                resources.displayMetrics.density,
                22f,
            )
        }
    }

    private fun showCustomSizeDialog() {
        val input = EditText(this).apply {
            hint = "userdata 容量（GB）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("自定义 userdata 容量")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value != null && value > 0) {
                    selectedUserdataGB = value
                    customCapacityText.text = "${value}GB"
                } else {
                    Toast.makeText(this, "请输入有效容量", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .also { showDialog(it) }
    }

    private fun showImageManager() {
        executor.execute {
            val images = DsuManager.listImages(::log)
            val imagePartitions = DsuManager.listImagePartitions(::log)
            val partitions = DsuManager.listPartitions(::log)
            runOnUiThread {
                 val content = LinearLayout(this).apply {
                     orientation = LinearLayout.VERTICAL
                      setBackgroundColor(Ui.dsuCard(this@DsuActivity))
                     setPadding(Ui.dp(20, resources.displayMetrics.density), 0, Ui.dp(20, resources.displayMetrics.density), 0)
                }
                if (imagePartitions.isNotEmpty()) {
                    content.addView(TextView(this).apply {
                        text = "镜像分区（可单独替换）"
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                         setTextColor(Ui.primaryText(this@DsuActivity))
                    })
                }
                imagePartitions.forEach { partition ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, Ui.dp(7, resources.displayMetrics.density), 0, Ui.dp(7, resources.displayMetrics.density))
                    }
                    row.addView(TextView(this).apply {
                        text = partition.label()
                        textSize = 13f
                         setTextColor(Ui.secondaryText(this@DsuActivity))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(this).apply {
                        text = "替换"
                        textSize = 13f
                        setTextColor(Ui.buttonPrimary(this@DsuActivity))
                        setOnClickListener {
                            replacementTarget = partition.path
                            pickReplacement.launch(arrayOf("application/octet-stream", "*/*"))
                        }
                    })
                    content.addView(row)
                }
                partitions.forEach { partition ->
                    content.addView(TextView(this).apply {
                        text = "${partition.path}\n${partition.detail}"
                        textSize = 13f
                         setTextColor(Ui.secondaryText(this@DsuActivity))
                        setPadding(0, Ui.dp(8, resources.displayMetrics.density), 0, Ui.dp(8, resources.displayMetrics.density))
                    })
                }
                if (images.isEmpty() && imagePartitions.isEmpty() && partitions.isEmpty()) {
                     content.addView(TextView(this).apply {
                         text = "暂无镜像文件"
                         textSize = 14f
                         setTextColor(Ui.primaryText(this@DsuActivity))
                        setPadding(0, Ui.dp(12, resources.displayMetrics.density), 0, Ui.dp(12, resources.displayMetrics.density))
                    })
                } else {
                    images.forEach { image ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, Ui.dp(8, resources.displayMetrics.density), 0, Ui.dp(8, resources.displayMetrics.density))
                        }
                         row.addView(TextView(this).apply {
                            text = image.label()
                            textSize = 13f
                            setTextColor(Ui.secondaryText(this@DsuActivity))
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        row.addView(TextView(this).apply {
                            text = "替换"
                            textSize = 13f
                            setTextColor(Ui.buttonPrimary(this@DsuActivity))
                            setOnClickListener {
                                replacementTarget = image.path
                                pickReplacement.launch(arrayOf("application/octet-stream", "*/*"))
                            }
                        })
                        row.addView(TextView(this).apply {
                            text = "删除"
                            textSize = 13f
                            setPadding(Ui.dp(12, resources.displayMetrics.density), 0, 0, 0)
                            setTextColor(Ui.buttonDanger(this@DsuActivity))
                            setOnClickListener {
                                executor.execute { DsuManager.deleteImage(image.path, ::log) }
                                showImageManager()
                            }
                        })
                        content.addView(row)
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("镜像管理")
                    .setView(content)
                    .setPositiveButton("关闭", null)
                    .also { showDialog(it) }
            }
        }
    }

    private fun confirmAction(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> action() }
            .setNegativeButton("取消", null)
            .also { showDialog(it) }
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.setOnShowListener {
            val density = resources.displayMetrics.density
            dialog.window?.setBackgroundDrawable(Ui.rounded(Ui.dsuCard(this), 24f, density))
            val titleId = resources.getIdentifier("alertTitle", "id", "android")
            dialog.findViewById<TextView>(titleId)?.setTextColor(Ui.primaryText(this))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Ui.secondaryText(this))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Ui.buttonPrimary(this))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Ui.secondaryText(this))
        }
        dialog.show()
    }

    private fun startInstall() {
        val zipUri = selectedZip ?: run {
            Toast.makeText(this, "请先选择 GSI zip 安装包", Toast.LENGTH_SHORT).show()
            return
        }
        val service = privilegedService ?: run {
            Toast.makeText(this, "ROOT DSU 安装器尚未连接，请先授予 ROOT 后重试", Toast.LENGTH_SHORT).show()
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
                    log("正在从所选位置直接读取 GSI ZIP")
                    contentResolver.openInputStream(zipUri)?.use { input ->
                        installWithRootService(input, service)
                    } ?: error("无法读取 GSI ZIP")
                }.onFailure {
                    log("读取 GSI ZIP 失败：${it.message}")
                    runOnUiThread { installProgressLabel.text = "安装 GSI：读取失败" }
                }
            }
        }
    }

    private fun installWithRootService(input: java.io.InputStream, service: IPrivilegedService) {
        runOnUiThread { installProgressBar.progress = 0; installProgressLabel.text = "安装 GSI：解析镜像" }
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
                // Enabling the installed DSU does not reboot; boot remains an explicit user action.
                 // Installation stays on the normal system. The reboot action enables DSU.
                 if (!service.setEnable(false, false)) error("停用 DSU 自动启动失败")
                completed = true
            }
            runOnUiThread { installProgressBar.progress = 100; installProgressLabel.text = "安装 GSI：完成"; Toast.makeText(this, "GSI 已安装，请在需要时点击“重启到 DSU”", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            if (started && !completed) runCatching { service.abort() }
            log("ROOT DSU 安装失败：${e.message}")
            runOnUiThread { installProgressLabel.text = "安装 GSI：失败"; Toast.makeText(this, "安装失败：${e.message}", Toast.LENGTH_LONG).show() }
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
                        runOnUiThread { installProgressBar.progress = progress; installProgressLabel.text = "安装 GSI：写入 $name $progress%" }
                    }
                } finally { SharedMemory.unmap(mapped) }
            }
        }
    }

    private fun sharedMemoryFd(memory: SharedMemory): ParcelFileDescriptor {
        return runCatching { SharedMemory::class.java.getMethod("getFdDup").invoke(memory) as ParcelFileDescriptor }
            .getOrElse { ParcelFileDescriptor.fromFd(SharedMemory::class.java.getDeclaredMethod("getFd").apply { isAccessible = true }.invoke(memory) as Int) }
    }


    private fun refreshStatus() {
        statusText.text = "GSI 状态：检测中..."
        executor.execute {
            val (state, detail) = StatusDetector.gsiState()
            val supported = StatusDetector.dsuSupported()
            val label = when (state) {
                GsiState.RUNNING -> "运行中"
                GsiState.INSTALLED -> "已安装"
                GsiState.ENABLED -> "已启用"
                GsiState.DISABLED -> "已停用"
                GsiState.NORMAL -> "未安装"
                GsiState.UNKNOWN -> "未检测到"
            }
            runOnUiThread {
                statusText.text = "GSI 状态：$label\n详情：$detail"
                supportText.text = "DSU 支持：${if (supported) "已检测到系统 DSU 服务" else "未检测到（安装可能失败）"}"
            }
        }
    }

    private fun log(line: String) {
        runOnUiThread {
            logText.append(line + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
