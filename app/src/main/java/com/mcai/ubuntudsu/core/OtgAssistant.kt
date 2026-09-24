package com.mcai.ubuntudsu.core

import android.content.Context
import android.hardware.usb.UsbManager
import java.util.Locale

/**
 * OTG 刷机助手核心：支持 ADB + Fastboot + EDL 全功能
 *
 * 工具来源优先级：
 * 1. APK 内嵌 ARM64 二进制（assets/tools/）
 * 2. Android 系统路径（/system/bin, /vendor/bin）
 * 3. Termux 路径
 * 4. Ubuntu rootfs chroot 环境
 */
object OtgAssistant {

    // ==================== 数据类 ====================

    data class Partition(val name: String, val type: String, val sizeBytes: Long) {
        val sizeStr get() = formatBytes(sizeBytes)
    }

    data class UsbDeviceInfo(
        val vendorId: Int,
        val productId: Int,
        val productName: String,
        val manufacturer: String,
        val deviceClass: Int,
    )

    data class Tool(val name: String, val path: String?, val location: String) {
        val available: Boolean get() = path != null
    }

    data class ConnectionStatus(
        val fastbootDevices: List<String>,
        val adbDevices: List<String>,
        val edlDevices: List<String>,
        val usbDevices: List<UsbDeviceInfo>,
    ) {
        val fastbootConnected: Boolean get() = fastbootDevices.isNotEmpty()
        val adbConnected: Boolean get() = adbDevices.isNotEmpty()
        val edlConnected: Boolean get() = edlDevices.isNotEmpty()
        val anyConnected: Boolean get() = fastbootConnected || adbConnected || edlConnected
    }

    data class FastbootVar(val key: String, val value: String)

    // ==================== 常量 ====================

    private val EDL_IDS = mapOf(
        (0x05C6 to 0x9008) to "Qualcomm 9008 (EDL)",
        (0x05C6 to 0x900E) to "Qualcomm 900E (EDL/DIAG)",
        (0x05C6 to 0x9006) to "Qualcomm 9006 (DIAG)",
        (0x0E8D to 0x0003) to "MediaTek BootROM (EDL)",
        (0x0E8D to 0x2000) to "MediaTek Preloader",
        (0x0E8D to 0x2001) to "MediaTek Preloader",
        (0x0B05 to 0x41E1) to "Asus EDL",
        (0x18D1 to 0xD00D) to "Google EDL",
    )

    // 常见分区列表（用于快速选择）
    val COMMON_PARTITIONS = listOf(
        "boot", "dtbo", "dlkm", "exynosabl", "fota", "fsbr0", "fsbr1",
        "gcf", "gpmem", "lk", "lksecapp", "modem", "oauth", "osp",
        "product", "qupfw", "rpmb", "splash", "tz", "uefi", "uefisecapp",
        "userdata", "vbmeta", "vendor", "vendor_boot", "aboot",
        "system", "system_ext", "persist", "cache", "misc",
        "recovery", "bootloader", "radio", "secure",
    )

    // 品牌解锁命令映射
    data class UnlockOption(val label: String, val commands: List<List<String>>, val warning: String = "")

    val UNLOCK_OPTIONS = listOf(
        UnlockOption("通用 - flashing unlock", listOf(listOf("flashing", "unlock")), "标准解锁命令，适用于大多数设备"),
        UnlockOption("通用 - oem unlock", listOf(listOf("oem", "unlock")), "部分设备使用 OEM 解锁命令"),
        UnlockOption("小米/红米 - oem unlock-go", listOf(listOf("oem", "unlock-go")), "小米设备专用解锁命令"),
        UnlockOption("Google Pixel - flashing unlock_critical", listOf(listOf("flashing", "unlock"), listOf("flashing", "unlock_critical")), "Pixel 需要两步解锁"),
        UnlockOption("OPPO/一加/realme", listOf(listOf("flashing", "unlock")), "ColorOS 设备解锁"),
        UnlockOption("联想", listOf(listOf("oem", "unlock"), listOf("oem", "unlock-go")), "Lenovo 设备解锁"),
        UnlockOption("BBK (OPPO/vivo)", listOf(listOf("bbk", "unlock")), "BBK 系设备解锁"),
    )

