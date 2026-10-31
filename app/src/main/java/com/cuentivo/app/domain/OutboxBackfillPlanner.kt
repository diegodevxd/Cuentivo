package com.cuentivo.app.domain

/**
 * Every kind of row the outbox can carry, as one list instead of eight string literals.
 *
 * The names are the wire format: they are what `SyncOperationEntity.entityType` stores and what
 * `FirestoreDocumentPath.forOperation` switches on, so [wireName] is not decoration and must not
 * drift from the strings already in the database.
 */
enum class SyncEntityType(val wireName: String) {
    BUSINESS("BUSINESS"),
    MEMBER("MEMBER"),
    CUSTOMER("CUSTOMER"),
    PRODUCT("PRODUCT"),
    SALE("SALE"),
    SALE_ITEM("SALE_ITEM"),
    PAYMENT("PAYMENT"),
    PAYMENT_ALLOCATION("PAYMENT_ALLOCATION"),
    MOVEMENT("MOVEMENT"),
}

/**
 * What to re-queue when an account becomes Pro, having written nothing to the cloud until now.
 *
 * The free plan does not sync, so nothing is enqueued while it is in force — that is what stops a
 * free account's abandoned businesses from landing in Firestore the moment it upgrades. The cost of
 * that choice is that upgrading finds an empty outbox and a full database, and this is what closes
 * the gap.
 *
 * Re-queueing is safe to repeat: `SyncOperationId.create` hashes
 * `businessId|entityType|entityId|revision` and the insert ignores conflicts, so seeding twice
 * writes each row once.
 *
 * **The risk this file exists to contain:** if the backfill silently skips a type, that data never
 * reaches the cloud for somebody who is now paying for exactly that. So the order and the coverage
 * are decided here, in pure code, and asserted by a test that fails when a new type is added
 * without being handled — rather than depending on whoever adds it remembering.
 */
object OutboxBackfillPlanner {

    /**
     * The order rows must be queued in, and it is load-bearing rather than tidy.
     *
     * `firestore.rules` gates every collection behind `isMember(businessId)`, which reads the
     * member document. Queue a customer before the business and the membership that authorises it
     * and the write is denied — then the row is parked as a permanent failure and never retried.
     * Business first, membership second, and everything else after.
     *
     * Within the rest, parents precede children for the same reason a sale item without its sale is
     * an orphan: nothing enforces it in Firestore, but a reader that arrives mid-drain sees a
     * coherent prefix instead of dangling references.
     */
    val ORDER: List<SyncEntityType> = listOf(
        SyncEntityType.BUSINESS,
        SyncEntityType.MEMBER,
        SyncEntityType.CUSTOMER,
        SyncEntityType.PRODUCT,
        SyncEntityType.SALE,
        SyncEntityType.SALE_ITEM,
        SyncEntityType.PAYMENT,
        SyncEntityType.PAYMENT_ALLOCATION,
        SyncEntityType.MOVEMENT,
    )

    /**
     * Which types to seed, in order, given how many rows each one has locally.
     *
     * Types with nothing stored are dropped rather than queued empty, so a brand-new account that
     * subscribes on day one costs nothing beyond its own business and membership.
     *
     * @param counts rows held in Room per type. A type absent from the map counts as zero.
     */
    fun plan(counts: Map<SyncEntityType, Int>): List<SyncEntityType> =
        ORDER.filter { (counts[it] ?: 0) > 0 }
}
