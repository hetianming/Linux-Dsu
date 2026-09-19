/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.vnc

import android.opengl.GLES20
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Pure-Kotlin implementation of the [VncClient] public API.
 *
 * The upstream AVNC implementation delegates the RFB protocol to a native
 * library. This build keeps the same API surface, but talks the RFB protocol
 * directly so that no native code is required.
 *
 * Supported protocol features:
 *  - RFB 003.008 handshake with VNC authentication (DES challenge) and None
 *  - Pixel format fixed to 32bpp BGRX (little endian)
  *  - Encodings: Raw, CopyRect, LastRect, NewFBSize, RichCursor pseudo-encoding
 *  - Client messages: SetPixelFormat, SetEncodings, FBUpdateRequest,
 *    KeyEvent, PointerEvent, ClientCutText, SetDesktopSize
 *
 * Framebuffer pixels are stored as raw BGRX bytes and uploaded directly to
 * the current OpenGL texture.
 */
class VncClient(private val observer: Observer) {

    /**
     * Interface for event observer.
     */
    interface Observer {
        fun getVncPassword(): String
        fun getVncCredentials(): UserCredential
        fun verifyVncServerCertificate(certificate: X509Certificate): Boolean
        fun onCutTextReceived(text: String)
        fun onFramebufferUpdated()
        fun onFramebufferSizeChanged(width: Int, height: Int)
        fun onPointerMoved(x: Int, y: Int)
        fun onBell()
    }

    @Volatile
    var connected = false
        private set

    private var destroyed = false

    /**
     * Lock protecting access to [connected] & [destroyed] state.
     */
    private val stateLock = ReentrantReadWriteLock()

    /**
     * If true, all input to remote server is disabled
     */
    private val inputDisabled = AtomicBoolean(false)

    /**
     * If true, client stops sending framebuffer update requests to server
     */
    val frameBufferUpdatesPaused = AtomicBoolean(false)

    /**
     * Latest pointer position. See [moveClientPointer].
     */
    @Volatile
    var pointerX = 0; private set
    @Volatile
    var pointerY = 0; private set

    /**
     * Cursor info, used by Renderer
     */
    @Volatile
    var cursorInfo = CursorInfo(0, 0, 0, 0)

    /**
     * Client-side cursor rendering creates a synchronization issue.
     * Suppose if pointer is moved to (50,10) by client. A PointerEvent is sent
     * to the server and cursor is immediately rendered on (50,10).
     * Some servers (e.g. Vino) will send back a PointerPosition event for (50, 10).
     * But, by the time that event is received from server, pointer on client
     * might have already moved to (60,20) (this is almost guaranteed to happen
     * with touchpad/relative action mode). So the cursor will probably 'jump back'
     * depending on the order of these events.
     *
     * This flags works around the issue by temporarily ignoring serer-side updates.
     */
    @Volatile
    var ignorePointerMovesByServer = false

    /**
     * Value of the most recent cut text sent/received from server
     */
    @Volatile
    private var lastCutText: String? = null

    //Networking
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    //Framebuffer state
    private val fbLock = Any()
    private var fbWidth = 0
    private var fbHeight = 0
    private var fbBytes = ByteArray(0)
    private var lastError = ""

    //Cursor state
    private val cursorLock = Any()
    private var cursorBytes = ByteArray(0)
    private var cursorMask = ByteArray(0)

    //OpenGL upload state, only touched from the renderer thread
    private var uploadBuffer: ByteBuffer? = null
    private var uploadTextureWidth = 0
    private var uploadTextureHeight = 0
    private var cursorUploadBuffer: ByteBuffer? = null

    /**
     * Setup different properties for this client.
     *
     * @param securityType RFB security type to use.
     */
    fun configure(securityType: Int, useLocalCursor: Boolean, imageQuality: Int, useRawEncoding: Boolean) {
        configuredSecurityType = securityType
        configuredUseRawEncoding = useRawEncoding
    }

    fun setupRepeater(serverId: Int) {
        // Not supported in the Kotlin implementation
    }