    @Volatile
    private var toolCache: Map<String, Tool> = emptyMap()

    fun clearToolCache() { toolCache = emptyMap() }

    // ==================== 工具定位 ====================

    private fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun tool(ctx: Context, name: String): Tool {
        toolCache[name]?.let { return it }
        val rootfs = Env.rootfs(ctx).absolutePath

        // 1) 内嵌工具
        ToolInstaller.toolPath(ctx, name)?.let { p ->
            val resolved = Tool(name, p, "内置")
            toolCache = toolCache + (name to resolved)
            return resolved
        }

        // 2) 系统/Termux 路径
        val script = """
            for p in /system/bin /system/xbin /vendor/bin /data/data/com.termux/files/usr/bin; do
              if [ -x "${'$'}p/$name" ]; then echo "SYS:${'$'}p/$name"; exit 0; fi
            done
            for p in /usr/bin /usr/local/bin /usr/sbin /usr/local/sbin /bin /sbin; do
              if [ -x "$rootfs${'$'}p/$name" ]; then echo "ROOTFS:${'$'}p/$name"; exit 0; fi
            done
            echo "NONE"
        """.trimIndent()
        val out = RootShell.exec(script, timeoutMs = 12000).stdout
        val line = out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("SYS:") || it.startsWith("ROOTFS:") || it == "NONE" }
            ?: "NONE"
        val resolved = when {
            line.startsWith("SYS:") -> Tool(name, line.removePrefix("SYS:"), "系统")
            line.startsWith("ROOTFS:") -> Tool(name, line.removePrefix("ROOTFS:"), "Ubuntu")
            else -> Tool(name, null, "")
        }
        toolCache = toolCache + (name to resolved)
        return resolved
    }

    fun testTool(ctx: Context, name: String): Boolean {
        val t = tool(ctx, name)
        if (!t.available) return false
        val arg = if (name == "adb" || name == "fastboot") "--version" else "-h"
        return runCatching {
            val r = run(ctx, name, listOf(arg), timeoutMs = 10000)
            r.code != 127
        }.getOrDefault(false)
    }

    private fun commandPrefix(ctx: Context, tool: Tool): String? {
        val path = tool.path ?: return null
        if (tool.location != "Ubuntu") return shq(path)
        val rootfs = Env.rootfs(ctx).absolutePath
        return """
            R=${shq(rootfs)}
            /system/bin/toybox mkdir -p "${'$'}R/dev" "${'$'}R/proc" "${'$'}R/sys" "${'$'}R/root/.android" 2>/dev/null
            mount_if() {
              _mnt="${'$'}1"; shift
              while read -r _dev _mp _opts; do
                [ "${'$'}_mp" = "${'$'}_mnt" ] && return 0
              done < /proc/mounts
              /system/bin/toybox mount "${'$'}@" "${'$'}_mnt" 2>/dev/null || true
            }
            mount_if "${'$'}R/dev" --bind /dev
            mount_if "${'$'}R/proc" -t proc proc
            mount_if "${'$'}R/sys" -t sysfs sysfs
            /system/bin/toybox chroot "${'$'}R" /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root TERM=xterm ${shq(path)}
        """.trimIndent()
    }

    fun run(ctx: Context, name: String, args: List<String>, timeoutMs: Long = 60000): ShellResult {
        val t = tool(ctx, name)
        val prefix = commandPrefix(ctx, t)
            ?: return ShellResult(127, "", "未找到可执行文件 $name，请先在 Ubuntu 或 Termux 中安装")
        val command = prefix + " " + args.joinToString(" ") { shq(it) }
        return RootShell.exec(command, timeoutMs = timeoutMs)
    }

    private fun serialize(serial: String?): List<String> =
        if (serial.isNullOrBlank()) emptyList() else listOf("-s", serial)

    // ==================== 连接检测 ====================

    fun usbDevices(ctx: Context): List<UsbDeviceInfo> {
        val manager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return manager.deviceList.values.map { device ->
            UsbDeviceInfo(
                vendorId = device.vendorId,
                productId = device.productId,
                productName = device.productName.orEmpty(),
                manufacturer = device.manufacturerName.orEmpty(),
                deviceClass = device.deviceClass,
            )
        }
    }

    fun fastbootDevices(ctx: Context): List<String> {
        if (!tool(ctx, "fastboot").available) return emptyList()
        val out = run(ctx, "fastboot", listOf("devices"), timeoutMs = 20000).stdout
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.split(Regex("\\s+"))[0] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    fun adbDevices(ctx: Context): List<String> {
        if (!tool(ctx, "adb").available) return emptyList()
        val out = run(ctx, "adb", listOf("devices", "-l"), timeoutMs = 20000).stdout
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("List of devices") }
            .map { it.split(Regex("\\s+"))[0] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    fun edlDevices(ctx: Context): List<String> {
        val result = ArrayList<String>()
        usbDevices(ctx).forEach { device ->
            val known = EDL_IDS[device.vendorId to device.productId]
            val readable = listOf(device.manufacturer, device.productName).joinToString(" ").trim()
            when {
                known != null -> result.add(
                    String.format(Locale.US, "%s [%04X:%04X]", known, device.vendorId, device.productId)
                )
                device.productName.contains("EDL", true) ||
                    device.productName.contains("9008", true) ||
                    device.manufacturer.contains("Qualcomm", true) ||
                    device.manufacturer.contains("MediaTek", true) ->
                    result.add(readable.ifBlank { String.format(Locale.US, "%04X:%04X", device.vendorId, device.productId) })
            }
        }
        return result
    }

    fun connectionStatus(ctx: Context): ConnectionStatus = ConnectionStatus(
        fastbootDevices = fastbootDevices(ctx),
        adbDevices = adbDevices(ctx),
        edlDevices = edlDevices(ctx),
        usbDevices = usbDevices(ctx),
    )

    // ==================== Fastboot 完整功能 ====================

    fun fastbootGetvarAll(ctx: Context, serial: String?): List<FastbootVar> {
        if (!tool(ctx, "fastboot").available) return emptyList()
        val result = run(ctx, "fastboot", serialize(serial) + listOf("getvar", "all"), timeoutMs = 30000)
        val text = result.stdout + "\n" + result.stderr
        val vars = ArrayList<FastbootVar>()
        val regex = Regex("""^(?:\(bootloader\)\s*)?([A-Za-z0-9_.:\-]+):\s*(.*)$""")
        text.lineSequence().forEach { raw ->
            val match = regex.find(raw.trim()) ?: return@forEach
            val key = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (key.equals("time", true) || key.startsWith("Finished")) return@forEach
            vars.add(FastbootVar(key, value))
        }
        return vars
    }

    fun fastbootGetvar(ctx: Context, serial: String?, name: String): String? {
        if (!tool(ctx, "fastboot").available) return null
        val result = run(ctx, "fastboot", serialize(serial) + listOf("getvar", name), timeoutMs = 20000)
        val text = result.stdout + "\n" + result.stderr
        val match = Regex("""${Regex.escape(name)}:\s*(\S+)""").find(text) ?: return null
        return match.groupValues[1].trim()
    }

    fun fastbootPartitions(ctx: Context, serial: String?): List<Partition> {
        val types = HashMap<String, String>()
        val sizes = HashMap<String, Long>()
        fastbootGetvarAll(ctx, serial).forEach { (key, value) ->
            when {
                key.startsWith("partition-type:") -> types[key.removePrefix("partition-type:")] = value
                key.startsWith("partition-size:") -> sizes[key.removePrefix("partition-size:")] = parseSize(value)
            }
        }
        return (types.keys + sizes.keys)
            .map { name -> Partition(name, types[name] ?: "-", sizes[name] ?: 0L) }
            .sortedBy { it.name }
    }

    // 刷入单个分区镜像
    fun fastbootFlash(ctx: Context, serial: String?, partition: String, image: String): ShellResult =
        run(ctx, "fastboot", serialize(serial) + listOf("flash", partition, image), timeoutMs = 900000)

    // 刷入并重启
    fun fastbootFlashAndReboot(ctx: Context, serial: String?, partition: String, image: String): ShellResult {
        val flashResult = fastbootFlash(ctx, serial, partition, image)
        if (!flashResult.success) return flashResult
        return fastbootReboot(ctx, serial, "system")
    }

    // 擦除分区
    fun fastbootErase(ctx: Context, serial: String?, partition: String): ShellResult =
        run(ctx, "fastboot", serialize(serial) + listOf("erase", partition), timeoutMs = 120000)

    // 设置启动槽
    fun fastbootSetActive(ctx: Context, serial: String?, slot: String): ShellResult {
        val first = run(ctx, "fastboot", serialize(serial) + listOf("--set-active=$slot"), timeoutMs = 30000)
        if (first.success) return first
        return run(ctx, "fastboot", serialize(serial) + listOf("set_active", slot), timeoutMs = 30000)
    }

    // 重启控制
    fun fastbootReboot(ctx: Context, serial: String?, target: String): ShellResult {
        val args = when (target) {
            "system" -> listOf("reboot")
            "bootloader" -> listOf("reboot-bootloader")
            "recovery" -> listOf("reboot", "recovery")
            "fastbootd" -> listOf("reboot", "fastboot")
            "edl" -> listOf("oem", "edl")
            "download" -> listOf("reboot", "download")
            "dfu" -> listOf("reboot", "dfu")
            else -> listOf("reboot", target)
        }
        return run(ctx, "fastboot", serialize(serial) + args, timeoutMs = 30000)
    }

    // 解锁 BL
    fun fastbootUnlockCmd(ctx: Context, serial: String?, commands: List<List<String>>): ShellResult {
        for (cmd in commands) {
            val result = run(ctx, "fastboot", serialize(serial) + cmd, timeoutMs = 90000)
            if (result.success) return result
        }
        return ShellResult(-1, "", "所有解锁命令均失败")
    }

    // 上锁 BL
    fun fastbootLockCmd(ctx: Context, serial: String?): ShellResult {
        val first = run(ctx, "fastboot", serialize(serial) + listOf("flashing", "lock"), timeoutMs = 90000)
        if (first.success) return first
        return run(ctx, "fastboot", serialize(serial) + listOf("oem", "lock"), timeoutMs = 90000)
    }

    // 刷入完整固件包（zip 解压后批量刷入）
    fun fastbootFlashZip(ctx: Context, serial: String?, zipPath: String): ShellResult {
        return run(ctx, "fastboot", serialize(serial) + listOf("flashall", "-p", zipPath), timeoutMs = 600000)
    }

    // ==================== ADB 完整功能 ====================

    fun adbConnect(ctx: Context, hostPort: String): ShellResult =
        run(ctx, "adb", listOf("connect", hostPort), timeoutMs = 30000)

    fun adbDisconnect(ctx: Context, hostPort: String): ShellResult {
        val args = if (hostPort.isBlank()) listOf("disconnect") else listOf("disconnect", hostPort)
        return run(ctx, "adb", args, timeoutMs = 20000)
    }

    fun adbPush(ctx: Context, serial: String?, local: String, remote: String): ShellResult =
        run(ctx, "adb", serialize(serial) + listOf("push", local, remote), timeoutMs = 1800000)

    fun adbPull(ctx: Context, serial: String?, remote: String, local: String): ShellResult =
        run(ctx, "adb", serialize(serial) + listOf("pull", remote, local), timeoutMs = 1800000)

    fun adbShell(ctx: Context, serial: String?, command: String): ShellResult =
        run(ctx, "adb", serialize(serial) + listOf("shell", command), timeoutMs = 60000)

    fun adbRoot(ctx: Context, serial: String?): ShellResult =
        run(ctx, "adb", serialize(serial) + listOf("root"), timeoutMs = 10000)

    fun adbReboot(ctx: Context, serial: String?, target: String): ShellResult {
        val args = when (target) {
            "bootloader" -> listOf("reboot", "bootloader")
            "recovery" -> listOf("reboot", "recovery")
            "edl" -> listOf("reboot", "edl")
            else -> listOf("reboot")
        }
        return run(ctx, "adb", serialize(serial) + args, timeoutMs = 10000)
    }

    fun adbInstall(ctx: Context, serial: String?, apkPath: String): ShellResult =
        run(ctx, "adb", serialize(serial) + listOf("install", "-r", apkPath), timeoutMs = 300000)

    fun adbShellSu(ctx: Context, serial: String?, command: String): ShellResult {
        // 通过 su 执行 root 命令
        return run(ctx, "adb", serialize(serial) + listOf("shell", "su", "-c", command), timeoutMs = 60000)
    }

    // ADB 设备信息查询
    fun adbDeviceInfo(ctx: Context, serial: String?): Map<String, String> {
        val info = HashMap<String, String>()
        val props = listOf(
            "ro.product.model" to "型号",
            "ro.product.brand" to "品牌",
            "ro.product.device" to "设备代号",
            "ro.board.platform" to "平台",
            "ro.build.version.release" to "Android 版本",
            "ro.build.version.sdk" to "SDK 版本",
            "ro.build.id" to "构建 ID",
            "ro.build.display.id" to "显示构建 ID",
            "ro.hardware" to "硬件",
            "ro.boot.serialno" to "序列号",
        )
        props.forEach { (prop, label) ->
            val result = adbShell(ctx, serial, "getprop $prop")
            info[label] = result.stdout.trim()
        }
        // 获取存储信息
        val storage = adbShell(ctx, serial, "df -h /sdcard 2>/dev/null || echo '无法获取'")
        info["存储"] = storage.stdout.trim()
        // 获取 CPU 信息
        val cpu = adbShell(ctx, serial, "cat /proc/cpuinfo | grep 'Hardware\\|Processor' | head -2")
        info["CPU"] = cpu.stdout.trim()
        return info
    }

    // ADB 进程管理
    fun adbProcesses(ctx: Context, serial: String?): String {
        val result = adbShell(ctx, serial, "ps -A | head -20")
        return result.stdout
    }

    fun adbKillProcess(ctx: Context, serial: String?, pid: String): ShellResult {
        return adbShell(ctx, serial, "kill $pid")
    }

    // ADB 文件管理
    fun adbListFiles(ctx: Context, serial: String?, path: String): String {
        val result = adbShell(ctx, serial, "ls -la $path 2>/dev/null || echo '无法列出目录'")
        return result.stdout
    }

    fun adbDeleteFile(ctx: Context, serial: String?, path: String): ShellResult {
        return adbShell(ctx, serial, "rm -f $path")
    }

    fun adbDeleteDir(ctx: Context, serial: String?, path: String): ShellResult {
        return adbShell(ctx, serial, "rm -rf $path")
    }

    // ADB 系统操作
    fun adbRebootToBootloader(ctx: Context, serial: String?): ShellResult {
        return adbShell(ctx, serial, "reboot bootloader")
    }

    fun adbRebootToRecovery(ctx: Context, serial: String?): ShellResult {
        return adbShell(ctx, serial, "reboot recovery")
    }

    fun adbScreenshot(ctx: Context, serial: String?, outputPath: String): ShellResult {
        return run(ctx, "adb", serialize(serial) + listOf("exec-out", "screencap", "-p", ">", outputPath), timeoutMs = 30000)
    }

    // ==================== 工具函数 ====================

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }

    private fun parseSize(raw: String): Long {
        val value = raw.trim()
        return when {
            value.startsWith("0x", true) -> value.substring(2).toLongOrNull(16) ?: 0L
            value.toLongOrNull() != null -> value.toLong()
            else -> 0L
        }
    }

    fun buildOutput(result: ShellResult): String {
        return buildString {
            if (result.stdout.isNotEmpty()) appendLine(result.stdout)
            if (result.stderr.isNotEmpty()) appendLine("[错误] ${result.stderr}")
            if (result.code != 0 && result.code != 127) appendLine("[退出码: ${result.code}]")
            if (result.code == 127) appendLine("[命令未找到]")
        }.trimEnd()
    }

    fun buildOutputSimple(result: ShellResult): String {
        return result.stdout.ifBlank {
            if (result.code == 127) "命令未找到"
            else if (result.code != 0) "[退出码: ${result.code}]"
            else ""
        }
    }
}
