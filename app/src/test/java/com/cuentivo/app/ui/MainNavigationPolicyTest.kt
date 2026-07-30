package com.cuentivo.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationPolicyTest {
    @Test
    fun global_destinations_exclude_sale_action_and_keep_stitch_order() {
        assertEquals(
            listOf(
                MainDestination.HOME,
                MainDestination.CUSTOMERS,
                MainDestination.PRODUCTS,
                MainDestination.PAYMENTS,
            ),
            MainNavigationPolicy.destinations,
        )
    }

    @Test
    fun new_sale_action_keeps_the_current_destination_selected() {
        MainDestination.entries.forEach { destination ->
            assertEquals(destination, MainNavigationPolicy.destinationAfterNewSale(destination))
        }
    }
}
