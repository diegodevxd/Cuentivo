package com.cuentivo.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuentivo.app.ai.ledger.LedgerDetectedPageDate
import com.cuentivo.app.ai.ledger.LedgerExtractionPayload
import com.cuentivo.app.ai.ledger.SafeDateCandidate
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Esto NO es una prueba: es el GUION del video de la ficha de Play.**
 *
 * Existe para grabar un recorrido corto y repetible de la pantalla de revisión. **Las pausas son
 * deliberadas** —del orden de un segundo por paso— porque el resultado es un video para mirar, no una
 * prueba de velocidad. **No las quites para "optimizar" esto**: si desaparecen, el video deja de
 * servir y nadie se entera de por qué. Y **no la borres pensando que es una prueba redundante**: es la
 * única cosa que garantiza que el video sale igual cada vez.
 *
 * La libreta que recorre es la **misma inventada de las capturas** ([CaptureFixtures]): nombres de
 * persona y montos de tienda, nunca una libreta real. Nada de esto toca la red ni una cuenta.
 *
 * La lanza `scripts/grabar-demo.ps1`, que es quien pone el modo demostración y graba la pantalla.
 */
@RunWith(AndroidJUnit4::class)
class LedgerDemoRecordingTest {

    @get:Rule val composeRule = createAndroidComposeRule<LedgerLabHostActivity>()

    private var imageFile: File? = null

    @Before
    fun setUp() {
        LedgerLabHostActivity.extractionCalls = 0
        // La misma libreta inventada de las capturas, y con ella la clienta que ya existe, para que
        // aparezca el aviso de "este cliente ya existe" que el video quiere enseñar.
        LedgerLabHostActivity.pagePayloadOverride = LedgerExtractionPayload(
            // La misma libreta inventada de las capturas, con la fecha puesta en cada renglón.
            //
            // Las filas de la libreta llegan sin fecha (la hoja la trae arriba y la app la OFRECE,
            // no la impone), y sin fecha ningún renglón está listo: el botón se quedaba en "Sin
            // movimientos para registrar" y el video no podía terminar en "Registrar 3 movimientos".
            // Poner aquí la fecha de la hoja es exactamente lo que la app hace cuando la persona
            // acepta la oferta — el paso que el video quiere enseñar es otro.
            operations = CaptureFixtures.LEDGER_PAGE_WITH_DOUBT.map {
                it.copy(date = SafeDateCandidate(2026, 9, 1))
            },
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            detectedPageDate = LedgerDetectedPageDate(SafeDateCandidate(2026, 9, 1), 0.95),
        )
        LedgerLabHostActivity.customerCandidates = CaptureFixtures.EXISTING_CARMEN

        // Una foto real servida por el FileProvider de la app, la misma ruta que producción: así el
        // recorrido del video pasa por copiar, preparar y llamar, no por un atajo.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cameraDir = File(context.cacheDir, "ai-camera").apply { mkdirs() }
        imageFile = File(cameraDir, "demo-page.jpg").apply { writeBytes(tinyJpeg()) }
        LedgerLabHostActivity.pickedUri = android.net.Uri.parse(
            "content://" + context.packageName + ".fileprovider/ai-camera/" + imageFile!!.name,
        )
    }

    @After
    fun tearDown() {
        // Dejar el host como estaba: la prueba de rotación es su otro usuario.
        LedgerLabHostActivity.pagePayloadOverride = null
        LedgerLabHostActivity.customerCandidates = emptyList()
        imageFile?.delete()
    }

    @Test
    fun recorre_la_libreta_despacio_para_grabarla() {
        // 1 · La página llega desde la foto y se ve la revisión con su resumen.
        composeRule.onNodeWithTag("ai-ledger-import-entry").performClick()
        composeRule.onNodeWithTag("ai-ledger-source-gallery").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("ledger-op-row-0").fetchSemanticsNodes().isNotEmpty()
        }
        hold()

        // 2 · Bajar por los renglones. Sin afirmar nada: si la lista no se puede desplazar, el video
        // sigue. La única afirmación de verdad está al final.
        runCatching { composeRule.onNodeWithTag("ledger-op-row-2").performClick() }
        hold()

        // 3 · Detenerse en el renglón dudoso y su aviso: el recorrido guiado abre el renglón que tiene
        // la objeción, con el aviso en palabras y el botón de confirmarlo.
        runCatching { composeRule.onNodeWithTag("ledger-start-review-button").performClick() }
        hold()

        // 4 · Resolverlo, como lo haría una persona: "Sí, es correcto".
        runCatching { composeRule.onNodeWithTag("ledger-edit-confirm-objection-button").performClick() }
        hold()

        // Cerrar el renglón para que el último plano sea la pantalla entera.
        runCatching { composeRule.onNodeWithTag("ledger-edit-save-button").performClick() }
        hold()

        // 5 · "Registrar N movimientos" activo. Esta es la ÚNICA comprobación del guion, y está a
        // propósito: si la pantalla terminara rota, el video sería un video de una pantalla rota.
        composeRule.onNodeWithTag("ledger-review-confirm-button").assertIsEnabled()
        hold()
    }

    /**
     * Pausa deliberada, para que el paso se vea en el video.
     *
     * NO la bajes para "acelerar la prueba": esto se graba con `screenrecord` y un paso que dura
     * décimas de segundo no se ve.
     */
    private fun hold() {
        composeRule.waitForIdle()
        Thread.sleep(HOLD_MILLIS)
    }

    /** Un JPEG de 1×1: real para el decodificador y el preparador, y por debajo de todos los topes. */
    private fun tinyJpeg(): ByteArray {
        val stream = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private companion object {
        /** Un segundo y pico por paso: lo que tarda un ojo en leer el paso, ni más ni menos. */
        const val HOLD_MILLIS = 1_400L
    }
}
