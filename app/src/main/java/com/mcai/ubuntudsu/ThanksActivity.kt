package com.mcai.ubuntudsu

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui

/**
 * 特别鸣谢页：内容移植自 Dsu-Manager DNA界面 / 设置 / 特别鸣谢。
 * 沿用本应用拟态玻璃架构，避免引入 Dsu-Manager 的液态玻璃资源依赖。
 */
class ThanksActivity : AppCompatActivity() {

    private var finishing = false

    companion object {
        private data class Credit(
            val icon: Int,
            val title: String,
            val desc: String,
            val url: String,
        )

        private val CREDITS = listOf(
            Credit(
                R.drawable.credit_icon_author,
                Lang.t("项目作者"),
                Lang.t("酷安@菜鸟_曾经的天明\nhttps://github.com/hetianming/Linux-Dsu"),
                "https://www.coolapk.com/u/2705572",
            ),
            Credit(
                R.drawable.credit_icon_dna_port,
                Lang.t("DNA移植源码"),
                Lang.t("DNA 移植源码作者：小你可兰\nDsu-Manager 项目开发"),
                "https://www.coolapk.com/u/3347561",
            ),
            Credit(
                R.drawable.credit_icon_tik,
                Lang.t("TIK工具箱"),
                Lang.t("部分代码来自于TIK2源码，在此感谢"),
                "https://gitee.com/yeliqin666/TIK",
            ),
            Credit(
                R.drawable.credit_icon_magiskboot,
                "magiskboot",
                Lang.t("镜像解包/打包核心工具"),
                "https://github.com/topjohnwu/Magisk",
            ),
            Credit(
                R.drawable.credit_icon_sdat,
                "sdat2img and img2sdat",
                Lang.t("transfer.list 数据转换工具"),
                "https://github.com/xpirt",
            ),
            Credit(
                R.drawable.credit_icon_erofs,
                "erofs-extract",
                Lang.t("EROFS 镜像提取工具"),
                "https://github.com/sekaiacg/erofs-extract",
            ),
            Credit(
                R.drawable.credit_icon_dna,
                "DNA",
                Lang.t("使用了@温柔的慈悲大佬的DNA工具箱名字，向大佬致敬！"),
                "https://gitee.com/sharpeter/DNA",
            ),
            Credit(
                R.drawable.credit_icon_dna_maintainer,
                Lang.t("酷安：@相见即是缘"),
                Lang.t("感谢大佬一直维护的 DNA 工具，DNA 打包功能移植自其 20260530 版本"),
                "https://www.coolapk.com/u/1614257",
            ),
            Credit(
                R.drawable.credit_icon_gjj,
                Lang.t("搞机助手"),
                Lang.t("搞机助手原作者@情非得已c，提取了搞机助手部分代码文件使用！"),
                "",
            ),
            Credit(
                R.drawable.credit_icon_ffix,
                "affggh",
                Lang.t("改用@affggh大佬的fspatch.py修补权限文件以及github开源的工具"),
                "https://github.com/affggh/fspatch",
            ),
            Credit(
                R.drawable.icon_dsu_modern,
                "DSU-Sideloader",
                Lang.t("本应用的 GSI 安装流程参考并使用了 DSU-Sideloader 项目的相关方案"),
                "https://github.com/VegaBobo/DSU-Sideloader",
            ),
        )
    }

