plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.apkwhispr"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.apkwhispr"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload build: sign with the debug key so the APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    kotlinOptions { jvmTarget = "17" }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    packaging { resources { excludes += listOf("kotlin/**", "META-INF/**", "DebugProbesKt.bin") } }
}
