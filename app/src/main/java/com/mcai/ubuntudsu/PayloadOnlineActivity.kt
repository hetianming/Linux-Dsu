package com.mcai.ubuntudsu

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.PayloadOnlinePage

/**
 * 在线提取 - 薄脚手架 Activity。
 *
 * 由「ROM固件」页卡刷包卡片的「在线提取」按钮唤起：传入卡刷包直链 + 文件名。
 * 页面用 HTTP Range 远程直读包结构，只拉取勾选分区的数据块（不下载整包），
 * 边下边解压写出 img（详见 [PayloadOnlinePage] / RemotePackage）。
 */
class PayloadOnlineActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_URL = "url"
        const val EXTRA_DEVICE = "device"
    }

    private var page: PayloadOnlinePage? = null
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        page = PayloadOnlinePage(this, { finish() }, lifecycleScope)
        val content = page!!.build()

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = Ui.liquidBackground(this@PayloadOnlineActivity)
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

    override fun onDestroy() {
        page?.cleanup()
        super.onDestroy()
    }

    override fun finish() {
        if (finishing) return
        finishing = true
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }
}
