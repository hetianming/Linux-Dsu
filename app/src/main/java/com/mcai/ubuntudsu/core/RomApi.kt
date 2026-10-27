package com.mcai.ubuntudsu.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ROM 版本信息
 */
data class RomVersion(
    val version: String,       // 版本号，如 OS1.0.7.0.TJBCNXM
    val branchName: String,    // 分支名称，如 正式版/Beta
    val region: String,        // 区域：cn/global/eea等
    val androidVersion: String,
    val releaseDate: String,
    val securityPatch: String,
    val recoveryFile: String? = null,  // Recovery 包文件名
    val fastbootFile: String? = null,  // Fastboot 包文件名
    val md5: String = "",
)

/**
 * 设备信息
 */
data class RomDevice(
    val name: String,       // 设备中文名
    val nameEn: String,     // 设备英文名
    val codename: String,   // 设备代号
    val brand: String,      // 品牌：Xiaomi/Redmi/POCO
    val series: String,     // 系列
    val type: String,       // 类型：phone/tablet/watch
)

/**
 * 欧加固件条目（rom.yule.ink /api/ota/catalog 数据源）
 * device 字段前缀即品牌：OP=OnePlus, OPPO, REALME=Realme, MEIZU=魅族
 * source_url 是占位值，需经 POST /api/ota/resolve 换取带有效期的真实下载链接
 */
data class YuleRomEntry(
    val releaseId: String,
    val device: String,      // 如 "OPPO PJX110"
    val version: String,
    val region: String = "",
    val sizeBytes: Long = 0,
    val md5: String = "",
    val buildTimestamp: String = "",
    val sourceUrl: String,
) {
    val brand: String get() = when {
        device.startsWith("OP ", true) || device == "OP" -> "OnePlus"
        device.startsWith("OPPO", true) -> "OPPO"
        device.startsWith("REALME", true) -> "Realme"
        device.startsWith("MEIZU", true) -> "Meizu"
        else -> device.substringBefore(' ').ifEmpty { device }
    }

    val sizeText: String
        get() = if (sizeBytes > 0) "%.2f GB".format(sizeBytes / 1024.0 / 1024.0 / 1024.0) else ""
}

/**
 * 欧加固件列表请求结果（含失败原因，便于界面提示）
 */
data class YuleRomResult(
    val entries: List<YuleRomEntry>,
    val error: String? = null,
)

/**
 * /api/ota/resolve 返回的临时下载链接
 */
data class YuleResolvedUrl(
    val url: String,
    val expiresAt: String,
)

/**
 * ROM 下载节点
 */
enum class DownloadNode(val displayName: String, val baseUrl: String) {
    BIG_OTA("BigOTA", "https://bigota.d.miui.com/"),
    HUGE_OTA("HugeOTA", "https://hugeota.d.miui.com/"),
    CDN_ORG("CDN.ORG", "https://cdnorg.d.miui.com/"),
    ALIYUN("阿里云", "https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/"),
    BN("BN", "https://bigota.d.miui.com/"),
}

object RomApi {

    private const val USER_AGENT = "Dsu-Manager-ROM/3.6.0"

    // 三个 ROM 数据源
    private const val FANS_DEVICE_URL = "https://data.hyperos.fans/devices/"
    private const val DEVICE_URL = "https://raw.githubusercontent.com/HegeKen/HyperData/main/devices/"
    private const val DEVICE_CDN_URL = "https://cdn.jsdelivr.net/gh/HegeKen/HyperData@main/devices/"

    // 设备列表数据源
    private const val DEVICES_JSON_URL = "https://raw.githubusercontent.com/HegeKen/HyperData/main/devices.json"
    // jsDelivr CDN 镜像：国内直连通常 1~2 秒，GitHub raw 常被墙（实测 19 秒 / 直接超时）
    private const val DEVICES_JSON_CDN_URL = "https://cdn.jsdelivr.net/gh/HegeKen/HyperData@main/devices.json"
    private const val DEVICES_JSON_FASTLY_URL = "https://fastly.jsdelivr.net/gh/HegeKen/HyperData@main/devices.json"

    // 欧加固件数据源（OPPO / OnePlus / Realme）
    private const val YULE_BASE_URL = "https://rom.yule.ink"
    private const val YULE_CATALOG_URL = "$YULE_BASE_URL/api/ota/catalog"
    private const val YULE_RESOLVE_URL = "$YULE_BASE_URL/api/ota/resolve"

