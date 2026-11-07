package com.cuentivo.app.sync

/**
 * Un renglón de log, mínimo y por plataforma.
 *
 * Existe porque `CloudRestore` es común y Android escribía ese aviso con `android.util.Log`. En
 * Android sigue yendo al mismo Logcat con el mismo tag y el mismo texto; en iOS va a `NSLog`. El
 * contenido no cambia: la etapa del fallo y el mensaje de Firestore, nunca ids, nombres ni montos.
 */
internal expect object PlatformLog {
    fun warn(tag: String, message: String)
    fun info(tag: String, message: String)
}
