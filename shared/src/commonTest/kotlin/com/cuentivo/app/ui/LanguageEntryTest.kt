package com.cuentivo.app.ui

import com.cuentivo.app.platform.LanguageControl
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageEntryTest {

    private class RecordingLanguageControl(
        override val opensSystemSettings: Boolean,
    ) : LanguageControl {
        var settingsOpened = 0

        override fun effectiveTag(): String? = null

        override fun select(tag: String?) = Unit

        override fun openSystemSettings() {
            settingsOpened++
        }
    }

    @Test
    fun `opens system settings and not the sheet`() {
        val language = RecordingLanguageControl(opensSystemSettings = true)
        var sheetOpened = 0

        openLanguageEntry(language) { sheetOpened++ }

        assertEquals(1, language.settingsOpened)
        assertEquals(0, sheetOpened)
    }

    @Test
    fun `opens the sheet and not system settings`() {
        val language = RecordingLanguageControl(opensSystemSettings = false)
        var sheetOpened = 0

        openLanguageEntry(language) { sheetOpened++ }

        assertEquals(0, language.settingsOpened)
        assertEquals(1, sheetOpened)
    }
}