    /**
     * Initializes VNC connection.
     */
    fun connect(host: String, port: Int) {
        stateLock.read {
            check(!connected) { "Already connected" }
            check(!destroyed) { "Client has been destroyed" }
        }
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 8000)
            s.tcpNoDelay = true
            socket = s
            val inStream = DataInputStream(s.getInputStream().buffered())
            val outStream = DataOutputStream(s.getOutputStream().buffered())
            input = inStream
            output = outStream
            handshake(inStream, outStream)
            stateLock.write {
                if (!destroyed)
                    connected = true
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            closeQuietly()
            throw IOException(lastError, e)
        }
    }

    /**
     * Waits for incoming server message, parses it and then invokes appropriate callbacks.
     */
    fun processServerMessage() {
        var handled = false
        stateLock.read {
            if (connected) handled = readServerMessage()
        }
        if (handled)
            return

        stateLock.write {
            connected = false
        }
        throw IOException(lastError.ifEmpty { "Connection closed" })
    }

    /**
     * Name of remote desktop
     */
    fun getDesktopName(): String {
        ifConnected {
            return desktopName
        }
        return ""
    }

    /**
     * Sends Key event to remote server.
     *
     * @param keySym    Key symbol
     * @param xtCode    Key code from [XTKeyCode]
     * @param isDown    true for key down, false for key up
     */
    fun sendKeyEvent(keySym: Int, xtCode: Int, isDown: Boolean) = ifConnectedAndInteractive {
        try {
            val out = output ?: return@ifConnectedAndInteractive
            synchronized(writeLock) {
                out.writeByte(4)
                out.writeByte(if (isDown) 1 else 0)
                out.writeShort(0)
                out.writeInt(keySym)
                out.flush()
            }
        } catch (e: Exception) {
            lastError = e.message ?: "Send failed"
        }
    }

    /**
     * Sends pointer event to remote server.
     *
     * @param x    Horizontal pointer coordinate
     * @param y    Vertical pointer coordinate
     * @param mask Button mask to identify which button was pressed.
     */
    fun sendPointerEvent(x: Int, y: Int, mask: Int) = ifConnectedAndInteractive {
        try {
            val out = output ?: return@ifConnectedAndInteractive
            pointerX = x
            pointerY = y
            synchronized(writeLock) {
                out.writeByte(5)
                out.writeByte(mask and 0xff)
                out.writeShort(x.coerceIn(0, 65535))
                out.writeShort(y.coerceIn(0, 65535))
                out.flush()
            }
        } catch (e: Exception) {
            lastError = e.message ?: "Send failed"
        }
    }

    /**
     * Updates client-side pointer position.
     * No event is sent to server.
     *
     * Primary use-case is to update pointer position during gestures.
     * This way we can immediately render the cursor on new position without
     * waiting for Network IO.
     *
     * It also helps with servers which don't send pointer-position updates
     * if pointer was moved by the client.
     *
     * @param x    Horizontal pointer coordinate
     * @param y    Vertical pointer coordinate
     */
    fun moveClientPointer(x: Int, y: Int) = ifConnected {
        pointerX = x
        pointerY = y
        observer.onPointerMoved(x, y)
    }

    /**
     * Sends text to remote desktop's clipboard.
     */
    fun sendCutText(text: String) = ifConnectedAndInteractive {
        if (text != lastCutText) {
            try {
                val out = output ?: return@ifConnectedAndInteractive
                val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
                synchronized(writeLock) {
                    out.writeByte(6)
                    out.writeByte(0); out.writeByte(0); out.writeByte(0)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
                lastCutText = text
            } catch (e: Exception) {
                lastError = e.message ?: "Send failed"
            }
        }
    }

    /**
     * Set remote desktop size to given dimensions.
     * This needs server support to actually work.
     * Non-positive [width] & [height] are ignored.
     */
    fun setDesktopSize(width: Int, height: Int) = ifConnected {
        if (width > 0 && height > 0) {
            try {
                val out = output ?: return@ifConnected
                synchronized(writeLock) {
                    out.writeByte(251)
                    out.writeByte(0)
                    out.writeShort(width)
                    out.writeShort(height)
                    out.writeByte(1)
                    out.writeInt(0) //screen id
                    out.writeShort(0); out.writeShort(0) //x, y
                    out.writeShort(width); out.writeShort(height)
                    out.writeInt(0) //flags
                    out.flush()
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Send failed"
            }
        }
    }

    fun setInputDisabled(disabled: Boolean) {
        inputDisabled.set(disabled)
    }

    /**
     * Sends frame buffer update request to remote server.
     */
    fun refreshFrameBuffer() = ifConnected {
        if (!frameBufferUpdatesPaused.get())
            requestFramebufferUpdate(false)
    }

    /**
     * Change framebuffer update status.
     * If paused, client will effectively stop asking for framebuffer updates from server.
     * This will do network IO when resuming, so must not be called from Main thread.
     */
    fun setFrameBufferUpdatesPaused(pause: Boolean) {
        stateLock.read {
            if (destroyed || !frameBufferUpdatesPaused.compareAndSet(!pause, pause))
                return
            refreshFrameBuffer()
        }
    }

    /**
     * Puts framebuffer contents in currently active OpenGL texture.
     * Must be called from an OpenGL ES context (i.e. from renderer thread).
     */
    fun uploadFrameTexture() = ifConnected {
        val (w, h) = synchronized(fbLock) { fbWidth to fbHeight }
        if (w <= 0 || h <= 0) return@ifConnected
        val buffer = uploadBuffer
                ?.takeIf { uploadTextureWidth == w && uploadTextureHeight == h }
                ?: ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).also {
                    uploadBuffer = it
                    uploadTextureWidth = w
                    uploadTextureHeight = h
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, it)
                }
        synchronized(fbLock) {
            buffer.clear()
            if (fbBytes.size == w * h * 4)
                buffer.put(fbBytes)
            buffer
        }.let { b ->
            b.position(0)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                                   GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
        }
    }

    /**
     * Upload cursor contents in currently active OpenGL texture
     */
    fun uploadCursorTexture() = ifConnected {
        val ci = cursorInfo
        if (ci.width <= 0 || ci.height <= 0) return@ifConnected
        val buffer = cursorUploadBuffer
                ?.takeIf { it.capacity() == ci.width * ci.height * 4 }
                ?: ByteBuffer.allocateDirect(ci.width * ci.height * 4)
                        .order(ByteOrder.nativeOrder()).also { cursorUploadBuffer = it }
        synchronized(cursorLock) {
            if (cursorBytes.size != ci.width * ci.height * 4) return@ifConnected
            val maskRowStride = (ci.width + 7) / 8
            buffer.clear()
            var pixelIndex = 0
            for (row in 0 until ci.height) {
                for (col in 0 until ci.width) {
                    val maskByte = cursorMask[row * maskRowStride + (col / 8)].toInt()
                    val opaque = (maskByte and (0x80 shr (col % 8))) != 0
                    val b = cursorBytes[pixelIndex * 4].toInt() and 0xff
                    val g = cursorBytes[pixelIndex * 4 + 1].toInt() and 0xff
                    val r = cursorBytes[pixelIndex * 4 + 2].toInt() and 0xff
                    buffer.put(b.toByte())
                    buffer.put(g.toByte())
                    buffer.put(r.toByte())
                    buffer.put(if (opaque) 0xff.toByte() else 0)
                    pixelIndex++
                }
            }
            buffer
        }.let { b ->
            b.position(0)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, ci.width, ci.height, 0,
                                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
        }
    }

    /**
     * Release all resources allocated by the client.
     * DO NOT use this client after [cleanup].
     */
    fun cleanup() {
        stateLock.write {
            if (!destroyed) {
                connected = false
                destroyed = true
            }
        }
        closeQuietly()
    }

    /******************************************************************************************
     * Protocol internals
     ******************************************************************************************/

    private val writeLock = Any()
    private var desktopName = ""
    private var configuredSecurityType = 0
    private var configuredUseRawEncoding = false

    private fun handshake(input: DataInputStream, output: DataOutputStream) {
        //1. Version handshake
        val serverVersion = readString(input, 12)
        if (!serverVersion.startsWith("RFB "))
            throw IOException("服务器返回了无效的 RFB 版本")
        output.write("RFB 003.008\n".toByteArray())
        output.flush()

        //2. Security types
        val securityCount = input.readUnsignedByte()
        if (securityCount == 0) {
            readErrorString(input)
        }
        val securityTypes = ByteArray(securityCount) { input.readByte() }

        when {
            (configuredSecurityType == 0 || configuredSecurityType == 2) && securityTypes.contains(2.toByte()) -> {
                output.writeByte(2); output.flush()
                val challenge = ByteArray(16); input.readFully(challenge)
                val key = ByteArray(8); observer.getVncPassword().toByteArray(StandardCharsets.ISO_8859_1)
                        .copyInto(key, endIndex = minOf(8, observer.getVncPassword().length))
                key.indices.forEach { i -> key[i] = reverseBits(key[i]) }
                val cipher = Cipher.getInstance("DES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
                output.write(cipher.doFinal(challenge)); output.flush()
                if (input.readInt() != 0)
                    throw IOException("VNC 密码认证失败")
            }
            (configuredSecurityType == 0 || configuredSecurityType == 1) && securityTypes.contains(1.toByte()) -> {
                output.writeByte(1); output.flush()
                if (input.readInt() != 0)
                    throw IOException("VNC 连接被拒绝")
            }
            else -> throw IOException("VNC 服务端不支持请求的认证方式")
        }

        //3. ClientInit / ServerInit
        output.writeByte(1) //shared
        output.flush()
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        //ServerInit pixel format (16 bytes) is ignored, we force our own below
        input.readFully(ByteArray(16))
        val nameLength = input.readInt()
        desktopName = if (nameLength > 0)
            String(readFully(input, nameLength), StandardCharsets.ISO_8859_1)
        else ""

        synchronized(fbLock) {
            fbWidth = width
            fbHeight = height
            fbBytes = ByteArray(width * height * 4)
        }
        observer.onFramebufferSizeChanged(width, height)

        //4. Force 32bpp BGRX pixel format
        synchronized(writeLock) {
            output.writeByte(0)
            output.writeByte(0); output.writeByte(0); output.writeByte(0)
            output.writeByte(32) //bits per pixel
            output.writeByte(24) //depth
            output.writeByte(0) //big endian
            output.writeByte(1) //true colour
            output.writeShort(255) //red max
            output.writeShort(255) //green max
            output.writeShort(255) //blue max
            output.writeByte(16) //red shift
            output.writeByte(8) //green shift
            output.writeByte(0) //blue shift
            output.writeByte(0); output.writeByte(0); output.writeByte(0)
            output.flush()
        }

        // Prefer CopyRect before Raw for TigerVNC compatibility.
        val encodings = if (configuredUseRawEncoding) {
            intArrayOf(0, 1, -224, -223, -239)
        } else {
            intArrayOf(1, 0, -224, -223, -239)
        }
        synchronized(writeLock) {
            output.writeByte(2)
            output.writeByte(0)
            output.writeShort(encodings.size)
            encodings.forEach { output.writeInt(it) }
            output.flush()
        }

        requestFramebufferUpdate(false)
    }

    private fun requestFramebufferUpdate(incremental: Boolean) {
        try {
            val out = output ?: return
            synchronized(writeLock) {
                out.writeByte(3)
                out.writeByte(if (incremental) 1 else 0)
                out.writeShort(0)
                out.writeShort(0)
                out.writeShort(fbWidth)
                out.writeShort(fbHeight)
                out.flush()
            }
        } catch (e: Exception) {
            lastError = e.message ?: "Send failed"
        }
    }

    /**
     * Reads one message from the server.
     * @return true when a message was handled successfully
     */
    private fun readServerMessage(): Boolean {
        val inStream = input ?: return false
        return try {
            when (val messageType = inStream.readUnsignedByte()) {
                0 -> readFramebufferUpdate(inStream)
                1 -> { //SetColourMapEntries
                    inStream.readUnsignedByte()
                    inStream.readUnsignedShort()
                    val count = inStream.readUnsignedShort()
                    inStream.readFully(ByteArray(count * 6))
                }
                2 -> observer.onBell() //Bell
                3 -> { //ServerCutText
                    inStream.readFully(ByteArray(3))
                    val length = inStream.readInt()
                    if (length < 0)
                        throw IOException("不支持服务器扩展剪贴板")
                    val text = String(readFully(inStream, length), StandardCharsets.ISO_8859_1)
                    if (text != lastCutText) {
                        lastCutText = text
                        observer.onCutTextReceived(text)
                    }
                }
                else -> throw IOException("收到未知的 VNC 消息类型: $messageType")
            }
            true
        } catch (e: IOException) {
            lastError = e.message ?: e.toString()
            false
        }
    }

    private fun readFramebufferUpdate(input: DataInputStream) {
        input.readUnsignedByte() //padding
        val numRects = input.readUnsignedShort()
        for (i in 0 until numRects) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                0 -> readRawRect(input, x, y, w, h)
                1 -> readCopyRect(input, x, y, w, h)
                -224 -> break
                -223 -> onFramebufferResized(w, h)
                -239 -> readRichCursorRect(input, w, h)
                else -> throw IOException("收到未支持的编码: $encoding")
            }
        }

        if (!frameBufferUpdatesPaused.get())
            requestFramebufferUpdate(true)
        observer.onFramebufferUpdated()
    }

    private fun readRawRect(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val rowStride = synchronized(fbLock) { fbWidth * 4 }
        val rectBytes = readFully(input, w * h * 4)
        synchronized(fbLock) {
            if (x + w > fbWidth || y + h > fbHeight) return
            if (w == fbWidth) {
                System.arraycopy(rectBytes, 0, fbBytes, y * rowStride, w * h * 4)
            } else {
                for (row in 0 until h) {
                    System.arraycopy(rectBytes, row * w * 4, fbBytes, (y + row) * rowStride + x * 4, w * 4)
                }
            }
        }
    }

    private fun readCopyRect(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        synchronized(fbLock) {
            if (x + w > fbWidth || y + h > fbHeight || srcX + w > fbWidth || srcY + h > fbHeight) return
            val rowStride = fbWidth * 4
            for (row in 0 until h) {
                System.arraycopy(fbBytes, (srcY + row) * rowStride + srcX * 4,
                                 fbBytes, (y + row) * rowStride + x * 4, w * 4)
            }
        }
    }

    private fun readRichCursorRect(input: DataInputStream, w: Int, h: Int) {
        if (w <= 0 || h <= 0) {
            cursorInfo = CursorInfo(0, 0, 0, 0)
            return
        }
        val pixels = readFully(input, w * h * 4)
        val maskRowStride = (w + 7) / 8
        val mask = readFully(input, maskRowStride * h)
        synchronized(cursorLock) {
            cursorBytes = pixels
            cursorMask = mask
        }
        cursorInfo = CursorInfo(w, h, 0, 0)
    }

    private fun onFramebufferResized(w: Int, h: Int) {
        synchronized(fbLock) {
            fbWidth = w
            fbHeight = h
            fbBytes = ByteArray(w * h * 4)
        }
        observer.onFramebufferSizeChanged(w, h)
    }

    /******************************************************************************************
     * Helpers
     ******************************************************************************************/

    private inline fun ifConnected(block: () -> Unit) = stateLock.tryRead {
        if (connected && !destroyed)
            block()
    }

    private inline fun ifConnectedAndInteractive(block: () -> Unit) = ifConnected {
        if (!inputDisabled.get())
            block()
    }

    private inline fun <T> ReentrantReadWriteLock.tryRead(block: () -> T) {
        if (this.readLock().tryLock(0, TimeUnit.SECONDS)) {
            try {
                block()
            } finally {
                this.readLock().unlock()
            }
        }
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun readFully(input: DataInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes
    }

    private fun readString(input: DataInputStream, length: Int): String {
        return String(readFully(input, length), StandardCharsets.ISO_8859_1)
    }

    private fun readErrorString(input: DataInputStream) {
        val length = input.readInt()
        val reason = if (length > 0) String(readFully(input, length), StandardCharsets.ISO_8859_1) else ""
        throw IOException(reason.ifEmpty { "VNC 安全协商失败" })
    }

    private fun reverseBits(value: Byte): Byte {
        var v = value.toInt() and 255
        var result = 0
        repeat(8) {
            result = (result shl 1) or (v and 1)
            v = v shr 1
        }
        return result.toByte()
    }

    companion object {
        /**
         * Upstream AVNC loads its native library here. The Kotlin implementation
         * does not need any native library, but the function is kept for API
         * compatibility.
         */
        fun loadLibrary() {
        }
    }
}
