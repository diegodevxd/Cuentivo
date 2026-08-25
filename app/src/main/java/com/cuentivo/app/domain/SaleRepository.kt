package com.cuentivo.app.domain

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ExpectedSaleResult
import com.cuentivo.app.data.local.FinancialDao
import com.cuentivo.app.data.local.OperationEntity
import com.cuentivo.app.data.local.PaymentAllocationEntity
import com.cuentivo.app.data.local.PaymentEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.data.local.StoredPaymentBundle
import com.cuentivo.app.data.local.StoredSaleBundle

class SaleRepository(private val financialDao: FinancialDao) {
    suspend fun create(idempotencyKey: String, sale: SaleEntity, items: List<SaleItemEntity>, charge: AccountMovementEntity): String {
        require(items.isNotEmpty()) { "A sale needs at least one item." }
        require(charge.type == "CHARGE" && charge.direction == "DEBIT")
        return financialDao.createSaleIdempotently(
            operation = OperationEntity(idempotencyKey, "SALE", sale.id, sale.createdAtEpochMillis),
            sale = sale,
            items = items,
            charge = charge,
        )
    }

    suspend fun registerAgainstPreview(
        idempotencyKey: String,
        sale: SaleEntity,
        items: List<SaleItemEntity>,
        charge: AccountMovementEntity,
        expectedCustomerFingerprint: String,
        expectedProductFingerprints: Map<String, String>,
        payment: PaymentEntity?,
        paymentMovement: AccountMovementEntity?,
        paymentAllocations: List<PaymentAllocationEntity>,
        expectedInitialPaymentId: String = com.cuentivo.app.domain.SaleOperationIds.payment(idempotencyKey),
    ): ExpectedSaleResult = financialDao.registerSaleAgainstPreviewIdempotently(
        operation = OperationEntity(idempotencyKey, "SALE", sale.id, sale.createdAtEpochMillis),
        sale = sale,
        items = items,
        charge = charge,
        expectedCustomerFingerprint = expectedCustomerFingerprint,
        expectedProductFingerprints = expectedProductFingerprints,
        payment = payment,
        paymentMovement = paymentMovement,
        paymentAllocations = paymentAllocations,
        expectedInitialPaymentId = expectedInitialPaymentId,
    )

    suspend fun registerCompositeAgainstPreview(
        idempotencyKey: String,
        sale: SaleEntity,
        items: List<SaleItemEntity>,
        charge: AccountMovementEntity,
        expectedCustomerFingerprint: String,
        expectedProductFingerprints: Map<String, String>,
        payment: PaymentEntity?,
        paymentMovement: AccountMovementEntity?,
        paymentAllocations: List<PaymentAllocationEntity>,
        expectedInitialPaymentId: String = com.cuentivo.app.domain.SaleOperationIds.payment(idempotencyKey),
        newCustomer: CustomerEntity? = null,
        explicitCustomerCreateNew: Boolean = false,
        newProducts: List<ProductEntity> = emptyList(),
        explicitProductCreateNewByProductId: Map<String, Boolean> = emptyMap(),
    ): ExpectedSaleResult = financialDao.registerCompositeSaleAgainstPreviewIdempotently(
        operation = OperationEntity(idempotencyKey, "SALE", sale.id, sale.createdAtEpochMillis),
        sale = sale,
        items = items,
        charge = charge,
        expectedCustomerFingerprint = expectedCustomerFingerprint,
        expectedProductFingerprints = expectedProductFingerprints,
        payment = payment,
        paymentMovement = paymentMovement,
        paymentAllocations = paymentAllocations,
        expectedInitialPaymentId = expectedInitialPaymentId,
        newCustomer = newCustomer,
        explicitCustomerCreateNew = explicitCustomerCreateNew,
        newProducts = newProducts,
        explicitProductCreateNewByProductId = explicitProductCreateNewByProductId,
    )

    suspend fun storedBundle(
        businessId: String,
        saleId: String,
        createdCustomerId: String? = null,
        createdProductIds: List<String> = emptyList(),
    ): StoredSaleBundle? {
        val sale = financialDao.saleForId(saleId) ?: return null
        if (sale.businessId != businessId) return null
        val items = financialDao.itemsForSale(saleId)
        val charge = financialDao.chargeMovementForSale(saleId) ?: return null
        val allocations = financialDao.allAllocationsForSale(saleId)
        val paymentBundle = allocations.firstOrNull()?.let { allocation ->
            val payment = financialDao.paymentForId(allocation.paymentId)
            val paymentMovement = financialDao.paymentMovement(businessId, allocation.paymentId)
            if (payment != null && paymentMovement != null) {
                StoredPaymentBundle(payment, financialDao.allocationsForPayment(payment.id), paymentMovement)
            } else null
        }
        val createdCustomer = createdCustomerId?.let { financialDao.customerForPayment(it) }
        val createdProducts = createdProductIds.mapNotNull { financialDao.productForSale(it) }
        return StoredSaleBundle(
            sale = sale,
            items = items,
            chargeMovement = charge,
            paymentBundle = paymentBundle,
            createdCustomer = createdCustomer,
            createdProducts = createdProducts,
        )
    }
}
