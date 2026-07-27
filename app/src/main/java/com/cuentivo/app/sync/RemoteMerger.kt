package com.cuentivo.app.sync

import androidx.room.withTransaction
import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.BusinessMemberEntity
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.data.local.SyncStateEntity

/**
 * Outcome of one pull. Counts only — never document contents, names or amounts, because this is
 * what ends up in logs.
 */
data class RemoteMergeReport(
    val adopted: Int = 0,
    val overwritten: Int = 0,
    val cancellationsApplied: Int = 0,
    val skipped: Int = 0,
    val rejected: Int = 0,
) {
    val touched: Int get() = adopted + overwritten + cancellationsApplied
}

/**
 * Applies remote documents to Room under [RemoteMergePlanner]'s rules.
 *
 * Every document is merged individually and idempotently, so replaying the same batch is a no-op.
 * That is what lets the high-water mark be advanced with a deliberate overlap window instead of
 * having to be exact.
 */
class RemoteMerger(private val database: CuentivoDatabase) {

    suspend fun apply(businessId: String, documents: List<RemoteDocument>): RemoteMergeReport {
        if (documents.isEmpty()) return RemoteMergeReport()
        return database.withTransaction {
            var report = RemoteMergeReport()
            documents.forEach { document ->
                report = try {
                    merge(businessId, document, report)
                } catch (error: IllegalArgumentException) {
                    // A malformed remote document must not abort the whole batch, and must not be
                    // retried forever either. Dropping it here is safe: nothing local changed.
                    report.copy(rejected = report.rejected + 1)
                }
            }
            advanceWatermark(businessId, documents)
            report
        }
    }

