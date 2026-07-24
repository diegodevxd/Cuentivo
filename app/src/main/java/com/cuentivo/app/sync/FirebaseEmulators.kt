package com.cuentivo.app.sync

import com.cuentivo.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Points Auth and Firestore at the local Emulator Suite when the build asked for it.
 *
 * Enable with `-Pcuentivo.useEmulators=true`; the flag defaults to false, so a release build can
 * never talk to localhost. Start the suite with `firebase emulators:start` — ports come from the
 * `emulators` block in firebase.json.
 *
 * Must run before anything touches Firestore or Auth: useEmulator throws once the instance has
 * been used. [connected] guards against a second call within the same process.
 */
object FirebaseEmulators {
    private var connected = false

    fun connectIfEnabled() {
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
