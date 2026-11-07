package com.cuentivo.app.ui.onboarding

import android.content.Context
import android.content.SharedPreferences

/**
 * Las preferencias de onboarding en Android, en el mismo archivo y con las mismas llaves de siempre.
 *
 * El archivo y el prefijo de la llave no se tocan: si cambiaran, a quien ya descartó la primera
 * deuda le volvería a salir la pregunta.
 */
class SharedPreferencesFirstDebtFlagStore(context: Context) : FirstDebtFlagStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun isSet(key: String): Boolean = preferences.getBoolean(key, false)

    override fun set(key: String) {
        preferences.edit().putBoolean(key, true).apply()
    }

    private companion object {
        const val PREFERENCES = "cuentivo_onboarding_preferences"
    }
}
