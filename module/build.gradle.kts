import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hyperfontfix.module"
    buildToolsVersion = "37.0.0"

    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.hyperfontfix.module"
        minSdk = 26
        targetSdk = 34
        versionCode = 27
        versionName = "1.4.0"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            res.srcDirs("res")
            assets.srcDirs("assets")
            resources.srcDirs("resources")
        }
    }

    signingConfigs {
        create("hf") {
            storeFile = file("../tools/debug.keystore")
            storePassword = "android"
            keyAlias = "hf"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("hf")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("io.github.libxposed:service:102.0.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("androidx.annotation:annotation:1.11.0")
}
