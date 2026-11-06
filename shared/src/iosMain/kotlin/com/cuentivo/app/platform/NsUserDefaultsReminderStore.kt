package com.cuentivo.app.platform

import com.cuentivo.app.settings.ReminderPreferenceStore
import platform.Foundation.NSUserDefaults

/**
 * Las preferencias de recordatorio de iOS, con los mismos valores por defecto que Android.
 *
 * Sólo el interruptor y la hora llegan aquí: ni deudas, ni clientes, ni saldos.
 *
 * Pendiente de CI: usa `NSUserDefaults`, que existe en el simulador de pruebas pero esta clase se
 * apoya en la misma interfaz que ya prueba `CollectionReminderPreferences` en común.
 */
class NsUserDefaultsReminderStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : ReminderPreferenceStore {

    override var enabled: Boolean
        get() = defaults.boolForKey(KEY_ENABLED)
        set(value) = defaults.setBool(value, forKey = KEY_ENABLED)

    override var hourOfDay: Int
        get() {
            if (defaults.objectForKey(KEY_HOUR) == null) return ReminderPreferenceStore.DEFAULT_HOUR
            return defaults.integerForKey(KEY_HOUR).toInt()
        }
        set(value) = defaults.setInteger(value.toLong(), forKey = KEY_HOUR)

    private companion object {
        const val KEY_ENABLED = "cuentivo.collection_reminders.enabled"
        const val KEY_HOUR = "cuentivo.collection_reminders.hour_of_day"
    }
}
