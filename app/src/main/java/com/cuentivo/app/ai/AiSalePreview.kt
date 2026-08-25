package com.cuentivo.app.ai

import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.domain.CustomerNameNormalizer
import java.util.Collections
import java.util.UUID

/** Mode of entity resolution: either existing row or proposal for a new row. */
enum class AiEntityResolutionMode { EXISTING, NEW }

/** Explicit resolution choice for a candidate match. */
sealed interface AiEntityChoice {
    data class UseExisting(val entityId: String) : AiEntityChoice
    data object CreateNew : AiEntityChoice
}

/** Explicit caller choices for resolving customer and product ambiguities or new entities. */
class AiSaleResolutionChoices private constructor(
    val customer: AiEntityChoice?,
    rawProducts: Map<String, AiEntityChoice>,
) {
    val productsByNormalizedName: Map<String, AiEntityChoice> =
        Collections.unmodifiableMap(rawProducts.toMap())

    companion object {
        operator fun invoke(
            customer: AiEntityChoice? = null,
            productsByNormalizedName: Map<String, AiEntityChoice> = emptyMap(),
        ): AiSaleResolutionChoices = AiSaleResolutionChoices(
            customer = customer,
            rawProducts = productsByNormalizedName,
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AiSaleResolutionChoices &&
                customer == other.customer &&
                productsByNormalizedName == other.productsByNormalizedName)

    override fun hashCode(): Int {
        var result = customer?.hashCode() ?: 0
        result = 31 * result + productsByNormalizedName.hashCode()
        return result
    }

    override fun toString(): String =
        "AiSaleResolutionChoices(customer=$customer, productsByNormalizedName=$productsByNormalizedName)"
}

/** Resolved customer in an AI sale preview. */
data class AiResolvedSaleCustomer(
    val customerId: String,
    val displayName: String,
    val normalizedName: String,
    val mode: AiEntityResolutionMode,
    val sourceFingerprint: String?,
    val explicitCreateNew: Boolean = false,
)

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
 * Resolved sale line with real product ID, name, quantity, unit price, line total and resolution mode.
 */
data class AiResolvedSaleLine(
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val lineTotalCents: Long,
    val sourceDeclaredPriceCents: Long,
    val sourceFingerprint: String? = null,
    val resolutionMode: AiEntityResolutionMode = AiEntityResolutionMode.EXISTING,
    val explicitCreateNew: Boolean = false,
) {
    val sourceProductFingerprint: String get() = sourceFingerprint ?: ""
}

/**
 * Resolved sale preview. All money fields are whole centavos in [Long].
 * Lines list is strictly unmodifiable on the JVM.
 */
class AiResolvedSalePreview private constructor(
    val confirmationKey: String,
    val customer: AiResolvedSaleCustomer,
    rawLines: List<AiResolvedSaleLine>,
    val totalCents: Long,
    val initialPaymentCents: Long,
    val remainderCents: Long,
    val promisedPaymentCandidate: PromisedPaymentCandidate?,
) {
    val customerId: String get() = customer.customerId
    val customerName: String get() = customer.displayName
    val sourceCustomerFingerprint: String get() = customer.sourceFingerprint ?: ""
    val customerMode: AiEntityResolutionMode get() = customer.mode
    val lines: List<AiResolvedSaleLine> = Collections.unmodifiableList(rawLines.toList())

    val newCustomer: AiNewCustomerSpec?
        get() = if (customer.mode == AiEntityResolutionMode.NEW) {
            AiNewCustomerSpec(
                customerId = customer.customerId,
                displayName = customer.displayName,
                normalizedName = customer.normalizedName,
                explicitCreateNew = customer.explicitCreateNew,
            )
        } else null

    val newProducts: List<AiNewProductSpec>
        get() = lines
            .filter { it.resolutionMode == AiEntityResolutionMode.NEW }
            .distinctBy { it.productId }
            .map { line ->
                AiNewProductSpec(
                    productId = line.productId,
                    name = line.productName,
                    salePriceCents = line.sourceDeclaredPriceCents,
                    explicitCreateNew = line.explicitCreateNew,
                )
            }

    companion object {
        operator fun invoke(
            confirmationKey: String,
            customer: AiResolvedSaleCustomer,
            rawLines: List<AiResolvedSaleLine>,
            totalCents: Long,
            initialPaymentCents: Long,
            remainderCents: Long,
            promisedPaymentCandidate: PromisedPaymentCandidate? = null,
        ): AiResolvedSalePreview = AiResolvedSalePreview(
            confirmationKey = confirmationKey,
            customer = customer,
            rawLines = rawLines,
            totalCents = totalCents,
            initialPaymentCents = initialPaymentCents,
            remainderCents = remainderCents,
            promisedPaymentCandidate = promisedPaymentCandidate,
        )

        operator fun invoke(
            confirmationKey: String,
            customerId: String,
            customerName: String,
            rawLines: List<AiResolvedSaleLine>,
            totalCents: Long,
            initialPaymentCents: Long,
            remainderCents: Long,
            promisedPaymentCandidate: PromisedPaymentCandidate? = null,
            sourceCustomerFingerprint: String? = null,
            customerMode: AiEntityResolutionMode = AiEntityResolutionMode.EXISTING,
        ): AiResolvedSalePreview = AiResolvedSalePreview(
            confirmationKey = confirmationKey,
            customer = AiResolvedSaleCustomer(
                customerId = customerId,
                displayName = customerName,
                normalizedName = CustomerNameNormalizer.key(customerName),
                mode = customerMode,
                sourceFingerprint = sourceCustomerFingerprint,
            ),
            rawLines = rawLines,
            totalCents = totalCents,
            initialPaymentCents = initialPaymentCents,
            remainderCents = remainderCents,
            promisedPaymentCandidate = promisedPaymentCandidate,
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AiResolvedSalePreview &&
                confirmationKey == other.confirmationKey &&
                customer == other.customer &&
                lines == other.lines &&
                totalCents == other.totalCents &&
                initialPaymentCents == other.initialPaymentCents &&
                remainderCents == other.remainderCents &&
                promisedPaymentCandidate == other.promisedPaymentCandidate)

    override fun hashCode(): Int {
        var result = confirmationKey.hashCode()
        result = 31 * result + customer.hashCode()
        result = 31 * result + lines.hashCode()
        result = 31 * result + totalCents.hashCode()
        result = 31 * result + initialPaymentCents.hashCode()
        result = 31 * result + remainderCents.hashCode()
        result = 31 * result + (promisedPaymentCandidate?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AiResolvedSalePreview(confirmationKey=$confirmationKey, customer=$customer, lines=$lines, totalCents=$totalCents, initialPaymentCents=$initialPaymentCents, remainderCents=$remainderCents, promisedPaymentCandidate=$promisedPaymentCandidate)"
}

/** Pre-execution blocks that prevent confirming an impossible or contradictory sale. */
enum class AiSaleBlockReason {
    INITIAL_PAYMENT_EXCEEDS_TOTAL,
    ARITHMETIC_OVERFLOW,
    INVALID_LINE_QUANTITY,
    INVALID_LINE_PRICE,
    INCONSISTENT_NEW_PRODUCT_PRICE,
}

/** Outcome of resolving a validated sale proposal against the local catalog/customer data. */
sealed interface AiSalePreviewResult {
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
    private val newEntityId: (kind: String, name: String) -> String = { _, _ -> UUID.randomUUID().toString() },
) {

    suspend fun resolve(
        businessId: String,
        proposal: CreateSaleProposal,
        choices: AiSaleResolutionChoices = AiSaleResolutionChoices(),
    ): AiSalePreviewResult = try {
        resolveInternal(businessId, proposal, choices)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        AiSalePreviewResult.Unavailable
    }

    private suspend fun resolveInternal(
        businessId: String,
        proposal: CreateSaleProposal,
        choices: AiSaleResolutionChoices,
    ): AiSalePreviewResult {
        if (businessId.isBlank()) {
            return AiSalePreviewResult.Unavailable
        }
        if (proposal.customerName.isBlank() || proposal.lines.isEmpty() || proposal.declaredInitialPaymentCents < 0L) {
            return AiSalePreviewResult.Unavailable
        }
        val normalizedCustomerName = CustomerNameNormalizer.key(proposal.customerName)
        if (normalizedCustomerName.isBlank()) {
            return AiSalePreviewResult.Unavailable
        }

        val rawCustomers = searchCustomers(businessId, proposal.customerName)
            .filter { it.businessId == businessId && it.isActive && it.deletedAtEpochMillis == null }

        val resolvedCustomer: AiResolvedSaleCustomer = when (val customerChoice = choices.customer) {
            is AiEntityChoice.CreateNew -> {
                val newId = newEntityId("customer", proposal.customerName).trim()
                if (newId.isBlank()) return AiSalePreviewResult.Unavailable
                AiResolvedSaleCustomer(
                    customerId = newId,
                    displayName = proposal.customerName.trim(),
                    normalizedName = normalizedCustomerName,
                    mode = AiEntityResolutionMode.NEW,
                    sourceFingerprint = null,
                    explicitCreateNew = true,
                )
            }
            is AiEntityChoice.UseExisting -> {
                val existing = rawCustomers.firstOrNull { it.id == customerChoice.entityId }
                    ?: return AiSalePreviewResult.Unavailable
                AiResolvedSaleCustomer(
                    customerId = existing.id,
                    displayName = existing.name,
                    normalizedName = existing.normalizedName,
                    mode = AiEntityResolutionMode.EXISTING,
                    sourceFingerprint = "${existing.id}:${existing.updatedAtEpochMillis}",
                )
            }
            null -> {
                val customerMatch = AiSaleCandidateMatcher.matchCustomer(proposal.customerName, rawCustomers)
                when (customerMatch) {
                    is AiSaleCustomerMatch.NotFound -> {
                        val newId = newEntityId("customer", proposal.customerName).trim()
                        if (newId.isBlank()) return AiSalePreviewResult.Unavailable
                        AiResolvedSaleCustomer(
                            customerId = newId,
                            displayName = proposal.customerName.trim(),
                            normalizedName = normalizedCustomerName,
                            mode = AiEntityResolutionMode.NEW,
                            sourceFingerprint = null,
                        )
                    }
                    is AiSaleCustomerMatch.SelectionRequired -> return AiSalePreviewResult.CustomerSelectionRequired(
                        customerMatch.candidates.map { AiSaleCustomerCandidate(it.id, it.name) },
                    )
                    is AiSaleCustomerMatch.Exact -> {
                        val exact = customerMatch.customer
                        AiResolvedSaleCustomer(
                            customerId = exact.id,
                            displayName = exact.name,
                            normalizedName = exact.normalizedName,
                            mode = AiEntityResolutionMode.EXISTING,
                            sourceFingerprint = "${exact.id}:${exact.updatedAtEpochMillis}",
                        )
                    }
                }
            }
        }

        val resolvedLines = ArrayList<AiResolvedSaleLine>(proposal.lines.size)
        val newProductIds = mutableMapOf<String, String>()
        val newProductDeclaredPrices = mutableMapOf<String, Long>()
        var totalCents = 0L

        for (line in proposal.lines) {
            if (line.productName.isBlank()) {
                return AiSalePreviewResult.Unavailable
            }
            val normalizedProductName = CustomerNameNormalizer.key(line.productName)
            if (normalizedProductName.isBlank()) {
                return AiSalePreviewResult.Unavailable
            }
            if (line.quantity <= 0) {
                return AiSalePreviewResult.Blocked(AiSaleBlockReason.INVALID_LINE_QUANTITY, null)
            }
            if (line.unitPriceCents < 0L) {
                return AiSalePreviewResult.Blocked(AiSaleBlockReason.INVALID_LINE_PRICE, null)
            }

            val rawProducts = searchProducts(businessId, line.productName)
                .filter { it.businessId == businessId && it.isActive && it.deletedAtEpochMillis == null }

            val resolvedLine: AiResolvedSaleLine
            val productChoice = choices.productsByNormalizedName[normalizedProductName]

            val unitPrice = line.unitPriceCents
            val lineTotal = try {
                Math.multiplyExact(line.quantity.toLong(), unitPrice)
            } catch (_: ArithmeticException) {
                return AiSalePreviewResult.Blocked(AiSaleBlockReason.ARITHMETIC_OVERFLOW, null)
            }

            when (productChoice) {
                is AiEntityChoice.CreateNew -> {
                    if (unitPrice <= 0L) {
                        return AiSalePreviewResult.Blocked(AiSaleBlockReason.INVALID_LINE_PRICE, null)
                    }
                    val priorPrice = newProductDeclaredPrices[normalizedProductName]
                    if (priorPrice != null && priorPrice != unitPrice) {
                        return AiSalePreviewResult.Blocked(AiSaleBlockReason.INCONSISTENT_NEW_PRODUCT_PRICE, null)
                    }
                    newProductDeclaredPrices[normalizedProductName] = unitPrice

                    val productId = if (newProductIds.containsKey(normalizedProductName)) {
                        newProductIds.getValue(normalizedProductName)
                    } else {
                        val newId = newEntityId("product", line.productName).trim()
                        if (newId.isBlank() || newProductIds.values.contains(newId)) {
                            return AiSalePreviewResult.Unavailable
                        }
                        newProductIds[normalizedProductName] = newId
                        newId
                    }
                    resolvedLine = AiResolvedSaleLine(
                        productId = productId,
                        productName = line.productName.trim(),
                        quantity = line.quantity,
                        unitPriceCents = unitPrice,
                        lineTotalCents = lineTotal,
                        sourceDeclaredPriceCents = line.unitPriceCents,
                        sourceFingerprint = null,
                        resolutionMode = AiEntityResolutionMode.NEW,
                        explicitCreateNew = true,
                    )
                }
                is AiEntityChoice.UseExisting -> {
                    val existing = rawProducts.firstOrNull { it.id == productChoice.entityId }
                        ?: return AiSalePreviewResult.Unavailable
                    resolvedLine = AiResolvedSaleLine(
                        productId = existing.id,
                        productName = existing.name,
                        quantity = line.quantity,
                        unitPriceCents = unitPrice,
                        lineTotalCents = lineTotal,
                        sourceDeclaredPriceCents = line.unitPriceCents,
                        sourceFingerprint = "${existing.id}:${existing.updatedAtEpochMillis}:${existing.salePriceCents}",
                        resolutionMode = AiEntityResolutionMode.EXISTING,
                    )
                }
                null -> {
                    val productMatch = AiSaleCandidateMatcher.matchProduct(line.productName, rawProducts)
                    when (productMatch) {
                        is AiSaleProductMatch.NotFound -> {
                            if (unitPrice <= 0L) {
                                return AiSalePreviewResult.Blocked(AiSaleBlockReason.INVALID_LINE_PRICE, null)
                            }
                            val priorPrice = newProductDeclaredPrices[normalizedProductName]
                            if (priorPrice != null && priorPrice != unitPrice) {
                                return AiSalePreviewResult.Blocked(AiSaleBlockReason.INCONSISTENT_NEW_PRODUCT_PRICE, null)
                            }
                            newProductDeclaredPrices[normalizedProductName] = unitPrice

                            val productId = if (newProductIds.containsKey(normalizedProductName)) {
                                newProductIds.getValue(normalizedProductName)
                            } else {
                                val newId = newEntityId("product", line.productName).trim()
                                if (newId.isBlank() || newProductIds.values.contains(newId)) {
                                    return AiSalePreviewResult.Unavailable
                                }
                                newProductIds[normalizedProductName] = newId
                                newId
                            }
                            resolvedLine = AiResolvedSaleLine(
                                productId = productId,
                                productName = line.productName.trim(),
                                quantity = line.quantity,
                                unitPriceCents = unitPrice,
                                lineTotalCents = lineTotal,
                                sourceDeclaredPriceCents = line.unitPriceCents,
                                sourceFingerprint = null,
                                resolutionMode = AiEntityResolutionMode.NEW,
                            )
                        }
                        is AiSaleProductMatch.SelectionRequired -> return AiSalePreviewResult.ProductSelectionRequired(
                            productName = line.productName,
                            candidates = productMatch.candidates.map {
                                AiSaleProductCandidate(it.id, it.name, it.salePriceCents)
                            },
                        )
                        is AiSaleProductMatch.Exact -> {
                            val exact = productMatch.product
                            resolvedLine = AiResolvedSaleLine(
                                productId = exact.id,
                                productName = exact.name,
                                quantity = line.quantity,
                                unitPriceCents = unitPrice,
                                lineTotalCents = lineTotal,
                                sourceDeclaredPriceCents = line.unitPriceCents,
                                sourceFingerprint = "${exact.id}:${exact.updatedAtEpochMillis}:${exact.salePriceCents}",
                                resolutionMode = AiEntityResolutionMode.EXISTING,
                            )
                        }
                    }
                }
            }

            try {
                totalCents = Math.addExact(totalCents, lineTotal)
            } catch (_: ArithmeticException) {
                return AiSalePreviewResult.Blocked(AiSaleBlockReason.ARITHMETIC_OVERFLOW, null)
            }

            resolvedLines.add(resolvedLine)
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
                    customer = resolvedCustomer,
                    rawLines = resolvedLines,
                    totalCents = totalCents,
                    initialPaymentCents = proposal.declaredInitialPaymentCents,
                    remainderCents = remainderCents,
                    promisedPaymentCandidate = proposal.promisedPaymentCandidate,
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
                customer = resolvedCustomer,
                rawLines = resolvedLines,
                totalCents = totalCents,
                initialPaymentCents = proposal.declaredInitialPaymentCents,
                remainderCents = remainderCents,
                promisedPaymentCandidate = proposal.promisedPaymentCandidate,
            ),
        )
    }
}
