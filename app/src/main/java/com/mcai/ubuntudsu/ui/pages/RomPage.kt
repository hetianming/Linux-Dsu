package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.core.RomDevice
import com.mcai.ubuntudsu.core.RomVersion
import com.mcai.ubuntudsu.core.RomApi
import com.mcai.ubuntudsu.core.DownloadNode
import com.mcai.ubuntudsu.core.JavaDownloader
import com.mcai.ubuntudsu.service.DownloadService
import com.mcai.ubuntudsu.ui.DynamicIsland
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RomPage(
    private val activity: Activity,
    private val onBack: () -> Unit,
    private val scope: CoroutineScope,
) {
    private lateinit var deviceListContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var searchInput: EditText
    private lateinit var currentDeviceText: TextView
    private lateinit var dynamicIsland: DynamicIsland

    private var allDevices = emptyList<RomDevice>()
    private var filteredDevices = emptyList<RomDevice>()
    private var isLoading = false

    private var downloadReceiver: BroadcastReceiver? = null

    fun build(): View {
        val d = activity.resources.displayMetrics.density

        // 根容器：FrameLayout，内容在下，灵动岛在上
        val root = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(8, d))
        }

        // ===== 标题栏 =====
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(12, d))
        }
        titleRow.addView(TextView(activity).apply {
            text = "‹ 返回"
            textSize = 13f
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonPrimary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            setOnClickListener {
                Haptics.perform(this)
                onBack()
            }
        })
        titleRow.addView(TextView(activity).apply {
            text = "ROM 固件"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(60, d), Ui.dp(1, d))
        })
        page.addView(titleRow)

        // ===== 当前设备卡片 =====
        val currentCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 16f)
            elevation = Ui.dp(2, d).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        currentCard.addView(TextView(activity).apply {
            text = "当前设备"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
        })
        currentDeviceText = TextView(activity).apply {
            text = "${RomApi.getCurrentDeviceModel()}（${RomApi.getCurrentDeviceCodename()}）"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.buttonPrimary(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        currentCard.addView(currentDeviceText)
        page.addView(currentCard)

        // ===== 搜索框 =====
        val searchBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
            background = Ui.glassSurface(activity, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        searchBox.addView(ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_search_category_default)
            setColorFilter(Ui.secondaryText(activity), android.graphics.PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(18, d), Ui.dp(18, d))
        })
        searchInput = EditText(activity).apply {
            hint = "搜索设备名称或代号..."
            textSize = 13f
            setTextColor(Ui.primaryText(activity))
            setHintTextColor(Ui.secondaryText(activity))
            background = null
            setPadding(Ui.dp(8, d), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener { filterDevices(it?.toString() ?: "") }
        }
        searchBox.addView(searchInput)
        page.addView(searchBox)

        // 状态文字
        statusText = TextView(activity).apply {
            text = "加载中..."
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        }
        page.addView(statusText)

        // ===== 设备列表 =====
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        deviceListContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scrollView.addView(deviceListContainer)
        page.addView(scrollView)

        // 将内容添加到根容器
        root.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // ===== 灵动岛 =====
        dynamicIsland = DynamicIsland(activity) {
            // 取消下载
            Haptics.perform(activity.window.decorView)
            activity.startService(
                Intent(activity, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_CANCEL
                }
            )
        }
        root.addView(dynamicIsland.build(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ))

        // 注册下载广播
        registerDownloadReceiver()

        // 如果下载已经在进行中，恢复灵动岛显示
        if (DownloadService.isDownloading()) {
            dynamicIsland.show(
                DownloadService.getFileName(),
                DownloadService.getDeviceName(),
                DownloadService.getLabelInfo(),
            )
        }

        // 加载设备列表
        loadDevices()

        return root
    }

    // ========== 下载广播接收 ==========

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                when {
                    intent.hasExtra(DownloadService.EXTRA_LOG) -> {
                        // 忽略详细日志，不显示在 UI
                    }
                    intent.hasExtra(DownloadService.EXTRA_DONE) -> {
                        val success = intent.getBooleanExtra(DownloadService.EXTRA_SUCCESS, false)
                        val msg = intent.getStringExtra(DownloadService.EXTRA_MESSAGE) ?: ""
                        val savedPath = intent.getStringExtra(DownloadService.EXTRA_SAVED_PATH) ?: ""
                        dynamicIsland.dismiss()
                        if (success) {
                            showDownloadCompleteDialog(msg, savedPath)
                        } else {
                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    intent.hasExtra(DownloadService.EXTRA_STATE) -> {
                        val state = intent.getIntExtra(DownloadService.EXTRA_STATE, 0)
                        val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
                        val speed = intent.getStringExtra(DownloadService.EXTRA_SPEED) ?: ""
                        val status = intent.getStringExtra(DownloadService.EXTRA_STATUS_TEXT) ?: ""
                        val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME) ?: ""
                        val devName = intent.getStringExtra(DownloadService.EXTRA_DEVICE) ?: ""
                        when (state) {
                            1 -> { // DOWNLOADING
                                if (!DownloadService.isDownloading() || progress == 0) {
                                    dynamicIsland.show(fileName, devName)
                                }
                                dynamicIsland.updateProgress(progress, speed)
                                dynamicIsland.setStatus(status)
                            }
                            2 -> { // DONE
                                dynamicIsland.setStatus("完成")
                                dynamicIsland.updateProgress(100, "")
                            }
                            3 -> { // CANCELLED
                                dynamicIsland.dismiss()
                                Toast.makeText(activity, "下载已取消", Toast.LENGTH_SHORT).show()
                            }
                            4 -> { // FAILED
                                dynamicIsland.setStatus("失败")
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(DownloadService.BROADCAST_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(downloadReceiver, filter)
        }
    }

    fun cleanup() {
        runCatching { downloadReceiver?.let { activity.unregisterReceiver(it) } }
        dynamicIsland.dismiss(false)
    }

    private fun showDownloadCompleteDialog(message: String, savedPath: String) {
        val d = activity.resources.displayMetrics.density
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(16, d), Ui.dp(20, d), Ui.dp(16, d))
        }
        view.addView(TextView(activity).apply {
            text = "✓ 下载完成"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.buttonPrimary(activity))
        })
        view.addView(TextView(activity).apply {
            text = savedPath
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(8, d), 0, 0)
            maxLines = 3
        })

        AlertDialog.Builder(activity)
            .setTitle("下载完成")
            .setView(view)
            .setPositiveButton("确定", null)
            .setNeutralButton("打开目录") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(savedPath), "application/zip")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { activity.startActivity(intent) }
            }
            .show()
    }

    // ========== 权限检查 ==========

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
                Toast.makeText(activity, "请授予「所有文件访问权限」以保存到 /sdcard/Downloads", Toast.LENGTH_LONG).show()
            }
        } else {
            activity.requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                1002,
            )
        }
    }

    // ========== 设备列表加载 ==========

    private fun loadDevices() {
        if (isLoading) return
        isLoading = true
        statusText.text = "正在加载设备列表..."

        scope.launch {
            try {
                val devices = RomApi.fetchDeviceList()
                allDevices = devices
                filteredDevices = devices

                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    renderDeviceList(devices)
                    statusText.text = "共 ${devices.size} 个设备"
                }
            } catch (e: Exception) {
                val builtIn = RomApi.fetchDeviceList()
                allDevices = builtIn
                filteredDevices = builtIn
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    statusText.text = "加载失败，使用内置列表"
                    renderDeviceList(builtIn)
                }
            } finally {
                isLoading = false
            }
        }
    }

    private fun filterDevices(query: String) {
        val q = query.trim().lowercase()
        filteredDevices = if (q.isEmpty()) {
            allDevices
        } else {
            allDevices.filter {
                it.name.lowercase().contains(q) ||
                    it.codename.lowercase().contains(q) ||
                    it.brand.lowercase().contains(q)
            }
        }
        renderDeviceList(filteredDevices)
        statusText.text = "找到 ${filteredDevices.size} 个设备"
    }

    // 系列显示名与排序优先级
    private val seriesOrder = listOf(
        "小米系列" to 0,
        "小米 Civi系列" to 1,
        "小米 MIX系列" to 2,
        "小米平板系列" to 3,
        "Redmi K系列" to 4,
        "Redmi Note系列" to 5,
        "Redmi Turbo系列" to 6,
        "Redmi R系列" to 7,
        "Redmi A系列" to 8,
        "Redmi系列" to 9,
        "Redmi M系列" to 10,
        "Redmi 平板系列" to 11,
        "POCO F系列" to 12,
        "POCO X系列" to 13,
        "POCO M系列" to 14,
        "POCO C系列" to 15,
        "POCO Pad系列" to 16,
    )

    private fun normalizeSeries(s: String): String {
        return s
            .replace("REDMI", "Redmi")
            .replace("小米Civi", "小米 Civi")
            .replace("Redmi R 系列", "Redmi R系列")
            .replace("REDMI M 系列", "Redmi M系列")
            .replace("REDMI 平板系列", "Redmi 平板系列")
            .trim()
    }

    private fun seriesPriority(s: String): Int {
        val normalized = normalizeSeries(s)
        return seriesOrder.firstOrNull { normalizeSeries(it.first) == normalized }?.second ?: 99
    }

    private fun extractDeviceNumber(name: String): Int {
        val numbers = Regex("\\d+").findAll(name).map { it.value.toInt() }.toList()
        return if (numbers.isNotEmpty()) numbers.first() * 1000 + (numbers.getOrElse(1) { 0 }) else 0
    }

    private fun renderDeviceList(devices: List<RomDevice>) {
        val d = activity.resources.displayMetrics.density
        deviceListContainer.removeAllViews()

        if (devices.isEmpty()) {
            deviceListContainer.addView(TextView(activity).apply {
                text = "未找到匹配的设备"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Ui.secondaryText(activity))
                setPadding(0, Ui.dp(30, d), 0, Ui.dp(30, d))
            })
            return
        }

        val grouped = devices
            .groupBy { normalizeSeries(it.series) }
            .toList()
            .sortedBy { (series, _) -> seriesPriority(series) }

        for ((series, seriesDevices) in grouped) {
            deviceListContainer.addView(TextView(activity).apply {
                text = series
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.secondaryText(activity))
                setPadding(Ui.dp(4, d), Ui.dp(10, d), 0, Ui.dp(6, d))
            })

            val sortedDevices = seriesDevices.sortedByDescending { extractDeviceNumber(it.name) }
            val columns = 4
            var row: LinearLayout? = null

            sortedDevices.forEachIndexed { index, device ->
                if (index % columns == 0) {
                    row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = Ui.dp(6, d) }
                    }
                    deviceListContainer.addView(row)
                }
                val item = buildDeviceGridItem(device)
                row!!.addView(item, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    if (index % columns > 0) marginStart = Ui.dp(4, d)
                    if (index % columns < columns - 1) marginEnd = Ui.dp(4, d)
                })
            }
        }
    }

    private fun getDeviceIconColor(device: RomDevice): Int {
        val series = normalizeSeries(device.series)
        return when {
            series.contains("MIX") -> Color.parseColor("#673AB7")
            series.contains("Civi") -> Color.parseColor("#E91E63")
            series.contains("平板") -> Color.parseColor("#00897B")
            series == "小米系列" -> Color.parseColor("#FF6B35")
            series.contains("K") -> Color.parseColor("#F44336")
            series.contains("Note") -> Color.parseColor("#795548")
            series.contains("Turbo") -> Color.parseColor("#FF5722")
            series.contains("R系列") -> Color.parseColor("#5D4037")
            series.contains("A系列") -> Color.parseColor("#6D4C41")
            series.contains("M系列") -> Color.parseColor("#4E342E")
            series == "Redmi系列" -> Color.parseColor("#BF360C")
            series.contains("F") -> Color.parseColor("#FFB300")
            series.contains("X") -> Color.parseColor("#3F51B5")
            series.contains("M") -> Color.parseColor("#1E88E5")
            series.contains("C") -> Color.parseColor("#7B1FA2")
            series.contains("Pad") -> Color.parseColor("#00838F")
            else -> Color.parseColor("#546E7A")
        }
    }

    private fun buildDeviceGridItem(device: RomDevice): View {
        val d = activity.resources.displayMetrics.density
        val isCurrentDevice = device.codename.equals(RomApi.getCurrentDeviceCodename(), ignoreCase = true)
        val accentColor = getDeviceIconColor(device)

        val item = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(6, d), Ui.dp(10, d), Ui.dp(6, d), Ui.dp(10, d))
            background = Ui.glassSurface(activity, 12f)
            elevation = Ui.dp(1, d).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.perform(this)
                queryDeviceVersions(device)
            }
        }
        Ui.pressAnimation(item)

        // 顶部彩色细条（替代大图标方块）
        val accentBar = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(24, d), Ui.dp(3, d)).apply {
                bottomMargin = Ui.dp(6, d)
            }
            background = Ui.rounded(accentColor, 2f, d)
        }
        item.addView(accentBar)

        // 设备名称
        item.addView(TextView(activity).apply {
            text = device.name
            textSize = 10f
            setTextColor(if (isCurrentDevice) Ui.buttonPrimary(activity) else Ui.primaryText(activity))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })

        // 代号
        item.addView(TextView(activity).apply {
            text = device.codename
            textSize = 9f
            setTextColor(Ui.secondaryText(activity))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(2, d), 0, 0)
        })

        return item
    }

    private fun queryDeviceVersions(device: RomDevice) {
        val loading = AlertDialog.Builder(activity)
            .setTitle("查询中")
            .setMessage("正在查询 ${device.name} 的 ROM 版本...")
            .setCancelable(false)
            .show()

        scope.launch {
            val versions = try {
                RomApi.fetchDeviceVersions(device.codename)
            } catch (e: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                runCatching { loading.dismiss() }
                if (activity.isFinishing) return@withContext

                if (versions.isEmpty()) {
                    AlertDialog.Builder(activity)
                        .setTitle("暂无数据")
                        .setMessage("未找到 ${device.name}（${device.codename}）的 ROM 版本信息。\n\n数据源：HyperOS.fans")
                        .setPositiveButton("确定", null)
                        .show()
                } else {
                    showVersionList(device, versions)
                }
            }
        }
    }

    private fun showVersionList(device: RomDevice, versions: List<RomVersion>) {
        val d = activity.resources.displayMetrics.density
        val view = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(400, d),
            )
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(8, d), Ui.dp(16, d), Ui.dp(8, d))
        }

        container.addView(TextView(activity).apply {
            text = device.name
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        container.addView(TextView(activity).apply {
            text = "代号：${device.codename}  ·  共 ${versions.size} 个版本"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(10, d))
        })

        versions.forEachIndexed { index, version ->
            val item = buildVersionItem(version, device)
            container.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) topMargin = Ui.dp(8, d)
            })
        }

        view.addView(container)

        AlertDialog.Builder(activity)
            .setTitle("ROM 版本列表")
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun buildVersionItem(version: RomVersion, device: RomDevice): View {
        val d = activity.resources.displayMetrics.density

        val item = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
            background = Ui.glassSurface(activity, 12f)
        }

        val row1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(TextView(activity).apply {
            text = version.version
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (version.branchName.isNotBlank()) {
            row1.addView(TextView(activity).apply {
                text = version.branchName
                textSize = 10f
                setTextColor(Ui.buttonText(activity))
                background = Ui.glassButton(activity, Ui.buttonPrimary(activity))
                setPadding(Ui.dp(6, d), Ui.dp(2, d), Ui.dp(6, d), Ui.dp(2, d))
            })
        }
        item.addView(row1)

        val info = buildString {
            if (version.region.isNotBlank()) append("区域: ${version.region}  ")
            if (version.androidVersion.isNotBlank()) append("安卓: ${version.androidVersion}")
        }
        if (info.isNotBlank()) {
            item.addView(TextView(activity).apply {
                text = info
                textSize = 11f
                setTextColor(Ui.secondaryText(activity))
                setPadding(0, Ui.dp(4, d), 0, 0)
            })
        }

        val info2 = buildString {
            if (version.releaseDate.isNotBlank()) append("发布: ${version.releaseDate}  ")
            if (version.securityPatch.isNotBlank()) append("补丁: ${version.securityPatch}")
        }
        if (info2.isNotBlank()) {
            item.addView(TextView(activity).apply {
                text = info2
                textSize = 10f
                setTextColor(Ui.secondaryText(activity))
                setPadding(0, Ui.dp(2, d), 0, 0)
            })
        }

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(8, d), 0, 0)
        }
        if (!version.recoveryFile.isNullOrBlank()) {
            btnRow.addView(buildDownloadBtn("Recovery 包", version.recoveryFile, version.version, device.name))
        }
        if (!version.fastbootFile.isNullOrBlank()) {
            btnRow.addView(buildDownloadBtn("Fastboot 包", version.fastbootFile, version.version, device.name).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = Ui.dp(8, d)
            })
        }
        if (btnRow.childCount > 0) {
            item.addView(btnRow)
        }

        return item
    }

    private fun buildDownloadBtn(label: String, filename: String, version: String, deviceName: String): View {
        val d = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = "下载 $label"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(10, d), Ui.dp(5, d), Ui.dp(10, d), Ui.dp(5, d))
            setOnClickListener {
                Haptics.perform(this)
                showDownloadConfigDialog(label, filename, version, deviceName)
            }
        }
    }

    // ========== 下载配置对话框 ==========

    private fun showDownloadConfigDialog(label: String, filename: String, version: String, deviceName: String) {
        val d = activity.resources.displayMetrics.density

        // 检查是否已有下载在进行
        if (DownloadService.isDownloading()) {
            Toast.makeText(activity, "已有下载正在进行中", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查存储权限
        if (!hasStoragePermission()) {
            AlertDialog.Builder(activity)
                .setTitle("需要存储权限")
                .setMessage("下载 ROM 固件需要存储权限以保存文件到 /sdcard/Downloads。\n\n请在接下来的设置中授予权限。")
                .setPositiveButton("去授权") { _, _ -> requestStoragePermission() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val nodes = DownloadNode.values()
        val nodeNames = nodes.map { it.displayName }.toTypedArray()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(16, d), Ui.dp(20, d), Ui.dp(16, d))
        }

        // 文件名
        val displayName = if (filename.endsWith(".zip", true) || filename.endsWith(".tgz", true)) filename else "$filename.zip"
        container.addView(TextView(activity).apply {
            text = "$deviceName - $label"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        container.addView(TextView(activity).apply {
            text = displayName
            textSize = 12f
            setTextColor(Ui.secondaryText(activity))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(2, d), 0, 0)
        })

        // 保存路径
        val saveDir = JavaDownloader.defaultSaveDir()
        container.addView(TextView(activity).apply {
            text = "保存到：${saveDir.absolutePath}"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        })

        // 下载节点选择
        container.addView(TextView(activity).apply {
            text = "下载节点"
            textSize = 12f
            setTextColor(Ui.primaryText(activity))
            setPadding(0, Ui.dp(12, d), 0, Ui.dp(6, d))
        })

        val nodeSpinner = Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                nodeNames,
            )
            setSelection(3) // 默认阿里云
            background = Ui.glassSurface(activity, 8f)
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
        }
        container.addView(nodeSpinner)

        // 后台下载提示
        container.addView(TextView(activity).apply {
            text = "支持后台下载，关闭页面后下载将继续进行"
            textSize = 10f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(12, d), 0, 0)
            gravity = Gravity.CENTER
        })

        AlertDialog.Builder(activity)
            .setTitle("$deviceName - $label")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始下载") { _, _ ->
                Haptics.perform(activity.window.decorView)
                startDownloadService(displayName, version, nodeSpinner.selectedItemPosition, label, deviceName)
            }
            .show()
    }

    private fun startDownloadService(filename: String, version: String, nodeIndex: Int, label: String, deviceName: String) {
        val nodes = DownloadNode.values()
        val node = nodes.getOrElse(nodeIndex) { nodes[3] }
        val downloadUrl = RomApi.getDownloadUrl(filename, version, node)

        // 灵动岛标题：设备名 + 包类型 + 节点
        val islandLabel = "$deviceName - $label"

        val intent = Intent(activity, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, downloadUrl)
            putExtra(DownloadService.EXTRA_FILENAME, filename)
            putExtra(DownloadService.EXTRA_VERSION, version)
            putExtra(DownloadService.EXTRA_NODE_INDEX, nodeIndex)
            putExtra(DownloadService.EXTRA_LABEL, islandLabel)
            putExtra(DownloadService.EXTRA_DEVICE_NAME, deviceName)
        }

        if (Build.VERSION.SDK_INT >= 26) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }

        // 立即显示灵动岛
        dynamicIsland.show(filename, deviceName, islandLabel)
    }
}
