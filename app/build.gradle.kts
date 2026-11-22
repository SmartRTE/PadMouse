import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// 签名配置
//
// 密钥文件与密码**不写进本文件**，而是放在项目根目录的 keystore.properties
// （已 gitignore）。也支持用环境变量覆盖，方便 CI：
//   PADMOUSE_STORE_FILE / PADMOUSE_STORE_PASSWORD / PADMOUSE_KEY_ALIAS / PADMOUSE_KEY_PASSWORD
//
// 读取不到就保持 release 未签名 —— 构建不报错，只是产出 unsigned APK。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun secret(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile = secret("storeFile", "PADMOUSE_STORE_FILE")
    ?.let { rootProject.file(it) }
val releaseStorePassword = secret("storePassword", "PADMOUSE_STORE_PASSWORD")
val releaseKeyAlias = secret("keyAlias", "PADMOUSE_KEY_ALIAS")
val releaseKeyPassword = secret("keyPassword", "PADMOUSE_KEY_PASSWORD")

val hasReleaseSigning =
    releaseStoreFile != null && releaseStoreFile.exists() &&
        releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.padmouse.mapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.padmouse.mapper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 本项目无反射、无序列化框架，R8 风险极低；开启后能显著减小体积
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // Probe 需要区分 debug / release 来决定是否记录高频日志
    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 只是抛异常的桩，单元测试需要真实实现
    testImplementation("org.json:json:20231013")
}
