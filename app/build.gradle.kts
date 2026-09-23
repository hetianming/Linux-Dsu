plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mcai.ubuntudsu"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.mcai.ubuntudsu"
        minSdk = 26
        targetSdk = 28
        versionCode = 14
        versionName = "1.5.1"
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
        // 解压 jniLibs 到磁盘（nativeLibraryDir），否则 libaria2c.so 无法作为可执行文件运行
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
