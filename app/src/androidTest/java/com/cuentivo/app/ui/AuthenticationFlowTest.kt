package com.cuentivo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.cuentivo.app.ui.auth.AuthenticationScreen
import com.cuentivo.app.ui.theme.CuentivoTheme
import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.unit.dp

/**
 * Task 8 behaviour for the first screen anyone sees.
 *
 * `CredentialsValidator` is not exercised here — it has its own unit tests. What this pins is that
 * the screen *asks* it before offering the action, so an invalid address never reaches Firebase.
 */
class AuthenticationFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun the_header_carries_the_real_mark_and_the_mode_it_is_in() {
        composeRule.setContent { Harness() }

        composeRule.onNodeWithContentDescription("Cuentivo").assertIsDisplayed()
        composeRule.onNodeWithText("Iniciar sesión").assertIsDisplayed()
        composeRule.onAllNodesWithText("Crear cuenta").assertCountEquals(0)
    }

    @Test fun the_compact_form_keeps_a_panel_and_16dp_gutters_at_320dp() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(320.dp)) { Harness() }
        }

        composeRule
            .onNodeWithTag("authentication-panel")
            .assertIsDisplayed()
            .assertLeftPositionInRootIsEqualTo(16.dp)
    }

    @Test fun both_credential_fields_are_present() {
        composeRule.setContent { Harness() }

        composeRule.onNodeWithText("Correo electrónico").assertIsDisplayed()
        composeRule.onNodeWithText("Contraseña").assertIsDisplayed()
        // Signing in never asks anyone to type their password twice.
        composeRule.onAllNodesWithText("Confirma tu contraseña").assertCountEquals(0)
    }

    @Test fun creating_an_account_asks_for_the_password_twice() {
        composeRule.setContent { Harness() }

        switchToCreate()

        composeRule.onNodeWithText("Confirma tu contraseña").assertIsDisplayed()
    }

    @Test fun the_action_stays_disabled_until_the_credentials_are_valid() {
        composeRule.setContent { Harness() }

        submitButton().assertIsNotEnabled()

        type("Correo electrónico", "no-es-un-correo")
        type("Contraseña", "claveSegura8")
        submitButton().assertIsNotEnabled()

        composeRule.onNodeWithText("Correo electrónico").performTextClearance()
        type("Correo electrónico", "ana@example.com")
        // Seven characters: one short of what the validator accepts.
        composeRule.onNodeWithText("Contraseña").performTextClearance()
        type("Contraseña", "corta12")
        submitButton().assertIsNotEnabled()

        composeRule.onNodeWithText("Contraseña").performTextClearance()
        type("Contraseña", "claveSegura8")
        submitButton().assertIsEnabled()
    }

    @Test fun creating_an_account_stays_disabled_until_both_passwords_match() {
        composeRule.setContent { Harness() }
        switchToCreate()

        type("Correo electrónico", "ana@example.com")
        type("Contraseña", "claveSegura8")
        submitButton().assertIsNotEnabled()

        type("Confirma tu contraseña", "otraClave99")
        submitButton().assertIsNotEnabled()

        composeRule.onNodeWithText("Confirma tu contraseña").performTextClearance()
        type("Confirma tu contraseña", "claveSegura8")
        submitButton().assertIsEnabled()
    }

    @Test fun signing_in_hands_over_the_trimmed_address_and_the_exact_password() {
        composeRule.setContent { Harness() }

        type("Correo electrónico", "  ana@example.com  ")
        type("Contraseña", "claveSegura8")
        submitButton().performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("signIn:ana@example.com:claveSegura8"), calls.toList())
        }
    }

    @Test fun creating_an_account_calls_the_other_callback() {
        composeRule.setContent { Harness() }
        switchToCreate()

        type("Correo electrónico", "ana@example.com")
        type("Contraseña", "claveSegura8")
        type("Confirma tu contraseña", "claveSegura8")
        submitButton().performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("signUp:ana@example.com:claveSegura8"), calls.toList())
        }
    }

    @Test fun the_two_modes_switch_back_and_forth() {
        composeRule.setContent { Harness() }

        switchToCreate()
        composeRule.onNodeWithText("Crear cuenta").assertIsDisplayed()

        composeRule.onNodeWithText("Ya tengo cuenta, iniciar sesión").performScrollTo().performClick()

        composeRule.onNodeWithText("Iniciar sesión").assertIsDisplayed()
        composeRule.onAllNodesWithText("Confirma tu contraseña").assertCountEquals(0)
    }

    /**
     * The keyboard is open the whole time somebody is typing credentials, and that is exactly when
     * the action has to stay reachable. At 320 dp there is no room to spare.
     */
    @Test fun the_action_is_reachable_while_the_keyboard_is_open() {
        composeRule.setContent { Harness() }

        type("Correo electrónico", "ana@example.com")
        type("Contraseña", "claveSegura8")

        submitButton().assertIsDisplayed()
    }

    @Test fun invalid_email_uses_the_english_validation_resource() {
        composeRule.setContent { Harness(localeTag = "en-US") }

        type("Email", "not-an-email")

        composeRule.onNodeWithText("Enter a valid email.").assertIsDisplayed()
    }

    @Test fun short_password_uses_the_english_validation_resource() {
        composeRule.setContent { Harness(localeTag = "en-US") }

        type("Email", "ana@example.com")
        type("Password", "short")

        composeRule.onNodeWithText("Password must be at least 8 characters.").assertIsDisplayed()
    }

    @Test fun mismatched_passwords_use_the_english_validation_resource() {
        composeRule.setContent { Harness(localeTag = "en-US") }
        switchToCreate("No account yet? Create one")

        type("Email", "ana@example.com")
        type("Password", "longEnough8")
        type("Confirm your password", "different9")

        composeRule.onNodeWithText("Passwords do not match.").assertIsDisplayed()
    }

    @Test fun sign_in_callback_failure_shows_the_fixed_english_message() {
        composeRule.setContent { Harness(localeTag = "en-US", message = "Could not sign in.") }

        composeRule.onNodeWithText("Could not sign in.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Firebase: raw failure").assertCountEquals(0)
        composeRule.onAllNodesWithText("No fue posible iniciar sesión").assertCountEquals(0)
    }

    @Test fun Google_action_is_accessible_and_calls_its_callback() {
        composeRule.setContent { Harness(onGoogleSignIn = { calls += "google" }) }

        composeRule.onNodeWithContentDescription("Continuar con Google").performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(listOf("google"), calls) }
    }

    @Test fun Google_action_is_disabled_while_authentication_is_working() {
        composeRule.setContent { Harness(isWorking = true) }

        composeRule.onNodeWithContentDescription("Continuar con Google").performScrollTo().assertIsNotEnabled()
    }

    @Test fun Google_action_stays_enabled_when_credentials_are_invalid() {
        composeRule.setContent { Harness() }

        type("Correo electrónico", "no-es-un-correo")
        type("Contraseña", "corta")

        composeRule.onNodeWithContentDescription("Continuar con Google").performScrollTo().assertIsEnabled()
    }

    private fun switchToCreate(label: String = "¿No tienes cuenta? Crear una") {
        composeRule.onNodeWithText(label, useUnmergedTree = true)
            .performScrollTo().performClick()
    }

    private fun type(label: String, text: String) {
        composeRule.onNodeWithText(label).performScrollTo().performTextInput(text)
        composeRule.waitForIdle()
    }

    /** The single primary action, whichever mode the screen is in. */
    private fun submitButton() = composeRule.onNodeWithText("Continuar").performScrollTo()

    private val calls = mutableListOf<String>()

    @Composable
    private fun Harness(
        isWorking: Boolean = false,
        message: String? = null,
        localeTag: String = "es-MX",
        onGoogleSignIn: () -> Unit = {},
    ) {
        val baseContext = LocalContext.current
        val configuration = Configuration(LocalConfiguration.current).apply {
            setLocale(Locale.forLanguageTag(localeTag))
        }
        val localizedContext = baseContext.createConfigurationContext(configuration)
        CompositionLocalProvider(
            LocalContext provides localizedContext,
            LocalConfiguration provides configuration,
        ) {
        CuentivoTheme {
            AuthenticationScreen(
                isWorking = isWorking,
                message = message,
                onSignIn = { email, password -> calls += "signIn:$email:$password" },
                onSignUp = { email, password -> calls += "signUp:$email:$password" },
                onGoogleSignIn = onGoogleSignIn,
            )
        }
        }
    }
}
