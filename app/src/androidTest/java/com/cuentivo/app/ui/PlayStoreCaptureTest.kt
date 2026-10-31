package com.cuentivo.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.LedgerReviewEdits
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.account.SubscriptionContent
import com.cuentivo.app.ui.ai.LedgerReviewContent
import com.cuentivo.app.ui.theme.CuentivoTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The six screenshots for the Play Store listing, in the order they are uploaded.
 *
 * Same idea as `StitchCaptureTest` and the same fictional shop ([CaptureFixtures]), but shaped for
 * the store rather than for design review: 9:16, scaled to a fixed 1080×1920 so the output does not
 * depend on the density of whichever AVD ran it, and only the screens worth showing a stranger.
 *
 * Rendering `CuentivoApp` directly needs no account, no network and no Firestore, which is what
 * makes automated store screenshots possible at all — every one of these screens normally sits
 * behind Firebase Auth.
 *
 * Pull them with:
 *
 *     adb exec-out run-as com.cuentivo.app cat files/play-captures/1-panel.png > 1-panel.png
 */
@RunWith(AndroidJUnit4::class)
class PlayStoreCaptureTest {
    @get:Rule val composeRule = createComposeRule()

    /** First in the list, so it is the one that shows up in search results. */
    @Test fun capture_1_dashboard() {
        capture("1-panel") {
            CuentivoApp(CaptureFixtures.populatedState(), CaptureFixtures.CUSTOMERS)
        }
    }

    @Test fun capture_2_customers() {
        capture("2-clientes") {
            CuentivoApp(CaptureFixtures.populatedState(), CaptureFixtures.CUSTOMERS)
        }
        open("Clientes")
        shoot("2-clientes")
    }

    /** The balance is derived from the ledger below it — that is the whole pitch of the app. */
    @Test fun capture_3_customer_detail() {
        capture("3-detalle-cliente") {
            CuentivoApp(
                state = CaptureFixtures.populatedState(),
                customers = CaptureFixtures.CUSTOMERS,
                statementCustomer = CaptureFixtures.CUSTOMERS[0],
                statement = STORE_STATEMENT,
                cancelables = CaptureFixtures.CANCELABLES,
            )
        }
    }

    @Test fun capture_4_new_sale() {
        capture("4-nueva-venta") {
            CuentivoApp(
                state = CaptureFixtures.populatedState(),
                customers = CaptureFixtures.CUSTOMERS,
                products = CaptureFixtures.SALE_PRODUCTS,
            )
        }
        composeRule.onNodeWithContentDescription("Nueva venta").invokeClick()
        shoot("4-nueva-venta")
    }

    /**
     * Rendered from the shared content rather than from the sheet: `AlertsSheet` is a
     * `ModalBottomSheet` and lives in its own window, so a capture keyed on the tag below would come
     * back showing the screen underneath with no sheet in it.
     */
    @Test fun capture_5_alerts() {
        capture("5-avisos") {
            Surface(color = MaterialTheme.colorScheme.surface) {
                AlertsContent(alerts = CaptureFixtures.ALERTS, onOpenAlert = {})
            }
        }
    }

