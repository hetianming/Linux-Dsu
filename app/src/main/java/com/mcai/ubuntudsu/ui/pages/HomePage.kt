package com.mcai.ubuntudsu.ui.pages

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mcai.ubuntudsu.MainActivity
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HomePage(
    private val activity: MainActivity,
) {
    private lateinit var deviceText: TextView
    private lateinit var gsiText: TextView
    private lateinit var gsiDot: View
    private lateinit var ubuntuText: TextView
    private lateinit var ubuntuDot: View
    private lateinit var desktopText: TextView
    private lateinit var desktopDot: View
    private lateinit var rootDot: View
    private lateinit var rootLabel: TextView
    private lateinit var storageValue: TextView
    private lateinit var storageDetail: TextView
    private lateinit var memoryValue: TextView
    private lateinit var memoryDetail: TextView
    private var cpuSample: Any? = null
    // 合并后的整张拼接卡（含实时状态 + 检测信息两个分区）
    private var unifiedCard: LinearLayout? = null
    // 顶部头图卡片：自定义背景图（Env.background），自动居中裁切适配
    private var heroImage: ImageView? = null
    private var heroScrim: View? = null
    private var heroTitle: TextView? = null
    private var heroSubtitle: TextView? = null
    // 头图左上角：电池/温度双列状态卡（样式对齐参考图，数据实时读 sysfs）
    private var batteryIcon: BatteryIconView? = null
    private var chargeStateText: TextView? = null
    private var tempValueText: TextView? = null
    private var tempStatePill: TextView? = null
    // sysfs 直读被限制时是否可用 root 兜底（缓存探测结果）
    @Volatile private var rootBatteryFallback: Boolean? = null
    // 首页不能和 Linux/DSU 的耗时任务共用 MainActivity 单线程队列：
    // 否则 GPU/ROOT 探测超时后会把“ROOT/GSI/Linux/桌面”永久堵在“检测中”。
    private val metricsExecutor = Executors.newSingleThreadExecutor()
    private val statusExecutor = Executors.newSingleThreadExecutor()
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private val metricsRunning = AtomicBoolean(false)
    private val statusRunning = AtomicBoolean(false)
    private val statusGeneration = AtomicLong(0L)

    fun build(): View {
        val d = activity.resources.displayMetrics.density
        activity.addLifecycleStopHook(Runnable {
            metricsExecutor.shutdownNow()
            statusExecutor.shutdownNow()
            imageExecutor.shutdownNow()
        })
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }
        // 标题行：左侧"首页"，右侧设置图标（置于最顶部）
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(14, d))
        }
        titleRow.addView(TextView(activity).apply {
            text = "首页"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(Ui.settingsIconButton(activity) { Ui.showThemeDialog(activity) { activity.recreate() } })
        page.addView(titleRow)

        // 顶部头图卡片：真玻璃面板 + 自定义背景图
        page.addView(buildHeroCard(d))

        // 上次崩溃信息（若有）
        showCrashIfAny(page)

        // 单张拼接卡：上分区实时状态，下分区检测信息，中间水晶玻璃分隔条
        val unified = buildUnifiedCard(d)
        unifiedCard = unified
        page.addView(unified)

        // MainActivity 的首次 onResume 发生在 root.post 懒加载首页之前，
        // 因此 onResume 中的 homePage?.refreshStatus() 会被跳过。页面就绪后主动启动首轮检测。
        refreshStatus()
        return page
    }

    /**
     * 圆角 ViewOutlineProvider（供裁剪/涟漪用）。
     */
    private class ViewOutlineProviderRounded(private val radiusPx: Int) : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx.toFloat())
        }
    }

    private fun showCrashIfAny(page: LinearLayout) {
        val d = activity.resources.displayMetrics.density
        val file = java.io.File(activity.filesDir, "crash.log")
        val content = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(18, d), Ui.dp(14, d), Ui.dp(18, d), Ui.dp(12, d))
            background = Ui.glassSurface(activity, 18f)
            // 圆角 outline 投影：裸 elevation 对 LayerDrawable 背景会渲染成方形影子
            Ui.applyNeuShadow(this, 3f, 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(12, d) }
        }
        val head = content.lineSequence().take(6).joinToString("\n")
        card.addView(TextView(activity).apply {
            text = "上次异常退出"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#EF454A"))
        })
        card.addView(TextView(activity).apply {
            text = head
            textSize = 10f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(4, d), 0, Ui.dp(6, d))
        })
        val buttons = LinearLayout(activity)
        buttons.addView(TextView(activity).apply {
            text = "复制完整日志"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
            Ui.pressAnimation(this)
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(36, d), 1f).apply { marginEnd = Ui.dp(4, d) }
            setOnClickListener {
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", content))
                android.widget.Toast.makeText(activity, "已复制崩溃日志", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(TextView(activity).apply {
            text = "清除"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonDanger(activity))
            Ui.pressAnimation(this)
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(36, d), 1f).apply { marginStart = Ui.dp(4, d) }
            setOnClickListener {
                runCatching { file.delete() }
                page.removeView(card)
            }
        })
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(card)
    }

    // 拼接一体卡：实时状态分区 + 水晶分隔条 + 检测信息分区
    private fun buildUnifiedCard(d: Float): LinearLayout {
        val card = com.mcai.ubuntudsu.ui.glass.TrueGlassPanel(activity, 26f, Ui.isDark(activity)).apply {
            setPadding(Ui.dp(18, d), Ui.dp(14, d), Ui.dp(18, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // 实时状态
        card.addView(TextView(activity).apply {
            text = "实时状态"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, 0, 0, Ui.dp(3, d))
        })
        val gauges = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cpuGauge = UsageGauge(activity, "CPU")
        val gpuGauge = UsageGauge(activity, "GPU")
        // CPU：圆圈在上、标签在下
        gauges.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(8, d) }
            addView(cpuGauge, LinearLayout.LayoutParams(Ui.dp(84, d), Ui.dp(84, d)))
            addView(TextView(activity).apply {
                text = "CPU"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Ui.secondaryText(activity))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(2, d) }
            })
        })
        // GPU：圆圈在上、标签在下
        gauges.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(8, d) }
            addView(gpuGauge, LinearLayout.LayoutParams(Ui.dp(84, d), Ui.dp(84, d)))
            addView(TextView(activity).apply {
                text = "GPU"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Ui.secondaryText(activity))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(2, d) }
            })
        })
        card.addView(gauges)
        val details = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val storageCard = detailMetric("存储", Ui.buttonPrimary(activity))
        val memoryCard = detailMetric("内存", Ui.buttonSecondary(activity))
        storageValue = storageCard.first
        storageDetail = storageCard.second
        memoryValue = memoryCard.first
        memoryDetail = memoryCard.second
        details.addView(storageCard.third, LinearLayout.LayoutParams(0, Ui.dp(62, d), 1f).apply { marginEnd = Ui.dp(4, d) })
        details.addView(memoryCard.third, LinearLayout.LayoutParams(0, Ui.dp(62, d), 1f).apply { marginStart = Ui.dp(4, d) })
        card.addView(details)

        // wire metrics polling
        val metricsHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                updateMetrics { cpu, gpu ->
                    cpuGauge.value = cpu
                    gpuGauge.value = gpu
                }
                metricsHandler.postDelayed(this, 2000L)
            }
        }
        metricsHandler.post(updater)
        // GPU 首次值依赖 root 探测（比 CPU 直读慢），页面就绪时另起轻线程立即探一次，
        // 不占用 metricsExecutor（避免拖累 CPU 首值节奏）
        Thread {
            runCatching { com.mcai.ubuntudsu.core.StatusDetector.warmupGpuProbe() }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                gpuGauge.value = com.mcai.ubuntudsu.core.StatusDetector.currentGpuPercent()
            }
        }.start()
        activity.addLifecycleStopHook(Runnable { metricsHandler.removeCallbacks(updater) })

        // 水晶玻璃分隔条
        card.addView(Ui.crystalDivider(activity, d), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(2, d),
        ).apply { topMargin = Ui.dp(10, d); bottomMargin = Ui.dp(8, d) })

        // 下分区：检测信息
        deviceText = TextView(activity).apply {
            textSize = 12f
            setTextColor(Ui.secondaryText(activity))
        }
        card.addView(deviceText)
        // ROOT 权限状态行：与 GSI/Linux 行同款边框胶囊 + 14f 字号，前置状态点
        val rootRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(10, d), Ui.dp(4, d), Ui.dp(10, d), Ui.dp(4, d))
            background = Ui.rounded(
                if (Ui.isDark(activity)) android.graphics.Color.argb(68, 0, 0, 0) else android.graphics.Color.argb(78, 255, 255, 255),
                8f,
                d,
            )
            layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        rootDot = Ui.statusDot(activity, android.graphics.Color.parseColor("#B0B0B0")).apply {
            val size = Ui.dp(8, d)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        rootLabel = TextView(activity).apply {
            text = "ROOT：检测中…"
            textSize = 14f
            setTextColor(Ui.primaryText(activity))
            layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(6, d)
            }
        }
        rootRow.addView(rootDot)
        rootRow.addView(rootLabel)
        card.addView(rootRow)
        // 与下方状态行保持相同行距
        (rootRow.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = Ui.dp(6, d)
        val statusColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        val gsiPill = statusRow("GSI 系统", "检测中...")
        gsiText = gsiPill.text
        gsiDot = gsiPill.dot
        val ubuntuPill = statusRow("Linux", "检测中...")
        ubuntuText = ubuntuPill.text
        ubuntuDot = ubuntuPill.dot
        val desktopPill = statusRow("桌面", "检测中...")
        desktopText = desktopPill.text
        desktopDot = desktopPill.dot
        statusColumn.addView(gsiPill.row)
        statusColumn.addView(ubuntuPill.row)
        statusColumn.addView(desktopPill.row)
        card.addView(statusColumn)
        card.addView(
            TextView(activity).apply {
                text = "刷新状态"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Ui.buttonText(activity))
                background = Ui.glassButton(activity, Ui.buttonSecondary(activity))
                Ui.pressAnimation(this)
                setOnClickListener { refreshStatus() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(32, d)).apply { topMargin = Ui.dp(6, d) },
        )
        return card
    }

    // 状态胶囊行：边框胶囊 + 前置状态点 + 文字（ROOT / GSI / Linux 三行同款）
    private class StatusPillRow(val row: View, val dot: View, val text: TextView)

    private fun statusRow(label: String, value: String): StatusPillRow {
        val d = activity.resources.displayMetrics.density
        val dot = Ui.statusDot(activity, android.graphics.Color.parseColor("#B0B0B0")).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(8, d), Ui.dp(8, d))
        }
        val text = TextView(activity).apply {
            text = "$label：$value"
            textSize = 14f
            setTextColor(Ui.primaryText(activity))
            layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(6, d)
            }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(10, d), Ui.dp(4, d), Ui.dp(10, d), Ui.dp(4, d))
            background = Ui.rounded(
                if (Ui.isDark(activity)) android.graphics.Color.argb(68, 0, 0, 0) else android.graphics.Color.argb(78, 255, 255, 255),
                8f,
                d,
            )
            layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(6, d)
            }
        }
        row.addView(dot)
        row.addView(text)
        return StatusPillRow(row, dot, text)
    }

    private fun updateMetrics(onResult: (Int?, Int?) -> Unit) {
        // GPU 节点在未授权 ROOT 设备上可能需要数秒超时；跳过重入，避免 2 秒轮询无限堆积。
        if (!metricsRunning.compareAndSet(false, true)) return
        metricsExecutor.execute {
            try {
                val result = com.mcai.ubuntudsu.core.StatusDetector.deviceMetrics(activity, cpuSample)
                cpuSample = result.second
                val metrics = result.first
                val battery = readBatteryData()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    storageValue.text = "${metrics.storagePercent}%"
                    storageDetail.text = "${metrics.storageUsed} / ${metrics.storageTotal}"
                    memoryValue.text = "${metrics.memoryPercent}%"
                    memoryDetail.text = "${metrics.memoryUsed} / ${metrics.memoryTotal}"
                    batteryIcon?.level = battery.level ?: -1
                    batteryIcon?.charging = battery.charging
                    chargeStateText?.text = battery.statusLabel
                    tempValueText?.let { tv ->
                        if (battery.temp != null) {
                            tv.text = String.format(java.util.Locale.US, "%.1f°C", battery.temp)
                            tv.setTextColor(if (battery.temp > 40.0) Color.rgb(255, 92, 92) else Color.rgb(255, 214, 64))
                        } else {
                            tv.text = "--.-°C"
                            tv.setTextColor(Color.argb(200, 255, 255, 255))
                        }
                    }
                    tempStatePill?.let { pill ->
                        val hot = battery.temp != null && battery.temp > 40.0
                        pill.text = if (hot) "过热" else "正常"
                        pill.background = Ui.rounded(
                            if (hot) Color.argb(235, 226, 68, 56) else Color.argb(235, 46, 170, 108),
                            9f,
                            activity.resources.displayMetrics.density,
                        )
                    }
                    onResult(metrics.cpuPercent, metrics.gpuPercent)
                }
            } finally {
                metricsRunning.set(false)
            }
        }
    }

    /** 电池实时数据：电量 / 充电状态 / 温度。 */
    private class BatteryData(
        val level: Int?,
        val statusLabel: String,
        val charging: Boolean,
        val temp: Double?,
    )

    /** 读取电池数据：优先直读 sysfs；部分机型 SELinux 拦截直读时，合并一条 root cat 兜底（单次 su 开销）。 */
    private fun readBatteryData(): BatteryData {
        val base = "/sys/class/power_supply/battery"
        fun direct(path: String) = runCatching { java.io.File(path).readText().trim() }.getOrNull() ?: ""
        var cap = direct("$base/capacity")
        var sta = direct("$base/status")
        var tmp = direct("$base/temp")
        if (cap.isEmpty() || sta.isEmpty() || tmp.isEmpty()) {
            // 仅在 ROOT 可用时走 root 兜底；结果缓存，避免每 2s 轮询反复探测 su。
            val fallback = rootBatteryFallback ?: run {
                val ok = runCatching { com.mcai.ubuntudsu.core.RootShell.available(timeoutMs = 3000) }.getOrDefault(false)
                rootBatteryFallback = ok
                ok
            }
            if (fallback) {
                val result = com.mcai.ubuntudsu.core.RootShell.exec(
                    "cat $base/capacity 2>/dev/null; echo ---; cat $base/status 2>/dev/null; echo ---; " +
                        "cat $base/temp 2>/dev/null || cat /sys/class/power_supply/bms/temp 2>/dev/null",
                    timeoutMs = 5000,
                )
                if (result.success) {
                    val parts = result.stdout.replace("\r", "").trim().split("---")
                    if (parts.size >= 3) {
                        if (cap.isEmpty()) cap = parts[0].trim()
                        if (sta.isEmpty()) sta = parts[1].trim()
                        if (tmp.isEmpty()) tmp = parts[2].trim()
                    }
                }
            }
            if (tmp.isEmpty()) tmp = direct("/sys/class/power_supply/bms/temp")
        }
        val level = cap.toIntOrNull()?.takeIf { it in 0..100 }
        val statusLabel = when (sta.lowercase()) {
            "charging" -> "充电中"
            "full" -> "已充满"
            "discharging" -> "放电中"
            "not charging" -> "未充电"
            else -> if (sta.isEmpty()) "--" else sta
        }
        val temp = tmp.toDoubleOrNull()?.let { raw ->
            if (raw.isNaN()) null else {
                val c = if (raw > 80.0 || raw < -20.0) raw / 10.0 else raw
                if (c in -20.0..80.0) c else null
            }
        }
        return BatteryData(level, statusLabel, sta.equals("charging", true), temp)
    }

    private fun detailMetric(title: String, color: Int): Triple<TextView, TextView, View> {
        val d = activity.resources.displayMetrics.density
        val valueView = TextView(activity).apply {
            text = "读取中..."
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 7, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }
        val detailView = TextView(activity).apply {
            text = ""
            textSize = 9f
            setTextColor(Ui.secondaryText(activity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 7, 9, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(8, d), Ui.dp(4, d), Ui.dp(8, d), Ui.dp(3, d))
            background = Ui.glassSurface(activity, 14f)
            addView(TextView(activity).apply {
                text = title
                textSize = 11f
                setTextColor(Ui.secondaryText(activity))
                gravity = android.view.Gravity.CENTER
            })
            addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(detailView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return Triple(valueView, detailView, card)
    }

    /** 电池图标：白色描边外壳 + 按电量比例填充，百分比文字内嵌（参考图同款）。 */
    private class BatteryIconView(context: android.content.Context) : View(context) {
        var level: Int = -1
            set(value) { field = value; invalidate() }
        var charging: Boolean = false
            set(value) { field = value; invalidate() }

        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(2f, 0f, 1f, Color.argb(160, 0, 0, 0))
        }

        private fun fillColor(): Int = when {
            charging || level >= 50 -> Color.rgb(76, 217, 123)
            level >= 20 -> Color.rgb(251, 191, 36)
            else -> Color.rgb(255, 92, 92)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val nubW = w * 0.08f
            val bodyW = w - nubW
            val stroke = h * 0.11f
            val radius = h * 0.24f

            // 外壳描边
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = Color.argb(240, 255, 255, 255)
            canvas.drawRoundRect(stroke / 2, stroke / 2, bodyW - stroke / 2, h - stroke / 2, radius, radius, paint)

            // 正极凸头
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(bodyW + stroke * 0.4f, h * 0.32f, w, h * 0.68f, stroke, stroke, paint)

            // 电量填充
            if (level in 0..100) {
                val inset = stroke * 1.7f
                val availW = (bodyW - stroke / 2) - inset
                val fillW = availW * level.coerceIn(0, 100) / 100f
                paint.color = fillColor()
                canvas.drawRoundRect(inset, inset, inset + fillW, h - inset, radius * 0.55f, radius * 0.55f, paint)
            }

            // 百分比内嵌文字
            if (level in 0..100) {
                textPaint.textSize = h * 0.52f
                val label = "$level%"
                val baseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(label, (bodyW - stroke / 2) / 2f, baseline, textPaint)
            }
        }
    }

    /** 温度计图标：白色轮廓 + 红色汞柱（参考图同款）。 */
    private class ThermoIconView(context: android.content.Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val bulbR = w * 0.5f
            val bulbCy = h - bulbR

            // 白色轮廓
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = Color.argb(240, 255, 255, 255)
            canvas.drawRoundRect(cx - w * 0.42f, 0f, cx + w * 0.42f, bulbCy + bulbR * 0.4f, w * 0.42f, w * 0.42f, paint)
            canvas.drawCircle(cx, bulbCy, bulbR, paint)

            // 红色汞柱 + 汞泡
            paint.color = Color.rgb(255, 82, 70)
            canvas.drawRoundRect(cx - w * 0.19f, h * 0.10f, cx + w * 0.19f, bulbCy + bulbR * 0.2f, w * 0.19f, w * 0.19f, paint)
            canvas.drawCircle(cx, bulbCy, bulbR * 0.62f, paint)
        }
    }

    private class UsageGauge(
        context: android.content.Context,
        private val name: String,
    ) : View(context) {
        var value: Int? = null
            set(newValue) {
                field = newValue
                invalidate()
            }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        /** 渐变端点：(浅色, 深色)。日间 CPU 蓝 / GPU 紫；夜间由霓虹多色接管。 */
        private fun gradient(): Pair<Int, Int> {
            val dark = Ui.isDark(context)
            return if (name == "CPU") {
                if (dark) Color.rgb(255, 210, 70) to Color.rgb(255, 80, 40)
                else Color.rgb(130, 185, 255) to Color.rgb(45, 100, 245)
            } else {
                if (dark) Color.rgb(120, 235, 150) to Color.rgb(0, 200, 95)
                else Color.rgb(185, 150, 255) to Color.rgb(120, 74, 255)
            }
        }

        /** 夜间霓虹多色渐变（沿弧顺时针一圈）：CPU 亮黄→橙→红，GPU 鲜绿，避开呼吸气球的粉/蓝/紫色系；日间返回 null 走普通样式。 */
        private fun neonColors(): IntArray? {
            if (!Ui.isDark(context)) return null
            return if (name == "CPU") intArrayOf(
                Color.rgb(255, 215, 70),
                Color.rgb(255, 150, 45),
                Color.rgb(255, 80, 40),
                Color.rgb(255, 150, 45),
            ) else intArrayOf(
                Color.rgb(150, 255, 180),
                Color.rgb(60, 225, 130),
                Color.rgb(0, 195, 90),
                Color.rgb(60, 225, 130),
            )
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val size = minOf(width, height).toFloat()
            val dark = Ui.isDark(context)
            val (lightC, darkC) = gradient()
            val percent = (value ?: 0).coerceIn(0, 100)
            val neon = neonColors()
            val radius = size * 0.34f
            val stroke = size * 0.075f
            val ring = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            if (dark && neon != null) {
                // ---- 夜间霓虹模式（参考图同款高强度：泛光外晕 + 常亮轨道 + 白热管芯） ----
                val sweepShader = { android.graphics.SweepGradient(cx, cy, neon, null) }
                // 1. 光晕：径向渐变从中间色淡出
                val glowC = neon[2]
                paint.style = android.graphics.Paint.Style.FILL
                paint.shader = android.graphics.RadialGradient(
                    cx, cy, size * 0.48f,
                    intArrayOf(
                        Color.argb(120, Color.red(glowC), Color.green(glowC), Color.blue(glowC)),
                        Color.argb(46, Color.red(glowC), Color.green(glowC), Color.blue(glowC)),
                        Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(cx, cy, size * 0.48f, paint)
                paint.shader = null

                // 2. 刻度点：扫描渐变按角度取色，模糊晕 + 亮芯两程
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeCap = android.graphics.Paint.Cap.BUTT
                val tickOuter = size * 0.47f
                val tickInner = size * 0.435f
                canvas.save()
                canvas.rotate(-90f, cx, cy)
                paint.shader = sweepShader()
                paint.maskFilter = android.graphics.BlurMaskFilter(size * 0.022f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                paint.strokeWidth = size * 0.030f
                paint.alpha = 210
                for (i in 0 until 60) {
                    val angle = Math.toRadians((i * 6).toDouble())
                    val cos = Math.cos(angle).toFloat()
                    val sin = Math.sin(angle).toFloat()
                    canvas.drawLine(
                        cx + cos * tickOuter, cy + sin * tickOuter,
                        cx + cos * tickInner, cy + sin * tickInner,
                        paint,
                    )
                }
                paint.maskFilter = null
                paint.strokeWidth = size * 0.013f
                paint.alpha = 255
                for (i in 0 until 60) {
                    val angle = Math.toRadians((i * 6).toDouble())
                    val cos = Math.cos(angle).toFloat()
                    val sin = Math.sin(angle).toFloat()
                    canvas.drawLine(
                        cx + cos * tickOuter, cy + sin * tickOuter,
                        cx + cos * tickInner, cy + sin * tickInner,
                        paint,
                    )
                }
                paint.shader = null
                canvas.restore()

                // 3. 轨道：整圈常亮霓虹管（晕 + 管体）
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.shader = sweepShader()
                paint.strokeWidth = stroke * 1.5f
                paint.alpha = 80
                paint.maskFilter = android.graphics.BlurMaskFilter(stroke * 0.55f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                canvas.drawArc(ring, -90f, 360f, false, paint)
                paint.maskFilter = null
                paint.strokeWidth = stroke * 0.95f
                paint.alpha = 165
                canvas.drawArc(ring, -90f, 360f, false, paint)
                paint.alpha = 255
                paint.shader = null

                // 4. 进度弧：强泛光外晕 → 聚焦内晕 → 饱和亮芯 → 白热管芯
                if (percent > 0) {
                    canvas.save()
                    canvas.rotate(-90f, cx, cy)
                    paint.shader = sweepShader()
                    paint.strokeCap = android.graphics.Paint.Cap.ROUND
                    paint.strokeWidth = stroke * 2.4f
                    paint.maskFilter = android.graphics.BlurMaskFilter(stroke * 1.1f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    paint.alpha = 175
                    canvas.drawArc(ring, 0f, 3.6f * percent, false, paint)
                    paint.strokeWidth = stroke * 1.5f
                    paint.maskFilter = android.graphics.BlurMaskFilter(stroke * 0.45f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    paint.alpha = 225
                    canvas.drawArc(ring, 0f, 3.6f * percent, false, paint)
                    paint.maskFilter = null
                    paint.strokeWidth = stroke * 0.9f
                    paint.alpha = 255
                    canvas.drawArc(ring, 0f, 3.6f * percent, false, paint)
                    // 白热管芯（沿弧更亮的近白线）
                    paint.shader = null
                    paint.color = if (name == "CPU") Color.argb(225, 255, 244, 214) else Color.argb(225, 224, 255, 238)
                    paint.strokeWidth = stroke * 0.30f
                    canvas.drawArc(ring, 0f, 3.6f * percent, false, paint)
                    canvas.restore()
                }

                // 5. 数字：辉光模糊 + 亮芯双程
                val numC = if (name == "CPU") Color.rgb(255, 225, 95) else Color.rgb(120, 255, 165)
                paint.style = android.graphics.Paint.Style.FILL
                paint.textAlign = android.graphics.Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                paint.textSize = size * 0.28f
                paint.color = numC
                val text = if (value != null) "$percent%" else "--"
                val valueBaseline = cy - (paint.ascent() + paint.descent()) / 2f
                paint.maskFilter = android.graphics.BlurMaskFilter(size * 0.028f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                paint.alpha = 200
                canvas.drawText(text, cx, valueBaseline, paint)
                paint.maskFilter = null
                paint.alpha = 255
                canvas.drawText(text, cx, valueBaseline, paint)
                return
            }

            // ---- 日间普通模式 ----
            // 1. 光晕背景：径向渐变从淡彩到透明
            val haloRadius = size * 0.48f
            paint.style = android.graphics.Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(
                cx, cy, haloRadius,
                intArrayOf(
                    Color.argb(if (dark) 56 else 64, Color.red(lightC), Color.green(lightC), Color.blue(lightC)),
                    Color.argb(8, Color.red(lightC), Color.green(lightC), Color.blue(lightC)),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, haloRadius, paint)
            paint.shader = null

            // 2. 表盘刻度：外围短刻度线（60 格，时钟式）
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = size * 0.012f
            paint.strokeCap = android.graphics.Paint.Cap.BUTT
            paint.color = Color.argb(if (dark) 170 else 110, Color.red(lightC), Color.green(lightC), Color.blue(lightC))
            val tickOuter = size * 0.47f
            val tickInner = size * 0.44f
            for (i in 0 until 60) {
                val angle = Math.toRadians((-90 + i * 6).toDouble())
                val cos = Math.cos(angle).toFloat()
                val sin = Math.sin(angle).toFloat()
                canvas.drawLine(
                    cx + cos * tickOuter, cy + sin * tickOuter,
                    cx + cos * tickInner, cy + sin * tickInner,
                    paint,
                )
            }

            // 3. 轨道环（淡色底环）+ 渐变进度弧
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.strokeWidth = stroke
            paint.color = if (dark) Color.argb(90, Color.red(lightC), Color.green(lightC), Color.blue(lightC)) else Color.argb(70, Color.red(lightC), Color.green(lightC), Color.blue(lightC))
            canvas.drawArc(ring, -90f, 360f, false, paint)
            if (percent > 0) {
                paint.shader = android.graphics.SweepGradient(cx, cy, lightC, darkC)
                canvas.drawArc(ring, -90f, 3.6f * percent, false, paint)
                paint.shader = null
            }

            // 4. 中间大数字：用深色端点，夜间用浅色端点保证可读
            paint.style = android.graphics.Paint.Style.FILL
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = size * 0.28f
            paint.color = if (dark) lightC else darkC
            val text = if (value != null) "$percent%" else "--"
            val valueBaseline = cy - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(text, cx, valueBaseline, paint)
        }
    }

    fun refreshStatus() {
        val ctx = activity
        statusGeneration.incrementAndGet()
        gsiText.text = "GSI 系统：检测中..."
        ubuntuText.text = "Linux：检测中..."
        desktopText.text = "桌面：检测中..."
        rootLabel.text = "ROOT：检测中…"
        fun grayDot(view: View) {
            view.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#B0B0B0"))
            }
        }
        grayDot(gsiDot)
        grayDot(ubuntuDot)
        grayDot(desktopDot)
        grayDot(rootDot)
        scheduleStatusRefresh(ctx)
    }

    /** 状态刷新独占轻量队列；请求合并后只渲染最新一次，避免旧结果覆盖新状态。 */
    private fun scheduleStatusRefresh(ctx: MainActivity) {
        if (!statusRunning.compareAndSet(false, true)) return
        statusExecutor.execute {
            var renderedGeneration = -1L
            try {
                while (true) {
                    val generation = statusGeneration.get()
                    val device = com.mcai.ubuntudsu.core.StatusDetector.deviceSummary()
                    // 先探测 ROOT，再把结果传给 GSI 检测，避免无 ROOT 时重复启动 su 并长时间阻塞。
                    val rootOk = com.mcai.ubuntudsu.core.StatusDetector.rootAvailable(timeoutMs = 5_000L)
                    val gsiState = com.mcai.ubuntudsu.core.StatusDetector.gsiState(rootOk).first
                    val linuxInstalled = Env.ubuntuInstalled(ctx)
                    val desktopLabel = Env.desktopState(ctx)
                    if (generation == statusGeneration.get()) {
                        val gsiLabel = when (gsiState) {
                            com.mcai.ubuntudsu.core.GsiState.RUNNING -> "运行中"
                            com.mcai.ubuntudsu.core.GsiState.INSTALLED -> "已安装"
                            com.mcai.ubuntudsu.core.GsiState.ENABLED -> "已启用"
                            com.mcai.ubuntudsu.core.GsiState.DISABLED -> "已停用"
                            com.mcai.ubuntudsu.core.GsiState.NORMAL -> "未安装"
                            com.mcai.ubuntudsu.core.GsiState.UNKNOWN -> "未检测到"
                        }
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed || generation != statusGeneration.get()) return@runOnUiThread
                            fun dotColor(colorHex: String) = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(android.graphics.Color.parseColor(colorHex))
                            }
                            deviceText.text = device
                            gsiText.text = "GSI 系统：$gsiLabel"
                            ubuntuText.text = if (linuxInstalled) "Linux：已安装" else "Linux：未安装"
                            desktopText.text = "桌面：$desktopLabel"
                            rootLabel.text = if (rootOk) "ROOT：已授权" else "ROOT：未授权"
                            rootDot.background = dotColor(if (rootOk) "#5CE1A5" else "#FF756F")
                            // GSI 点：运行/安装/启用=绿，停用=琥珀，未安装/未知=灰
                            gsiDot.background = dotColor(
                                when (gsiState) {
                                    com.mcai.ubuntudsu.core.GsiState.RUNNING,
                                    com.mcai.ubuntudsu.core.GsiState.INSTALLED,
                                    com.mcai.ubuntudsu.core.GsiState.ENABLED,
                                    -> "#5CE1A5"
                                    com.mcai.ubuntudsu.core.GsiState.DISABLED -> "#FBBF24"
                                    else -> "#B0B0B0"
                                },
                            )
                            ubuntuDot.background = dotColor(if (linuxInstalled) "#5CE1A5" else "#B0B0B0")
                            val hasDesktop = !desktopLabel.startsWith("未安装")
                            desktopDot.background = dotColor(if (hasDesktop) "#5CE1A5" else "#B0B0B0")
                        }
                        renderedGeneration = generation
                    }
                    if (generation == statusGeneration.get()) break
                }
            } finally {
                statusRunning.set(false)
                // 检测完成后正好有新请求到达时，再启动一轮，保证不遗留“检测中”。
                if (renderedGeneration != statusGeneration.get() && !activity.isFinishing && !activity.isDestroyed) {
                    scheduleStatusRefresh(ctx)
                }
            }
        }
    }

    // ==================== 顶部头图卡片 ====================

    /** 头图卡片：默认拟态玻璃底；选择图片后 CENTER_CROP 自动裁切适配，长按恢复默认 */
    private fun buildHeroCard(d: Float): View {
        val hero = FrameLayout(activity).apply {
            background = Ui.neuCard(activity, 22f)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, Ui.dp(22, d).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(150, d),
            ).apply { bottomMargin = Ui.dp(14, d) }
        }

        heroImage = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            loadHeroBitmap()?.let { setImageBitmap(it) }
                ?: setImageResource(R.drawable.hero_default) // 内置默认壁纸
        }
        hero.addView(heroImage)

        // 左上角：电池/温度双列状态卡（宽度对齐下方"Ubuntu DSU"标题，左右两列等宽居中）
        val battCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 2f
            setPadding(Ui.dp(10, d), Ui.dp(6, d), Ui.dp(10, d), Ui.dp(6, d))
            background = Ui.rounded(Color.argb(125, 8, 12, 24), 12f, d)
            layoutParams = FrameLayout.LayoutParams(
                Ui.dp(108, d),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { marginStart = Ui.dp(12, d); topMargin = Ui.dp(12, d) }
        }

        // 左列：电池图标（电量内嵌）+ 充电状态
        val leftCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        batteryIcon = BatteryIconView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(38, d), Ui.dp(16, d))
        }
        leftCol.addView(batteryIcon)
        chargeStateText = TextView(activity).apply {
            text = "读取中"
            textSize = 9f
            setTextColor(Color.argb(225, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(2, d), 0, 0)
        }
        leftCol.addView(chargeStateText)
        battCard.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 右列：温度计图标 + 温度值 / 状态徽章
        val rightCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(4, d), 0, 0, 0)
        }
        val tempRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tempRow.addView(ThermoIconView(activity), LinearLayout.LayoutParams(Ui.dp(7, d), Ui.dp(14, d)))
        tempValueText = TextView(activity).apply {
            text = "--.-°C"
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(255, 214, 64))
            setPadding(Ui.dp(3, d), 0, 0, 0)
            isSingleLine = true
        }
        tempRow.addView(tempValueText)
        rightCol.addView(tempRow)
        tempStatePill = TextView(activity).apply {
            text = "正常"
            textSize = 9f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(6, d), Ui.dp(1, d), Ui.dp(6, d), Ui.dp(1, d))
            background = Ui.rounded(Color.argb(235, 46, 170, 108), 9f, d)
        }
        rightCol.addView(tempStatePill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Ui.dp(2, d) })
        battCard.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        hero.addView(battCard)

        // 底部渐变压暗：保证白色文案可读（默认壁纸与自定义图都需要）
        heroScrim = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(165, 7, 11, 22)),
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(72, d), Gravity.BOTTOM,
            )
        }
        hero.addView(heroScrim)

        // 左下文案：应用名 + 口号
        val textBlock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { marginStart = Ui.dp(16, d); bottomMargin = Ui.dp(14, d) }
        }
        heroTitle = TextView(activity).apply {
            text = "Ubuntu DSU"
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        heroSubtitle = TextView(activity).apply {
            text = "口袋里的 Linux 工作站 · 天明研发版"
            textSize = 11f
            setPadding(0, Ui.dp(2, d), 0, 0)
        }
        textBlock.addView(heroTitle)
        textBlock.addView(heroSubtitle)
        hero.addView(textBlock)

        // 右下「更换背景」玻璃胶囊：唤起系统图片选择器
        val chip = TextView(activity).apply {
            text = "更换背景"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(activity, Ui.buttonPrimary(activity))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply { marginEnd = Ui.dp(12, d); bottomMargin = Ui.dp(12, d) }
            Ui.pressAnimation(this)
            setOnClickListener { activity.pickHeroImage() }
        }
        hero.addView(chip)

        // 长按恢复内置默认壁纸（有自定义图时删除并回退）
        hero.setOnLongClickListener {
            val file = Env.background(activity)
            if (file.exists() && file.delete()) {
                heroImage?.setImageResource(R.drawable.hero_default)
                applyHeroTextColors(true)
                Toast.makeText(activity, "已恢复默认壁纸", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "当前已是默认壁纸", Toast.LENGTH_SHORT).show()
            }
            true
        }

        applyHeroTextColors(true) // 默认壁纸/自定义图均为深色底，文字恒白

        // 右上角小风车：无底容器，叶片高饱和色直接显示，持续旋转
        val windmillIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_windmill)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                Ui.dp(34, d), Ui.dp(34, d),
                Gravity.TOP or Gravity.END,
            ).apply {
                marginEnd = Ui.dp(14, d); topMargin = Ui.dp(6, d)
            }
        }
        hero.addView(windmillIcon)
        android.animation.ObjectAnimator.ofFloat(windmillIcon, "rotation", 0f, 360f).apply {
            duration = 3000L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }

        return hero
    }

    // 有图时文字白色（落在压暗渐变上），无图时跟随主题文字色
    private fun applyHeroTextColors(onImage: Boolean) {
        heroTitle?.setTextColor(if (onImage) Color.WHITE else Ui.primaryText(activity))
        heroSubtitle?.setTextColor(
            if (onImage) Color.argb(215, 255, 255, 255) else Ui.secondaryText(activity),
        )
    }

    // 解码头图：按卡片尺寸降采样，避免大图内存压力
    private fun loadHeroBitmap(): android.graphics.Bitmap? {
        return runCatching {
            val file = Env.background(activity)
            if (!file.exists()) return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1200) sample *= 2
            android.graphics.BitmapFactory.decodeFile(
                file.path,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

    // 图片选择回调：拷贝到应用私有目录（持久保存），刷新头图
    fun onHeroImagePicked(uri: android.net.Uri) {
        imageExecutor.execute {
            val copied = runCatching {
                val target = Env.background(activity)
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            activity.runOnUiThread {
                val bmp = if (copied) loadHeroBitmap() else null
                if (bmp != null) {
                    heroImage?.setImageBitmap(bmp)
                    heroImage?.visibility = View.VISIBLE
                    heroScrim?.visibility = View.VISIBLE
                    applyHeroTextColors(true)
                } else {
                    Toast.makeText(activity, "图片读取失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
