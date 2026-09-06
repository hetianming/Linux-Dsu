package com.mcai.ubuntudsu

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.EditText
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import com.mcai.ubuntudsu.vnc.KeyHandler
import com.mcai.ubuntudsu.vnc.KeySink

class VncViewerActivity : AppCompatActivity() {
    private enum class PointerMode { TOUCH, TRACKPAD }

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var keyboardInput: EditText
    private lateinit var cursorOverlay: CursorOverlay
    private var protocol: VncProtocol? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var previousInputText = ""
    private var suppressInputChanges = false
    private var pointerMode = PointerMode.TOUCH
    private var pointerDown = false
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var remotePointerX = 0
    private var remotePointerY = 0
    private var trackpadPointerInitialized = false
    private var trackpadRemainderX = 0f
    private var trackpadRemainderY = 0f
    private var downAt = 0L
    private val keyHandler = KeyHandler(object : KeySink {
        override fun sendKeySym(keySym: Int, isDown: Boolean): Boolean {
            return protocol?.sendKeySym(keySym, isDown) == true
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        keyboardInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            setCursorVisible(false)
            alpha = 0.05f
            isSingleLine = false
            isFocusableInTouchMode = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            showSoftInputOnFocus = true
        }
        cursorOverlay = CursorOverlay(this)
        cursorOverlay.visibility = View.GONE
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xcc202124.toInt())
            visibility = View.GONE
        }
        val edgeHandle = TextView(this).apply {
            text = "‹"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xaa202124.toInt())
            setOnClickListener { controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        fun control(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
            setOnClickListener { action() }
        }
        controls.addView(control("键盘") { showVncKeyboard() })
        controls.addView(control("触屏") { pointerMode = PointerMode.TOUCH; controls.visibility = View.GONE })
        controls.addView(control("触控板") {
            pointerMode = PointerMode.TRACKPAD
            trackpadPointerInitialized = false
            trackpadRemainderX = 0f
            trackpadRemainderY = 0f
            controls.visibility = View.GONE
        })
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xaa000000.toInt())
            setPadding(24, 14, 24, 14)
            text = "正在连接 VNC..."
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(cursorOverlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(keyboardInput, FrameLayout.LayoutParams(8, 8, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = 8
            leftMargin = 8
        })
        root.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START))
        root.addView(edgeHandle, FrameLayout.LayoutParams(28, 72, Gravity.CENTER_VERTICAL or Gravity.START))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)

        val gesture = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                image.scaleX = (image.scaleX * detector.scaleFactor).coerceIn(0.5f, 4f)
                image.scaleY = image.scaleX
                return true
            }
        })
        var downX = 0f
        var downY = 0f
        image.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastPointerX = event.x
                    lastPointerY = event.y
                    downAt = android.os.SystemClock.uptimeMillis()
                    pointerDown = true
                    val point = screenToFrame(event.x, event.y)
                    if (pointerMode == PointerMode.TOUCH) {
                        point?.let {
                            remotePointerX = it.first
                            remotePointerY = it.second
                            protocol?.sendPointer(it.first, it.second, 1)
                        }
                    } else {
                        point?.let {
                            if (!trackpadPointerInitialized) {
                                remotePointerX = protocol?.frameWidth()?.div(2) ?: it.first
                                remotePointerY = protocol?.frameHeight()?.div(2) ?: it.second
                                trackpadPointerInitialized = true
                                trackpadRemainderX = 0f
                                trackpadRemainderY = 0f
                            }
                            updateCursorOverlay()
                            protocol?.sendPointer(remotePointerX, remotePointerY, 0)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> if (!gesture.isInProgress && event.pointerCount == 1) {
                    val point = screenToFrame(event.x, event.y)
                    if (pointerMode == PointerMode.TOUCH) {
                        point?.let { protocol?.sendPointer(it.first, it.second, if (pointerDown) 1 else 0) }
                    } else {
                        if (point != null) {
                            val maxX = protocol?.frameWidth()?.takeIf { it > 0 }?.minus(1) ?: Int.MAX_VALUE
                            val maxY = protocol?.frameHeight()?.takeIf { it > 0 }?.minus(1) ?: Int.MAX_VALUE
                            val drawable = image.drawable
                            val fitScale = if (drawable != null && image.width > 0 && image.height > 0) {
                                minOf(image.width.toFloat() / drawable.intrinsicWidth, image.height.toFloat() / drawable.intrinsicHeight)
                            } else 1f
                            val sensitivity = 2f / (fitScale * image.scaleX.coerceAtLeast(0.01f))
                            trackpadRemainderX += (event.x - lastPointerX) * sensitivity
                            trackpadRemainderY += (event.y - lastPointerY) * sensitivity
                            remotePointerX = (remotePointerX + trackpadRemainderX.toInt()).coerceIn(0, maxX)
                            remotePointerY = (remotePointerY + trackpadRemainderY.toInt()).coerceIn(0, maxY)
                            trackpadRemainderX -= trackpadRemainderX.toInt()
                            trackpadRemainderY -= trackpadRemainderY.toInt()
                            updateCursorOverlay()
                            protocol?.sendPointer(remotePointerX, remotePointerY, 0)
                        }
                    }
                    lastPointerX = event.x
                    lastPointerY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val point = screenToFrame(event.x, event.y)
                    if (pointerMode == PointerMode.TOUCH) {
                        point?.let { protocol?.sendPointer(it.first, it.second, 0) }
                    } else if (point != null) {
                        val rightButton = android.os.SystemClock.uptimeMillis() - downAt >= 500
                        protocol?.sendPointer(remotePointerX, remotePointerY, if (rightButton) 4 else 1)
                        protocol?.sendPointer(remotePointerX, remotePointerY, 0)
                    }
                    pointerDown = false
                }
            }
            true
        }
        image.setOnKeyListener { _, keyCode, event ->
            keyHandler.onKeyEvent(event)
        }
        keyboardInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                previousInputText = s?.toString().orEmpty()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s == null || suppressInputChanges) return
                val currentText = s.toString()
                val commonLength = minOf(previousInputText.length, currentText.length)
                var prefixLength = 0
                while (prefixLength < commonLength && previousInputText[prefixLength] == currentText[prefixLength]) {
                    prefixLength++
                }
                repeat(previousInputText.length - prefixLength) {
                    keyHandler.onKeyEvent(android.view.KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    keyHandler.onKeyEvent(android.view.KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                }
                if (currentText.length > prefixLength) protocol?.sendText(currentText.substring(prefixLength))
                if (currentText.isNotEmpty()) {
                    suppressInputChanges = true
                    keyboardInput.setText("")
                    suppressInputChanges = false
                }
            }
        })
        keyboardInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId != 0 || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                keyHandler.onKeyEvent(android.view.KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                keyHandler.onKeyEvent(android.view.KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                true
            } else false
        }
        keyboardInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DEL,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_TAB,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    keyHandler.onKeyEvent(event)
                    keyHandler.onKeyEvent(android.view.KeyEvent(KeyEvent.ACTION_UP, keyCode))
                    true
                }
                else -> false
            }
        }
        showPasswordDialog()
    }

    private fun showVncKeyboard() {
        keyboardInput.requestFocus()
        keyboardInput.post {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.showSoftInput(keyboardInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun screenToFrame(x: Float, y: Float): Pair<Int, Int>? {
        val bitmap = image.drawable ?: return null
        val sourceWidth = bitmap.intrinsicWidth
        val sourceHeight = bitmap.intrinsicHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val fitScale = minOf(image.width.toFloat() / sourceWidth, image.height.toFloat() / sourceHeight)
        val displayedScale = fitScale * image.scaleX.coerceAtLeast(0.01f)
        val left = (image.width - sourceWidth * displayedScale) / 2f
        val top = (image.height - sourceHeight * displayedScale) / 2f
        return (((x - left) / displayedScale).toInt().coerceIn(0, sourceWidth - 1) to
            ((y - top) / displayedScale).toInt().coerceIn(0, sourceHeight - 1))
    }

    private fun updateCursorOverlay() {
        if (!trackpadPointerInitialized) return
        val drawable = image.drawable ?: return
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0 || image.width <= 0 || image.height <= 0) return
        val fitScale = minOf(image.width.toFloat() / drawable.intrinsicWidth, image.height.toFloat() / drawable.intrinsicHeight)
        val displayedScale = fitScale * image.scaleX.coerceAtLeast(0.01f)
        val left = (image.width - drawable.intrinsicWidth * displayedScale) / 2f
        val top = (image.height - drawable.intrinsicHeight * displayedScale) / 2f
        cursorOverlay.setCursor(
            left + remotePointerX * displayedScale + dp(18f),
            top + remotePointerY * displayedScale + dp(18f),
        )
        cursorOverlay.visibility = View.VISIBLE
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private class CursorOverlay(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cursorX = 0f
        private var cursorY = 0f

        init {
            isClickable = false
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
        }

        fun setCursor(x: Float, y: Float) {
            cursorX = x
            cursorY = y
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = 22f * resources.displayMetrics.density
            val outline = Path().apply {
                moveTo(cursorX, cursorY)
                lineTo(cursorX, cursorY + size)
                lineTo(cursorX + size * 0.3f, cursorY + size * 0.72f)
                lineTo(cursorX + size * 0.58f, cursorY + size * 1.08f)
                lineTo(cursorX + size * 0.78f, cursorY + size * 0.92f)
                lineTo(cursorX + size * 0.5f, cursorY + size * 0.6f)
                lineTo(cursorX + size * 0.92f, cursorY + size * 0.6f)
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            canvas.drawPath(outline, paint)
            val inner = Path().apply {
                moveTo(cursorX + 2f, cursorY + 3f)
                lineTo(cursorX + 2f, cursorY + size - 5f)
                lineTo(cursorX + size * 0.33f, cursorY + size * 0.75f)
                lineTo(cursorX + size * 0.58f, cursorY + size * 0.98f)
                lineTo(cursorX + size * 0.68f, cursorY + size * 0.88f)
                lineTo(cursorX + size * 0.43f, cursorY + size * 0.58f)
                lineTo(cursorX + size * 0.82f, cursorY + size * 0.58f)
                close()
            }
            paint.color = Color.WHITE
            canvas.drawPath(inner, paint)
            /* Keep a small center marker visible against bright and dark desktops. */
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.BLACK
            canvas.drawCircle(cursorX + size * 0.25f, cursorY + size * 0.25f, 3f, paint)
        }
    }

    private fun showPasswordDialog() {
        val passwordInput = EditText(this).apply {
            hint = "VNC 密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(intent.getStringExtra(EXTRA_PASSWORD).orEmpty())
            setSelection(length())
        }
        AlertDialog.Builder(this)
            .setTitle("连接 VNC 桌面")
            .setMessage("请输入 VNC 服务密码")
            .setView(passwordInput)
            .setNegativeButton("取消") { _, _ -> finish() }
            .setPositiveButton("确定") { _, _ -> connect(passwordInput.text.toString()) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun connect(password: String) {
        val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 5901)
        status.text = "正在连接 $host:$port..."
        status.visibility = View.VISIBLE
        protocol = VncProtocol(
            host = host,
            port = port,
            password = password,
            onStatus = { text -> runOnUiThread { status.text = text; status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE } },
            onFrame = { bitmap -> runOnUiThread { image.setImageBitmap(bitmap) } },
        )
        executor.execute { protocol?.connect() }
    }

    override fun onDestroy() { protocol?.close(); executor.shutdownNow(); super.onDestroy() }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PASSWORD = "password"
    }
}

private class VncProtocol(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val onStatus: (String) -> Unit,
    private val onFrame: (Bitmap) -> Unit,
) {
    private val pointerExecutor = Executors.newSingleThreadExecutor()
    private val pointerLock = Any()
    private var pendingMove: IntArray? = null
    private var moveWriteScheduled = false
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var width = 0
    private var height = 0
    private var bitsPerPixel = 32
    private var bigEndian = false
    private var redMax = 255
    private var greenMax = 255
    private var blueMax = 255
    private var redShift = 16
    private var greenShift = 8
    private var blueShift = 0

    fun connect() {
        try {
            onStatus("正在连接 $host:$port...")
            socket = Socket().also { it.connect(InetSocketAddress(host, port), 8000); it.tcpNoDelay = true }
            input = DataInputStream(BufferedInputStream(socket!!.getInputStream()))
            output = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream()))
            val serverVersion = readString(12)
            output!!.write("RFB 003.008\n".toByteArray()); output!!.flush()
            onStatus("已连接，正在协商认证...")
            val securityCount = input!!.readUnsignedByte()
            val security = ByteArray(securityCount); input!!.readFully(security)
            if (security.contains(2.toByte())) {
                output!!.writeByte(2); output!!.flush()
                val challenge = ByteArray(16); input!!.readFully(challenge)
                val key = ByteArray(8); password.toByteArray().copyInto(key, endIndex = minOf(8, password.length))
                key.indices.forEach { i -> key[i] = reverseBits(key[i]) }
                val cipher = Cipher.getInstance("DES/ECB/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
                output!!.write(cipher.doFinal(challenge)); output!!.flush()
                if (input!!.readInt() != 0) throw IllegalStateException("VNC 密码认证失败")
            } else if (security.contains(1.toByte())) {
                output!!.writeByte(1); output!!.flush()
                if (input!!.readInt() != 0) throw IllegalStateException("VNC 无密码连接被拒绝")
            } else throw IllegalStateException("VNC 服务端不支持认证方式")
            output!!.writeByte(1); output!!.flush()
            input!!.readUnsignedShort().also { width = it }; input!!.readUnsignedShort().also { height = it }
            bitsPerPixel = input!!.readUnsignedByte()
            input!!.readUnsignedByte()
            bigEndian = input!!.readUnsignedByte() != 0
            input!!.readUnsignedByte()
            redMax = input!!.readUnsignedShort()
            greenMax = input!!.readUnsignedShort()
            blueMax = input!!.readUnsignedShort()
            redShift = input!!.readUnsignedByte()
            greenShift = input!!.readUnsignedByte()
            blueShift = input!!.readUnsignedByte()
            input!!.readFully(ByteArray(3))
            val nameLength = input!!.readInt()
            if (nameLength > 0) input!!.readFully(ByteArray(nameLength))
            // Request a predictable 32-bit BGRX format before reading framebuffer data.
            output!!.writeByte(0)
            output!!.writeByte(0); output!!.writeByte(0); output!!.writeByte(0)
            output!!.writeByte(32); output!!.writeByte(24); output!!.writeByte(0); output!!.writeByte(1)
            output!!.writeShort(255); output!!.writeShort(255); output!!.writeShort(255)
            output!!.writeByte(16); output!!.writeByte(8); output!!.writeByte(0)
            output!!.writeByte(0); output!!.writeByte(0); output!!.writeByte(0)
            output!!.flush()
            bitsPerPixel = 32
            bigEndian = false
            redMax = 255; greenMax = 255; blueMax = 255
            redShift = 16; greenShift = 8; blueShift = 0
            // Request Raw only so the lightweight decoder never receives Tight/ZRLE data.
            output!!.writeByte(2); output!!.writeByte(0); output!!.writeShort(2)
            output!!.writeInt(0)
            output!!.writeInt(-224)
            output!!.flush()
            output!!.writeByte(3); output!!.writeByte(0); output!!.writeShort(0); output!!.writeShort(0); output!!.writeShort(width); output!!.writeShort(height); output!!.flush()
            onStatus("已连接，等待桌面画面...")
            while (socket?.isClosed == false) readUpdate()
        } catch (e: Exception) { onStatus(e.message ?: "VNC 连接失败") }
    }

    private fun readUpdate() {
        when (input!!.readUnsignedByte()) {
            0 -> {
                input!!.readUnsignedByte(); val count = input!!.readUnsignedShort()
                repeat(count) { if (!readRect()) return }
            }
            1 -> { input!!.readUnsignedByte(); val first = input!!.readUnsignedShort(); repeat(first) { input!!.readUnsignedShort(); input!!.readUnsignedShort(); input!!.readUnsignedShort() } }
            2 -> Unit
            3 -> { input!!.readFully(ByteArray(3)); val length = input!!.readInt(); if (length > 0) input!!.readFully(ByteArray(length)) }
            else -> throw IllegalStateException("VNC 协议错误")
        }
        output!!.writeByte(3); output!!.writeByte(0); output!!.writeShort(0); output!!.writeShort(0)
        output!!.writeShort(width); output!!.writeShort(height); output!!.flush()
    }

    private fun readRect(): Boolean {
        val x = input!!.readUnsignedShort(); val y = input!!.readUnsignedShort(); val w = input!!.readUnsignedShort(); val h = input!!.readUnsignedShort(); val encoding = input!!.readInt()
        when (encoding) {
            0 -> {
                val pixels = IntArray(w * h)
                repeat(pixels.size) { pixels[it] = readPixel() }
                val bitmap = frameBuffer ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frameBuffer = it }
                Canvas(bitmap).drawBitmap(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888), x.toFloat(), y.toFloat(), null)
                onFrame(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            }
            1 -> {
                val sourceX = input!!.readUnsignedShort(); val sourceY = input!!.readUnsignedShort()
                frameBuffer?.let { bitmap ->
                    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    Canvas(bitmap).drawBitmap(
                        copy,
                        Rect(sourceX, sourceY, sourceX + w, sourceY + h),
                        Rect(x, y, x + w, y + h),
                        null,
                    )
                    onFrame(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                }
            }
            -239 -> {
                input!!.readFully(ByteArray(w * h * (bitsPerPixel / 8)))
                input!!.readFully(ByteArray(((w + 7) / 8) * h))
            }
            -240 -> {
                input!!.readFully(ByteArray(6))
                input!!.readFully(ByteArray(((w + 7) / 8) * h * 2))
            }
            -232 -> Unit
            -224 -> return false
            -223 -> {
                width = w; height = h
                frameBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            else -> throw IllegalStateException("VNC 服务端返回未支持的编码: $encoding")
        }
        return true
    }

    private fun readPixel(): Int {
        val bytesPerPixel = (bitsPerPixel + 7) / 8
        var value = 0
        if (bigEndian) {
            repeat(bytesPerPixel) { value = (value shl 8) or input!!.readUnsignedByte() }
        } else {
            repeat(bytesPerPixel) { value = value or (input!!.readUnsignedByte() shl (it * 8)) }
        }
        fun component(max: Int, shift: Int): Int = (((value shr shift) and max) * 255 / max.coerceAtLeast(1)).coerceIn(0, 255)
        return Color.rgb(component(redMax, redShift), component(greenMax, greenShift), component(blueMax, blueShift))
    }

    @Synchronized
    fun sendPointer(x: Int, y: Int, mask: Int) {
        val packet = intArrayOf(x, y, mask)
        if (mask == 0) {
            synchronized(pointerLock) {
                pendingMove = packet
                if (moveWriteScheduled) return
                moveWriteScheduled = true
            }
            pointerExecutor.execute { drainPointerMoves() }
        } else {
            pointerExecutor.execute { writePointer(packet, true) }
        }
    }

    private fun drainPointerMoves() {
        while (true) {
            val packet = synchronized(pointerLock) {
                val next = pendingMove
                pendingMove = null
                if (next == null) moveWriteScheduled = false
                next
            } ?: return
            writePointer(packet, true)
        }
    }

    @Synchronized
    private fun writePointer(packet: IntArray, flush: Boolean) {
        try {
            output?.let {
                it.writeByte(5)
                it.writeByte(packet[2])
                it.writeShort(packet[0].coerceIn(0, (width - 1).coerceAtLeast(0)))
                it.writeShort(packet[1].coerceIn(0, (height - 1).coerceAtLeast(0)))
                if (flush) it.flush()
            }
        } catch (_: Exception) { }
    }

    @Synchronized
    fun frameWidth(): Int = width

    @Synchronized
    fun frameHeight(): Int = height
    @Synchronized
    fun sendKey(keyCode: Int, down: Boolean) {
        keyHandlerForProtocol(keyCode, down)
    }

    private fun keyHandlerForProtocol(keyCode: Int, down: Boolean) {
        try { output?.let {
            val keysym = when (keyCode) {
                android.view.KeyEvent.KEYCODE_ENTER -> 0xff0d
                android.view.KeyEvent.KEYCODE_BACK -> 0xff1b
                android.view.KeyEvent.KEYCODE_DEL -> 0xff08
                android.view.KeyEvent.KEYCODE_TAB -> 0xff09
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
                android.view.KeyEvent.KEYCODE_DPAD_UP -> 0xff52
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
                else -> 0
            }
            if (keysym != 0) { it.writeByte(4); it.writeByte(if (down) 1 else 0); it.writeShort(0); it.writeInt(keysym); it.flush() }
        } } catch (_: Exception) { }
    }
    @Synchronized
    fun sendKeySym(keySym: Int, down: Boolean): Boolean {
        return try {
            val stream = output ?: return false
            stream.writeByte(4)
            stream.writeByte(if (down) 1 else 0)
            stream.writeShort(0)
            stream.writeInt(keySym)
            stream.flush()
            true
        } catch (_: Exception) {
            false
        }
    }
    @Synchronized
    fun sendText(text: String) {
        try {
            text.codePoints().forEach { codePoint ->
                sendKeySym(if (codePoint < 0x100) codePoint else codePoint + 0x01000000, true)
                sendKeySym(if (codePoint < 0x100) codePoint else codePoint + 0x01000000, false)
            }
        } catch (_: Exception) { }
    }
    fun close() { pointerExecutor.shutdownNow(); socket?.close() }
    private fun readString(length: Int): String { val bytes = ByteArray(length); input!!.readFully(bytes); return String(bytes) }
    private fun reverseBits(value: Byte): Byte { var v = value.toInt() and 255; var result = 0; repeat(8) { result = (result shl 1) or (v and 1); v = v shr 1 }; return result.toByte() }
    private var frameBuffer: Bitmap? = null
}
