package com.cuentivo.app.ai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for the instrumented callable validation: the test must exist in androidTest, must never
 * sign the user out, and must not log tokens, uids or request ids.
 */
class FirebaseCallableGatewayInstrumentedGuardTest {

    private val instrumentedTest =
        File("src/androidTest/java/com/cuentivo/app/ai/FirebaseCallableGatewayInstrumentedTest.kt")

    @Test
    fun `instrumented callable test exists`() {
        assertTrue(
            "missing androidTest FirebaseCallableGatewayInstrumentedTest",
            instrumentedTest.exists(),
        )
    }

    @Test
    fun `instrumented callable test never signs out`() {
        val source = instrumentedTest.readText()
        assertFalse("instrumented test must never call signOut", source.contains("signOut"))
    }

    @Test
    fun `instrumented callable test never logs sensitive values`() {
        val source = instrumentedTest.readText()
        for (forbidden in listOf("Log.d", "Log.i", "Log.w", "Log.e", "Log.v")) {
            assertFalse("instrumented test must not log: $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun `instrumented callable test probes app check absence over raw https`() {
        val source = instrumentedTest.readText()
        assertTrue(
            "missing raw-HttpsURLConnection probe for App Check absence",
            source.contains("HttpsURLConnection"),
        )
        assertTrue(
            "probe must use the callable wire body data wrapper",
            source.contains("\"data\""),
        )
        // The negative probe deliberately omits the App Check header; the literal must not appear.
        assertFalse(
            "negative probe must not send the App Check header",
            source.contains("X-Firebase-AppCheck"),
        )
        // App Check absence must map to the exact 401 rejection; broad >=400 would accept
        // ambiguous evidence such as 404 or 500.
        assertTrue(
            "negative probe must assert the HTTP_UNAUTHORIZED constant",
            source.contains("HTTP_UNAUTHORIZED"),
        )
        assertFalse(
            "negative probe must not accept ambiguous broad status ranges",
            source.contains(">= 400"),
        )
    }
}
