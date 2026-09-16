package com.mcai.ubuntudsu

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.ProcessManagerPage

class ProcessManagerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = ProcessManagerPage(this) { finish() }
        val content = page.build()

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        Ui.animateLiquidBackground(root)
        root.addView(content)
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.ViewCompat.requestApplyInsets(content)
            insets
        }
    }
}
