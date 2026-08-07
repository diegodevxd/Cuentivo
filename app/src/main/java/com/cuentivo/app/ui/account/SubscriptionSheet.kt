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
 * The plan section: which plan this account is on, what it costs, and the one way to change it.
 *
 * There is no wrapper composable in this file on purpose. Cuenta is already a `ModalBottomSheet`
 * and this content is a section inside it — a second sheet would put a sheet on top of a sheet for
 * no gain. It lives in its own file so the copy has a single home, and so a capture can render it
 * directly: a `ModalBottomSheet` is its own window, and photographing the app's composition would
 * produce an image with no sheet in it at all.
 *
 * It used to render a single card describing whichever plan you were already on, as a paragraph
 * followed by a bulleted list, with no price anywhere. Three problems in one: you could not see
 * what you were *not* on, the answer to "what does it cost" was behind a purchase sheet, and the
 * whole thing had to be read rather than glanced at. Now both plans are always on screen, the one
 * you have carries a badge, and the numbers that separate them are the headline instead of prose.
 *
 * Every limit is still read from [FeatureAccessManager] and every paid capability from
 * [PaidFeature], so a tightened free plan changes this section instead of going quietly out of
 * date.
 *
 * This screen never claims a purchase happened: entitlement arrives from `BillingGateway` through
 * the usual path, and until it does the account is still on the plan shown here. There is
 * deliberately no restore control — no such callback exists.
 */
@Composable
internal fun SubscriptionContent(
    tier: SubscriptionTier,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Pro's price exactly as Play formats it, or null while it has not loaded. Never a hardcoded
     * figure: see the parameter of the same name on `BillingGateway`.
     */
    proPrice: String? = null,
) {
    val free = tier == SubscriptionTier.FREE
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
            // Null means Play has not answered yet, which is also the permanent state on a phone
            // that cannot reach it. Pointing at Play beats printing a number nobody verified.
            price = proPrice?.let { stringResource(R.string.subscription_price_per_month, it) }
                ?: stringResource(R.string.subscription_price_unknown),
            isCurrent = !free,
        ) {
            Text(
                text = stringResource(
                    if (free) R.string.subscription_pro_adds else R.string.subscription_pro_includes
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The feature list is the enum, not a paraphrase of it. A capability that stops being
            // paid disappears from here by deleting one entry.
            PaidFeature.entries.forEach { feature -> FeatureLine(feature) }
        }

        if (free) {
            NeoAction(stringResource(R.string.subscription_upgrade_action), onUpgrade, Modifier.fillMaxWidth())
        }
        Text(
            text = stringResource(
                if (free) R.string.subscription_billing_note else R.string.subscription_play_note
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One plan, readable at a glance: what it is called, the badge if it is yours, and the single fact
 * that distinguishes it from the other one.
 *
 * The plan you are on gets the accented border and the badge. The one you are not on stays quiet —
 * an outline that shouted on both cards would leave the eye with nothing to land on, which is the
 * state this screen was in before.
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
            // The number is the headline, not a sentence containing the number. "5 clientes y 30
            // ventas" beside "Clientes y ventas sin límite" is the entire comparison, and it is
            // meant to be taken in without reading.
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
    Row(modifier = Modifier.fillMaxWidth()) {
        // A bullet rather than a tick: nothing here is a claim about what has been unlocked.
        Text(
            text = "·",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(NeoSpacing.sm))
        Text(
            text = feature.explanation,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
