package com.mcai.ubuntudsu.ui

import android.app.Activity
import android.graphics.Color
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
import com.mcai.ubuntudsu.ui.Haptics

/**
 * 灵动岛 - 下载进度浮动组件
 * 收起态：显示设备名 + 进度百分比 + 进度条
 * 展开态：显示文件名、速度、取消按钮
 */
class DynamicIsland(
    private val activity: Activity,
    private val onCancel: () -> Unit,
) {
    private lateinit var rootView: FrameLayout
    private lateinit var pillView: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleText: TextView
    private lateinit var speedText: TextView
    private lateinit var detailContainer: LinearLayout
    private lateinit var fileNameText: TextView
    private lateinit var deviceText: TextView

    private var isShowing = false
    private var isExpanded = false
    private val d by lazy { activity.resources.displayMetrics.density }

    fun build(): FrameLayout {
        rootView = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), 0)
        }

        pillView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBackground()
            elevation = Ui.dp(10, d).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.perform(this)
                toggleExpand()
            }
            Ui.pressAnimation(this)
        }

        // ===== 收起态行 =====
        val collapsedRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(10, d), Ui.dp(16, d), Ui.dp(10, d))
        }

        titleText = TextView(activity).apply {
            text = "下载中"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        collapsedRow.addView(titleText)

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(4, d), 1f).apply {
                marginStart = Ui.dp(10, d)
                marginEnd = Ui.dp(10, d)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4FC3F7"))
        }
        collapsedRow.addView(progressBar)

        progressText = TextView(activity).apply {
            text = "0%"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            minWidth = Ui.dp(36, d)
            gravity = Gravity.END
        }
        collapsedRow.addView(progressText)

        pillView.addView(collapsedRow)

        // ===== 展开态详情 =====
        detailContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(Ui.dp(16, d), 0, Ui.dp(16, d), Ui.dp(12, d))
        }

        deviceText = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#4FC3F7"))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        detailContainer.addView(deviceText)

        fileNameText = TextView(activity).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#B0B0B0"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, Ui.dp(2, d), 0, 0)
        }
        detailContainer.addView(fileNameText)

        speedText = TextView(activity).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#808080"))
            setPadding(0, Ui.dp(2, d), 0, Ui.dp(8, d))
        }
        detailContainer.addView(speedText)

        val cancelBtn = TextView(activity).apply {
            text = "取消下载"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF6B6B"))
            background = cancelBackground()
            setPadding(Ui.dp(16, d), Ui.dp(6, d), Ui.dp(16, d), Ui.dp(6, d))
            setOnClickListener {
                Haptics.perform(this)
                onCancel()
            }
        }
        val btnRow = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        btnRow.addView(cancelBtn)
        detailContainer.addView(btnRow)

        pillView.addView(detailContainer)

        val pillParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        rootView.addView(pillView, pillParams)

        rootView.visibility = View.GONE
        return rootView
    }

    fun show(fileName: String, deviceName: String = "", label: String = "") {
        if (isShowing) {
            // 已显示，只更新信息
            if (deviceName.isNotBlank()) deviceText.text = deviceName
            if (fileName.isNotBlank()) fileNameText.text = fileName
            return
        }
        isShowing = true
        deviceText.text = if (deviceName.isNotBlank()) deviceName else ""
        fileNameText.text = fileName
        speedText.text = ""
        progressText.text = "0%"
        progressBar.progress = 0
        // 收起态标题：设备名 + 包类型
        titleText.text = if (label.isNotBlank()) label else "下载中"
        rootView.visibility = View.VISIBLE
        rootView.alpha = 0f
        rootView.translationY = -60f
        rootView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(350)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    fun updateProgress(progress: Int, speed: String) {
        if (!isShowing) return
        progressText.text = "$progress%"
        progressBar.progress = progress
        if (speed.isNotBlank()) {
            speedText.text = speed
        }
    }

    fun setStatus(text: String) {
        if (!isShowing) return
        titleText.text = text
    }

    fun dismiss(animate: Boolean = true) {
        if (!isShowing) return
        isShowing = false
        if (animate) {
            rootView.animate()
                .alpha(0f)
                .translationY(-60f)
                .setDuration(250)
                .withEndAction { rootView.visibility = View.GONE }
                .start()
        } else {
            rootView.visibility = View.GONE
        }
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        detailContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        if (isExpanded) {
            detailContainer.alpha = 0f
            detailContainer.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun pillBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#E81A1A1A"))
            cornerRadius = Ui.dp(22, d).toFloat()
            setStroke(Ui.dp(1, d), Color.parseColor("#33FFFFFF"))
        }
    }

    private fun cancelBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#33FF6B6B"))
            cornerRadius = Ui.dp(8, d).toFloat()
        }
    }
}
