package com.cuentivo.app.platform

/** Dónde se elige el idioma de la app. */
interface LanguageControl {
    /** `true` si esa elección vive en los Ajustes del sistema, y la app no muestra su hoja. */
    val opensSystemSettings: Boolean

    /** La elección vigente ("es", "en"), o null si sigue al sistema. */
    fun effectiveTag(): String?

    /** `tag` nulo significa seguir al sistema. */
    fun select(tag: String?)

    fun openSystemSettings()
}
