package com.cuentivo.app.ai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for the regional callable transport in T6:
 * - firebase-functions is included as implementation for both debug and release;
 * - firebase-appcheck-debug remains debugImplementation only;
 * - transport gateway lives in main source set with Tasks.await isolated on Dispatchers.IO;
 * - debug App Check provider does not leak into main or release sources.
 */
class FirebaseCallableGatewayDebugOnlyGuardTest {

    private val buildFile = File("build.gradle.kts").readText()

    @Test
    fun `firebase-functions is implementation and appcheck-debug is debugImplementation`() {
        assertTrue(
            "build.gradle.kts must declare firebase-functions as implementation",
            Regex("\\bimplementation\\(\"com\\.google\\.firebase:firebase-functions\"\\)")
                .containsMatchIn(buildFile),
        )
        assertTrue(
            "build.gradle.kts must declare firebase-appcheck-debug as debugImplementation",
            Regex("debugImplementation\\(\"com\\.google\\.firebase:firebase-appcheck-debug\"\\)")
                .containsMatchIn(buildFile),
        )
        assertFalse(
            "build.gradle.kts must NOT declare firebase-appcheck-debug as implementation",
            Regex("\\bimplementation\\(\"com\\.google\\.firebase:firebase-appcheck-debug\"\\)")
                .containsMatchIn(buildFile),
        )
    }

    @Test
    fun `transport class lives in the main source set`() {
        assertTrue(
            "main source set must contain the callable transport",
            File("src/main/java/com/cuentivo/app/ai/FirebaseCallableAiGateway.kt").exists(),
        )
        assertFalse(
            "debug source set must not duplicate the callable transport",
            File("src/debug/java/com/cuentivo/app/ai/FirebaseCallableAiGateway.kt").exists(),
        )
    }

    @Test
    fun `blocking Tasks await runs on IO instead of the Android main thread`() {
        val transportSource =
            File("src/main/java/com/cuentivo/app/ai/FirebaseCallableAiGateway.kt").readText()
        assertTrue(
            "Tasks.await must be isolated in Dispatchers.IO",
            transportSource.contains("withContext(Dispatchers.IO)"),
        )
        assertTrue(transportSource.contains("Tasks.await(task)"))
    }

    @Test
    fun `debug app check provider is not in main or release sources`() {
        val nonDebugSources = (File("src/main/java").walkTopDown() + File("src/release/java").walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
        for (source in nonDebugSources) {
            assertFalse(
                "main/release sources must not reference DebugAppCheckProvider",
                source.contains("DebugAppCheckProvider") || source.contains("debugAppCheck"),
            )
        }
    }
}
