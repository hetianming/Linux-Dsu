package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.ui.Ui

// 「更多」页：聚合主题设置等扩展功能入口，后续新功能在此追加
class SettingsPage(
    private val activity: Activity,
    private val onThemeChanged: () -> Unit,
) {
    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
        }
        // 标题："更多"（设置入口仅在首页）
        page.addView(TextView(activity).apply {
            text = "更多"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, 0, 0, Ui.dp(14, d))
        })

        // 功能区：主题设置入口（彩色图标，无边框）
        page.addView(
            Ui.entryButton(activity, "主题设置", "跟随系统 · 浅色 · 深色", ">", "#5B6CFF", R.drawable.icon_theme_color, framed = false) {
                showThemeDialog()
            },
        )
        // 在线检查更新：读取 GitHub Releases 最新版，下载 APK 自动安装
        page.addView(
            Ui.entryButton(
                activity, "检查更新", "检测 GitHub 新版本 · 下载安装", ">", "#2D64AA",
                R.drawable.icon_update_color, framed = false,
            ) { checkUpdate() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(8, d) },
        )

        // 更多功能占位
        val moreCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(18, d), Ui.dp(20, d), Ui.dp(18, d))
            background = Ui.glassSurface(activity, 24f)
            elevation = Ui.dp(4, d).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(14, d) }
        }
        moreCard.addView(TextView(activity).apply {
            text = "更多功能"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
        })
        moreCard.addView(TextView(activity).apply {
            text = "后续版本将在此处提供扩展功能，敬请期待。"
            textSize = 12f
            setTextColor(Ui.secondaryText(activity))
            setPadding(0, Ui.dp(6, d), 0, Ui.dp(8, d))
        })
        page.addView(moreCard)
        return page
    }

    private fun showThemeDialog() {
        val modes = arrayOf("跟随系统", "浅色", "深色")
        val current = activity.getPreferences(Activity.MODE_PRIVATE).getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val checked = when (current) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }
        AlertDialog.Builder(activity)
            .setTitle("夜间模式")
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                val mode = when (which) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                activity.getPreferences(Activity.MODE_PRIVATE).edit().putInt("theme_mode", mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode)
                onThemeChanged()
                dialog.dismiss()
            }
            .show()
    }

    // 在线检查更新：GitHub Releases 最新版比对本地版本，提示 / 下载 / 安装
    private fun checkUpdate() {
        val d = activity.resources.displayMetrics.density
        val checking = android.app.AlertDialog.Builder(activity)
            .setTitle("检查更新")
            .setMessage("正在检测新版本...")
            .setCancelable(false)
            .show()
        Thread {
            val local = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"
            val info = com.mcai.ubuntudsu.core.AppUpdater.fetchLatest()
            activity.runOnUiThread {
                checking.dismiss()
                when {
                    info == null -> AlertDialog.Builder(activity)
                        .setTitle("检查更新")
                        .setMessage("未能获取更新信息（无 Release 或网络异常），请稍后重试。")
                        .setPositiveButton("关闭", null)
                        .show()
                    !com.mcai.ubuntudsu.core.AppUpdater.isNewer(local, info.version) ->
                        AlertDialog.Builder(activity)
                            .setTitle("检查更新")
                            .setMessage("当前已是最新版本（v$local）。")
                            .setPositiveButton("关闭", null)
                            .show()
                    else -> AlertDialog.Builder(activity)
                        .setTitle("发现新版本 v${info.version}")
                        .setMessage(
                            (if (info.notes.isBlank()) "" else "${info.notes}\n\n") +
                                "下载并安装新版本？",
                        )
                        .setPositiveButton("下载并安装") { _, _ -> downloadAndInstall(info) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }.start()
    }

    private fun downloadAndInstall(info: com.mcai.ubuntudsu.core.AppUpdater.ReleaseInfo) {
        val d = activity.resources.displayMetrics.density
        val progress = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = Ui.pillProgressDrawable(activity)
        }
        val percentText = Ui.percentTextView(activity)
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("正在下载 v${info.version}")
            .setView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(20, d), Ui.dp(6, d), Ui.dp(20, d), Ui.dp(4, d))
                    addView(progress)
                    addView(percentText.apply {
                        setPadding(0, Ui.dp(6, d), 0, 0)
                    })
                },
            )
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> }
            .show()
        Thread {
            val target = com.mcai.ubuntudsu.core.AppUpdater.download(
                info, java.io.File(activity.filesDir, "downloads"),
            ) { percent ->
                activity.runOnUiThread {
                    progress.progress = percent
                    percentText.text = "$percent %"
                }
            }
            activity.runOnUiThread {
                dialog.dismiss()
                if (target != null) {
                    com.mcai.ubuntudsu.core.AppUpdater.install(activity, target)
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle("下载失败")
                        .setMessage("新版本下载失败，请稍后重试。")
                        .setPositiveButton("关闭", null)
                        .show()
                }
            }
        }.start()
    }
}
