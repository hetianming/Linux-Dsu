package com.mcai.ubuntudsu.core

import android.content.Context
import java.io.File

object ChrootRunner {
    private const val SU = "/system/bin/su"
    private const val SH = "/system/bin/sh"

    // root chroot 启动规格（已验证可用）：
    // /system/bin/su 0 提权，toybox 挂载 /dev /proc /sys /tmp，伪造音频 FIFO，
    // toybox chroot 进 rootfs 起 bash。依赖 root（Magisk/su）。
    // 返回 (executable, args)，供 TerminalSession 直接 execve
    fun terminalLaunchSpec(ctx: Context, rootfs: File): Pair<String, Array<String>> {
        val script = File(rootfs.parentFile, ".linux-dsu-chroot.sh")
        script.writeText(rootCommand(rootfs, Env.audioPipeHost(ctx)), Charsets.UTF_8)
        script.setReadable(true, false)
        script.setExecutable(true, false)

        // 同步写入国内 apt 源配置脚本（host 侧），由 chroot 启动脚本调用
        val mirrorScript = File(rootfs.parentFile, ".linux-dsu-apt-mirror.sh")
        mirrorScript.writeText(aptMirrorScript(), Charsets.UTF_8)
        mirrorScript.setReadable(true, false)
        mirrorScript.setExecutable(true, false)

        val scriptRef = script.path.replace("'", "'\\''")
        return SU to arrayOf(SU, "0", "-c", "sh '$scriptRef'")
    }

    // 国内 apt 源配置脚本：识别 /etc/os-release 的 ID + VERSION_CODENAME，
    // 为 Ubuntu 写入清华 ubuntu-ports（arm64 必须用 ports 路径），为 Debian 写入清华 debian + debian-security。
    // 仅当 rootfs 里尚未包含国内镜像域名时写入，避免覆盖用户手动修改的源。
    // 兼容传统 sources.list；清掉 deb822 格式 .sources 文件（Ubuntu 23.10+/Debian 13+）避免冲突。
    private fun aptMirrorScript(): String {
        // ${'$'} = 字面 $ 符号，供 shell 变量使用
        return """
#!/system/bin/sh
# 用法: .linux-dsu-apt-mirror.sh <rootfs 绝对路径>
set -e
ROOTFS="${'$'}1"
[ -z "${'$'}ROOTFS" ] && exit 0
OS_RELEASE="${'$'}ROOTFS/etc/os-release"
[ -f "${'$'}OS_RELEASE" ] || OS_RELEASE="${'$'}ROOTFS/usr/lib/os-release"
[ -f "${'$'}OS_RELEASE" ] || exit 0

. "${'$'}OS_RELEASE"
DISTRO="${'$'}ID"
CODENAME="${'$'}VERSION_CODENAME"
[ -n "${'$'}CODENAME" ] || exit 0

# 若现有源文件已包含国内镜像域名，说明已配置过，直接退出
EXISTING="${'$'}ROOTFS/etc/apt/sources.list"
if [ -f "${'$'}EXISTING" ] && /system/bin/toybox grep -q -E 'tuna\.tsinghua\.edu\.cn|mirrors\.ustc\.edu\.cn' "${'$'}EXISTING" 2>/dev/null; then
    exit 0
fi

case "${'$'}DISTRO" in
    ubuntu)
        [ -f "${'$'}EXISTING" ] && /system/bin/toybox cp -f "${'$'}EXISTING" "${'$'}EXISTING.dsu.bak" 2>/dev/null || true
        /system/bin/toybox printf '%s\n' \
            "# Ubuntu ${'$'}CODENAME - China mirrors (TUNA, auto-configured)" \
            "deb [arch=arm64] https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ ${'$'}CODENAME main restricted universe multiverse" \
            "deb [arch=arm64] https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ ${'$'}CODENAME-updates main restricted universe multiverse" \
            "deb [arch=arm64] https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ ${'$'}CODENAME-backports main restricted universe multiverse" \
            "deb [arch=arm64] https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ ${'$'}CODENAME-security main restricted universe multiverse" \
            > "${'$'}EXISTING"
        /system/bin/toybox rm -f "${'$'}ROOTFS/etc/apt/sources.list.d/ubuntu.sources" 2>/dev/null || true
        ;;
    debian)
        [ -f "${'$'}EXISTING" ] && /system/bin/toybox cp -f "${'$'}EXISTING" "${'$'}EXISTING.dsu.bak" 2>/dev/null || true
        /system/bin/toybox printf '%s\n' \
            "# Debian ${'$'}CODENAME - China mirrors (TUNA, auto-configured)" \
            "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ ${'$'}CODENAME main" \
            "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ ${'$'}CODENAME-updates main" \
            "deb https://mirrors.tuna.tsinghua.edu.cn/debian-security/ ${'$'}CODENAME-security main" \
            > "${'$'}EXISTING"
        /system/bin/toybox rm -f "${'$'}ROOTFS/etc/apt/sources.list.d/debian.sources" 2>/dev/null || true
        ;;
    *)
        exit 0
        ;;
esac

# 清掉 sources.list.d/ 里的官方源 .list 文件（防止与 sources.list 冲突）
for f in "${'$'}ROOTFS/etc/apt/sources.list.d/"*.list; do
    [ -f "${'$'}f" ] || continue
    /system/bin/toybox grep -q -E 'archive\.ubuntu\.com|security\.ubuntu\.com|deb\.debian\.org' "${'$'}f" 2>/dev/null && /system/bin/toybox rm -f "${'$'}f"
done

exit 0
"""
    }

