package com.mcai.ubuntudsu.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// 在线更新：Gitee（国内直连）+ GitHub 双源检查，下载 APK 并静默安装
object AppUpdater {
    // 两个常量需与仓库实际 owner/repo 一致；Gitee 侧需手动创建同名仓库并发布同 tag Release
    private const val GITHUB_API = "https://api.github.com/repos/hetianming/Linux-Dsu/releases/latest"
    private const val GITEE_API = "https://gitee.com/api/v5/repos/hetianming/Linux-Dsu/releases/latest"

    data class ReleaseInfo(
        val version: String,        // tag 名，如 v1.0.3
        val notes: String,          // release 说明
        val apkUrl: String,         // apk 下载直链
        val apkName: String,        // apk 文件名
        val apkSize: Long,          // apk 大小（字节）
    )

    // 并行请求 Gitee 与 GitHub：Gitee 国内秒回，GitHub 常超时，互为兜底；
    // 两边都成功时取版本较高者，版本相同优先 Gitee（下载线路国内更稳）
    fun fetchLatest(): ReleaseInfo? {
        var gitee: ReleaseInfo? = null
        var github: ReleaseInfo? = null
        val tg = Thread { gitee = fetchFrom(GITEE_API) }
        val th = Thread { github = fetchFrom(GITHUB_API) }
        tg.start(); th.start()
        tg.join(15000); th.join(15000)
        return when {
            gitee == null -> github
            github == null -> gitee
            isNewer(gitee!!.version, github!!.version) -> gitee
            isNewer(github!!.version, gitee!!.version) -> github
            else -> gitee
        }
    }

    // 拉取单个源的 latest Release；Gitee/GitHub JSON 结构一致
    // Gitee 资产无 size 字段（apkSize=-1），下载后自动跳过大小校验
    private fun fetchFrom(apiUrl: String): ReleaseInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UbuntuDSU-Updater")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
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
            null
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
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

    // GitHub Release 直链会 302 到 release-assets.githubusercontent.com，该域名在国内常被静默丢包。
    // 直链无进展时依次切换公共加速线路，保证国内可下载（安装时系统仍会校验签名，镜像无法伪造）。
    // 列表内的镜像均实测过 206 Range 可用；镜像失效时直链逻辑仍可兜底，替换即可。
    private val gitHubMirrors = listOf(
        "https://ghproxy.net/",
        "https://ghfast.top/",
    )

    // 下载线路：直链优先，GitHub 资产再追加加速镜像
    fun urlCandidates(apkUrl: String): List<String> {
        val list = mutableListOf(apkUrl)
        if (apkUrl.startsWith("https://github.com/")) {
            gitHubMirrors.forEach { list.add(it + apkUrl) }
        }
        return list
    }

    // 下载 apk：逐线路尝试 aria2c（多线程提速）→ HttpURLConnection 兜底；
    // 每条线路校验文件大小，全部失败返回 null，绝不向上抛出
    fun download(
        ctx: Context,
        info: ReleaseInfo,
        targetDir: File,
        onProgress: (Int) -> Unit,
        onLog: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): File? {
        val target = File(targetDir, "update-${info.version}.apk")
        if (!targetDir.exists() && !targetDir.mkdirs()) return null
        // GitHub 资产自带 size；Gitee 资产无 size 字段，用 Range 探测真实大小
        // （供无 Content-Length 的线路按已下载字节计算百分比，并做完整性校验）
        val expectedSize = if (info.apkSize > 0) info.apkSize else probeContentLength(info.apkUrl)
        val candidates = urlCandidates(info.apkUrl)
        for ((index, url) in candidates.withIndex()) {
            if (isCancelled()) return null
            if (index > 0) {
                onLog?.invoke("直链无进展，切换备用线路 $index/${candidates.size - 1}")
                // 跨线路不复用断点文件，避免续传错位导致文件损坏
                runCatching { target.delete() }
                runCatching { File(targetDir, "${target.name}.aria2").delete() }
            }
            onProgress(0)
            onLog?.invoke("线路 ${index + 1}/${candidates.size}：开始下载")
            var file = downloadWithAria2(ctx, url, target, expectedSize, onProgress, onLog, isCancelled)
            if (isCancelled()) return null
            if (file == null) file = downloadWithHttp(url, target, expectedSize, onProgress, isCancelled)
            if (isCancelled()) return null
            if (file != null && file.isFile && file.length() > 0 &&
                (expectedSize <= 0 || file.length() == expectedSize)
            ) {
                onProgress(100)
                return file
            }
            if (file != null) {
                onLog?.invoke("文件大小校验不通过（${file.length()} / $expectedSize），重试其它线路")
            }
        }
        return null
    }

