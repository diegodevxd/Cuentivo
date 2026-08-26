package com.cuentivo.app.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.domain.Money
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoPanel
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing

/**
 * Single-question value onboarding: "¿Quién te debe?" -> name + amount -> ledger created.
 *
 * No carousels, no tutorials. A person who installs Cuentivo to track debts gets to the core value
 * in ten seconds before seeing or needing to understand the rest of the app.
 *
 * Can be skipped at any time and never blocks entering the app.
 */
@Composable
fun FirstDebtScreen(
    onSave: (name: String, amountText: String) -> Unit,
    onSkip: () -> Unit,
    isSaving: Boolean = false,
) {
    var name by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }

    val isValid = FirstDebtPolicy.canSubmit(name = name, amountText = amountText, isSaving = isSaving)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
    ) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        val horizontalGutter = maxOf(16, layout.horizontalPaddingDp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalGutter.dp, vertical = NeoSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            NeoPanel(modifier = Modifier.widthIn(max = 480.dp).testTag("first-debt-panel")) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NeoSpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
                ) {
                    Surface(
                        shape = NeoShapes.card,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher_foreground),
                            contentDescription = "Cuentivo",
                            modifier = Modifier.fillMaxSize().scale(1.5f),
                        )
                    }

                    Text(
                        text = stringResource(R.string.first_debt_title),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    Text(
                        text = stringResource(R.string.first_debt_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(NeoSpacing.xs))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.first_debt_name_label)) },
                        placeholder = { Text(stringResource(R.string.first_debt_name_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        shape = NeoShapes.row,
                        modifier = Modifier.fillMaxWidth().testTag("first-debt-name"),
                    )

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(stringResource(R.string.first_debt_amount_label)) },
                        placeholder = { Text(stringResource(R.string.first_debt_amount_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = NeoShapes.row,
                        modifier = Modifier.fillMaxWidth().testTag("first-debt-amount"),
                    )

                    Spacer(Modifier.height(NeoSpacing.sm))

                    NeoAction(
                        label = stringResource(R.string.first_debt_confirm),
                        enabled = isValid,
                        onClick = {
                            if (isValid) {
                                onSave(name.trim(), amountText.trim())
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("first-debt-confirm"),
                    )

                    TextButton(
                        onClick = onSkip,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().testTag("first-debt-skip"),
                    ) {
                        Text(stringResource(R.string.first_debt_skip))
                    }
                }
            }
        }
    }
}