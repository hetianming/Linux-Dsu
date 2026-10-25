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
    private lateinit var storageBar: MetricBarView
    private lateinit var memoryBar: MetricBarView
    private var cpuSample: Any? = null
    // 顶部头图卡片：自定义背景图（Env.background），自动居中裁切适配
    private var heroImage: ImageView? = null
    // 头图视频背景：默认循环播放的 mp4（自带动态效果），选择自定义图片时隐藏
    private var heroVideo: android.view.TextureView? = null
    private var heroMediaPlayer: android.media.MediaPlayer? = null
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
            releaseHeroVideo()
        })
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }
        // 标题行：左侧"首页"，右侧设置图标（置于最顶部）
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(10, d))
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

        // 两张卡分区：上「实时状态」（CPU/GPU + 存储/内存），下「运行环境」（ROOT/GSI/Linux/桌面）
        page.addView(buildStatusCard(d))
        page.addView(buildEnvCard(d))

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
    // ==================== 状态区：两张卡（实时状态 / 运行环境） ====================

    /** 实时状态卡：CPU/GPU 细线圆环 + 存储/内存横向进度条，刷新收进标题右侧 */
    private fun buildStatusCard(d: Float): LinearLayout {
        val card = com.mcai.ubuntudsu.ui.glass.TrueGlassPanel(activity, 24f, Ui.isDark(activity)).apply {
            setPadding(Ui.dp(14, d), Ui.dp(10, d), Ui.dp(14, d), Ui.dp(12, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(8, d) }
        }

        // 标题行：左「实时状态」，右刷新小图标（替代原来的底部整行按钮）
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(6, d))
        }
        head.addView(TextView(activity).apply {
            text = "实时状态"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(activity).apply {
            text = "\u27F3"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Ui.secondaryText(activity))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (Ui.isDark(activity)) Color.argb(26, 255, 255, 255) else Color.argb(38, 255, 255, 255))
                setStroke(
                    maxOf(1, Ui.dp(1, d)),
                    if (Ui.isDark(activity)) Color.argb(46, 255, 255, 255) else Color.argb(72, 255, 255, 255),
                )
            }
            setOnClickListener { refreshStatus() }
            Ui.pressAnimation(this)
        }, LinearLayout.LayoutParams(Ui.dp(30, d), Ui.dp(30, d)))
        card.addView(head)

        // CPU / GPU：细线圆环（夜间泛光/刻度已在 UsageGauge 中移除）
        val gauges = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, Ui.dp(6, d))
        }
        val cpuGauge = UsageGauge(activity, "CPU")
        val gpuGauge = UsageGauge(activity, "GPU")
        gauges.addView(gaugeColumn(cpuGauge, "CPU", d, true))
        gauges.addView(gaugeColumn(gpuGauge, "GPU", d, false))
        card.addView(gauges)

        // 存储 / 内存：横向进度条
        val storageRow = metricBar("存储", Ui.buttonPrimary(activity))
        storageValue = storageRow.value
        storageDetail = storageRow.detail
        storageBar = storageRow.bar
        card.addView(
            storageRow.root,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val memoryRow = metricBar("内存", Ui.buttonWarning(activity))
        memoryValue = memoryRow.value
        memoryDetail = memoryRow.detail
        memoryBar = memoryRow.bar
        card.addView(
            memoryRow.root,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(8, d) },
        )

        // 指标轮询（每 2 秒）
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
        // GPU 首次值依赖 root 探测（比 CPU 直读慢），页面就绪时另起轻线程立即探一次
        Thread {
            runCatching { com.mcai.ubuntudsu.core.StatusDetector.warmupGpuProbe() }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                gpuGauge.value = com.mcai.ubuntudsu.core.StatusDetector.currentGpuPercent()
            }
        }.start()
        activity.addLifecycleStopHook(Runnable { metricsHandler.removeCallbacks(updater) })

        return card
    }

    /** 圆环 + 下方标签（CPU / GPU 两列） */
    private fun gaugeColumn(gauge: UsageGauge, label: String, d: Float, first: Boolean): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (first) marginEnd = Ui.dp(8, d) else marginStart = Ui.dp(8, d)
            }
            addView(gauge, LinearLayout.LayoutParams(Ui.dp(62, d), Ui.dp(62, d)))
            addView(TextView(activity).apply {
                text = label
                textSize = 12.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Ui.secondaryText(activity))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(1, d) }
            })
        }

    /** 运行环境卡：设备摘要 + ROOT / GSI / Linux / 桌面 四行（圆点 + 左标签 + 右状态值） */
    private fun buildEnvCard(d: Float): LinearLayout {
        val card = com.mcai.ubuntudsu.ui.glass.TrueGlassPanel(activity, 24f, Ui.isDark(activity)).apply {
            setPadding(Ui.dp(14, d), Ui.dp(10, d), Ui.dp(14, d), Ui.dp(6, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        card.addView(TextView(activity).apply {
            text = "运行环境"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        deviceText = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(4, d))
        }
        card.addView(deviceText)

        val rootLine = statusLine("ROOT")
        rootDot = rootLine.dot
        rootLabel = rootLine.value
        card.addView(rootLine.row)

        val gsiLine = statusLine("GSI 系统")
        gsiDot = gsiLine.dot
        gsiText = gsiLine.value
        card.addView(gsiLine.row)

        val ubuntuLine = statusLine("Linux")
        ubuntuDot = ubuntuLine.dot
        ubuntuText = ubuntuLine.value
        card.addView(ubuntuLine.row)

        val desktopLine = statusLine("桌面环境")
        desktopDot = desktopLine.dot
        desktopText = desktopLine.value
        card.addView(desktopLine.row)

        return card
    }

    private class StatusLine(val row: View, val dot: View, val value: TextView)

    private fun statusLine(label: String): StatusLine {
        val d = activity.resources.displayMetrics.density
        val dot = Ui.statusDot(activity, Color.parseColor("#B0B0B0")).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(7, d), Ui.dp(7, d))
        }
        val name = TextView(activity).apply {
            text = label
            textSize = 12.5f
            setTextColor(Ui.primaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(9, d)
            }
        }
        val value = TextView(activity).apply {
            text = "检测中…"
            textSize = 11.5f
            gravity = Gravity.END
            setTextColor(Ui.secondaryText(activity))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(7, d), 0, Ui.dp(7, d))
        }
        row.addView(dot)
        row.addView(name)
        row.addView(value)
        return StatusLine(row, dot, value)
    }

    /** 横向进度条行：标题 + 百分比 + 5dp 圆角轨道 + 容量小字 */
    private class MetricRow(val root: View, val value: TextView, val detail: TextView, val bar: MetricBarView)

    private fun metricBar(title: String, color: Int): MetricRow {
        val d = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(activity).apply {
            text = title
            textSize = 12f
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = TextView(activity).apply {
            text = "--%"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        }
        top.addView(value)
        root.addView(top)
        val bar = MetricBarView(activity, color)
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(5, d)).apply {
                topMargin = Ui.dp(5, d)
            },
        )
        val detail = TextView(activity).apply {
            text = ""
            textSize = 9.5f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(2, d), 0, 0)
        }
        root.addView(detail)
        return MetricRow(root, value, detail, bar)
    }

    private class MetricBarView(context: android.content.Context, private val fill: Int) : View(context) {
        var percent: Int = 0
            set(value) {
                field = value.coerceIn(0, 100)
                invalidate()
            }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h / 2f
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = if (Ui.isDark(context)) Color.argb(36, 255, 255, 255) else Color.argb(46, 0, 0, 0)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
            if (percent > 0) {
                paint.color = fill
                canvas.drawRoundRect(0f, 0f, w * percent / 100f, h, r, r, paint)
            }
        }
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
                    storageBar.percent = metrics.storagePercent
                    memoryValue.text = "${metrics.memoryPercent}%"
                    memoryDetail.text = "${metrics.memoryUsed} / ${metrics.memoryTotal}"
                    memoryBar.percent = metrics.memoryPercent
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
                // ---- 夜间简洁模式：细轨道 + 渐变进度弧 + 中心数字（去泛光/刻度，降低视觉噪音） ----
                paint.shader = null
                paint.maskFilter = null
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                // 轨道
                paint.color = Color.argb(46, 255, 255, 255)
                paint.strokeWidth = size * 0.055f
                canvas.drawArc(ring, 0f, 360f, false, paint)
                // 进度弧
                if (percent > 0) {
                    paint.shader = android.graphics.SweepGradient(cx, cy, neon, null)
                    canvas.drawArc(ring, -90f, 3.6f * percent, false, paint)
                    paint.shader = null
                }
                // 中心数字
                paint.style = android.graphics.Paint.Style.FILL
                paint.textAlign = android.graphics.Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                paint.textSize = size * 0.26f
                paint.color = if (name == "CPU") Color.rgb(255, 205, 120) else Color.rgb(120, 232, 170)
                val text = if (value != null) "$percent%" else "--"
                val baseline = cy - (paint.ascent() + paint.descent()) / 2f
                canvas.drawText(text, cx, baseline, paint)
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
        val grayText = Ui.secondaryText(activity)
        gsiText.text = "检测中…"; gsiText.setTextColor(grayText)
        ubuntuText.text = "检测中…"; ubuntuText.setTextColor(grayText)
        desktopText.text = "检测中…"; desktopText.setTextColor(grayText)
        rootLabel.text = "检测中…"; rootLabel.setTextColor(grayText)
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
                            val okColor = android.graphics.Color.parseColor("#5CE1A5")
                            val idleColor = Ui.secondaryText(activity)
                            val hasDesktop = !desktopLabel.startsWith("未安装")
                            deviceText.text = device
                            gsiText.text = gsiLabel
                            gsiText.setTextColor(
                                when (gsiState) {
                                    com.mcai.ubuntudsu.core.GsiState.RUNNING,
                                    com.mcai.ubuntudsu.core.GsiState.INSTALLED,
                                    com.mcai.ubuntudsu.core.GsiState.ENABLED,
                                    -> okColor
                                    com.mcai.ubuntudsu.core.GsiState.DISABLED -> android.graphics.Color.parseColor("#FBBF24")
                                    else -> idleColor
                                },
                            )
                            ubuntuText.text = if (linuxInstalled) "已安装" else "未安装"
                            ubuntuText.setTextColor(if (linuxInstalled) okColor else idleColor)
                            desktopText.text = desktopLabel
                            desktopText.setTextColor(if (hasDesktop) okColor else idleColor)
                            rootLabel.text = if (rootOk) "已授权" else "未授权"
                            rootLabel.setTextColor(if (rootOk) okColor else idleColor)
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
            // 默认隐藏（视频背景生效）；仅当用户选了自定义图才显示
            visibility = if (loadHeroBitmap() != null) View.VISIBLE else View.INVISIBLE
        }

        // 视频背景层：TextureView 播放 mp4，铺满卡片；选自定义图时隐藏、显示 heroImage
        val videoView = android.view.TextureView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // 跟随卡片 22dp 圆角裁切，避免视频矩形溢出卡片圆角
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, Ui.dp(22, d).toFloat())
                }
            }
        }
        heroVideo = videoView
        hero.addView(videoView)      // 视频在底层
        hero.addView(heroImage)     // 自定义图在顶层（INVISIBLE 时透出视频）
        startHeroVideo(videoView)

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
            text = "更换背景（视频/图）"
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

        // 长按恢复默认：删除用户自定义视频/静图，回到内置视频动态背景
        hero.setOnLongClickListener {
            val uv = Env.backgroundVideo(activity)
            val uimg = Env.background(activity)
            if (uv.exists() || uimg.exists()) {
                resetHeroBackground()
                Toast.makeText(activity, "已恢复默认动态背景", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "当前已是默认动态背景", Toast.LENGTH_SHORT).show()
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

        // 活体效果改由背景视频自带（hero_video.mp4），不再叠加 2D 光斑/飞鸟层

        return hero
    }

    // 有图时文字白色（落在压暗渐变上），无图时跟随主题文字色
    private fun applyHeroTextColors(onImage: Boolean) {
        heroTitle?.setTextColor(if (onImage) Color.WHITE else Ui.primaryText(activity))
        heroSubtitle?.setTextColor(
            if (onImage) Color.argb(215, 255, 255, 255) else Ui.secondaryText(activity),
        )
    }

    // 启动头图视频背景：TextureView + MediaPlayer 循环静音播放，按原视频分辨率渲染，不做缩放裁切
    // 优先用用户自定义视频（Env.backgroundVideo），否则内置默认 hero_video
    private fun startHeroVideo(tex: android.view.TextureView) {
        if (heroMediaPlayer != null) return
        val mp = android.media.MediaPlayer()
        heroMediaPlayer = mp
        val userVideo = Env.backgroundVideo(activity)
        val sourcePath = if (userVideo.exists()) userVideo.path else null
        var prepared = false
        fun doPrepare() {
            if (prepared) return
            prepared = true
            runCatching {
                if (sourcePath != null) {
                    mp.setDataSource(sourcePath)
                } else {
                    val uri = android.net.Uri.parse("android.resource://${activity.packageName}/raw/${R.raw.hero_video}")
                    mp.setDataSource(activity, uri)
                }
                mp.isLooping = true
                mp.setOnPreparedListener { p ->
                    p.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    p.setVolume(0f, 0f)
                    p.start()
                }
                mp.prepare()
            }.onFailure {
                mp.release()
                heroMediaPlayer = null
                heroImage?.visibility = View.VISIBLE
            }
        }
        val st0 = tex.surfaceTexture
        if (st0 != null) {
            runCatching { mp.setSurface(android.view.Surface(st0)) }
            doPrepare()
        }
        tex.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                runCatching { mp.setSurface(android.view.Surface(st)) }
                doPrepare()
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = false
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
    }

    // 释放头图视频（生命周期 stop 时调用）
    private fun releaseHeroVideo() {
        heroMediaPlayer?.runCatching {
            try { stop() } catch (_: Exception) {}
            release()
        }
        heroMediaPlayer = null
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

    // 背景选择回调：按 MIME 分流——视频存 card_bg.mp4（隐藏静图、播放视频），图片存 card_bg.jpg（隐藏视频、显示静图）
    fun onHeroImagePicked(uri: android.net.Uri) {
        imageExecutor.execute {
            val mime = activity.contentResolver.getType(uri) ?: ""
            val isVideo = mime.startsWith("video/") || uri.path?.lowercase()?.endsWith(".mp4") == true
            val copied = runCatching {
                val target = if (isVideo) Env.backgroundVideo(activity) else Env.background(activity)
                // 切换类型时清掉另一种残留，保证单一背景源
                val other = if (isVideo) Env.background(activity) else Env.backgroundVideo(activity)
                if (other.exists()) other.delete()
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            activity.runOnUiThread {
                if (!copied) {
                    Toast.makeText(activity, "背景读取失败", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (isVideo) {
                    // 视频背景：隐藏静图，重播视频（用户视频优先）
                    heroImage?.visibility = View.INVISIBLE
                    heroVideo?.visibility = View.VISIBLE
                    startHeroVideoSafe()
                    applyHeroTextColors(true)
                } else {
                    // 静态图背景：隐藏视频，显示静图
                    val bmp = loadHeroBitmap()
                    if (bmp != null) {
                        heroImage?.setImageBitmap(bmp)
                        heroImage?.visibility = View.VISIBLE
                        heroVideo?.visibility = View.INVISIBLE
                        stopHeroVideo()
                        heroScrim?.visibility = View.VISIBLE
                        applyHeroTextColors(true)
                    } else {
                        Toast.makeText(activity, "图片读取失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 恢复默认背景（长按）：删除用户自定义的视频和静图，回到内置视频
    private fun resetHeroBackground() {
        val uv = Env.backgroundVideo(activity)
        val uimg = Env.background(activity)
        if (uv.exists()) uv.delete()
        if (uimg.exists()) uimg.delete()
        heroImage?.visibility = View.INVISIBLE
        heroVideo?.visibility = View.VISIBLE
        startHeroVideoSafe()
        applyHeroTextColors(true)
    }

    // 恢复视频背景（长按删除自定义图后调用）
    private fun startHeroVideoSafe() {
        runCatching {
            heroMediaPlayer?.release()
            heroMediaPlayer = null
            heroVideo?.let { startHeroVideo(it) }
        }
    }

    // 停止并释放视频（选自定义图或页面 stop 时调用）
    private fun stopHeroVideo() {
        runCatching {
            heroMediaPlayer?.stop()
            heroMediaPlayer?.release()
        }
        heroMediaPlayer = null
    }
}
