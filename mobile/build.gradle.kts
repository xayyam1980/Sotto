import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // Screenshot tests write their PNGs into docs/screens (used by the README)
        unitTests.all { it.systemProperty("roborazzi.test.record", "true") }
    }
    // applicationId must match the watch app so the Data Layer links them.
    namespace = "com.example.watchscribe.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.watchscribe"
        minSdk = 31
        targetSdk = 34
        versionCode = 13
        versionName = "1.3"
        ndk { abiFilters += "arm64-v8a" } // the phone is 64-bit ARM; keeps the app small
    }

    // Release key (keep sotto-release.jks + keystore.properties backed up: updates need the same key)
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = rootProject.file(keyProps.getProperty("storeFile").removePrefix("../"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // Shrinks unused code (mostly the icon library): ~39 MB -> ~20 MB
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }

    packaging {
        jniLibs {
            useLegacyPackaging = true // extract .so files; large native libs load more reliably
            pickFirsts += "**/libc++_shared.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")

    // On-device AI
    implementation(files("libs/sherpa-onnx-1.13.8-arm64.aar"))                   // Whisper + speakers
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0") // Gemma 4
    implementation("org.apache.commons:commons-compress:1.27.1")                 // unpack model archives
    implementation("androidx.work:work-runtime-ktx:2.9.1")                       // background processing
    implementation("androidx.biometric:biometric:1.1.0")                         // app lock (on-device)
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.maplibre.gl:android-sdk:11.8.0")                         // Places map (OpenFreeMap tiles, no key)

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
