package com.cuentivo.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.cuentivo.app.R
import com.cuentivo.shared.resources.Res
import com.cuentivo.shared.resources.nav_home
import java.util.Locale
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `R.string` sale de la configuración del `Context` y `Res.string` del `Locale` del proceso. Mientras
 * convivan, `MainActivity.attachBaseContext` debe moverlos juntos o una pantalla mezcla idiomas. Esta
 * prueba lee los `.cvr` reales de la APK, que es lo que ninguna prueba JVM puede hacer.
 */
class ResLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var original: Locale

    @Before fun remember() {
        original = Locale.getDefault()
    }

    @After fun restore() {
        Locale.setDefault(original)
    }

    @Test fun res_matches_r_in_spanish() = assertResMatchesR("es")

    @Test fun res_matches_r_in_english() = assertResMatchesR("en")

    private fun assertResMatchesR(tag: String) {
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        val expected = base.createConfigurationContext(configuration).getString(R.string.nav_home)

        composeRule.setContent { Text(stringResource(Res.string.nav_home)) }

        composeRule.onNodeWithText(expected).assertExists()
    }
}
