import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing key created by scripts/build-apk.sh; kept out of git.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.personal.music"
    // AndroidX/OkHttp releases of late 2026 require compiling against API 37.2.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    // Same build-tools as installed in docker/android-build.Dockerfile; without this AGP picks its
    // default (36.0.0) and, before sdkDownload=false, silently downloaded it on every build.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.personal.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file("keystore/release.jks")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No R8 for now: NewPipeExtractor needs keep rules; revisit if APK size matters.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        // NewPipeExtractor uses java.time / java.nio APIs.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // OkHttp's Android variant calls android.util.Log; stub it in JVM tests.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
