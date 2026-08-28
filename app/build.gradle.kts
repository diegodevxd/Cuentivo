// Imported explicitly: inside the android { } block, `java` resolves to Gradle's java extension.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.cuentivo.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.cuentivo.app"
        minSdk = 24
        targetSdk = 36
        // Play refuses an AAB whose versionCode was already uploaded, so this goes up on every
        // upload even when versionName does not.
        versionCode = 33
        versionName = "1.18.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Off unless explicitly asked for, so a release build can never be pointed at localhost:
        //   .\gradlew.bat installDebug -Pcuentivo.useEmulators=true
        buildConfigField(
            "boolean",
            "USE_FIREBASE_EMULATORS",
            (project.findProperty("cuentivo.useEmulators") as? String ?: "false"),
        )
    }

    // Release signing reads from keystore.properties, which stays out of git. Without that file the
    // release build simply goes unsigned instead of failing, so a fresh clone still builds.
    val keystoreProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // java.time only exists from API 26, and this app ships to minSdk 24. Without desugaring it
        // does not merely look wrong on Android 7.0/7.1 â€” LocalDate throws NoClassDefFoundError and
        // the app fails to open at all. Every screen touches dates, so there is no partial failure.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // MigrationTestHelper reads the exported Room schemas from androidTest assets; the test-only
    // helper resolves them under `{canonicalName}/{version}.json` from this exact directory.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    // The app switches between Spanish and English at runtime. Both resource sets must be present
    // in every Play-delivered install; otherwise a language split may leave the selected locale
    // unavailable until Play downloads an additional split.
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    // Backports java.time to API 24. Paired with isCoreLibraryDesugaringEnabled above; neither one
    // does anything without the other.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Declares the com.android.vending.BILLING permission in the merged manifest, which Play Console
    // requires before it will let a subscription product be created for the app.
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    // Callable transport to the us-south1 gateway; the v1 contract carries no text.
    implementation("com.google.firebase:firebase-functions")
    // Crash reporting for release only: fixed technical event names, never PII, amounts or ids.
    // The reporter abstraction keeps debug/emulator sessions from sending anything at all.
    implementation("com.google.firebase:firebase-crashlytics")
    // App Check proves the request came from this app, not from a script holding the API key that
    // ships inside every APK. The rules already prove *who* is calling; this proves *from where*.
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    // Google sign-in via AndroidX Credential Manager + Google ID. The legacy Google Sign-In APIs
    // are deliberately not used: Credential Manager is the supported path and the only one that
    // returns an ID token the Firebase GoogleAuthProvider can exchange without a Play games client.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // On-device OCR for the AI debug lab (notebook/ticket text extraction). Bundled variant:
    // no network, no Firebase, no Play Services model download at runtime.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Debug only. Play Integrity attests a binary installed by Play, which an emulator is not, so
    // the debug provider is what lets instrumented tests get a token at all. Deliberately NOT
    // `implementation`: shipping a provider that hands out tokens on request would undo the whole
    // protection. The variant-specific AppCheckProvider is what keeps the two apart.
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation(platform(libs.androidx.compose.bom))
    // Version managed by the Compose BOM. Core set only â€” material-icons-extended stays out.
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    // androidTest only: lets the E2E build a real SyncWorker without WorkManager scheduling it.
    // Deliberately not `implementation` â€” none of this reaches the release APK.
    androidTestImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
