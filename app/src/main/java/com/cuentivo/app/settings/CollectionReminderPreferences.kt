package com.cuentivo.app.settings

import android.content.Context
import android.content.SharedPreferences

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
 * SharedPreferences-backed store in its own private file, separate from Room and the business.
 * Only the toggle and the hour ever reach this file: no debt, customer or balance data.
 */
class SharedPreferencesReminderStore(context: Context) : ReminderPreferenceStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, ReminderPreferenceStore.DEFAULT_ENABLED)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    override var hourOfDay: Int
        get() = preferences.getInt(KEY_HOUR, ReminderPreferenceStore.DEFAULT_HOUR)
        set(value) = preferences.edit().putInt(KEY_HOUR, value).apply()

    private companion object {
        const val PREFERENCES = "cuentivo.collection_reminders"
        const val KEY_ENABLED = "enabled"
        const val KEY_HOUR = "hour_of_day"
    }
}

/**
 * The owner's reminder preference: disabled at 09:00 by default, and every hour is clamped into
 * 0..23 on both reads and writes so an invalid value can never be scheduled.
 */
class CollectionReminderPreferences(
    private val store: ReminderPreferenceStore,
) {
    constructor(context: Context) : this(SharedPreferencesReminderStore(context))

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
