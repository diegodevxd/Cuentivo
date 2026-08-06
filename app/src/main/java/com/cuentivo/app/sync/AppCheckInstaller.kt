package com.cuentivo.app.sync

import com.google.firebase.appcheck.FirebaseAppCheck

/**
 * Attaches the App Check provider so every Firestore and Auth call carries proof it came from this
 * app.
 *
 * The Firestore rules prove *who* is calling — a signed-in account with a verified email. They
 * cannot prove *from where*: `google-services.json` ships inside the APK, so anyone who unzips it
 * can register an account with curl, verify the mail, and write to Firestore from a script that the
 * rules have no way to tell apart from the real app. App Check is the missing half.
 *
 * Which provider gets installed is decided by the source set, not by a branch here: the debug
 * variant supplies a debug provider (Play Integrity cannot attest an emulator), the release variant
 * supplies Play Integrity. See `AppCheckProvider` in `src/debug` and `src/release`.
 *
 * Must run before anything touches Firestore or Auth, so that the first request already carries a
 * token. [installed] guards against a second call within the same process; installing twice is not
 * an error, it just discards and re-creates the token exchange.
 *
 * Note this is *not* enforcement. Until App Check is switched to enforced in the Firebase console,
 * a failed attestation costs nothing — which is exactly what makes it safe to ship ahead of the
 * console change, while versionCode 8 without App Check is still the build most users are running.
 */
object AppCheckInstaller {
    private var installed = false

    fun installOnce() {
        if (installed) return
        installed = true
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(AppCheckProvider.factory())
    }
}
