package com.cuentivo.app.settings

import android.app.Activity
import android.content.Context
import android.os.Build
import com.cuentivo.app.platform.LanguageControl

/**
 * El idioma en Android: se elige dentro de la app, con `LanguagePreference`, y nunca se sale a
 * Ajustes.
 *
 * Recibe el `Context` de `LocalContext`, que es la Activity: por debajo de API 33 hay que recrearla
 * para que el idioma nuevo se aplique, y con el `applicationContext` eso no corre.
 */
class AndroidLanguageControl(private val context: Context) : LanguageControl {

    override val opensSystemSettings: Boolean = false

    override fun effectiveTag(): String? = LanguagePreference.effectiveTag(context)

    override fun select(tag: String?) {
        LanguagePreference.set(context, tag)
        if (Build.VERSION.SDK_INT < 33) {
            (context as? Activity)?.recreate()
        }
    }

    /** No hace nada: en Android el idioma se elige dentro de la app, no en los Ajustes del sistema. */
    override fun openSystemSettings() = Unit
}
