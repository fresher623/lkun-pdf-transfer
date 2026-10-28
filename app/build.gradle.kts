import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名配置放在项目目录之外，避免整包拷贝源码时带出口令：
//   默认 ~/keystores/lk-pdf-transfer/signing.properties，可用环境变量 LK_PDF_SIGNING_PROPERTIES 覆盖。
// 文件缺失时只是不配置 release 签名，debug / benchmark 构建不受影响；仓库与项目目录本身都不含口令。
val signingPropsFile = System.getenv("LK_PDF_SIGNING_PROPERTIES")?.let { File(it) }
    ?: File(System.getProperty("user.home"), "keystores/lk-pdf-transfer/signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.isFile) {
        FileInputStream(signingPropsFile).use { load(it) }
    }
}

android {
    namespace = "cn.local.imagepdf"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.local.imagepdf"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.1"
        manifestPlaceholders["appLabel"] = "l君のpdf转换"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (signingPropsFile.isFile) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storeType = signingProps.getProperty("storeType") ?: "PKCS12"
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingPropsFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    // A debuggable, release-like variant for repeatable device measurements.
    buildTypes.create("benchmark") {
        initWith(buildTypes.getByName("release"))
        isDebuggable = true
        applicationIdSuffix = providers.gradleProperty("benchmarkIdSuffix").orElse(".benchmark").get()
        manifestPlaceholders["appLabel"] = if (providers.gradleProperty("benchmarkIdSuffix").isPresent)
            "l君のpdf转换 · 自动验证" else "l君のpdf转换 · 测试"
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += listOf("release", "debug")
    }
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
}
dependencies {
    implementation(project(":core"))
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // Encrypted and signed inputs are rejected before crypto handling in this release.
        exclude(group = "org.bouncycastle")
    }
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
