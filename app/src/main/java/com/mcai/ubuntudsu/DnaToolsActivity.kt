package com.mcai.ubuntudsu

import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.DnaToolsPage

/**
 * 二进制工具（DNA-Android 命令行工具集）- 薄脚手架 Activity
 *
 * 界面与交互全部交由 [DnaToolsPage]（主应用拟态 + 液态玻璃架构），
 * 本类只负责生命周期接线：挂液态玻璃背景 + edge-to-edge，构建页面，销毁时取消下载。
 */
class DnaToolsActivity : AppCompatActivity() {

    private lateinit var page: DnaToolsPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.enableEdgeToEdge(this, window.decorView)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val root = FrameLayout(this)
        root.clipToOutline = true
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val r = 32 * view.resources.displayMetrics.density
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        Ui.animateLiquidBackground(root)

        page = DnaToolsPage(this)
        val content = page.build()
        Ui.applyContentInsets(content)
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        setContentView(root)
    }

    override fun onDestroy() {
        page.destroy()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }
}
