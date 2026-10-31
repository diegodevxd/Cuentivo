package com.cuentivo.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cuentivo.app.ui.dashboard.DashboardUiState
import org.junit.Rule
import org.junit.Test

class LanguageHeaderTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun header_language_action_opens_compact_single_select_sheet() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }

        composeRule.onNodeWithTag("global-language-action").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("language-sheet").assertIsDisplayed()

        val system = composeRule.onNodeWithTag("language-option-system")
        val spanish = composeRule.onNodeWithTag("language-option-es")
        val english = composeRule.onNodeWithTag("language-option-en")
        listOf(system, spanish, english).forEach {
            it.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assertHeightIsAtLeast(48.dp)
        }
        system.assertIsSelected()
        spanish.assertIsNotSelected()
        english.assertIsNotSelected()
    }

    @Test fun language_action_has_a_localized_content_description() {
        composeRule.setContent {
            CuentivoApp(DashboardUiState.Content("business-1", "Mi negocio", 12_500, 8_000, 3_000, 500, 2))
        }
        composeRule.onNodeWithTag("global-language-action")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
    }

    @Test fun language_action_is_absent_without_brand_header() {
        composeRule.setContent {
            NeoFintechShell(
                destination = MainDestination.HOME,
                onDestination = {},
                onNewSale = {},
                onOpenAccount = {},
                onOpenLanguage = {},
                onOpenAlerts = {},
                showBrandHeader = false,
            ) { }
        }
        composeRule.onAllNodesWithTag("global-language-action").assertCountEquals(0)
    }
}
