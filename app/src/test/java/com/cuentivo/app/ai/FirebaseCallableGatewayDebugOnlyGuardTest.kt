package com.cuentivo.app.ai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard that the regional callable transport is debug-only: the Firebase Functions dependency is
 * scoped to debugImplementation and no release source references the callable at all.
 */
class FirebaseCallableGatewayDebugOnlyGuardTest {

    private val buildFile = File("build.gradle.kts").readText()

    @Test
    fun `firebase-functions is debugImplementation only`() {
        assertTrue(
            "build.gradle.kts must declare firebase-functions as debugImplementation",
            Regex("debugImplementation\\(\"com\\.google\\.firebase:firebase-functions\"\\)")
                .containsMatchIn(buildFile),
        )
        assertFalse(
            "build.gradle.kts must NOT declare firebase-functions as implementation",
            Regex("\\bimplementation\\(\"com\\.google\\.firebase:firebase-functions\"\\)")
                .containsMatchIn(buildFile),
        )
    }

    @Test
    fun `transport class lives in the debug source set only`() {
        assertFalse(
            "release source set must not contain the callable transport",
            File("src/main/java/com/cuentivo/app/ai/FirebaseCallableAiGateway.kt").exists(),
        )
        assertTrue(
            "debug source set must contain the callable transport",
            File("src/debug/java/com/cuentivo/app/ai/FirebaseCallableAiGateway.kt").exists(),
        )
    }

    @Test
    fun `release sources contain no callable references`() {
        val mainSources = File("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
        for (source in mainSources) {
            assertFalse(
                "release sources must not reference the callable transport",
                source.contains("getHttpsCallable") ||
                    source.contains("FirebaseFunctions") ||
                    source.contains("interpretAiRequest"),
            )
        }
    }
}
