package com.cuentivo.app.domain

import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ExpectedSaleResult
import com.cuentivo.app.data.local.ExpectedLedgerBatchResult
import com.cuentivo.app.data.local.StoredLedgerBatchBundle
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

    suspend fun registerLedgerBatch(plan: PlannedLedgerBatch): ExpectedLedgerBatchResult =
        financialDao.registerLedgerBatchIdempotently(
            batchIdempotencyKey = plan.batchIdempotencyKey,
            businessId = plan.businessId,
            userId = plan.sellerUserId,
            nowEpochMillis = plan.nowEpochMillis,
            newCustomers = plan.newCustomers,
            sales = plan.sales,
            saleItems = plan.saleItems,
            chargeMovements = plan.chargeMovements,
            payments = plan.payments,
            paymentMovements = plan.paymentMovements,
            paymentAllocations = plan.paymentAllocations,
            executableOperationsCount = plan.executableOperationsCount,
        )

    suspend fun storedLedgerBatchBundle(
        businessId: String,
        batchIdempotencyKey: String,
        createdCustomerIds: List<String> = emptyList(),
        saleIds: List<String> = emptyList(),
        paymentIds: List<String> = emptyList(),
    ): StoredLedgerBatchBundle? {
        val opResult = financialDao.operationResultId(batchIdempotencyKey) ?: return null
        if (opResult != batchIdempotencyKey) return null

        val createdCustomers = createdCustomerIds.mapNotNull { customerId ->
            financialDao.customerForPayment(customerId)?.takeIf { it.businessId == businessId }
        }

        val sales = saleIds.mapNotNull { saleId ->
            financialDao.saleForId(saleId)?.takeIf { it.businessId == businessId }
        }

        val saleItems = sales.flatMap { financialDao.itemsForSale(it.id) }
        val chargeMovements = sales.mapNotNull { financialDao.chargeMovementForSale(it.id) }

        val payments = paymentIds.mapNotNull { paymentId ->
            financialDao.paymentForId(paymentId)?.takeIf { it.businessId == businessId }
        }
        val paymentAllocations = payments.flatMap { financialDao.allocationsForPayment(it.id) }
        val paymentMovements = payments.mapNotNull { financialDao.paymentMovement(businessId, it.id) }

        return StoredLedgerBatchBundle(
            batchIdempotencyKey = batchIdempotencyKey,
            businessId = businessId,
            createdCustomers = createdCustomers,
            sales = sales,
            saleItems = saleItems,
            chargeMovements = chargeMovements,
            payments = payments,
            paymentAllocations = paymentAllocations,
            paymentMovements = paymentMovements,
        )
    }

}
