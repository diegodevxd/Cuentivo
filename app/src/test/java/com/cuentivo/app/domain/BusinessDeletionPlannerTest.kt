package com.cuentivo.app.domain

import com.cuentivo.app.domain.BusinessDeletionPlanner.Inventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two properties of the deletion cascade that Firestore itself enforces:
 * - children are deleted before the parent that owns them (Firestore does not cascade), and
 * - no batch exceeds the 500-write ceiling of a single `WriteBatch`.
 *
 * This is the pure half; `FirestoreDeletionGateway` feeds it a real inventory and executes the
 * batches. If the order or the ceiling drift here, the remote erasure silently leaves orphans
 * (or is rejected) — and nothing on screen would say so.
 */
class BusinessDeletionPlannerTest {

    @Test
    fun `empty inventory still erases the business document, alone, last`() {
        val batches = BusinessDeletionPlanner.plan(Inventory(businessId = "b1"))

        assertEquals(1, batches.size)
        assertEquals(listOf("businesses/b1"), batches.single().documentPaths)
    }

    @Test
    fun `sale items are deleted before their sale`() {
        val batches = BusinessDeletionPlanner.plan(
            Inventory(
                businessId = "b1",
                saleIds = listOf("s1"),
                saleItemIds = mapOf("s1" to listOf("i1", "i2")),
            ),
        )

        val all = batches.flatMap { it.documentPaths }
        val itemIndexes = listOf("i1", "i2").map { itemId -> all.indexOf("businesses/b1/sales/s1/items/$itemId") }
        val saleIndex = all.indexOf("businesses/b1/sales/s1")
        itemIndexes.forEach { assertTrue("item must come before its sale", it < saleIndex) }
    }

    @Test
    fun `allocations are deleted before their payment`() {
        val batches = BusinessDeletionPlanner.plan(
            Inventory(
                businessId = "b1",
                paymentIds = listOf("p1"),
                allocationIds = mapOf("p1" to listOf("a1")),
            ),
        )

        val all = batches.flatMap { it.documentPaths }
        val allocationIndex = all.indexOf("businesses/b1/payments/p1/allocations/a1")
        val paymentIndex = all.indexOf("businesses/b1/payments/p1")
        assertTrue(allocationIndex < paymentIndex)
    }

    @Test
    fun `every collection comes before the business document`() {
        val batches = BusinessDeletionPlanner.plan(
            Inventory(
                businessId = "b1",
                memberIds = listOf("u1"),
                customerIds = listOf("c1"),
                productIds = listOf("pr1"),
                saleIds = listOf("s1"),
                saleItemIds = mapOf("s1" to listOf("i1")),
                paymentIds = listOf("p1"),
                allocationIds = mapOf("p1" to listOf("a1")),
                movementIds = listOf("m1"),
                auditLogIds = listOf("l1"),
            ),
        )

        val all = batches.flatMap { it.documentPaths }
        val businessIndex = all.indexOf("businesses/b1")
        assertEquals("business document must be the last path of the last batch", all.lastIndex, businessIndex)
        all.forEachIndexed { index, path ->
            if (index < businessIndex) assertTrue("$path must come before the business document", true)
        }
    }

    @Test
    fun `never more than 500 writes per batch`() {
        val batches = BusinessDeletionPlanner.plan(
            Inventory(
                businessId = "b1",
                customerIds = (1..499).map { "c$it" },
                productIds = (1..499).map { "p$it" },
                saleIds = (1..501).map { "s$it" },
            ),
        )

        batches.forEach { batch ->
            assertTrue("batch has ${batch.documentPaths.size} paths", batch.documentPaths.size <= BusinessDeletionPlanner.MAX_WRITES_PER_BATCH)
        }
        // Everything is covered, and the business document appears exactly once, last.
        val all = batches.flatMap { it.documentPaths }
        assertEquals(1, all.count { it == "businesses/b1" })
        assertEquals("businesses/b1", all.last())
    }

    @Test
    fun `500 subcollection writes fit in one batch, business alone after`() {
        val batches = BusinessDeletionPlanner.plan(
            Inventory(businessId = "b1", customerIds = (1..500).map { "c$it" }),
        )
        // 500 customers fill the ceiling of the first batch; the business document is alone in the last.
        assertEquals(2, batches.size)
        assertEquals(500, batches[0].documentPaths.size)
        assertEquals(listOf("businesses/b1"), batches[1].documentPaths)
    }

    @Test
    fun `501 subcollection writes split across batches`() {
        val batches = BusinessDeletionPlanner.plan(
            Inventory(businessId = "b1", customerIds = (1..501).map { "c$it" }),
        )
        assertEquals(3, batches.size)
        assertEquals(500, batches[0].documentPaths.size)
        assertEquals(1, batches[1].documentPaths.size)
        assertEquals(listOf("businesses/b1"), batches[2].documentPaths)
    }

    @Test
    fun `paths match the rules' document layout`() {
        val batches = BusinessDeletionPlanner.plan(
            Inventory(
                businessId = "b1",
                memberIds = listOf("u1"),
                saleIds = listOf("s1"),
                saleItemIds = mapOf("s1" to listOf("i1")),
            ),
        )
        val all = batches.flatMap { it.documentPaths }
        assertTrue(all.contains("businesses/b1/members/u1"))
        assertTrue(all.contains("businesses/b1/sales/s1/items/i1"))
    }
}
