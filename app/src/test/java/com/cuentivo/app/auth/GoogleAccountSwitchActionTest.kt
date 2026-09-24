package com.cuentivo.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresión del hallazgo v45: entrar con Google desde una sesión de invitada pedía elegir la cuenta
 * dos veces. La decisión de reutilizar el token ya obtenido vive en [GoogleAccountSwitchAction], y
 * esta prueba la invoca de verdad: si alguien volviera a relanzar el selector con un token en la
 * mano, la prueba se pone roja.
 */
class GoogleAccountSwitchActionTest {

    @Test
    fun un_token_disponible_se_reutiliza_sin_volver_a_pedir_la_cuenta() {
        assertEquals(
            GoogleAccountSwitchAction.Route.REUSE_TOKEN,
            GoogleAccountSwitchAction.route("google-id-token"),
        )
    }

    @Test
    fun sin_token_no_queda_otra_que_relanzar_el_selector() {
        assertEquals(
            GoogleAccountSwitchAction.Route.RELAUNCH_PICKER,
            GoogleAccountSwitchAction.route(null),
        )
        assertEquals(
            GoogleAccountSwitchAction.Route.RELAUNCH_PICKER,
            GoogleAccountSwitchAction.route(""),
        )
        assertEquals(
            GoogleAccountSwitchAction.Route.RELAUNCH_PICKER,
            GoogleAccountSwitchAction.route("   "),
        )
    }
}
