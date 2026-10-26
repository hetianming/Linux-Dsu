package com.mcai.ubuntudsu.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检测（GitHub Releases）。
 *
 * - 启动时调用 [maybeCheck]：距上次检测超过 24 小时才真正请求（本地节流）；
 * - 有更新的版本（含 .apk 资产）时回调 [Info]，由调用方弹更新框；
 * - 下载交给 [com.mcai.ubuntudsu.service.UpdateService]（前台服务，支持后台静默下载）。
 */
object AppUpdate {

    const val REPO = "hetianming/Linux-Dsu"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class Info(
        val tag: String,
        val version: String,
        val name: String,
        val notes: String,
        val apkUrl: String,
    )

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** 语义比较 v1.8.35 > 1.8.34 */
    fun isNewer(remote: String, current: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").removePrefix("V")
            .split(".").map { it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val r = parse(remote)
        val c = parse(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** 24 小时节流检测；结果（有新版本时）回调主线程 */
    fun maybeCheck(context: Context, onNewVersion: (Info) -> Unit) {
        val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong("last_check", now).apply()
        check(context, onNewVersion)
    }

    /** 立即检测（无视节流）；有新版本时回调主线程 */
    fun check(context: Context, onNewVersion: (Info) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            var result: Info? = null
            runCatching {
                val conn =
                    URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "UbuntuDSU-App")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val obj = JSONObject(body)
                    val tag = obj.optString("tag_name", "")
                    val current = currentVersion(appContext)
                    if (isNewer(tag, current)) {
                        var apkUrl = ""
                        val assets = obj.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val a = assets.getJSONObject(i)
                                if (a.optString("name", "").endsWith(".apk", true)) {
                                    apkUrl = a.optString("browser_download_url", "")
                                    break
                                }
                            }
                        }
                        if (apkUrl.isNotBlank()) {
                            result = Info(
                                tag = tag,
                                version = tag.removePrefix("v").removePrefix("V"),
                                name = obj.optString("name", tag),
                                notes = obj.optString("body", ""),
                                apkUrl = apkUrl,
                            )
                        }
                    }
                }
            }
            val info = result
            if (info != null) {
                Handler(Looper.getMainLooper()).post { onNewVersion(info) }
            }
        }.start()
    }
}
