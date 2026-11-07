plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}

// 构建产物一律输出到工作区之外（/tmp/gunyabuild），避免 app/build 下数千个
// 中间文件（R-def.txt / baseline-prof.txt / manifest-merger-blame-* 等）
// 混进「全部产物」列表，把真正的 APK 埋掉。
allprojects {
    layout.buildDirectory.set(file("/tmp/gunyabuild/${name}"))
}
