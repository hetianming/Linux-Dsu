package com.mcai.ubuntudsu.ui

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * 触觉反馈工具类
 * 基于 Dsu-Manager 的 Haptics 实现思路
 */
object Haptics {

    private var lastFeedbackAt: Long = 0
    private const val MIN_INTERVAL_MS = 180L // 防抖间隔

    // ---- 手势判定状态：所有页面共用一个实例，天然互斥（一次只有一根手指在处理） ----
    private var downView: View? = null
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var slop = 0

    /**
     * 全局触摸入口：**只有真正的「点一下」才震**。
     *
     * 判定：按下时记住手指底下的可点击 View，位移一旦超过系统 touchSlop 就作废，
     * 抬手时仍是同一次按压且时长 ≤700ms 才震。滚动列表、拖日志、划卡片都不震。
     *
     * 早先的实现是一按下就震，用户的原话是「滑一次震动一次」。
     */
    @kotlin.jvm.JvmOverloads
    fun onTouch(root: View?, event: MotionEvent, fallbackOnMiss: Boolean = false) {
        if (root == null) return
        if (slop == 0) {
            slop = android.view.ViewConfiguration.get(root.context).scaledTouchSlop
            if (slop <= 0) slop = 8
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downView = findClickable(root, event.x, event.y)
                downX = event.x
                downY = event.y
                downAt = android.os.SystemClock.uptimeMillis()
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (downView != null && !moved) {
                    if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) {
                        moved = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val v = downView
                val held = android.os.SystemClock.uptimeMillis() - downAt
                if (!moved && held <= 700L) {
                    if (v != null) perform(v)
                    // 内嵌 tab 页含横向 pageStrip + 纵向 ScrollView，命中偶尔落空时
                    // 用根 View 兜底，保证「点一下」一定有反馈。仅 MainActivity 开启。
                    else if (fallbackOnMiss) perform(root)
                }
                downView = null
                moved = false
            }
            MotionEvent.ACTION_CANCEL -> {
                downView = null
                moved = false
            }
        }
    }

    /**
     * 单次点击震动（带 180ms 防抖）
     */
    fun perform(view: View?) {
        if (view == null) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFeedbackAt <= MIN_INTERVAL_MS) return
        lastFeedbackAt = now
        // 1) View 触觉反馈：不需要 VIBRATE 权限，跟随系统「触感反馈」开关
        val ok = runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }.getOrDefault(false)
        // 2) 部分 ROM（尤其关掉系统触感的定制系统）会直接返回 false，退回振动器
        if (!ok) {
            runCatching {
                val ctx = view.context
                val v = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    (ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                        as android.os.VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                }
                if (v.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(12, 60))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(12)
                    }
                }
            }
        }
    }

    /**
     * 立即触发震动（无防抖）
     */
    fun performImmediate(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /**
     * 长按震动反馈
     */
    fun performLongPress(view: View?) {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * 递归查找触摸点下的可点击 View
     */
    fun findClickable(view: View, x: Float, y: Float): View? {
        if (!view.isShown || x < 0 || y < 0 || x > view.width || y > view.height) return null

        if (view is ViewGroup) {
            // 子 View 的 left/top 属于「内容坐标系」，传入的 x/y 属于「可见区域坐标系」。
            // 容器一旦滚动，两者相差 scrollX/scrollY：MainActivity 的 pageStrip 靠
            // scrollX = tab * 屏宽 定位，第 2/3/4 页不补偿就会把手指算到别的页，
            // downView 恒为 null —— 内嵌 tab 页按钮点了不震的真正原因。translation 同理。
            val sx = view.scrollX.toFloat()
            val sy = view.scrollY.toFloat()
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                val target = findClickable(
                    child,
                    x - child.left + sx - child.translationX,
                    y - child.top + sy - child.translationY,
                )
                if (target != null) return target
            }
        }

        return if (view.isClickable || view.isLongClickable) view else null
    }
}
