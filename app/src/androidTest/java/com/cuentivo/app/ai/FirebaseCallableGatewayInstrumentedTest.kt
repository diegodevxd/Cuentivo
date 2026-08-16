package com.cuentivo.app.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented validation of the deployed `us-south1` callable from a debug build.
 *
 * Requires a Firebase user already authenticated on the device. Never signs out, never logs
 * tokens, uids or request ids, and sends no text, financial data or business context: the v1
 * contract is exactly contractVersion, requestId and action.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseCallableGatewayInstrumentedTest {

    @Before
    fun ensureDebugAppCheckProvider() {
        // SDK 19.3.0 removed the old AppCheckInstaller helper; the debug provider factory is the
        // supported replacement. Debug builds auto-install it, so an already-set factory is fine.
        try {
            FirebaseAppCheck.getInstance()
                .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        } catch (_: IllegalStateException) {
            // Already installed: the registered debug token applies.
        }
    }

    @Test
    fun deployedCallableReturnsExactNotConfiguredContract() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        assertNotNull(
            "Requires a Firebase user already authenticated on the device",
            currentUser,
        )

        val requestId = UUID.randomUUID().toString()
        val payload = mapOf(
            "contractVersion" to 1,
            "requestId" to requestId,
            "action" to "interpret_text",
        )

        val result = Tasks.await(
            FirebaseFunctions.getInstance("us-south1")
                .getHttpsCallable("interpretAiRequest")
                .call(payload),
        )

        val dataMap = result.data as? Map<*, *>
            ?: error("callable response must be a map")

        assertEquals(1L, (dataMap["contractVersion"] as? Number)?.toLong())
        assertEquals(requestId, dataMap["requestId"])
        assertEquals("AI_NOT_CONFIGURED", dataMap["status"])
        assertTrue(
            "response must carry exactly the v1 keys",
            dataMap.keys.map { it.toString() }.toSet() ==
                setOf("contractVersion", "requestId", "status"),
        )

        // The debug transport collapses the real response to the same fixed technical state.
        assertEquals(
            AiProviderResult.Unavailable("AI_NOT_CONFIGURED"),
            parseCallableData(dataMap, requestId),
        )
    }
}
