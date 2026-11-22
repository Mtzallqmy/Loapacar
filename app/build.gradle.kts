plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.masalik.yemen"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.masalik.yemen"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "3.0.0"
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file(System.getenv("MASALIK_KEYSTORE") ?: "${rootDir}/debug.keystore")
            storePassword = System.getenv("MASALIK_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("MASALIK_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("MASALIK_KEY_PASSWORD") ?: System.getenv("MASALIK_STORE_PASSWORD") ?: "android"
        }
        create("release") {
            storeFile = file(System.getenv("MASALIK_KEYSTORE") ?: "${rootDir}/debug.keystore")
            storePassword = System.getenv("MASALIK_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("MASALIK_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("MASALIK_KEY_PASSWORD") ?: System.getenv("MASALIK_STORE_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
}
