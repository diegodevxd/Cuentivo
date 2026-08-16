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
}
