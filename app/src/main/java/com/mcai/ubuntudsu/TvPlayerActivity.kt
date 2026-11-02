package com.mcai.ubuntudsu

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mcai.ubuntudsu.ui.Ui

/**
 * 手机电视播放器：ExoPlayer (media3) 全屏横屏播放直播源，自建极简控制层。
 *
 * 设计取舍：
 * 1. 关掉 media3 自带控制条（又厚又杂），改用「顶部频道信息 + 底部三键」轻量浮层，
 *    点画面任意处显隐浮层，3.5 秒无操作自动隐藏，整体更清爽；
 * 2. 直播源多为 3~5 个分片的滚动窗口（整份清单只有十几秒），播放器若按固定时长播完就会停——
 *    这里统一按直播处理，窗口播完自动重新拉清单续到最新；同一频道常有多条线路，断了自动换线；
 * 3. 新增「画面」键循环切换 原始比例 / 拉伸全屏 / 裁剪填充，解决「左右黑边去不掉」的问题。
 */
class TvPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"
        const val EXTRA_ALTS = "alts"

        /** 播放器 UA：部分 CDN 对默认播放器 UA 有额外限制，用通用 UA 更稳 */
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36"

        private const val MAX_RETRY = 3
        private const val MAX_SHORT_END = 2
        private val RESIZE_LABELS = arrayOf("原始比例", "拉伸全屏", "裁剪填充")
        private const val RESIZE_FIT = 0
        private const val RESIZE_FILL = 1
        private const val RESIZE_ZOOM = 2
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var liveBadge: TextView
    private lateinit var lineText: TextView
    private lateinit var resizeBtn: TextView

    private var lines: List<String> = emptyList()
    private var lineIndex = 0
    private val currentUrl: String get() = lines.getOrElse(lineIndex) { "" }

    private var channelName = ""
    private var resizeModeIndex = RESIZE_FIT

    private val handler = Handler(Looper.getMainLooper())
    private var overlayHandler = Handler(Looper.getMainLooper())
    private var retryTimes = 0
    private var shortEndTimes = 0
    private var preparedAt = 0L
    private var pendingReconnect = false
    private var overlayVisible = true
    private var persistentStatus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.setBackgroundDrawableResource(android.R.color.black)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        channelName = intent.getStringExtra(EXTRA_NAME) ?: "频道"
        val primary = intent.getStringExtra(EXTRA_URL) ?: ""
        val alts = intent.getStringArrayListExtra(EXTRA_ALTS) ?: arrayListOf()
        lines = (listOf(primary) + alts).filter { it.isNotBlank() }.distinct()

        val root = FrameLayout(this)
        setContentView(root)
        Ui.enableEdgeToEdge(this, root)

        // 真正沉浸式：让画面铺满到挖孔/灵动岛区域，并隐藏状态栏与导航栏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // SHORT_EDGES：允许内容延伸到屏幕短边（横屏即左右上下的挖孔/刘海区），
            // 解决「拉伸后摄像头区域仍是黑边」的问题
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemUI()

        if (lines.isEmpty()) {
            showFatal("无效的播放地址")
            return
        }

        buildPlayerView(root)
        buildOverlay(root)
        applyResizeMode()
        showOverlay()
        startPlayback()
    }

    // ---------------------------------------------------------------- 播放器

    private fun buildPlayerView(root: FrameLayout) {
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            useController = false
            setKeepContentOnPlayerReset(true)
        }
        // 点画面任意处显隐控制浮层
        playerView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) toggleOverlay()
            false
        }
        root.addView(playerView)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory()),
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3_000, 20_000, 1_000, 2_000)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        playerView.player = player
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> showStatus("连接中…")
                    Player.STATE_READY -> {
                        if (!persistentStatus) hideStatus()
                        retryTimes = 0
                        refreshBadges()
                    }
                    Player.STATE_ENDED -> onWindowEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    retryTimes = 0
                    if (!persistentStatus) hideStatus()
                }
                refreshBadges()
            }

            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError(error)
            }
        })
    }

    private fun httpFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            // 关键：m3u8 清单必须每次回源，不能被中间层缓存住（否则清单永远停在旧窗口）
            .setDefaultRequestProperties(
                mapOf(
                    "Cache-Control" to "no-cache, no-store",
                    "Pragma" to "no-cache",
                ),
            )

    private fun mediaItem(): MediaItem = MediaItem.Builder()
        .setUri(currentUrl)
        .setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(6_000)
                .setMinPlaybackSpeed(0.97f)
                .setMaxPlaybackSpeed(1.03f)
                .build(),
        )
        .build()

    private fun startPlayback() {
        val p = player ?: return
        preparedAt = SystemClock.elapsedRealtime()
        p.setMediaItem(mediaItem())
        p.prepare()
        p.play()
        showStatus("连接中…")
        refreshBadges()
    }

    /** 窗口播完 → 重新拉清单续到最新（直播不具备「播完」概念） */
    private fun onWindowEnded() {
        if (!looksLikeLive()) {
            showStatus("播放结束")
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - preparedAt
        if (elapsed < 3_000) {
            shortEndTimes++
            if (shortEndTimes >= MAX_SHORT_END) {
                tryNextLine("该线路画面中断，换线路…") {
                    showFatal("所有线路都无法播放，可稍后再试")
                }
                return
            }
        } else {
            shortEndTimes = 0
        }
        reconnectSoon("画面已滚动，正在续播…")
    }

    private fun onPlaybackError(error: PlaybackException) {
        val code = error.errorCodeName
        if (retryTimes < MAX_RETRY) {
            retryTimes++
            reconnectSoon("连接中断（$code），第 $retryTimes 次重试…")
            return
        }
        tryNextLine("线路异常（$code），换线路…") {
            showFatal("播放失败：$code\n所有线路都不通，可稍后再试")
        }
    }

    private fun reconnectSoon(reason: String) {
        if (pendingReconnect) return
        pendingReconnect = true
        showStatus(reason)
        handler.postDelayed({
            pendingReconnect = false
            if (isFinishing || isDestroyed) return@postDelayed
            startPlayback()
        }, 400)
    }

    /** 切到下一条线路；若已无可用线路则执行 [onExhausted] */
    private fun tryNextLine(reason: String, onExhausted: () -> Unit) {
        if (lines.size <= 1) {
            onExhausted()
            return
        }
        val next = (lineIndex + 1) % lines.size
        if (next == 0) {
            onExhausted()
            return
        }
        lineIndex = next
        retryTimes = 0
        shortEndTimes = 0
        showStatus("$reason（线路 ${lineIndex + 1}/${lines.size}）")
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            startPlayback()
        }, 400)
    }

    private fun switchLineManually() {
        if (lines.size <= 1) {
            retryTimes = 0
            shortEndTimes = 0
            startPlayback()
            flashStatus("正在重连…")
            return
        }
        lineIndex = (lineIndex + 1) % lines.size
        retryTimes = 0
        shortEndTimes = 0
        startPlayback()
        flashStatus("切换线路 ${lineIndex + 1}/${lines.size}")
    }

    /** m3u8 / TS 组播代理一律视为直播；其余格式看播放器判定 */
    private fun looksLikeLive(): Boolean {
        val u = currentUrl.lowercase()
        if (u.contains(".m3u8") || u.contains("/udp/") || u.endsWith(".ts")) return true
        val p = player ?: return false
        return runCatching { p.isCurrentMediaItemLive || p.duration == C.TIME_UNSET }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 控制浮层

    private fun buildOverlay(root: FrameLayout) {
        val d = resources.displayMetrics.density

        // 顶部：频道名 + 直播徽章 + 线路指示
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(0x99000000.toInt(), 18)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { setMargins(dp(14), dp(14), dp(14), 0) }
        }
        titleText = TextView(this).apply {
            text = channelName
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        liveBadge = TextView(this).apply {
            text = "● 直播"
            setTextColor(0xFF4CD964.toInt())
            textSize = 13f
            setPadding(dp(8), 0, dp(6), 0)
        }
        lineText = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
            setPadding(dp(4), 0, 0, 0)
        }
        topBar.addView(titleText)
        topBar.addView(liveBadge)
        topBar.addView(lineText)

        // 底部：换线 / 画面 / 关闭
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { setMargins(dp(14), 0, dp(14), dp(18)) }
        }
        val switchBtn = barButton("⇄ 换线") { switchLineManually() }
        resizeBtn = barButton("▣ 画面") { cycleResizeMode() }
        val closeBtn = barButton("✕ 关闭") { finish() }
        bottomBar.addView(switchBtn)
        bottomBar.addView(resizeBtn)
        bottomBar.addView(closeBtn)

        // 居中状态（缓冲 / 错误 / 提示），默认隐藏
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = pill(0xAA000000.toInt(), 14)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }

        root.addView(topBar)
        root.addView(bottomBar)
        root.addView(statusText)
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = pill(0xCC000000.toInt(), 22)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(10) }
            layoutParams = lp
            Ui.pressAnimation(this)
            setOnClickListener { onClick() }
        }

    private fun pill(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun cycleResizeMode() {
        resizeModeIndex = (resizeModeIndex + 1) % RESIZE_LABELS.size
        applyResizeMode()
        flashStatus("画面：${RESIZE_LABELS[resizeModeIndex]}")
    }

    private fun applyResizeMode() {
        playerView.resizeMode = when (resizeModeIndex) {
            RESIZE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            RESIZE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        resizeBtn.text = "▣ ${RESIZE_LABELS[resizeModeIndex]}"
    }

    // ---------------------------------------------------------------- 浮层显隐

    private fun toggleOverlay() {
        if (overlayVisible) hideOverlay() else showOverlay()
    }

    private fun showOverlay() {
        overlayVisible = true
        persistentStatus = false
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        topBar.alpha = 0f; bottomBar.alpha = 0f
        topBar.animate().alpha(1f).setDuration(180).start()
        bottomBar.animate().alpha(1f).setDuration(180).start()
        scheduleAutoHide()
    }

    private fun hideOverlay() {
        if (persistentStatus) return // 致命错误时浮层常驻
        overlayVisible = false
        topBar.animate().alpha(0f).setDuration(180).withEndAction { topBar.visibility = View.GONE }.start()
        bottomBar.animate().alpha(0f).setDuration(180).withEndAction { bottomBar.visibility = View.GONE }.start()
        if (statusText.visibility == View.VISIBLE && !persistentStatus) {
            statusText.animate().alpha(0f).setDuration(180).withEndAction { statusText.visibility = View.GONE }.start()
        }
    }

    private fun scheduleAutoHide() {
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({ if (!persistentStatus) hideOverlay() }, 3500)
    }

    private fun refreshBadges() {
        liveBadge.visibility = if (looksLikeLive()) View.VISIBLE else View.GONE
        lineText.text = "线路 ${lineIndex + 1}/${lines.size}"
        lineText.visibility = if (lines.size > 1) View.VISIBLE else View.GONE
    }

    private fun showStatus(msg: String) {
        persistentStatus = false
        statusText.text = msg
        statusText.alpha = 1f
        statusText.visibility = View.VISIBLE
        showOverlaySoft()
    }

    private fun flashStatus(msg: String) {
        statusText.text = msg
        statusText.alpha = 1f
        statusText.visibility = View.VISIBLE
        showOverlay()
    }

    /** 浮层可见但仅点亮状态条（不重置自动隐藏逻辑冲突时复用） */
    private fun showOverlaySoft() {
        if (!overlayVisible) {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            topBar.alpha = 1f; bottomBar.alpha = 1f
            overlayVisible = true
        }
        scheduleAutoHide()
    }

    private fun hideStatus() {
        if (statusText.visibility == View.VISIBLE && !persistentStatus) {
            statusText.visibility = View.GONE
        }
    }

    private fun showFatal(msg: String) {
        persistentStatus = true
        overlayVisible = true
        topBar.visibility = View.VISIBLE; topBar.alpha = 1f
        bottomBar.visibility = View.VISIBLE; bottomBar.alpha = 1f
        liveBadge.visibility = View.GONE
        statusText.text = msg
        statusText.alpha = 1f
        statusText.visibility = View.VISIBLE
        overlayHandler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
        hideSystemUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    /** 隐藏状态栏与导航栏（沉浸式 sticky），视频真正全屏铺满 */
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlayHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
