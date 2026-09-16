package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import com.mcai.ubuntudsu.ProcessManagerActivity
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.ui.Ui

// 「更多」页：桌面图标式网格布局，聚合扩展功能入口
class SettingsPage(
    private val activity: Activity,
    private val onThemeChanged: () -> Unit,
) {
    fun build(): View {
        val d = activity.resources.displayMetrics.density
        val prefs = activity.getPreferences(Activity.MODE_PRIVATE)
        val wallpaperSync = prefs.getBoolean("wallpaper_sync", false)

        // 外层 Frame：负责壁纸背景（填满全屏，不受内层 padding 影响）
        val frame = android.widget.FrameLayout(activity).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (wallpaperSync) {
                // 壁纸同步时：填充全屏，图标垂直居中，上下留出状态栏/导航栏空间
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                gravity = Gravity.CENTER
                setPadding(Ui.dp(16, d), Ui.dp(40, d), Ui.dp(16, d), Ui.dp(90, d))
            } else {
                setPadding(Ui.dp(16, d), Ui.dp(12, d), Ui.dp(16, d), Ui.dp(16, d))
            }
        }

        // 壁纸背景设置在外层 Frame 上
        if (wallpaperSync) {
            applyWallpaperBackground(frame)
            // 顶部渐变遮罩（保护状态栏区域可读性）
            val topScrim = View(activity).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(android.graphics.Color.argb(120, 0, 0, 0), android.graphics.Color.TRANSPARENT),
                )
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(80, d),
                    Gravity.TOP,
                )
            }
            frame.addView(topScrim)
            // 底部渐变遮罩（保护导航栏区域可读性）
            val bottomScrim = View(activity).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(android.graphics.Color.argb(150, 0, 0, 0), android.graphics.Color.TRANSPARENT),
                )
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(100, d),
                    Gravity.BOTTOM,
                )
            }
            frame.addView(bottomScrim)
        }

        frame.addView(page)

        // 标题（壁纸同步时隐藏，图标上移替代）
        if (!wallpaperSync) {
            page.addView(TextView(activity).apply {
                text = "TMOS桌面"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                setPadding(0, 0, 0, Ui.dp(18, d))
            })
        }

        // 功能图标网格（每行 4 个）
        val items = listOf(
            GridItem("主题样式", R.drawable.icon_theme_color, "#5B6CFF") { showThemeDialog() },
            GridItem("进程管理", R.drawable.icon_process_manager, "#E53935") { openProcessManager() },
            GridItem("ROM固件", R.drawable.icon_rom_firmware, "#FF6B35") { openRomFirmware() },
            GridItem("软件更新", R.drawable.icon_update_color, "#2D64AA") { checkUpdate() },
        )

        // 网格布局：每行 4 个，居中排列，以后新增图标自动换行居中
        val rows = items.chunked(4)
        for (rowItems in rows) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                // 居中：不满 4 个时整行居中，不靠左堆
                gravity = Gravity.CENTER
            }
            for (item in rowItems) {
                val iconView = buildGridIcon(item, d, wallpaperSync)
                row.addView(iconView)
            }
            page.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (page.childCount > 1) topMargin = Ui.dp(16, d)
            })
        }

        return frame
    }

    private data class GridItem(
        val title: String,
        val iconRes: Int,
        val tintColor: String,
        val onClick: () -> Unit,
    )

    private fun buildGridIcon(item: GridItem, d: Float, wallpaperSync: Boolean = false): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            setOnClickListener { item.onClick() }
            // 固定宽度，配合行 gravity=CENTER 实现居中排列
            layoutParams = LinearLayout.LayoutParams(Ui.dp(76, d), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        Ui.pressAnimation(container)

        // 图标
        val iconView = ImageView(activity).apply {
            setImageResource(item.iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(Ui.dp(56, d), Ui.dp(56, d))
        }
        container.addView(iconView)

        // 文字标签
        container.addView(TextView(activity).apply {
            text = item.title
            textSize = 12f
            setTextColor(if (wallpaperSync) android.graphics.Color.WHITE else Ui.primaryText(activity))
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(8, d), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 壁纸模式：无偏移强阴影，形成均匀黑色描边，白壁纸也清晰可见
            if (wallpaperSync) {
                setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
            }
        })

        return container
    }

    private fun showThemeDialog() {
        val d = activity.resources.displayMetrics.density
        val prefs = activity.getPreferences(Activity.MODE_PRIVATE)
        val current = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val wallpaperSync = prefs.getBoolean("wallpaper_sync", false)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(20, d), Ui.dp(12, d), Ui.dp(20, d), Ui.dp(8, d))
        }

        // === 主题模式 ===
        container.addView(TextView(activity).apply {
            text = "主题模式"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.primaryText(activity))
            setPadding(0, 0, 0, Ui.dp(8, d))
        })

        val modes = arrayOf("跟随系统", "浅色", "深色")
        val checked = when (current) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }
        val modeGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        modes.forEachIndexed { index, label ->
            modeGroup.addView(TextView(activity).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (index == checked) Ui.buttonText(activity) else Ui.secondaryText(activity))
                background = if (index == checked) {
                    Ui.glassButton(activity, Ui.buttonPrimary(activity))
                } else {
                    Ui.rounded(android.graphics.Color.TRANSPARENT, 10f, d)
                }
                setPadding(Ui.dp(12, d), Ui.dp(6, d), Ui.dp(12, d), Ui.dp(6, d))
                Ui.pressAnimation(this)
                layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = Ui.dp(4, d)
                }
                setOnClickListener {
                    val mode = when (index) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                    prefs.edit().putInt("theme_mode", mode).apply()
                    AppCompatDelegate.setDefaultNightMode(mode)
                    onThemeChanged()
                }
            })
        }
        container.addView(modeGroup)

        // === 分隔线 ===
        container.addView(View(activity).apply {
            setBackgroundColor(Ui.secondaryText(activity))
            alpha = 0.2f
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(1, d),
            ).apply { topMargin = Ui.dp(16, d); bottomMargin = Ui.dp(16, d) }
        })

        // === 壁纸同步 ===
        val wallpaperRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wallpaperRow.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(activity).apply {
                text = "系统壁纸同步"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
            })
            addView(TextView(activity).apply {
                text = "开启后应用背景跟随系统壁纸"
                textSize = 11f
                setTextColor(Ui.secondaryText(activity))
                setPadding(0, Ui.dp(2, d), 0, 0)
            })
        })

        // 开关按钮
        val toggleBtn = TextView(activity).apply {
            text = if (wallpaperSync) "已开启" else "已关闭"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Ui.buttonText(activity))
            background = Ui.glassButton(
                activity,
                if (wallpaperSync) Ui.buttonSuccess(activity) else Ui.secondaryText(activity),
            )
            setPadding(Ui.dp(14, d), Ui.dp(6, d), Ui.dp(14, d), Ui.dp(6, d))
            Ui.pressAnimation(this)
            setOnClickListener {
                val newState = !wallpaperSync
                prefs.edit().putBoolean("wallpaper_sync", newState).apply()
                text = if (newState) "已开启" else "已关闭"
                background = Ui.glassButton(
                    activity,
                    if (newState) Ui.buttonSuccess(activity) else Ui.secondaryText(activity),
                )
                onThemeChanged()
            }
        }
        wallpaperRow.addView(toggleBtn)
        container.addView(wallpaperRow)

        AlertDialog.Builder(activity)
            .setTitle("主题样式")
            .setView(container)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openProcessManager() {
        val intent = Intent(activity, com.mcai.ubuntudsu.ProcessManagerActivity::class.java)
        activity.startActivity(intent)
    }

    private fun openRomFirmware() {
        val intent = Intent(activity, com.mcai.ubuntudsu.RomActivity::class.java)
        activity.startActivity(intent)
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
            try {
                val local = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"
                val info = com.mcai.ubuntudsu.core.AppUpdater.fetchLatest()
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    runCatching { checking.dismiss() }
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
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    runCatching { checking.dismiss() }
                    AlertDialog.Builder(activity)
                        .setTitle("检查更新")
                        .setMessage("检测过程出现异常，请稍后重试。")
                        .setPositiveButton("关闭", null)
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
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
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
            .setNegativeButton("取消") { _, _ -> cancelled.set(true) }
            .show()
        percentText.text = "连接中…"
        Thread {
            try {
                var shownPercent = 0
                var statusSuffix = ""
                var lastReason = ""
                val render: () -> Unit = {
                    activity.runOnUiThread {
                        if (!activity.isFinishing) {
                            progress.progress = shownPercent
                            percentText.text = if (shownPercent <= 0) "连接中…$statusSuffix" else "$shownPercent %$statusSuffix"
                        }
                    }
                }
                val target = com.mcai.ubuntudsu.core.AppUpdater.download(
                    activity, info, java.io.File(activity.filesDir, "downloads"),
                    onProgress = { percent ->
                        shownPercent = percent
                        render()
                    },
                    onLog = { line ->
                        val handled = when {
                            line.contains("CN:") -> {
                                val speed = Regex("""DL:([0-9.]+\s*\w+)""").find(line)?.groupValues?.get(1)?.trim()
                                val conns = Regex("""CN:(\d+)""").find(line)?.groupValues?.get(1)
                                statusSuffix = buildString {
                                    speed?.let { append(" · $it/s") }
                                    conns?.let { append(" · $it 线程") }
                                }
                                true
                            }
                            line.startsWith("aria2c 失败") || line.startsWith("文件大小校验") -> {
                                lastReason = line.take(90)
                                false
                            }
                            line.contains("切换") -> {
                                statusSuffix = " · ${line.take(26)}"
                                lastReason = line.take(90)
                                true
                            }
                            line.startsWith("线路 ") -> {
                                statusSuffix = ""
                                true
                            }
                            else -> false
                        }
                        if (handled) render()
                    },
                    isCancelled = { cancelled.get() },
                )
                if (target == null) {
                    activity.runOnUiThread {
                        if (activity.isFinishing) return@runOnUiThread
                        runCatching { dialog.dismiss() }
                        if (cancelled.get()) return@runOnUiThread
                        AlertDialog.Builder(activity)
                            .setTitle("下载失败")
                            .setMessage(
                                if (lastReason.isNotBlank()) "所有线路均下载失败：\n$lastReason"
                                else "所有线路均下载失败，请稍后重试。"
                            )
                            .setPositiveButton("关闭", null)
                            .show()
                    }
                    return@Thread
                }
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    dialog.setTitle("正在安装 v${info.version}")
                    runCatching { progress.isIndeterminate = true }
                    percentText.text = "后台安装中，请稍候…"
                }
                val install = com.mcai.ubuntudsu.core.AppUpdater.silentInstall(target)
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    runCatching { dialog.dismiss() }
                    if (install.success) {
                        AlertDialog.Builder(activity)
                            .setTitle("安装完成")
                            .setMessage("v${info.version} 已安装成功，重启应用后生效。")
                            .setCancelable(false)
                            .setPositiveButton("重启应用") { _, _ ->
                                runCatching {
                                    val relaunch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                                    relaunch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    relaunch?.let { activity.startActivity(it) }
                                    Runtime.getRuntime().exit(0)
                                }
                            }
                            .show()
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("需手动安装")
                            .setMessage("已下载 v${info.version}。\n\n自动安装未成功：${install.message}")
                            .setPositiveButton("调用系统安装") { _, _ ->
                                runCatching { com.mcai.ubuntudsu.core.AppUpdater.install(activity, target) }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    runCatching { dialog.dismiss() }
                    AlertDialog.Builder(activity)
                        .setTitle("下载失败")
                        .setMessage("新版本下载出现异常，请稍后重试。")
                        .setPositiveButton("关闭", null)
                        .show()
                }
            }
        }.start()
    }

    /**
     * 将系统壁纸设置为更多页面的背景（仅此页面，不影响其他界面）
     * 多种方式降级获取壁纸，确保兼容性
     */
    private fun applyWallpaperBackground(page: View) {
        val dm = activity.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // 方式1：通过 WallpaperManager.getDrawable() 获取
        val bmp = tryGetWallpaperFromDrawable(screenW, screenH)
            // 方式2：通过 getWallpaperFile 获取文件并解码
            ?: tryGetWallpaperFromFile(screenW, screenH)

        if (bmp != null) {
            val bd = android.graphics.drawable.BitmapDrawable(activity.resources, bmp)
            bd.gravity = android.view.Gravity.CENTER
            page.background = bd
        } else {
            // 方式3：全部失败时使用深色背景
            page.setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
        }
    }

    /**
     * 方式1：从 WallpaperManager.drawable 获取壁纸
     */
    private fun tryGetWallpaperFromDrawable(screenW: Int, screenH: Int): android.graphics.Bitmap? {
        return runCatching {
            val wm = android.app.WallpaperManager.getInstance(activity)
            val drawable = wm.drawable ?: return@runCatching null

            // drawable 的 intrinsicWidth 可能为 -1（未知尺寸），需特殊处理
            val srcW: Int
            val srcH: Int
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                srcW = drawable.intrinsicWidth
                srcH = drawable.intrinsicHeight
            } else {
                // 尺寸未知时使用屏幕尺寸
                srcW = screenW
                srcH = screenH
            }

            val bmp = android.graphics.Bitmap.createBitmap(srcW, srcH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, srcW, srcH)
            drawable.draw(canvas)

            // 检查 bitmap 是否全透明（空壁纸）
            if (bmp.sameAs(android.graphics.Bitmap.createBitmap(srcW, srcH, android.graphics.Bitmap.Config.ARGB_8888))) {
                bmp.recycle()
                return@runCatching null
            }

            centerCropScale(bmp, screenW, screenH)
        }.getOrNull()
    }

    /**
     * 方式2：从 WallpaperManager.getWallpaperFile 获取壁纸文件并解码
     */
    private fun tryGetWallpaperFromFile(screenW: Int, screenH: Int): android.graphics.Bitmap? {
        return runCatching {
            val wm = android.app.WallpaperManager.getInstance(activity)
            val wallFile = wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM) ?: return@runCatching null

            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFileDescriptor(wallFile.fileDescriptor, null, opts)

            // 计算 inSampleSize
            val imgW = opts.outWidth
            val imgH = opts.outHeight
            if (imgW <= 0 || imgH <= 0) return@runCatching null

            var sampleSize = 1
            var maxDim = maxOf(imgW, imgH)
            val targetMax = maxOf(screenW, screenH) * 2
            while (maxDim / (sampleSize * 2) > targetMax) {
                sampleSize *= 2
            }

            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val bmp = android.graphics.BitmapFactory.decodeFileDescriptor(wallFile.fileDescriptor, null, decodeOpts)
            wallFile.close()

            if (bmp == null || bmp.width <= 0) return@runCatching null

            centerCropScale(bmp, screenW, screenH)
        }.getOrNull()
    }

    /**
     * CENTER_CROP 缩放：填满屏幕，保持比例，裁剪多余
     */
    private fun centerCropScale(src: android.graphics.Bitmap, targetW: Int, targetH: Int): android.graphics.Bitmap {
        val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        if (scaled !== src) src.recycle()
        return scaled
    }
}
