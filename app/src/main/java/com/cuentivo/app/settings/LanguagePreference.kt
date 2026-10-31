package com.cuentivo.app.settings

import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.app.LocaleManager
import java.util.Locale

/**
 * The chosen UI language, stored where `attachBaseContext` can read it on a cold start.
 *
 * Below API 33 this private preference is the sole authority and is readable before `onCreate`.
 * API 33+ uses the platform `LocaleManager`; the legacy value is consumed only for one-time
 * migration and then removed.
 *
 * Null means "follow the system", which is the default and what an untouched install gets.
 */
object LanguagePreference {
    private const val PREFERENCES = "cuentivo.language"
    private const val TAG = "language_tag"

    fun save(context: Context, languageTag: String?) {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().apply {
            if (languageTag == null) remove(TAG) else putString(TAG, languageTag)
        }.apply()
    }

    /** The stored tag ("es", "en"), or null to follow the system. */
    fun current(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(TAG, null)

    /** The platform-owned choice on API 33+, or the legacy choice below it. */
    fun effectiveTag(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 33) {
            migrateLegacyToPlatformIfNeeded(context)
            return context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeUnless { it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
        }
        return normalizedLegacyTag(current(context))
    }

    /** Writes to the sole authority for the current Android API family. */
    fun set(context: Context, languageTag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Once LocaleManager is authoritative, discard the legacy value so a later system
            // reset to empty cannot resurrect an old pre-33 preference.
            save(context, null)
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            manager.applicationLocales = languageTag
                ?.let { LocaleList.forLanguageTags(it) }
                ?: LocaleList.getEmptyLocaleList()
        } else {
            save(context, languageTag)
        }
    }

    /** One-way migration: only an empty platform choice may adopt a valid legacy value. */
    fun migrateLegacyToPlatformIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        if (!manager.applicationLocales.isEmpty) {
            save(context, null)
            return
        }
        val legacy = normalizedLegacyTag(current(context))
        if (legacy == null) {
            save(context, null)
            return
        }
        manager.applicationLocales = LocaleList.forLanguageTags(legacy)
        save(context, null)
    }

    private fun normalizedLegacyTag(tag: String?): String? = when (tag?.lowercase()?.substringBefore('-')) {
        "es" -> "es"
        "en" -> "en"
        else -> null
    }
}
