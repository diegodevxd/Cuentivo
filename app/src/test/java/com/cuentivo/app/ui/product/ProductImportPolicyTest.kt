package com.cuentivo.app.ui.product

import com.cuentivo.app.domain.AccessDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the reason the Importar button used to stick on "Importando…" forever: progress was tied
 * to a `SheetImport` arriving, and the routes below never produce one.
 */
class ProductImportPolicyTest {
    @Test
    fun a_blocked_plan_never_starts_and_therefore_never_shows_progress() {
        val start = ProductImportPolicy.start("business-1", BLOCKED)

        assertEquals(ImportStart.Denied(BLOCKED), start)
        assertFalse(ProductImportPolicy.showsProgress(start))
    }

    @Test
    fun no_active_business_never_starts_and_therefore_never_shows_progress() {
        val start = ProductImportPolicy.start(null, AccessDecision.Allowed)

        assertEquals(ImportStart.NoBusiness, start)
        assertFalse(ProductImportPolicy.showsProgress(start))
    }

    @Test
    fun a_missing_business_outranks_the_entitlement_check() {
        // Nowhere to put the rows, so the plan is beside the point and no upgrade dialog appears.
        assertEquals(ImportStart.NoBusiness, ProductImportPolicy.start(null, BLOCKED))
    }

    @Test
    fun an_allowed_import_with_a_business_starts_and_owns_the_progress_flag() {
        val start = ProductImportPolicy.start("business-1", AccessDecision.Allowed)

        assertEquals(ImportStart.Begin("business-1"), start)
        assertTrue(ProductImportPolicy.showsProgress(start))
    }

    private companion object {
        val BLOCKED = AccessDecision.Blocked(
            title = "Función de Cuentivo Pro",
            detail = "Importar tu catálogo está incluido en Cuentivo Pro.",
        )
    }
}
