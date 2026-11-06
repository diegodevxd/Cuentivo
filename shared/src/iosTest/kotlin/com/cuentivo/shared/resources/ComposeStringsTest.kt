package com.cuentivo.shared.resources

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Ejercita la lectura real de `composeResources` en iOS.
 *
 * Vive en `iosTest` y no en `commonTest` a propósito: en el host de Android
 * (`:shared:testAndroidHostTest`) `Resources.getSystem()` no existe, así que un `getString` de la
 * librería de Compose no puede resolver nada ahí. El binario de pruebas de iOS sí recibe los
 * recursos: el plugin de Compose copia `compose-resources` junto al ejecutable de prueba.
 *
 * Lo que se comprueba es lo que Android dejó de verificar al mover los textos a la fuente única: que
 * las comillas viajan **sin** diagonales (Compose no entiende `\"` y las mostraría en pantalla), y
 * que los marcadores `%N$s` y los plurales se sustituyen de verdad.
 */
class ComposeStringsTest {

    @Test
    fun `app name comes from the shared compose resources`() = runBlocking {
        assertEquals("Cuentivo", getString(Res.string.app_name))
    }

    @Test
    fun `text keeps its quotes and carries no escape backslash`() = runBlocking {
        val text = getString(Res.string.ai_sale_preview_product_selection_prompt, "x")
        assertTrue(text.contains("\"x\""), "el argumento debe ir entre comillas: $text")
        assertFalse(text.contains("\\"), "el texto no puede traer diagonales de escape: $text")
    }

    @Test
    fun `hour option formats the padded hour`() = runBlocking {
        assertEquals("07:00", getString(Res.string.reminder_hour_option, "07"))
    }

    @Test
    fun `plural replaces its argument and leaves no placeholder`() = runBlocking {
        val text = getPluralString(Res.plurals.ai_ledger_action_register_blocked_pending, 2, 3)
        assertTrue(text.contains("3"), "el plural debe sustituir su argumento: $text")
        assertFalse(text.contains("%"), "no puede quedar un marcador sin sustituir: $text")
    }
}
