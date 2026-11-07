package com.mcai.ubuntudsu.core

import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 平板模式：把 APP 里的触摸事件直接送进 guest 的 virtio-input 多点触摸屏。
 *
 * 完整链路：
 *   VncActivity 的 InputView
 *     → 本类（编码成 virtio_input_event）
 *     → 127.0.0.1:7788（TCP）
 *     → touchd（native 常驻进程）
 *     → /data/data/<pkg>/files/gunyahvm/touch.sock（unix socket）
 *     → crosvm `--input multi-touch[path=...]`
 *     → guest 的触摸屏设备（INPUT_PROP_DIRECT）
 *
 * 为什么中间要有一个 touchd：
 *   crosvm 是 unix socket 的 **connect** 方，且只认文件系统路径；
 *   而 Android 的 Java 层没有能 bind 文件路径 socket 的公开 API。
 *   所以让一个几十行的 C 进程去 bind，APP 用普通 TCP 连它。
 *
 * 协议：写入的都是 virtio_input_event，8 字节小端：u16 type, u16 code, u32 value。
 * 见 crosvm devices/src/virtio/input/event_source.rs 的 SocketEventSource。
 *
 * 接管时机（重要）：
 *   touchd 在「crosvm 连上 unix socket」且「APP 连上 TCP」之后，才会回一个 0x01。
 *   只有收到这个字节，[active] 才为 true，[InputHandler] 才敢把所有手指事件吞下来。
 *   否则 crosvm 没建设备（guest 无 virtio-input 驱动是最常见的原因）时，
 *   APP 会既吞掉事件又没人接收 —— 表现就是「屏幕上点了完全没反应」。
 *   现在这种情况会自动回退给 VNC 的鼠标处理，至少不会把手势一起弄死。
 */
object TouchBridge {

    private const val TAG = "TouchBridge"
    private const val TCP_PORT = 7788
    private const val RETRY_MS = 3000L
    private const val HELLO_TIMEOUT_MS = 3000

    // linux/input-event-codes.h
    private const val EV_SYN = 0x00
    private const val EV_KEY = 0x01
    private const val EV_ABS = 0x03
    private const val SYN_REPORT = 0x00
    private const val BTN_TOUCH = 0x14a
    private const val ABS_MT_SLOT = 0x2f
    private const val ABS_MT_POSITION_X = 0x35
    private const val ABS_MT_POSITION_Y = 0x36
    private const val ABS_MT_TRACKING_ID = 0x39

    /** true 表示触摸直通链路已真正建立（crosvm 那一端确认过）：此时 VNC 指针事件必须全部丢弃 */
    @Volatile
    var active: Boolean = false
        private set

    /** true 表示用户打开了平板模式（不管链路是否已建立） */
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var connecting = false

    private var sock: Socket? = null
    private var out: OutputStream? = null
    private var lastConnectTry = 0L
    private var lastHelloState = false

    private var fbW = 720
    private var fbH = 1584

    /** Android pointerId → MT slot */
    private val slotOf = HashMap<Int, Int>()
    private var nextTrackingId = 1

    // 一帧最多 10 指 × 3 个事件 = 30 条；留一倍余量
    private val buf: ByteBuffer = ByteBuffer.allocate(8 * 64).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * 进入平板模式。socket 由 touchd 负责创建，这里只记参数。
     * 真正的连接与握手推迟到第一次 [feed]，避免 VM 还没起来就空转。
     */
    fun start(sockPath: String, width: Int, height: Int): Boolean {
        stop()
        fbW = width.coerceAtLeast(2)
        fbH = height.coerceAtLeast(2)
        lastConnectTry = 0L
        enabled = true
        return true
    }

    fun stop() {
        enabled = false
        active = false
        connecting = false
        runCatching { out?.close() }
        runCatching { sock?.close() }
        out = null
        sock = null
        lastConnectTry = 0L
        lastHelloState = false
        synchronized(this) {
            slotOf.clear()
            nextTrackingId = 1
        }
    }

