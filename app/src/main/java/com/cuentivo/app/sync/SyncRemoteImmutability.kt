package com.cuentivo.app.sync

import org.json.JSONObject

/**
 * Which queued rows describe a remote document that this client may never rewrite.
 *
 * The distinction matters because the outbox uploads with `set()`, and `set()` on a document that
 * already exists is an **update**. For every financial collection `firestore.rules` says
 * `allow update: if false`, so re-publishing a sale that is already in Firestore is denied — not
 * because anything is wrong, but because the document reached its final state and the rules are
 * doing their job. The owner's own membership is the same case through a different door: `allow
 * update` requires `resource.data.role != 'OWNER'`, so it can be created and never modified.
 *
 * Diego's phone had 117 rows parked like that. Every one of them named a document that was already
 * in Firestore, complete and correct. Nothing was lost; the outbox simply could not tell "already
 * done" apart from "rejected", and kept the difference as a permanent failure forever.
 *
 * So for these types, and only these, **existing at the right id is the goal state**: there is no
 * legitimate write this client could still make. Descriptive documents are deliberately excluded —
 * customers, products and the business itself accept updates, so a denial there can mean a payload
 * that stopped validating over a document holding older content, and treating that as done would
 * throw the correction away.
 */
object SyncRemoteImmutability {

    private val ALWAYS_IMMUTABLE = setOf(
        "SALE",
        "SALE_ITEM",
        "PAYMENT",
        "PAYMENT_ALLOCATION",
        "MOVEMENT",
    )

    /**
     * Whether a rejected upload is worth one extra read of the server.
     *
     * Two conditions, both required. The denial has to be PERMISSION_DENIED -- no other status means
     * "the rules said no". And the document has to be one this client could never rewrite, so that
     * finding it there settles the row instead of hiding a pending change.
     *
     * Kept as a decision of its own so the ordering is testable: the read must happen only after a
     * denial, never on the normal path.
     */
    fun shouldCheckWhetherAlreadyPublished(
        entityType: String,
        payload: String,
        isPermissionDenied: Boolean,
    ): Boolean = isPermissionDenied && isSettledByExistence(entityType, payload)

    /** True when an existing remote document leaves this operation with nothing left to do. */
    fun isSettledByExistence(entityType: String, payload: String): Boolean = when (entityType) {
        in ALWAYS_IMMUTABLE -> true
        // Only the OWNER row. A member the owner may still promote or deactivate is a document the
        // rules let this client update, so a denial there is a real rejection.
        "MEMBER" -> roleOf(payload) == "OWNER"
        else -> false
    }

    /**
     * Whether the document already up there is the very one this row was trying to publish.
     *
     * Existing is not the same as matching, and the difference matters. If the remote copy holds
     * something else under the same id, the row is not done -- it is a divergence, and these
     * documents can never be rewritten, so nobody would ever notice it again if the row were quietly
     * dropped. So: identical means finished, different means parked with an error that says so.
     *
     * Compared after normalising numbers, because the same cents arrive as Int from the queued JSON
     * and as Long from Firestore, and an amount that matches must not read as a conflict.
     */
    fun isAlreadyTheSameDocument(payload: Map<String, Any?>, remote: Map<String, Any?>): Boolean {
        if (payload.keys != remote.keys) return false
        return payload.keys.all { normalize(payload[it]) == normalize(remote[it]) }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null -> null
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong() else value
        is Float -> normalize(value.toDouble())
        is Number -> value.toLong()
        is Boolean, is String -> value
        else -> value.toString()
    }

    private fun roleOf(payload: String): String? = runCatching {
        JSONObject(payload).optString("role").takeIf { it.isNotEmpty() }
    }.getOrNull()
}
