# Linux-Dsu

安卓运行 Ubuntu 终端和 DSU 的一体化工具。

## DNA 工具箱（移植开发）

DNA 工具箱首页「Android®天明 移植开发」提供 ROM 分解/合成/格式转换等能力。其中 **本机分区 提取 / 刷写** 支持：

- 枚举 `/dev/block/by-name` 下全部本机分区（自动遍历各 by-name 目录）；
- **提取**：`dd` 将分区导出为 img（默认保存到工程 `Partitions` 目录或 `/sdcard/Download`）；
- **刷写**：选择本地 img 经 `dd` 刷回指定分区；
- 进度经 `/proc/<pid>/io` 实时回传（百分比 + 已写字节）。

两项操作均需在已 ROOT 设备上进行，直接写块设备有误刷变砖风险，操作前请务必备份。

## Build

Requirements:

- JDK 17
- Android SDK with platform 36 and build tools
- Android NDK `26.1.10909125`
- Gradle Wrapper 8.11.1

Clone the repository, then configure `local.properties` with the local Android SDK path. The file is ignored by Git.

### Debug APK

```bash
./gradlew assembleDebug
```

### Release APK

Release signing is required. Copy `gradle.properties.example` to a local Gradle properties file, provide the signing properties, and place the keystore at `keystore/release.jks`.

```bash
./gradlew assembleRelease
```

The release task fails when the local keystore or signing properties are unavailable, preventing an unsigned release APK.
