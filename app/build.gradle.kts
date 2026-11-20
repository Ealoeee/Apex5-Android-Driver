import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProps = Properties().apply {
    val propsFile = rootProject.file("signing.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

val persistentStoreFilePath: String? =
    System.getenv("APEX5_KEYSTORE_PATH") ?: signingProps.getProperty("storeFile")
val persistentStoreFile = persistentStoreFilePath?.let { file(it) }?.takeIf { it.exists() }
val persistentStorePass: String? =
    System.getenv("APEX5_STORE_PASSWORD") ?: signingProps.getProperty("storePassword")
val persistentKeyAlias: String? =
    System.getenv("APEX5_KEY_ALIAS") ?: signingProps.getProperty("keyAlias")
val persistentKeyPass: String? =
    System.getenv("APEX5_KEY_PASSWORD") ?: signingProps.getProperty("keyPassword")

val hasPersistentSigning = persistentStoreFile != null &&
    !persistentStorePass.isNullOrBlank() &&
    !persistentKeyAlias.isNullOrBlank() &&
    !persistentKeyPass.isNullOrBlank()

android {
    namespace = "com.apex5.driver"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.apex5.driver"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O2", "-std=c++17")
            }
        }
    }

    signingConfigs {
        if (hasPersistentSigning) {
            create("persistent") {
                storeFile = persistentStoreFile
                storePassword = persistentStorePass
                keyAlias = persistentKeyAlias
                keyPassword = persistentKeyPass
            }
        }
    }

    buildTypes {
        debug {
            if (hasPersistentSigning) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }
        release {
            if (hasPersistentSigning) {
                signingConfig = signingConfigs.getByName("persistent")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
}
