plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "cn.local.imagepdf"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.local.imagepdf"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        manifestPlaceholders["appLabel"] = "l君のpdf转换"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
