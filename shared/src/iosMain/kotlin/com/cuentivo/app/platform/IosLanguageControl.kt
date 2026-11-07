package com.cuentivo.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * El idioma en iOS: lo administra el sistema por app, gracias a `CFBundleLocalizations`, así que la
 * app no dibuja su hoja y manda a Ajustes.
 *
 * Pendiente de CI: nadie ha comprobado que `NSLocale.preferredLanguages` refleje la elección hecha
 * en Ajustes; esta clase solo abre la pantalla correcta.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLanguageControl : LanguageControl {

    override val opensSystemSettings: Boolean = true

    override fun effectiveTag(): String? = null

    override fun select(tag: String?) {
        openSystemSettings()
    }

    override fun openSystemSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
