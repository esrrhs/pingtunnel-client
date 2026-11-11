import java.util.Properties
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin（com.android.application 自带），不再应用 kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun gitCommitHash(dir: File): String {
    return try {
        val process = ProcessBuilder("git", "-C", dir.absolutePath, "rev-parse", "--short", "HEAD")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        text.ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

val appGitHash = gitCommitHash(rootDir)
val pingtunnelGitHash = gitCommitHash(file("${rootDir}/third_party/pingtunnel"))
val hevGitHash = gitCommitHash(file("${rootDir}/third_party/hev-socks5-tunnel"))
val buildTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())

// Release 签名从 keystore.properties 读取（不入库）；文件缺失时产出未签名 release APK
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.esrrhs.pingtunnel.client"
    compileSdk = 37
    // Android 16+ QPR 次版本 SDK（最新 AndroidX 要求 compileSdk 37.2）
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.esrrhs.pingtunnel.client"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "0.3.0"

        buildConfigField("String", "GIT_COMMIT_APP", "\"$appGitHash\"")
        buildConfigField("String", "GIT_COMMIT_PINGTUNNEL", "\"$pingtunnelGitHash\"")
        buildConfigField("String", "GIT_COMMIT_HEV", "\"$hevGitHash\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimeStr\"")

        ndk {
            // MVP 只保留主流 ABI，其余按需在 scripts 里加
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // libpingtunnel.so / libhev-socks5-tunnel.so 由 scripts/build_native.sh 产出。
    // libpingtunnel.so 实为 Go 静态可执行文件，借 .so 名装入 nativeLibraryDir 以获得 exec 权限，
    // 因此必须开启 useLegacyPackaging 让 so 落盘为真实文件。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// AGP 9 内置 Kotlin：jvmTarget 通过 Kotlin DSL 设置
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
