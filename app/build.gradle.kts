import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

// Load keystore properties for release signing
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "app.maskan.chat"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.maskan.chat"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "2.6.1"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Test builds install side-by-side with the Play copy (which is signed with the Play
            // app-signing key, so a same-id local build would fail with UPDATE_INCOMPATIBLE).
            // Debug-only: the release/F-Droid APK is unaffected.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Package native debug symbols into the Play AAB (clears Play Console's
            // "no debug symbols" warning, makes native crash/ANR traces readable).
            // Affects only the bundle metadata — the F-Droid/GitHub APK is unchanged.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // The golden-request tests run on the JVM. android.util.Log (the debug-only logging in
        // the providers) returns defaults there instead of throwing; nothing the tests compare
        // depends on an Android stub's return value.
        unitTests.isReturnDefaultValues = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }

    packaging {
        jniLibs {
            // The on-device engine's native library is 26.6 MB on arm64 and 108 MB across the
            // four ABIs in the universal APK. Compressed in the APK it is 10.3 MB and 42 MB.
            // AGP's default (uncompressed, mapped straight out of the APK) is the better
            // runtime trade and the wrong download trade: the universal APK is what F-Droid
            // serves and what people sideload, and 136 MB over mobile data does not finish.
            // The cost, accepted once and stated here: the libraries are extracted at install,
            // so roughly 26 MB more storage on arm64, a slower first launch, and Play marking
            // the delivery as non-recommended.
            //
            // Measured on real release builds of c320ee0 in session 5, not estimated:
            // universal 136,651,327 uncompressed vs 58,053,655 compressed.
            useLegacyPackaging = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Disable baseline profile generation for reproducible builds (F-Droid)
// See: https://f-droid.org/docs/Reproducible_Builds/#bug-baselineprof-not-deterministic
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val abiCode = abiCodes[abi] ?: 0
            output.versionCode.set((output.versionCode.get() ?: 0) * 10 + abiCode)
        }
    }
}

// kotlinOptions inside android {} is an error from Kotlin 2.2 on; this is its replacement, with
// the same target (2.7: Kotlin 2.1.20 -> 2.3.21, which LiteRT-LM 0.15+ requires).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // All dependencies here are FOSS-compatible (Apache 2.0, MIT, BSD, JetBrains licenses).
    // No Google Play Services, no Firebase, no proprietary analytics or telemetry.

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM enforces compatible versions for all Compose libs)
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager (AndroidX, no Google Play Services) - a video render is minutes long and is
    // waited on by a worker that outlives the screen, the activity and the process.
    implementation(libs.androidx.work.runtime.ktx)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Kotlinx Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Security
    implementation(libs.security.crypto)

    // SQLCipher
    implementation(libs.sqlcipher)
    implementation(libs.sqlite)

    // PDF text extraction (Apache PDFBox 2.0.27 ported to Android; Apache 2.0, pure Java).
    // BouncyCastle is excluded on purpose: of the 978 classes in the AAR only
    // PublicKeySecurityHandler and SecurityProvider reference it, so the exclusion costs
    // certificate-encrypted PDFs (rare, refused with a message) and saves ~11 MB of jars.
    // Password- and permissions-encrypted PDFs go through StandardSecurityHandler, which
    // uses javax.crypto and is unaffected.
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }

    // LiteRT-LM - the on-device model's runtime (Apache 2.0). Its POM names exactly three
    // dependencies - gson, kotlin-reflect and kotlinx-coroutines-android - and no Play Services,
    // Firebase or analytics; the AAR declares no permissions, and its native library has no
    // socket or resolver calls in it (read 2026-10-24, 2.7 session 8). Almost all of its weight is
    // one prebuilt native library per ABI, and it ships arm64-v8a and x86_64 ONLY: the
    // armeabi-v7a and x86 APKs have no on-device model. The MODEL is not here and is never in the
    // APK - it is downloaded, verified and deleted by the user (see ondevice/).
    //
    // It replaced MediaPipe's tasks-genai in 2.7 (plan item 14): Google put the LLM Inference API
    // in maintenance-only mode and named this as its successor, and Qwen3.5 - a GatedDeltaNet
    // hybrid, not a plain transformer - needs litert-lm 0.15 or later. Pinned at 0.16.1 because
    // 0.17 and later are compiled with Kotlin 2.4, which this toolchain (2.1.20) cannot read;
    // Humam chose no Kotlin bump.
    implementation(libs.litertlm.android)

    // Tests only - never in the APK, and not resolved by assembleRelease, so F-Droid's build
    // never fetches them. JUnit 4.13.2 (EPL-1.0) and OkHttp's MockWebServer (Apache 2.0, the
    // same version as okhttp above): the golden-request tests point each provider at a server
    // on localhost and compare the bytes it receives with a file in src/test/resources/golden.
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}