    private suspend fun merge(businessId: String, document: RemoteDocument, report: RemoteMergeReport): RemoteMergeReport {
        val fields = document.fields
        return when (document.entityType) {
            "BUSINESS" -> {
                val remote = BusinessEntity(
                    id = document.entityId,
                    name = fields.text("name"),
                    ownerUserId = fields.text("ownerUserId"),
                    currency = fields.text("currency"),
                    timezone = fields.text("timezone"),
                    createdAtEpochMillis = fields.whole("createdAtEpochMillis"),
                    updatedAtEpochMillis = fields.whole("updatedAtEpochMillis"),
                )
                val local = database.businessDao().byId(remote.id)
                act(
                    entityType = document.entityType,
                    remoteUpdatedAt = remote.updatedAtEpochMillis,
                    local = local?.let { LocalRecord(it.updatedAtEpochMillis) },
                    sameContent = local == remote,
                    report = report,
                    write = { database.businessDao().upsert(remote) },
                )
            }

            "MEMBER" -> {
                // The Firestore document id is the uid; the local primary key is "businessId:uid".
                val remote = BusinessMemberEntity(
                    id = "$businessId:${document.entityId}",
                    businessId = fields.text("businessId"),
                    userId = fields.text("userId"),
                    role = fields.text("role"),
                    status = fields.text("status"),
                    invitedAtEpochMillis = fields.wholeOrNull("invitedAtEpochMillis"),
                    acceptedAtEpochMillis = fields.wholeOrNull("acceptedAtEpochMillis"),
                )
                val local = database.businessDao().memberFor(remote.businessId, remote.userId)
                act(
                    entityType = document.entityType,
                    // Members carry no updatedAt of their own; the acceptance timestamp is the only
                    // ordering signal available, and 0 makes an unaccepted invite always lose.
                    remoteUpdatedAt = remote.acceptedAtEpochMillis ?: 0L,
                    local = local?.let { LocalRecord(it.acceptedAtEpochMillis ?: 0L) },
                    sameContent = local == remote,
                    report = report,
                    write = { database.businessDao().upsertMember(remote) },
                )
            }

            "CUSTOMER" -> {
                val remote = CustomerEntity(
                    id = document.entityId,
                    businessId = fields.text("businessId"),
                    name = fields.text("name"),
                    normalizedName = fields.text("normalizedName"),
                    phone = fields.textOrNull("phone"),
                    email = fields.textOrNull("email"),
                    address = fields.textOrNull("address"),
                    notes = fields.textOrNull("notes"),
                    creditLimitCents = fields.centsOrNull("creditLimitCents"),
                    isActive = fields.flag("isActive"),
                    createdAtEpochMillis = fields.whole("createdAtEpochMillis"),
                    updatedAtEpochMillis = fields.whole("updatedAtEpochMillis"),
                    deletedAtEpochMillis = fields.wholeOrNull("deletedAtEpochMillis"),
                )
                require(remote.businessId == businessId) { "Customer belongs to another business." }
                val local = database.customerDao().byId(remote.id)
                act(
                    entityType = document.entityType,
                    remoteUpdatedAt = remote.updatedAtEpochMillis,
                    local = local?.let { LocalRecord(it.updatedAtEpochMillis) },
                    sameContent = local == remote,
                    report = report,
                    write = { database.customerDao().upsert(remote) },
                )
            }

            "PRODUCT" -> {
                val remote = ProductEntity(
                    id = document.entityId,
                    businessId = fields.text("businessId"),
                    name = fields.text("name"),
                    description = fields.textOrNull("description"),
                    sku = fields.textOrNull("sku"),
                    category = fields.textOrNull("category"),
                    salePriceCents = fields.cents("salePriceCents"),
                    costPriceCents = fields.centsOrNull("costPriceCents"),
                    stock = fields.wholeOrNull("stock"),
                    imageUri = fields.textOrNull("imageUri"),
                    isActive = fields.flag("isActive"),
                    createdAtEpochMillis = fields.whole("createdAtEpochMillis"),
                    updatedAtEpochMillis = fields.whole("updatedAtEpochMillis"),
                    deletedAtEpochMillis = fields.wholeOrNull("deletedAtEpochMillis"),
                )
                require(remote.businessId == businessId) { "Product belongs to another business." }
                val local = database.productDao().byId(remote.id)
                act(
                    entityType = document.entityType,
                    remoteUpdatedAt = remote.updatedAtEpochMillis,
                    local = local?.let { LocalRecord(it.updatedAtEpochMillis) },
                    sameContent = local == remote,
                    report = report,
                    write = { database.productDao().upsert(remote) },
                )
            }

            "SALE" -> {
                val remote = SaleEntity(
                    id = document.entityId,
                    businessId = fields.text("businessId"),
                    customerId = fields.text("customerId"),
                    sellerUserId = fields.text("sellerUserId"),
                    saleNumber = fields.text("saleNumber"),
                    saleDateEpochDay = fields.whole("saleDateEpochDay"),
                    dueDateEpochDay = fields.wholeOrNull("dueDateEpochDay"),
                    subtotalCents = fields.cents("subtotalCents"),
                    discountCents = fields.cents("discountCents"),
                    totalCents = fields.cents("totalCents"),
                    isCanceled = fields.flag("isCanceled"),
                    notes = fields.textOrNull("notes"),
                    createdAtEpochMillis = fields.whole("createdAtEpochMillis"),
                    updatedAtEpochMillis = fields.whole("updatedAtEpochMillis"),
                )
                require(remote.businessId == businessId) { "Sale belongs to another business." }
                require(remote.totalCents == remote.subtotalCents - remote.discountCents) {
                    "Sale total does not match its subtotal and discount."
                }
                val local = database.financialDao().saleForId(remote.id)
                act(
                    entityType = document.entityType,
                    remoteUpdatedAt = remote.updatedAtEpochMillis,
                    remoteCanceled = remote.isCanceled,
                    local = local?.let { LocalRecord(it.updatedAtEpochMillis, it.isCanceled) },
                    sameContent = local == remote,
                    report = report,
                    write = { database.financialDao().adoptSale(remote) },
                    cancel = { database.financialDao().markSaleCanceled(remote.id, remote.updatedAtEpochMillis) },
                )
            }

            "SALE_ITEM" -> {
                val remote = SaleItemEntity(
                    id = document.entityId,
                    saleId = fields.text("saleId"),
                    productId = fields.textOrNull("productId"),
                    productNameSnapshot = fields.text("productNameSnapshot"),
                    quantity = fields.whole("quantity"),
                    unitPriceCents = fields.cents("unitPriceCents"),
                    discountCents = fields.cents("discountCents"),
                    subtotalCents = fields.cents("subtotalCents"),
                )
                require(remote.subtotalCents == remote.quantity * remote.unitPriceCents - remote.discountCents) {
                    "Sale item subtotal does not match quantity, price and discount."
                }
                val local = database.financialDao().saleItemForId(remote.id)
                act(
                    entityType = document.entityType,
                    remoteUpdatedAt = document.watermarkEpochMillis,
                    local = local?.let { LocalRecord(document.watermarkEpochMillis) },
                    sameContent = local == remote,
                    report = report,
                    write = { database.financialDao().adoptSaleItem(remote) },
                )
            }

            "PAYMENT" -> {
                val remote = PaymentEntity(
                    id = document.entityId,
                    businessId = fields.text("businessId"),
                    customerId = fields.text("customerId"),
                    amountCents = fields.cents("amountCents"),
                    paymentMethod = fields.text("paymentMethod"),
                    paymentDateEpochDay = fields.whole("paymentDateEpochDay"),
                    reference = fields.textOrNull("reference"),
                    notes = fields.textOrNull("notes"),
                    createdByUserId = fields.text("createdByUserId"),
                    createdAtEpochMillis = fields.whole("createdAtEpochMillis"),
                    canceledAtEpochMillis = fields.wholeOrNull("canceledAtEpochMillis"),
                )
                require(remote.businessId == businessId) { "Payment belongs to another business." }
                require(remote.amountCents > 0) { "Payment must be positive." }
                val local = database.financialDao().paymentForId(remote.id)
                act(
                    entityType = document.entityType,
                    remoteUpdatedAt = remote.createdAtEpochMillis,
                    remoteCanceled = remote.canceledAtEpochMillis != null,
                    local = local?.let { LocalRecord(it.createdAtEpochMillis, it.canceledAtEpochMillis != null) },
                    sameContent = local == remote,
                    report = report,
                    write = { database.financialDao().adoptPayment(remote) },
                    cancel = {
                        database.financialDao().markPaymentCanceled(
                            remote.id,
                            requireNotNull(remote.canceledAtEpochMillis),
                        )
                    },
                )
            }

            "PAYMENT_ALLOCATION" -> {
                val remote = PaymentAllocationEntity(
                    id = document.entityId,
                    paymentId = fields.text("paymentId"),
                    saleId = fields.text("saleId"),
                    amountCents = fields.cents("amountCents"),
                )
                require(remote.amountCents > 0) { "Allocation must be positive." }
                val local = database.financialDao().allocationForId(remote.id)
                act(
                    entityType = document.entityType,
                    remoteUpdatedAt = document.watermarkEpochMillis,
                    local = local?.let { LocalRecord(document.watermarkEpochMillis) },
                    sameContent = local == remote,
                    report = report,
                    write = { database.financialDao().adoptAllocation(remote) },
                )
            }

            "MOVEMENT" -> {
                val remote = AccountMovementEntity(
                    id = document.entityId,
                    businessId = fields.text("businessId"),
                    customerId = fields.text("customerId"),
                    saleId = fields.textOrNull("saleId"),
                    paymentId = fields.textOrNull("paymentId"),
                    type = fields.text("type"),
                    amountCents = fields.cents("amountCents"),
                    direction = fields.text("direction"),
                    occurredAtEpochMillis = fields.whole("occurredAtEpochMillis"),
                    createdByUserId = fields.text("createdByUserId"),
                    reversesMovementId = fields.textOrNull("reversesMovementId"),
                    notes = fields.textOrNull("notes"),
                )
                require(remote.businessId == businessId) { "Movement belongs to another business." }
                // The ledger stores magnitude in amountCents and sign in direction. A negative
                // amount would silently invert every balance query that reads this row.
                require(remote.amountCents > 0) { "Movement amount must be positive." }
                require(remote.direction == "DEBIT" || remote.direction == "CREDIT") {
                    "Movement direction must be DEBIT or CREDIT."
                }
                val local = database.financialDao().movementForId(remote.id)
                act(
                    entityType = document.entityType,
                    remoteUpdatedAt = remote.occurredAtEpochMillis,
                    local = local?.let { LocalRecord(it.occurredAtEpochMillis) },
                    sameContent = local == remote,
                    report = report,
                    write = {
                        database.financialDao().adoptMovement(remote)
                        // Sales and payments are create-only in firestore.rules, so a cancellation
                        // can never arrive as an edit of those documents: this compensating
                        // movement is the only carrier. Sales are pulled before movements, so the
                        // row this flags has already landed.
                        when (remote.type) {
                            "CANCELLATION" -> remote.saleId?.let {
                                database.financialDao().markSaleCanceled(it, remote.occurredAtEpochMillis)
                            }
                            "REFUND" -> remote.paymentId?.let {
                                database.financialDao().markPaymentCanceled(it, remote.occurredAtEpochMillis)
                            }
                        }
                    },
                )
            }

            else -> report.copy(skipped = report.skipped + 1)
        }
    }

