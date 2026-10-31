package com.cuentivo.app.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing

/**
 * Subscription paywall sheet for CuentivoAI.
 *
 * Sells execution over mere answering: shows a concrete operation completing and highlights that
 * CuentivoAI records sales, payments, and balances directly without filling forms.
 *
 * Reassures existing Pro subscribers that CuentivoAI includes everything in Pro (cloud backup,
 * unlimited customers, collection reminders).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPaywallSheet(
    currentTier: SubscriptionTier,
    onUpgrade: (SubscriptionTier) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** AI's price as Play formats it, or null while not loaded. */
    aiPrice: String? = null,
    aiTrialMonths: Int = 0,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NeoSpacing.lg)
                .padding(bottom = NeoSpacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_cuentivo_ai_robot),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    text = stringResource(R.string.ai_paywall_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            Text(
                text = stringResource(R.string.ai_paywall_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Concrete example card showing an operation completing
            Surface(
                shape = NeoShapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(NeoSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(NeoSpacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.ai_paywall_example_quote),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.ai_paywall_example_result),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PaywallBenefitLine(stringResource(R.string.ai_paywall_feature_execution))
                PaywallBenefitLine(stringResource(R.string.ai_paywall_feature_pro_included))
                PaywallBenefitLine(stringResource(R.string.ai_paywall_feature_queries))
            }

            // Price loaded from Play or fallback
            val formattedPrice = aiPrice?.let { planPriceText(it, aiTrialMonths) }
                ?: stringResource(R.string.subscription_price_unknown)
            Text(
                text = formattedPrice,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            val actionLabel = if (currentTier == SubscriptionTier.PRO) {
                stringResource(R.string.ai_paywall_action_upgrade)
            } else {
                stringResource(R.string.ai_paywall_action_buy)
            }

            NeoAction(
                label = actionLabel,
                onClick = {
                    onDismiss()
                    onUpgrade(SubscriptionTier.AI)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = aiPrice != null,
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ai_paywall_dismiss))
            }

            Text(
                text = stringResource(R.string.subscription_billing_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(NeoSpacing.sm))
        }
    }
}

@Composable
private fun PaywallBenefitLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(NeoSpacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
