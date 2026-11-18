plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val groqKey: String = (project.findProperty("GROQ_KEY") as String?) ?: ""
val updateBase: String = (project.findProperty("UPDATE_BASE") as String?) ?: ""
val buildNumber: Int = ((project.findProperty("BUILD_NUMBER") as String?) ?: "1").toInt()

android {
    namespace = "com.tarjuman.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tarjuman.tv"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "DEFAULT_KEY", "\"$groqKey\"")
        buildConfigField("String", "UPDATE_BASE", "\"$updateBase\"")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "tarjuman2026"
            keyAlias = "tarjuman"
            keyPassword = "tarjuman2026"
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

    buildFeatures { buildConfig = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // on-device text reading (letters / phone messages shown in a scene)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}
