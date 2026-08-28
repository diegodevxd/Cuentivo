package com.cuentivo.app.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
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

    private fun assertAuthorizeResponse(
        requestId: String,
        raw: Any?,
        expectedDecision: String,
    ) {
        val data = raw as? Map<*, *> ?: error("callable response must be a map")
        assertEquals(1L, (data["contractVersion"] as? Number)?.toLong())
        assertEquals(requestId, data["requestId"])
        assertEquals(expectedDecision, data["decision"])
        assertEquals(if (expectedDecision == "AI_AUTHORIZED") "GEMINI" else null, data["provider"])
        assertEquals(false, data["fallbackUsed"])
        assertEquals(
            setOf("contractVersion", "requestId", "decision", "provider", "fallbackUsed"),
            data.keys.map(Any?::toString).toSet(),
        )
    }

    @Test
    fun deployedAuthorizeCallableReturnsExactContractAndReplayIsIdentical() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        assumeNotNull(
            "Skipping live gateway test: requires an authenticated Firebase user on the device",
            currentUser,
        )

        val expected = InstrumentationRegistry.getArguments()
            .getString("cuentivoAiExpectedDecision", "AI_AUTHORIZED")!!
        val requestId = UUID.randomUUID().toString()
        val payload = mapOf(
            "contractVersion" to 1,
            "requestId" to requestId,
            "action" to "authorize_text",
        )

        val callable = FirebaseFunctions.getInstance("us-south1").getHttpsCallable("authorizeAiAction")
        val first = Tasks.await(callable.call(payload))
        val replay = Tasks.await(callable.call(payload))

        assertAuthorizeResponse(requestId, first.data, expected)
        assertAuthorizeResponse(requestId, replay.data, expected)
        assertEquals(first.data, replay.data)
    }

    @Test
    fun callableWithoutAppCheckHeaderIsRejected() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        assumeNotNull(
            "Skipping live gateway test: requires an authenticated Firebase user on the device",
            currentUser,
        )

        // Raw HTTP probe: valid Authorization Bearer only, and deliberately no App Check header.
        // The callable wire protocol wraps the payload in a data envelope, so the body is exactly
        // {"data": {"contractVersion":1, requestId, action:"authorize_text"}} with no text.
        val idToken = Tasks.await(currentUser!!.getIdToken(false)).token
        val requestId = UUID.randomUUID().toString()
        val body = JSONObject()
            .put(
                "data",
                JSONObject()
                    .put("contractVersion", 1)
                    .put("requestId", requestId)
                    .put("action", "authorize_text"),
            )
            .toString()

        var statusCode = -1
        val connection = URL(
            "https://us-south1-organitazionlabs.cloudfunctions.net/authorizeAiAction",
        ).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $idToken")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            statusCode = connection.responseCode
        } finally {
            connection.disconnect()
        }

        assertEquals(
            "expected HTTP 401 rejection without the App Check header, got $statusCode",
            HttpURLConnection.HTTP_UNAUTHORIZED,
            statusCode,
        )
    }
}
