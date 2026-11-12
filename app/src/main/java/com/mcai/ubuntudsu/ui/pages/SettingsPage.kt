package com.mcai.ubuntudsu.ui.pages

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcai.ubuntudsu.ProcessManagerActivity
import com.mcai.ubuntudsu.Lang
import com.mcai.ubuntudsu.MainActivity
import com.mcai.ubuntudsu.R
import com.mcai.ubuntudsu.ui.Ui

// 「多功能」页：功能卡布局（每行 2 张卡片，逐行往下排），聚合扩展功能入口
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
                text = Lang.t("阿明®有趣小程序")
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, Ui.dp(18, d))
            })
        }

        // 功能入口（卡片式，每行 2 张）；沿用历史保存的排列顺序
        val gridPrefs = activity.getPreferences(Activity.MODE_PRIVATE)
        val savedOrder = gridPrefs.getString("icon_order", null)
        val keyToItem = mapOf(
            "theme" to GridItem("设置", R.drawable.icon_settings, "#5B6CFF") { view -> openSettings(view) },
            // v2.0.38：语言直达卡（老版本保存的 icon_order 里没有 lang，会自动追加到末尾）
            "lang" to GridItem(Lang.t("语言"), R.drawable.ic_tools, "#00897B") { view -> openLanguageDialog(view) },
            "process" to GridItem("进程管理", R.drawable.icon_process_manager, "#E53935") { view -> openProcessManager(view) },
            "rom" to GridItem("ROM固件", R.drawable.icon_rom_firmware, "#FF6B35") { view -> openRomFirmware(view) },
            "otg" to GridItem("OTG助手", R.drawable.icon_otg, "#00897B") { view -> openOtgAssistant(view) },
            "update" to GridItem("软件更新", R.drawable.icon_update_color, "#2D64AA") { _ -> checkUpdate() },
            "usbboot" to GridItem("U盘启动", R.drawable.icon_usb_boot, "#E65100") { view -> openUsbBoot(view) },
            "worktime" to GridItem("日历工时记", R.drawable.icon_worktime, "#00A98F") { view -> openWorkTime(view) },
            "dnatools" to GridItem("ROM移植", R.drawable.icon_rom_port, "#7C4DFF") { view -> openDnaTools(view) },
            // v2.0.28：入口位置对调 —— gunyahVM 上提为底部第 3 格 tab，
            // Linux 容器功能从原 tab 下放到这里，作为「多功能」页的一张卡。
            "linux" to GridItem("Linux", R.drawable.icon_linux_modern, "#E95420") { view -> openLinuxContainer(view) },
        )
        val defaultKeys = keyToItem.keys.toList()
        val orderedKeys: List<String> = if (savedOrder != null) {
            // v2.0.28：老版本保存的顺序里是 "gunyahvm"，迁移为 "linux"，
            // 让 Linux 卡原位继承（不会掉到末尾、也不会丢卡）。
            val parsed = savedOrder.split(",").map { if (it == "gunyahvm") "linux" else it }
            val valid = parsed.filter { it in keyToItem }.distinct()
            valid + defaultKeys.filter { it !in valid }
        } else defaultKeys

        // 卡片式布局：每行 2 张功能卡，逐行往下排（已移除长按拖动重排）
        val rowContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        for ((rowIndex, rowItems) in orderedKeys.chunked(2).withIndex()) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (rowIndex > 0) topMargin = Ui.dp(12, d)
                }
            }
            rowItems.forEachIndexed { colIndex, key ->
                val item = keyToItem[key]!!
                val cardView = buildGridCard(item, d, wallpaperSync)
                row.addView(
                    cardView,
                    LinearLayout.LayoutParams(0, Ui.dp(84, d), 1f).apply {
                        if (colIndex > 0) leftMargin = Ui.dp(12, d)
                    },
                )
            }
            rowContainer.addView(row)
        }
        page.addView(rowContainer)

        return frame
    }

    private data class GridItem(
        val title: String,
        val iconRes: Int,
        val tintColor: String,
        val onClick: (View) -> Unit,
    )

    /** 功能卡（卡片式入口）：复用全局 Ui.featureCard，与 Linux 页保持同一视觉语言 */
    private fun buildGridCard(item: GridItem, d: Float, wallpaperSync: Boolean = false): View =
        Ui.featureCard(
            activity,
            item.iconRes,
            android.graphics.Color.parseColor(item.tintColor),
            item.title,
            null,
            wallpaperSync,
            false,
            item.onClick,
        )

    /** v2.0.38：语言三选一直达；切换后重启任务栈，强制重建所有已缓存页面。 */
    private fun openLanguageDialog(iconView: View) {
        val opts = arrayOf(Lang.optFollow(), Lang.optChinese(), Lang.optEnglish())
        val modes = arrayOf(Lang.AUTO, Lang.ZH, Lang.EN)
        val prefs = activity.getSharedPreferences(Lang.PREF, Activity.MODE_PRIVATE)
        val cur = prefs.getString(Lang.KEY_MODE, Lang.AUTO) ?: Lang.AUTO
        AlertDialog.Builder(activity)
            .setTitle(Lang.title())
            .setMessage(Lang.hint())
            .setSingleChoiceItems(opts, modes.indexOf(cur).coerceAtLeast(0)) { dlg, which ->
                Lang.setMode(activity, modes[which])
                dlg.dismiss()
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
                activity.finish()
            }
            .setNegativeButton(Lang.t("取消")) { dlg, _ -> dlg.dismiss() }
            .show()
    }

    /** 打开全屏设置页（个性主题 + 语言 + 关于信息） */
    private fun openSettings(iconView: View) {
        launchScaleUp(iconView, com.mcai.ubuntudsu.SettingsActivity::class.java)
    }

    private fun launchScaleUp(iconView: View, target: Class<out Activity>) {
        val intent = Intent(activity, target)
        val options = android.app.ActivityOptions.makeScaleUpAnimation(iconView, 0, 0, iconView.width, iconView.height)
        activity.startActivity(intent, options.toBundle())
    }

    private fun openProcessManager(iconView: View) {
        launchScaleUp(iconView, com.mcai.ubuntudsu.ProcessManagerActivity::class.java)
    }

    private fun openRomFirmware(iconView: View) {
        launchScaleUp(iconView, com.mcai.ubuntudsu.RomActivity::class.java)
    }

    private fun openOtgAssistant(iconView: View) {
        launchScaleUp(iconView, com.mcai.ubuntudsu.OtgAssistantActivity::class.java)
    }

    private fun openUsbBoot(iconView: View) {
        launchScaleUp(iconView, com.mcai.ubuntudsu.UsbBootActivity::class.java)
    }

    private fun openWorkTime(iconView: View) {
        launchScaleUp(iconView, com.mcai.ubuntudsu.WorkTimeActivity::class.java)
    }

    private fun openDnaTools(iconView: View) {
        // 进入 DNA 工具箱首页（工具链状态 + 工程管理 + 分解/合成/转换/其他 全部功能入口）
        launchScaleUp(iconView, com.mcai.ubuntudsu.DnaToolsActivity::class.java)
    }

    private fun openLinuxContainer(iconView: View) {
        // v2.0.28：Linux 容器（容器终端 / 桌面环境 / 文件管理 / 安装 / 卸载 rootfs），
        // 原 MainActivity 第 3 个 tab 的完整内容，现由此卡进入。
        launchScaleUp(iconView, com.mcai.ubuntudsu.LinuxContainerActivity::class.java)
    }


    // 在线检查更新：GitHub Releases 最新版比对本地版本，提示 / 下载 / 安装
    private fun checkUpdate() {
        val d = activity.resources.displayMetrics.density
        val checking = android.app.AlertDialog.Builder(activity)
            .setTitle(Lang.t("检查更新"))
            .setMessage(Lang.t("正在检测新版本..."))
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
                            .setTitle(Lang.t("检查更新"))
                            .setMessage(Lang.t("未能获取更新信息（无 Release 或网络异常），请稍后重试。"))
                            .setPositiveButton(Lang.t("关闭"), null)
                            .show()
                        !com.mcai.ubuntudsu.core.AppUpdater.isNewer(local, info.version) ->
                            AlertDialog.Builder(activity)
                                .setTitle(Lang.t("检查更新"))
                                .setMessage("当前已是最新版本（v$local）。")
                                .setPositiveButton(Lang.t("关闭"), null)
                                .show()
                        else -> AlertDialog.Builder(activity)
                            .setTitle("发现新版本 v${info.version}")
                            .setMessage(
                                (if (info.notes.isBlank()) "" else "${info.notes}\n\n") +
                                    Lang.t("下载并安装新版本？"),
                            )
                            .setPositiveButton("下载并安装") { _, _ -> downloadAndInstall(info) }
                            .setNegativeButton(Lang.t("取消"), null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    runCatching { checking.dismiss() }
                    AlertDialog.Builder(activity)
                        .setTitle(Lang.t("检查更新"))
                        .setMessage(Lang.t("检测过程出现异常，请稍后重试。"))
                        .setPositiveButton(Lang.t("关闭"), null)
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
        percentText.text = Lang.t("连接中…")
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
                            line.startsWith("aria2c 失败") || line.startsWith("文件大小校验") ||
                                line.startsWith("SHA-256 校验") -> {
                                lastReason = line.take(90)
                                false
                            }
                            line.startsWith("正在测速") || line.startsWith("线路测速结果") -> {
                                statusSuffix = " · ${line.take(30)}"
                                true
                            }
                            line.contains("切换") -> {
                                statusSuffix = " · ${line.take(26)}"
                                lastReason = line.take(90)
                                true
                            }
                            line.startsWith("线路 ") -> {
                                statusSuffix = " · ${line.take(30)}"
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
                            .setTitle(Lang.t("下载失败"))
                            .setMessage(
                                if (lastReason.isNotBlank()) "所有线路均下载失败：\n$lastReason"
                                else Lang.t("所有线路均下载失败，请稍后重试。")
                            )
                            .setPositiveButton(Lang.t("关闭"), null)
                            .show()
                    }
                    return@Thread
                }
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    dialog.setTitle("正在安装 v${info.version}")
                    runCatching { progress.isIndeterminate = true }
                    percentText.text = Lang.t("后台安装中，请稍候…")
                }
                val install = com.mcai.ubuntudsu.core.AppUpdater.silentInstall(target)
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    runCatching { dialog.dismiss() }
                    if (install.success) {
                        AlertDialog.Builder(activity)
                            .setTitle(Lang.t("安装完成"))
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
                            .setTitle(Lang.t("需手动安装"))
                            .setMessage("已下载 v${info.version}。\n\n自动安装未成功：${install.message}")
                            .setPositiveButton("调用系统安装") { _, _ ->
                                runCatching { com.mcai.ubuntudsu.core.AppUpdater.install(activity, target) }
                            }
                            .setNegativeButton(Lang.t("取消"), null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    runCatching { dialog.dismiss() }
                    AlertDialog.Builder(activity)
                        .setTitle(Lang.t("下载失败"))
                        .setMessage(Lang.t("新版本下载出现异常，请稍后重试。"))
                        .setPositiveButton(Lang.t("关闭"), null)
                        .show()
                }
            }
        }.start()
    }

    /**
     * 将系统壁纸设置为多功能页面的背景（仅此页面，不影响其他界面）
     * 多种方式降级获取壁纸，确保兼容性
     */
    private fun applyWallpaperBackground(page: View) {
        val dm = activity.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // 多种方式尝试获取壁纸
        val bmp = tryGetWallpaperFromDrawable(screenW, screenH)
            ?: tryGetWallpaperFromFile(screenW, screenH)
            ?: tryGetWallpaperViaRoot(screenW, screenH)
            ?: tryGetBuiltInWallpaper(screenW, screenH)

        if (bmp != null) {
            val bd = android.graphics.drawable.BitmapDrawable(activity.resources, bmp)
            bd.gravity = android.view.Gravity.CENTER
            page.background = bd
        } else {
            // 全部失败时回退全局液态玻璃背景（日间/夜间自适应）
            Ui.animateLiquidBackground(page)
        }
    }

    /**
     * 方式1：从 WallpaperManager.getDrawable() 获取壁纸
     */
    private fun tryGetWallpaperFromDrawable(screenW: Int, screenH: Int): android.graphics.Bitmap? {
        return runCatching {
            val wm = android.app.WallpaperManager.getInstance(activity)
            val drawable = wm.drawable ?: return@runCatching null

            val srcW = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else screenW
            val srcH = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else screenH

            val bmp = android.graphics.Bitmap.createBitmap(srcW, srcH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, srcW, srcH)
            drawable.draw(canvas)

            centerCropScale(bmp, screenW, screenH)
        }.getOrNull()
    }

    /**
     * 方式2：从 WallpaperManager.getWallpaperFile 获取壁纸文件并解码
     */
    private fun tryGetWallpaperFromFile(screenW: Int, screenH: Int): android.graphics.Bitmap? {
        return runCatching {
            val wm = android.app.WallpaperManager.getInstance(activity)

            // 先尝试 FLAG_SYSTEM，再尝试 FLAG_LOCK（部分设备锁屏壁纸可用）
            val wallFile = wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM)
                ?: wm.getWallpaperFile(android.app.WallpaperManager.FLAG_LOCK)
                ?: return@runCatching null

            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFileDescriptor(wallFile.fileDescriptor, null, opts)

            val imgW = opts.outWidth
            val imgH = opts.outHeight
            if (imgW <= 0 || imgH <= 0) { wallFile.close(); return@runCatching null }

            var sampleSize = 1
            val maxDim = maxOf(imgW, imgH)
            val targetMax = maxOf(screenW, screenH) * 2
            while (maxDim / (sampleSize * 2) > targetMax) sampleSize *= 2

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
     * 方式3：通过 root shell 读取系统壁纸文件
     * MIUI/HyperOS 壁纸存储在 /data/system/ 或 /data/system/users/0/
     */
    private fun tryGetWallpaperViaRoot(screenW: Int, screenH: Int): android.graphics.Bitmap? {
        val wallpaperPaths = listOf(
            "/data/system/users/0/wallpaper",
            "/data/system/wallpaper",
            "/data/system/users/0/wallpaper_orig",
            "/data/system/wallpaper_orig",
        )
        for (path in wallpaperPaths) {
            val result = runCatching {
                val tmpFile = java.io.File(activity.cacheDir, "wallpaper_tmp")
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '$path' > '${tmpFile.absolutePath}' && chmod 644 '${tmpFile.absolutePath}'"))
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!tmpFile.exists() || tmpFile.length() < 100) return@runCatching null

                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(tmpFile.absolutePath, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null

                var sampleSize = 1
                val maxDim = maxOf(opts.outWidth, opts.outHeight)
                val targetMax = maxOf(screenW, screenH) * 2
                while (maxDim / (sampleSize * 2) > targetMax) sampleSize *= 2

                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                val bmp = android.graphics.BitmapFactory.decodeFile(tmpFile.absolutePath, decodeOpts)
                tmpFile.delete()
                if (bmp == null || bmp.width <= 0) return@runCatching null

                centerCropScale(bmp, screenW, screenH)
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    /**
     * 方式4：获取系统内置默认壁纸
     */
    private fun tryGetBuiltInWallpaper(screenW: Int, screenH: Int): android.graphics.Bitmap? {
        return runCatching {
            val wm = android.app.WallpaperManager.getInstance(activity)
            val drawable = wm.builtInDrawable ?: return@runCatching null

            val srcW = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else screenW
            val srcH = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else screenH

            val bmp = android.graphics.Bitmap.createBitmap(srcW, srcH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, srcW, srcH)
            drawable.draw(canvas)

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
