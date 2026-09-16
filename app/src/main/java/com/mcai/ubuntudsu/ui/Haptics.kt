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

    /**
     * 全局触摸事件处理：在 ACTION_UP 时对可点击 View 触发震动
     */
    fun onTouch(root: View?, event: MotionEvent) {
        if (root == null || event.actionMasked != MotionEvent.ACTION_UP) return
        val target = findClickable(root, event.x, event.y)
        if (target != null) perform(target)
    }

    /**
     * 单次点击震动（带 180ms 防抖）
     */
    fun perform(view: View?) {
        val now = android.os.SystemClock.uptimeMillis()
        if (view != null && now - lastFeedbackAt > MIN_INTERVAL_MS) {
            lastFeedbackAt = now
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
    private fun findClickable(view: View, x: Float, y: Float): View? {
        if (!view.isShown || x < 0 || y < 0 || x > view.width || y > view.height) return null

        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                val target = findClickable(
                    child,
                    x - child.left,
                    y - child.top,
                )
                if (target != null) return target
            }
        }

        return if (view.isClickable || view.isLongClickable) view else null
    }
}
