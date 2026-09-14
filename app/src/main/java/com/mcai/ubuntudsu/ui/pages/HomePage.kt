package com.mcai.ubuntudsu.ui.pages

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mcai.ubuntudsu.MainActivity
import com.mcai.ubuntudsu.core.Env
import com.mcai.ubuntudsu.ui.Ui
import java.util.concurrent.Executor

class HomePage(
    private val activity: MainActivity,
    private val executor: Executor,
) {
    private lateinit var deviceText: TextView
    private lateinit var gsiText: TextView
    private lateinit var ubuntuText: TextView
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

    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }
        // 标题行：左侧"首页"，ROOT 徽章居中，右侧设置图标
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
        titleRow.addView(buildRootBadge(d))
        // 右侧 weight 容器包设置图标（gravity END）：固定空间只剩徽章宽度，徽章真正居中
        titleRow.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(Ui.settingsIconButton(activity) { Ui.showThemeDialog(activity) { activity.recreate() } })
        })
        page.addView(titleRow)

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
    private fun applyGradient() {
        val card = unifiedCard ?: return
        if (gradientIndex < 0) {
            card.background = Ui.glassSurface(activity, 20f)
            return
        }
        val (top, bottom) = gradientPresets()[gradientIndex]
        card.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
            cornerRadius = Ui.dp(20, activity.resources.displayMetrics.density).toFloat()
        }
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
            elevation = Ui.dp(3, d).toFloat()
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
            elevation = Ui.dp(4, d).toFloat()
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
            gravity = Gravity.CENTER
        }
        val cpuGauge = UsageGauge(activity, "CPU", Ui.buttonPrimary(activity))
        val gpuGauge = UsageGauge(activity, "GPU", Ui.buttonSecondary(activity))
        gauges.addView(cpuGauge, LinearLayout.LayoutParams(0, Ui.dp(86, d), 1f).apply { marginEnd = Ui.dp(4, d) })
        gauges.addView(gpuGauge, LinearLayout.LayoutParams(0, Ui.dp(86, d), 1f).apply { marginStart = Ui.dp(4, d) })
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
        card.addView(TextView(activity).apply {
            text = "Linux - Dsu"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(2, d))
        })
        val statusColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(4, d), 0, 0)
        }
        gsiText = statusRow("GSI 系统", "检测中...")
        ubuntuText = statusRow("Linux", "检测中...")
        statusColumn.addView(gsiText)
        statusColumn.addView(ubuntuText)
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

    // ROOT 检测徽章：页面标题行居中放置
    private fun buildRootBadge(d: Float): View {
        val badge = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(
                if (Ui.isDark(activity)) android.graphics.Color.argb(68, 0, 0, 0) else android.graphics.Color.argb(78, 255, 255, 255),
                8f,
                d,
            )
            setPadding(Ui.dp(8, d), 0, Ui.dp(10, d), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Ui.dp(26, d),
            )
        }
        rootDot = Ui.statusDot(activity, android.graphics.Color.parseColor("#B0B0B0")).apply {
            val size = Ui.dp(7, d)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        rootLabel = TextView(activity).apply {
            text = "ROOT 检测中"
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.secondaryText(activity))
            layoutParams = Ui.layoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(5, d)
            }
        }
        badge.addView(rootDot)
        badge.addView(rootLabel)
        return badge
    }

    private fun statusRow(label: String, value: String): TextView = TextView(activity).apply {
        text = "$label：$value"
        textSize = 14f
        setTextColor(Ui.primaryText(activity))
        val d = activity.resources.displayMetrics.density
        setPadding(Ui.dp(10, d), Ui.dp(4, d), Ui.dp(10, d), Ui.dp(4, d))
        background = Ui.rounded(
            if (Ui.isDark(activity)) android.graphics.Color.argb(68, 0, 0, 0) else android.graphics.Color.argb(78, 255, 255, 255),
            8f,
            d,
        )
        layoutParams = Ui.layoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(6, activity.resources.displayMetrics.density) }
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
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 7, 9, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(10, d), Ui.dp(5, d), Ui.dp(10, d), Ui.dp(4, d))
            background = Ui.glassSurface(activity, 14f)
            addView(TextView(activity).apply {
                text = title
                textSize = 11f
                setTextColor(Ui.secondaryText(activity))
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
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textSize = radius * 0.36f
            paint.color = secondary
            val nameBaseline = cy - radius * 0.52f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(name, cx, nameBaseline, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = radius * 0.52f
            paint.color = if (percent != null) accent else secondary
            val valueBaseline = cy + radius * 0.48f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(percent?.let { "$it%" } ?: "--", cx, valueBaseline, paint)
        }
    }

    fun refreshStatus() {
        val ctx = activity
        gsiText.text = "GSI 系统：检测中..."
        ubuntuText.text = "Linux：检测中..."
        rootLabel.text = "ROOT 检测中"
        rootDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.parseColor("#B0B0B0"))
        }
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
            activity.runOnUiThread {
                deviceText.text = device
                gsiText.text = "GSI 系统：$gsiLabel"
                ubuntuText.text = if (Env.ubuntuInstalled(ctx)) "Linux：已安装（大小计算中...）" else "Linux：未安装"
                rootLabel.text = if (rootOk) "ROOT 已授权" else "ROOT 未授权"
                rootDot.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor(if (rootOk) "#5CE1A5" else "#FF756F"))
                }
            }
            if (Env.ubuntuInstalled(ctx)) {
                val ubuntuSize = runCatching { Env.formatSize(Env.dirSize(Env.rootfs(ctx))) }
                    .getOrElse { "读取失败" }
                activity.runOnUiThread { ubuntuText.text = "Linux：已安装 ($ubuntuSize)" }
            }
        }
    }
}
