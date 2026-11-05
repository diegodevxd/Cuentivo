package com.cuentivo.app.domain

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

/**
 * NFD en iOS. `decomposedStringWithCanonicalMapping` es exactamente la descomposición canónica de
 * Unicode, la misma que `Normalizer.Form.NFD` en Android: "María" llega como "Mari" + U+0301 + "a".
 */
@OptIn(BetaInteropApi::class)
internal actual fun canonicalDecomposition(text: String): String =
    (text as NSString).decomposedStringWithCanonicalMapping
