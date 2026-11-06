package com.cuentivo.app.platform

import android.net.Uri

/**
 * En Android el selector entrega un `android.net.Uri`; la app lo copia a su propio storage.
 *
 * No es un `actual typealias` como `PlatformAuthHost` por una limitación real del compilador:
 * un `actual typealias` no puede expandir a una clase **abstracta** (`Uri` lo es, con el
 * constructor privado), y Kotlin lo rechaza con "has no corresponding expected declaration". El
 * envoltorio conserva el `Uri` y no cuesta nada: hoy nadie construye un `PickedImage` en Android.
 */
actual class PickedImage(val uri: Uri)