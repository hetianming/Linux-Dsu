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
import com.mcai.ubuntudsu.R
import android.view.MotionEvent
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
                text = "阿明®有趣小程序"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.primaryText(activity))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, Ui.dp(18, d))
            })
        }

        // 功能图标网格（每行 4 个），顺序可持久化
        val gridPrefs = activity.getPreferences(Activity.MODE_PRIVATE)
        val savedOrder = gridPrefs.getString("icon_order", null)
        val keyToItem = mapOf(
            "theme" to GridItem("设置", R.drawable.icon_settings, "#5B6CFF") { view -> openSettings(view) },
            "process" to GridItem("进程管理", R.drawable.icon_process_manager, "#E53935") { view -> openProcessManager(view) },
            "rom" to GridItem("ROM固件", R.drawable.icon_rom_firmware, "#FF6B35") { view -> openRomFirmware(view) },
            "otg" to GridItem("OTG助手", R.drawable.icon_otg, "#00897B") { view -> openOtgAssistant(view) },
            "update" to GridItem("软件更新", R.drawable.icon_update_color, "#2D64AA") { _ -> checkUpdate() },
            "usbboot" to GridItem("U盘启动", R.drawable.icon_usb_boot, "#E65100") { view -> openUsbBoot(view) },
            "worktime" to GridItem("日历工时记", R.drawable.icon_worktime, "#00A98F") { view -> openWorkTime(view) },
            "dnatools" to GridItem("ROM移植", R.drawable.icon_rom_port, "#7C4DFF") { view -> openDnaTools(view) },
        )
        val defaultKeys = keyToItem.keys.toList()
        val orderedKeys: List<String> = if (savedOrder != null) {
            val parsed = savedOrder.split(",")
            val valid = parsed.filter { it in keyToItem }
            valid + defaultKeys.filter { it !in valid }
        } else defaultKeys

        // 卡片式布局：每行 2 张功能卡，逐行往下排，支持长按拖动交换位置
        val iconViews = mutableListOf<Pair<View, String>>()
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
                iconViews.add(cardView to key)
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

        setupDragReorder(iconViews, rowContainer, gridPrefs, keyToItem, orderedKeys)

        return frame
    }

    /** 长按拖动重排图标：跟手移动，松手时与最近的图标交换位置并重建网格 */
    private fun setupDragReorder(
        iconViews: MutableList<Pair<View, String>>,
        rowContainer: LinearLayout,
        prefs: android.content.SharedPreferences,
        keyToItem: Map<String, GridItem>,
        initialKeys: List<String>,
    ) {
        val d = activity.resources.displayMetrics.density

        for ((v, key) in iconViews) {
            v.setOnLongClickListener {
                // 视觉反馈
                v.elevation = Ui.dp(24, d).toFloat()
                v.scaleX = 1.2f
                v.scaleY = 1.2f
                v.alpha = 0.9f

                var startRawX = 0f
                var startRawY = 0f
                var grabbed = false

                v.isLongClickable = false
                v.setOnTouchListener { view, event ->
                    if (!grabbed && event.action == MotionEvent.ACTION_MOVE) {
                        grabbed = true
                        startRawX = event.rawX
                        startRawY = event.rawY
                    }
                    when (event.action) {
                        MotionEvent.ACTION_MOVE -> {
                            if (grabbed) {
                                v.translationX = event.rawX - startRawX
                                v.translationY = event.rawY - startRawY
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            // 找到松手位置最近的其他图标
                            var targetKey: String? = null
                            var minDist = Float.MAX_VALUE
                            for ((otherV, otherKey) in iconViews) {
                                if (otherKey == key) continue
                                val oLoc = IntArray(2)
                                otherV.getLocationOnScreen(oLoc)
                                val ocx = oLoc[0] + otherV.width / 2f
                                val ocy = oLoc[1] + otherV.height / 2f
                                val vLoc = IntArray(2)
                                view.getLocationOnScreen(vLoc)
                                val vcx = vLoc[0] + view.width / 2f
                                val vcy = vLoc[1] + view.height / 2f
                                val dist = kotlin.math.abs(ocx - vcx) + kotlin.math.abs(ocy - vcy)
                                if (dist < minDist) {
                                    minDist = dist
                                    targetKey = otherKey
                                }
                            }
                            val swapped = targetKey != null && minDist < view.width * 2.5f
                            if (swapped) {
                                val i = iconViews.indexOfFirst { it.second == key }
                                val j = iconViews.indexOfFirst { it.second == targetKey!! }
                                val tmp = iconViews[i]
                                iconViews[i] = iconViews[j]
                                iconViews[j] = tmp
                                val newOrder = iconViews.map { it.second }.joinToString(",")
                                prefs.edit().putString("icon_order", newOrder).apply()
                            }
                            // 回弹动画
                            v.elevation = 0f
                            v.animate().translationX(0f).translationY(0f)
                                .scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(200)
                                .withEndAction {
                                    if (swapped) {
                                        rebuildGrid(rowContainer, iconViews, keyToItem, prefs, initialKeys)
                                    }
                                }
                                .start()
                            v.isLongClickable = true
                            v.setOnTouchListener(null)
                            true
                        }
                        else -> false
                    }
                }
                true
            }
        }
    }

    /** 根据 iconViews 当前顺序重建网格布局 */
    private fun rebuildGrid(
        rowContainer: LinearLayout,
        iconViews: List<Pair<View, String>>,
        keyToItem: Map<String, GridItem>,
        prefs: android.content.SharedPreferences,
        initialKeys: List<String>,
    ) {
        val d = activity.resources.displayMetrics.density
        rowContainer.removeAllViews()

        // 按 iconViews 顺序重建行（每行 2 张卡片）
        val keys = iconViews.map { it.second }
        for ((rowIndex, rowItems) in keys.chunked(2).withIndex()) {
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
                val item = keyToItem[key] ?: return@forEachIndexed
                val cardView = buildGridCard(item, d, false)
                row.addView(
                    cardView,
                    LinearLayout.LayoutParams(0, Ui.dp(84, d), 1f).apply {
                        if (colIndex > 0) leftMargin = Ui.dp(12, d)
                    },
                )
            }
            rowContainer.addView(row)
        }
    }

    private data class GridItem(
        val title: String,
        val iconRes: Int,
        val tintColor: String,
        val onClick: (View) -> Unit,
    )

    /**
     * 功能卡（卡片式入口）：左侧品牌色圆角块 + 彩色图标，右侧功能标题。
     * 宽度为行内等分（每行 2 张，约等于原先两个图标格合并的宽度），高度由行参数固定。
     */
    private fun buildGridCard(item: GridItem, d: Float, wallpaperSync: Boolean = false): View {
        val tint = android.graphics.Color.parseColor(item.tintColor)
        val tr = android.graphics.Color.red(tint)
        val tg = android.graphics.Color.green(tint)
        val tb = android.graphics.Color.blue(tint)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(Ui.dp(12, d), Ui.dp(10, d), Ui.dp(12, d), Ui.dp(10, d))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(60, tr, tg, tb)),
                Ui.glassSurface(activity, 18f),
                null,
            )
            setOnClickListener { item.onClick(this) }
        }
        Ui.pressAnimation(card)

        // 左侧：品牌色圆角块 + 图标
        val iconBox = android.widget.FrameLayout(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(34, tr, tg, tb))
                cornerRadius = Ui.dp(14, d).toFloat()
                setStroke(maxOf(1, Ui.dp(1, d)), android.graphics.Color.argb(110, tr, tg, tb))
            }
            layoutParams = LinearLayout.LayoutParams(Ui.dp(48, d), Ui.dp(48, d))
        }
        iconBox.addView(ImageView(activity).apply {
            setImageResource(item.iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(Ui.dp(28, d), Ui.dp(28, d)).apply {
                gravity = Gravity.CENTER
            }
        })
        card.addView(iconBox)

        // 右侧：功能标题
        card.addView(TextView(activity).apply {
            text = item.title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (wallpaperSync) android.graphics.Color.WHITE else Ui.primaryText(activity))
            setPadding(Ui.dp(12, d), 0, 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 壁纸模式：无偏移强阴影，形成均匀黑色描边，白壁纸也清晰可见
            if (wallpaperSync) {
                setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return card
    }

    /** 打开全屏设置页（个性主题 + 关于信息） */
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