    // 欧加接口走 Cloudflare 防护，必须用浏览器 UA，否则会被机器人拦截返回 HTML
    private const val YULE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; PMA120) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * 获取设备列表（优先本地缓存由 RomCache 负责，这里只处理网络）
     */
    suspend fun fetchDeviceList(): List<RomDevice> = withContext(Dispatchers.IO) {
        val body = fetchDevicesBody()
        if (body != null) parseDevicesJson(body) else builtInDevices()
    }

    /**
     * 拉取设备列表原始 JSON：先 CDN 镜像（国内快），再 GitHub raw 兜底。
     * 不再长时间等待 raw 的 15 秒超时。
     */
    internal suspend fun fetchDevicesBody(): String? = withContext(Dispatchers.IO) {
        val sources = listOf(
            DEVICES_JSON_CDN_URL to 8000,
            DEVICES_JSON_FASTLY_URL to 8000,
            DEVICES_JSON_URL to 6000,
        )
        for ((url, timeout) in sources) {
            val body = httpGet(url, timeoutMs = timeout)
            if (!body.isNullOrBlank() && body.trimStart().startsWith("{")) {
                val parsed = parseDevicesJson(body)
                if (parsed.isNotEmpty() && parsed.size > 50) return@withContext body
            }
        }
        null
    }

    /** 内置设备列表（离线兜底，进入页面时立即渲染用） */
    fun builtInDevices(): List<RomDevice> = getBuiltInDevices()

    internal fun parseDevicesBody(json: String): List<RomDevice> = parseDevicesJson(json)
    internal fun parseYuleBody(json: String): List<YuleRomEntry> = parseYuleRomList(json)

    internal fun parseVersionsBody(json: String): List<RomVersion> = try {
        parseVersionsFromBranches(JSONObject(json))
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 获取欧加固件列表（OPPO / OnePlus / Realme / Meizu）
     * 数据源：rom.yule.ink /api/ota/catalog
     * 返回结构：{ releases: [{id, device, version, region, size, md5, build_timestamp, source_url}], cached_at }
     */
    suspend fun fetchYuleRomList(): YuleRomResult = withContext(Dispatchers.IO) {
        val (body, error) = fetchYuleBody()
        if (body == null) YuleRomResult(emptyList(), error)
        else YuleRomResult(parseYuleRomList(body))
    }

    /** 拉取欧加目录原始 JSON（1.1MB / 1600+ 条），并保留错误信息供界面提示 */
    internal suspend fun fetchYuleBody(): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(YULE_CATALOG_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", YULE_USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("Referer", "https://rom.yule.ink/")

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext null to "HTTP $code（可能被站点防护拦截）"
            }
            // 服务端可能返回 gzip，HttpURLConnection 不会自动解压
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true) {
                java.util.zip.GZIPInputStream(raw)
            } else raw
            val body = stream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (body.trimStart().startsWith("<")) {
                return@withContext null to "接口返回HTML（被防护拦截或接口路径变更）"
            }
            body to null
        } catch (e: Exception) {
            null to "${e.javaClass.simpleName}: ${e.message ?: "网络异常"}"
        }
    }

    /**
     * 用 release_id 换取带有效期的真实下载链接
     * POST /api/ota/resolve {release_id, device, source_url} -> {url, expires_at}
     */
    suspend fun resolveYuleDownload(entry: YuleRomEntry): YuleResolvedUrl? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(YULE_RESOLVE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", YULE_USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Referer", "https://rom.yule.ink/")

            val reqBody = JSONObject().apply {
                put("release_id", entry.releaseId)
                put("device", entry.device)
                put("source_url", entry.sourceUrl)
            }.toString()
            conn.outputStream.use { it.write(reqBody.toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val url = resp.optString("url", "")
            if (url.isBlank()) return@withContext null
            YuleResolvedUrl(url, resp.optString("expires_at", ""))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseYuleRomList(json: String): List<YuleRomEntry> {
        val entries = mutableListOf<YuleRomEntry>()
        try {
            val root = JSONObject(json)
            val releases = root.optJSONArray("releases") ?: return entries
            for (i in 0 until releases.length()) {
                val item = releases.optJSONObject(i) ?: continue
                val sourceUrl = item.optString("source_url", "")
                val device = item.optString("device", "")
                if (sourceUrl.isBlank() && device.isBlank()) continue
                entries.add(
                    YuleRomEntry(
                        releaseId = item.optString("id", ""),
                        device = device,
                        version = item.optString("version", ""),
                        region = item.optString("region", ""),
                        sizeBytes = item.optLong("size", 0L),
                        md5 = item.optString("md5", ""),
                        buildTimestamp = item.optString("build_timestamp", ""),
                        sourceUrl = sourceUrl,
                    )
                )
            }
        } catch (_: Exception) {
        }
        return entries
    }

    /**
     * 查询设备 ROM 版本（并发请求三个源，**第一个可用就返回**，不再等最慢的 GitHub raw）
     */
    suspend fun fetchDeviceVersions(codename: String): List<RomVersion> {
        val json = fetchDeviceVersionsJson(codename) ?: return emptyList()
        return parseVersionsBody(json)
    }

    /** 返回命中的原始 JSON 文本（便于写入缓存） */
    internal suspend fun fetchDeviceVersionsJson(codename: String): String? = coroutineScope {
        val urls = listOf(
            FANS_DEVICE_URL + codename + ".json",
            DEVICE_CDN_URL + codename + ".json",
            DEVICE_URL + codename + ".json",
        )
        val channel = Channel<JSONObject?>(urls.size)
        urls.forEach { url ->
            launch { channel.send(runCatching { queryHyperData(url) }.getOrNull()) }
        }

        val fallbacks = mutableListOf<JSONObject>()
        var result: String? = null
        for (i in 0 until urls.size) {
            val json = channel.receive() ?: continue
            if (hasUsableBranches(json)) {
                result = json.toString()
                // 命中第一个可用源，取消其余请求，避免被 raw.githubusercontent 拖到 8 秒
                coroutineContext.cancelChildren()
                break
            }
            fallbacks.add(json)
        }
        result ?: if (fallbacks.isEmpty()) null else mergeResults(fallbacks).toString()
    }

    /**
     * 生成下载 URL
     * URL 格式：baseUrl + 版本号目录 + 文件名
     * 参考 Dsu-Manager 的 downloadUrl() 实现
     */
    fun getDownloadUrl(filename: String, version: String, node: DownloadNode = DownloadNode.ALIYUN): String {
        if (filename.startsWith("http://") || filename.startsWith("https://")) {
            // 如果已经是完整URL，提取路径部分
            val path = filename.substringAfter("://").substringAfter('/')
            return node.baseUrl + path
        }
        val cleanName = filename.trimStart('/')
        // 版本号作为目录（如 OS3.0.303.0.WMBEUXM/ 或 V14.0.2.0.TJBCNXM/）
        val versionDir = version.trim()
        val path = if (versionDir.isNotEmpty()) {
            "$versionDir/$cleanName"
        } else {
            cleanName
        }
        // 确保 .zip 后缀
        val lowerPath = path.lowercase()
        val fullPath = if (!lowerPath.endsWith(".zip") && !lowerPath.endsWith(".tgz")) {
            "$path.zip"
        } else {
            path
        }
        return node.baseUrl + fullPath
    }

    /**
     * 小米 CDN 所需的 Referer（防盗链绕过）
     */
    const val MIUI_REFERER = "https://www.miui.com/"

    /**
     * 下载 User-Agent（模拟小米系统下载器）
     */
    const val MIUI_USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 16; PMA120 Build/BP2A.250605.015)"

    // ========== 内部方法 ==========

    private fun queryHyperData(url: String): JSONObject? {
        return try {
            val json = httpGet(url, timeoutMs = 8000) ?: return null
            JSONObject(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun hasUsableBranches(root: JSONObject): Boolean {
        val branches = root.optJSONArray("branches") ?: return false
        for (i in 0 until branches.length()) {
            val branch = branches.optJSONObject(i) ?: continue
            if (branch.optString("show") != "1") continue
            val roms = branch.optJSONObject("roms") ?: continue
            if (roms.length() > 0) return true
        }
        return false
    }

    private fun mergeResults(results: List<JSONObject>): JSONObject {
        val merged = JSONObject()
        val allBranches = JSONArray()
        for (result in results) {
            val branches = result.optJSONArray("branches") ?: continue
            for (i in 0 until branches.length()) {
                allBranches.put(branches.opt(i))
            }
        }
        merged.put("branches", allBranches)
        return merged
    }

    private fun parseVersionsFromBranches(root: JSONObject): List<RomVersion> {
        val versions = mutableListOf<RomVersion>()
        val branches = root.optJSONArray("branches") ?: return versions

        for (i in 0 until branches.length()) {
            val branch = branches.optJSONObject(i) ?: continue
            if (branch.optString("show") != "1") continue

            val branchName = branch.optJSONObject("name")?.optString("zh")
                ?: branch.optString("branchCode", "")
            val region = branch.optString("region", "")

            val roms = branch.optJSONObject("roms") ?: continue
            val keys = roms.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val rom = roms.optJSONObject(key) ?: continue

                versions.add(
                    RomVersion(
                        version = rom.optString("os", key),
                        branchName = branchName,
                        region = region.uppercase(),
                        androidVersion = rom.optString("android", ""),
                        releaseDate = rom.optString("release", ""),
                        securityPatch = rom.optString("aspatch", ""),
                        recoveryFile = rom.optString("recovery").takeIf { it.isNotBlank() },
                        fastbootFile = rom.optString("fastboot").takeIf { it.isNotBlank() },
                        md5 = rom.optString("md5", ""),
                    )
                )
            }
        }

        // 按发布日期降序
        return versions.sortedByDescending { it.releaseDate }
    }

    private fun parseDevicesJson(json: String): List<RomDevice> {
        val devices = mutableListOf<RomDevice>()
        try {
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val groupKey = keys.next()
                val group = root.optJSONObject(groupKey) ?: continue
                val brand = group.optString("brand", groupKey)
                val devicesArray = group.optJSONArray("devices") ?: continue

                for (i in 0 until devicesArray.length()) {
                    val dev = devicesArray.optJSONObject(i) ?: continue
                    val code = dev.optString("code", "")
                    if (code.isBlank()) continue

                    val nameObj = dev.optJSONObject("name")
                    val nameZh = nameObj?.optString("zh", code) ?: code
                    val nameEn = nameObj?.optString("en", code) ?: code

                    val seriesObj = dev.optJSONObject("series")
                    val seriesZh = seriesObj?.optString("zh", "") ?: ""

                    devices.add(
                        RomDevice(
                            name = nameZh,
                            nameEn = nameEn,
                            codename = code,
                            brand = brand,
                            series = seriesZh,
                            type = dev.optString("type", "phone"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return devices.ifEmpty { getBuiltInDevices() }
    }

    private fun getBuiltInDevices(): List<RomDevice> {
        return listOf(
        // Xiaomi 系列（80 款）
        RomDevice("小米 10", "Xiaomi 10", "umi", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 10 Pro", "Xiaomi 10 Pro", "cmi", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 10 至尊纪念版", "Xiaomi 10 Ultra", "cas", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 10S", "Xiaomi 10S", "thyme", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11", "Xiaomi 11", "venus", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11 Pro / Ultra", "Xiaomi 11 Pro / Ultra", "star", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11 青春活力版", "Mi 11 LE", "lisa", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11 Lite 5G NE", "Xiaomi 11 Lite 5G NE", "lisa", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11i", "Mi 11i", "haydn", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11i / Hypercharge", "Xiaomi 11i / Hypercharge", "pissarro_in", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11T", "Xiaomi 11T", "agate", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11T Pro", "Xiaomi 11T Pro", "vili", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11X", "Mi 11X", "alioth", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 11X Pro", "Mi 11X Pro", "haydn", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12", "Xiaomi 12", "cupid", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12 Pro", "Xiaomi 12 Pro", "zeus", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12X", "Xiaomi 12X", "psyche", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12 Pro 天玑版", "Xiaomi 12 Pro Dimensity", "daumier", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12 Lite", "Xiaomi 12 Lite", "taoyao", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12S", "Xiaomi 12S", "mayfly", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12S Pro", "Xiaomi 12S Pro", "unicorn", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12S Ultra", "Xiaomi 12S Ultra", "thor", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12T", "Xiaomi 12T", "plato", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 12T Pro", "Xiaomi 12T Pro", "diting", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 13", "Xiaomi 13", "fuxi", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 13 Pro", "Xiaomi 13 Pro", "nuwa", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 13 Ultra", "Xiaomi 13 Ultra", "ishtar", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 13T", "Xiaomi 13T", "aristotle", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 13T Pro", "Xiaomi 13T Pro", "corot", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 14", "Xiaomi 14", "houji", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 14 Pro / Ti / 钛金属卫星通讯版", "Xiaomi 14 Pro / Ti / Satellite", "shennong", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 14 Pro 钛金属卫星通讯版", "Xiaomi 14 Pro Ti Satellite", "shennong_t", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 14 Civi", "Xiaomi 14 Civi", "chenfeng", "Xiaomi", "小米Civi系列", "phone"),
        RomDevice("小米 14 Ultra / Ultra Ti", "Xiaomi 14 Ultra / Ultra Ti", "aurora", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 14T", "Xiaomi 14T", "degas", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 14T Pro", "Xiaomi 14T Pro", "rothko", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 15", "Xiaomi 15", "dada", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 15 Pro", "Xiaomi 15 Pro", "haotian", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 15 Ultra", "Xiaomi 15 Ultra", "xuanyuan", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 15S Pro", "Xiaomi 15S Pro", "dijun", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 15T", "Xiaomi 15T", "goya", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 15T Pro", "Xiaomi 15T Pro", "klimt", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 17", "Xiaomi 17", "pudding", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 17 Pro", "Xiaomi 17 Pro", "pandora", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 17 Max", "Xiaomi 17 Max", "byron", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 17 Pro Max", "Xiaomi 17 Pro Max", "popsicle", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 17 Ultra", "Xiaomi 17 Ultra by Leica", "nezha", "Xiaomi", "小米系列", "phone"),
        RomDevice("Xiaomi 17T", "Xiaomi 17T", "chagall", "Xiaomi", "小米系列", "phone"),
        RomDevice("Xiaomi 17T Pro", "Xiaomi 17T Pro", "warhol", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米 18 Fold", "Xiaomi 18 Fold", "lhasa", "Xiaomi", "小米系列", "phone"),
        RomDevice("小米平板 5", "Xiaomi Pad 5", "nabu", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 5 Pro 5G", "Xiaomi Pad 5 Pro 5G", "enuma", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 5 Pro Wi-Fi", "Xiaomi Pad 5 Pro Wi-Fi", "elish", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 5 Pro 12.4", "Xiaomi Pad 5 Pro 12.4", "dagu", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 6", "Xiaomi Pad 6", "pipa", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 6 Pro", "Xiaomi Pad 6 Pro", "liuqin", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 6 Max 14", "Xiaomi Pad 6 Max 14", "yudi", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 6S Pro 12.4", "Xiaomi Pad 6S Pro 12.4", "sheng", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 7", "Xiaomi Pad 7", "uke", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 7 Pro", "Xiaomi Pad 7 Pro", "muyu", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 7 Ultra", "Xiaomi Pad 7 Ultra", "jinghu", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 7S Pro 12.5", "Xiaomi Pad 7S Pro 12.5", "violin", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 8", "Xiaomi Pad 8", "yupei", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 8 Pro", "Xiaomi Pad 8 Pro", "piano", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 9 Pro Max", "Xiaomi Pad 9 Pro Max", "yingtian", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米平板 Mini", "Xiaomi Pad Mini", "turner", "Xiaomi", "小米平板系列", "phone"),
        RomDevice("小米 MIX 4", "MIX 4", "odin", "Xiaomi", "小米 MIX系列", "phone"),
        RomDevice("小米 MIX FOLD", "MI MIX FOLD", "cetus", "Xiaomi", "小米 MIX系列", "phone"),
        RomDevice("小米 MIX Fold 2", "Xiaomi MIX Fold 2", "zizhan", "Xiaomi", "小米 MIX系列", "phone"),
        RomDevice("小米 MIX Fold 3", "Xiaomi MIX Fold 3", "babylon", "Xiaomi", "小米 MIX系列", "phone"),
        RomDevice("小米 MIX Fold 4", "Xiaomi MIX Fold 4", "goku", "Xiaomi", "小米 MIX系列", "phone"),
        RomDevice("小米 MIX Flip", "Xiaomi MIX Flip", "ruyi", "Xiaomi", "小米 MIX系列", "phone"),
        RomDevice("小米 MIX Flip 2", "Xiaomi MIX Flip 2", "bixi", "Xiaomi", "小米 MIX系列", "phone"),
        RomDevice("小米 Civi", "Xiaomi Civi", "mona", "Xiaomi", "小米 Civi系列", "phone"),
        RomDevice("小米 Civi 1S", "Xiaomi Civi 1S", "zijin", "Xiaomi", "小米 Civi系列", "phone"),
        RomDevice("小米 Civi 2", "Xiaomi Civi 2", "ziyi", "Xiaomi", "小米 Civi系列", "phone"),
        RomDevice("小米 Civi 3", "Xiaomi Civi 3", "yuechu", "Xiaomi", "小米 Civi系列", "phone"),
        RomDevice("小米 Civi 4 Pro", "Xiaomi Civi 4 Pro", "chenfeng", "Xiaomi", "小米 Civi系列", "phone"),
        RomDevice("小米 Civi 5 Pro", "Xiaomi Civi 5 Pro", "luming", "Xiaomi", "小米 Civi系列", "phone"),

        // REDMI 系列（117 款）
        RomDevice("Redmi 10 5G", "Redmi 10 5G", "light", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 11 Prime 4G", "Redmi 11 Prime 4G", "rock", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 11 Prime 5G", "Redmi 11 Prime 5G", "light", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 12 国际", "Redmi 12", "fire", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 12 5G", "Redmi 12 5G", "sky", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 12C", "Redmi 12C", "earth", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 12 / 12R 5G", "Redmi 12 / 12R 5G", "sky", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 13 5G", "Redmi 13 5G", "breeze", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 13 国际", "Redmi 13", "moon", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 13C / 13R 5G", "Redmi 13C / 13R 5G", "air", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 13C 国际", "Redmi 13C", "gale", "REDMI", "Redmi 系列", "phone"),
        RomDevice("Redmi 14C", "Redmi 14C", "lake", "REDMI", "Redmi 系列", "phone"),
        RomDevice("REDMI 15 4G", "REDMI 15 4G", "creek", "REDMI", "Redmi 系列", "phone"),
        RomDevice("REDMI 15C", "REDMI 15C", "dew", "REDMI", "Redmi 系列", "phone"),
        RomDevice("REDMI 15C 5G", "REDMI 15C 5G", "tornado", "REDMI", "Redmi 系列", "phone"),
        RomDevice("REDMI 15 5G", "REDMI 15 5G", "spring", "REDMI", "Redmi 系列", "phone"),
        RomDevice("REDMI 17 5G", "REDMI 17 5G", "somalia", "REDMI", "REDMI 系列", "phone"),
        RomDevice("REDMI M100", "REDMI M100", "steppe", "REDMI", "REDMI M 系列", "phone"),
        RomDevice("Redmi Note 11 5G / 11T 5G", "Redmi Note 11 5G / 11T 5G", "evergo", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11E", "Redmi Note 11E", "light", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11R", "Redmi Note 11R", "lightcm", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11E Pro", "Redmi Note 11E Pro", "veux", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11 Pro 5G", "Redmi Note 11 Pro 5G", "veux", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11 Pro / Pro+ / Pro+ 5G", "Redmi Note 11 Pro / Pro+ / Pro+ 5G", "pissarro", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11T Pro / Pro+", "Redmi Note 11T Pro / Pro+", "xaga", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11 国际版", "Redmi Note 11", "spes", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11 NFC", "Redmi Note 11 NFC", "spesn", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11 Pro 4G", "Redmi Note 11 Pro 4G", "viva", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11 Pro 4G 印度版", "Redmi Note 11 Pro 4G India", "vida", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11S 4G", "Redmi Note 11S 4G", "fleur", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 11S 5G", "Redmi Note 11S 5G", "opal", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 12 5G / Note 12R Pro", "Redmi Note 12 5G / Note 12R Pro", "sunstone", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 12 Pro / Pro+ / 探索版", "Redmi Note 12 Pro / Pro+ / Discovery 5G", "ruby", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 12 Pro 极速版", "Redmi Note 12 Pro Speed", "redwood", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 12T Pro", "Redmi Note 12T Pro", "pearl", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 12 Turbo", "Redmi Note 12 Turbo", "marble", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 12 4G", "Redmi Note 12 4G", "tapas", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 12 4G NFC", "Redmi Note 12 4G NFC", "topaz", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 12 Pro 4G", "Redmi Note 12 Pro 4G", "sweet_k6a", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 12S", "Redmi Note 12S", "sea", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 13 4G", "Redmi Note 13 4G", "sapphire", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 13 5G", "Redmi Note 13 5G", "gold", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 13 Pro 5G", "Redmi Note 13 Pro 5G", "garnet", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 13 Pro 4G", "Redmi Note 13 Pro 4G", "emerald", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 13 Pro+ 5G", "Redmi Note 13 Pro+ 5G", "zircon", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 13R Pro", "Redmi Note 13R Pro", "gold", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 13 国际版", "Redmi Note 13", "sapphire", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 13 NFC", "Redmi Note 13 NFC", "sapphiren", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 14 4G", "Redmi Note 14 4G", "tanzanite", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 14 5G", "Redmi Note 14 5G", "beryl", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 14 Pro 4G", "Redmi Note 14 Pro 4G", "obsidian", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 14 Pro", "Redmi Note 14 Pro", "malachite", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 14 Pro+", "Redmi Note 14 Pro+", "amethyst", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Note 14s", "Redmi Note 14s", "emerald_r", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("REDMI Note 15 5G", "REDMI Note 15 5G", "kunzite", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("REDMI Note 15 Pro 5G", "REDMI Note 15 Pro 5G", "lapis", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("REDMI Note 15 Pro+ 5G", "REDMI Note 15 Pro+ 5G", "flourite", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("REDMI Note 15 4G", "REDMI Note 15 4G", "spinel", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("REDMI Note 15 Pro 4G", "REDMI Note 15 Pro 4G", "charoite", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("REDMI Note 17 5G", "REDMI Note 17 5G", "mist", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("REDMI Note 17 Pro 5G", "REDMI Note 17 Pro 5G", "iolite", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("REDMI Note 17 Pro Max", "REDMI Note 17 Pro Max", "brussels", "REDMI", "Redmi Note系列", "phone"),
        RomDevice("Redmi Turbo 3", "Redmi Turbo 3", "peridot", "REDMI", "Redmi Turbo系列", "phone"),
        RomDevice("REDMI Turbo 4", "REDMI Turbo 4", "rodin", "REDMI", "Redmi Turbo系列", "phone"),
        RomDevice("REDMI Turbo 4 Pro", "REDMI Turbo 4 Pro", "onyx", "REDMI", "Redmi Turbo系列", "phone"),
        RomDevice("REDMI Turbo 5", "REDMI Turbo 5", "klee", "REDMI", "Redmi Turbo系列", "phone"),
        RomDevice("REDMI Turbo 5 MAX", "REDMI Turbo 5 MAX", "dash", "REDMI", "Redmi Turbo系列", "phone"),
        RomDevice("Redmi Note 12R", "Redmi Note 12R", "sky", "REDMI", "Redmi R 系列", "phone"),
        RomDevice("Redmi 13R 5G", "Redmi 13R 5G", "air", "REDMI", "Redmi R 系列", "phone"),
        RomDevice("Redmi Note 13R 5G", "Redmi Note 13R 5G", "breeze", "REDMI", "Redmi R 系列", "phone"),
        RomDevice("Redmi 14R 5G", "Redmi 14R 5G", "flame", "REDMI", "Redmi R 系列", "phone"),
        RomDevice("REDMI 15R 5G", "REDMI 15R 5G", "tornado", "REDMI", "REDMI R 系列", "phone"),
        RomDevice("REDMI Note 15R 5G", "REDMI Note 15R 5G", "spring", "REDMI", "REDMI R 系列", "phone"),
        RomDevice("REDMI R70 / REDMI R70m", "REDMI R70 / REDMI R70m", "somalia", "REDMI", "REDMI R 系列", "phone"),
        RomDevice("Redmi K40", "Redmi K40", "alioth", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K40 Pro/Pro+", "Redmi K40 Pro/Pro+", "haydn", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K40 游戏增强版", "Redmi K40 Gaming", "ares", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K40S", "Redmi K40S", "munch", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K50", "Redmi K50", "rubens", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K50 Pro", "Redmi K50 Pro", "matisse", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K50i", "Redmi K50i", "xaga", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K50 电竞版", "Redmi K50G", "ingres", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K50 至尊版", "Redmi K50 Ultra", "diting", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K60E", "Redmi K60E", "rembrandt", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K60", "Redmi K60", "mondrian", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K60 Pro", "Redmi K60 Pro", "socrates", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K60 至尊版", "Redmi K60 Ultra", "corot", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K70E", "Redmi K70E", "duchamp", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K70", "Redmi K70", "vermeer", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K70 Pro", "Redmi K70 Pro", "manet", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi K70 至尊版", "Redmi K70 Ultra", "rothko", "REDMI", "Redmi K系列", "phone"),
        RomDevice("REDMI K80", "REDMI K80", "zorn", "REDMI", "Redmi K系列", "phone"),
        RomDevice("REDMI K80 Pro", "REDMI K80 Pro", "miro", "REDMI", "Redmi K系列", "phone"),
        RomDevice("REDMI K80 至尊版", "REDMI K80 Ultra", "dali", "REDMI", "Redmi K系列", "phone"),
        RomDevice("REDMI K90", "REDMI K90", "annibale", "REDMI", "Redmi K系列", "phone"),
        RomDevice("REDMI K90 Max", "REDMI K90 Max", "prague", "REDMI", "Redmi K系列", "phone"),
        RomDevice("REDMI K90 至尊版", "REDMI K90 Ultra", "warsaw", "REDMI", "Redmi K系列", "phone"),
        RomDevice("REDMI K90 Pro Max", "REDMI K90 Pro Max", "myron", "REDMI", "Redmi K系列", "phone"),
        RomDevice("REDMI K100 Pro", "REDMI K100 Pro", "athens", "REDMI", "Redmi K系列", "phone"),
        RomDevice("REDMI K100 Pro Max", "REDMI K100 Pro Max", "songyuan", "REDMI", "Redmi K系列", "phone"),
        RomDevice("Redmi 平板", "Redmi Pad", "yunluo", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("Redmi 平板 SE", "Redmi Pad SE", "xun", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("Redmi 平板 SE 4G 8.7", "Redmi Pad SE 4G 8.7", "spark", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("Redmi Pad SE 8.7 Wi-Fi", "Redmi Pad SE 8.7 Wi-Fi", "flare", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("REDMI Pad 2 SE", "REDMI Pad 2 SE", "guitar", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("REDMI Pad 2 SE 4G", "REDMI Pad 2 SE 4G", "erhu", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("Redmi Pad Pro / 哈利·波特版", "Redmi Pad Pro / Special Edition featuring Harry Potter", "dizi", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("Redmi Pad Pro 5G", "Redmi Pad Pro 5G", "ruan", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("Redmi 平板 2 4G", "Redmi Pad 2 4G", "koto", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("REDMI 平板 2", "REDMI Pad 2", "taiko", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("REDMI 平板 2 Pro", "REDMI Pad 2 Pro", "flute", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("REDMI 平板 2 Pro 5G", "REDMI Pad 2 Pro 5G", "organ", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("REDMI K Pad", "REDMI K Pad", "turner", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("REDMI K Pad 2", "REDMI K Pad 2", "yili", "REDMI", "REDMI 平板系列", "phone"),
        RomDevice("Redmi A4 5G", "Redmi A4 5G", "warm", "REDMI", "Redmi A系列", "phone"),
        RomDevice("REDMI A5", "REDMI A5", "serenity", "REDMI", "Redmi A系列", "phone"),
        RomDevice("REDMI A7 Pro", "REDMI A7 Pro", "arctic", "REDMI", "Redmi A系列", "phone"),

        // POCO 系列（46 款）
        RomDevice("POCO C55", "POCO C55", "earth", "POCO", "POCO C系列", "phone"),
        RomDevice("POCO C65", "POCO C65", "gale", "POCO", "POCO C系列", "phone"),
        RomDevice("POCO C71", "POCO C71", "serenity", "POCO", "POCO C系列", "phone"),
        RomDevice("POCO C75 5G", "POCO C75 5G", "warm", "POCO", "POCO C系列", "phone"),
        RomDevice("POCO C85", "POCO C85", "dew", "POCO", "POCO C系列", "phone"),
        RomDevice("POCO C85x 5G", "POCO C85x 5G", "somalia", "POCO", "POCO C系列", "phone"),
        RomDevice("POCO C85", "POCO C85", "dew", "POCO", "POCO C系列", "phone"),
        RomDevice("POCO F3", "POCO F3", "alioth", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F3 GT", "POCO F3 GT", "ares", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F4", "POCO F4", "munch", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F4 GT", "POCO F4 GT", "ingres", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F5", "POCO F5", "marble", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F5 Pro", "POCO F5 Pro", "mondrian", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F6", "POCO F6", "peridot", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F6 Pro", "POCO F6 Pro", "vermeer", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F7", "POCO F7", "onyx", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F7 Pro", "POCO F7 Pro", "zorn", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F7 Ultra", "POCO F7 Ultra", "miro", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F8 Pro", "POCO F8 Pro", "annibale", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO F8 Ultra", "POCO F8 Ultra", "myron", "POCO", "POCO F系列", "phone"),
        RomDevice("POCO M4 Pro 4G", "POCO M4 Pro 4G", "fleur", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M4 5G", "POCO M4 5G", "light", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M4 Pro 5G", "POCO M4 Pro 5G", "evergreen", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M5", "POCO M5", "rock", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M6 4G", "POCO M6 4G", "moon", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M6 5G", "POCO M6 5G", "air", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M6 Plus 5G", "POCO M6 Plus 5G", "breeze", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M6 Pro 4G", "POCO M6 Pro 4G", "emerald", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M6 Pro 5G", "POCO M6 Pro 5G", "sky", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M7 5G", "POCO M7 5G", "flame", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO M7 Pro 5G", "POCO M7 Pro 5G", "beryl", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO X4 GT / Pro", "POCO X4 GT / Pro", "xaga", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO M8 Pro", "POCO M8 Pro", "flourite", "POCO", "POCO M系列", "phone"),
        RomDevice("POCO X4 Pro 5G", "POCO X4 Pro 5G", "veux", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO X5 Pro 5G", "POCO X5 Pro 5G", "redwood", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO X5 5G", "POCO X5 5G", "moonstone", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO X6 Neo 5G", "POCO X6 Neo 5G", "gold", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO X6 5G", "POCO X6 5G", "garnet", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO X6 Pro 5G", "POCO X6 Pro 5G", "duchamp", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO X7", "POCO X7", "malachite", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO X7 Pro", "POCO X7 Pro", "rodin", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO X8 Pro", "POCO X8 Pro", "klee", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO X8 Pro Max", "POCO X8 Pro Max", "dash", "POCO", "POCO X系列", "phone"),
        RomDevice("POCO Pad", "POCO Pad", "dizi", "POCO", "POCO Pad系列", "phone"),
        RomDevice("POCO Pad X1", "POCO Pad X1", "uke", "POCO", "POCO Pad系列", "phone"),
        RomDevice("POCO Pad 5G", "POCO Pad 5G", "ruan", "POCO", "POCO Pad系列", "phone"),
        )
    }

    /**
     * 简单的 HTTP GET
     */
    private fun httpGet(urlStr: String, timeoutMs: Int = 10000): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取当前设备代号
     */
    fun getCurrentDeviceCodename(): String {
        return android.os.Build.DEVICE ?: ""
    }

    /**
     * 获取当前设备型号
     */
    fun getCurrentDeviceModel(): String {
        return android.os.Build.MODEL ?: ""
    }
}
