# R8 混淆规则：体积优化为主，保守保留反射/序列化相关代码

# sshlib → Tink 引用的编译期注解（运行时不存在，安全忽略）
-dontwarn javax.annotation.Nullable

# 应用自身代码整体保留（体积小，安全第一）
-keep class com.mcai.ubuntudsu.** { *; }

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
