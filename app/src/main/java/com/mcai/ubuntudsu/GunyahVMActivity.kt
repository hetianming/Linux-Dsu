package com.mcai.ubuntudsu

import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.ui.Ui

/**
 * v2.0.29：GunyaniOS 主页的独立 Activity 薄壳。
 *
 * 界面、状态刷新、3s 轮询、启动/停止动作全部在 [VmPage]（MainActivity 第 3 格
 * 「TmVM」内嵌复用同一个类），这里不再重复实现任何 UI，只负责窗口级 insets 与生命周期转发。
 *
 * 保留本 Activity 的原因：子页面 GunyahSubActivity.saveAndGoHome() 用 CLEAR_TOP 回主页，
 * 以及 Manifest 里 Theme.App.Gunyah 的入口都指向它，删掉会破坏返回栈。
 * 内部逻辑与 v2.0.28 完全一致，启动链 / GunyahManager 未改。
 */
class GunyahVMActivity : HapticAppCompatActivity() {
    private var page: VmPage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = VmPage(this, showBack = true)
        page = p
        val root = p.build()
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        page?.onResume()
    }

    override fun onPause() {
        page?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        page?.onDestroy()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }
}
