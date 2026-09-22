package com.mcai.ubuntudsu.ui.pages

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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
import java.util.concurrent.Executor

class HomePage(
    private val activity: MainActivity,
    private val executor: Executor,
) {
    private lateinit var deviceText: TextView
    private lateinit var gsiText: TextView
    private lateinit var gsiDot: View
    private lateinit var ubuntuText: TextView
    private lateinit var ubuntuDot: View
    private lateinit var rootDot: View
    private lateinit var rootLabel: TextView
    private lateinit var storageValue: TextView
    private lateinit var storageDetail: TextView
    private lateinit var memoryValue: TextView
    private lateinit var memoryDetail: TextView
    private var cpuSample: Any? = null
    // 合并后的整张拼接卡（含实时状态 + 检测信息两个分区）
    private var unifiedCard: LinearLayout? = null
    // 当前渐变档位；默认 0 = 薄荷绿渐变
    private var gradientIndex = 0
    // 顶部头图卡片：自定义背景图（Env.background），自动居中裁切适配
    private var heroImage: ImageView? = null
    private var heroScrim: View? = null
    private var heroTitle: TextView? = null
    private var heroSubtitle: TextView? = null

    fun build(): View {
        val d = activity.resources.displayMetrics.density
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
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(Ui.settingsIconButton(activity) { Ui.showThemeDialog(activity) { activity.recreate() } })
        page.addView(titleRow)

        // 顶部头图卡片：支持自定义背景图，长按恢复默认拟态底
        page.addView(buildHeroCard(d))

        // 上次崩溃信息（若有）
        showCrashIfAny(page)

        // 单张拼接卡：上分区实时状态，下分区检测信息，中间水晶玻璃分隔条
        val unified = buildUnifiedCard(d)
        unifiedCard = unified
        attachBackgroundGestures(unified)
        // 初始即应用默认渐变（薄荷绿）
        applyGradient()
        page.addView(unified)
        return page
    }

    // 渐变档位：浅色/深色各自一组半透明渐变，叠加白/黑内衬保证文字可读
    // 默认三档为薄荷绿 / 青绿 / 清新绿，后续档位为彩色循环
    private fun gradientPresets(): List<Pair<Int, Int>> {
        val dark = Ui.isDark(activity)
        return if (dark) {
            listOf(
                Color.parseColor("#CC1F3A2E") to Color.parseColor("#CC1F2E4E"),
                Color.parseColor("#CC14401F") to Color.parseColor("#CC1F4E3A"),
                Color.parseColor("#CC1F4E3A") to Color.parseColor("#CC1F4E50"),
                Color.parseColor("#33361FCC") to Color.parseColor("#331F4ECC"),
                Color.parseColor("#CC2E1F33") to Color.parseColor("#CC331F55"),
                Color.parseColor("#CC1F3333") to Color.parseColor("#CC331F55"),
                Color.parseColor("#CC4E1F50") to Color.parseColor("#CC50331F"),
                Color.parseColor("#CC1F4E50") to Color.parseColor("#CC1F3ACC"),
            )
        } else {
            listOf(
                Color.parseColor("#F2B8FFF2") to Color.parseColor("#F2A0F0E0"),
                Color.parseColor("#F2BAE6FF") to Color.parseColor("#F2A0E0D0"),
                Color.parseColor("#F2BAFFF0") to Color.parseColor("#F2C8FFB0"),
                Color.parseColor("#F2FFE3BA") to Color.parseColor("#F2FFD7B0"),
                Color.parseColor("#F2BAE0FF") to Color.parseColor("#F2B0C8FF"),
                Color.parseColor("#F2E0BAFF") to Color.parseColor("#F2D7B0FF"),
                Color.parseColor("#F2FFBABA") to Color.parseColor("#F2FFD0B0"),
                Color.parseColor("#F2B0FFC8") to Color.parseColor("#F2D0FFE0"),
            )
        }
    }

    // 单击切换到下一档渐变，双击恢复默认（薄荷绿）
    private fun attachBackgroundGestures(card: View) {
        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val presets = gradientPresets()
                gradientIndex = (gradientIndex + 1).let { if (it >= presets.size) 0 else it }
                applyGradient()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                gradientIndex = 0
                applyGradient()
                Toast.makeText(activity, "已恢复默认背景", Toast.LENGTH_SHORT).show()
                return true
            }
        })
        card.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            v.performClick()
            true
        }
    }

    // 按当前档位应用渐变；-1 恢复默认 glassSurface
    // 渐变档位同样叠加拟态高光/阴影环，保持全局拟态质感
    private fun applyGradient() {
        val card = unifiedCard ?: return
        if (gradientIndex < 0) {
            card.background = Ui.glassSurface(activity, 20f)
            return
        }
        val (top, bottom) = gradientPresets()[gradientIndex]
        card.background = android.graphics.drawable.LayerDrawable(
            arrayOf(
                // 用户选择的渐变填充
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
                    cornerRadius = Ui.dp(20, activity.resources.displayMetrics.density).toFloat()
                },
                // 左上高光内环（拟态受光边）
                GradientDrawable().apply {
                    cornerRadius = Ui.dp(20, activity.resources.displayMetrics.density).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(
                        Ui.dp(1, activity.resources.displayMetrics.density),
                        if (Ui.isDark(activity)) Color.argb(62, 168, 214, 255) else Color.argb(200, 255, 255, 255),
                    )
                },
                // 右下阴影外环（拟态背光边）
                GradientDrawable().apply {
                    cornerRadius = Ui.dp(21, activity.resources.displayMetrics.density).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(
                        Ui.dp(2, activity.resources.displayMetrics.density),
                        if (Ui.isDark(activity)) Color.argb(120, 6, 10, 24) else Color.argb(70, 150, 168, 198),
                    )
                },
            ),
        )
    }

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
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(18, d), Ui.dp(14, d), Ui.dp(18, d), Ui.dp(14, d))
            background = Ui.glassSurface(activity, 20f)
            // 圆角 outline 投影：裸 elevation 对 LayerDrawable 背景会渲染成方形影子
            Ui.applyNeuShadow(this, 4f, 20f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // 上分区：实时状态
        card.addView(TextView(activity).apply {
            text = "实时状态"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, 0, 0, Ui.dp(4, d))
        })
        val gauges = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cpuGauge = UsageGauge(activity, "CPU", Ui.buttonPrimary(activity))
        val gpuGauge = UsageGauge(activity, "GPU", Ui.buttonSecondary(activity))
        // CPU 标签 + 圆圈
        gauges.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(8, d) }
            addView(TextView(activity).apply {
                text = "CPU"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Ui.secondaryText(activity))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(6, d) }
            })
            addView(cpuGauge, LinearLayout.LayoutParams(Ui.dp(86, d), Ui.dp(86, d)))
        })
        // GPU 标签 + 圆圈
        gauges.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(8, d) }
            addView(TextView(activity).apply {
                text = "GPU"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Ui.secondaryText(activity))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(6, d) }
            })
            addView(gpuGauge, LinearLayout.LayoutParams(Ui.dp(86, d), Ui.dp(86, d)))
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
        details.addView(storageCard.third, LinearLayout.LayoutParams(0, Ui.dp(60, d), 1f).apply { marginEnd = Ui.dp(4, d) })
        details.addView(memoryCard.third, LinearLayout.LayoutParams(0, Ui.dp(60, d), 1f).apply { marginStart = Ui.dp(4, d) })
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
        activity.addLifecycleStopHook(Runnable { metricsHandler.removeCallbacks(updater) })

        // 水晶玻璃分隔条
        card.addView(Ui.crystalDivider(activity, d), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(2, d),
        ).apply { topMargin = Ui.dp(14, d); bottomMargin = Ui.dp(12, d) })

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
        statusColumn.addView(gsiPill.row)
        statusColumn.addView(ubuntuPill.row)
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
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(36, d)).apply { topMargin = Ui.dp(8, d) },
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
        executor.execute {
            val result = com.mcai.ubuntudsu.core.StatusDetector.deviceMetrics(activity, cpuSample)
            cpuSample = result.second
            val metrics = result.first
            activity.runOnUiThread {
                storageValue.text = "${metrics.storagePercent}%"
                storageDetail.text = "${metrics.storageUsed} / ${metrics.storageTotal}"
                memoryValue.text = "${metrics.memoryPercent}%"
                memoryDetail.text = "${metrics.memoryUsed} / ${metrics.memoryTotal}"
                onResult(metrics.cpuPercent, metrics.gpuPercent)
            }
        }
    }

    private fun detailMetric(title: String, color: Int): Triple<TextView, TextView, View> {
        val d = activity.resources.displayMetrics.density
        val valueView = TextView(activity).apply {
            text = "读取中..."
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 8, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
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
            setPadding(Ui.dp(10, d), Ui.dp(5, d), Ui.dp(10, d), Ui.dp(4, d))
            background = Ui.glassSurface(activity, 14f)
            addView(TextView(activity).apply {
                text = title
                textSize = 11f
                setTextColor(Ui.secondaryText(activity))
                gravity = android.view.Gravity.CENTER
            })
            addView(valueView)
            addView(detailView)
        }
        return Triple(valueView, detailView, card)
    }

    private class UsageGauge(
        context: android.content.Context,
        private val name: String,
        private val accent: Int,
    ) : View(context) {
        var value: Int? = null
            set(newValue) {
                field = newValue
                invalidate()
            }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val size = minOf(width, height).toFloat()
            val radius = size * 0.40f
            val stroke = size * 0.09f
            val ring = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            val dark = Ui.isDark(context)
            paint.color = if (dark) {
                android.graphics.Color.argb(120, 255, 255, 255)
            } else {
                android.graphics.Color.argb(110, android.graphics.Color.red(accent), android.graphics.Color.green(accent), android.graphics.Color.blue(accent))
            }
            canvas.drawArc(ring, -90f, 360f, false, paint)
            val percent = value
            if (percent != null && percent > 0) {
                paint.color = accent
                canvas.drawArc(ring, -90f, 3.6f * percent, false, paint)
            }
            paint.style = android.graphics.Paint.Style.FILL
            paint.textAlign = android.graphics.Paint.Align.CENTER
            val secondary = Ui.secondaryText(context)
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = radius * 0.52f
            paint.color = if (percent != null) accent else secondary
            val valueBaseline = cy - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(percent?.let { "$it%" } ?: "--", cx, valueBaseline, paint)
        }
    }

    fun refreshStatus() {
        val ctx = activity
        gsiText.text = "GSI 系统：检测中..."
        ubuntuText.text = "Linux：检测中..."
        rootLabel.text = "ROOT：检测中…"
        fun grayDot(view: View) {
            view.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#B0B0B0"))
            }
        }
        grayDot(gsiDot)
        grayDot(ubuntuDot)
        grayDot(rootDot)
        executor.execute {
            val device = com.mcai.ubuntudsu.core.StatusDetector.deviceSummary()
            val gsiState = com.mcai.ubuntudsu.core.StatusDetector.gsiState().first
            val rootOk = com.mcai.ubuntudsu.core.StatusDetector.rootAvailable()
            val gsiLabel = when (gsiState) {
                com.mcai.ubuntudsu.core.GsiState.RUNNING -> "运行中"
                com.mcai.ubuntudsu.core.GsiState.INSTALLED -> "已安装"
                com.mcai.ubuntudsu.core.GsiState.ENABLED -> "已启用"
                com.mcai.ubuntudsu.core.GsiState.DISABLED -> "已停用"
                com.mcai.ubuntudsu.core.GsiState.NORMAL -> "未安装"
                com.mcai.ubuntudsu.core.GsiState.UNKNOWN -> "未检测到"
            }
            fun dotColor(colorHex: String) = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor(colorHex))
            }
            activity.runOnUiThread {
                deviceText.text = device
                gsiText.text = "GSI 系统：$gsiLabel"
                ubuntuText.text = if (Env.ubuntuInstalled(ctx)) "Linux：已安装" else "Linux：未安装"
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
                ubuntuDot.background = dotColor(if (Env.ubuntuInstalled(ctx)) "#5CE1A5" else "#B0B0B0")
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
        executor.execute {
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