    /**
     * 异步建链。UI 线程调用，绝不阻塞：connect 100ms 超时，等 hello 3s 超时。
     * 任何一步失败都只是「本次不接管」，下一轮 feed 会再试。
     */
    private fun ensureConnected() {
        if (sock != null || connecting) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastConnectTry < RETRY_MS) return
        lastConnectTry = now
        connecting = true
        Thread({
            var s: Socket? = null
            try {
                s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), TCP_PORT), 100)
                s.soTimeout = HELLO_TIMEOUT_MS
                val hello = s.getInputStream().read()
                if (hello != 1) {
                    if (!lastHelloState) {
                        Log.w(TAG, "touchd 未确认 crosvm 端，平板模式暂不接管")
                        lastHelloState = true
                    }
                    runCatching { s.close() }
                    return@Thread
                }
                synchronized(this@TouchBridge) {
                    sock = s
                    out = s.getOutputStream()
                }
                active = true
                lastHelloState = false
                Log.i(TAG, "触摸直通已就绪（crosvm 已连接）")
            } catch (t: Throwable) {
                if (!lastHelloState) {
                    Log.w(TAG, "触摸直通未建立: ${t.message}")
                    lastHelloState = true
                }
                runCatching { s?.close() }
            } finally {
                connecting = false
            }
        }, "touch-connect").apply { isDaemon = true }.start()
    }

    /**
     * 转发一次触摸事件。
     *
     * @return true 表示事件已被触摸通道处理（调用方应吞掉，不再发给 VNC）；
     *         false 表示链路还没建立，调用方必须回退到 VNC 的鼠标处理
     */
    fun feed(e: MotionEvent, map: (Float, Float) -> PointF?): Boolean {
        if (!enabled) return false
        ensureConnected()
        if (!active) return false
        val os = synchronized(this) { out } ?: return false
        synchronized(this) {
            buf.clear()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = e.actionIndex
                    // 点在画面外：这次触摸不属于 guest，直接吞掉，别退回 VNC 造成半截事件
                    val p = map(e.getX(idx), e.getY(idx)) ?: return true
                    if (slotOf.isEmpty()) put(EV_KEY, BTN_TOUCH, 1)
                    val slot = allocSlot(e.getPointerId(idx))
                    put(EV_ABS, ABS_MT_SLOT, slot)
                    put(EV_ABS, ABS_MT_TRACKING_ID, nextTrackingId++)
                    putPos(p)
                    put(EV_SYN, SYN_REPORT, 0)
                }

                MotionEvent.ACTION_MOVE -> {
                    var any = false
                    for (i in 0 until e.pointerCount) {
                        val slot = slotOf[e.getPointerId(i)] ?: continue
                        val p = map(e.getX(i), e.getY(i)) ?: continue
                        put(EV_ABS, ABS_MT_SLOT, slot)
                        putPos(p)
                        any = true
                    }
                    if (any) put(EV_SYN, SYN_REPORT, 0)
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val idx = e.actionIndex
                    val slot = slotOf.remove(e.getPointerId(idx)) ?: return true
                    put(EV_ABS, ABS_MT_SLOT, slot)
                    put(EV_ABS, ABS_MT_TRACKING_ID, -1)
                    put(EV_SYN, SYN_REPORT, 0)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    for (slot in slotOf.values) {
                        put(EV_ABS, ABS_MT_SLOT, slot)
                        put(EV_ABS, ABS_MT_TRACKING_ID, -1)
                    }
                    slotOf.clear()
                    put(EV_KEY, BTN_TOUCH, 0)
                    put(EV_SYN, SYN_REPORT, 0)
                }

                else -> return false
            }
            val n = buf.position()
            if (n > 0) {
                try {
                    os.write(buf.array(), 0, n)
                    os.flush()
                } catch (t: Throwable) {
                    // 对端断了：丢弃连接，下次 feed 会重连，并把事件交回 VNC
                    Log.w(TAG, "写入触摸通道失败: ${t.message}")
                    runCatching { sock?.close() }
                    out = null
                    sock = null
                    active = false
                    return false
                }
            }
        }
        return true
    }

    private fun allocSlot(id: Int): Int {
        slotOf[id]?.let { return it }
        var s = 0
        while (slotOf.containsValue(s)) s++
        slotOf[id] = s
        return s
    }

    private fun putPos(p: PointF) {
        put(EV_ABS, ABS_MT_POSITION_X, p.x.toInt().coerceIn(0, fbW - 1))
        put(EV_ABS, ABS_MT_POSITION_Y, p.y.toInt().coerceIn(0, fbH - 1))
    }

    private fun put(type: Int, code: Int, value: Int) {
        buf.putShort(type.toShort())
        buf.putShort(code.toShort())
        buf.putInt(value)
    }
}
