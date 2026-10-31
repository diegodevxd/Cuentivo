package com.cuentivo.app.sync

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.BusinessMemberEntity
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity

/**
 * The one place a row becomes the document Firestore expects.
 *
 * These maps have to match `firestore.rules` **exactly**: `hasExactKeys()` rejects a document with a
 * key too many or too few, and a rejection is classified permanent — the row parks and never
 * retries. Nothing surfaces on screen when that happens, which is what makes a hand-copied payload
 * such an expensive mistake.
 *
 * Each builder takes the entity rather than loose locals on purpose. The call sites that assembled
 * the map by hand from whatever variables were nearby are exactly the ones that dropped a field: a
 * quick sale once published `dueDateEpochDay` as a hard-coded null while the row saved locally had
 * the real date.
 *
 * Every enqueue in the app goes through here: `MainActivity`, [OutboxBackfill] and
 * [OwnerMembershipBootstrap]. That is the point — the backfill and the write paths used to keep
 * separate hand-written copies of the same map, so adding a column to an entity meant remembering
 * two places, and forgetting one published a document that validates on one route and is rejected
 * on the other. **If a column is added to an entity, it is added here, once.**
 */
object SyncPayloads {

    /**
     * The local row id is the Firestore document id, so it is deliberately not a key here —
     * `validBusiness` would reject it.
     *
     * `deletionRequestedAtEpochMillis` is likewise absent, and must stay absent: it is the one
     * optional key in the schema, written directly by the deletion request rather than through the
     * outbox (the outbox drains after the Auth account is gone). Sending it as `null` from here
     * would be a key too many for `hasAll`/`hasOnly` on every ordinary business write.
     */
    fun business(business: BusinessEntity): Map<String, Any?> = mapOf(
        "name" to business.name,
        "ownerUserId" to business.ownerUserId,
        "currency" to business.currency,
        "timezone" to business.timezone,
        "createdAtEpochMillis" to business.createdAtEpochMillis,
        "updatedAtEpochMillis" to business.updatedAtEpochMillis,
    )

    /**
     * Six keys, no more and no fewer — `validMember` uses `hasExactKeys`.
     *
     * The local row id is `"$businessId:$userId"`, and it is **not** what the document is named:
     * `match /members/{memberUserId}` requires `data.userId == memberUserId`. Publishing under the
     * composite id cost a whole upload once — the rejection parks permanently, membership never
     * lands, and every later write fails `isMember()`. The caller passes `member.userId` as the
     * entity id; this map only has to carry the fields.
     */
    fun member(member: BusinessMemberEntity): Map<String, Any?> = mapOf(
        "businessId" to member.businessId,
        "userId" to member.userId,
        "role" to member.role,
        "status" to member.status,
        "invitedAtEpochMillis" to member.invitedAtEpochMillis,
        "acceptedAtEpochMillis" to member.acceptedAtEpochMillis,
    )

    fun customer(customer: CustomerEntity): Map<String, Any?> = mapOf(
        "businessId" to customer.businessId,
        "name" to customer.name,
        "normalizedName" to customer.normalizedName,
        "phone" to customer.phone,
        "email" to customer.email,
        "address" to customer.address,
        "notes" to customer.notes,
        "creditLimitCents" to customer.creditLimitCents,
        "isActive" to customer.isActive,
        "createdAtEpochMillis" to customer.createdAtEpochMillis,
        "updatedAtEpochMillis" to customer.updatedAtEpochMillis,
        "deletedAtEpochMillis" to customer.deletedAtEpochMillis,
    )

    fun product(product: ProductEntity): Map<String, Any?> = mapOf(
        "businessId" to product.businessId,
        "name" to product.name,
        "description" to product.description,
        "sku" to product.sku,
        "category" to product.category,
        "salePriceCents" to product.salePriceCents,
        "costPriceCents" to product.costPriceCents,
        "stock" to product.stock,
        "imageUri" to product.imageUri,
        "isActive" to product.isActive,
        "createdAtEpochMillis" to product.createdAtEpochMillis,
        "updatedAtEpochMillis" to product.updatedAtEpochMillis,
        "deletedAtEpochMillis" to product.deletedAtEpochMillis,
    )

    fun sale(sale: SaleEntity): Map<String, Any?> = mapOf(
        "businessId" to sale.businessId,
        "customerId" to sale.customerId,
        "sellerUserId" to sale.sellerUserId,
        "saleNumber" to sale.saleNumber,
        "saleDateEpochDay" to sale.saleDateEpochDay,
        "dueDateEpochDay" to sale.dueDateEpochDay,
        "subtotalCents" to sale.subtotalCents,
        "discountCents" to sale.discountCents,
        "totalCents" to sale.totalCents,
        "isCanceled" to sale.isCanceled,
        "notes" to sale.notes,
        "createdAtEpochMillis" to sale.createdAtEpochMillis,
        "updatedAtEpochMillis" to sale.updatedAtEpochMillis,
    )

    fun saleItem(item: SaleItemEntity): Map<String, Any?> = mapOf(
        "saleId" to item.saleId,
        // Null whenever the seller wrote the line by hand or the row came from the old web import.
        // The rules allow it; the name and price on the line are what the sale is made of.
        "productId" to item.productId,
        "productNameSnapshot" to item.productNameSnapshot,
        "quantity" to item.quantity,
        "unitPriceCents" to item.unitPriceCents,
        "discountCents" to item.discountCents,
        "subtotalCents" to item.subtotalCents,
    )

    /**
     * `canceledAtEpochMillis` travels even though it is null on every payment this app publishes:
     * `validPayment` lists it in `hasExactKeys`, so leaving it out is a key too few. A cancellation
     * is not an edit to this document — payments are create-only — it arrives as its own REFUND
     * movement, and the seal on the local row is what `RemoteMerger` re-applies on the way down.
     */
    fun payment(payment: PaymentEntity): Map<String, Any?> = mapOf(
        "businessId" to payment.businessId,
        "customerId" to payment.customerId,
        "amountCents" to payment.amountCents,
        "paymentMethod" to payment.paymentMethod,
        "paymentDateEpochDay" to payment.paymentDateEpochDay,
        "reference" to payment.reference,
        "notes" to payment.notes,
        "createdByUserId" to payment.createdByUserId,
        "createdAtEpochMillis" to payment.createdAtEpochMillis,
        "canceledAtEpochMillis" to payment.canceledAtEpochMillis,
    )

    fun allocation(allocation: PaymentAllocationEntity): Map<String, Any?> = mapOf(
        "paymentId" to allocation.paymentId,
        "saleId" to allocation.saleId,
        "amountCents" to allocation.amountCents,
    )

    fun movement(movement: AccountMovementEntity): Map<String, Any?> = mapOf(
        "businessId" to movement.businessId,
        "customerId" to movement.customerId,
        "saleId" to movement.saleId,
        "paymentId" to movement.paymentId,
        "type" to movement.type,
        "amountCents" to movement.amountCents,
        "direction" to movement.direction,
        "occurredAtEpochMillis" to movement.occurredAtEpochMillis,
        "createdByUserId" to movement.createdByUserId,
        "reversesMovementId" to movement.reversesMovementId,
        "notes" to movement.notes,
    )
}
