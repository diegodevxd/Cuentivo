package com.cuentivo.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoFintechLayoutPolicyTest {

    @Test
    fun `320 dp uses the Stitch compact gutter and stacks dense content`() {
        val layout = NeoFintechLayoutPolicy.forWidth(320)

        assertEquals(NeoPhoneWidth.COMPACT, layout.widthClass)
        assertEquals(14, layout.horizontalPaddingDp)
        assertTrue(layout.stackPrimaryActions)
        assertTrue(layout.stackLedgerAmounts)
    }

    @Test
    fun `360 dp starts the standard phone layout`() {
        val layout = NeoFintechLayoutPolicy.forWidth(360)

        assertEquals(NeoPhoneWidth.STANDARD, layout.widthClass)
        assertEquals(16, layout.horizontalPaddingDp)
        assertFalse(layout.stackPrimaryActions)
        assertFalse(layout.stackLedgerAmounts)
    }

    @Test
    fun `411 dp remains standard`() {
        assertEquals(
            NeoPhoneWidth.STANDARD,
            NeoFintechLayoutPolicy.forWidth(411).widthClass,
        )
    }

    @Test
    fun `412 dp starts the large phone layout`() {
        val layout = NeoFintechLayoutPolicy.forWidth(412)

        assertEquals(NeoPhoneWidth.LARGE, layout.widthClass)
        assertEquals(20, layout.horizontalPaddingDp)
        assertFalse(layout.stackPrimaryActions)
        assertFalse(layout.stackLedgerAmounts)
    }

    @Test
    fun `480 dp retains the reference wide-phone gutter`() {
        assertEquals(20, NeoFintechLayoutPolicy.forWidth(480).horizontalPaddingDp)
    }

    @Test
    fun `wide layouts constrain content to the supported phone width`() {
        assertEquals(480, NeoFintechLayoutPolicy.forWidth(600).contentMaxWidthDp)
    }

    @Test
    fun `invalid widths fail closed to compact`() {
        assertEquals(
            NeoFintechLayoutPolicy.forWidth(320),
            NeoFintechLayoutPolicy.forWidth(-1),
        )
    }
}
