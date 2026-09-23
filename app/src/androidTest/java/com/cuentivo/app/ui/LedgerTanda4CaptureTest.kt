package com.cuentivo.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.ai.ledger.LedgerFieldSources
import com.cuentivo.app.ai.ledger.LedgerFieldSource
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEngine
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import com.cuentivo.app.domain.LedgerDeterministicObjections
import com.cuentivo.app.ui.ai.LedgerReviewContent
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas reales de la tanda 4, tomadas del render en el teléfono.
 *
 * No afirman comportamiento nuevo —de eso se encargan las suites— sino que dejan el PNG que se puede
 * mirar: la tarjeta de corrección de año de toda la hoja y el diálogo con el botón "Cambiar a 2026"
 * como salida primaria.
 */
@RunWith(AndroidJUnit4::class)
class LedgerTanda4CaptureTest {

    @get:Rule val composeRule = createComposeRule()

    private val pageDay = LocalDate.of(2026, 9, 1).toEpochDay()
    private val today = LocalDate.of(2026, 9, 23).toEpochDay()

    private fun row(
        lineIndex: Int,
        name: String,
        cents: String,
        rawText: String,
    ) = LedgerOperation(
        lineIndex = lineIndex,
        proposedKind = ProposedLedgerKind.SALE,
        customerName = name,
        amountCents = cents,
        date = SafeDateCandidate(2023, 9, 1),
        rawText = rawText,
        sources = LedgerFieldSources(customer = LedgerFieldSource.EXPLICIT),
    )

    @Test fun captura_la_correccion_de_ano_y_el_dialogo() {
        // Tres renglones con 2023 en una hoja de 2026, y uno de ellos todavía en duda con $999.
        val operations = listOf(
            row(0, "Rosa", "99900", "1010 Cuaderno 999 Rosa")
                .copy(uncertainFields = listOf(UncertainLedgerField.CUSTOMER)),
            row(1, "Juana", "18000", "1011 Cuaderno 180 Juana"),
            row(2, "Carmen", "14900", "1012 Cuaderno 149 (Carmen)"),
        )
        val review = LedgerReviewEngine.build(
            operations = operations,
            pageDateEpochDay = pageDay,
            todayEpochDay = today,
        )
        val objections = LedgerDeterministicObjections.evaluate(
            operations = operations,
            existingCustomerCandidates = CaptureFixtures.EXISTING_CARMEN,
            catalogProducts = emptyList(),
            pageDateEpochDay = pageDay,
            todayEpochDay = today,
        )

        composeRule.setContent {
            Box(Modifier.requiredWidth(360.dp).requiredHeight(800.dp).testTag("tanda4-capture-root")) {
                LedgerReviewContent(
                    review = review,
                    captureFile = null,
                    onUpdateAmount = { _, _ -> },
                    onUpdateCustomer = { _, _ -> },
                    onUpdateKind = { _, _ -> },
                    onUpdateDate = { _, _ -> },
                    onUpdatePageDate = { },
                    onRemoveRow = { },
                    onConfirmObjection = { },
                    isPageCompletenessConfirmed = false,
                    onConfirmPageCompleteness = { },
                    customerMatches = emptyMap(),
                    productMatches = emptyMap(),
                    duplicateMatches = emptyMap(),
                    objections = objections,
                    isRegistrationBlockedByLegacySave = false,
                    onConfirm = { },
                    onCancel = { },
                )
            }
        }

        // La tarjeta de "N renglones dicen 2023" y el pendiente en duda, en pantalla.
        composeRule.onNodeWithTag("ledger-year-bulk-card", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-group-pending-in-doubt", useUnmergedTree = true).assertIsDisplayed()
        write("tanda4-revision-360dp", composeRule.onNodeWithTag("tanda4-capture-root").captureToImage().asAndroidBitmap())

        // El diálogo del renglón con la fecha imposible: "Cambiar a 2026" es el botón grande.
        composeRule.onNodeWithTag("ledger-op-row-0").performScrollTo().performClick()
        composeRule.onNodeWithTag("ledger-edit-correct-year-button").assertIsDisplayed()
        write("tanda4-dialogo-360dp", composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap())
    }

    private fun write(name: String, bitmap: Bitmap) {
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "stitch-captures",
        )
        dir.mkdirs()
        try {
            FileOutputStream(File(dir, "$name.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
