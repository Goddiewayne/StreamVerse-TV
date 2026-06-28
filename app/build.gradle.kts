plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

import java.util.Properties

android {
    namespace = "com.streamverse.app"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.streamverse.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    val keystoreFile = rootProject.file("keystore.properties")
    val signingConfigsMap = if (keystoreFile.exists()) {
        val props = Properties()
        keystoreFile.inputStream().use { props.load(it) }
        mapOf(
            "storeFile" to props["storeFile"] as String,
            "storePassword" to props["storePassword"] as String,
            "keyAlias" to props["keyAlias"] as String,
            "keyPassword" to props["keyPassword"] as String,
        )
    } else null

    signingConfigs {
        create("release") {
            if (signingConfigsMap != null) {
                storeFile = rootProject.file(signingConfigsMap["storeFile"]!!)
                storePassword = signingConfigsMap["storePassword"]
                keyAlias = signingConfigsMap["keyAlias"]
                keyPassword = signingConfigsMap["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.cast)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)

    implementation(libs.coil.compose)

    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    implementation(libs.browser)
    implementation(libs.datastore.preferences)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
}