    /** Same reason: Cuenta is itself a modal sheet. */
    @Test fun capture_6_pro() {
        capture("6-pro") {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.padding(NeoSpacing.lg)) {
                    SubscriptionContent(tier = SubscriptionTier.FREE, onUpgrade = {})
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // La función nueva: importar una libreta por foto. La v41 es la primera que la lleva a quien ya
    // está en producción, así que necesita capturas propias.
    //
    // Datos inventados: nombres de persona y montos de tienda, nada de una libreta real (llevan
    // nombres y teléfonos de clientes, y esto se publica).
    // ---------------------------------------------------------------------------------------------

    /** La foto ya convertida en cuentas: varias clientas, sus montos y el resumen de arriba. */
    @Test fun capture_7_ledger_review() {
        capture("7-libreta-revision") { LedgerCapture(CaptureFixtures.LEDGER_PAGE_READ) }

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertExists()
    }

    /**
     * Un renglón dudoso con su aviso en palabras, y "Registrar" apagado.
     *
     * Es la captura que dice lo que de verdad diferencia a Cuentivo: no registra nada sin que la
     * persona lo revise. La aserción del botón apagado no es adorno — si alguien dejara de exigir la
     * confirmación, la captura fallaría en vez de publicar una promesa falsa.
     */
    @Test fun capture_8_ledger_warning() {
        capture("8-libreta-aviso") { LedgerCapture(CaptureFixtures.LEDGER_PAGE_WITH_DOUBT) }

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsNotEnabled()
    }

    /** La misma página ya resuelta: el aviso se fue y "Registrar 3 movimientos" está activo. */
    @Test fun capture_9_ledger_ready() {
        capture("9-libreta-resumen") {
            LedgerCapture(LedgerReviewEdits.confirmObjection(CaptureFixtures.LEDGER_PAGE_WITH_DOUBT, 2))
        }

        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsEnabled()
    }

    @Composable
    private fun LedgerCapture(operations: List<LedgerOperation>) {
        LedgerReviewContent(
            review = CaptureFixtures.ledgerReview(operations),
            captureFile = null,
            onUpdateAmount = { _, _ -> },
            onUpdateCustomer = { _, _ -> },
            onUpdateKind = { _, _ -> },
            onUpdateDate = { _, _ -> },
            onUpdatePageDate = { },
            onRemoveRow = { },
            onConfirmObjection = { },
            customerMatches = emptyMap(),
            productMatches = emptyMap(),
            duplicateMatches = emptyMap(),
            objections = CaptureFixtures.ledgerObjections(operations),
            isRegistrationBlockedByLegacySave = false,
            isPageCompletenessConfirmed = false,
            onConfirmPageCompleteness = { },
            onConfirm = { },
            onCancel = { },
        )
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            Box(
                Modifier
                    .testTag(CAPTURE_TAG)
                    .requiredWidth(WIDTH_DP.dp)
                    .requiredHeight(HEIGHT_DP.dp),
            ) {
                CuentivoTheme { content() }
            }
        }
        shoot(name)
    }

    private fun shoot(name: String) {
        composeRule.waitForIdle()
        write(name, scaleToStoreSize(captureStableImage()))
    }

    /**
     * PixelCopy is an external compositor request and can reject an otherwise idle frame on this
     * AVD; retry only after Compose reports idle again.
     *
     * Two different failures have to survive the retry, which is why this catches `Throwable` and
     * not one type: PixelCopy rejecting the frame surfaces as an `AssertionError`, while the redraw
     * Compose forces before the copy times out as a `ComposeTimeoutException` — a
     * `RuntimeException`, so a catch written for the first one lets the second one through and
     * fails the capture on the first attempt.
     */
    private fun captureStableImage(): Bitmap {
        var lastFailure: Throwable? = null
        repeat(CAPTURE_ATTEMPTS) {
            composeRule.waitForIdle()
            try {
                return composeRule.onNodeWithTag(CAPTURE_TAG).captureToImage().asAndroidBitmap()
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw checkNotNull(lastFailure)
    }

    /**
     * The composition is laid out in dp, so its pixel size is whatever the AVD's density makes it.
     * Play judges pixels, so the bitmap is resampled to a fixed size. The dp box is already 9:16,
     * so this only changes the scale — nothing is stretched.
     */
    private fun scaleToStoreSize(source: Bitmap): Bitmap {
        if (source.width == STORE_WIDTH_PX && source.height == STORE_HEIGHT_PX) return source
        val scaled = Bitmap.createScaledBitmap(source, STORE_WIDTH_PX, STORE_HEIGHT_PX, true)
        source.recycle()
        return scaled
    }

    /** See `StitchCaptureTest`: a tap leaves its ripple in the picture, the action does not. */
    private fun SemanticsNodeInteraction.invokeClick(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick)

    private fun open(label: String) {
        composeRule.onNodeWithContentDescription(label).invokeClick()
        composeRule.waitForIdle()
    }

    private fun write(name: String, bitmap: Bitmap) {
        // Internal storage: since Android 11 the shell user cannot read another package's
        // /sdcard/Android/data, so an external capture would be invisible to `adb pull`.
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "play-captures",
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

    private companion object {
        const val CAPTURE_TAG = "play-capture-root"
        const val CAPTURE_ATTEMPTS = 4

        /**
         * The shared statement charges 1 500 and credits 250, which leaves the detail screen
         * reading "le vendiste 1 500 · te ha abonado 250 · debe hoy 1 500" — arithmetic that does
         * not close. It goes unnoticed in a design reference, where the point is the layout. It
         * cannot go on a store page for an app whose whole pitch is that the numbers add up, and
         * every other screenshot already says Ramírez owes 1 500, so the sale is what moves.
         */
        val STORE_STATEMENT = listOf(
            StatementEntry("2026-07-14", "CHARGE", "DEBIT", 175_000, "Pedido semanal"),
            StatementEntry("2026-07-21", "PAYMENT", "CREDIT", 25_000, null),
        )

        // 9:16. Play rejects anything taller than 2:1, so the 900 dp box StitchCaptureTest uses for
        // design review — nearly 1:2.5 at 360 dp wide — would not be accepted as a screenshot.
        const val WIDTH_DP = 360
        const val HEIGHT_DP = 640

        const val STORE_WIDTH_PX = 1080
        const val STORE_HEIGHT_PX = 1920
    }
}
