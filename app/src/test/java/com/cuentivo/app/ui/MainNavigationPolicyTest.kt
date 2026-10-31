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

    @Test
    fun the_bar_marks_the_sale_action_and_no_destination_while_a_sale_is_open() {
        MainDestination.entries.forEach { destination ->
            assertEquals(destination, MainNavigationPolicy.barSelection(destination, newSaleActive = false))
            assertEquals(null, MainNavigationPolicy.barSelection(destination, newSaleActive = true))
        }
    }

    @Test
    fun a_customer_detail_marks_the_customers_tab_from_wherever_it_was_opened() {
        MainDestination.entries.forEach { destination ->
            assertEquals(
                MainDestination.CUSTOMERS,
                MainNavigationPolicy.barSelection(destination, newSaleActive = false, detailOpen = true),
            )
        }
        // A sale still wins over the detail: it is the task actually on screen.
        assertEquals(
            null,
            MainNavigationPolicy.barSelection(MainDestination.CUSTOMERS, newSaleActive = true, detailOpen = true),
        )
    }

    @Test
    fun tapping_the_current_selection_resets_its_root_and_an_unselected_tab_opens() {
        MainDestination.entries.forEach { destination ->
            // Already on this tab, nothing else open: the tap resets it to its root.
            assertEquals(
                TabTapAction.RESET_ROOT,
                MainNavigationPolicy.tabTapAction(destination, destination),
            )
            // A sale owns the screen and marks no tab; a tap opens the selected tab.
            assertEquals(
                TabTapAction.OPEN,
                MainNavigationPolicy.tabTapAction(null, destination),
            )
            // A different tab is simply opened.
            val other = MainDestination.entries.first { it != destination }
            assertEquals(
                TabTapAction.OPEN,
                MainNavigationPolicy.tabTapAction(destination, other),
            )
        }
    }

    @Test
    fun tapping_the_customers_tab_from_a_customer_detail_resets_its_root() {
        MainDestination.entries.forEach { underlyingDestination ->
            val selectedByOpenDetail = MainNavigationPolicy.barSelection(
                underlyingDestination,
                newSaleActive = false,
                detailOpen = true,
            )

            assertEquals(MainDestination.CUSTOMERS, selectedByOpenDetail)
            assertEquals(
                TabTapAction.RESET_ROOT,
                MainNavigationPolicy.tabTapAction(
                    selectedByOpenDetail!!,
                    MainDestination.CUSTOMERS,
                ),
            )
        }
    }
}
