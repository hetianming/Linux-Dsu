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
import com.mcai.ubuntudsu.core.YuleRomEntry
import com.mcai.ubuntudsu.core.DownloadNode
import com.mcai.ubuntudsu.core.JavaDownloader
import com.mcai.ubuntudsu.service.DownloadService
import com.mcai.ubuntudsu.DownloadsActivity
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
    private lateinit var tabXiaomi: TextView
    private lateinit var tabMultiBrand: TextView
    private lateinit var brandRow: LinearLayout
    private lateinit var multiBrandScroll: ScrollView
    private lateinit var xiaomiScroll: ScrollView
    private lateinit var deviceRow: LinearLayout
    private lateinit var deviceSelectBtn: TextView
    private lateinit var deviceLabel: TextView

    private var activeTab = 0 // 0=小米固件 1=欧加固件

    private var allDevices = emptyList<RomDevice>()
    private var filteredDevices = emptyList<RomDevice>()
    private var allYuleEntries = emptyList<YuleRomEntry>()
    private var filteredYuleEntries = emptyList<YuleRomEntry>()
    private var activeBrand = "Meizu"
    private val brandOptions = listOf("Meizu", "OPPO", "OnePlus", "Realme")
    /** 单选设备：null 表示显示当前品牌下全部设备 */
    private var selectedDevice: String? = null
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

        // ===== 标题栏（标题真正居中） =====
        val titleRow = FrameLayout(activity).apply {
            setPadding(0, 0, 0, Ui.dp(12, d))
        }
        titleRow.addView(TextView(activity).apply {
            text = "ROM固件"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        titleRow.addView(TextView(activity).apply {
            text = "下载管理"
            textSize = 12.5f
            setTextColor(Ui.buttonText(activity))
            gravity = Gravity.CENTER
            background = Ui.glassButton(activity, Ui.buttonSuccess(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(10, d), Ui.dp(6, d), Ui.dp(10, d), Ui.dp(6, d))
            setOnClickListener {
                Haptics.perform(this)
                activity.startActivity(Intent(activity, DownloadsActivity::class.java))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        })
        page.addView(titleRow)

        // ===== 当前设备卡片 =====
        val currentCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14, d), Ui.dp(12, d), Ui.dp(14, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            // 圆角 outline 投影：裸 elevation 对 LayerDrawable 背景会渲染成方形影子
            Ui.applyNeuShadow(this, 3f, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(10, d) }
        }
        currentCard.addView(TextView(activity).apply {
            text = "当前设备"
            textSize = 11.5f
            setTextColor(Ui.secondaryText(activity))
        })
        currentDeviceText = TextView(activity).apply {
            text = "${RomApi.getCurrentDeviceModel()}（${RomApi.getCurrentDeviceCodename()}）"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.buttonSuccess(activity))
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        currentCard.addView(currentDeviceText)
        page.addView(currentCard)

        // ===== 搜索框 =====
        val searchBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(8, d), Ui.dp(12, d), Ui.dp(8, d))
            background = Ui.glassSurface(activity, 14f)
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
            addTextChangedListener { text ->
                val q = text?.toString() ?: ""
                if (activeTab == 0) filterDevices(q) else filterMultiBrand(q)
            }
        }
        searchBox.addView(searchInput)
        page.addView(searchBox)

        // ===== Tab 切换：小米固件 / 欧加固件 =====
        val tabBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(8, d))
        }
        tabXiaomi = buildTab("HyperOS固件", 0)
        tabMultiBrand = buildTab("ColorOS FlymeOS realme UI 固件", 1)
        tabBar.addView(tabXiaomi)
        tabBar.addView(tabMultiBrand, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = Ui.dp(8, d) })
        page.addView(tabBar)

        // 品牌筛选行（仅多品牌 Tab）
        brandRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, Ui.dp(6, d))
        }
        for (brand in brandOptions) {
            brandRow.addView(buildBrandChip(brand))
        }
        page.addView(brandRow)

        // 设备筛选行（仅多品牌 Tab）：选择设备按钮 + 当前所选设备标签，位于品牌行下方
        deviceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, Ui.dp(6, d))
        }
        deviceSelectBtn = TextView(activity).apply {
            text = "选择设备"
            textSize = 12f
            setTextColor(Ui.buttonText(activity))
            setPadding(Ui.dp(10, d), Ui.dp(5, d), Ui.dp(10, d), Ui.dp(5, d))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.perform(this)
                showDevicePicker()
            }
        }
        deviceRow.addView(deviceSelectBtn)
        deviceLabel = TextView(activity).apply {
            text = ""
            textSize = 11.5f
            setTextColor(Ui.secondaryText(activity))
            setPadding(Ui.dp(8, d), 0, 0, 0)
        }
        deviceRow.addView(deviceLabel)
        page.addView(deviceRow)

        // 状态文字
        statusText = TextView(activity).apply {
            text = "加载中..."
            textSize = 11.5f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        }
        page.addView(statusText)

        // ===== 小米固件列表 =====
        xiaomiScroll = ScrollView(activity).apply {
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
        xiaomiScroll.addView(deviceListContainer)
        page.addView(xiaomiScroll)

        // ===== 欧加固件列表 =====
        multiBrandScroll = ScrollView(activity).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val multiBrandContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        multiBrandScroll.addView(multiBrandContainer)
        page.addView(multiBrandScroll)

        // 将内容添加到根容器
        root.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // 默认 Tab：小米固件
        updateTabStyles()

        // 加载小米设备列表
        loadDevices()
        // 预加载多品牌固件
        loadMultiBrandFirmware()

        return root
    }

    // ========== Tab 切换 ==========

    private fun buildTab(label: String, index: Int): TextView {
        val d = activity.resources.displayMetrics.density
        val active = index == 0
        val tv = TextView(activity).apply {
            text = label
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(tabTextColor(active))
            setPadding(Ui.dp(14, d), Ui.dp(7, d), Ui.dp(14, d), Ui.dp(7, d))
            background = tabBackground(active, d)
            Ui.pressAnimation(this)
            isClickable = true
            isFocusable = true
        }
        tv.setOnClickListener {
            Haptics.perform(tv)
            switchTab(index)
        }
        return tv
    }

    /** Tab/品牌 chip 选中背景：实色填充 + 高亮描边；未选中：面底色 + 边框（与 DSU 容量选择一致） */
    private fun tabBackground(active: Boolean, d: Float): android.graphics.drawable.GradientDrawable {
        val fill = if (active) Ui.buttonSuccess(activity) else Ui.surface(activity)
        val stroke = if (active) Ui.buttonSuccess(activity) else Ui.border(activity)
        return Ui.strokeRounded(fill, stroke, 1f, d, 14f)
    }

    /** 选中态文字：白；未选中：主文字色（保证实色填充上可读） */
    private fun tabTextColor(active: Boolean): Int {
        return if (active) Color.WHITE else Ui.primaryText(activity)
    }

    private fun buildBrandChip(brand: String): TextView {
        val d = activity.resources.displayMetrics.density
        val active = brand == activeBrand
        val tv = TextView(activity).apply {
            text = brand
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(tabTextColor(active))
            setPadding(Ui.dp(10, d), Ui.dp(5, d), Ui.dp(10, d), Ui.dp(5, d))
            background = tabBackground(active, d)
            Ui.pressAnimation(this)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (brand != brandOptions.first()) marginStart = Ui.dp(6, d)
            }
        }
        tv.setOnClickListener {
            Haptics.perform(tv)
            activeBrand = brand
            // 切换品牌时清空所选设备，避免跨品牌残留过滤
            selectedDevice = null
            updateDeviceLabel()
            filterMultiBrand(searchInput.text?.toString() ?: "")
            updateBrandChipStyles()
        }
        return tv
    }

    private fun updateBrandChipStyles() {
        val d = activity.resources.displayMetrics.density
        for (i in 0 until brandRow.childCount) {
            val chip = brandRow.getChildAt(i) as? TextView ?: continue
            val isActive = chip.text.toString() == activeBrand
            chip.setTextColor(tabTextColor(isActive))
            chip.background = tabBackground(isActive, d)
        }
    }

    private fun switchTab(index: Int) {
        if (index == activeTab) return
        activeTab = index
        val isXiaomi = index == 0
        // 只显示当前 Tab 对应的 ScrollView，避免另一个 weight=1 的空视图占据屏幕造成大片空白
        if (isXiaomi) {
            xiaomiScroll.visibility = View.VISIBLE
            multiBrandScroll.visibility = View.GONE
            brandRow.visibility = View.GONE
            deviceRow.visibility = View.GONE
            if (allDevices.isEmpty()) loadDevices()
        } else {
            xiaomiScroll.visibility = View.GONE
            multiBrandScroll.visibility = View.VISIBLE
            brandRow.visibility = View.VISIBLE
            deviceRow.visibility = View.VISIBLE
            if (allYuleEntries.isEmpty()) loadMultiBrandFirmware()
            updateBrandChipStyles()
        }
        updateTabStyles()
        statusText.text = if (isXiaomi) {
            if (allDevices.isNotEmpty()) "共 ${allDevices.size} 个设备" else "加载中..."
        } else {
            if (allYuleEntries.isNotEmpty()) "共 ${allYuleEntries.size} 个固件" else "加载中..."
        }
    }

    private fun updateTabStyles() {
        val d = activity.resources.displayMetrics.density
        for ((tv, idx) in listOf(tabXiaomi to 0, tabMultiBrand to 1)) {
            val active = idx == activeTab
            tv.setTextColor(tabTextColor(active))
            tv.background = tabBackground(active, d)
        }
    }

    private fun loadMultiBrandFirmware() {
        if (allYuleEntries.isNotEmpty()) return
        statusText.text = if (activeTab == 1) "正在加载欧加固件..." else statusText.text
        scope.launch {
            val result = RomApi.fetchYuleRomList()
            allYuleEntries = result.entries
            filteredYuleEntries = result.entries
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (activeTab == 1) {
                    renderMultiBrandList(filteredYuleEntries, result.error)
                    statusText.text = when {
                        result.entries.isNotEmpty() -> "共 ${result.entries.size} 个固件"
                        result.error != null -> "加载失败：${result.error}"
                        else -> "未获取到固件数据"
                    }
                    updateBrandChipStyles()
                }
            }
        }
    }

    private fun filterMultiBrand(query: String = "") {
        val q = query.trim().lowercase()
        val selDevice = selectedDevice?.lowercase()
        filteredYuleEntries = allYuleEntries.filter { entry ->
            // 品牌精确匹配 + 设备单选（可选）
            val brandMatch = entry.brand.equals(activeBrand, ignoreCase = true)
            val deviceMatch = selDevice == null || entry.device.lowercase() == selDevice
            val searchMatch = q.isEmpty() ||
                entry.device.lowercase().contains(q) ||
                entry.brand.lowercase().contains(q) ||
                entry.version.lowercase().contains(q)
            brandMatch && deviceMatch && searchMatch
        }
        if (activeTab == 1) {
            renderMultiBrandList(filteredYuleEntries)
            statusText.text = if (filteredYuleEntries.isEmpty()) "未找到匹配的固件" else "找到 ${filteredYuleEntries.size} 个固件"
        }
    }

    /** 设备单选弹窗：按当前品牌分组展示，点选后过滤固件列表 */
    private fun showDevicePicker() {
        if (allYuleEntries.isEmpty()) {
            Toast.makeText(activity, "固件数据加载中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        // 仅展示当前激活品牌下的设备
        val devices = allYuleEntries
            .filter { it.brand.equals(activeBrand, ignoreCase = true) }
            .map { it.device }
            .distinct()
            .sortedWith(deviceOrderComparator())
        if (devices.isEmpty()) {
            Toast.makeText(activity, "当前品牌暂无设备", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = devices.toTypedArray()
        val checked = selectedDevice?.let { sel -> labels.indexOfFirst { it == sel }.takeIf { it >= 0 } } ?: -1
        AlertDialog.Builder(activity)
            .setTitle("选择设备 - ${activeBrand}")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                Haptics.perform(activity.window.decorView)
                selectedDevice = labels[which]
                updateDeviceLabel()
                filterMultiBrand()
                dialog.dismiss()
            }
            .setNeutralButton("全部设备") { _, _ ->
                selectedDevice = null
                updateDeviceLabel()
                filterMultiBrand()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateDeviceLabel() {
        deviceLabel.text = if (selectedDevice != null) "：$selectedDevice" else "：全部设备"
        deviceLabel.setTextColor(if (selectedDevice != null) Ui.buttonSuccess(activity) else Ui.secondaryText(activity))
    }

    /**
     * 设备排序：数字系列机型（如 OP 15 / 16、OPPO A6、Realme 14）排前并按系列号从大到小；
     * 非数字系列机型（如折叠 Find N、OPPO Find X 等字母型号）排后，按名称字母序。
     */
    private fun deviceOrderComparator(): Comparator<String> {
        return Comparator { a, b ->
            val na = seriesNumber(a)
            val nb = seriesNumber(b)
            when {
                na > 0 && nb > 0 -> {
                    // 两者都是数字系列：系列号大的（更新的）在前
                    if (na != nb) nb.compareTo(na) else a.lowercase().compareTo(b.lowercase())
                }
                na > 0 -> -1   // a 是数字系列，b 不是 → a 在前
                nb > 0 -> 1    // b 是数字系列，a 不是 → b 在前
                else -> a.lowercase().compareTo(b.lowercase())
            }
        }
    }

    /** 从设备名提取数字系列号：取第一个 >=10 的独立整数（15/16 等），无则返回 0 */
    private fun seriesNumber(device: String): Int {
        val m = Regex("(?:^|\\D)(\\d{2,})(?:\\D|$)").find(device)?.groupValues?.getOrNull(1)?.toInt() ?: 0
        return if (m >= 10) m else 0
    }

    private fun renderMultiBrandList(entries: List<YuleRomEntry>, errorHint: String? = null) {
        val d = activity.resources.displayMetrics.density
        val container = multiBrandScroll.getChildAt(0) as LinearLayout
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = if (errorHint != null) "暂无固件数据\n\n失败原因：$errorHint\n\n提示：请确认网络可访问 rom.yule.ink" else "暂无固件数据"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Ui.secondaryText(activity))
                setPadding(Ui.dp(16, d), Ui.dp(30, d), Ui.dp(16, d), Ui.dp(30, d))
            })
            return
        }
        // 按版本号数字段从新到旧排序（同设备下高版本在上）；
        // buildTimestamp 多为空，不能用时间戳，改用 version 数字段比较
        val sorted = entries.sortedWith(buildVersionComparator())
        for (entry in sorted) {
            container.addView(buildMultiBrandItem(entry))
        }
    }

    /** 先按版本号数字段降序，再按时间戳降序，再按设备名/区域升序 */
    private fun buildVersionComparator(): Comparator<YuleRomEntry> {
        val versionDesc = Comparator<YuleRomEntry> { a, b ->
            val va = versionNumber(a.version)
            val vb = versionNumber(b.version)
            val max = va.size.coerceAtLeast(vb.size)
            for (i in 0 until max) {
                // 对齐位数：缺失段按 0 处理
                val x = if (i < va.size) va[i] else 0L
                val y = if (i < vb.size) vb[i] else 0L
                if (x != y) return@Comparator y.compareTo(x) // 降序
            }
            0
        }
        val tsDesc = Comparator<YuleRomEntry> { a, b -> parseTimestamp(b.buildTimestamp).compareTo(parseTimestamp(a.buildTimestamp)) }
        val deviceAsc = Comparator.comparing<YuleRomEntry, String> { it.device.lowercase() }
        val regionAsc = Comparator.comparing<YuleRomEntry, String> { it.region.lowercase() }
        return versionDesc.then(tsDesc).then(deviceAsc).then(regionAsc)
    }

    /** 把 "15.0.2.901(EX01)" 这类版本号解析成可比较大小的 Long 列表（主.次.修.构建） */
    private fun versionNumber(v: String): List<Long> {
        // 去掉区域后缀括号，取数字段
        val core = v.substringBefore('(').trim()
        val parts = core.split('.', '-', '_', ' ').map { it.toLongOrNull() ?: 0L }
        return if (parts.isEmpty()) listOf(0L) else parts
    }

    private fun parseTimestamp(s: String): Long {
        if (s.isBlank()) return 0L
        return try { java.time.Instant.parse(s).toEpochMilli() }
        catch (_: Exception) { s.toLongOrNull() ?: 0L }
    }

    private fun buildMultiBrandItem(entry: YuleRomEntry): View {
        val d = activity.resources.displayMetrics.density
        val item = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(12, d), Ui.dp(7, d))
            background = Ui.glassSurface(activity, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(6, d) }
        }

        val row1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(TextView(activity).apply {
            text = entry.device
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row1.addView(TextView(activity).apply {
            text = entry.brand
            textSize = 9f
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonSuccess(activity))
            setPadding(Ui.dp(5, d), Ui.dp(1, d), Ui.dp(5, d), Ui.dp(1, d))
        })
        item.addView(row1)

        if (entry.version.isNotBlank()) {
            item.addView(TextView(activity).apply {
                text = entry.version
                textSize = 10f
                setTextColor(Ui.secondaryText(activity))
                setPadding(0, Ui.dp(2, d), 0, 0)
            })
        }

        val metaLine = buildString {
            if (entry.sizeText.isNotBlank()) append(entry.sizeText)
            if (entry.region.isNotBlank()) {
                if (isNotEmpty()) append("  ")
                append("区域: ${entry.region}")
            }
        }
        if (metaLine.isNotBlank()) {
            item.addView(TextView(activity).apply {
                text = metaLine
                textSize = 9f
                setTextColor(Ui.secondaryText(activity))
                setPadding(0, Ui.dp(1, d), 0, 0)
            })
        }

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow.addView(TextView(activity).apply {
            text = "下载"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            setOnClickListener {
                Haptics.perform(this)
                resolveAndDownload(entry)
            }
        })
        btnRow.addView(TextView(activity).apply {
            text = "复制链接"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = Ui.dp(6, d) }
            setOnClickListener {
                Haptics.perform(this)
                copyLink(entry)
            }
        })
        item.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(5, d) })

        return item
    }

    /** resolve 临时链接并复制到系统剪贴板 */
    private fun copyLink(entry: YuleRomEntry) {
        val loading = AlertDialog.Builder(activity)
            .setTitle("正在获取下载链接")
            .setMessage("正在获取 ${entry.device} 的临时 ROM 链接...")
            .setCancelable(false)
            .show()
        scope.launch {
            val resolved = RomApi.resolveYuleDownload(entry)
            withContext(Dispatchers.Main) {
                runCatching { loading.dismiss() }
                if (activity.isFinishing) return@withContext
                val url = resolved?.url
                if (url.isNullOrBlank()) {
                    Toast.makeText(activity, "获取下载链接失败", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newRawUri(
                    "${entry.device}_${entry.version}",
                    android.net.Uri.parse(url),
                ))
                val exp = resolved.expiresAt
                val msg = if (exp.isNotBlank()) "下载链接已复制（有效期 $exp）" else "下载链接已复制"
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveAndDownload(entry: YuleRomEntry) {
        val loading = AlertDialog.Builder(activity)
            .setTitle("正在获取下载链接")
            .setMessage("正在获取 ${entry.device} 的临时 ROM 链接...")
            .setCancelable(false)
            .show()
        scope.launch {
            val resolved = RomApi.resolveYuleDownload(entry)
            withContext(Dispatchers.Main) {
                runCatching { loading.dismiss() }
                if (activity.isFinishing) return@withContext
                if (resolved != null && resolved.url.isNotBlank()) {
                    startYuleDownload(resolved.url, entry)
                } else {
                    Toast.makeText(activity, "获取下载链接失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 欧加固件走内置 aria2c 引擎下载，并登记到下载管理列表 */
    private fun startYuleDownload(url: String, entry: YuleRomEntry) {
        val filename = java.net.URL(url).path.split('/').last().takeIf { it.isNotBlank() }
            ?: "${entry.device}_${entry.version}.zip"
        val label = entry.brand.ifBlank { "欧加" }
        val intent = Intent(activity, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_FILENAME, filename)
            putExtra(DownloadService.EXTRA_VERSION, "")
            putExtra(DownloadService.EXTRA_NODE_INDEX, 3)
            putExtra(DownloadService.EXTRA_LABEL, label)
            putExtra(DownloadService.EXTRA_DEVICE_NAME, entry.device)
            // 直链：走内置 aria2c 引擎（16 连接分块 + 断点续传）
            putExtra(DownloadService.EXTRA_USE_ARIA2, true)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
        DownloadsActivity.addTask(DownloadsActivity.Companion.DownloadTask(
            id = filename,
            fileName = filename,
            deviceName = "${entry.device} - ${entry.version}",
            status = "准备下载",
            state = 0,
            url = url,
            startTime = System.currentTimeMillis(),
        ))
        Toast.makeText(activity, "已添加到下载管理，可继续下载或后台续传", Toast.LENGTH_SHORT).show()
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
                        // Download complete notification shown in DownloadsActivity
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
                                // Downloading - updates shown in DownloadsActivity
                            }
                            2 -> { // DONE
                                Toast.makeText(activity, "下载完成: $fileName", Toast.LENGTH_SHORT).show()
                            }
                            3 -> { // CANCELLED
                                Toast.makeText(activity, "下载已取消", Toast.LENGTH_SHORT).show()
                            }
                            4 -> { // FAILED
                                Toast.makeText(activity, "下载失败: $status", Toast.LENGTH_SHORT).show()
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
            setTextColor(Ui.buttonSuccess(activity))
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
            background = Ui.glassSurface(activity, 14f)
            // 圆角 outline 投影：裸 elevation 对 LayerDrawable 背景会渲染成方形影子
            Ui.applyNeuShadow(this, 1.5f, 12f)
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
            setTextColor(if (isCurrentDevice) Ui.buttonSuccess(activity) else Ui.primaryText(activity))
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
            .setMessage("正在查询 ${device.name} 的固件版本...")
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
                        .setMessage("未找到 ${device.name}（${device.codename}）的固件版本信息。\n\n数据源：HyperOS.fans")
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
            .setTitle("固件版本列表")
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun buildVersionItem(version: RomVersion, device: RomDevice): View {
        val d = activity.resources.displayMetrics.density

        val item = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
            background = Ui.glassSurface(activity, 14f)
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
            background = Ui.glassButton(activity, Ui.buttonSuccess(activity))
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

        val intent = Intent(activity, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, downloadUrl)
            putExtra(DownloadService.EXTRA_FILENAME, filename)
            putExtra(DownloadService.EXTRA_VERSION, version)
            putExtra(DownloadService.EXTRA_NODE_INDEX, nodeIndex)
            putExtra(DownloadService.EXTRA_LABEL, label)
            putExtra(DownloadService.EXTRA_DEVICE_NAME, deviceName)
        }

        if (Build.VERSION.SDK_INT >= 26) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }

        // 记录到下载管理器
        DownloadsActivity.addTask(DownloadsActivity.Companion.DownloadTask(
            id = filename,
            fileName = filename,
            deviceName = "$deviceName - $label",
            status = "准备下载",
            state = 0,
        ))

        Toast.makeText(activity, "已添加到下载队列", Toast.LENGTH_SHORT).show()
    }
}
