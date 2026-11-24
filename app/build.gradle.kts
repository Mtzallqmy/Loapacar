plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.masalik.yemen"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    defaultConfig {
        applicationId = "com.masalik.yemen"
        testInstrumentationRunner = "com.masalik.yemen.TouchDriveProbe"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "4.0.0"
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file(System.getenv("MASALIK_KEYSTORE") ?: "${rootDir}/debug.keystore")
            storePassword = System.getenv("MASALIK_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("MASALIK_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("MASALIK_KEY_PASSWORD") ?: System.getenv("MASALIK_STORE_PASSWORD") ?: "android"
        }
        create("release") {
            storeFile = System.getenv("MASALIK_RELEASE_KEYSTORE")?.let { file(it) }
            storePassword = System.getenv("MASALIK_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("MASALIK_RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("MASALIK_RELEASE_KEY_PASSWORD") ?: storePassword
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
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        create("journeys") {
            initWith(getByName("preview"))
            applicationIdSuffix = ".cities"
            versionNameSuffix = "-cities-preview.1"
            ndk { abiFilters += "arm64-v8a" }
        }
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
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

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

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

// JVM simulation tests execute the same C/C++ tire code that Android loads via JNI.
val hostNative = layout.buildDirectory.dir("host-contacts")
val configureContacts by tasks.registering(Exec::class) {
    environment("JAVA_HOME", System.getProperty("java.home"))
    commandLine("cmake", "-S", file("src/main/cpp"), "-B", hostNative.get().asFile,
        "-DCMAKE_BUILD_TYPE=Release")
    inputs.file("src/main/cpp/CMakeLists.txt")
    outputs.file(hostNative.map { it.file("CMakeCache.txt") })
}
val compileContacts by tasks.registering(Exec::class) {
    dependsOn(configureContacts)
    commandLine("cmake", "--build", hostNative.get().asFile, "--config", "Release")
    inputs.dir("src/main/cpp")
    outputs.file(hostNative.map { it.file(System.mapLibraryName("mycar_contacts")) })
}
tasks.withType<Test>().configureEach {
    dependsOn(compileContacts)
    val nativePath = hostNative.get().asFile
    systemProperty("java.library.path", listOf(nativePath, nativePath.resolve("Release")).joinToString(File.pathSeparator))
}