    private suspend fun act(
        entityType: String,
        remoteUpdatedAt: Long,
        remoteCanceled: Boolean = false,
        local: LocalRecord?,
        sameContent: Boolean,
        report: RemoteMergeReport,
        write: suspend () -> Unit,
        cancel: (suspend () -> Unit)? = null,
    ): RemoteMergeReport =
        when (RemoteMergePlanner.plan(entityType, remoteUpdatedAt, remoteCanceled, local, sameContent)) {
            RemoteMergeAction.Adopt -> {
                write()
                report.copy(adopted = report.adopted + 1)
            }

            RemoteMergeAction.Overwrite -> {
                write()
                report.copy(overwritten = report.overwritten + 1)
            }

            RemoteMergeAction.ApplyCancellation -> {
                cancel?.invoke()
                report.copy(cancellationsApplied = report.cancellationsApplied + 1)
            }

            is RemoteMergeAction.Skip -> report.copy(skipped = report.skipped + 1)
        }

    private suspend fun advanceWatermark(businessId: String, documents: List<RemoteDocument>) {
        val highest = documents.maxOf { it.watermarkEpochMillis }
        // Deliberately rewind. A device that was offline can upload documents stamped earlier than
        // ones already pulled, and those would otherwise fall permanently behind the mark. Every
        // merge above is idempotent, so re-reading the overlap costs a query and changes nothing.
        val safe = (highest - OVERLAP_MILLIS).coerceAtLeast(0L)
        val current = database.syncStateDao().lastPulledAt(businessId) ?: 0L
        if (safe > current) {
            database.syncStateDao().save(SyncStateEntity(businessId, safe))
        }
    }

    private companion object {
        const val OVERLAP_MILLIS = 5L * 60L * 1000L
    }
}

private fun Map<String, Any?>.text(key: String): String =
    requireNotNull(this[key] as? String) { "Missing text field: $key" }

private fun Map<String, Any?>.textOrNull(key: String): String? = this[key] as? String

private fun Map<String, Any?>.flag(key: String): Boolean =
    requireNotNull(this[key] as? Boolean) { "Missing boolean field: $key" }

private fun Map<String, Any?>.whole(key: String): Long =
    requireNotNull(wholeOrNull(key)) { "Missing integer field: $key" }

private fun Map<String, Any?>.wholeOrNull(key: String): Long? = when (val value = this[key]) {
    null -> null
    is Long -> value
    is Int -> value.toLong()
    // Anything else, including Double, is refused rather than coerced. Truncating here is how
    // fractional money silently enters an integer-cents system.
    else -> throw IllegalArgumentException("Field $key is not an integer: ${value::class.simpleName}")
}

/** Same contract as [whole], named at the call site so money reads are obvious. */
private fun Map<String, Any?>.cents(key: String): Long = whole(key)

private fun Map<String, Any?>.centsOrNull(key: String): Long? = wholeOrNull(key)
