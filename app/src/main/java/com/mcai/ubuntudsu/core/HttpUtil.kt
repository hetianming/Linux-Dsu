package com.mcai.ubuntudsu.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 简单的 HTTP 请求工具
 */
object HttpUtil {

    /**
     * 发送 GET 请求，返回响应体字符串
     */
    suspend fun get(url: String, timeoutMs: Int = 10000): String? {
        return withTimeoutOrNull(timeoutMs.toLong()) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
                conn.setRequestProperty("Accept", "application/json")

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    response.toString()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 简单的超时包装
     */
    private suspend fun <T> withTimeoutOrNull(timeMillis: Long, block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            null
        }
    }
}