    // Range 0-0 探测真实大小（部分服务器拒绝 HEAD）；Gitee 附件经重定向响应 Content-Range
    private fun probeContentLength(url: String): Long {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UbuntuDSU-Updater")
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.connect()
            if (connection.responseCode == 206) {
                Regex("""bytes\s+\d+-\d+/(\d+)""")
                    .find(connection.getHeaderField("Content-Range") ?: "")
                    ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            } else if (connection.responseCode in 200..299) {
                connection.contentLengthLong
            } else -1L
        } catch (e: Exception) {
            -1L
        } finally {
            connection?.disconnect()
        }
    }

    // aria2c 下载（内置二进制免 root 优先，失败经 root 兜底；目标在 app 私有目录始终可写）
    private fun downloadWithAria2(
        ctx: Context,
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
        onLog: ((String) -> Unit)?,
        isCancelled: () -> Boolean,
    ): File? {
        val result = Aria2c.download(
            ctx, url, target, onProgress,
            isCancelled = isCancelled, onLog = onLog, expectedSize = expectedSize,
        )
        if (!result.success) onLog?.invoke("aria2c 失败：${result.message}")
        return if (result.success && target.isFile && target.length() > 0) target else null
    }

    // HttpURLConnection 下载（兜底，aria2c 不可用时）
    private fun downloadWithHttp(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): File? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            // 单次 read 超时对齐 aria2c 的无进展判定，避免慢速/被阻断线路长时间无响应
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UbuntuDSU-Updater")
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val total = if (expectedSize > 0) expectedSize else connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        if (isCancelled()) return null
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
            return target
        } catch (e: Exception) {
            runCatching { target.delete() }
            return null
        } finally {
            connection?.disconnect()
        }
    }

    data class InstallResult(val success: Boolean, val message: String)

    // root 静默安装（应用商店式后台安装）：su -c pm install -r；无 root 或失败返回 false + 原因
    // pm 以 root 身份读取 app 私有目录中的 apk 并流式写入安装会话，绕开私目录权限限制
    fun silentInstall(apk: File, timeoutMs: Long = 180000): InstallResult {
        if (!apk.isFile) return InstallResult(false, "安装包不存在")
        val result = runCatching {
            RootShell.exec("pm install -r '${apk.absolutePath}'", timeoutMs = timeoutMs)
        }.getOrNull() ?: return InstallResult(false, "无法执行 su（未获得 root 权限）")
        val output = (result.stdout + "\n" + result.stderr).trim()
        val ok = result.success && output.contains("Success", ignoreCase = true)
        if (ok) return InstallResult(true, "安装成功")
        // 常见失败给出人话提示，便于定位（签名不一致是本项目自构建包覆盖官方包时的典型问题）
        val friendly = when {
            output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || output.contains("signatures do not match") ->
                "签名与已安装版本不一致，无法覆盖安装。需先卸载旧版再安装，或使用同一签名打包。"
            output.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
                "线上包版本号不高于当前版本，系统拒绝降级安装。"
            output.contains("INSTALL_PARSE_FAILED") -> "安装包损坏或不完整，请重新下载。"
            else -> output.ifBlank { "安装失败（退出码 ${result.code}）" }
        }
        return InstallResult(false, friendly.take(300))
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
