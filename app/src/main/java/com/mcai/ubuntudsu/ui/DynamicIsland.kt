package com.mcai.ubuntudsu.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 顶部灵动岛（摄像头区域）。
 *
 * 收起态：居中黑色胶囊，仅占状态栏高度，位于挖孔/摄像头区域两侧，不遮挡时间与信号。
 * 展开态：点击胶囊后展开为黑色大卡，显示任务标题、文件名、进度条与操作按钮。
 *
 * 同时服务两类任务：GSI 安装（[Kind.INSTALL]）与固件下载（[Kind.DOWNLOAD]）。
 */
class DynamicIsland(
    private val activity: Activity,
    private val onAction: (Kind) -> Unit,
) {

    enum class Kind { INSTALL, DOWNLOAD }

    private lateinit var rootView: FrameLayout
    private lateinit var pill: LinearLayout
    private lateinit var ring: RingView
    private lateinit var labelText: TextView
    private lateinit var percentText: TextView
    private lateinit var expandBox: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var bar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var actionBtn: TextView

    private var isShowing = false
    private var isExpanded = false
    private var kind = Kind.DOWNLOAD
    private val d by lazy { activity.resources.displayMetrics.density }

    fun build(): FrameLayout {
        rootView = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(Ui.dp(16, d), 0, Ui.dp(16, d), 0)
            visibility = View.GONE
        }

        pill = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBackground()
            elevation = Ui.dp(12, d).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.perform(this)
                toggleExpand()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            )
        }

        // ===== 收起态：Ø24 进度环 + 名称 + 百分比 =====
        val collapsed = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(12, d), Ui.dp(7, d), Ui.dp(14, d), Ui.dp(7, d))
        }
        ring = RingView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(22, d), Ui.dp(22, d))
        }
        collapsed.addView(ring)

        labelText = TextView(activity).apply {
            text = "下载中"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(8, d), 0, 0, 0)
            maxWidth = Ui.dp(120, d)
        }
        collapsed.addView(labelText)

        percentText = TextView(activity).apply {
            text = "0%"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
            minWidth = Ui.dp(34, d)
            setPadding(Ui.dp(10, d), 0, 0, 0)
        }
        collapsed.addView(percentText)
        pill.addView(collapsed)

        // ===== 展开态：标题 / 文件名 / 进度条 / 状态 / 操作 =====
        expandBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(Ui.dp(16, d), 0, Ui.dp(16, d), Ui.dp(14, d))
        }
        titleText = TextView(activity).apply {
            text = "下载中"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        expandBox.addView(titleText)

        fileNameText = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#B9C4D2"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, Ui.dp(3, d), 0, Ui.dp(10, d))
        }
        expandBox.addView(fileNameText)

        bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4FC3F7"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(4, d),
            )
        }
        expandBox.addView(bar)

        statusText = TextView(activity).apply {
            textSize = 10.5f
            setTextColor(Color.parseColor("#98A6B6"))
            setPadding(0, Ui.dp(6, d), 0, 0)
        }
        expandBox.addView(statusText)

        actionBtn = TextView(activity).apply {
            text = "取消"
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF8A80"))
            background = actionBackground()
            setPadding(Ui.dp(18, d), Ui.dp(7, d), Ui.dp(18, d), Ui.dp(7, d))
            setOnClickListener {
                Haptics.perform(this)
                onAction(kind)
            }
        }
        val actionRow = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(10, d), 0, 0)
        }
        actionRow.addView(actionBtn)
        expandBox.addView(actionRow)

        pill.addView(expandBox)
        rootView.addView(pill)
        return rootView
    }

    fun show(kind: Kind, title: String, fileName: String, actionText: String) {
        this.kind = kind
        val accent = if (kind == Kind.INSTALL) Color.parseColor("#FFB74D") else Color.parseColor("#4FC3F7")
        ring.color = accent
        bar.progressTintList = android.content.res.ColorStateList.valueOf(accent)
        titleText.text = title
        labelText.text = title
        fileNameText.text = fileName
        actionBtn.text = actionText

        if (!isShowing) {
            isShowing = true
            applyCollapsedWidth()
            rootView.visibility = View.VISIBLE
            rootView.alpha = 0f
            rootView.translationY = -Ui.dp(24, d).toFloat()
            rootView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }

    fun update(percent: Int, status: String) {
        if (!isShowing) return
        percentText.text = "$percent%"
        ring.percent = percent
        bar.progress = percent
        if (status.isNotBlank()) statusText.text = status
    }

    fun setStatus(text: String) {
        if (!isShowing) return
        titleText.text = text
        labelText.text = text
    }

    fun dismiss(animate: Boolean = true) {
        if (!isShowing) return
        isShowing = false
        if (isExpanded) {
            isExpanded = false
            expandBox.visibility = View.GONE
            applyCollapsedWidth()
        }
        if (animate) {
            rootView.animate()
                .alpha(0f)
                .translationY(-Ui.dp(24, d).toFloat())
                .setDuration(220)
                .withEndAction { rootView.visibility = View.GONE }
                .start()
        } else {
            rootView.visibility = View.GONE
        }
    }

    // ==================== 展开 / 收起 ====================

    private fun toggleExpand() {
        isExpanded = !isExpanded
        if (isExpanded) {
            expandBox.visibility = View.VISIBLE
            expandBox.alpha = 0f
            expandBox.animate().alpha(1f).setDuration(180).start()
            pill.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            )
        } else {
            expandBox.visibility = View.GONE
            applyCollapsedWidth()
        }
        pill.requestLayout()
    }

    private fun applyCollapsedWidth() {
        pill.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL,
        )
    }

    // ==================== 视觉 ====================

    private fun pillBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#F5000000"))
        cornerRadius = Ui.dp(21, d).toFloat()
        setStroke(Ui.dp(1, d), Color.parseColor("#26FFFFFF"))
    }

    private fun actionBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#26FF8A80"))
        cornerRadius = Ui.dp(12, d).toFloat()
    }

    /** 细进度环（灵动岛左侧），无泛光，夜间也清晰 */
    private class RingView(context: Context) : View(context) {
        var percent: Int = 0
            set(value) {
                field = value.coerceIn(0, 100)
                invalidate()
            }
        var color: Int = Color.parseColor("#4FC3F7")

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = minOf(w, h) / 2f - h * 0.07f
            paint.strokeWidth = h * 0.12f
            paint.color = Color.argb(56, 255, 255, 255)
            canvas.drawCircle(w / 2f, h / 2f, r, paint)
            if (percent > 0) {
                paint.color = color
                val rect = RectF(w / 2f - r, h / 2f - r, w / 2f + r, h / 2f + r)
                canvas.drawArc(rect, -90f, 360f * percent / 100f, false, paint)
            }
        }
    }
}
