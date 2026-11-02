package com.mcai.ubuntudsu

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.TvPage

/**
 * 手机电视：IPTV 直播浏览页。
 *
 * 由「多功能」页的「手机电视」卡片唤起：选择直播源（TVBox 订阅 / IPv4 / IPv6 / 自定义），
 * 解析频道列表后点击即可调用 [TvPlayerActivity] 播放。
 */
class TvActivity : AppCompatActivity() {

    private var page: TvPage? = null
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        page = TvPage(this, { finish() }, lifecycleScope)
        val content = page!!.build()

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = Ui.liquidBackground(this@TvActivity)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 32 * view.resources.displayMetrics.density)
                }
            }
        }
        Ui.animateLiquidBackground(root)
        root.addView(content)
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun finish() {
        if (finishing) return
        finishing = true
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }
}
