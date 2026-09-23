package com.cuentivo.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.data.local.CustomerBalanceRow
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.domain.CustomerScore
import com.cuentivo.app.domain.PaymentReliability
import com.cuentivo.app.domain.StatementEntry
import com.cuentivo.app.ui.dashboard.DashboardUiState
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas reales de la tanda 5, tomadas del render en el teléfono.
 *
 * No afirman comportamiento nuevo —de eso se encargan las suites— sino que dejan el PNG que se puede
 * mirar: el callejón de Nueva venta sin clientas con su botón, las dos acciones de la libreta, la
 * barra marcando la venta, y la línea de calificación dentro de la tarjeta con la deuda anterior.
 */
@RunWith(AndroidJUnit4::class)
class Tanda5CaptureTest {

    @get:Rule val composeRule = createComposeRule()

    private val rosa = CustomerBalanceRow(
        customer = CustomerEntity(
            id = "rosa",
            businessId = "business-1",
            name = "Rosa Martinez",
            normalizedName = "rosa martinez",
            phone = "5512345678",
            email = null,
            address = null,
            notes = null,
            creditLimitCents = null,
            isActive = true,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            deletedAtEpochMillis = null,
        ),
        balanceCents = 15_000,
    )

    @Test fun captura_nueva_venta_sin_clientas() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(360.dp).requiredHeight(800.dp).testTag("t5-root")) {
                CuentivoApp(
                    state = DashboardUiState.Content("business-1", "Mi negocio", 0, 0, 0, 0, 0),
                    customers = emptyList(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Nueva venta").performClick()
        // El callejón tiene salida: el botón para crear la primera clienta está aquí mismo.
        composeRule.onNodeWithText("Agregar cliente").assertIsDisplayed()
        // Y la barra marca la venta, no la pestaña de la que se vino.
        composeRule.onNodeWithTag("main-action-new-sale").assertIsSelected()
        write("tanda5-nueva-venta-sin-clientas", composeRule.onNodeWithTag("t5-root").captureToImage().asAndroidBitmap())
    }

    @Test fun captura_acciones_de_la_libreta() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(360.dp).requiredHeight(800.dp).testTag("t5-root")) {
                CuentivoApp(
                    state = DashboardUiState.Content("business-1", "Mi negocio", 15_000, 0, 0, 0, 1),
                    customers = listOf(rosa),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Clientes").performClick()
        composeRule.onNodeWithText("Anotar deudas a mano").assertIsDisplayed()
        composeRule.onNodeWithText("Tomar foto de mi libreta").assertIsDisplayed()
        write("tanda5-acciones-libreta", composeRule.onNodeWithTag("t5-root").captureToImage().asAndroidBitmap())
    }

    @Test fun captura_detalle_con_calificacion_y_deuda_anterior() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(360.dp).requiredHeight(900.dp).testTag("t5-root")) {
                CuentivoApp(
                    state = DashboardUiState.Content("business-1", "Mi negocio", 15_000, 0, 0, 0, 1),
                    customers = listOf(rosa),
                    statementCustomer = rosa,
                    statement = listOf(
                        StatementEntry("2026-09-01", "CHARGE", "DEBIT", 15_000, null, isPriorDebt = true),
                        StatementEntry("2026-09-02", "CHARGE", "DEBIT", 2_500, "Cuaderno"),
                        StatementEntry("2026-09-03", "PAYMENT", "CREDIT", 10_000, null),
                    ),
                    customerScores = mapOf(
                        "rosa" to CustomerScore(PaymentReliability.SLOW, averageDaysLate = 4, worstDaysLate = 9, ratedSaleCount = 5),
                    ),
                )
            }
        }

        // La calificación respeta el margen de la tarjeta, y "Te debe" reemplaza a "BALANCE TOTAL".
        composeRule.onNodeWithText("Te debe").assertIsDisplayed()
        write("tanda5-detalle-calificacion-deuda-anterior", composeRule.onNodeWithTag("t5-root").captureToImage().asAndroidBitmap())
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
