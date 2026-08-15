package com.cuentivo.app.domain

/**
 * Decides what a deletion *request* may do — nothing more.
 *
 * This is the pure half of the idempotent flag write, mirroring the planner pattern of
 * [BusinessDeletionPlanner]: it decides but never touches Firestore. The gateway that executes it
 * (`sync/FirestoreSyncGateway.requestBusinessDeletion`) feeds it the state of the business document
 * **as read from the server**, and only acts on [Outcome.NeedsWrite].
 *
 * Why the decision exists at all: `firestore.rules` makes `deletionRequestedAtEpochMillis` immutable
 * once set (`deletionFlagImmutable`), so a request that rewrites it with a fresh timestamp on a
 * retry is denied. Before this existed both clients blind-wrote the flag with `Date.now()` on every
 * attempt; the first attempt could land, the cascade could fail partway, and the retry — with a new
 * timestamp — was rejected forever, stranding the erasure. The decision keeps every retry within the
 * rules: an absent flag is written exactly once, a present flag means the request already landed
 * (nothing is written, so the original marker survives), and a missing business document means a
 * prior cascade already finished the erasure.
 */
object DeletionRequestPolicy {

    sealed interface Outcome {
        /**
         * The business document no longer exists on the server: a prior cascade already erased it
         * (the business document is deleted last), so the request has nothing left to write.
         */
        data object AlreadyGone : Outcome

        /**
         * The owner already asked: the flag is on the document and is immutable. Nothing is written,
         * so the original `deletionRequestedAtEpochMillis` survives untouched.
         */
        data class AlreadyRequested(val deletionRequestedAtEpochMillis: Long) : Outcome

        /**
         * The owner is asking for the first time: the caller must write the flag exactly once,
         * merged so no other field is touched.
         */
        data object NeedsWrite : Outcome
    }

    fun decide(exists: Boolean, deletionRequestedAtEpochMillis: Long?): Outcome = when {
        !exists -> Outcome.AlreadyGone
        deletionRequestedAtEpochMillis != null -> Outcome.AlreadyRequested(deletionRequestedAtEpochMillis)
        else -> Outcome.NeedsWrite
    }
}
