package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxBackfillPlannerTest {

    /**
     * The guard the whole backfill rests on.
     *
     * Not syncing on the free plan means an upgrade finds an empty outbox, so anything the backfill
     * forgets is data a paying customer never gets to the cloud — and nothing would report it. Add
     * a tenth entity type without adding it to ORDER and this test fails, which is the point:
     * coverage is checked by the build rather than by whoever is editing that day.
     */
    @Test fun every_entity_type_is_covered_by_the_backfill_order() {
        assertEquals(SyncEntityType.entries.toSet(), OutboxBackfillPlanner.ORDER.toSet())
        assertEquals(
            "ORDER must not repeat a type",
            SyncEntityType.entries.size,
            OutboxBackfillPlanner.ORDER.size,
        )
    }

    /**
     * `firestore.rules` gates every collection behind the member document. A customer queued before
     * the business and the membership is denied, and a denial is parked permanently rather than
     * retried — so this ordering is the difference between a full upload and a silently truncated one.
     */
    @Test fun the_business_and_its_membership_are_queued_before_anything_else() {
        assertEquals(SyncEntityType.BUSINESS, OutboxBackfillPlanner.ORDER[0])
        assertEquals(SyncEntityType.MEMBER, OutboxBackfillPlanner.ORDER[1])
    }

    @Test fun parents_precede_their_children() {
        val order = OutboxBackfillPlanner.ORDER
        assertTrue(order.indexOf(SyncEntityType.SALE) < order.indexOf(SyncEntityType.SALE_ITEM))
        assertTrue(order.indexOf(SyncEntityType.PAYMENT) < order.indexOf(SyncEntityType.PAYMENT_ALLOCATION))
        assertTrue(order.indexOf(SyncEntityType.CUSTOMER) < order.indexOf(SyncEntityType.SALE))
    }

    @Test fun only_types_with_rows_are_seeded() {
        val plan = OutboxBackfillPlanner.plan(
            mapOf(
                SyncEntityType.BUSINESS to 1,
                SyncEntityType.MEMBER to 1,
                SyncEntityType.CUSTOMER to 3,
                SyncEntityType.PRODUCT to 0,
            )
        )
        assertEquals(
            listOf(SyncEntityType.BUSINESS, SyncEntityType.MEMBER, SyncEntityType.CUSTOMER),
            plan,
        )
    }

    /** A brand-new account that subscribes immediately costs nothing beyond what it actually has. */
    @Test fun an_empty_database_seeds_nothing() {
        assertEquals(emptyList<SyncEntityType>(), OutboxBackfillPlanner.plan(emptyMap()))
    }

    @Test fun the_plan_always_comes_back_in_order() {
        val everything = SyncEntityType.entries.associateWith { 1 }
        assertEquals(OutboxBackfillPlanner.ORDER, OutboxBackfillPlanner.plan(everything))
    }

    /** The names are the wire format already stored in `sync_operations`; drifting breaks the drain. */
    @Test fun wire_names_match_what_the_database_already_stores() {
        assertEquals("SALE_ITEM", SyncEntityType.SALE_ITEM.wireName)
        assertEquals("PAYMENT_ALLOCATION", SyncEntityType.PAYMENT_ALLOCATION.wireName)
        assertTrue(SyncEntityType.entries.all { it.wireName == it.name })
    }
}
