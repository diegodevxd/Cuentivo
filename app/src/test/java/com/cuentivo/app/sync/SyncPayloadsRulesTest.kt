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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds [SyncPayloads] and `firestore.rules` to the same set of keys.
 *
 * This is the one thing the whole outbox rests on and the one thing nothing was checking. The rules
 * reject a document with a key too many or too few, that rejection is classified **permanent**, the
 * row parks and never retries — and **nothing surfaces on screen**. The person sees a sale saved on
 * their phone and believes it reached the cloud. Getting all thirty-one call sites onto one builder
 * fixed today's copies; this is what stops tomorrow's column from silently un-fixing them.
 *
 * It reads the real `firestore.rules` rather than a copy, because a copy is one more thing that can
 * drift. Pure JVM: the entities are plain data classes, so no device is involved.
 */
class SyncPayloadsRulesTest {

    // ---- the rules, parsed ----

    /** What a `valid*` function demands: everything in [required], and nothing outside [allowed]. */
    private data class RuleKeys(val required: Set<String>, val allowed: Set<String>)

    private val rules: String by lazy { locateRules().readText() }

    /**
     * Walks up from the working directory, which Gradle sets to the module (`app/`) for unit tests
     * but which an IDE may set to the repository root. Looking for the file instead of assuming its
     * depth is what keeps this test from passing vacuously in one runner and failing in the other.
     */
    private fun locateRules(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "firestore.rules")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("firestore.rules not found walking up from ${File("").absolutePath}")
    }

    private fun ruleKeys(function: String): RuleKeys {
        // The parameter list varies: validCustomer takes (data), validSale takes (data, businessId),
        // validSaleItem takes (data, saleId). Matching them all beats hardcoding one and silently
        // skipping the rest.
        val body = Regex("function $function\\([^)]*\\)\\s*\\{(.*?)\\n    \\}", RegexOption.DOT_MATCHES_ALL)
            .find(rules)
            ?.groupValues
            ?.get(1)
            ?: throw AssertionError("$function not found in firestore.rules")
        // Two shapes are in use. `hasExactKeys(data, [...])` is the norm; validBusiness spells out
        // hasAll/hasOnly separately because it is the only document with an optional key.
        val exact = Regex("hasExactKeys\\(data,\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(body)
        if (exact != null) {
            val keys = quoted(exact.groupValues[1])
            return RuleKeys(required = keys, allowed = keys)
        }
        val all = Regex("hasAll\\(\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(body)
            ?: throw AssertionError("$function has neither hasExactKeys nor hasAll")
        val only = Regex("hasOnly\\(\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(body)
            ?: throw AssertionError("$function uses hasAll without hasOnly, so anything could be smuggled in")
        return RuleKeys(required = quoted(all.groupValues[1]), allowed = quoted(only.groupValues[1]))
    }

    private fun quoted(list: String): Set<String> =
        Regex("'([^']+)'").findAll(list).map { it.groupValues[1] }.toSet()

    // ---- the assertion every case shares ----

    private fun assertMatches(name: String, function: String, payload: Map<String, Any?>) {
        val expected = ruleKeys(function)
        val actual = payload.keys
        assertEquals(
            "$name is missing keys $function requires; the write would be rejected as permanent",
            emptySet<String>(),
            expected.required - actual,
        )
        assertEquals(
            "$name sends keys $function does not allow; the write would be rejected as permanent",
            emptySet<String>(),
            actual - expected.allowed,
        )
    }

    // ---- sample rows. Values are irrelevant; only the shape of the map is under test ----

    @Test fun `business payload matches validBusiness`() {
        assertMatches(
            "SyncPayloads.business", "validBusiness",
            SyncPayloads.business(BusinessEntity("b", "Mi negocio", "u", "MXN", "America/Mexico_City", 1L, 2L)),
        )
    }

    /**
     * The optional key must stay *out* of the ordinary payload. It is written directly by the
     * deletion request, not through the outbox, and sending it as null from here would be a key too
     * many on every normal business write — which `hasOnly` accepts but `hasAll` never asked for, and
     * which would quietly change what "no deletion requested" means on the server.
     */
    @Test fun `the business payload does not carry the deletion flag`() {
        val payload = SyncPayloads.business(BusinessEntity("b", "n", "u", "MXN", "tz", 1L, 2L))
        assertTrue(
            "deletionRequestedAtEpochMillis must not travel through the outbox",
            "deletionRequestedAtEpochMillis" !in payload.keys,
        )
        // And the rule really does treat it as optional, or the check above is meaningless.
        val keys = ruleKeys("validBusiness")
        assertEquals(setOf("deletionRequestedAtEpochMillis"), keys.allowed - keys.required)
    }

    @Test fun `member payload matches validMember`() {
        assertMatches(
            "SyncPayloads.member", "validMember",
            SyncPayloads.member(BusinessMemberEntity("b:u", "b", "u", "OWNER", "ACTIVE", 1L, 2L)),
        )
    }

    @Test fun `customer payload matches validCustomer`() {
        assertMatches(
            "SyncPayloads.customer", "validCustomer",
            SyncPayloads.customer(
                CustomerEntity("c", "b", "Ana", "ana", null, null, null, null, null, true, 1L, 2L, null),
            ),
        )
    }

    @Test fun `product payload matches validProduct`() {
        assertMatches(
            "SyncPayloads.product", "validProduct",
            SyncPayloads.product(
                ProductEntity("p", "b", "Jabón", null, null, null, 2_500L, null, null, null, true, 1L, 2L, null),
            ),
        )
    }

    @Test fun `sale payload matches validSale`() {
        assertMatches(
            "SyncPayloads.sale", "validSale",
            SyncPayloads.sale(
                SaleEntity("s", "b", "c", "u", "V-1", 20_000L, null, 100L, 0L, 100L, false, null, 1L, 2L),
            ),
        )
    }

    @Test fun `sale item payload matches validSaleItem`() {
        assertMatches(
            "SyncPayloads.saleItem", "validSaleItem",
            SyncPayloads.saleItem(SaleItemEntity("i", "s", null, "Jabón", 1L, 100L, 0L, 100L)),
        )
    }

    @Test fun `payment payload matches validPayment`() {
        assertMatches(
            "SyncPayloads.payment", "validPayment",
            SyncPayloads.payment(PaymentEntity("p", "b", "c", 100L, "CASH", 20_000L, null, null, "u", 1L, null)),
        )
    }

    @Test fun `allocation payload matches validAllocation`() {
        assertMatches(
            "SyncPayloads.allocation", "validAllocation",
            SyncPayloads.allocation(PaymentAllocationEntity("a", "p", "s", 100L)),
        )
    }

    @Test fun `movement payload matches validMovement`() {
        assertMatches(
            "SyncPayloads.movement", "validMovement",
            SyncPayloads.movement(
                AccountMovementEntity("m", "b", "c", "s", null, "CHARGE", 100L, "DEBIT", 1L, "u", null, null),
            ),
        )
    }

    /**
     * The document id is never a field. Every `valid*` uses `hasExactKeys`, so an `id` smuggled into
     * the map is a key too many and the write dies permanently — and it is the easiest mistake to
     * make, because the entity carries one.
     */
    @Test fun `no payload carries the local row id`() {
        val payloads = mapOf(
            "business" to SyncPayloads.business(BusinessEntity("b", "n", "u", "MXN", "tz", 1L, 2L)),
            "member" to SyncPayloads.member(BusinessMemberEntity("b:u", "b", "u", "OWNER", "ACTIVE", 1L, 2L)),
            "customer" to SyncPayloads.customer(
                CustomerEntity("c", "b", "Ana", "ana", null, null, null, null, null, true, 1L, 2L, null),
            ),
            "product" to SyncPayloads.product(
                ProductEntity("p", "b", "Jabón", null, null, null, 2_500L, null, null, null, true, 1L, 2L, null),
            ),
            "sale" to SyncPayloads.sale(
                SaleEntity("s", "b", "c", "u", "V-1", 20_000L, null, 100L, 0L, 100L, false, null, 1L, 2L),
            ),
            "saleItem" to SyncPayloads.saleItem(SaleItemEntity("i", "s", null, "Jabón", 1L, 100L, 0L, 100L)),
            "payment" to SyncPayloads.payment(
                PaymentEntity("p", "b", "c", 100L, "CASH", 20_000L, null, null, "u", 1L, null),
            ),
            "allocation" to SyncPayloads.allocation(PaymentAllocationEntity("a", "p", "s", 100L)),
            "movement" to SyncPayloads.movement(
                AccountMovementEntity("m", "b", "c", "s", null, "CHARGE", 100L, "DEBIT", 1L, "u", null, null),
            ),
        )
        payloads.forEach { (name, payload) ->
            assertTrue("$name must not send the local row id as a field", "id" !in payload.keys)
        }
    }
}
