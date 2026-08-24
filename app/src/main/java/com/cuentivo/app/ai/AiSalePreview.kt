package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.domain.CustomerNameNormalizer
import java.util.Collections
import java.util.UUID

/** Result of matching a sale customer query against candidate rows. */
sealed interface AiSaleCustomerMatch {
    data object NotFound : AiSaleCustomerMatch

    data class Exact(val customer: CustomerEntity) : AiSaleCustomerMatch

    class SelectionRequired private constructor(
        rawCandidates: List<CustomerEntity>,
    ) : AiSaleCustomerMatch {
        val candidates: List<CustomerEntity> = Collections.unmodifiableList(rawCandidates.toList())

        companion object {
            operator fun invoke(candidates: List<CustomerEntity>): SelectionRequired =
                SelectionRequired(rawCandidates = candidates)
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is SelectionRequired && candidates == other.candidates)

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "SelectionRequired(candidates=$candidates)"
    }
}

/** Result of matching a sale product query against candidate rows. */
sealed interface AiSaleProductMatch {
    data object NotFound : AiSaleProductMatch

    data class Exact(val product: ProductEntity) : AiSaleProductMatch

    class SelectionRequired private constructor(
        rawCandidates: List<ProductEntity>,
    ) : AiSaleProductMatch {
        val candidates: List<ProductEntity> = Collections.unmodifiableList(rawCandidates.toList())

        companion object {
            operator fun invoke(candidates: List<ProductEntity>): SelectionRequired =
                SelectionRequired(rawCandidates = candidates)
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is SelectionRequired && candidates == other.candidates)

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "SelectionRequired(candidates=$candidates)"
    }
}

/** A safe-for-presentation customer candidate. */
data class AiSaleCustomerCandidate(val customerId: String, val displayName: String)

/** A safe-for-presentation product candidate. */
data class AiSaleProductCandidate(val productId: String, val displayName: String, val salePriceCents: Long)

/**
 * Resolved sale line with real product ID, name, quantity, unit price and line total.
 */
data class AiResolvedSaleLine(
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val lineTotalCents: Long,
    val sourceDeclaredPriceCents: Long,
    val sourceProductFingerprint: String,
)

/**
 * Resolved sale preview. All money fields are whole centavos in [Long].
 * Lines list is strictly unmodifiable on the JVM.
 */
