package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mcai.ubuntudsu.GunyahVMActivity
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.ui.Ui

/**
 * v2.0.28：底部第 3 格「gunyahVM」tab 的兜底页面。
 *
 * 点该 tab 由 MainActivity.onTabClicked 直接打开 GunyahVMActivity（一级直达）；
 * 横滑到这一格时显示本页，避免 4 页横滑框架出现空白，并保留一个同样能进入虚拟机的按钮。
 * GunyahVMActivity 是独立 Activity（生命周期 / 启动链 / 状态刷新都在里面），
 * 这里刻意只做入口，不复制它的任何逻辑。
 */
class VmEntryPage(private val activity: Activity) {
    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val root = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.glassSurface(activity, 22f)
            setPadding(Ui.dp(24, d), Ui.dp(30, d), Ui.dp(24, d), Ui.dp(26, d))
        }
        Ui.applyNeuShadow(card, 4f, 22f)

        card.addView(ImageView(activity).apply {
            setImageResource(R.drawable.icon_gunyah_vm)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(72, d), Ui.dp(72, d)).apply {
                bottomMargin = Ui.dp(16, d)
            }
        })
        card.addView(TextView(activity).apply {
            text = "gunyahVM"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        card.addView(TextView(activity).apply {
            text = "工具链 · 启动配置 · 镜像映射 · 内核模块 · 启动与停止"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(10, d); bottomMargin = Ui.dp(22, d) }
        })
        card.addView(TextView(activity).apply {
            text = "进入 gunyahVM"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Ui.buttonText(activity))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.buttonPrimary(activity))
                cornerRadius = Ui.dp(14, d).toFloat()
            }
            setPadding(Ui.dp(28, d), Ui.dp(12, d), Ui.dp(28, d), Ui.dp(12, d))
            setOnClickListener {
                activity.startActivity(Intent(activity, GunyahVMActivity::class.java))
            }
            Ui.pressAnimation(this)
        })
        card.addView(TextView(activity).apply {
            text = "也可直接点底部「gunyahVM」标签进入"
            textSize = 11f
            setTextColor(Ui.secondaryText(activity))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(14, d) }
        })

        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = Ui.dp(24, d)
                rightMargin = Ui.dp(24, d)
            },
        )
        return root
    }
}
