package com.cuentivo.app.sync

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug variant: hands out a token tied to a debug secret instead of a Play attestation.
 *
 * Play Integrity attests a binary that Play installed, so it can never succeed on an emulator or on
 * a locally installed debug build — which is every environment the instrumented suite runs in.
 *
 * The token is only accepted once its debug secret is registered in the Firebase console under
 * App Check → the Android app → Manage debug tokens. The secret is printed to Logcat on first run:
 *
 *     D DebugAppCheckProvider: Enter this debug secret into the allow list in the Firebase Console
 *
 * The release source set declares this same object backed by Play Integrity. Splitting by source
 * set rather than branching on BuildConfig.DEBUG is what keeps [DebugAppCheckProviderFactory] out
 * of the release classpath entirely: a provider that issues tokens to anyone who asks would defeat
 * the point of turning App Check on.
 */
object AppCheckProvider {
    fun factory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
}
