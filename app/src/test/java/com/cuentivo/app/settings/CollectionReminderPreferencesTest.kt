package com.cuentivo.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory mirror of the SharedPreferences store, sharing the same defaults contract. */
private class FakeReminderPreferenceStore : ReminderPreferenceStore {
    override var enabled: Boolean = ReminderPreferenceStore.DEFAULT_ENABLED
    override var hourOfDay: Int = ReminderPreferenceStore.DEFAULT_HOUR
}

class CollectionReminderPreferencesTest {
    @Test
    fun `reminders are disabled and 9 AM by default`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore())
        assertFalse(preferences.enabled)
        assertEquals(9, preferences.hourOfDay)
    }

    @Test
    fun `update stores the toggle and the hour`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore())
        preferences.update(enabled = true, hourOfDay = 17)
        assertTrue(preferences.enabled)
        assertEquals(17, preferences.hourOfDay)
    }

    @Test
    fun `turning off persists as off`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore())
        preferences.update(enabled = true, hourOfDay = 9)
        preferences.update(enabled = false, hourOfDay = 9)
        assertFalse(preferences.enabled)
    }

    @Test
    fun `hours above 23 are clamped to 23`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore())
        preferences.update(enabled = true, hourOfDay = 24)
        assertEquals(23, preferences.hourOfDay)
    }

    @Test
    fun `hours below 0 are clamped to 0`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore())
        preferences.update(enabled = true, hourOfDay = -3)
        assertEquals(0, preferences.hourOfDay)
    }

    @Test
    fun `the hour setter also validates`() {
        val preferences = CollectionReminderPreferences(FakeReminderPreferenceStore())
        preferences.hourOfDay = 30
        assertEquals(23, preferences.hourOfDay)
    }
}
