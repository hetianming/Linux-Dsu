plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Optional reproducible native build. The checked-in ARM64 .so keeps ordinary Gradle builds
// independent of Rust; run ./gradlew buildPayloadExtractJni after changing native sources.
tasks.register<Exec>("buildPayloadExtractJni") {
    group = "build"
    description = "Build the independent ARM64 payload extraction JNI library with Rust/NDK"
    val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
    val cargo = file("$cargoHome/bin/cargo").takeIf { it.isFile }?.absolutePath ?: "cargo"
    val nativeManifest = rootProject.file("native/payload_extract_jni/Cargo.toml")
    val nativeLockfile = rootProject.file("native/payload_extract_jni/Cargo.lock")
    val nativeOutput = rootProject.file("native/payload_extract_jni/target/aarch64-linux-android/release/libpayload_extract_jni.so")
    val jniDestination = file("src/main/jniLibs/arm64-v8a")
    val packagedOutput = file("src/main/jniLibs/arm64-v8a/libpayload_extract_jni.so")
    workingDir(rootProject.projectDir)
    commandLine(cargo, "build", "--manifest-path", nativeManifest.absolutePath,
        "--target", "aarch64-linux-android", "--release")
    environment("CC_aarch64_linux_android", "${android.ndkDirectory}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang")
    environment("AR_aarch64_linux_android", "${android.ndkDirectory}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar")
    environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", "${android.ndkDirectory}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang")
    inputs.dir(rootProject.file("native/payload_extract_jni/src"))
    inputs.file(nativeManifest)
    inputs.file(nativeLockfile)
    outputs.files(nativeOutput, packagedOutput)
    doLast {
        copy {
            from(nativeOutput)
            into(jniDestination)
        }
    }
}

android {
    namespace = "com.mcai.ubuntudsu"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.mcai.ubuntudsu"
        minSdk = 26
        targetSdk = 28
        versionCode = 77
        versionName = "1.8.28"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // 裁剪资源语言：仅保留中英文，大幅缩小 resources.arsc
        resourceConfigurations += listOf("en", "zh")
    }

    val hasReleaseSigning = providers.gradleProperty("RELEASE_STORE_PASSWORD").isPresent &&
        providers.gradleProperty("RELEASE_KEY_ALIAS").isPresent &&
        providers.gradleProperty("RELEASE_KEY_PASSWORD").isPresent &&
        file("../keystore/release.jks").isFile

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file("../keystore/release.jks")
                storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // R8 全程序优化 + 资源收缩：dex/arsc 体积最小化
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    tasks.matching { it.name == "assembleRelease" }.configureEach {
        doFirst {
            check(hasReleaseSigning) {
                "Release 构建需要 keystore/release.jks 以及 RELEASE_STORE_PASSWORD、RELEASE_KEY_ALIAS、RELEASE_KEY_PASSWORD"
            }
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        dataBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.tukaani:xz:1.9")
    implementation(project(":terminal-view"))
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.connectbot:sshlib:2.2.36")
}
