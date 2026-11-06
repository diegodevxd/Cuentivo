package com.cuentivo.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene

/**
 * El controlador que está arriba del todo, o `null` si no hay ventana.
 *
 * Escena `foregroundActive` → su `keyWindow` → sube por lo que tenga presentado encima. Es lo que
 * necesitan la hoja de compartir y los selectores de fotos: presentar sobre otro controlador deja la
 * hoja sin aparecer o truena en iPad.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull {
            it.activationState == UISceneActivationStateForegroundActive
        }
        ?.keyWindow
    var controller: UIViewController? = window?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
