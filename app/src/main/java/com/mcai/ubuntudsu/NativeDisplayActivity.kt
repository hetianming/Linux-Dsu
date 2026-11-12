package com.mcai.ubuntudsu

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.crosvm.ICrosvmAndroidDisplayService
import com.mcai.ubuntudsu.core.EvdevEncoder
import com.mcai.ubuntudsu.core.GunyahManager
import com.mcai.ubuntudsu.core.INativeDisplayService
import com.mcai.ubuntudsu.core.InputServiceClient
import com.mcai.ubuntudsu.core.KeyCodeMapper
import com.mcai.ubuntudsu.core.NativeDisplayRootService
import com.mcai.ubuntudsu.core.NativeInputBridge
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * v2.0 内置原生显示器（替代独立 display_app）。
 *
 * 一条链路全部在主程序里（v2.0.10 架构）：
 *   自有 root 服务(InputSocketHost) 在 VM 启动前 bind+listen 4 路 unix socket
 *     → crosvm 启动时 connect（crosvm 是 connect 方）
 *   本页 SurfaceView → 自有 root 服务拿到 crosvm_display binder → setSurface
 *   本页触摸/按键 → NativeInputBridge → AIDL writeInput → root 服务直写 socket → crosvm
 *
 * 输入不再经过 native touchd / APP 直连本地 TCP（那层在真机被 SELinux 拦，
 * 是「能显示不能操作」的根因）。socket 由 root 服务进程常驻持有，与 APP 页面
 * 生命周期解耦；任何一步失败自动落回内置 VNC，不会出现「有画面没输入」的死局。
 */
class NativeDisplayActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "NativeDisplay"
        const val SERVICE_NAME = "crosvm_display"
        /**
         * v2.0.24 输入模式（0/1 与旧版兼容）：
         *  0 = 触摸板：整屏当笔记本触摸板，相对位移移动光标（默认，替代旧的「鼠标」档）
         *  1 = 多点触控：绝对触点，留给 iOS/触控系统的手势
         *  2 = 直接点按：旧的「按哪到哪」绝对指针，保留为可随时切回的一档
         * 老用户存的 0 会自然变成触摸板，1 仍是多点触控，无需迁移配置。
         */
        const val MODE_TOUCHPAD = 0
        const val MODE_MULTITOUCH = 1
        const val MODE_ABSOLUTE = 2
        private const val SP_NAME = "native_disp"
        private const val SP_MODE = "input_mode"
        private const val SP_TP_GAIN = "tp_gain"
        // v2.0.27：软件箭头默认不画（桌面系统自带指针），开关按「当前启动盘组合」分别记忆。
        // 旧版全局键 tp_cursor 不再读取：升级后一律回到默认关，立刻消除双箭头。
        private const val SP_TP_CURSOR_PROFILE = "tp_cursor_"
        private const val SP_TP_CURSOR_SIZE = "tp_cursor_size"  // v2.0.26 箭头大小档位
        // v2.0.26：v2.0.25 的箭头约 26dp 高 + 2.5dp 描边，实测偏大、盖在浅色桌面上不自然。
        // 以尖端 (0,0) 为锚整体缩放：小 0.58 / 中 0.75 / 大 0.92（约 15 / 19 / 24 dp 高）。
        // 长按「指针」循环，持久化；默认「小」。
        private val CURSOR_SCALES = floatArrayOf(0.58f, 0.75f, 0.92f)
        private val CURSOR_SIZE_NAMES = arrayOf(Lang.t("小"), Lang.t("中"), Lang.t("大"))
        private const val CURSOR_SIZE_DEFAULT = 0
        // v2.0.24 触摸板手势参数
        private const val TP_TAP_SLOP_DP = 10f       // 位移小于它判「轻点」
        private const val TP_TAP_TIMEOUT_MS = 240L   // 轻点最长按下时长
        private const val TP_LONG_PRESS_MS = 260L    // 按住不动超过它 → 左键按住拖动
        private const val TP_WHEEL_STEP_DP = 20f     // 双指每滑动这么多 dp 发一格滚轮
        private val TP_GAINS = floatArrayOf(0.7f, 1.0f, 1.6f)  // 长按「模式」循环：慢/中/快
        // v2.0.18：键盘入口贴边收纳。0=浮球，1/2/3/4=贴左/右/上/下边缘的透明细杠条。
        private const val SP_DOCK_EDGE = "dock_edge"
        private const val SP_DOCK_POS = "dock_pos"
        private const val SP_BALL_X = "ball_x"
        private const val SP_BALL_Y = "ball_y"
        private const val SP_DOCK_TIP = "dock_tip"
        private const val EDGE_SNAP_DP = 46f    // 浮球中心离边小于它 → 吸附收纳
        private const val UNSNAP_DP = 42f       // 细条向屏内拖超过它 → 还原浮球
        private const val DOCK_THICK_DP = 13f   // 细条厚度（不超过原浮球 44dp 的 1/3）
        private const val DOCK_LONG_DP = 78f    // 细条长度
        // v2.0.20：键盘窗口自由拖动位置（按屏幕宽高比例记忆）+ 显示器防误退锁
        private const val SP_KB_OFF_X = "kb_off_x"
        private const val SP_KB_OFF_Y = "kb_off_y"
        // v2.0.22：纯软件防误退锁（不再用系统屏幕固定，见 1308 起注释）。
        // 沿用 display_lock2：用户上次手动点过 🔓 解锁仍被记住；没点过的默认上锁。
        private const val SP_DISPLAY_LOCK = "display_lock2"
        private const val DRAG_SLOP_DP = 12f    // 超过它才判定为「拖动工具条」，轻点仍是按钮
        // v2.0.16：按键反馈配色（图2 效果）
        private val KB_BG = 0xFF28323C.toInt()
        private val KB_BG_DOWN = 0xFF7C8794.toInt()
        private val PREVIEW_BG = 0xFF8A8F98.toInt()
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var panel: LinearLayout
    private lateinit var ball: TextView
    // v2.0.18：键盘入口形态。dockEdge=0 是 44dp 浮球；1/2/3/4 是贴左/右/上/下的
    // 半透明模糊细杠条。同一个 View 切形态，避免两个入口并存互相抢显隐。
    // v2.0.40：默认贴右边收纳（2），不再默认浮球；点细条出键盘，向屏内拖还原浮球。
    private var dockEdge = 2
    private var dockPos = 0.45f      // 细条中心在所贴边上的比例
    private var ballRx = 0.93f       // 浮球中心相对屏幕宽/高的比例（还原位置）
    private var ballRy = 0.44f
    private var entryReady = false   // 首次布局完成前不贴边，交给 root.post 的 applyEntryShape
    // 拖动中禁止布局监听重定位，否则每次改 margin 都会被拉回记忆位置（拖不动）。
    @Volatile private var entryDragging = false
    private lateinit var rootFrame: FrameLayout
    // v2.0.17：键盘 4 向旋转只转这一层（键盘 + 浮出气泡一起转）；
    // 画面 SurfaceView 与 ⌨ 浮球在 rootFrame 里，方向保持不变。
    private lateinit var kbHost: FrameLayout
    // v2.0.15：全键盘视图引用，统一调 padding/字号，不重建面板。
    private val keyViews = ArrayList<TextView>()
    private var numpadView: LinearLayout? = null
    private var numBtn: TextView? = null
    private var rotateBtn: TextView? = null
    // v2.0.20：🔒 防误退锁按钮与其状态
    private var lockBtn: TextView? = null
    private var displayLocked = true   // v2.0.22：默认上锁（纯软件锁，不占屏幕底部）
    private var lockToast: Toast? = null
    // v2.0.20：键盘窗口位置（相对屏幕宽/高的比例），拖动工具条改变，跨启动记忆
    private var kbOffX = 0f
    private var kbOffY = 0f
    // v2.0.16：按键按下时浮出的放大字符预览（图2），以及按下态。
    private var keyPreview: TextView? = null
    private var kbRotation = 0   // 0 / 90 / 180 / 270，默认图1 布局（0°）
    // v2.0.17：⌫ 长按连续删除
    private var repeatActive = false
    private var repeatRunnable: Runnable? = null
    private var repeatStart: Runnable? = null

    private var displayService: ICrosvmAndroidDisplayService? = null
    private var rootService: INativeDisplayService? = null
    private var surface: Surface? = null
    private var bound = false
    private var fellBack = false
    private var surfaceSet = false
    // 出过一次画面就永久取消自动兜底：切后台会 surfaceDestroyed 把 surfaceSet 清零，
    // 但那不是失败。旧版据此误判并写回配置，导致「第二次再也进不去原生显示」。
    private var everSurfaceSet = false
    // v2.0.4：crosvm 的 setSurface 每个 surface 生命周期只接受一次。
    // surfaceSent 是硬闸门；needsSend 由 surfaceCreated 置位；hasSavedFrame
    // 用于 surface 重建后立刻恢复最后一帧（静态桌面不会重新绘制）。
    private var surfaceSent = false
    private var needsSend = false
    private var hasSavedFrame = false
    private var guestW = 0
    private var guestH = 0
    // v2.0.14：输入设备的坐标基准，来自 native.res（脚本写入的 宽 高 = 720x1584），与
    // --input 的 width/height 严格一致。绝不能被 displayConfig 覆盖：crosvm 的 DisplayConfig
    // 报的是 1280x800，而设备声明的 ABS 范围是 720x1584，用错会把触点 clamp 到边角。
    private var inputW = 0
    private var inputH = 0
    // v2.0.14：true=7790 是 absolute-mouse(发 EV_ABS)；false=旧工具链回退的相对 mouse。
    // 由脚本探测 crosvm 能力后写 work/input.abs；读不到默认 true（新版工具链）。
    @Volatile private var absPointer = true
    private val ui = Handler(Looper.getMainLooper())
    private var alive = true
    // v2.0.7 双输入模式 → v2.0.24 三档：默认触摸板（笔记本手感）；多点触控留给
    // iOS/触控系统；直接点按保留旧的绝对指针。持久化，下次进原生显示沿用上次选择。
    @Volatile     private var inputMode = MODE_TOUCHPAD
    private var modeToggle: TextView? = null
    // v2.0.24 触摸板状态机：只在 UI 线程读写（setOnTouchListener 回调），无需加锁。
    private var tpVX = 0f; private var tpVY = 0f      // 虚拟光标（guest ABS 坐标，浮点累积）
    private var tpInit = false                        // 是否已与 guest 光标对齐
    private var tpLastX = 0f; private var tpLastY = 0f
    private var tpDownX = 0f; private var tpDownY = 0f
    private var tpDownTime = 0L
    private var tpMoved = false
    private var tpDragging = false
    private var tpLongPress: Runnable? = null
    private var tpTwoFinger = false
    private var tpTwoCX = 0f; private var tpTwoCY = 0f
    private var tpTwoMoved = false
    private var tpTwoDownTime = 0L
    private var tpTwoTapDone = false
    private var tpWheelAccX = 0f; private var tpWheelAccY = 0f
    private var tpGainIdx = 1                        // 默认中速 ×1.0
    // v2.0.25 软件光标：guest 不画指针的系统（固件/GRUB/部分 Linux）靠它知道光标在哪。
    // 只在触摸板模式显示 —— 只有这个模式 APP 才持有光标位置。默认开，工具条「指针」可关。
    private var cursorView: CursorOverlayView? = null
    private var cursorBtn: TextView? = null
    // v2.0.27：默认不画。guest 自带指针时 APP 那个箭头是多余且不同步的第二个箭头。
    private var cursorVisible = false
    private var cursorKey = SP_TP_CURSOR_PROFILE + "default"   // 当前启动盘组合对应的记忆键
    private var cursorSizeIdx = CURSOR_SIZE_DEFAULT   // v2.0.26：默认「小」
    // v2.0.3：把链路每一步的结果直接显示在状态栏，不用 logcat 也能一次定位
    @Volatile private var diag = Lang.t("初始化…")

    private val rootConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootService = INativeDisplayService.Stub.asInterface(service)
            diag = Lang.t("root✓ 服务…")
            Log.i(TAG, "root service connected; diag=${runCatching { rootService?.diagnostics(SERVICE_NAME) }.getOrNull()}")
            Thread({ attachDisplay() }, "native-attach").apply { isDaemon = true }.start()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullScreen()
        readGuestResolution()
        loadInputMode()
        loadEntry()
        loadKbOffsetAndLock()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // v2.0.19：键盘层会整体旋转，内容可能越过自身 layout 框；关掉裁剪，
            // 否则旋转到 90/270° 时贴左/贴右的键盘会被父容器裁掉一部分。
            clipChildren = false
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame = root

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
            // v2.0.3 黑屏根因：guest 帧缓冲已折算成 720×1584，SurfaceView 却仍是
            // 全屏 1440×3168。crosvm 的 android_display 后端要求 Surface 尺寸与
            // 帧缓冲一致，对不上就 setSurface 成功也不出画面。
            // v2.0.16：竖屏(720x1584 与手机屏 1440x3168 比例天然一致)直接铺满，零黑边；
            // 只有横屏才等比居中，避免把竖屏 buffer 拉成宽屏。见 updateSurfaceLayout()。
            // setFixedSize 让 Surface = 帧缓冲尺寸；触摸 e.x/e.y 是 View 局部坐标，
            // scales() 仍按 View 宽高换算，所以不需要额外黑边偏移，输入基准 inputW/H 不动。
            if (guestW > 0 && guestH > 0) holder.setFixedSize(guestW, guestH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            }
            holder.addCallback(this@NativeDisplayActivity)
            // 关键：只有一个 SurfaceView，不再叠第二层 cursor surface 吞触摸
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
        attachTouch(surfaceView)
        root.addView(surfaceView)

        // v2.0.25 软件光标：普通 View 叠加，**不是第二个 SurfaceView**。历史教训见
        // 249 行注释：早期叠过 cursor surface，结果把触摸吞了才删掉。普通 View 默认
        // 不 clickable、onTouchEvent 返回 false，触摸会自然穿透到下层 SurfaceView；
        // 加在 surfaceView 之后、kbHost 之前，键盘弹出时光标被键盘盖住，不挡按键。
        cursorView = CursorOverlayView(this).apply {
            val d = resources.displayMetrics.density
            val size = (28 * d).roundToInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            isClickable = false
            isFocusable = false
        }
        root.addView(cursorView)

        // v2.0.15：顶部检测状态卡片已删除（用户要求）。同时停掉每秒轮询
        // startStatusLoop()，省电、省 binder。诊断改为「长按 ⌨ 浮球 / 面板 ⓘ」临时查看，
        // 平时画面零遮挡。

        // v2.0.17：键盘放进独立的全屏容器。旋转只作用于 kbHost，画面与浮球不动。
        // kbHost 不 clickable，触摸落在键盘外时自然穿透到下层 SurfaceView。
        kbHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }
        panel = buildPanel()
        panel.visibility = View.GONE
        kbHost.addView(panel)
        applyKbDensity()
        root.addView(kbHost)

        ball = buildBall()
        root.addView(ball)

        setContentView(root)
        // v2.0.18：布局完成后再按实测宽高应用入口形态（收纳条要贴准边缘）。
        root.post {
            updateSurfaceLayout()
            applyEntryShape()
            applyKbRotation()          // 内含 applyKbOffset()：恢复上次拖到的位置
            applyLockState()           // 内含手势排除区：锁定时仅屏蔽左右边缘返回手势（不占底部）
        }
        // 状态栏隐藏、旋转、键盘展开等导致窗口实测尺寸变化后自动重算，
        // 不再依赖一次性 post（这也是旧版四角露黑边的原因之一）。
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            updateSurfaceLayout()
            // 拖动中每一帧都会触发布局回调；此时必须跳过，否则球会被拉回记忆位置。
            if (!entryDragging) positionEntry()
            // v2.0.19：窗口宽高真的变了才重算旋转 pivot —— 首次布局完成时补一次，
            // 兜住 root.post 早于实测宽高、pivot 仍是默认中心的情况。
            if (r - l != or_ - ol || b - t != ob - ot) {
                applyKbRotation()
                // v2.0.20：手势排除区按实测宽高算，横竖屏/系统栏变化后要重设。
                if (displayLocked) applyLockExclusion()
            }
        }

        // v2.0.10：输入 socket 由 root 服务进程托管。VM 启动时已先 ensureListeningAndWait，
        // 这里再兜底一次（幂等）：覆盖「直接打开显示器」「root 服务被回收后重建」等情况。
        InputServiceClient.ensureListening(this)
        NativeInputBridge.start()
        bindRoot()
        // v2.0.15：状态卡片与每秒轮询均已移除。
        ui.postDelayed({
            if (!isFinishing && !isDestroyed) {
                // v2.0.39：启动引导提示卡贴顶部居中（带顶部留白，避开顶部工具条），
                // 不再压在屏幕底部与「默认键盘布局」重叠。
                Toast.makeText(this, Lang.t("触摸板模式（像笔记本）：单指滑动移光标 / 轻点单击 / 长按拖动 / 双指滚动 / 双指轻点右键。工具条「模式」点按切 触摸板→直接点按→多点触控，长按调光标速度；「指针」默认关（用系统自带指针，不再出现两个箭头），没有指针的固件/GRUB 界面点按打开即可、且按当前启动盘记住，长按切箭头大小 小/中/大。默认已上锁 🔒（软件锁）：拦返回键与左右侧滑返回防误退；屏幕底部不占用，Windows 任务栏 / Linux 开始菜单可直接点。点 🔓 解锁后返回键恢复。点 ⌨ 出全键盘，按住工具条可拖动；「旋转」切 0/90/180/270°；长按 ⌨ 看诊断"), Toast.LENGTH_LONG).apply {
                    setGravity(
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                        0,
                        (resources.displayMetrics.density * 56f).toInt()
                    )
                }.show()
            }
        }, 1200)
    }

    // ---------------- 全屏：铺满、含摄像头挖孔区、零遮挡 ----------------
    private fun fullScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 原生显示器里不能让系统软键盘弹出来：它会挡住画面、拦截按键，
        // 而且手机没有实体键盘时，软键盘只发「文字」、不发扬端键事件，
        // GRUB/Fedora 安装程序根本收不到方向键。直接禁止 IME 弹出。
        runCatching {
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // 真机崩溃修复：onCreate 阶段 DecorView 还没安装，此时读 window.insetsController
        // 会走到 PhoneWindow.getInsetsController() 里的 mDecor.getWindowInsetsController()，
        // mDecor 为 null -> NullPointerException。所有依赖 DecorView 的沉浸式设置
        // 一律 post 到 setContentView / attach 之后执行。
        window.decorView.post { runCatching { applyImmersive() } }
    }

    /** 必须在 DecorView 已安装后调用；onWindowFocusChanged 会再补一次，双保险。 */
    private fun applyImmersive() {
        if (isFinishing || isDestroyed) return
        val decor = window.decorView ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { window.setDecorFitsSystemWindows(false) }
            // 用 View 的 controller：未 attached 时返回 null（不会像 Window 那样 NPE），
            // 此时退回传统 flags，保证任何 ROM 都能铺满全屏、无遮挡。
            val ctrl = decor.windowInsetsController
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                applyLegacyFullscreen(decor)
            }
        } else {
            applyLegacyFullscreen(decor)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyFullscreen(decor: View) {
        decor.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    /** 脚本写入 work/native.res = "宽 高"；读不到就按 1:1（autoRes 本来就跟随宿主）。 */
    private fun readGuestResolution() {
        runCatching {
            val f = File(File(GunyahManager.privateDir(this), "work"), "native.res")
            val parts = f.readText().trim().split(Regex("\\s+"))
            guestW = parts.getOrNull(0)?.toIntOrNull() ?: 0
            guestH = parts.getOrNull(1)?.toIntOrNull() ?: 0
            // 输入基准独立保存；attachDisplay() 里的 displayConfig 只许改画面尺寸 guestW/H，
            // 不许动 inputW/H，否则触摸坐标会按 1280x800 发到 720x1584 的设备上被 clamp。
            inputW = guestW
            inputH = guestH
            // 脚本探测结果：新版工具链写 1(absolute-mouse)，旧版写 0(相对 mouse)。
            absPointer = runCatching {
                File(File(GunyahManager.privateDir(this), "work"), "input.abs")
                    .readText().trim() != "0"
            }.getOrDefault(true)
        }
        Log.i(TAG, "guest resolution = ${guestW}x${guestH}; input basis = ${inputW}x${inputH}; absPointer=$absPointer")
    }

    /**
     * v2.0.16：修掉「四角都有黑边、不全屏」。
     *
     * 根因：v2.0.15 用 resources.displayMetrics 算等比尺寸。沉浸式全屏窗口实测是
     * 1440x3168，displayMetrics 却返回去掉系统栏的 1440x3024；min() 缩放后得到
     * 1375x3024 居中，于是上下左右全部露黑（用户看到的四角黑边）。
     *
     * 做法：一律用 rootFrame 的实测宽高；竖屏帧缓冲 720x1584 与手机屏 1440x3168
     * 比例天然一致，直接 MATCH_PARENT 铺满 —— 零黑边、零变形。横屏比例差太大，
     * 仍等比居中（保留 v2.0.15 的横屏不变形修复）。v2.0.17 按用户要求移除「铺满」档位。
     *
     * 触摸不用改：OnTouchListener 绑在 SurfaceView 上，e.x/e.y 是 View 局部坐标，
     * scales() 按 View 实测宽高换算到 inputW/H，输入基准与 --input ABS 范围一律不动。
     */
    private fun updateSurfaceLayout() {
        if (!::surfaceView.isInitialized || !::rootFrame.isInitialized) return
        val sw = rootFrame.width.takeIf { it > 0 }
            ?: rootFrame.measuredWidth.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val sh = rootFrame.height.takeIf { it > 0 }
            ?: rootFrame.measuredHeight.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val bw = if (guestW > 0) guestW else 720
        val bh = if (guestH > 0) guestH else 1584
        if (sw <= 0 || sh <= 0 || bw <= 0 || bh <= 0) return
        val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        val containerRatio = sw.toFloat() / sh
        val frameRatio = bw.toFloat() / bh
        val near = kotlin.math.abs(containerRatio - frameRatio) / frameRatio < 0.06f
        // 防御：displayConfig 有可能报 1280x800 之类的异形值并改写 guestW/H（见
        // attachDisplay）。竖屏手机 + 竖向帧缓冲一律铺满，不让黑边换个方式回来。
        val portraitFit = containerRatio < 1f && frameRatio < 1f
        if (near || portraitFit) {
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.CENTER
                surfaceView.layoutParams = lp
            }
            Log.i(TAG, "surface 铺满 ${sw}x${sh} (frame ${bw}x${bh}, near=$near portraitFit=$portraitFit)")
            return
        }
        val scale = minOf(sw.toFloat() / bw, sh.toFloat() / bh)
        val w = (bw * scale).roundToInt().coerceAtMost(sw)
        val h = (bh * scale).roundToInt().coerceAtMost(sh)
        if (lp.width == w && lp.height == h && lp.gravity == Gravity.CENTER) return
        lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
        Log.i(TAG, "surface 等比居中 ${w}x${h} in ${sw}x${sh} (frame ${bw}x${bh})")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 已声明 orientation，旋转不重建 Activity，在这里重算。
        updateSurfaceLayout()
        rootFrame.post { updateCursorOverlay() }  // v2.0.25：布局完成后重算箭头偏移
        if (!entryDragging) positionEntry()   // v2.0.18：旋转后按新宽高重新贴边
        applyKbDensity()
        applyKbRotation()                     // v2.0.19：宽高变了重算 4 向旋转的 pivot
        if (displayLocked) applyLockExclusion()   // v2.0.20：按新宽高重设手势排除区
        runCatching { applyImmersive() }
    }

    private fun bindRoot() {
        try {
            bound = true
            RootService.bind(Intent(this, NativeDisplayRootService::class.java), rootConn)
        } catch (t: Throwable) {
            Log.e(TAG, "bind root failed", t)
            scheduleFallback(Lang.t("root 服务绑定失败"))
        }
    }

    /** 后台等 crosvm 注册服务，拿到 binder 后 setSurface；失败给足时间再兜底。 */
    private fun attachDisplay() {
        val binder = runCatching { rootService?.waitForDisplayBinder(SERVICE_NAME) }.getOrNull()
        if (binder == null) {
            Log.w(TAG, "display binder not found")
            diag = Lang.t("root✓ 服务✗（无 crosvm_display）")
            ui.post { scheduleFallback(Lang.t("未找到 crosvm_display 服务")) }
            return
        }
        diag = Lang.t("root✓ 服务✓ 设置…")
        val svc = ICrosvmAndroidDisplayService.Stub.asInterface(binder)
        // v2.0.4 黑屏根因：旧版在这里轮询 40 次反复调 setSurface，surfaceCreated /
        // surfaceChanged 又各调一次。参考实现明确记录 crosvm 每个 surface 生命周期
        // 只接受一次 setSurface，且 IllegalArgumentException 是「已送达」的正常返回
        // （异常来自客户端 binder 回复路径）。旧版把 IAE 当失败继续重试，等于把
        // 刚配好的 display 拆掉 —— 注释原文 "the real cause of the black screen"。
        // 现在：绝不轮询重试，surfaceSent 保证只发一次。
        ui.post {
            displayService = svc
            runCatching {
                val dc = svc.displayConfig
                if (dc != null && dc.width > 0 && dc.height > 0 &&
                    (dc.width != guestW || dc.height != guestH)) {
                    Log.i(TAG, "displayConfig=${dc.width}x${dc.height} dpi=${dc.dpi}")
                    guestW = dc.width; guestH = dc.height
                    runCatching { surfaceView.holder.setFixedSize(dc.width, dc.height) }
                    // v2.0.15：帧缓冲变了就重算等比居中尺寸，横屏不变形。
                    updateSurfaceLayout()
                }
                diag = "root✓ 服务✓ ${guestW}x${guestH} 设置…"
            }
            // surface 已建好就现在发；还没建好则由 surfaceCreated / surfaceChanged 发。
            // 两条路径都受 surfaceSent 保护，只会真正调用一次 setSurface。
            if (!trySendSurface()) Log.i(TAG, "surface not ready yet, deferring to surfaceChanged")
        }
    }

    /**
     * 每个 surface 生命周期只调用一次 setSurface。
     * IllegalArgumentException 必须当作成功：crosvm 已收到并配置好 surface，
     * 异常来自客户端 transact/reply；此时若重试或隐藏 SurfaceView，会触发
     * removeSurface 把 display 拆掉，画面就永久黑了。
     */
    private fun trySendSurface(): Boolean {
        val svc = displayService ?: return false
        if (surfaceSent) return true
        val s = surface
        if (s == null || !s.isValid) return false
        return try {
            svc.setSurface(s, false)
            markSent(svc)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "setSurface IllegalArgumentException (known/benign) — treated as delivered", e)
            markSent(svc)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setSurface failed: ${t.javaClass.simpleName} ${t.message}")
            diag = "root✓ 服务✓ ${guestW}x${guestH} 设置✗ ${t.javaClass.simpleName}"
            false
        }
    }

    private fun markSent(svc: ICrosvmAndroidDisplayService) {
        surfaceSent = true
        needsSend = false
        surfaceSet = true
        everSurfaceSet = true
        diag = "root✓ 服务✓ ${guestW}x${guestH} 设置✓"
        Log.i(TAG, "setSurface delivered")
        // surface 重建后（切后台/全屏切换）立刻把上一帧画回去，
        // 静态桌面在 damage-driven compositor 下不会主动重绘，否则又是黑屏。
        if (hasSavedFrame) runCatching { svc.drawSavedFrameForSurface(false) }
        Log.i(TAG, "display connected (状态卡片已移除, 长按 ⌨ 看诊断)")
    }

    // ---------------- 兜底：任何失败都自动落回内置 VNC ----------------
    private fun scheduleFallback(reason: String) {
        if (fellBack || everSurfaceSet || isFinishing || isDestroyed) return
        // 给 25 秒：crosvm 启动慢时服务注册会晚，别一上来就跳走。
        // everSurfaceSet：已经出过画面就永不再自动兜底（切后台 / 返回 APP 不算失败）。
        // v2.0.3：若脚本能力探测已判定回退 VNC（写了 display.backend=vnc），
        // 说明根本不会有 crosvm_display 服务，别让用户干等 25 秒黑屏，1 秒后直接切。
        val backend = runCatching {
            File(File(GunyahManager.privateDir(this), "work"), "display.backend").readText().trim()
        }.getOrDefault("")
        val waitMs = if (backend == "vnc") 1200L else 25_000L
        ui.postDelayed({
            if (fellBack || everSurfaceSet || !alive || surfaceSet) return@postDelayed
            doFallback(reason)
        }, waitMs)
    }

    private fun doFallback(reason: String) {
        if (fellBack) return
        fellBack = true
        Log.w(TAG, "fallback to VNC: $reason")
        Toast.makeText(this, "本次切回内置 VNC（$reason）· 配置不变，下次仍进原生显示", Toast.LENGTH_LONG).show()
        // v2.0.2：绝不修改持久配置。旧版在这里把 displayBackend 写成 vnc，
        // 用户切一次后台或误触一次「切回VNC」就被永久锁死在 VNC —— 这正是
        // 「第一次能进，后面再也进不去原生显示器」的原因。回退只在本次会话生效。
        val cfg = runCatching { GunyahManager.loadConfig(this) }.getOrNull()
        val host = cfg?.vncHost?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
        val port = GunyahManager.actualVncPort(this) ?: cfg?.vncPort ?: 5905
        runCatching {
            startActivity(Intent(this, VncViewerActivity::class.java).apply {
                putExtra(VncViewerActivity.EXTRA_HOST, host)
                putExtra(VncViewerActivity.EXTRA_PORT, port)
                // 传空密码 = 明确告诉内置 VNC 页「无密码直连」，不要再弹密码框。
                // crosvm 的 --vnc-server 不带 password=，走 RFB None 认证。
                putExtra(VncViewerActivity.EXTRA_PASSWORD, "")
            })
        }
        finish()
    }

    // ---------------- Surface ----------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated")
        surface = holder.surface
        needsSend = true
        surfaceSent = false
        NativeInputBridge.start()
        // 此时 displayService 可能还没拿到（binder 晚于 surface），返回 false 即可；
        // attachDisplay 拿到后会在 ui.post 里再调一次 trySendSurface。
        trySendSurface()
    }

    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
        Log.i(TAG, "surfaceChanged ${w}x${h}, needsSend=$needsSend, sent=$surfaceSent")
        surface = holder.surface
        // 布局变化、旋转、setFixedSize 都会触发这里。已经发过就绝不再发：
        // 重复 setSurface 会让 crosvm 抛 IAE 并拆掉 display（黑屏根因）。
        if (needsSend && !surfaceSent) trySendSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceDestroyed")
        runCatching {
            displayService?.let { svc ->
                // 先留最后一帧，再 removeSurface。重建后由 markSent 画回去。
                runCatching { svc.saveFrameForSurface(false); hasSavedFrame = true }
                runCatching { svc.removeSurface(false) }
            }
        }
        surface = null
        surfaceSet = false
        surfaceSent = false
        needsSend = false
    }

    // ---------------- 输入 ----------------
    /** 输入坐标缩放：基准是设备声明的 ABS 范围 inputW/H，不是会被 displayConfig 改写的 guestW/H。 */
    private fun scales(): PointF {
        val w = surfaceView.width.takeIf { it > 0 } ?: 1
        val h = surfaceView.height.takeIf { it > 0 } ?: 1
        val bw = if (inputW > 0) inputW else guestW
        val bh = if (inputH > 0) inputH else guestH
        return PointF(
            if (bw > 0) bw.toFloat() / w else 1f,
            if (bh > 0) bh.toFloat() / h else 1f,
        )
    }

    /** 设备声明的 ABS 上限，用于把坐标 clamp 在合法范围内（防 guest input core 截到边角）。 */
    private fun inputBounds(): Pair<Int, Int> = Pair(
        (if (inputW > 0) inputW else guestW).coerceAtLeast(1),
        (if (inputH > 0) inputH else guestH).coerceAtLeast(1),
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(v: View) {
        v.setOnTouchListener { _, e ->
            val s = scales()
            val (bw, bh) = inputBounds()
            // v2.0.13 关键修复保留：必须用 isFromSource()（内部 (source & mask) == mask），
            // 不能用 `source and SOURCE_MOUSE != 0`（SOURCE_TOUCHSCREEN=0x1002 与
            // SOURCE_MOUSE=0x2002 共有 0x2 低位，按位与会把手误判成鼠标）。
            val isPhysicalPointer =
                e.isFromSource(android.view.InputDevice.SOURCE_MOUSE) ||
                    e.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)
            when {
                // 多点触控模式（手指）：保留 7788 触摸屏设备给 iOS/手势，绝不删除。
                // 坐标基准已改为 inputW/H，与 multi-touch[width,height] 声明一致。
                inputMode == MODE_MULTITOUCH && !isPhysicalPointer -> {
                    EvdevEncoder.sendMultiTouch(
                        NativeInputBridge.sink(NativeInputBridge.CH_TOUCH), e, s.x, s.y)
                }
                // v2.0.24 触摸板模式（手指）：整块屏当笔记本触摸板，见 handleTouchpad()。
                // 同样走 7790，但由 APP 维护虚拟光标、把手指位移增量累加后下发，
                // 不要求 crosvm 具备 REL_X/REL_Y —— 因此启动链 / 输入设备一律不用改。
                inputMode == MODE_TOUCHPAD && !isPhysicalPointer -> {
                    handleTouchpad(e, s.x, s.y, bw, bh)
                }
                // 直接点按（按哪到哪）以及物理鼠标/触摸板：全部走 7790 的 usb-tablet 绝对指针。
                // v2.0.14：7790 已从相对 mouse 换成 absolute-mouse，能力位图只有 EV_ABS，
                // 必须发 ABS_X/ABS_Y，发 EV_REL 相对位移会被 guest 按能力位图直接丢弃。
                // 绝对指针「按哪到哪」，不需要 APP 维护虚拟光标、不存在漂移/不同步。
                else -> {
                    val sink = NativeInputBridge.sink(NativeInputBridge.CH_MOUSE)
                    if (!absPointer) {
                        // 旧工具链回退：7790 仍是相对 mouse，原样转发 MotionEvent（v2.0.13 行为）。
                        // 探测到二进制没有 absolute-mouse 解析器时才走这里，保证 VM 一定起得来。
                        EvdevEncoder.sendMouse(sink, e)
                    } else {
                        val tx = (e.x * s.x).roundToInt().coerceIn(0, bw - 1)
                        val ty = (e.y * s.y).roundToInt().coerceIn(0, bh - 1)
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN ->
                                EvdevEncoder.sendAbsoluteMouse(sink, tx, ty, true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                EvdevEncoder.sendAbsoluteMouse(sink, tx, ty, false)
                            MotionEvent.ACTION_MOVE ->
                                EvdevEncoder.sendAbsoluteMouse(sink, tx, ty, null)
                            MotionEvent.ACTION_SCROLL -> {
                                val sx = e.getAxisValue(MotionEvent.AXIS_HSCROLL).toInt()
                                val sy = e.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt()
                                EvdevEncoder.sendAbsoluteWheel(sink, sx, sy)
                            }
                            else -> Unit
                        }
                    }
                }
            }
            hideIme()
            true
        }
    }

    /**
     * v2.0.24：把整块手机屏当笔记本触摸板用。
     *
     * 为什么不直接发 EV_REL：当前 7790 是 `absolute-mouse`，能力位图只有
     * EV_ABS(ABS_X,ABS_Y) + EV_KEY(BTN_*) + EV_REL(WHEEL,HWHEEL)，**没有 REL_X/REL_Y**，
     * 发相对位移会被 guest input core 按能力位图直接丢弃（见 EvdevEncoder 注释）。
     * 所以这里在 APP 内维护一个虚拟光标：手指位移增量累加到它，再走已验证可用的
     * EV_ABS 绝对通道下发。guest 看到的是「光标从当前位置按增量移动」，正是触摸板手感，
     * 而 crosvm / --input / 启动脚本 / 启动盘 / 内核一律不用改，也不需要重启手机。
     *
     * absPointer=false（旧工具链回退，7790 是相对 mouse）时更简单：增量直接交给
     * sendMouseMotion 发 EV_REL，连虚拟光标都不用维护。
     *
     * 手势（对齐笔记本触摸板）：
     *   单指滑动     → 移动光标（手指不必对准目标）
     *   单指轻点     → 左键单击（在光标当前位置，不跳到手指落点）
     *   按住不动再滑 → 左键按住拖动
     *   双指滑动     → 滚轮（上下 / 左右）
     *   双指轻点     → 右键单击
     */
    private fun handleTouchpad(e: MotionEvent, sx: Float, sy: Float, bw: Int, bh: Int) {
        val sink = NativeInputBridge.sink(NativeInputBridge.CH_MOUSE)
        val d = resources.displayMetrics.density
        val slop = TP_TAP_SLOP_DP * d
        val step = TP_WHEEL_STEP_DP * d
        val gain = TP_GAINS[tpGainIdx.coerceIn(0, TP_GAINS.size - 1)]
        val now = e.eventTime

        fun cancelLongPress() {
            tpLongPress?.let { ui.removeCallbacks(it) }
            tpLongPress = null
        }
        /** 一帧绝对坐标；相对设备（旧工具链）不需要它。 */
        fun absFrame(button: Boolean?) {
            if (absPointer) {
                EvdevEncoder.sendAbsoluteMouse(
                    sink,
                    tpVX.roundToInt().coerceIn(0, bw - 1),
                    tpVY.roundToInt().coerceIn(0, bh - 1),
                    button,
                )
            }
        }
        /** 在当前光标位置单击（buttonRight=true 为右键）。 */
        fun click(buttonRight: Boolean) {
            if (buttonRight) {
                EvdevEncoder.sendRightClick(sink)
            } else if (absPointer) {
                absFrame(true); absFrame(false)
            } else {
                EvdevEncoder.sendMouseButton(sink, true)
                EvdevEncoder.sendMouseButton(sink, false)
            }
        }
        fun buttonDown(down: Boolean) {
            if (absPointer) absFrame(down)
            else EvdevEncoder.sendMouseButton(sink, down)
        }
        // absolute-mouse 与 mouse 的能力位图都含 REL_WHEEL/HWHEEL，滚轮协议通用。
        fun wheel(h: Int, v: Int) = EvdevEncoder.sendAbsoluteWheel(sink, h, v)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelLongPress()
                tpDownX = e.x; tpDownY = e.y
                tpLastX = e.x; tpLastY = e.y
                tpDownTime = now
                tpMoved = false; tpDragging = false
                tpTwoFinger = false; tpTwoMoved = false; tpTwoTapDone = false
                tpWheelAccX = 0f; tpWheelAccY = 0f
                // 首次进入：把 guest 光标拉到屏幕中心并与本地虚拟光标对齐，之后两边
                // 一直同步。否则第一次滑动会从 guest 一个未知位置突然跳过来。
                if (!tpInit) {
                    tpVX = bw / 2f; tpVY = bh / 2f
                    tpInit = true
                    absFrame(null)
                    updateCursorOverlay()   // v2.0.25：对齐后立刻显示箭头
                }
                val lp = Runnable {
                    if (!tpMoved && !tpTwoFinger && !tpDragging) {
                        tpDragging = true
                        buttonDown(true)
                    }
                }
                tpLongPress = lp
                ui.postDelayed(lp, TP_LONG_PRESS_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    cancelLongPress()
                    // 单指按住拖动中途来了第二指：先松开左键，别让 guest 卡在按下态
                    if (tpDragging) { buttonDown(false); tpDragging = false }
                    tpTwoFinger = true
                    tpTwoMoved = false
                    tpTwoTapDone = false
                    tpTwoDownTime = now
                    tpTwoCX = (e.getX(0) + e.getX(1)) / 2f
                    tpTwoCY = (e.getY(0) + e.getY(1)) / 2f
                    tpWheelAccX = 0f; tpWheelAccY = 0f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (tpTwoFinger && e.pointerCount >= 2) {
                    val cx = (e.getX(0) + e.getX(1)) / 2f
                    val cy = (e.getY(0) + e.getY(1)) / 2f
                    val dcx = cx - tpTwoCX
                    val dcy = cy - tpTwoCY
                    tpTwoCX = cx; tpTwoCY = cy
                    if (kotlin.math.abs(dcx) + kotlin.math.abs(dcy) > slop) tpTwoMoved = true
                    // 自然滚动：手指往哪滑内容往哪走（双指上滑 → 页面向下 → REL_WHEEL +1）
                    tpWheelAccX += -dcx
                    tpWheelAccY += -dcy
                    while (kotlin.math.abs(tpWheelAccY) >= step) {
                        val v = if (tpWheelAccY > 0) 1 else -1
                        wheel(0, v)
                        tpWheelAccY -= (if (tpWheelAccY > 0) 1f else -1f) * step
                    }
                    while (kotlin.math.abs(tpWheelAccX) >= step) {
                        val h = if (tpWheelAccX > 0) 1 else -1
                        wheel(h, 0)
                        tpWheelAccX -= (if (tpWheelAccX > 0) 1f else -1f) * step
                    }
                } else if (!tpTwoFinger && e.pointerCount == 1) {
                    val dx = e.x - tpLastX
                    val dy = e.y - tpLastY
                    tpLastX = e.x; tpLastY = e.y
                    if (kotlin.math.abs(e.x - tpDownX) + kotlin.math.abs(e.y - tpDownY) > slop) {
                        tpMoved = true
                        cancelLongPress()
                    }
                    if (dx != 0f || dy != 0f) {
                        // v2.0.25：虚拟光标两种工具链都维护 —— 绝对通道靠它下发坐标，
                        // 相对通道虽不下发，但软件箭头需要它来定位。
                        tpVX = (tpVX + dx * sx * gain).coerceIn(0f, (bw - 1).toFloat())
                        tpVY = (tpVY + dy * sy * gain).coerceIn(0f, (bh - 1).toFloat())
                        if (absPointer) {
                            absFrame(null)
                        } else {
                            EvdevEncoder.sendMouseMotion(
                                sink,
                                (dx * sx * gain).roundToInt(),
                                (dy * sy * gain).roundToInt(),
                            )
                        }
                        updateCursorOverlay()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (tpTwoFinger && !tpTwoTapDone) {
                    if (!tpTwoMoved && now - tpTwoDownTime < TP_TAP_TIMEOUT_MS) {
                        click(true)              // 双指轻点 = 右键
                        tpTwoTapDone = true
                    }
                    tpTwoFinger = false
                    tpMoved = true              // 回到单指时别再判成轻点
                    // 用剩下那根指的坐标做新基准，避免抬指瞬间光标跳一下
                    val idx = if (e.actionIndex == 0) 1 else 0
                    if (idx < e.pointerCount) {
                        tpLastX = e.getX(idx); tpLastY = e.getY(idx)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                if (tpTwoFinger && !tpTwoTapDone &&
                    !tpTwoMoved && now - tpTwoDownTime < TP_TAP_TIMEOUT_MS) {
                    click(true)                  // 两指几乎同时抬起
                    tpTwoTapDone = true
                }
                if (tpDragging) {
                    buttonDown(false)
                } else if (!tpMoved && !tpTwoFinger &&
                    e.actionMasked == MotionEvent.ACTION_UP &&
                    now - tpDownTime < TP_TAP_TIMEOUT_MS) {
                    click(false)                 // 轻点 = 左键单击
                }
                tpDragging = false
                tpTwoFinger = false
                tpMoved = false
            }
        }
    }

    /**
     * v2.0.25 软件鼠标箭头；v2.0.26 整体缩小并支持三档大小。
     *
     * 只画一个 28dp 的小 View（28dp 是绘制上界，不是显示尺寸），靠 translationX/Y 移动，
     * 重绘范围只有自身。箭头尖端落在 (0,0)，正是 guest 光标位置。
     * 白填充 + 黑描边，深/浅背景都能看清。
     */
    private class CursorOverlayView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val arrow = Path()
        private val unit = d * 1.15f

        init {
            // 标准鼠标箭头轮廓（单位 dp，尖端在 0,0）
            arrow.moveTo(0f, 0f)
            arrow.lineTo(0f, 19f * unit)
            arrow.lineTo(4.6f * unit, 14.6f * unit)
            arrow.lineTo(8.2f * unit, 22.5f * unit)
            arrow.lineTo(11.2f * unit, 21.1f * unit)
            arrow.lineTo(7.6f * unit, 13.4f * unit)
            arrow.lineTo(14.2f * unit, 13.4f * unit)
            arrow.close()
        }

        /**
         * v2.0.26：以尖端 (0,0) 为锚缩放。pivot 默认是 View 中心（14dp），不改成 0 的话
         * 缩放会让尖端偏移 (1-scale)*14dp，箭头就和 guest 光标错位了。
         * 白填充、黑描边一起等比变细，小箭头配细边更自然。
         */
        fun setSizeScale(scale: Float) {
            pivotX = 0f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
        }

        /** localX/Y 是相对 SurfaceView 左上角的局部坐标；ox/oy 是 SurfaceView 在 root 中的偏移（横屏等比居中时非 0）。 */
        fun setPosition(localX: Float, localY: Float, ox: Float, oy: Float) {
            translationX = ox + localX
            translationY = oy + localY
        }

        override fun onDraw(canvas: Canvas) {
            // 先描黑边再填白，尖角处也能留出均匀轮廓
            canvas.drawPath(arrow, stroke)
            canvas.drawPath(arrow, fill)
        }

        // 关键：绝不消费触摸，事件继续穿透到下层 SurfaceView 的 OnTouchListener。
        // 历史教训：早期用第二个 SurfaceView 画光标，把触摸全吞了，已删除。
        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }

    /** 把虚拟光标（guest ABS 坐标）映射回 View 局部坐标，并更新叠加层位置 / 可见性。 */
    private fun updateCursorOverlay() {
        val cv = cursorView ?: return
        // v2.0.26：每次都重新应用缩放，避免 loadInputMode / 配置变更的先后顺序影响默认档位。
        cv.setSizeScale(CURSOR_SCALES[cursorSizeIdx.coerceIn(0, CURSOR_SCALES.size - 1)])
        // 只在触摸板模式显示：只有这个模式 APP 才持有光标位置；且要与 guest 对齐过（tpInit）。
        val show = inputMode == MODE_TOUCHPAD && cursorVisible && tpInit
        cv.visibility = if (show) View.VISIBLE else View.INVISIBLE
        if (!show) return
        val s = scales()
        val localX = if (s.x != 0f) tpVX / s.x else tpVX
        val localY = if (s.y != 0f) tpVY / s.y else tpVY
        val ox = if (::surfaceView.isInitialized) surfaceView.left.toFloat() else 0f
        val oy = if (::surfaceView.isInitialized) surfaceView.top.toFloat() else 0f
        cv.setPosition(localX, localY, ox, oy)
    }

    /**
     * v2.0.27：把「当前启动盘组合」折成一个稳定的记忆键。
     *
     * guest 有没有自带指针，APP 侧检测不到：原生显示只交出一个 Surface，帧缓冲由
     * crosvm 直写，APP 拿不到像素；simplefb 也没有硬件光标通道（AIDL 那条主 APP
     * 从未启用，早期叠 cursor surface 还因吞触摸被删）。硬做图像识别会误判 + 耗电。
     * 所以用「按盘记忆」代替自动检测：桌面系统盘默认关，遇到没指针的固件/GRUB
     * 界面点开一次，之后这套盘一直开；换盘互不干扰。
     */
    private fun cursorProfileKey(): String = runCatching {
        val c = GunyahManager.loadConfig(this)
        val raw = listOf(c.bootDisk, c.systemDisk, c.cdRom, c.mmio, c.displayBackend)
            .joinToString("|")
        SP_TP_CURSOR_PROFILE + Integer.toHexString(raw.hashCode())
    }.getOrDefault(SP_TP_CURSOR_PROFILE + "default")

    /** 工具条「指针」按钮文字直接带状态，一眼能看出当前画不画。 */
    private fun cursorLabel(): String = if (cursorVisible) Lang.t("指针 开") else Lang.t("指针 关")

    /** v2.0.27 工具条「指针」点按：开/关软件箭头，按当前启动盘组合分别记忆。 */
    private fun toggleCursor() {
        cursorVisible = !cursorVisible
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putBoolean(cursorKey, cursorVisible).apply()
        }
        cursorBtn?.apply {
            text = this@NativeDisplayActivity.cursorLabel()
            alpha = if (cursorVisible) 1f else 0.4f
        }
        updateCursorOverlay()
        Toast.makeText(
            this,
            if (cursorVisible)
                Lang.t("软件箭头：开（仅当前这套启动盘记住；没指针的固件/GRUB 界面用）")
            else
                Lang.t("软件箭头：关（用系统自带指针，不再出现两个箭头）"),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** v2.0.26 长按「指针」：循环箭头大小 小/中/大，持久化。返回后立刻重绘，不用重启原生显示。 */
    private fun cycleCursorSize() {
        cursorSizeIdx = (cursorSizeIdx + 1) % CURSOR_SCALES.size
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putInt(SP_TP_CURSOR_SIZE, cursorSizeIdx).apply()
        }
        updateCursorOverlay()
        val tail = if (cursorVisible) "" else Lang.t("（箭头当前为关，点按「指针」打开后可见）")
        Toast.makeText(
            this,
            "软件光标大小：${CURSOR_SIZE_NAMES[cursorSizeIdx]}（×${CURSOR_SCALES[cursorSizeIdx]}）$tail",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun hideIme() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // v2.0.22：返回键优先收起键盘 —— 上锁时也要能收，否则展开的键盘会
            // 一直卡在屏幕底部，挡住 guest 的 Windows 任务栏 / Linux 开始菜单。
            if (panel.visibility == View.VISIBLE) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    hideKeyPreview()
                    stopRepeatDelete()
                    panel.visibility = View.GONE
                }
                return true
            }
            // 上锁只拦「返回动作」，不再占用屏幕底部（锁已是纯软件锁，见下方注释）。
            if (displayLocked) {
                if (event.action == KeyEvent.ACTION_DOWN) showLockToast()
                return true
            }
        }
        val code = KeyCodeMapper.androidToEvdev(event.keyCode)
        if (code != null) {
            val sink = NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> EvdevEncoder.sendKey(sink, code.toShort(), true)
                KeyEvent.ACTION_UP -> EvdevEncoder.sendKey(sink, code.toShort(), false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** v2.0.20：手势导航的返回在部分 ROM 上不走 KeyEvent，这里再兜一层。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 手势返回不走 KeyEvent，这里同样先收键盘，再判锁。
        if (panel.visibility == View.VISIBLE) {
            hideKeyPreview()
            stopRepeatDelete()
            panel.visibility = View.GONE
            return
        }
        if (displayLocked) {
            showLockToast()
            return
        }
        super.onBackPressed()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // v2.0.13：同 attachTouch，避免把手指的 SOURCE_TOUCHSCREEN 误判成 SOURCE_MOUSE
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)) {
            EvdevEncoder.sendMouse(NativeInputBridge.sink(NativeInputBridge.CH_MOUSE), event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * v2.0.16：支持 Shift 组合（小键盘的 + 和 * 走 Shift+主键盘符号键）。
     * 顺序与参考实现 InputForwarder 一致：Shift↓ → 键↓ … 键↑ → Shift↑。
     */
    private fun sendKeyTap(k: KbKey) {
        val sink = NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD)
        if (k.shift) {
            EvdevEncoder.sendKey(sink, KeyCodeMapper.KEY_LEFTSHIFT.toShort(), true)
        }
        EvdevEncoder.sendKey(sink, k.code.toShort(), true)
        ui.postDelayed({
            EvdevEncoder.sendKey(sink, k.code.toShort(), false)
            if (k.shift) {
                EvdevEncoder.sendKey(sink, KeyCodeMapper.KEY_LEFTSHIFT.toShort(), false)
            }
        }, 45)
    }

    // ---------------- 键盘入口：浮球 ⇄ 贴边透明细条（v2.0.18） ----------------
    /**
     * 同一个 View 两种形态，避免两个入口并存互相抢显隐：
     *  - 浮球：44dp ⌨，点一下出/收全键盘，长按看诊断，可自由拖动；
     *  - 收纳：拖到任一屏幕边缘松手 → 吸附成 13dp 厚的半透明模糊细条，只占一条边，
     *    点它出键盘，沿边可滑动换位置，向屏内拖 42dp 以上还原浮球。
     * 形态、所贴边、位置全部持久化；旋转/窗口尺寸变化自动重新贴边。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildBall(): TextView = TextView(this).apply {
        text = "⌨"
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        setBackgroundColor(0xAA4488AA.toInt())
        val d = resources.displayMetrics.density
        val size = (44 * d).roundToInt()
        layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        alpha = 0.85f
        var downX = 0f; var downY = 0f; var ox = 0f; var oy = 0f; var moved = false
        var undocked = false   // 本次拖动是否已从细条还原成浮球
        var longPress: Runnable? = null
        fun cancelLongPress() { longPress?.let { ui.removeCallbacks(it) }; longPress = null }
        setOnTouchListener { v, e ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    ox = lp.leftMargin.toFloat(); oy = lp.topMargin.toFloat()
                    moved = false; undocked = false
                    entryDragging = true
                    // v2.0.15：状态卡片删了，长按 ⌨ 临时看链路诊断（不占画面）。
                    longPress = Runnable { showDiagnostics(); longPress = null }
                    longPress?.let { ui.postDelayed(it, 600) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 8f) {
                        moved = true; cancelLongPress()
                    }
                    val W = frameW(); val H = frameH()
                    if (dockEdge == 0) {
                        // 浮球：自由拖动（TOP|START 定位，不再依赖 END 的右边界反算）
                        lp.leftMargin = (ox + dx).roundToInt()
                            .coerceIn(0, (W - v.width).coerceAtLeast(0))
                        lp.topMargin = (oy + dy).roundToInt()
                            .coerceIn(0, (H - v.height).coerceAtLeast(0))
                        v.layoutParams = lp
                    } else {
                        // 细条：向屏内拖足够远 → 还原浮球；否则沿边滑动
                        val off = when (dockEdge) {
                            1 -> dx; 2 -> -dx; 3 -> dy; else -> -dy
                        }
                        if (!undocked && off > UNSNAP_DP * d) {
                            undocked = true
                            dockEdge = 0
                            ballRx = (e.rawX / W).coerceIn(0f, 1f)
                            ballRy = (e.rawY / H).coerceIn(0f, 1f)
                            applyEntryShape()
                            downX = e.rawX; downY = e.rawY   // 重设拖动基准，球不跳
                            val nlp = v.layoutParams as FrameLayout.LayoutParams
                            ox = nlp.leftMargin.toFloat(); oy = nlp.topMargin.toFloat()
                            saveEntry()
                            Toast.makeText(
                                this@NativeDisplayActivity,
                                Lang.t("已还原浮球，拖到屏幕边缘可再次收纳"), Toast.LENGTH_SHORT
                            ).show()
                        } else if (undocked) {
                            lp.leftMargin = (ox + dx).roundToInt()
                                .coerceIn(0, (W - v.width).coerceAtLeast(0))
                            lp.topMargin = (oy + dy).roundToInt()
                                .coerceIn(0, (H - v.height).coerceAtLeast(0))
                            v.layoutParams = lp
                        } else {
                            if (dockEdge == 1 || dockEdge == 2) {
                                dockPos = ((oy + dy) + v.height / 2f) / H
                            } else {
                                dockPos = ((ox + dx) + v.width / 2f) / W
                            }
                            dockPos = dockPos.coerceIn(0.06f, 0.94f)
                            lp.leftMargin = when (dockEdge) {
                                1 -> 0
                                2 -> W - v.width
                                else -> (W * dockPos - v.width / 2f).roundToInt()
                                    .coerceIn(0, (W - v.width).coerceAtLeast(0))
                            }
                            lp.topMargin = when (dockEdge) {
                                3 -> 0
                                4 -> H - v.height
                                else -> (H * dockPos - v.height / 2f).roundToInt()
                                    .coerceIn(0, (H - v.height).coerceAtLeast(0))
                            }
                            v.layoutParams = lp
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    entryDragging = false
                    val W = frameW(); val H = frameH()
                    if (dockEdge == 0) {
                        if (!moved) {
                            togglePanel()
                        } else {
                            val cx = lp.leftMargin + v.width / 2f
                            val cy = lp.topMargin + v.height / 2f
                            val dl = cx; val dr = W - cx; val dt = cy; val db = H - cy
                            val near = minOf(dl, dr, dt, db)
                            if (!undocked && near < EDGE_SNAP_DP * d) {
                                dockEdge = when (near) {
                                    dl -> 1; dr -> 2; dt -> 3; else -> 4
                                }
                                dockPos = (if (dockEdge <= 2) cy / H else cx / W)
                                    .coerceIn(0.06f, 0.94f)
                                applyEntryShape()
                                entryBounce()
                                saveEntry()
                                showDockTipOnce()
                            } else {
                                ballRx = (cx / W).coerceIn(0f, 1f)
                                ballRy = (cy / H).coerceIn(0f, 1f)
                                positionEntry()
                                saveEntry()
                            }
                        }
                    } else {
                        if (!moved) togglePanel() else saveEntry()
                    }
                    v.performClick(); true
                }
                MotionEvent.ACTION_CANCEL -> { cancelLongPress(); entryDragging = false; true }
                else -> true
            }
        }
    }

    /** 按 dockEdge 应用形态：宽高、背景、文字、模糊；随后按实测宽高贴边定位。 */
    @SuppressLint("NewApi")
    private fun applyEntryShape() {
        if (!::ball.isInitialized || !::rootFrame.isInitialized) return
        val d = resources.displayMetrics.density
        val lp = ball.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.rightMargin = 0; lp.bottomMargin = 0
        if (dockEdge == 0) {
            val size = (44 * d).roundToInt()
            lp.width = size; lp.height = size
            ball.setBackgroundColor(0xAA4488AA.toInt())   // 与原浮球完全一致，不做外观负优化
            ball.text = "⌨"
            ball.setTextColor(Color.WHITE)
            ball.textSize = 20f
            ball.alpha = 0.85f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { ball.setRenderEffect(null) }
            }
        } else {
            val thick = (DOCK_THICK_DP * d).roundToInt()
            val long = (DOCK_LONG_DP * d).roundToInt()
            val vertical = dockEdge == 1 || dockEdge == 2
            lp.width = if (vertical) thick else long
            lp.height = if (vertical) long else thick
            ball.background = buildDockGlass(vertical, thick, d)
            ball.text = ""      // 细条太窄，用中间一条小白杠代替文字，不会溢出边界
            ball.alpha = 1f
            // 背景毛玻璃：API31+ 再叠一层轻模糊；低版本靠半透明渐变 + 高光细边模拟。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    ball.setRenderEffect(
                        RenderEffect.createBlurEffect(2.2f, 2.2f, Shader.TileMode.CLAMP)
                    )
                }
            }
        }
        ball.layoutParams = lp
        entryReady = true
        positionEntry()
    }

    /** 半透明渐变 + 细白边 + 居中小白杠 = 模糊透明「杠条」（不依赖系统窗口模糊）。 */
    private fun buildDockGlass(vertical: Boolean, thick: Int, d: Float): LayerDrawable {
        val glass = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x66FFFFFF.toInt(), 0x33FFFFFF.toInt(), 0x1EFFFFFF.toInt())
        ).apply {
            cornerRadius = thick / 2f
            setStroke((0.8f * d).roundToInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
        }
        val gw = if (vertical) (3 * d).roundToInt() else (24 * d).roundToInt()
        val gh = if (vertical) (24 * d).roundToInt() else (3 * d).roundToInt()
        val grip = GradientDrawable().apply {
            cornerRadius = minOf(gw, gh) / 2f
            setColor(0xD9FFFFFF.toInt())
        }
        return LayerDrawable(arrayOf(glass, grip)).apply {
            setLayerSize(1, gw, gh)
            setLayerGravity(1, Gravity.CENTER)
        }
    }

    /** 只重算 left/top margin；幂等，供旋转与窗口尺寸变化反复调用。 */
    private fun positionEntry() {
        if (!entryReady || !::ball.isInitialized || !::rootFrame.isInitialized) return
        val W = frameW(); val H = frameH()
        val lp = ball.layoutParams as FrameLayout.LayoutParams
        val w = lp.width; val h = lp.height
        if (w <= 0 || h <= 0) return
        val left: Int
        val top: Int
        if (dockEdge == 0) {
            left = (W * ballRx - w / 2f).roundToInt()
                .coerceIn(0, (W - w).coerceAtLeast(0))
            top = (H * ballRy - h / 2f).roundToInt()
                .coerceIn(0, (H - h).coerceAtLeast(0))
        } else {
            when (dockEdge) {
                1 -> {
                    left = 0
                    top = (H * dockPos - h / 2f).roundToInt().coerceIn(0, (H - h).coerceAtLeast(0))
                }
                2 -> {
                    left = W - w
                    top = (H * dockPos - h / 2f).roundToInt().coerceIn(0, (H - h).coerceAtLeast(0))
                }
                3 -> {
                    left = (W * dockPos - w / 2f).roundToInt().coerceIn(0, (W - w).coerceAtLeast(0))
                    top = 0
                }
                else -> {
                    left = (W * dockPos - w / 2f).roundToInt().coerceIn(0, (W - w).coerceAtLeast(0))
                    top = H - h
                }
            }
        }
        val g = Gravity.TOP or Gravity.START
        if (lp.leftMargin == left && lp.topMargin == top && lp.gravity == g) return
        lp.gravity = g; lp.leftMargin = left; lp.topMargin = top
        ball.layoutParams = lp
    }

    private fun frameW(): Int = rootFrame.width.takeIf { it > 0 }
        ?: resources.displayMetrics.widthPixels

    private fun frameH(): Int = rootFrame.height.takeIf { it > 0 }
        ?: resources.displayMetrics.heightPixels

    private fun togglePanel() {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        ball.alpha = if (panel.visibility == View.VISIBLE) 1.0f
        else if (dockEdge == 0) 0.85f else 1f
        if (panel.visibility == View.GONE) {
            hideKeyPreview()
            stopRepeatDelete()
        }
    }

    /** 收纳/解收纳时的轻弹动画，让形态切换不生硬。 */
    private fun entryBounce() {
        runCatching {
            ball.animate().cancel()
            ball.scaleX = 0.62f; ball.scaleY = 0.62f; ball.alpha = 0.45f
            ball.animate().scaleX(1f).scaleY(1f)
                .alpha(if (dockEdge == 0) 0.85f else 1f)
                .setDuration(180).start()
        }
    }

    private fun showDockTipOnce() {
        runCatching {
            val sp = getSharedPreferences(SP_NAME, MODE_PRIVATE)
            if (sp.getBoolean(SP_DOCK_TIP, false)) return@runCatching
            sp.edit().putBoolean(SP_DOCK_TIP, true).apply()
            Toast.makeText(
                this,
                Lang.t("已贴边收纳：点细条出键盘，沿边滑动换位置，向屏内拖动可还原 ⌨"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun loadEntry() {
        runCatching {
            val sp = getSharedPreferences(SP_NAME, MODE_PRIVATE)
            dockEdge = sp.getInt(SP_DOCK_EDGE, 0).coerceIn(0, 4)
            dockPos = sp.getFloat(SP_DOCK_POS, 0.45f).coerceIn(0.06f, 0.94f)
            ballRx = sp.getFloat(SP_BALL_X, 0.93f).coerceIn(0f, 1f)
            ballRy = sp.getFloat(SP_BALL_Y, 0.44f).coerceIn(0f, 1f)
        }
    }

    private fun saveEntry() {
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putInt(SP_DOCK_EDGE, dockEdge)
                .putFloat(SP_DOCK_POS, dockPos)
                .putFloat(SP_BALL_X, ballRx)
                .putFloat(SP_BALL_Y, ballRy)
                .apply()
        }
    }

    // ---------------- 单页全键盘（v2.0.15，按确认的设计稿 v3） ----------------
    // 主键盘数字行已删除，数字全部交给右侧小键盘；「切回VNC」入口已移除。
    // 所有键走已验证可用的 7789 键盘通道 sendKeyTap()，不碰输入桥 / 启动脚本 / --input。
    private data class KbKey(
        val label: String, val code: Int, val weight: Float = 1f, val shift: Boolean = false
    )

    private fun kbLetters(s: String, base: Int): List<KbKey> =
        s.mapIndexed { i, c -> KbKey(c.toString(), base + i) }

    private val mainRows: List<List<KbKey>> by lazy {
        val K = KeyCodeMapper
        listOf(
            listOf(KbKey("Esc", K.KEY_ESC, 1.3f)) +
                (1..10).map { KbKey("F$it", K.KEY_F1 + it - 1) } +
                listOf(KbKey("F11", K.KEY_F11), KbKey("F12", K.KEY_F12),
                    KbKey("⌫", K.KEY_BACKSPACE, 1.5f)),
            listOf(KbKey("Tab", K.KEY_TAB, 1.5f)) + kbLetters("QWERTYUIOP", K.KEY_Q) +
                listOf(KbKey("[", K.KEY_LEFTBRACE), KbKey("]", K.KEY_RIGHTBRACE),
                    KbKey("\\", K.KEY_BACKSLASH, 1.5f)),
            listOf(KbKey("Caps", K.KEY_CAPSLOCK, 1.7f)) + kbLetters("ASDFGHJKL", K.KEY_A) +
                listOf(KbKey(";", K.KEY_SEMICOLON), KbKey("'", K.KEY_APOSTROPHE),
                    KbKey("↵", K.KEY_ENTER, 2f)),
            listOf(KbKey("⇧", K.KEY_LEFTSHIFT, 2.2f)) + kbLetters("ZXCVBNM", K.KEY_Z) +
                listOf(KbKey(",", K.KEY_COMMA), KbKey(".", K.KEY_DOT),
                    KbKey("/", K.KEY_SLASH), KbKey("⇧", K.KEY_RIGHTSHIFT, 2.2f)),
            listOf(
                KbKey("Ctrl", K.KEY_LEFTCTRL, 1.2f), KbKey("Alt", K.KEY_LEFTALT, 1.2f),
                KbKey("Space", K.KEY_SPACE, 6.5f),
                KbKey("Alt", K.KEY_RIGHTALT, 1.2f), KbKey("Ctrl", K.KEY_RIGHTCTRL, 1.2f),
                KbKey("←", K.KEY_LEFT, 0.9f), KbKey("↑", K.KEY_UP, 0.9f),
                KbKey("↓", K.KEY_DOWN, 0.9f), KbKey("→", K.KEY_RIGHT, 0.9f),
            ),
        )
    }

    /**
     * v2.0.16：小键盘改用「主键盘数字/符号键码」，不再发 KEY_KP*。
     * 根因：crosvm 键盘设备位图确实包含全部 KEY_*（defaults.rs 里 KEY_ESC..KEY_MICMUTE），
     * 事件不会被丢；但 guest(KDE/XKB) 的 NumLock 默认关闭，KP7 被解释成 Home、KP8 成 ↑，
     * 于是按 7 永远不出数字 —— 用户看到的「小键盘用不了」。主键盘数字码不受 NumLock 影响，
     * 和已经验证可用的字母/F 键走完全相同的键位区间，保证按下去就出字符。
     * `+` / `*` 用 Shift+KEY_EQUAL / Shift+KEY_8 合成（与参考实现 InputForwarder 一致）。
     */
    private val numRows: List<List<KbKey>> by lazy {
        val K = KeyCodeMapper
        listOf(
            // v2.0.17 按用户要求删掉小键盘的 ⌫(X 图标) 与 ↵（v2.0.19 保持删除，不加回）。
            // v2.0.19：空位改成「可见的深色空格子」，补齐 5 行 × 4 列完整网格（图1 观感），
            // 但格子不发任何事件，功能仍是 v2.0.18。主键盘的 ⌫（长按连删）与 ↵ 保留。
            listOf(KbKey("/", K.KEY_SLASH), KbKey("*", K.KEY_8, shift = true),
                KbKey("-", K.KEY_MINUS), KbKey(" ", 0)),
            listOf(KbKey("7", K.KEY_7), KbKey("8", K.KEY_8),
                KbKey("9", K.KEY_9), KbKey("+", K.KEY_EQUAL, shift = true)),
            listOf(KbKey("4", K.KEY_4), KbKey("5", K.KEY_5),
                KbKey("6", K.KEY_6), KbKey("-", K.KEY_MINUS)),
            listOf(KbKey("1", K.KEY_1), KbKey("2", K.KEY_2),
                KbKey("3", K.KEY_3), KbKey("*", K.KEY_8, shift = true)),
            listOf(KbKey("0", K.KEY_0, 2f), KbKey(".", K.KEY_DOT), KbKey(" ", 0)),
        )
    }

    private fun buildPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xF2101418.toInt())
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }
        val d = resources.displayMetrics.density

        // v2.0.20 顶部窄工具条：模式 / 小键盘 / 旋转 / 🔒 / 诊断 / 收起。
        // 「铺满」「紧凑」按用户要求移除，默认固定图1 布局。无「切回VNC」。
        // 工具条同时是键盘窗口的拖动把手：轻点走各按钮，按住超过 12dp 则移动整个键盘层。
        val bar = DragBar(this@NativeDisplayActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * d).roundToInt(), (2 * d).roundToInt(),
                (4 * d).roundToInt(), (2 * d).roundToInt())
        }
        // 模式按钮给 1.8 份宽度，装得下「模式: 多点触控」不挤成两行。
        val mt = this@NativeDisplayActivity.barButton(this@NativeDisplayActivity.modeLabel(), 1.8f) {
            this@NativeDisplayActivity.toggleInputMode()
        }
        // v2.0.24：长按循环触摸板光标速度。返回 true 消费长按，不会误触发上面的 click 切模式。
        mt.setOnLongClickListener {
            this@NativeDisplayActivity.cycleTpGain()
            true
        }
        modeToggle = mt
        bar.addView(mt)
        val nb = this@NativeDisplayActivity.barButton(this@NativeDisplayActivity.numpadLabel()) {
            this@NativeDisplayActivity.toggleNumpad()
        }
        numBtn = nb
        bar.addView(nb)
        val rb = this@NativeDisplayActivity.barButton(Lang.t("旋转 0°")) {
            this@NativeDisplayActivity.rotateKeyboard()
        }
        rotateBtn = rb
        bar.addView(rb)
        // v2.0.20：🔒 上锁 / 🔓 解锁（防误退回虚拟机界面或安卓桌面）
        val lb = this@NativeDisplayActivity.barButton(this@NativeDisplayActivity.lockLabel(), 0.7f) {
            this@NativeDisplayActivity.toggleDisplayLock()
        }
        lockBtn = lb
        bar.addView(lb)
        // v2.0.27 软件光标开关：默认关（桌面系统自带指针），点按开/关并按盘记忆。
        val cb = this@NativeDisplayActivity.barButton(this@NativeDisplayActivity.cursorLabel(), 0.9f) {
            this@NativeDisplayActivity.toggleCursor()
        }
        // v2.0.26：长按循环箭头大小。返回 true 消费长按，不会误触发上面的 click 开关。
        cb.setOnLongClickListener {
            this@NativeDisplayActivity.cycleCursorSize()
            true
        }
        cb.alpha = if (this@NativeDisplayActivity.cursorVisible) 1f else 0.4f
        this@NativeDisplayActivity.cursorBtn = cb
        bar.addView(cb)
        bar.addView(this@NativeDisplayActivity.barButton("ⓘ", 0.6f) { this@NativeDisplayActivity.showDiagnostics() })
        bar.addView(this@NativeDisplayActivity.barButton(Lang.t("收起 ×"), 0.9f) {
            this@NativeDisplayActivity.hideKeyPreview()
            this@NativeDisplayActivity.stopRepeatDelete()
            this@NativeDisplayActivity.panel.visibility = View.GONE
        })
        addView(bar)

        val body = LinearLayout(this@NativeDisplayActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val main = LinearLayout(this@NativeDisplayActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mainRows.forEach { row -> main.addView(this@NativeDisplayActivity.buildKeyRow(row)) }
        body.addView(main)

        val np = LinearLayout(this@NativeDisplayActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.32f
            ).apply { leftMargin = (3 * d).roundToInt() }
        }
        numRows.forEach { row -> np.addView(this@NativeDisplayActivity.buildKeyRow(row)) }
        numpadView = np
        body.addView(np)
        addView(body)
    }

    private fun buildKeyRow(keys: List<KbKey>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            keys.forEach { k -> addView(keyButton(k)) }
        }

    /**
     * v2.0.19：图1 布局——工具条是铺满整行的深色通栏，按钮按 weight 等分、
     * 绿色文字、淡分块，不再是挤在左边的一小撮方块。功能键位仍是 v2.0.18 那 5 个
     * （模式 / 小键盘 / 旋转 / ⓘ / 收起），「铺满」「紧凑」不回来。
     */
    private fun barButton(label: String, weight: Float = 1f, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(0xFF9BE7A0.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            val d = resources.displayMetrics.density
            setPadding((2 * d).roundToInt(), (5 * d).roundToInt(),
                (2 * d).roundToInt(), (5 * d).roundToInt())
            setBackgroundColor(0xFF1B232B.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight
            ).apply { setMargins(1, 1, 1, 2) }
            setOnClickListener { onClick() }
        }

    /**
     * v2.0.20：键盘窗口的拖动把手。
     *
     * 轻点（位移 < 12dp）完全按原来的按钮点击走；按住超过阈值才由父容器接管，
     * 子按钮会收到 ACTION_CANCEL 因而不误触发 click。拖动改的是 kbHost 的
     * translationX/Y —— 它在 rotation 之后、以屏幕坐标应用，所以四个旋转角度下
     * 拖动方向都跟手指一致，也不影响 v2.0.19 的 pivot 计算。
     */
    private inner class DragBar(context: Context) : LinearLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var startTy = 0f
        private var dragging = false
        private val slop = DRAG_SLOP_DP * resources.displayMetrics.density

        private fun begin(e: MotionEvent) {
            downX = e.rawX; downY = e.rawY
            startTx = if (::kbHost.isInitialized) kbHost.translationX else 0f
            startTy = if (::kbHost.isInitialized) kbHost.translationY else 0f
            dragging = false
        }

        private fun exceeded(e: MotionEvent): Boolean =
            kotlin.math.abs(e.rawX - downX) + kotlin.math.abs(e.rawY - downY) > slop

        private fun startDrag() {
            dragging = true
            hideKeyPreview()
            stopRepeatDelete()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> if (!dragging && exceeded(ev)) {
                    startDrag()
                    return true
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { begin(e); return true }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && exceeded(e)) startDrag()
                    if (dragging) moveKbWindow(startTx + e.rawX - downX, startTy + e.rawY - downY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 父容器拦截 MOVE 时，系统会把当前事件转成 CANCEL 交给自己；
                    // 这里只收尾保存，不回滚位置，随后的 MOVE 会重新进入拖动，手感连续。
                    if (dragging) { saveKbOffset(); dragging = false }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }

    /** 按当前旋转角度把键盘中心约束在屏幕内，保证任何角度至少还有半屏可见。 */
    private fun moveKbWindow(tx: Float, ty: Float) {
        if (!::kbHost.isInitialized) return
        val W = frameW().toFloat()
        val H = frameH().toFloat()
        if (W <= 0 || H <= 0) return
        val h = (panel.height.takeIf { it > 0 }
            ?: (220 * resources.displayMetrics.density).roundToInt()).toFloat()
        val (minX, maxX, minY, maxY) = when (kbRotation) {
            90 -> floatArrayOf(-h / 2, W - h / 2, -H / 2, H / 2)
            180 -> floatArrayOf(-W / 2, W / 2, -h / 2, H - h / 2)
            270 -> floatArrayOf(-(W - h / 2), h / 2, -H / 2, H / 2)
            else -> floatArrayOf(-W / 2, W / 2, -(H - h / 2), h / 2)
        }
        val nx = tx.coerceIn(minX, maxX)
        val ny = ty.coerceIn(minY, maxY)
        kbHost.translationX = nx
        kbHost.translationY = ny
        kbOffX = nx / W
        kbOffY = ny / H
    }

    /** 从持久化比例恢复位置；旋转 / 屏幕尺寸变化后由 applyKbRotation() 调用。 */
    private fun applyKbOffset() {
        val W = frameW().toFloat()
        val H = frameH().toFloat()
        if (W > 0 && H > 0) moveKbWindow(kbOffX * W, kbOffY * H)
    }

    private fun loadKbOffsetAndLock() {
        runCatching {
            val sp = getSharedPreferences(SP_NAME, MODE_PRIVATE)
            kbOffX = sp.getFloat(SP_KB_OFF_X, 0f)
            kbOffY = sp.getFloat(SP_KB_OFF_Y, 0f)
            displayLocked = sp.getBoolean(SP_DISPLAY_LOCK, true)
        }
    }

    private fun saveKbOffset() {
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putFloat(SP_KB_OFF_X, kbOffX)
                .putFloat(SP_KB_OFF_Y, kbOffY)
                .apply()
        }
        Log.i(TAG, "kb window offset = ($kbOffX, $kbOffY)")
    }

    // ---------------- v2.0.20 / v2.0.21：🔒 防误退锁 ----------------
    private fun lockLabel(): String = if (displayLocked) "🔒" else "🔓"

    private fun toggleDisplayLock() {
        displayLocked = !displayLocked
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putBoolean(SP_DISPLAY_LOCK, displayLocked).apply()
        }
        if (displayLocked) applySoftLock() else clearSoftLock()
        applyLockState()
        Toast.makeText(
            this,
            if (displayLocked)
                Lang.t("🔒 已上锁（软件锁）：拦返回键 / 左右侧滑返回，屏幕底部照常可点任务栏与开始菜单")
            else
                Lang.t("🔓 已解锁：返回键与左右侧滑返回恢复正常"),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * v2.0.22：上锁改为【纯软件防误退】，彻底不再调用 startLockTask() 系统屏幕固定。
     *
     * 为什么要去掉屏幕固定：Android 屏幕固定会在屏幕底部保留一块 SystemUI 的
     * 手势/导航感应区（供「长按返回 + 最近任务」退出固定），这块区域的触摸
     * 不会派发给 Activity —— 正好压在 guest 的 Windows 任务栏 / Linux 开始菜单上，
     * 表现就是用户反馈的「一上锁，屏幕底部就点不了」。
     *
     * 现在上锁只做三件不占屏幕的事：
     *   ① dispatchKeyEvent / onBackPressed 拦返回键（已有）
     *   ② applyLockExclusion() 拦左右边缘侧滑返回（已有）
     *   ③ applyImmersive() 保持沉浸式全屏（已有）
     * 代价：拦不住「底部上滑回桌面 / HOME 键」——那只有系统屏幕固定能做到。
     *      换来的是底部任务栏 / 开始菜单完全可点，正是本次要解决的问题。
     * 从左/右边缘侧滑返回、误触返回键这两类最常见的误退依旧被挡住。
     */
    private fun applySoftLock() {
        runCatching { applyImmersive() }
        applyLockExclusion()
        Log.i(TAG, "soft lock on (no screen pinning; bottom stays touchable)")
    }

    private fun clearSoftLock() {
        if (::rootFrame.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { rootFrame.setSystemGestureExclusionRects(emptyList<Rect>()) }
        }
        runCatching { applyImmersive() }
        Log.i(TAG, "soft lock off")
    }

    /** 从后台回来 / 窗口尺寸变化后补一次软件锁（系统可能重置了手势排除区）。幂等、无弹窗。 */
    private fun syncSoftLock() {
        if (!displayLocked || !::rootFrame.isInitialized) return
        applyLockExclusion()
    }

    private fun applyLockState() {
        lockBtn?.text = lockLabel()
        lockBtn?.setBackgroundColor(
            if (displayLocked) 0xFF5A2630.toInt() else 0xFF1B232B.toInt()
        )
        lockBtn?.setTextColor(
            if (displayLocked) 0xFFFFC4BC.toInt() else 0xFF9BE7A0.toInt()
        )
        applyLockExclusion()
        Log.i(TAG, "display lock = $displayLocked")
    }

    /**
     * 只停用左右边缘的「返回手势」——这是 setSystemGestureExclusionRects 唯一有效的
     * 场景。v2.0.22 起【刻意不在底部放任何排除区】：底部要留给 guest 的任务栏 /
     * 开始菜单，且上锁已改为纯软件锁（不再屏幕固定），无需再为底部手势让路。
     * 底部上滑回桌面因此不再被拦，这是换取底部可点的明确取舍。
     */
    private fun applyLockExclusion() {
        if (!::rootFrame.isInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            if (!displayLocked) {
                rootFrame.setSystemGestureExclusionRects(emptyList<Rect>())
                return
            }
            val W = frameW()
            val H = frameH()
            if (W <= 0 || H <= 0) return
            val side = (80 * resources.displayMetrics.density).roundToInt()
            rootFrame.setSystemGestureExclusionRects(
                listOf(Rect(0, 0, side, H), Rect(W - side, 0, W, H))
            )
        }
    }

    private fun showLockToast() {
        lockToast?.cancel()
        lockToast = Toast.makeText(
            this, Lang.t("🔒 已上锁（防误退）：点工具条 🔓 解锁后返回键才生效；屏幕底部仍可正常点"), Toast.LENGTH_SHORT
        )
        lockToast?.show()
    }

    /**
     * v2.0.16 图2 效果 + v2.0.17 震动 / 长按连删。
     * 按下瞬间：按键高亮 + 上方浮出放大字符气泡 + 12ms 轻震 + 立即发键；
     * ⌫ 另外在 400ms 后进入连续删除，抬手停止。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun keyButton(k: KbKey): TextView =
        TextView(this).apply {
            text = k.label
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, k.weight
            ).apply { setMargins(2, 2, 2, 2) }
            if (k.code == 0) {
                // v2.0.19 空占位：显示成深色空格子（补齐图1 的 5×4 网格，有行高），
                // 但不显示字符、不响应触摸、不震动、不发事件。text 是空格以保证行高一致。
                setBackgroundColor(0xFF161D24.toInt())
                return@apply
            }
            setBackgroundColor(KB_BG)
            val isDelete = k.code == KeyCodeMapper.KEY_BACKSPACE
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.setBackgroundColor(KB_BG_DOWN)
                        showKeyPreview(v, k.label)
                        hapticTick()
                        sendKeyTap(k)
                        if (isDelete) scheduleRepeatDelete()
                        v.performClick()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.setBackgroundColor(KB_BG)
                        hideKeyPreview()
                        stopRepeatDelete()
                        true
                    }
                    else -> true
                }
            }
        }.also { keyViews.add(it) }

    /** 按键震动反馈：12ms 轻震。重复删除不重复震，避免持续嗡鸣。 */
    @Suppress("DEPRECATION")
    private fun hapticTick() {
        runCatching {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(12)
            }
        }
    }

    /** ⌫ 长按：先发一次，400ms 后每 ~90ms 发一对 down/up，直到抬手。 */
    private fun scheduleRepeatDelete() {
        stopRepeatDelete()
        val start = Runnable { startRepeatLoop() }
        repeatStart = start
        ui.postDelayed(start, 400)
    }

    private fun startRepeatLoop() {
        repeatActive = true
        val sink = NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD)
        val code = KeyCodeMapper.KEY_BACKSPACE.toShort()
        val r = object : Runnable {
            override fun run() {
                if (!repeatActive) return
                EvdevEncoder.sendKey(sink, code, true)
                ui.postDelayed({ EvdevEncoder.sendKey(sink, code, false) }, 40)
                ui.postDelayed(this, 90)
            }
        }
        repeatRunnable = r
        r.run()
    }

    private fun stopRepeatDelete() {
        repeatActive = false
        repeatStart?.let { ui.removeCallbacks(it) }
        repeatStart = null
        repeatRunnable?.let { ui.removeCallbacks(it) }
        repeatRunnable = null
    }

    /**
     * 键盘 4 向旋转：0°贴底 / 90°贴左 / 180°贴顶(倒置) / 270°贴右。
     * 整块 kbHost 一起转，按键文字与浮出气泡方向一致；画面与 ⌨ 浮球不转。
     * 纯手动按钮，与手机物理方向完全无关（系统横屏在 guest 设置里改，手机仍竖屏）。
     */
    private fun rotateKeyboard() {
        kbRotation = (kbRotation + 90) % 360
        applyKbRotation()
        rotateBtn?.text = "旋转 ${kbRotation}°"
        Toast.makeText(
            this,
            "键盘 ${kbRotation}°：0°贴底 / 90°贴左 / 180°贴顶 / 270°贴右（与手机方向无关）",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * v2.0.19 旋转「直接消失」根因修复。
     *
     * 旧版只做 `kbHost.rotation = 90`。kbHost 是 MATCH_PARENT 全屏容器，默认 pivot
     * 在它自己的中心 (W/2, H/2)；贴底的键盘中心是 (W/2, H-h/2)，绕屏幕中心转 90°
     * 后会被甩到 x≈(W+H)/2 的屏幕外（1440×3168 时约 860px 出界）—— 用户看到的
     * 就是「点旋转键盘直接消失」。
     *
     * 现在不改 layout、不加 translation（避免依赖变换顺序），只把 pivot 设到按实测
     * 宽高算出的点，让旋转后键盘中心正好落在目标边的中点。屏幕坐标顺时针旋转
     * R90(dx,dy)=(-dy,dx)，令 pivot=(px,py) 满足 pivot+Rθ(C-pivot)=T：
     *   90°  T=(h/2, H/2)    → px=(H+W)/4,      py=(3H-W)/4
     *   180° T=(W/2, h/2)    → px=W/2,          py=H/2
     *   270° T=(W-h/2, H/2)  → px=(3W-H)/4,     py=(3H-W)/4
     * h（键盘高度）被消掉，所以不需要等键盘测量，也不受折叠小键盘影响。
     * 气泡用未旋转布局坐标加在 kbHost 里，随同一变换一起转，位置始终贴合按键。
     */
    private fun applyKbRotation() {
        if (!::kbHost.isInitialized || !::rootFrame.isInitialized) return
        val w = kbHost.width.takeIf { it > 0 } ?: rootFrame.width
        val h = kbHost.height.takeIf { it > 0 } ?: rootFrame.height
        if (w <= 0 || h <= 0) return
        val W = w.toFloat(); val H = h.toFloat()
        val (px, py) = when (kbRotation) {
            90 -> Pair((H + W) / 4f, (3f * H - W) / 4f)
            180 -> Pair(W / 2f, H / 2f)
            270 -> Pair((3f * W - H) / 4f, (3f * H - W) / 4f)
            else -> Pair(W / 2f, H / 2f)
        }
        kbHost.pivotX = px
        kbHost.pivotY = py
        kbHost.rotation = kbRotation.toFloat()
        // v2.0.20：旋转后按新角度重新 clamp 已拖动的位置，键盘不会飞出屏外。
        applyKbOffset()
        Log.i(TAG, "kb rotation $kbRotation° pivot=(${px.roundToInt()},${py.roundToInt()}) in ${w}x${h}")
    }

    /** 累加 left/top 得到子 View 在 host 内的「未旋转」布局坐标。
     *  不依赖 getLocationInWindow —— 父 View 有 rotation 时它的返回值含义会变复杂。 */
    private fun layoutOffsetIn(v: View, host: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== host) {
            x += cur.left
            y += cur.top
            cur = cur.parent as? View
        }
        return Pair(x, y)
    }

    /** 图2：灰底圆角、白色粗体放大字符，浮在按键正上方；第一行空间不够则落到下方。
     *  v2.0.17：气泡加进 kbHost（与键盘同层），旋转时跟着一起转，位置始终贴合按键。 */
    private fun showKeyPreview(anchor: View, label: String) {
        hideKeyPreview()
        if (!::kbHost.isInitialized || anchor.width <= 0) return
        val d = resources.displayMetrics.density
        val bw = (anchor.width * 1.3f).roundToInt().coerceAtLeast((46 * d).roundToInt())
        val bh = (anchor.height * 1.1f).roundToInt().coerceAtLeast((42 * d).roundToInt())
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (label.length > 2) 15f else 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = 8 * d
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(PREVIEW_BG)
            }
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(bw, bh)
        }
        kbHost.addView(tv)
        keyPreview = tv
        val (ax, ay) = layoutOffsetIn(anchor, kbHost)
        val x = ax + anchor.width / 2 - bw / 2
        var y = ay - bh - (4 * d).roundToInt()
        if (y < 0) y = ay + anchor.height + (4 * d).roundToInt()
        val lp = tv.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.leftMargin = x.coerceIn(0, (kbHost.width - bw).coerceAtLeast(0))
        lp.topMargin = y.coerceIn(0, (kbHost.height - bh).coerceAtLeast(0))
        tv.layoutParams = lp
        tv.scaleX = 0.72f; tv.scaleY = 0.72f; tv.alpha = 0f
        tv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start()
    }

    private fun hideKeyPreview() {
        keyPreview?.let {
            runCatching { if (::kbHost.isInitialized) kbHost.removeView(it) }
        }
        keyPreview = null
    }

    // ---------------- 键盘密度：v2.0.17 固定图1 标准档 ----------------
    /** 「紧凑 / 铺满」档位已按用户要求移除，键盘高度固定为图1 的标准档。 */
    private fun applyKbDensity() {
        if (!::panel.isInitialized) return
        val d = resources.displayMetrics.density
        val pv = (6 * d).roundToInt()
        val m = (2 * d).roundToInt()
        for (v in keyViews) {
            v.setPadding(v.paddingLeft, pv, v.paddingRight, pv)
            v.textSize = if (v.text.length >= 3) 12f * 0.82f else 12f
            (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.setMargins(m, m, m, m)
                v.layoutParams = lp
            }
        }
        panel.setPadding(
            (4 * d).roundToInt(), (4 * d).roundToInt(),
            (4 * d).roundToInt(), (6 * d).roundToInt()
        )
    }

    /** 竖屏可一键折叠小键盘，让主键变大；横屏空间够，默认展开。 */
    private fun numpadLabel(): String =
        if (numpadView?.visibility == View.GONE) "小键盘 ×" else "小键盘 ✓"

    private fun toggleNumpad() {
        numpadView?.let {
            it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        numBtn?.text = numpadLabel()
        Toast.makeText(
            this,
            if (numpadView?.visibility == View.GONE) "小键盘已折叠" else "小键盘已展开：数字键直接出数字（不受 NumLock 影响）",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 状态卡片已删；长按 ⌨ 或点 ⓘ 临时看链路，不占画面。 */
    private fun showDiagnostics() {
        val r = NativeInputBridge.channelsReady
        val msg = buildString {
            append(Lang.t("画面 ")).append(if (surfaceSet) "✓" else "✗")
                .append(Lang.t(" 服务 ")).append(if (NativeInputBridge.connected) "✓" else "✗")
                .append(Lang.t(" 写入✓")).append(NativeInputBridge.writeOk)
                .append(" ✗").append(NativeInputBridge.writeFail)
            append("\n").append(if (absPointer) "usb-tablet " else Lang.t("相对mouse(降级) "))
                .append(if (inputW > 0) "${inputW}x${inputH}" else "?")
                .append(" / 画面 ${guestW}x${guestH}")
            append("\n").append(modeLabel())
            if (inputMode == MODE_TOUCHPAD) {
                append(" 速度×${TP_GAINS[tpGainIdx.coerceIn(0, TP_GAINS.size - 1)]}")
                append(Lang.t(" 箭头")).append(
                    if (cursorVisible) CURSOR_SIZE_NAMES[cursorSizeIdx.coerceIn(0, CURSOR_SCALES.size - 1)]
                    else Lang.t("关")
                )
            }
            // v2.0.27：显示当前这套启动盘的记忆键尾，确认「按盘记忆」确实分了档。
            append("\n").append(Lang.t("软件箭头按盘记忆: "))
                .append(cursorKey.removePrefix(SP_TP_CURSOR_PROFILE))
                .append(" / ").append(if (cursorVisible) Lang.t("开") else Lang.t("关（默认）"))
            append("\n").append(NativeInputBridge.inputDiagnostics.replace(" | ", "\n"))
            append(Lang.t("\n通道 触摸/键盘/鼠标/开关: "))
                .append(if (r.getOrNull(0) == true) "✓" else "✗")
                .append(if (r.getOrNull(1) == true) "✓" else "✗")
                .append(if (r.getOrNull(2) == true) "✓" else "✗")
                .append(if (r.getOrNull(3) == true) "✓" else "✗")
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------------- 输入模式切换（v2.0.7） ----------------

    private fun modeLabel(): String = "模式: " + when (inputMode) {
        MODE_MULTITOUCH -> "多点触控"
        MODE_ABSOLUTE -> "直接点按"
        else -> "触摸板"
    }

    /** v2.0.24：三档循环 触摸板 → 直接点按 → 多点触控 → 触摸板。原绝对指针没被删，随时可切回。 */
    private fun toggleInputMode() {
        inputMode = when (inputMode) {
            MODE_TOUCHPAD -> MODE_ABSOLUTE
            MODE_ABSOLUTE -> MODE_MULTITOUCH
            else -> MODE_TOUCHPAD
        }
        // 切回触摸板时重新与 guest 光标对齐（期间 guest 光标可能被别的输入移动过）。
        if (inputMode == MODE_TOUCHPAD) tpInit = false
        saveInputMode()
        modeToggle?.text = modeLabel()
        updateCursorOverlay()   // v2.0.25：切出触摸板时隐藏箭头，切回时恢复
        Toast.makeText(
            this,
            when (inputMode) {
                MODE_TOUCHPAD -> "触摸板：单指滑动移光标 / 轻点单击 / 长按拖动 / 双指滚动 / 双指轻点右键；长按本按钮调光标速度"
                MODE_ABSOLUTE -> "直接点按：按哪到哪（原来的鼠标模式）"
                else -> "多点触控：iOS/触控系统，手势可用"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    /** v2.0.24：长按「模式」按钮循环触摸板光标速度（慢 0.7 / 中 1.0 / 快 1.6），不改变模式。 */
    private fun cycleTpGain() {
        tpGainIdx = (tpGainIdx + 1) % TP_GAINS.size
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putInt(SP_TP_GAIN, tpGainIdx).apply()
        }
        val names = arrayOf(Lang.t("慢"), Lang.t("中"), Lang.t("快"))
        Toast.makeText(
            this, "触摸板光标速度：${names[tpGainIdx]}（×${TP_GAINS[tpGainIdx]}）", Toast.LENGTH_SHORT
        ).show()
    }

    private fun loadInputMode() {
        runCatching {
            val sp = getSharedPreferences(SP_NAME, MODE_PRIVATE)
            inputMode = sp.getInt(SP_MODE, MODE_TOUCHPAD).coerceIn(0, 2)
            tpGainIdx = sp.getInt(SP_TP_GAIN, 1).coerceIn(0, TP_GAINS.size - 1)
            // v2.0.27：按盘读取，默认关。旧版全局键 tp_cursor 不再读取 —— 升级后一律
            // 回到「不画」，避免 Windows 桌面又冒出第二个箭头。
            cursorKey = cursorProfileKey()
            cursorVisible = sp.getBoolean(cursorKey, false)
            cursorSizeIdx = sp.getInt(SP_TP_CURSOR_SIZE, CURSOR_SIZE_DEFAULT)
                .coerceIn(0, CURSOR_SCALES.size - 1)
        }
    }

    private fun saveInputMode() {
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit().putInt(SP_MODE, inputMode).apply()
        }
    }

    /** v2.0.22：默认上锁（纯软件锁），回前台补一次手势排除区，无系统弹窗。 */
    override fun onResume() {
        super.onResume()
        ui.postDelayed({
            if (!isFinishing && !isDestroyed) syncSoftLock()
        }, 700)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 此时 DecorView 一定已 attached，重新压一次沉浸式
        // （从后台回来、或用户下滑唤出系统栏后恢复全屏）
        if (hasFocus) {
            runCatching { applyImmersive() }
            hideIme()
            // 从后台回来后系统可能重置手势排除区，锁着就补一次（纯软件锁，无弹窗）。
            syncSoftLock()
        }
    }

    override fun onDestroy() {
        alive = false
        hideKeyPreview()
        stopRepeatDelete()
        lockToast?.cancel()
        if (displayLocked && ::rootFrame.isInitialized &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            runCatching { rootFrame.setSystemGestureExclusionRects(emptyList<Rect>()) }
        }
        runCatching { ball.animate().cancel() }
        ui.removeCallbacksAndMessages(null)
        // surfaceDestroyed 已处理 saveFrame / removeSurface，这里不重复拆 display。
        NativeInputBridge.stop()
        if (bound) runCatching { RootService.unbind(rootConn) }
        super.onDestroy()
    }
}
