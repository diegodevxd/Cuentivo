package com.cuentivo.app.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cuentivo.app.domain.FeatureAccessManager
import com.cuentivo.app.domain.PaidFeature
import com.cuentivo.app.R
import com.cuentivo.app.domain.SubscriptionTier
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoShapes
import com.cuentivo.app.ui.NeoSpacing

/**
 * The plan section: which plan this account is on, what each costs, and how to change it.
 *
 * There is no wrapper composable in this file on purpose. Cuenta is already a `ModalBottomSheet`
 * and this content is a section inside it — a second sheet would put a sheet on top of a sheet for
 * no gain. It lives in its own file so the copy has a single home, and so a capture can render it
 * directly.
 *
 * Three plans are presented on screen: Gratis, Pro, and CuentivoAI. The one you have carries a badge,
 * and the numbers and capabilities that separate them are the headline.
 *
 * Every limit is read from [FeatureAccessManager] and every paid capability from [PaidFeature].
 *
 * This screen never claims a purchase happened: entitlement arrives from `BillingGateway` through
 * the usual path, and until it does the account is still on the plan shown here.
 */
@Composable
internal fun SubscriptionContent(
    tier: SubscriptionTier,
    onUpgrade: (SubscriptionTier) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Pro's price exactly as Play formats it, or null while it has not loaded. Never a hardcoded
     * figure: see the parameter of the same name on `BillingGateway`.
     */
    proPrice: String? = null,
    /**
     * AI's price exactly as Play formats it, or null while it has not loaded. Never a hardcoded
     * figure: see the parameter of the same name on `BillingGateway`.
     */
    aiPrice: String? = null,
    /** Whole free-trial months Play offers for each plan; 0 = none. */
    proTrialMonths: Int = 0,
    aiTrialMonths: Int = 0,
    /** Whether the current session is an anonymous guest session. */
    isAnonymous: Boolean = false,
    /** Customer count used to contextualise the Pro cloud-backup value proposition. */
    customerCount: Int = 0,
) {
    val free = tier == SubscriptionTier.FREE
    val isPro = tier == SubscriptionTier.PRO
    val isAi = tier == SubscriptionTier.AI

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
    ) {
        PlanCard(
            name = stringResource(R.string.subscription_plan_free),
            headline = stringResource(
                R.string.subscription_free_limits,
                FeatureAccessManager.FREE_CUSTOMER_LIMIT,
                FeatureAccessManager.FREE_SALE_LIMIT,
            ),
            isCurrent = free,
        )

        PlanCard(
            name = stringResource(R.string.subscription_plan_pro),
            headline = stringResource(R.string.subscription_pro_limits),
            price = proPrice?.let { planPriceText(it, proTrialMonths) }
                ?: stringResource(R.string.subscription_price_unknown),
            isCurrent = isPro,
        ) {
            Text(
                text = stringResource(
                    if (free) R.string.subscription_pro_adds else R.string.subscription_pro_includes
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PaidFeature.entries.forEach { feature -> FeatureLine(feature) }
            if (free) {
                Spacer(Modifier.width(NeoSpacing.xs))
                NeoAction(
                    label = stringResource(R.string.subscription_upgrade_action),
                    onClick = { onUpgrade(SubscriptionTier.PRO) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = proPrice != null,
                )
            }
        }

        PlanCard(
            name = stringResource(R.string.subscription_plan_ai),
            headline = stringResource(R.string.subscription_ai_limits),
            price = aiPrice?.let { planPriceText(it, aiTrialMonths) }
                ?: stringResource(R.string.subscription_price_unknown),
            isCurrent = isAi,
        ) {
            Text(
                text = stringResource(
                    if (isAi) R.string.subscription_ai_includes else R.string.subscription_ai_adds
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BulletLine(stringResource(R.string.subscription_ai_feature_pro_all))
            BulletLine(stringResource(R.string.subscription_ai_feature_execution))
            BulletLine(stringResource(R.string.subscription_ai_feature_text))
            if (free) {
                Spacer(Modifier.width(NeoSpacing.xs))
                NeoAction(
                    label = stringResource(R.string.subscription_upgrade_ai_action),
                    onClick = { onUpgrade(SubscriptionTier.AI) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = aiPrice != null,
                )
            } else if (isPro) {
                Spacer(Modifier.width(NeoSpacing.xs))
                NeoAction(
                    label = stringResource(R.string.subscription_switch_to_ai_action),
                    onClick = { onUpgrade(SubscriptionTier.AI) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = aiPrice != null,
                )
            }
        }

        val billingNote = if (isAnonymous) {
            if (customerCount > 0) {
                stringResource(R.string.subscription_guest_pro_note_with_count, customerCount)
            } else {
                stringResource(R.string.subscription_guest_pro_note_generic)
            }
        } else if (free) {
            stringResource(R.string.subscription_billing_note)
        } else {
            stringResource(R.string.subscription_play_note)
        }
        Text(
            text = billingNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One plan, readable at a glance: what it is called, the badge if it is yours, and the key facts
 * that distinguish it.
 */
@Composable
private fun PlanCard(
    name: String,
    headline: String,
    isCurrent: Boolean,
    price: String? = null,
    details: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Surface(
        shape = NeoShapes.card,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            if (isCurrent) 2.dp else 1.dp,
            if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(NeoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                if (isCurrent) {
                    Spacer(Modifier.width(NeoSpacing.sm))
                    CurrentPlanBadge()
                }
            }
            Text(text = headline, style = MaterialTheme.typography.titleLarge)
            price?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            details?.let {
                Spacer(Modifier.width(NeoSpacing.xs))
                it()
            }
        }
    }
}

@Composable
private fun CurrentPlanBadge() {
    Surface(
        shape = NeoShapes.row,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            text = stringResource(R.string.subscription_current_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = NeoSpacing.sm, vertical = 2.dp),
        )
    }
}

@Composable
private fun FeatureLine(feature: PaidFeature) {
    BulletLine(feature.explanation)
}

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "·",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(NeoSpacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
