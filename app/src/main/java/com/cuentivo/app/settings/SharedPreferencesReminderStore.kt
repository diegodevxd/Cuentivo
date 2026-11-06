package com.cuentivo.app.settings

import android.content.Context
import android.content.SharedPreferences

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
 * La misma llamada de siempre para Android: `CollectionReminderPreferences(context)`.
 *
 * El constructor con `Context` no puede vivir en común, así que vive aquí y no cambia ni una llamada
 * del lado de Android.
 */
fun CollectionReminderPreferences(context: Context): CollectionReminderPreferences =
    CollectionReminderPreferences(SharedPreferencesReminderStore(context))
