package com.cuentivo.app.ui

import com.cuentivo.app.platform.LanguageControl

/** iOS cambia el idioma en Ajustes; Android abre su hoja. */
internal fun openLanguageEntry(language: LanguageControl, showSheet: () -> Unit) {
    if (language.opensSystemSettings) language.openSystemSettings() else showSheet()
}
