// Top-level build file. Individual module config lives in app/build.gradle.kts.
// AGP 9.0+ 内置 Kotlin 支持，无需再声明 org.jetbrains.kotlin.android。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
