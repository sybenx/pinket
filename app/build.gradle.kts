plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.objectbox)
}

android {
    val baseApplicationId = rootProject.extra["baseApplicationId"] as String
    val debugApplicationIdSuffix = rootProject.extra["debugApplicationIdSuffix"] as String

    namespace = "com.wisp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Release builds take the version from the tag (v1.4.0 -> 1.4.0, code 10400)
        val wispVersion = System.getenv("WISP_VERSION")
        versionName = wispVersion ?: "1.4.0-dev"
        versionCode = wispVersion?.split(".")?.map { it.toInt() }
            ?.let { (a, b, c) -> a * 10000 + b * 100 + c } ?: 10399
        resValue("string", "app_name", "Pinket")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing from env (CI secrets, or ~/.android/wisp locally); unsigned without it
    val wispKeystore = System.getenv("WISP_KEYSTORE_FILE")
    signingConfigs {
        if (wispKeystore != null) {
            create("release") {
                storeFile = file(wispKeystore)
                storePassword = System.getenv("WISP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WISP_KEY_ALIAS") ?: "wisp"
                keyPassword = System.getenv("WISP_KEY_PASSWORD") ?: System.getenv("WISP_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = debugApplicationIdSuffix
            resValue("string", "app_name", "Pinket Debug")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (wispKeystore != null) signingConfig = signingConfigs.getByName("release")
        }

        create("staging") {
            // Release-performance build for on-device testing. debuggable=true (the
            // debug type) makes ART ignore the baseline profile and run JIT-only with
            // deoptimization support, which cripples the per-event hot path (JSON parse,
            // hex decode, SHA-256, JNI Schnorr verify per relay event). This variant is
            // non-debuggable + R8 like release, but debug-signed so anyone can install
            // it without the release keystore. Installs alongside debug/release.
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            resValue("string", "app_name", "Pinket Staging")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.secp256k1.kmp.jni.jvm)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // JVM natives for the secp256k1 JNI so the crypto regression suites can
    // run as plain unit tests (CI has no emulator for these; the same suites
    // also remain in androidTest for on-device runs).
    testImplementation(libs.secp256k1.kmp.jvm)
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    testImplementation(libs.secp256k1.kmp.jni.common)
    testImplementation(libs.secp256k1.kmp.jni.jvm.extract)
    testImplementation(libs.secp256k1.kmp.jni.jvm.linux)
    testImplementation(libs.secp256k1.kmp.jni.jvm.darwin)
    testImplementation(libs.secp256k1.kmp.jni.jvm.mingw)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // JVM natives for the secp256k1 JNI so the crypto regression suites can
    // run as plain unit tests (CI has no emulator for these; the same suites
    // also remain in androidTest for on-device runs).
    testImplementation(libs.secp256k1.kmp.jvm)
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    testImplementation(libs.secp256k1.kmp.jni.common)
    testImplementation(libs.secp256k1.kmp.jni.jvm.extract)
    testImplementation(libs.secp256k1.kmp.jni.jvm.linux)
    testImplementation(libs.secp256k1.kmp.jni.jvm.darwin)
    testImplementation(libs.secp256k1.kmp.jni.jvm.mingw)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.secp256k1.kmp)
    implementation(libs.secp256k1.kmp.jni.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.security.crypto)
    implementation(libs.bouncycastle)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.biometric)
    implementation(libs.splashscreen)
    implementation(libs.profileinstaller)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.objectbox.android)
    implementation(libs.objectbox.kotlin)
    implementation(libs.mlkit.barcode)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.mlkit)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
}
