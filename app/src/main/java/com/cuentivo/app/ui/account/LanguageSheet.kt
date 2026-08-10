package com.cuentivo.app.ui.account

import android.app.Activity
import android.os.Build
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.settings.LanguagePreference
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing

private enum class LanguageOption(val tag: String?, val labelRes: Int) {
    SYSTEM(null, R.string.account_language_system),
    SPANISH("es", R.string.account_language_spanish),
    ENGLISH("en", R.string.account_language_english),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember {
        mutableStateOf(
            when (LanguagePreference.effectiveTag(context)) {
                "es" -> LanguageOption.SPANISH
                "en" -> LanguageOption.ENGLISH
                else -> LanguageOption.SYSTEM
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NeoSpacing.lg)
                .padding(bottom = NeoSpacing.lg)
                .testTag("language-sheet"),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.account_language),
                style = MaterialTheme.typography.titleMedium,
            )
            LanguageOption.entries.forEach { option ->
                LanguageChoice(
                    option = option,
                    selected = selected == option,
                    onClick = {
                        selected = option
                        LanguagePreference.set(context, option.tag)
                        if (Build.VERSION.SDK_INT < 33) {
                            (context as? Activity)?.recreate()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageChoice(
    option: LanguageOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // A list row with a radio, not a card. Three stacked surfaces made three equal-weight choices
    // and never said which one was live; the whole job of this sheet is to answer that at a glance.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("language-option-${option.tag ?: "system"}")
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable(onClick = onClick)
            .padding(horizontal = NeoSpacing.xs, vertical = NeoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpacing.md),
    ) {
        RadioButton(
            selected = selected,
            // The whole row is the target already; a second one would let a tap land on neither.
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Text(
            text = stringResource(option.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
