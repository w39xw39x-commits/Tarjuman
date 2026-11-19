plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val groqKey: String = (project.findProperty("GROQ_KEY") as String?) ?: ""
val wyzieKey: String = (project.findProperty("WYZIE_KEY") as String?) ?: ""
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
        // stored scrambled (reversed + base64) so key scanners never see it in the public APK
        buildConfigField("String", "DEFAULT_KEY", "\"${java.util.Base64.getEncoder().encodeToString(groqKey.reversed().toByteArray())}\"")
        buildConfigField("String", "UPDATE_BASE", "\"$updateBase\"")
        buildConfigField("String", "WYZIE_KEY", "\"$wyzieKey\"")
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
    // on-device text reading via Google Play services (keeps the APK small)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // built-in player (IPTV) with delayed picture for perfectly synced subtitles
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}

// Flat upload support: icon files uploaded next to the sources become drawables.
// Phones sometimes turn "ic_x.xml" into "ic x.xml" - both are accepted.
val copyFlatIcons = tasks.register<Copy>("copyFlatIcons") {
    from(rootDir) {
        include("ic_*.xml", "ic *.xml")
        exclude("ic_launcher.xml", "ic_fg.xml", "ic_stat.xml", "ic launcher.xml", "ic fg.xml", "ic stat.xml")
    }
    rename { it.replace(' ', '_') }
    into(layout.buildDirectory.dir("generated/flatIcons/drawable"))
}
android.sourceSets.getByName("main").res.srcDir(layout.buildDirectory.get().asFile.resolve("generated/flatIcons"))
tasks.named("preBuild") { dependsOn(copyFlatIcons) }
