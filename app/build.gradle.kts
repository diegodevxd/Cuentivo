// Imported explicitly: inside the android { } block, `java` resolves to Gradle's java extension.
import org.gradle.api.Action
import org.gradle.api.execution.TaskExecutionGraph
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

// The host of the local Functions emulator. Empty by default, and ONLY the debug build type ever
// carries it:
//   .\gradlew.bat assembleDebug -Pcuentivo.functionsEmulatorHost=127.0.0.1
// On a physical phone 127.0.0.1 is the phone itself, so the loopback is opened with
// `adb reverse tcp:5001 tcp:5001` (see HANDOFF_CUENTIVO1.md, section CUENTIVO3).
// A release build can never carry the value: the property is read by the debug build type only,
// and the guard at the end of this file fails the build if somebody sets it while building a
// release.
val functionsEmulatorHost = ((project.findProperty("cuentivo.functionsEmulatorHost") as? String) ?: "").trim()

// A host is a bare host: no scheme, no path, no spaces. A value that cannot be one is a typo, and
// saying so here beats shipping an app that silently cannot reach the emulator.
if (functionsEmulatorHost.isNotEmpty() && !Regex("[A-Za-z0-9._:\\-]+").matches(functionsEmulatorHost)) {
    throw GradleException(
        "cuentivo.functionsEmulatorHost must be a bare host (for example 127.0.0.1 or localhost), " +
            "but it was '$functionsEmulatorHost'."
    )
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
        versionCode = 49
        versionName = "1.24.0"

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
        debug {
            // The only build type that can talk to a local Functions emulator. Empty when the
            // property was not given, which is the case for every ordinary build.
            buildConfigField("String", "FUNCTIONS_EMULATOR_HOST", buildConfigStringLiteral(functionsEmulatorHost))
        }
        release {
            // Always empty. The property above is read by the debug build type only, so a release
            // can never point Functions at a local emulator; the guard at the end of this file
            // fails the build if somebody sets the property while building a release.
            buildConfigField("String", "FUNCTIONS_EMULATOR_HOST", "\"\"")
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
    testImplementation("org.json:json:20240303")
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

// Ensures the release merged manifest and shared test fixtures are declared as inputs
afterEvaluate {
    tasks.named("testDebugUnitTest") {
        dependsOn("processReleaseMainManifest")
        inputs.file(layout.buildDirectory.file("intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"))
        inputs.file(rootProject.file("shared-fixtures/ledgerOperationFixtures.json"))
    }
}

/**
 * A Java string literal for the generated BuildConfig.
 *
 * The host comes from a command-line property, so it is escaped: a stray quote would otherwise
 * produce a BuildConfig that does not compile, with an error that points nowhere near the cause.
 */
fun buildConfigStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// A release build must never point Firebase Functions at a local emulator.
//
// The property is read by the debug build type only, so a release always carries the empty string.
// But somebody who sets the property and then builds a release would be silently ignored -- and
// the next person to "fix" that could publish an app that talks to localhost. So the build fails.
//
// The trigger is the release variant's COMPILE tasks. The release variant also owns a few shared
// preparation tasks -- the manifest, the version-control info, the Google Services file -- that a
// DEBUG task pulls in on purpose (the unit tests pull `processReleaseMainManifest` in), and those
// do not mean "a release build is happening".
val appProject = project
gradle.taskGraph.whenReady(Action<TaskExecutionGraph> {
    val graph = this
    if (functionsEmulatorHost.isEmpty()) return@Action
    val releaseIsBeingBuilt = graph.allTasks.any { task ->
        task.project == appProject && task.name.startsWith("compileRelease")
    }
    if (releaseIsBeingBuilt) {
        throw GradleException(
            "cuentivo.functionsEmulatorHost=$functionsEmulatorHost is set, but a release build must " +
                "never point Firebase Functions at a local emulator. Build the debug variant " +
                "instead: assembleDebug -Pcuentivo.functionsEmulatorHost=$functionsEmulatorHost"
        )
    }
})
