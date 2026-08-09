package com.cuentivo.app.sync

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.CustomerEntity
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
 * `MainActivity` and [OutboxBackfill] still carry their own copies of these maps; they are being
 * moved over separately, where the only change in the diff is the move itself.
 */
object SyncPayloads {

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
