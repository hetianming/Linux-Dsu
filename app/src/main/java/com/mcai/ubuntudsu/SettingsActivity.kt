package com.mcai.ubuntudsu

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.mcai.ubuntudsu.ui.Haptics
import com.mcai.ubuntudsu.ui.Ui

/**
 * 全屏设置页（参考 Dsu-Manager 的 SettingsActivity 布局）：
 * 返回条 + 「个性主题」分区 + 「关于信息」分区，整体沿用主应用拟态玻璃架构。
 *
 * 主题偏好与主界面共用 MainActivity 的私有 SharedPreferences 文件
 * （MainActivity 用 getPreferences() 读写，文件名即类名 "MainActivity"），
 * 保证设置页改主题后主界面立即生效。
 */
class SettingsActivity : AppCompatActivity() {

    private var finishing = false

    companion object {
        const val THEME_PREFS = "MainActivity"
        const val KEY_THEME_MODE = "theme_mode"
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
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(24, d))
        }

        // ===== 返回条：‹ 返回 / 设置（标题真正居中） =====
        val titleRow = FrameLayout(this).apply {
            setPadding(0, 0, 0, Ui.dp(16, d))
        }
        titleRow.addView(TextView(this).apply {
            text = Lang.t("‹ 返回")
            textSize = 13f
            setTextColor(Ui.buttonText(this@SettingsActivity))
            background = Ui.glassButton(this@SettingsActivity, Ui.buttonPrimary(this@SettingsActivity))
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
            text = Lang.t("设置")
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })
        page.addView(titleRow)

        // ===== 分区一：关于信息（置顶，使用主色绿替代蓝字） =====
        val aboutInfoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(this@SettingsActivity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(12, d) }
        }
        aboutInfoCard.addView(TextView(this).apply {
            text = Lang.t("关于信息")
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        })
        aboutInfoCard.addView(TextView(this).apply {
            text = "Linux - Dsu"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(2, d))
        })
        aboutInfoCard.addView(TextView(this).apply {
            val ver = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "--"
            text = "版本 v$ver  ·  天明构建  ·  Copyright © 2026"
            textSize = 11f
            setTextColor(Ui.secondaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(10, d))
        })
        aboutInfoCard.addView(TextView(this).apply {
            text = Lang.t("Ubuntu Chroot / DSU / ROM 移植一站式工具。首页右上角「关于信息」可查看完整功能简介。")
            textSize = 12f
            setTextColor(Ui.secondaryText(this@SettingsActivity))
            setLineSpacing(Ui.dp(3, d).toFloat(), 1f)
        })
        page.addView(aboutInfoCard)

        // ===== 分区二：个性主题 =====
        val themeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(this@SettingsActivity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(12, d) }
        }
        themeCard.addView(TextView(this).apply {
            text = Lang.t("主题模式")
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(10, d))
        })
        themeCard.addView(buildThemeModeRow())
        page.addView(themeCard)

        // ===== 分区：语言（v2.0.38：跟随系统 / 手动固定）=====
        val langCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(this@SettingsActivity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(12, d) }
        }
        langCard.addView(TextView(this).apply {
            text = Lang.title()
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        })
        langCard.addView(TextView(this).apply {
            text = Lang.hint()
            textSize = 11f
            setTextColor(Ui.secondaryText(this@SettingsActivity))
            setLineSpacing(Ui.dp(3, d).toFloat(), 1f)
            setPadding(0, 0, 0, Ui.dp(10, d))
        })
        langCard.addView(buildLanguageRow())
        page.addView(langCard)

        // ===== 分区三：特别鸣谢入口 =====
        val aboutCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.neuCard(this@SettingsActivity, 18f)
            setPadding(Ui.dp(16, d), Ui.dp(14, d), Ui.dp(16, d), Ui.dp(14, d))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(12, d) }
            isClickable = true
            isFocusable = true
            Ui.pressAnimation(this)
            setOnClickListener {
                Haptics.perform(this)
                startActivity(Intent(this@SettingsActivity, ThanksActivity::class.java))
            }
        }
        aboutCard.addView(TextView(this).apply {
            text = Lang.t("查看特别鸣谢")
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(this@SettingsActivity))
            setPadding(0, 0, 0, Ui.dp(6, d))
        })
        aboutCard.addView(TextView(this).apply {
            text = Lang.t("感谢本软件采用的开源项目、工具链与社区贡献者。查看完整鸣谢列表")
            textSize = 12f
            setTextColor(Ui.secondaryText(this@SettingsActivity))
            setLineSpacing(Ui.dp(3, d).toFloat(), 1f)
        })
        page.addView(aboutCard)

        return page
    }

    /** v2.0.38：语言三选一。切换后清空任务栈回首页，让缓存的页面全部按新语言重建。 */
    private fun buildLanguageRow(): View {
        val prefs = getSharedPreferences(Lang.PREF, Activity.MODE_PRIVATE)
        val cur = prefs.getString(Lang.KEY_MODE, Lang.AUTO) ?: Lang.AUTO
        val opts = listOf(
            Lang.AUTO to Lang.optFollow(),
            Lang.ZH to Lang.optChinese(),
            Lang.EN to Lang.optEnglish(),
        )
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        opts.forEachIndexed { index, (m, label) ->
            group.addView(TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                val selected = m == cur
                setTextColor(if (selected) Ui.buttonText(this@SettingsActivity) else Ui.secondaryText(this@SettingsActivity))
                background = if (selected) {
                    Ui.glassButton(this@SettingsActivity, Ui.buttonPrimary(this@SettingsActivity))
                } else {
                    Ui.rounded(Color.TRANSPARENT, 10f, d)
                }
                setPadding(Ui.dp(10, d), Ui.dp(9, d), Ui.dp(10, d), Ui.dp(9, d))
                Ui.pressAnimation(this)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = Ui.dp(6, d)
                }
                setOnClickListener {
                    if (m == cur) return@setOnClickListener
                    Lang.setMode(this@SettingsActivity, m)
                    startActivity(Intent(this@SettingsActivity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                    finish()
                }
            })
        }
        return group
    }

    /** 主题模式三选一：跟随系统 / 浅色 / 深色（选中项高亮，切换后立即重建生效） */
    private fun buildThemeModeRow(): View {
        val prefs = getSharedPreferences(THEME_PREFS, Activity.MODE_PRIVATE)
        val current = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val checked = when (current) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }
        val modes = arrayOf(Lang.t("跟随系统"), Lang.t("浅色"), Lang.t("深色"))
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes.forEachIndexed { index, label ->
            group.addView(TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (index == checked) Ui.buttonText(this@SettingsActivity) else Ui.secondaryText(this@SettingsActivity))
                background = if (index == checked) {
                    Ui.glassButton(this@SettingsActivity, Ui.buttonPrimary(this@SettingsActivity))
                } else {
                    Ui.rounded(Color.TRANSPARENT, 10f, d)
                }
                setPadding(Ui.dp(12, d), Ui.dp(9, d), Ui.dp(12, d), Ui.dp(9, d))
                Ui.pressAnimation(this)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = Ui.dp(6, d)
                }
                setOnClickListener {
                    val mode = when (index) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                    prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
                    AppCompatDelegate.setDefaultNightMode(mode)
                    recreate()
                }
            })
        }
        return group
    }

    override fun finish() {
        if (finishing) return
        finishing = true
        super.finish()
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out)
    }
}
