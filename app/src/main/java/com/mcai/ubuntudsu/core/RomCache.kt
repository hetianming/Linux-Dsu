package com.mcai.ubuntudsu.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * ROM 固件数据的本地缓存（内存 → 磁盘 → 网络）。
 *
 * 为什么要缓存：设备列表源 raw.githubusercontent.com 在国内经常 15 秒超时，
 * 欧加目录 1.1MB / 1600+ 条也要 10 秒以上。每次进页面都同步等待，就成了
 * 「正在加载设备列表...」长时间空屏。
 *
 * 策略：进页面先用内存/磁盘缓存（或内置列表）**立即渲染**，再后台静默刷新。
 */
object RomCache {

    private const val FILE_DEVICES = "rom_devices.json"
    private const val FILE_YULE = "rom_yule.json"
    private const val DIR_VERSIONS = "rom_versions"

    private const val TTL_DEVICES = 12 * 60 * 60 * 1000L   // 12 小时
    private const val TTL_YULE = 12 * 60 * 60 * 1000L
    private const val TTL_VERSIONS = 6 * 60 * 60 * 1000L

    @Volatile private var memDevices: List<RomDevice>? = null
    @Volatile private var memDevicesAt = 0L
    @Volatile private var memYule: List<YuleRomEntry>? = null
    @Volatile private var memYuleAt = 0L

    private val memVersions = HashMap<String, Pair<Long, List<RomVersion>>>()

    private val warmScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // ==================== 设备列表 ====================

    /** 内存命中，否则读磁盘；都没有返回 null */
    fun devices(context: Context): List<RomDevice>? {
        memDevices?.let { return it }
        val f = File(context.filesDir, FILE_DEVICES)
        if (!f.exists() || f.length() == 0L) return null
        val list = runCatching { RomApi.parseDevicesBody(f.readText()) }.getOrNull()
        if (list.isNullOrEmpty()) return null
        memDevices = list
        memDevicesAt = f.lastModified()
        return list
    }

    fun devicesFresh(context: Context): Boolean {
        if (memDevices == null) return false
        val at = if (memDevicesAt > 0) memDevicesAt else 0L
        return at > 0 && System.currentTimeMillis() - at < TTL_DEVICES
    }

    fun putDevices(context: Context, body: String, list: List<RomDevice>) {
        memDevices = list
        memDevicesAt = System.currentTimeMillis()
        writeFile(File(context.filesDir, FILE_DEVICES), body)
    }

    // ==================== 欧加目录 ====================

    fun yule(context: Context): List<YuleRomEntry>? {
        memYule?.let { return it }
        val f = File(context.filesDir, FILE_YULE)
        if (!f.exists() || f.length() == 0L) return null
        val list = runCatching { RomApi.parseYuleBody(f.readText()) }.getOrNull()
        if (list.isNullOrEmpty()) return null
        memYule = list
        memYuleAt = f.lastModified()
        return list
    }

    fun yuleFresh(context: Context): Boolean {
        if (memYule == null) return false
        val at = if (memYuleAt > 0) memYuleAt else 0L
        return at > 0 && System.currentTimeMillis() - at < TTL_YULE
    }

    fun putYule(context: Context, body: String, list: List<YuleRomEntry>) {
        memYule = list
        memYuleAt = System.currentTimeMillis()
        writeFile(File(context.filesDir, FILE_YULE), body)
    }

    // ==================== 单设备版本列表 ====================

    fun versions(context: Context, codename: String): List<RomVersion>? {
        memVersions[codename]?.let { if (it.second.isNotEmpty()) return it.second }
        val f = versionFile(context, codename)
        if (!f.exists() || f.length() == 0L) return null
        val list = runCatching { RomApi.parseVersionsBody(f.readText()) }.getOrNull()
        if (list.isNullOrEmpty()) return null
        memVersions[codename] = f.lastModified() to list
        return list
    }

    fun versionsFresh(context: Context, codename: String): Boolean {
        val at = memVersions[codename]?.first ?: versionFile(context, codename).lastModified()
        return at > 0 && System.currentTimeMillis() - at < TTL_VERSIONS
    }

    fun putVersions(context: Context, codename: String, body: String, list: List<RomVersion>) {
        memVersions[codename] = System.currentTimeMillis() to list
        writeFile(versionFile(context, codename), body)
    }

    private fun versionFile(context: Context, codename: String): File {
        val dir = File(context.filesDir, DIR_VERSIONS)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, codename.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
    }

    // ==================== 预热 ====================

    /** 进程启动时后台把磁盘缓存读进内存，进页面时就是 0 等待 */
    fun warmUp(context: Context) {
        warmScope.launch {
            runCatching { devices(context.applicationContext) }
            runCatching { yule(context.applicationContext) }
        }
    }

    private fun writeFile(f: File, body: String) {
        runCatching {
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) {
                f.writeText(body)
                tmp.delete()
            }
        }
    }
}
