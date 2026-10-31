package com.cuentivo.app.sync

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release variant: Play Integrity attests that the request comes from an unmodified build of this
 * app, installed by Play, on a genuine device.
 *
 * Requires, on the console side: the SHA-256 of the **Play App Signing** certificate registered on
 * the Firebase Android app, the Firebase project linked to Play Console, and the app registered
 * under App Check with the Play Integrity provider. Missing any of those makes every attestation
 * fail, which is harmless while enforcement is off and total once it is on.
 *
 * See the debug source set for the counterpart used by the instrumented suite.
 */
object AppCheckProvider {
    fun factory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
}
