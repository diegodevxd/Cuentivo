package com.cuentivo.app.auth

/**
 * Cómo completar el cambio de cuenta de Google tras confirmarlo (v45).
 *
 * El defecto que cierra: al entrar con Google desde una sesión de invitada, la app pedía elegir la
 * cuenta **dos veces** —una antes del aviso "¿Entrar con otra cuenta?" y otra después—. La causa:
 * el enlace falla porque la cuenta ya existe, y confirmar volvía a lanzar el selector de Google en
 * vez de reutilizar el token que ya se tenía.
 *
 * La decisión vive aquí, pura y sin Android, para que una prueba JVM la invoque de verdad: si el
 * token está disponible, se reutiliza; si no, no queda otra que volver a pedirlo.
 */
object GoogleAccountSwitchAction {

    /** Qué hacer cuando la persona confirma el cambio de cuenta. */
    enum class Route {
        /** Reutilizar el idToken ya obtenido: no se vuelve a abrir el selector. */
        REUSE_TOKEN,

        /** No hay token disponible; hay que lanzar el flujo de Google otra vez. */
        RELAUNCH_PICKER,
    }

    /**
     * Decide la ruta a partir del token que dejó el intento de enlace fallido.
     *
     * Un token en blanco o nulo no sirve para `signInWithCredential`, así que en ese caso se
     * relanza el selector. Un token presente se reutiliza.
     */
    fun route(idToken: String?): Route =
        if (idToken.isNullOrBlank()) Route.RELAUNCH_PICKER else Route.REUSE_TOKEN
}
