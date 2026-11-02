package com.mcai.ubuntudsu.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * IPTV 直播源：解析 TVBox 订阅（tvbox.json）与 m3u 播放列表，并提供台标异步加载。
 *
 * - [TvSource.kind]=TVBOX 时，先拉取 json 解析 lives[]，再对其中每个 live 的 url（多为 m3u）二次拉取解析合并。
 * - [TvSource.kind]=M3U 时，直接拉取并解析 m3u。
 */
data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgName: String? = null,
    /** 同一频道的备用线路（m3u 里同名频道的其它地址），主线路失效时可切换 */
    val alts: List<String> = emptyList(),
)

enum class SourceKind { TVBOX, M3U }

data class TvSource(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val url: String,
)

object IptvSources {
    val defaults: List<TvSource> = listOf(
        TvSource("tvbox", "IPTV 直播 (TVBox订阅)", SourceKind.TVBOX, "https://lightconer.github.io/iptv-api/tvbox.json"),
        TvSource("ipv4", "IPv4 直播源", SourceKind.M3U, "https://lightconer.github.io/iptv-api/ipv4/result.m3u"),
        TvSource("ipv6", "IPv6 直播源", SourceKind.M3U, "https://lightconer.github.io/iptv-api/ipv6/result.m3u"),
    )

    fun fromUrl(label: String, url: String): TvSource {
        val kind = if (url.trim().endsWith(".json", ignoreCase = true) ||
            url.contains("tvbox", ignoreCase = true)
        ) SourceKind.TVBOX else SourceKind.M3U
        return TvSource("custom_$label", label, kind, url.trim())
    }
}

object IptvSource {

    /**
     * 跨源合并加载：把多个源的频道按「频道名」合并到同一个条目下，
     * 不同源里同名频道会变成同一条的多条备用线路（alts）。
     *
     * 这是抗「直播源不稳定」的关键：lightconer 三源（TVBox/IPv4/IPv6）里
     * 同一个台往往只在某一条线路上能播，合并后播放器可在断流时自动换线，
     * 而不是让用户回列表里挨个试。
     */
    suspend fun loadAllMerged(sources: List<TvSource>): List<Channel> = withContext(Dispatchers.IO) {
        val all = mutableListOf<Channel>()
        for (s in sources) {
            runCatching { all += loadChannels(s) }
        }
        // 跨源再做一次同名合并，把不同源的同名频道叠加成多线路
        mergeLines(all, maxAlts = 12)
    }

    /** 加载一个源的频道列表（合并 TVBox 订阅下所有 live 的 m3u）。 */
    suspend fun loadChannels(source: TvSource): List<Channel> = withContext(Dispatchers.IO) {
        return@withContext when (source.kind) {
            SourceKind.TVBOX -> {
                val channels = mutableListOf<Channel>()
                val seen = mutableSetOf<String>()
                runCatching {
                    val json = fetchText(source.url)
                    for (live in parseTvboxLives(json)) {
                        runCatching {
                            for (ch in parseM3u(fetchText(live.url))) {
                                val key = ch.name + "|" + ch.url
                                if (seen.add(key)) channels.add(ch)
                            }
                        }
                    }
                }
                mergeLines(channels)
            }
            SourceKind.M3U -> mergeLines(parseM3u(fetchText(source.url)))
        }
    }

    /**
     * 把同名频道的多条线路合并成一条：[Channel.url] 为主线路，其余进 [Channel.alts]。
     *
     * IPTV 源质量参差，同一个台常有 2~5 条线路（一条是 CDN 短窗口、一条是组播代理），
     * 合并后播放器就能在断流时自动换线，而不是让用户回列表里试。
     */
    private fun mergeLines(list: List<Channel>, maxAlts: Int = 6): List<Channel> {
        val grouped = LinkedHashMap<String, MutableList<Channel>>()
        for (c in list) grouped.getOrPut(c.name) { mutableListOf() }.add(c)
        return grouped.values.map { group ->
            val head = group.first()
            val alts = group.drop(1).map { it.url }.distinct().take(maxAlts)
            if (alts.isEmpty()) head else head.copy(alts = alts)
        }
    }

    /** 解析 tvbox.json 的 lives[]，每项转为 M3U 子源。 */
    fun parseTvboxLives(json: String): List<TvSource> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("lives") ?: return emptyList()
        val out = mutableListOf<TvSource>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (url.isBlank()) continue
            out.add(TvSource("live_$i", o.optString("name", "直播源$i"), SourceKind.M3U, url))
        }
        return out
    }

    /** 解析标准 m3u 文本，返回频道列表。 */
    fun parseM3u(text: String): List<Channel> {
        val lines = text.lines()
        val channels = mutableListOf<Channel>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val name = line.substringAfterLast(",").trim().ifBlank { "未命名频道" }
                val tvgName = attr(line, "tvg-name")
                val logo = attr(line, "tvg-logo")
                val group = attr(line, "group-title")
                // 找到下一行非空、非 # 开头的作为 URL
                var url = ""
                var j = i + 1
                while (j < lines.size) {
                    val l = lines[j].trim()
                    if (l.isEmpty() || l.startsWith("#")) { j++; continue }
                    url = l
                    break
                }
                if (url.isNotBlank()) {
                    channels.add(
                        Channel(
                            name = name,
                            url = url,
                            logo = logo.ifBlank { null },
                            group = group.ifBlank { null },
                            tvgName = tvgName.ifBlank { null },
                        ),
                    )
                }
                i = j + 1
            } else {
                i++
            }
        }
        return channels
    }

    private fun attr(line: String, key: String): String {
        val regex = Regex("""$key\s*=\s*"([^"]*)"""")
        return regex.find(line)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "LinuxDsu/1.8.54")
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 台标图片加载（内存缓存，失败返回 null）。 */
    private val logoCache = LruCache<String, Bitmap>(256)

    suspend fun loadLogo(url: String): Bitmap? = withContext(Dispatchers.IO) {
        logoCache.get(url)?.let { return@withContext it }
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "LinuxDsu/1.8.54")
                instanceFollowRedirects = true
            }
            val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            conn.disconnect()
            bmp?.also { logoCache.put(url, it) }
        }.getOrNull()
    }
}