    fun terminalEnvironment(): Array<String> = arrayOf(
        "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin",
        "TERM=xterm-256color",
        "HOME=/root",
    )

    fun terminalSetupCommand(rootfs: File): ByteArray = ByteArray(0)

    private fun rootCommand(rootfs: File, audioHost: File): String {
        val hostAudio = audioHost.absolutePath.replace("'", "'\\''")
        return """
            export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin
            set +x
            ROOTFS=${q(rootfs.absolutePath)}
            HOST_FILES=${q(File(rootfs.absolutePath, "..").absolutePath)}

            cleanup() {
              /system/bin/toybox umount "${'$'}ROOTFS/dev/pts" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/dev" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/proc" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/sys" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/tmp" 2>/dev/null
              /system/bin/toybox umount "${'$'}ROOTFS/etc/resolv.conf" 2>/dev/null
            }
            trap cleanup EXIT INT TERM HUP

            if [ ! -x "${'$'}ROOTFS/bin/bash" ] && [ ! -x "${'$'}ROOTFS/usr/bin/bash" ]; then
              echo "Linux-Dsu: rootfs 中没有可执行的 /bin/bash" >&2
              exit 127
            fi

            # 挂载 /dev /dev/pts /proc /sys /tmp（toybox）
            /system/bin/toybox mkdir -p "${'$'}ROOTFS/dev/pts" "${'$'}ROOTFS/proc" "${'$'}ROOTFS/sys" "${'$'}ROOTFS/tmp" "${'$'}ROOTFS/root"
            /system/bin/toybox mount --bind /dev "${'$'}ROOTFS/dev" || { echo "挂载 /dev 失败" >&2; exit 125; }
            /system/bin/toybox mount --bind /dev/pts "${'$'}ROOTFS/dev/pts" || { echo "挂载 /dev/pts 失败" >&2; exit 125; }
            /system/bin/toybox mount -t proc proc "${'$'}ROOTFS/proc" || { echo "挂载 /proc 失败" >&2; exit 125; }
            /system/bin/toybox mount -t sysfs sysfs "${'$'}ROOTFS/sys" || { echo "挂载 /sys 失败" >&2; exit 125; }
            /system/bin/toybox mount -t tmpfs tmpfs "${'$'}ROOTFS/tmp" || { echo "挂载 /tmp 失败" >&2; exit 125; }
            # resolv.conf：bind host 的，缺失则写国内 DNS
            /system/bin/toybox rm -f "${'$'}ROOTFS/etc/resolv.conf"
            /system/bin/toybox touch "${'$'}ROOTFS/etc/resolv.conf"
            /system/bin/toybox mount --bind /etc/resolv.conf "${'$'}ROOTFS/etc/resolv.conf" 2>/dev/null || true
            if ! /system/bin/toybox test -s "${'$'}ROOTFS/etc/resolv.conf"; then
              /system/bin/toybox printf '%s\n' 'nameserver 114.114.114.114' 'nameserver 223.5.5.5' > "${'$'}ROOTFS/etc/resolv.conf"
            fi

            # 音频 FIFO：host 侧 audioPipeHost 映射到 rootfs /run/android-audio.pcm
            /system/bin/toybox mkdir -p "${'$'}ROOTFS/run"
            # 确保 host 侧路径是真正的 FIFO：先删再 mkfifo（AudioBridge 会读它）
            /system/bin/toybox rm -f "$hostAudio" 2>/dev/null
            /system/bin/toybox mkfifo "$hostAudio" 2>/dev/null || { /system/bin/toybox touch "$hostAudio" 2>/dev/null; }
            /system/bin/toybox rm -f "${'$'}ROOTFS/run/android-audio.pcm"
            /system/bin/toybox ln -s "$hostAudio" "${'$'}ROOTFS/run/android-audio.pcm" 2>/dev/null || true

            # bashrc：pulse 音频 + VNC 桌面环境变量
            /system/bin/toybox printf '%s\n' \
              'if [ -r /usr/lib/os-release ]; then . /usr/lib/os-release; else . /etc/os-release 2>/dev/null; fi' \
              'export PS1="root@${'$'}{PRETTY_NAME:-Linux}:\\w# "' \
              'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' \
              'export XDG_RUNTIME_DIR=/run/user/0; mkdir -p /run/user/0 /run/pulse; export PULSE_SERVER=unix:/run/pulse/native; export PULSE_SINK=android_audio; export LANG=zh_CN.UTF-8; export LANGUAGE=zh_CN:en; export LC_ALL=' \
              > "${'$'}ROOTFS/root/.bashrc"
            /system/bin/toybox printf '%s\n' \
              '. /root/.bashrc' \
              > "${'$'}ROOTFS/root/.bash_profile"

            # 配置国内 apt 源（仅当源尚未配置为国内镜像时写入，避免覆盖用户手动修改）
            # 识别 /etc/os-release 的 ID 和 VERSION_CODENAME，写入清华源（Ubuntu 用 ubuntu-ports）
            /system/bin/toybox sh "${'$'}HOST_FILES/.linux-dsu-apt-mirror.sh" "${'$'}ROOTFS" 2>/dev/null || true

            # 修复 Tigervnc 默认 session wrapper：它引用 host 的 /system/bin/sh，chroot 里不存在
            /system/bin/toybox printf '%s\n' \
              '#!/bin/sh' \
              'unset SESSION_MANAGER' \
              'unset DBUS_SESSION_BUS_ADDRESS' \
              'export HOME=/root' \
              'export XDG_VNC_SESSION=1' \
              'export DISPLAY=${'$'}{DISPLAY:-:1}' \
              'export PULSE_SERVER=unix:/run/pulse/native' \
              'export PULSE_SINK=android_audio' \
              'export LANG=zh_CN.UTF-8' \
              'export LANGUAGE=zh_CN:en' \
              'export LC_ALL=' \
              'if [ -x /root/.vnc/xstartup ]; then exec /root/.vnc/xstartup; else for c in startxfce4 startplasma-x11 gnome-shell; do command -v "${'$'}c" >/dev/null 2>&1 && { exec "${'$'}c"; }; done; echo "no desktop" >&2; exit 1; fi' \
              > "${'$'}ROOTFS/etc/X11/Xtigervnc-session" 2>/dev/null || true
            /system/bin/toybox chmod +x "${'$'}ROOTFS/etc/X11/Xtigervnc-session" 2>/dev/null || true

            echo -e "\033[?25l"
            cd "${'$'}ROOTFS" || exit 1
            /system/bin/toybox chroot "${'$'}ROOTFS" /usr/bin/env \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              HOME=/root \
              TERM="${'$'}TERM" \
              LANG=zh_CN.UTF-8 \
              LANGUAGE=zh_CN:en \
              PULSE_SERVER=unix:/run/pulse/native \
              PULSE_SINK=android_audio \
              /bin/bash --login
            status=${'$'}?
            exit "${'$'}status"
        """.trimIndent()
    }

    private fun q(value: String): String = "'${value.replace("'", "'\\''")}'"
}
