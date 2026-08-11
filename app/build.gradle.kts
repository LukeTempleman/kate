import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.gonxt.kate"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.gonxt.kate"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // Keystore + password stay out of the repo: local.properties (gitignored).
            val props = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            storeFile = rootProject.file(props.getProperty("kate.store.file") ?: "keystore/kate-release.keystore")
            storePassword = props.getProperty("kate.store.password") ?: ""
            keyAlias = "kate"
            keyPassword = props.getProperty("kate.store.password") ?: ""
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
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
