package com.cuentivo.app.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the deletion contract in `firestore.rules` to the shape the client depends on.
 *
 * The rules are the enforcement: no client delete works unless the same verified owner asked for the
 * business to be erased (`deletionRequestedAtEpochMillis` present, one-way). The cascade in
 * [com.cuentivo.app.domain.BusinessDeletionPlanner] and [FirestoreDeletionGateway] is only a mirror
 * of this file — if the two drift, the cascade is either silently denied or, worse, allowed without
 * the owner's request. Like [SyncPayloadsRulesTest], this reads the real rules file rather than a
 * copy, so it cannot drift from what actually protects the data.
 */
class DeletionRulesContractTest {

    private val rules: String by lazy { locateRules().readText() }

    private fun locateRules(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "firestore.rules")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("firestore.rules not found walking up from ${File("").absolutePath}")
    }

    @Test
    fun `no collection keeps an unconditional delete ban`() {
        // Every `allow delete` must have been replaced by the one-way door. A leftover `delete: if
        // false` means the cascade would be denied on that collection in silence.
        assertFalse(
            "every delete rule must be gated by canDeleteForDeletion; found a bare delete ban",
            rules.contains("allow delete: if false"),
        )
    }

    @Test
    fun `the business document delete is gated on the one-way door`() {
        assertTrue(rules.contains("allow delete: if canDeleteForDeletion(businessId);"))
    }

    @Test
    fun `every subcollection delete is gated on the one-way door`() {
        // The five financial collections plus customers, products, auditLogs, and sale items /
        // allocations each gate their delete on canDeleteForDeletion. members adds the owner's
        // ordinary right to remove a non-owner, which predates the cascade and must survive it.
        listOf(
            "match /members/{memberUserId}",
            "match /customers/{customerId}",
            "match /products/{productId}",
            "match /sales/{saleId}",
            "match /items/{itemId}",
            "match /payments/{paymentId}",
            "match /allocations/{allocationId}",
            "match /movements/{movementId}",
            "match /auditLogs/{auditId}",
        ).forEach { blockHeader ->
            assertTrue(
                "delete on $blockHeader must be gated by canDeleteForDeletion",
                blockText(blockHeader).contains("allow delete: if canDeleteForDeletion(businessId)"),
            )
        }
    }

    @Test
    fun `financial collections stay create-only for ordinary writes`() {
        // The append-only invariant survives: updates are still forbidden everywhere financial.
        listOf(
            "match /sales/{saleId}",
            "match /items/{itemId}",
            "match /payments/{paymentId}",
            "match /allocations/{allocationId}",
            "match /movements/{movementId}",
        ).forEach { block ->
            val blockText = blockText(block)
            assertTrue("$block must deny update", blockText.contains("allow update: if false;"))
        }
    }

    @Test
    fun `audit logs stay write-forbidden except the cascade`() {
        val auditBlock = blockText("match /auditLogs/{auditId}")
        assertTrue(auditBlock.contains("allow create, update: if false;"))
        assertTrue(auditBlock.contains("allow delete: if canDeleteForDeletion(businessId);"))
    }

    @Test
    fun `the deletion flag cannot be removed or changed once set`() {
        // The business update rule must keep the flag immutable when it is present.
        assertTrue(
            "business update must enforce deletionFlagImmutable",
            rules.contains("deletionFlagImmutable(request.resource.data, resource.data)"),
        )
        val immutable = functionText("deletionFlagImmutable")
        assertTrue(
            "an update dropping the flag must be rejected",
            immutable.contains("!('deletionRequestedAtEpochMillis' in resourceData)"),
        )
        assertTrue(
            "the flag value must survive an update unchanged",
            immutable.contains("requestData.deletionRequestedAtEpochMillis == resourceData.deletionRequestedAtEpochMillis"),
        )
    }

    @Test
    fun `the one-way door requires the verified owner`() {
        val canDelete = functionText("canDeleteForDeletion")
        assertTrue(canDelete.contains("isBusinessOwner(businessId)"))
        assertTrue(canDelete.contains("deletionRequested(businessId)"))
        val requested = functionText("deletionRequested")
        assertTrue(requested.contains("isAuthenticated()"))
        assertTrue(requested.contains("'deletionRequestedAtEpochMillis' in"))
    }

    @Test
    fun `the flag stays the only optional key of the business document`() {
        val validBusiness = functionText("validBusiness")
        val allowed = Regex("hasOnly\\(\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(validBusiness)?.groupValues?.get(1) ?: throw AssertionError("hasOnly not found")
        val required = Regex("hasAll\\(\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(validBusiness)?.groupValues?.get(1) ?: throw AssertionError("hasAll not found")
        val quoted = { list: String -> Regex("'([^']+)'").findAll(list).map { it.groupValues[1] }.toSet() }
        assertEquals(
            setOf("deletionRequestedAtEpochMillis"),
            quoted(allowed) - quoted(required),
        )
    }

    @Test
    fun `a verified user may read a business document that no longer exists`() {
        // The erasure cascade deletes the business document last. A retry must be able to *see*
        // that absence — an empty snapshot — instead of being told PERMISSION_DENIED, or "already
        // deleted" is indistinguishable from "forbidden" and the account can never finish deleting
        // itself. The allowance is narrow: it fires only when the document is missing, and still
        // demands a verified session (the same gate every other function here hangs off).
        val readRule = businessReadRule()
        assertTrue(
            "the business read rule must allow reading a missing document",
            readRule.contains("resource == null"),
        )
        assertTrue(
            "reading a missing document must still require a verified session",
            readRule.contains("isAuthenticated()"),
        )
    }

    @Test
    fun `the missing-document allowance never opens existing documents`() {
        // `resource == null` is false for an existing document, so its read must still be gated on
        // ownership or an ACTIVE membership. A stranger probing a business they do not belong to
        // keeps getting PERMISSION_DENIED, and the client therefore never wipes local or Auth.
        val readRule = businessReadRule()
        assertTrue(
            "existing documents must still require ownership",
            readRule.contains("isOwner(resource.data.ownerUserId)"),
        )
        assertTrue(
            "existing documents must still require membership",
            readRule.contains("canReadBusiness(businessId)"),
        )
        assertFalse("the read rule must never be unconditional", readRule.contains("allow read: if true"))
    }

    /** The `allow read` line of the business document itself — the first read rule in its block. */
    private fun businessReadRule(): String {
        val block = blockText("match /businesses/{businessId}")
        return Regex("allow read: if [^;]+;").find(block)?.value
            ?: throw AssertionError("business read rule not found")
    }

    /** The text of a `match` block, delimited by its closing brace at the same indentation. */
    private fun blockText(header: String): String {
        val index = rules.indexOf(header)
        if (index < 0) throw AssertionError("match block $header not found")
        // The header itself carries path wildcards like `{saleId}`; the block-opening brace is the
        // first one after the header ends, so counting must start there.
        val open = rules.indexOf("{", index + header.length)
        var depth = 0
        for (i in open until rules.length) {
            when (rules[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return rules.substring(index, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced braces after $header")
    }

    private fun functionText(name: String): String {
        val body = Regex("function $name\\([^)]*\\)\\s*\\{(.*?)\\n    \\}", RegexOption.DOT_MATCHES_ALL)
            .find(rules)
            ?.groupValues
            ?.get(1)
            ?: throw AssertionError("function $name not found in firestore.rules")
        return body
    }
}
