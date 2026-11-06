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
 *
 * Las reglas en sí viven en `:shared` (`SyncRemoteImmutabilityRules`) para que el gateway de iOS
 * decida exactamente igual. Este objeto sólo añade lo que necesita el payload en texto: sacar el
 * `role` con `org.json`.
 */
object SyncRemoteImmutability {

    fun shouldCheckWhetherAlreadyPublished(
        entityType: String,
        payload: String,
        isPermissionDenied: Boolean,
    ): Boolean = SyncRemoteImmutabilityRules.shouldCheckWhetherAlreadyPublished(
        entityType = entityType,
        role = roleOf(payload),
        isPermissionDenied = isPermissionDenied,
    )

    /** True when an existing remote document leaves this operation with nothing left to do. */
    fun isSettledByExistence(entityType: String, payload: String): Boolean =
        SyncRemoteImmutabilityRules.isSettledByExistence(entityType, roleOf(payload))

    fun isAlreadyTheSameDocument(payload: Map<String, Any?>, remote: Map<String, Any?>): Boolean =
        SyncRemoteImmutabilityRules.isAlreadyTheSameDocument(payload, remote)

    private fun roleOf(payload: String): String? = runCatching {
        JSONObject(payload).optString("role").takeIf { it.isNotEmpty() }
    }.getOrNull()
}
