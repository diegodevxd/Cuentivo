package com.cuentivo.app.sync

import com.cuentivo.app.BuildConfig
import com.cuentivo.app.FUNCTIONS_REGION
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions

/**
 * Points the Firebase SDKs at local emulators -- but only at the ones this build asked for.
 *
 * Two independent switches:
 *
 *  - `-Pcuentivo.useEmulators=true` -> Auth and Firestore point at the Emulator Suite. Off by
 *    default, so a release build can never talk to localhost.
 *  - `-Pcuentivo.functionsEmulatorHost=<host>` -> **only** Functions points at a local Functions
 *    emulator (port [FUNCTIONS_EMULATOR_PORT]). Auth and Firestore keep talking to production,
 *    because the two switches are independent on purpose: a phone can call the local Functions
 *    (the emulated Ledger, Gemini's proxy, whatever is being tried) while the account and the
 *    cloud copy stay real.
 *
 * Only a debug build can carry the second value. A release build that is given the property fails
 * instead of ignoring it (see `app/build.gradle.kts`).
 *
 * Must run before anything touches Firestore, Auth or Functions: useEmulator throws once the
 * instance has been used. [connected] guards against a second call within the same process, and so
 * does the `connected` of the Functions wiring.
 */
object FirebaseEmulators {
    private var connected = false

    /**
     * The Functions wiring. `internal` so the JVM test can drive it with a fake connector:
     * `FirebaseFunctions` does not exist on the JVM.
     */
    internal val functionsEmulator = FunctionsEmulatorWiring(BuildConfig.FUNCTIONS_EMULATOR_HOST)

    fun connectIfEnabled() {
        functionsEmulator.connectIfEnabled()
        if (!BuildConfig.USE_FIREBASE_EMULATORS || connected) return
        connected = true
        FirebaseFirestore.getInstance().useEmulator(HOST, FIRESTORE_PORT)
        FirebaseAuth.getInstance().useEmulator(HOST, AUTH_PORT)
    }

    /** The Android emulator reaches the host machine's loopback through this alias, not 127.0.0.1. */
    private const val HOST = "10.0.2.2"
    private const val FIRESTORE_PORT = 8080
    private const val AUTH_PORT = 9099
}

/** The port the Firebase Emulator Suite serves Functions on. */
internal const val FUNCTIONS_EMULATOR_PORT = 5001

/**
 * Points **only** Firebase Functions at a local emulator, and only when the build named a host.
 *
 * A class and not a function, for two reasons:
 *
 *  - the "already connected" state belongs to the instance. A second `useEmulator` on the same
 *    `FirebaseFunctions` instance throws ("Cannot modify a FirebaseFunctions instance after it has
 *    been used"), and this wiring is called from two places that can live in the same process
 *    (the Activity and a WorkManager job).
 *  - a JVM test can build one with a fake connector, because `FirebaseFunctions` does not exist on
 *    the JVM. The test drives the same code production drives.
 *
 * [region] has to be the region the app's callables live in: `FirebaseFunctions.getInstance`
 * keeps one instance per region, so pointing a different region at the emulator would configure an
 * instance nobody calls.
 */
class FunctionsEmulatorWiring(
    internal val host: String,
    private val region: String = FUNCTIONS_REGION,
    private val port: Int = FUNCTIONS_EMULATOR_PORT,
) {
    private var connected = false

    /**
     * Points Functions at [host] when the build named one; does nothing when the build named
     * none. [connect] is the seam for the tests: production calls `useEmulator`.
     */
    fun connectIfEnabled(
        connect: (region: String, host: String, port: Int) -> Unit = { r, h, p ->
            FirebaseFunctions.getInstance(r).useEmulator(h, p)
        },
    ) {
        if (host.isEmpty() || connected) return
        connected = true
        connect(region, host, port)
    }
}
