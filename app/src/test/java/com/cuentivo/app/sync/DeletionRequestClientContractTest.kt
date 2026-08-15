package com.cuentivo.app.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the deletion *request* client (the Android gateway) to the idempotent shape the
 * immutable flag demands, the same way [DeletionRulesContractTest] holds the rules.
 *
 * The bug this guards against: the client wrote `deletionRequestedAtEpochMillis` with a fresh
 * timestamp on every attempt, but `deletionFlagImmutable` in `firestore.rules` rejects any update
 * that moves the flag. After a partial failure the retry was denied and the erasure could never
 * resume. The contract: read the business document from the server first; write the flag only when
 * it is absent; never blind-update it.
 *
 * This reads the real source files rather than copies, so it cannot drift from what actually runs.
 * Pure JVM: it only does string checks against files on disk.
 */
class DeletionRequestClientContractTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "firestore.rules").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found walking up from ${File("").absolutePath}")
    }

    private val gatewaySource: String by lazy {
        File(repoRoot(), "app/src/main/java/com/cuentivo/app/sync/FirestoreSyncGateway.kt").readText()
    }

    // ---- Android ----

    @Test
    fun `android request decides through the pure policy`() {
        assertTrue(
            "the gateway must route the request decision through DeletionRequestPolicy.decide",
            gatewaySource.contains("DeletionRequestPolicy.decide"),
        )
    }

    @Test
    fun `android request reads the business from the server, never the cache`() {
        // A cached read could answer "no flag" to a retry whose flag already landed, causing the
        // rewrite that the rules deny. The decision must come from Source.SERVER.
        assertTrue(
            "the gateway must read the business document with Source.SERVER",
            gatewaySource.contains(".get(Source.SERVER)"),
        )
    }

    @Test
    fun `android request never blind-updates the immutable flag`() {
        assertFalse(
            "an unconditional .update of deletionRequestedAtEpochMillis would change the immutable flag on retry and be denied forever",
            gatewaySource.contains(".update(\"deletionRequestedAtEpochMillis\""),
        )
    }

    @Test
    fun `android request writes the flag as a partial merge, not a full overwrite`() {
        assertTrue(
            "the one write of the flag must be a merge so no other field is touched",
            gatewaySource.contains("SetOptions.merge()"),
        )
    }
}
