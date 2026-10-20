plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// URL OTA server Xiaozhi (bisa dioverride: -PXIAOZHI_OTA_URL=... atau secret GitHub)
val otaUrl: String = (project.findProperty("XIAOZHI_OTA_URL") as String?)
    ?: "https://api.tenclass.net/xiaozhi/ota/"

android {
    namespace = "com.xiaozhi.device"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaozhi.device"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "OTA_URL", "\"$otaUrl\"")
    }

    val ksFile: String? = project.findProperty("KEYSTORE_FILE") as String?
    val ksPass: String? = project.findProperty("KEYSTORE_PASSWORD") as String?
    val ksAlias: String? = project.findProperty("KEY_ALIAS") as String?
    val ksKeyPass: String? = project.findProperty("KEY_PASSWORD") as String?

    signingConfigs {
        if (ksFile != null && ksPass != null && ksAlias != null && ksKeyPass != null) {
            create("release") {
                storeFile = File(projectDir, ksFile)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile != null && ksPass != null && ksAlias != null && ksKeyPass != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes.add("META-INF/NOTICE.md")
        resources.excludes.add("META-INF/LICENSE.md")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
