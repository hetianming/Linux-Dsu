# Linux-Dsu

安卓运行 Ubuntu 终端和 DSU 的一体化工具。

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
