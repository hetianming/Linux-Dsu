# R8 混淆规则：体积优化为主，保守保留反射/序列化相关代码

# sshlib → Tink 引用的编译期注解（运行时不存在，安全忽略）
-dontwarn javax.annotation.Nullable

# 不保留整个应用命名空间：让 R8 对应用代码也执行收缩、优化与混淆。
# Android 组件由 Manifest 与 AGP 的默认规则保活；序列化/反射所需成员在下方单独保留。

# 终端视图模块（XML 反射实例化 + 自定义 View）
-keep class com.termux.** { *; }

# libsu RootService（IPC/反射加载 stub）
-keep class com.topjohnwu.** { *; }

# HiddenApiBypass（大量反射调用 hidden API）
-keep class org.lsposed.hiddenapibypass.** { *; }

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.mcai.ubuntudsu.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mcai.ubuntudsu.**$$serializer { *; }

# 通用：保留源码行号便于崩溃定位
-keepattributes SourceFile,LineNumberTable

# JNI 方法通过静态导出符号绑定；类名和 native 方法名必须与 Rust 导出符号一致。
-keep class com.mcai.ubuntudsu.core.dna.PayloadExtractNative { *; }

# v2.0 内置原生显示：AIDL Stub 由 binder 反射加载，root 服务由 libsu 按类名启动
-keep class com.mcai.ubuntudsu.core.INativeDisplayService$Stub { *; }
-keep class com.mcai.ubuntudsu.core.INativeDisplayService$Stub$Proxy { *; }
-keep class com.mcai.ubuntudsu.core.NativeDisplayRootService { *; }
-keep class android.crosvm.ICrosvmAndroidDisplayService$Stub { *; }
-keep class android.crosvm.ICrosvmAndroidDisplayService$Stub$Proxy { *; }
-keep class android.crosvm.DisplayConfig { *; }
