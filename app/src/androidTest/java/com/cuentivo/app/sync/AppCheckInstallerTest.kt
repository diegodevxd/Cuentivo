package com.cuentivo.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the wiring of App Check, not the attestation itself.
 *
 * The part worth a regression test is the source-set split: [AppCheckProvider] is declared twice,
 * once in `src/debug` and once in `src/release`, and nothing in the type system stops someone from
 * collapsing them into one file that branches on `BuildConfig.DEBUG`. That would drag
 * [DebugAppCheckProviderFactory] — which hands a token to whoever asks — into the release APK and
 * quietly undo the protection. This suite runs against the debug variant, so it can prove which
 * half it got.
 *
 * Whether Play Integrity actually accepts the binary cannot be tested here at all: it needs a build
 * signed by Play App Signing and installed from Play. The debug round trip below is the closest
 * approximation, and it stays opt-in because it needs a console change first.
 */
@RunWith(AndroidJUnit4::class)
class AppCheckInstallerTest {

    @Before
    fun initializeFirebase() {
        FirebaseApp.initializeApp(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun the_debug_variant_resolves_the_debug_provider() {
        assertTrue(
            "The debug build must not get Play Integrity: it can never attest an emulator. " +
                "Got ${AppCheckProvider.factory()::class.java.name}",
            AppCheckProvider.factory() is DebugAppCheckProviderFactory,
        )
    }

    @Test
    fun installing_twice_within_a_process_is_safe() {
        // MainActivity and SyncWorker both call this, and a Worker can run while the Activity is
        // alive. The second call has to be a no-op rather than a crash or a token reset.
        AppCheckInstaller.installOnce()
        AppCheckInstaller.installOnce()
    }

    /**
     * Opt-in: run with `-Pandroid.testInstrumentationRunnerArguments.appCheckToken=true` once the
     * debug secret from Logcat is registered in the Firebase console under App Check → Manage debug
     * tokens. Without that registration the exchange returns 403, and asserting on it would make the
     * suite fail for a reason that lives in a browser tab.
     */
    @Test
    fun a_registered_debug_token_exchanges_for_an_app_check_token() {
        assumeTrue(
            "Set -Pandroid.testInstrumentationRunnerArguments.appCheckToken=true after registering " +
                "the debug secret",
            InstrumentationRegistry.getArguments().getString("appCheckToken") == "true",
        )
        AppCheckInstaller.installOnce()
        val token = Tasks.await(FirebaseAppCheck.getInstance().getAppCheckToken(false))
        assertTrue("App Check returned an empty token", token.token.isNotEmpty())
    }
}
