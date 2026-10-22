package com.cuentivo.app.sync

import com.cuentivo.app.BuildConfig
import com.cuentivo.app.FUNCTIONS_REGION
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring that points Firebase Functions at a local emulator.
 *
 * The rules it has to keep:
 *
 *  - with no host in the build, `useEmulator` is **never** called: the default build talks to
 *    production like any other;
 *  - with a host, Functions is pointed at `host:5001` in **the region the app's own callables
 *    live in**, because `FirebaseFunctions.getInstance` keeps one instance per region;
 *  - Auth and Firestore are not touched by this wiring at all;
 *  - it happens once per process: a second `useEmulator` on the same instance throws.
 *
 * `FirebaseFunctions` does not exist on the JVM, so the connector is injected. The code under test
 * is the code production runs -- the production object is the one that reads `BuildConfig`.
 */
class FirebaseEmulatorsTest {

    @Test
    fun `an empty host never points anything at the emulator`() {
        var calls = 0
        FunctionsEmulatorWiring("").connectIfEnabled { _, _, _ -> calls++ }
        assertEquals(0, calls)
    }

    @Test
    fun `a named host points Functions at the emulator of the app's own region`() {
        val calls = mutableListOf<Triple<String, String, Int>>()
        FunctionsEmulatorWiring("127.0.0.1").connectIfEnabled { region, host, port ->
            calls += Triple(region, host, port)
        }
        assertEquals(1, calls.size)
        assertEquals(FUNCTIONS_REGION, calls[0].first)
        assertEquals("127.0.0.1", calls[0].second)
        assertEquals(FUNCTIONS_EMULATOR_PORT, calls[0].third)
        assertEquals(5001, FUNCTIONS_EMULATOR_PORT)
    }

    @Test
    fun `the second call in the same process points nowhere`() {
        var calls = 0
        val wiring = FunctionsEmulatorWiring("127.0.0.1")
        wiring.connectIfEnabled { _, _, _ -> calls++ }
        wiring.connectIfEnabled { _, _, _ -> calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `the production wiring carries the host the build carries`() {
        assertEquals(BuildConfig.FUNCTIONS_EMULATOR_HOST, FirebaseEmulators.functionsEmulator.host)
    }

    @Test
    fun `the production wiring of a build without a host touches nothing`() {
        // The ordinary build (no `-Pcuentivo.functionsEmulatorHost`) has no host, and then the
        // wiring does not call `useEmulator` at all. A build that DID ask for a host is covered by
        // the wiring tests above, with the host given explicitly.
        if (BuildConfig.FUNCTIONS_EMULATOR_HOST.isNotEmpty()) return
        var calls = 0
        FirebaseEmulators.functionsEmulator.connectIfEnabled { _, _, _ -> calls++ }
        assertEquals(0, calls)
    }

    @Test
    fun `the release build type can never carry the host`() {
        val buildScript = File("build.gradle.kts")
        assertTrue("app/build.gradle.kts must exist", buildScript.exists())
        val source = buildScript.readText()
        val declarations = source.lines().filter { it.contains("FUNCTIONS_EMULATOR_HOST") }
        assertEquals("exactly one declaration per build type", 2, declarations.size)
        val debugDeclaration = declarations.single { it.contains("functionsEmulatorHost") }
        val releaseDeclaration = declarations.single { !it.contains("functionsEmulatorHost") }
        assertTrue(
            "the debug build type must carry the property",
            debugDeclaration.contains("buildConfigStringLiteral(functionsEmulatorHost)"),
        )
        assertTrue(
            "the release build type must carry the empty string, never the property",
            releaseDeclaration.contains("\"\\\"\\\"\""),
        )
        assertTrue(
            "the property must default to empty",
            source.contains("project.findProperty(\"cuentivo.functionsEmulatorHost\") as? String) ?: \"\""),
        )
    }

    @Test
    fun `every Functions gateway uses the shared region`() {
        val gateways = listOf(
            "src/main/java/com/cuentivo/app/ai/FirebaseAiAvailabilityGateway.kt",
            "src/main/java/com/cuentivo/app/ai/FirebaseCallableAiGateway.kt",
            "src/main/java/com/cuentivo/app/ai/ledger/LedgerExtractionGateway.kt",
            "src/main/java/com/cuentivo/app/billing/FirebaseCallableSubscriptionVerificationGateway.kt",
        )
        for (gateway in gateways) {
            val file = File(gateway)
            assertTrue("$gateway must exist", file.exists())
            val source = file.readText()
            assertTrue(
                "$gateway must take the instance of the shared region",
                source.contains("getInstance(FUNCTIONS_REGION)"),
            )
            assertFalse(
                "$gateway must not hardcode a region of its own",
                source.contains("getInstance(\"us-south1\")"),
            )
        }
    }
}
