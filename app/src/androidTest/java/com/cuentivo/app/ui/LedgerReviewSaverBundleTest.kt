package com.cuentivo.app.ui

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.ai.ledger.LedgerDetectedPageDate
import com.cuentivo.app.ai.ledger.LedgerDiscardDiagnostic
import com.cuentivo.app.ai.ledger.LedgerDiscardReason
import com.cuentivo.app.ai.ledger.LedgerFieldConfidence
import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerLineItem
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerRegion
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import com.cuentivo.app.ui.ai.LedgerReviewSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Bundle round trip of the review saver, on a real Android runtime.
 *
 * This cannot run on the JVM: this project has no Robolectric, and the android.jar Bundle is a
 * stub that throws. So the trip a review actually takes across Activity recreation — written into
 * a Bundle, marshalled through a Parcel, read back — is exercised here, on the emulator, against
 * the same machinery that will carry it when the system kills the process mid-review.
 */
@RunWith(AndroidJUnit4::class)
class LedgerReviewSaverBundleTest {

    /** The heaviest legitimate row: every optional field filled. */
    private fun heavyOperation(lineIndex: Int, edited: Boolean = false) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = if (edited) "Rosa Martinez Corregida" else "Rosa Martnez",
        customerId = if (edited) "cust-rosa" else null,
        amountCents = if (edited) "38000" else "25000",
        description = "2 cocas y un pan grande de la esquina",
        date = SafeDateCandidate(2026, 7, 30),
        uncertainFields = if (edited) emptyList() else listOf(UncertainLedgerField.CUSTOMER),
        unreadableFields = emptyList(),
        region = LedgerRegion(0.1, 0.2, 0.3, 0.4),
        confidence = LedgerFieldConfidence(customer = 0.93, concept = 0.61, amount = 0.99, date = 0.88, kind = 0.97),
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT, date = LedgerFieldSource.PAGE_DATE),
        rawText = "\" 250 protector tapete azul de los grandes",
        items = listOf(
            LedgerLineItem("coca 600ml", quantity = 2, subtotalCents = "25000"),
            LedgerLineItem("pan blanco", quantity = 1, subtotalCents = "13000"),
        ),
        warnings = listOf("nombre heredado de comilla", "monto escrito a mano"),
    )

    private fun savedReview(operations: List<LedgerOperation>) = LedgerReviewSaver.SavedLedgerReview(
        sessionId = "session-1",
        businessId = "business-1",
        operations = operations,
        discards = listOf(
            LedgerDiscardDiagnostic(inputIndex = 0, lineIndex = null, reason = LedgerDiscardReason.MALFORMED),
            LedgerDiscardDiagnostic(inputIndex = 3, lineIndex = 7, reason = LedgerDiscardReason.OUT_OF_ORDER),
        ),
        unreadTextRemaining = true,
        pageIncomplete = true,
        pageDateEpochDay = 20634L,
        todayEpochDay = 20700L,
        detectedPageDate = LedgerDetectedPageDate(SafeDateCandidate(2026, 7, 30), 0.95),
        // El baseline tambien viaja en el Bundle: si se perdiera, las correcciones se contarian
        // contra la version ya editada y saldrian siempre en cero.
        originalOperations = operations.map { it.copy(customerId = null, amountCents = "25000") },
    )

    @Test
    fun a_review_with_edits_survives_the_bundle_round_trip() {
        val original = savedReview(
            listOf(heavyOperation(0, edited = true), heavyOperation(1, edited = false)),
        )

        val restored = LedgerReviewSaver.fromBundle(LedgerReviewSaver.toBundle(original))

        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun an_empty_bundle_restores_as_no_review() {
        // The screen's Saver writes an empty Bundle when there is no review; that must come back
        // as null, not as a crash and not as a phantom review.
        assertNull(LedgerReviewSaver.fromBundle(Bundle()))
    }

    @Test
    fun a_bundle_missing_its_session_is_not_a_review() {
        val bundle = LedgerReviewSaver.toBundle(savedReview(listOf(heavyOperation(0))))
        bundle.remove("sessionId")

        assertNull(LedgerReviewSaver.fromBundle(bundle))
    }

    @Test
    fun a_twenty_five_row_draft_with_raw_text_items_and_region_fits_with_room_to_spare() {
        // The wire cap is 25 rows, so 25 is the biggest draft there can be, and each row here
        // carries the heaviest legitimate payload. The Bundle a Saver writes crosses the process
        // boundary inside one Binder transaction; the limit is 1 MB and going over it kills the
        // app, so the cota has to leave a wide margin below it.
        val operations = (0..24).map { heavyOperation(it) }
        val saved = savedReview(operations)

        val bundle = LedgerReviewSaver.toBundle(saved)
        val bytes = parcelledSizeBytes(bundle)

        assertTrue(
            "25 renglones pesados deben pesar holgados, pesaron $bytes bytes",
            bytes < 400_000,
        )
    }

    /** What the Bundle costs on the wire: its parcelled form, marshalled. */
    private fun parcelledSizeBytes(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            return parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}
