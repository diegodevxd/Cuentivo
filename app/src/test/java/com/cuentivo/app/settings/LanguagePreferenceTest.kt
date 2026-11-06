package com.cuentivo.app.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ejercita [LanguagePreference.processLocale], la pieza que decide qué idioma ve el proceso.
 *
 * Es la misma función que llama `MainActivity.attachBaseContext`: si la regla cambia, esta prueba
 * falla. El caso `null` es el que importa de verdad —quien vuelve a "seguir al sistema" tiene que
 * recuperar el idioma del sistema, no quedarse con el anterior—, por eso se comprueba aparte.
 */
class LanguagePreferenceTest {

    @Test
    fun `processLocale follows the system when nothing was chosen`() {
        val system = Locale.forLanguageTag("es-MX")

        assertEquals(system, LanguagePreference.processLocale(null, system))
    }

    @Test
    fun `processLocale uses the chosen tag instead of the system`() {
        assertEquals(
            Locale.forLanguageTag("en"),
            LanguagePreference.processLocale("en", Locale.forLanguageTag("es-MX")),
        )
    }
}
