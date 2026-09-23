package com.mcai.ubuntudsu.core

import android.content.Context
import android.hardware.usb.UsbManager
import java.util.Locale

/**
 * OTG 助手核心：本机作为 OTG 主机，对通过 OTG 外接的「目标设备」执行操作。
 *
 * 与「本机」无关：所有 fastboot / adb 命令均针对外接线连接的目标设备，
 * 9008/EDL 通过 USB VID/PID 枚举识别。
 *
 * 依赖外接工具 adb / fastboot / edl：
 * - 优先使用 APK 内嵌的 ARM64 二进制（assets/tools/adb_arm64, fastboot_arm64）；
 * - 否则回退到 Android 系统或 Termux 中已安装的可执行文件；
 * - 最后回退到 Ubuntu(rootfs) 内，通过 chroot 运行（自动挂载 /dev /proc /sys 以访问 USB）。
 */
object OtgAssistant {

    data class Partition(val name: String, val type: String, val sizeBytes: Long)

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
    }

    // 已知的 9008 / EDL 模式 USB 标识
    private val EDL_IDS = mapOf(
        (0x05C6 to 0x9008) to "Qualcomm 9008 (EDL)",
        (0x05C6 to 0x900E) to "Qualcomm 900E (EDL/DIAG)",
        (0x05C6 to 0x9006) to "Qualcomm 9006 (DIAG)",
        (0x0E8D to 0x0003) to "MediaTek BootROM (EDL)",
        (0x0E8D to 0x2000) to "MediaTek Preloader",
        (0x0E8D to 0x2001) to "MediaTek Preloader",
    )

    @Volatile
    private var toolCache: Map<String, Tool> = emptyMap()

    fun clearToolCache() {
        toolCache = emptyMap()
    }

    // ==================== 工具定位与执行 ====================

    private fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** 在 Android 系统 / Termux / Ubuntu(rootfs) / 内嵌工具中查找可执行文件。 */
    fun tool(ctx: Context, name: String): Tool {
        toolCache[name]?.let { return it }
        val rootfs = Env.rootfs(ctx).absolutePath

        // 1) 优先使用内嵌工具（来自 APK assets，ARM64）
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

    /** 验证工具实际可执行：用 -h / --version 快速测试。 */
    fun testTool(ctx: Context, name: String): Boolean {
        val t = tool(ctx, name)
        if (!t.available) return false
        val arg = if (name == "adb" || name == "fastboot") "--version" else "-h"
        return runCatching {
            val r = run(ctx, name, listOf(arg), timeoutMs = 10000)
            r.code != 127
        }.getOrDefault(false)
    }

    /** 生成可直接拼接参数的命令前缀；Ubuntu 工具会自动挂载依赖目录并 chroot。 */
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

    /** 执行某个外接工具命令；args 由调用方提供并自动做安全引用。 */
    fun run(ctx: Context, name: String, args: List<String>, timeoutMs: Long = 60000): ShellResult {
        val t = tool(ctx, name)
        val prefix = commandPrefix(ctx, t)
            ?: return ShellResult(127, "", "未找到可执行文件 $name，请先在 Ubuntu 或 Termux 中安装")
        val command = prefix + " " + args.joinToString(" ") { shq(it) }
        return RootShell.exec(command, timeoutMs = timeoutMs)
    }

    private fun serialize(serial: String?): List<String> =
        if (serial.isNullOrBlank()) emptyList() else listOf("-s", serial)

    // ==================== 连接状态检测 ====================

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
                    String.format(
                        Locale.US, "%s [%04X:%04X]", known, device.vendorId, device.productId,
                    ),
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

    // ==================== Fastboot ====================

    fun fastbootGetvarAll(ctx: Context, serial: String?): List<Pair<String, String>> {
        if (!tool(ctx, "fastboot").available) return emptyList()
        val result = run(ctx, "fastboot", serialize(serial) + listOf("getvar", "all"), timeoutMs = 30000)
        val text = result.stdout + "\n" + result.stderr
        val regex = Regex("""^(?:\(bootloader\)\s*)?([A-Za-z0-9_.:\-]+):\s(.+)$""")
        val vars = ArrayList<Pair<String, String>>()
        text.lineSequence().forEach { raw ->
            val match = regex.find(raw.trim()) ?: return@forEach
            val key = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (key.equals("time", true) || key.startsWith("Finished")) return@forEach
            vars.add(key to value)
        }
        return vars
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

    fun fastbootGetvar(ctx: Context, serial: String?, name: String): String? {
        if (!tool(ctx, "fastboot").available) return null
        val result = run(ctx, "fastboot", serialize(serial) + listOf("getvar", name), timeoutMs = 20000)
        val text = result.stdout + "\n" + result.stderr
        val match = Regex("""${Regex.escape(name)}:\s*(\S+)""").find(text) ?: return null
        return match.groupValues[1].trim()
    }

    fun fastbootFlash(ctx: Context, serial: String?, partition: String, image: String): ShellResult =
        run(ctx, "fastboot", serialize(serial) + listOf("flash", partition, image), timeoutMs = 900000)

    fun fastbootUnlock(ctx: Context, serial: String?): ShellResult {
        val first = run(ctx, "fastboot", serialize(serial) + listOf("flashing", "unlock"), timeoutMs = 90000)
        if (first.success) return first
        return run(ctx, "fastboot", serialize(serial) + listOf("oem", "unlock"), timeoutMs = 90000)
    }

    private val UNLOCK_CMD_MAP = listOf(
        listOf("oem", "unlock"),
        listOf("oem", "unlock-go"),
        listOf("flashing", "unlock"),
        listOf("flashing", "unlock_critical"),
        listOf("bbk", "unlock"),
    )

    private val LOCK_CMD_MAP = listOf(
        listOf("flashing", "lock"),
        listOf("oem", "lock"),
    )

    fun fastbootUnlockCmd(ctx: Context, serial: String?, cmdIndex: Int): ShellResult {
        val args = UNLOCK_CMD_MAP.getOrElse(cmdIndex) { return ShellResult(-1, "", "无效命令索引 $cmdIndex") }
        return run(ctx, "fastboot", serialize(serial) + args, timeoutMs = 90000)
    }

    fun fastbootLockCmd(ctx: Context, serial: String?, cmdIndex: Int): ShellResult {
        val args = LOCK_CMD_MAP.getOrElse(cmdIndex) { return ShellResult(-1, "", "无效命令索引 $cmdIndex") }
        return run(ctx, "fastboot", serialize(serial) + args, timeoutMs = 90000)
    }

    fun fastbootLock(ctx: Context, serial: String?): ShellResult {
        val first = run(ctx, "fastboot", serialize(serial) + listOf("flashing", "lock"), timeoutMs = 90000)
        if (first.success) return first
        return run(ctx, "fastboot", serialize(serial) + listOf("oem", "lock"), timeoutMs = 90000)
    }

    fun fastbootSetActive(ctx: Context, serial: String?, slot: String): ShellResult {
        val first = run(ctx, "fastboot", serialize(serial) + listOf("--set-active=$slot"), timeoutMs = 30000)
        if (first.success) return first
        return run(ctx, "fastboot", serialize(serial) + listOf("set_active", slot), timeoutMs = 30000)
    }

    fun fastbootReboot(ctx: Context, serial: String?, target: String): ShellResult {
        val args = when (target) {
            "system" -> listOf("reboot")
            "bootloader" -> listOf("reboot-bootloader")
            "recovery" -> listOf("reboot", "recovery")
            "fastbootd" -> listOf("reboot", "fastboot")
            "edl" -> listOf("oem", "edl")
            else -> listOf("reboot", target)
        }
        return run(ctx, "fastboot", serialize(serial) + args, timeoutMs = 30000)
    }

    // ==================== ADB ====================

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

    fun adbKillServer(ctx: Context): ShellResult =
        run(ctx, "adb", listOf("kill-server"), timeoutMs = 20000)

    // ==================== 工具函数 ====================

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
}
