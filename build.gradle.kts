plugins {
    id("com.android.application") version "8.2.0"
    kotlin("android") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"
}

android {
    namespace = "eu.kanade.tachiyomi.extension.en.ifetch"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.en.ifetch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    compileOnly("com.github.komikku-app:extensions-lib:1.4.4")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("uy.kohesive.injekt:injekt-core:1.16.1")
    compileOnly("androidx.preference:preference:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
