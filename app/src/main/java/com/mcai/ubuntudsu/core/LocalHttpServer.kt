package com.mcai.ubuntudsu.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class LocalHttpServer(
    private val length: Long,
    private val openStream: () -> InputStream,
    private val onRequest: (String) -> Unit = {},
) : Thread("dsu-http") {
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = true
    private val pool = Executors.newFixedThreadPool(2)
    private var boundPort = -1

    val port: Int get() = boundPort
    val url: String get() = "http://127.0.0.1:$boundPort/gsi.zip"

    constructor(file: File, onRequest: (String) -> Unit = {}) : this(
        file.length(),
        { file.inputStream() },
        onRequest,
    )

    override fun run() {
        try {
            val server = serverSocket ?: ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")).also {
                serverSocket = it
                boundPort = it.localPort
            }
            onRequest("HTTP 服务已启动: $url")
            while (running) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.submit { handle(client) }
            }
        } catch (e: Exception) {
            onRequest("HTTP 服务异常: ${e.message}")
        }
    }

    @Synchronized
    fun startAndWait() {
        if (isAlive) return
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        boundPort = server.localPort
        start()
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 300000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                onRequest("<- $requestLine")
                var rangeStart = 0L
                var rangeEnd = -1L
                var suffixLength = -1L
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) {
                        Regex("bytes=(\\d*)-(\\d*)").find(line)?.let { m ->
                            m.groupValues[1].takeIf { it.isNotEmpty() }?.toLong()?.let { rangeStart = it }
                            if (m.groupValues[1].isEmpty()) {
                                m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()?.let { suffixLength = it }
                            }
                            m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()?.let { rangeEnd = it }
                        }
                    }
                }
                val total = length
                if (suffixLength > 0) rangeStart = maxOf(0L, total - suffixLength)
                val end = if (rangeEnd >= 0) minOf(rangeEnd, total - 1) else total - 1
                val length = end - rangeStart + 1
                if (rangeStart >= total) {
                    s.getOutputStream().apply {
                        write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        flush()
                    }
                    return
                }
                val head = if (rangeStart > 0 || rangeEnd >= 0) {
                    "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: application/zip\r\n" +
                        "Content-Range: bytes $rangeStart-$end/$total\r\n" +
                        "Content-Length: $length\r\n" +
                        "Accept-Ranges: bytes\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/zip\r\n" +
                        "Content-Length: $total\r\n" +
                        "Accept-Ranges: bytes\r\n\r\n"
                }
                val output = s.getOutputStream()
                output.write(head.toByteArray(Charsets.ISO_8859_1))
                output.flush()
                openStream().use { input ->
                    input.skip(rangeStart)
                    val buffer = ByteArray(256 * 1024)
                    var remaining = length
                    while (remaining > 0 && running) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        remaining -= n
                    }
                    output.flush()
                }
                onRequest("-> 已发送 $rangeStart-$end")
            }
        } catch (e: Exception) {
            onRequest("连接异常: ${e.message}")
        }
    }

    fun shutdown() {
        running = false
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
        onRequest("HTTP 服务已停止")
    }
}
