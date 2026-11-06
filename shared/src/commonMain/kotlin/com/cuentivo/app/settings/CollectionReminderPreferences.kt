package com.cuentivo.app.settings

/** Where the reminder toggle and hour live, behind an interface so the JVM tests can drive it. */
interface ReminderPreferenceStore {
    var enabled: Boolean
    var hourOfDay: Int

    companion object {
        const val DEFAULT_ENABLED = false
        const val DEFAULT_HOUR = 9
    }
}

/**
 * The owner's reminder preference: disabled at 09:00 by default, and every hour is clamped into
 * 0..23 on both reads and writes so an invalid value can never be scheduled.
 *
 * Vive en común desde F5-C porque iOS necesita exactamente la misma regla; el almacenamiento
 * concreto (SharedPreferences en Android, NSUserDefaults en iOS) se inyecta.
 */
class CollectionReminderPreferences(
    private val store: ReminderPreferenceStore,
) {
    var enabled: Boolean
        get() = store.enabled
        set(value) {
            store.enabled = value
        }

    var hourOfDay: Int
        get() = validatedHour(store.hourOfDay)
        set(value) {
            store.hourOfDay = validatedHour(value)
        }

    fun update(enabled: Boolean, hourOfDay: Int) {
        store.enabled = enabled
        store.hourOfDay = validatedHour(hourOfDay)
    }

    companion object {
        /** Pure: clamps any hour into 0..23, so an impossible hour can never be scheduled. */
        fun validatedHour(hour: Int): Int = hour.coerceIn(0, 23)
    }
}
