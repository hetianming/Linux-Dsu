package com.mcai.ubuntudsu

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mcai.ubuntudsu.ui.Ui
import com.mcai.ubuntudsu.ui.pages.LinuxPage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v2.0.28：Linux 容器功能页（原 MainActivity 第 3 个 tab 的完整内容）。
 *
 * 「Linux」入口下放为「多功能」页的一张卡后，LinuxPage 原样搬到这里承载，
 * 内部逻辑一行未改：容器终端 / 桌面环境 / 文件管理 / 安装 rootfs / 卸载 rootfs。
 * 用独立 Activity 而非内嵌 View，是为了让它拥有自己的生命周期与返回栈
 * （LinuxPage 里有 AlertDialog、startActivity、runOnUiThread、后台 executor）。
 */
class LinuxContainerActivity : AppCompatActivity() {

    private lateinit var executor: ExecutorService
    private var page: LinuxPage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = Executors.newSingleThreadExecutor()
        val d = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.liquidBackground(this@LinuxContainerActivity)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        Ui.animateLiquidBackground(root)

        // 顶部紧凑返回条：独立 Activity 没有底部导航，给一个明确的返回入口。
        // 不重复标题，LinuxPage 自带「Linux ARM® 架构」标题行。
        root.addView(
            TextView(this).apply {
                text = Lang.t("‹  返回")
                textSize = 15f
                setTextColor(Ui.primaryText(this@LinuxContainerActivity))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(8, d))
                setOnClickListener { finish() }
                Ui.pressAnimation(this)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val p = LinuxPage(this, executor)
        page = p
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(p.build())
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
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
        page?.refreshInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::executor.isInitialized) executor.shutdownNow()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }
}