class AiResolvedSalePreview private constructor(
    val confirmationKey: String,
    val customerId: String,
    val customerName: String,
    rawLines: List<AiResolvedSaleLine>,
    val totalCents: Long,
    val initialPaymentCents: Long,
    val remainderCents: Long,
    val promisedPaymentCandidate: PromisedPaymentCandidate?,
    val sourceCustomerFingerprint: String,
) {
    val lines: List<AiResolvedSaleLine> = Collections.unmodifiableList(rawLines.toList())

    companion object {
        operator fun invoke(
            confirmationKey: String,
            customerId: String,
            customerName: String,
            rawLines: List<AiResolvedSaleLine>,
            totalCents: Long,
            initialPaymentCents: Long,
            remainderCents: Long,
            promisedPaymentCandidate: PromisedPaymentCandidate? = null,
            sourceCustomerFingerprint: String = "",
        ): AiResolvedSalePreview = AiResolvedSalePreview(
            confirmationKey = confirmationKey,
            customerId = customerId,
            customerName = customerName,
            rawLines = rawLines,
            totalCents = totalCents,
            initialPaymentCents = initialPaymentCents,
            remainderCents = remainderCents,
            promisedPaymentCandidate = promisedPaymentCandidate,
            sourceCustomerFingerprint = sourceCustomerFingerprint,
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AiResolvedSalePreview &&
                confirmationKey == other.confirmationKey &&
                customerId == other.customerId &&
                customerName == other.customerName &&
                lines == other.lines &&
                totalCents == other.totalCents &&
                initialPaymentCents == other.initialPaymentCents &&
                remainderCents == other.remainderCents &&
                promisedPaymentCandidate == other.promisedPaymentCandidate &&
                sourceCustomerFingerprint == other.sourceCustomerFingerprint)

    override fun hashCode(): Int {
        var result = confirmationKey.hashCode()
        result = 31 * result + customerId.hashCode()
        result = 31 * result + customerName.hashCode()
        result = 31 * result + lines.hashCode()
        result = 31 * result + totalCents.hashCode()
        result = 31 * result + initialPaymentCents.hashCode()
        result = 31 * result + remainderCents.hashCode()
        result = 31 * result + (promisedPaymentCandidate?.hashCode() ?: 0)
        result = 31 * result + sourceCustomerFingerprint.hashCode()
        return result
    }

    override fun toString(): String =
        "AiResolvedSalePreview(confirmationKey=$confirmationKey, customerId=$customerId, customerName=$customerName, lines=$lines, totalCents=$totalCents, initialPaymentCents=$initialPaymentCents, remainderCents=$remainderCents, promisedPaymentCandidate=$promisedPaymentCandidate)"
}

enum class AiSaleBlockReason {
    INITIAL_PAYMENT_EXCEEDS_TOTAL,
    ARITHMETIC_OVERFLOW,
    INVALID_LINE_QUANTITY,
    INVALID_LINE_PRICE,
}

/** Outcome of resolving a validated sale proposal against the local catalog/customer data. */
sealed interface AiSalePreviewResult {
    data object CustomerNotFound : AiSalePreviewResult

    class CustomerSelectionRequired private constructor(
        rawCandidates: List<AiSaleCustomerCandidate>,
    ) : AiSalePreviewResult {
        val candidates: List<AiSaleCustomerCandidate> = Collections.unmodifiableList(rawCandidates.toList())

        companion object {
            operator fun invoke(candidates: List<AiSaleCustomerCandidate>): CustomerSelectionRequired =
                CustomerSelectionRequired(rawCandidates = candidates)
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is CustomerSelectionRequired && candidates == other.candidates)

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "CustomerSelectionRequired(candidates=$candidates)"
    }

    data class ProductNotFound(val productName: String) : AiSalePreviewResult

    class ProductSelectionRequired private constructor(
        val productName: String,
        rawCandidates: List<AiSaleProductCandidate>,
    ) : AiSalePreviewResult {
        val candidates: List<AiSaleProductCandidate> = Collections.unmodifiableList(rawCandidates.toList())

        companion object {
            operator fun invoke(
                productName: String,
                candidates: List<AiSaleProductCandidate>,
            ): ProductSelectionRequired = ProductSelectionRequired(
                productName = productName,
                rawCandidates = candidates,
            )
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is ProductSelectionRequired &&
                    productName == other.productName &&
                    candidates == other.candidates)

        override fun hashCode(): Int = 31 * productName.hashCode() + candidates.hashCode()

        override fun toString(): String = "ProductSelectionRequired(productName=$productName, candidates=$candidates)"
    }

    data class Ready(val preview: AiResolvedSalePreview) : AiSalePreviewResult

    data class Blocked(val reason: AiSaleBlockReason, val preview: AiResolvedSalePreview?) : AiSalePreviewResult

    data object Unavailable : AiSalePreviewResult
}

/**
 * Candidate matcher for customers and products in sale resolution.
 */
object AiSaleCandidateMatcher {

    fun matchCustomer(query: String, candidates: List<CustomerEntity>): AiSaleCustomerMatch {
        val normalizedQuery = CustomerNameNormalizer.key(query)
        val exactMatches = candidates.filter { it.normalizedName == normalizedQuery }
        return when (exactMatches.size) {
            1 -> AiSaleCustomerMatch.Exact(exactMatches.first())
            0 -> if (candidates.isEmpty()) {
                AiSaleCustomerMatch.NotFound
            } else {
                AiSaleCustomerMatch.SelectionRequired(candidates)
            }
            else -> AiSaleCustomerMatch.SelectionRequired(candidates)
        }
    }

    fun matchProduct(query: String, candidates: List<ProductEntity>): AiSaleProductMatch {
        val normalizedQuery = CustomerNameNormalizer.key(query)
        val exactMatches = candidates.filter { CustomerNameNormalizer.key(it.name) == normalizedQuery }
        return when (exactMatches.size) {
            1 -> AiSaleProductMatch.Exact(exactMatches.first())
            0 -> if (candidates.isEmpty()) {
                AiSaleProductMatch.NotFound
            } else {
                AiSaleProductMatch.SelectionRequired(candidates)
            }
            else -> AiSaleProductMatch.SelectionRequired(candidates)
        }
    }
}

/**
 * Read-only resolver that turns a validated [CreateSaleProposal] into a deterministic [AiResolvedSalePreview].
 * It never writes, confirms, or mutates any data.
 */
class AiSalePreviewResolver(
    private val searchCustomers: suspend (businessId: String, query: String) -> List<CustomerEntity>,
    private val searchProducts: suspend (businessId: String, query: String) -> List<ProductEntity>,
    private val newConfirmationKey: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun resolve(
        businessId: String,
        proposal: CreateSaleProposal,
    ): AiSalePreviewResult = try {
        resolveInternal(businessId, proposal)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        AiSalePreviewResult.Unavailable
    }

    private suspend fun resolveInternal(
        businessId: String,
        proposal: CreateSaleProposal,
    ): AiSalePreviewResult {
        if (businessId.isBlank()) {
            return AiSalePreviewResult.Unavailable
        }
        if (proposal.customerName.isBlank() || proposal.lines.isEmpty() || proposal.declaredInitialPaymentCents < 0L) {
            return AiSalePreviewResult.Unavailable
        }

        val rawCustomers = searchCustomers(businessId, proposal.customerName)
            .filter { it.businessId == businessId && it.isActive && it.deletedAtEpochMillis == null }
        val customerMatch = AiSaleCandidateMatcher.matchCustomer(proposal.customerName, rawCustomers)
        val customer = when (customerMatch) {
            is AiSaleCustomerMatch.NotFound -> return AiSalePreviewResult.CustomerNotFound
            is AiSaleCustomerMatch.SelectionRequired -> return AiSalePreviewResult.CustomerSelectionRequired(
                customerMatch.candidates.map { AiSaleCustomerCandidate(it.id, it.name) },
            )
            is AiSaleCustomerMatch.Exact -> customerMatch.customer
        }

        val resolvedLines = ArrayList<AiResolvedSaleLine>(proposal.lines.size)
        var totalCents = 0L

        for (line in proposal.lines) {
            if (line.quantity <= 0 || line.unitPriceCents < 0L || line.productName.isBlank()) {
                return AiSalePreviewResult.Unavailable
            }
            val rawProducts = searchProducts(businessId, line.productName)
                .filter { it.businessId == businessId && it.isActive && it.deletedAtEpochMillis == null }
            val productMatch = AiSaleCandidateMatcher.matchProduct(line.productName, rawProducts)
            val product = when (productMatch) {
                is AiSaleProductMatch.NotFound -> return AiSalePreviewResult.ProductNotFound(line.productName)
                is AiSaleProductMatch.SelectionRequired -> return AiSalePreviewResult.ProductSelectionRequired(
                    productName = line.productName,
                    candidates = productMatch.candidates.map {
                        AiSaleProductCandidate(it.id, it.name, it.salePriceCents)
                    },
                )
                is AiSaleProductMatch.Exact -> productMatch.product
            }

            val unitPrice = if (line.unitPriceCents > 0L) line.unitPriceCents else product.salePriceCents
            val lineTotal = try {
                Math.multiplyExact(line.quantity.toLong(), unitPrice)
            } catch (_: ArithmeticException) {
                return AiSalePreviewResult.Blocked(AiSaleBlockReason.ARITHMETIC_OVERFLOW, null)
            }

            try {
                totalCents = Math.addExact(totalCents, lineTotal)
            } catch (_: ArithmeticException) {
                return AiSalePreviewResult.Blocked(AiSaleBlockReason.ARITHMETIC_OVERFLOW, null)
            }

            resolvedLines.add(
                AiResolvedSaleLine(
                    productId = product.id,
                    productName = product.name,
                    quantity = line.quantity,
                    unitPriceCents = unitPrice,
                    lineTotalCents = lineTotal,
                    sourceDeclaredPriceCents = line.unitPriceCents,
                    sourceProductFingerprint = "${product.id}:${product.updatedAtEpochMillis}:${product.salePriceCents}",
                ),
            )
        }

        val remainderCents = try {
            Math.subtractExact(totalCents, proposal.declaredInitialPaymentCents)
        } catch (_: ArithmeticException) {
            return AiSalePreviewResult.Blocked(AiSaleBlockReason.ARITHMETIC_OVERFLOW, null)
        }

        if (remainderCents < 0L) {
            return AiSalePreviewResult.Blocked(
                reason = AiSaleBlockReason.INITIAL_PAYMENT_EXCEEDS_TOTAL,
                preview = AiResolvedSalePreview(
                    confirmationKey = "",
                    customerId = customer.id,
                    customerName = customer.name,
                    rawLines = resolvedLines,
                    totalCents = totalCents,
                    initialPaymentCents = proposal.declaredInitialPaymentCents,
                    remainderCents = remainderCents,
                    promisedPaymentCandidate = proposal.promisedPaymentCandidate,
                    sourceCustomerFingerprint = "${customer.id}:${customer.updatedAtEpochMillis}",
                ),
            )
        }

        val key = newConfirmationKey().trim()
        if (key.isBlank()) {
            return AiSalePreviewResult.Unavailable
        }

        return AiSalePreviewResult.Ready(
            AiResolvedSalePreview(
                confirmationKey = key,
                customerId = customer.id,
                customerName = customer.name,
                rawLines = resolvedLines,
                totalCents = totalCents,
                initialPaymentCents = proposal.declaredInitialPaymentCents,
                remainderCents = remainderCents,
                promisedPaymentCandidate = proposal.promisedPaymentCandidate,
                sourceCustomerFingerprint = "${customer.id}:${customer.updatedAtEpochMillis}",
            ),
        )
    }
}