    private val d by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.background = Ui.liquidBackground(this)
        Ui.animateLiquidBackground(root)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(buildContent())
        }
        Ui.applyContentInsets(scroll)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)
    }

    private fun buildContent(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(28, d))
        }

        val titleRow = FrameLayout(this).apply {
            setPadding(0, 0, 0, Ui.dp(16, d))
        }
        titleRow.addView(TextView(this).apply {
            text = Lang.t("‹ 返回")
            textSize = 13f
            setTextColor(Ui.buttonText(this@ThanksActivity))
            background = Ui.glassButton(this@ThanksActivity, Ui.buttonPrimary(this@ThanksActivity))
            Ui.pressAnimation(this)
            setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
            setOnClickListener {
                Haptics.perform(this)
                finish()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            )
        })
        titleRow.addView(TextView(this).apply {
            text = Lang.t("特别鸣谢")
            textSize = 20f
            setTextColor(Ui.primaryText(this@ThanksActivity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        page.addView(titleRow)

        val headCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.neuCard(this@ThanksActivity, 20f, Ui.buttonSuccess(this@ThanksActivity))
            Ui.applyNeuShadow(this, 5f, 18f, Ui.buttonSuccess(this@ThanksActivity))
            setPadding(Ui.dp(18, d), Ui.dp(18, d), Ui.dp(18, d), Ui.dp(16, d))
        }
        headCard.addView(TextView(this).apply {
            text = Lang.t("特别鸣谢")
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headCard.addView(TextView(this).apply {
            text = Lang.t("不分先后，如有遗忘望提醒")
            textSize = 13f
            setTextColor(android.graphics.Color.argb(210, 255, 255, 255))
            setPadding(0, Ui.dp(5, d), 0, 0)
        })
        headCard.addView(TextView(this).apply {
            text = Lang.t("本软件的诞生离不开这些开源项目与开发者们的贡献")
            textSize = 11f
            setTextColor(android.graphics.Color.argb(180, 255, 255, 255))
            setPadding(0, Ui.dp(4, d), 0, 0)
        })
        page.addView(headCard)

        CREDITS.forEachIndexed { index, credit ->
            page.addView(buildCreditCard(credit, index))
        }

        page.addView(TextView(this).apply {
            text = Lang.t("开源让世界更美好\n谨向所有为中文搞机社区贡献过代码、教程与时间的人们致敬")
            textSize = 12f
            setTextColor(Ui.secondaryText(this@ThanksActivity))
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(20, d), 0, 0)
            setLineSpacing(Ui.dp(4, d).toFloat(), 1f)
        })

        return page
    }

    private fun buildCreditCard(credit: Credit, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.neuCard(this@ThanksActivity, 20f, Ui.buttonSuccess(this@ThanksActivity))
            Ui.applyNeuShadow(this, 5f, 18f, Ui.buttonSuccess(this@ThanksActivity))
            setPadding(Ui.dp(14, d), Ui.dp(14, d), Ui.dp(14, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = if (index == 0) Ui.dp(14, d) else Ui.dp(12, d) }
        }

        val badge = android.widget.ImageView(this).apply {
            setImageResource(credit.icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            background = Ui.neuInset(this@ThanksActivity, 12f)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(42, d), Ui.dp(42, d))
        }
        card.addView(badge)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ui.dp(12, d), 0, Ui.dp(6, d), 0)
        }
        textCol.addView(TextView(this).apply {
            text = credit.title
            textSize = 13f
            setTextColor(Ui.primaryText(this@ThanksActivity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        if (credit.desc.isNotEmpty()) {
            textCol.addView(TextView(this).apply {
                text = credit.desc
                textSize = 11f
                setTextColor(Ui.secondaryText(this@ThanksActivity))
                setPadding(0, Ui.dp(2, d), 0, 0)
                setLineSpacing(Ui.dp(2, d).toFloat(), 1f)
            })
        }
        card.addView(textCol)

        if (credit.url.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = "↗"
                textSize = 16f
                setTextColor(Ui.buttonSuccess(this@ThanksActivity))
                background = Ui.neuInset(this@ThanksActivity, 12f)
                gravity = Gravity.CENTER
                isClickable = true
                Ui.pressAnimation(this)
                layoutParams = LinearLayout.LayoutParams(Ui.dp(34, d), Ui.dp(34, d))
                setOnClickListener {
                    Haptics.perform(this)
                    openUrl(credit.url)
                }
            })
        }

        return card
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, Lang.t("无应用可打开链接"), Toast.LENGTH_SHORT).show()
        }
    }

    override fun finish() {
        if (finishing) return
        finishing = true
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }
}
