plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "tech.gonxt.kate"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.gonxt.kate"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/kate-release.keystore")
            storePassword = "kate-release-2026"
            keyAlias = "kate"
            keyPassword = "kate-release-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Keeps the APK under Cloudflare Pages' 25MB file cap; costs install size.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.kotlinx.serialization.json)
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
