package com.mcai.ubuntudsu.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// 在线更新：查询 GitHub Releases 最新版本，下载 APK 并触发系统安装器
object AppUpdater {
    private const val API_URL = "https://api.github.com/repos/hetianming/Linux-Dsu/releases/latest"

    data class ReleaseInfo(
        val version: String,        // tag 名，如 v1.0.3
        val notes: String,          // release 说明
        val apkUrl: String,         // apk 下载直链
        val apkName: String,        // apk 文件名
        val apkSize: Long,          // apk 大小（字节）
    )

    // 拉取最新 Release；无 Release 或无 apk 资源返回 null（视为无更新通道）
    fun fetchLatest(): ReleaseInfo? {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "UbuntuDSU-Updater")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connect()
        if (connection.responseCode !in 200..299) return null
        val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        connection.disconnect()
        val json = JSONObject(body)
        val assets = json.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", true)) {
                return ReleaseInfo(
                    version = json.optString("tag_name").removePrefix("v"),
                    notes = json.optString("body"),
                    apkUrl = asset.optString("browser_download_url"),
                    apkName = name,
                    apkSize = asset.optLong("size", -1L),
                )
            }
        }
        return null
    }

    // 本地版本是否落后于线上版本（按 x.y.z 逐段比较）
    fun isNewer(local: String, remote: String): Boolean {
        val lhs = local.split('.').map { it.toIntOrNull() ?: 0 }
        val rhs = remote.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(lhs.size, rhs.size)) {
            val a = lhs.getOrElse(i) { 0 }
            val b = rhs.getOrElse(i) { 0 }
            if (b > a) return true
            if (b < a) return false
        }
        return false
    }

    // 下载 apk 到 app 私有下载目录，返回进度（0-100）与完成文件
    fun download(
        info: ReleaseInfo,
        targetDir: File,
        onProgress: (Int) -> Unit,
    ): File? {
        val target = File(targetDir, "update-${info.version}.apk")
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "UbuntuDSU-Updater")
        connection.connect()
        if (connection.responseCode !in 200..299) return null
        val total = if (info.apkSize > 0) info.apkSize else connection.contentLengthLong
        try {
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { target.delete() }
            return null
        }
        return target
    }

    // 触发系统安装器安装 apk（Android 7.0+ 经 FileProvider 暴露）
    fun install(activity: Activity, apk: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            androidx.core.content.FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apk,
            )
        } else {
            Uri.fromFile(apk)
        }
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
