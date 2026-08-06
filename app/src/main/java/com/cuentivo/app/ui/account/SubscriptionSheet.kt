package com.cuentivo.app.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * The plan section: which plan this account is on, what that plan allows, and the one way to
 * change it.
 *
 * There is no wrapper composable in this file on purpose. Cuenta is already a `ModalBottomSheet`
 * and this content is a section inside it — a second sheet would put a sheet on top of a sheet for
 * no gain. It lives in its own file so the copy has a single home, and so a capture can render it
 * directly: a `ModalBottomSheet` is its own window, and photographing the app's composition would
 * produce an image with no sheet in it at all.
 *
 * Every number and every line of copy is read from [FeatureAccessManager] and [PaidFeature]. When
 * the free plan tightens, this section changes with it instead of going quietly out of date.
 *
 * The only callback is [onUpgrade], which launches the Play purchase flow. This screen never claims
 * a purchase happened: entitlement arrives from `BillingGateway` through the usual path, and until
 * it does the account is still on the plan shown here. There is deliberately no restore control —
 * no such callback exists — and no price, because the price lives in Play.
 */
@Composable
internal fun SubscriptionContent(
    tier: SubscriptionTier,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val free = tier == SubscriptionTier.FREE
    Surface(
        shape = NeoShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(NeoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
        ) {
            Text(
                text = if (free) "Plan Gratis" else "Cuentivo Pro",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (free) {
                    "Hasta ${FeatureAccessManager.FREE_CUSTOMER_LIMIT} clientes y " +
                        "${FeatureAccessManager.FREE_SALE_LIMIT} ventas registradas."
                } else {
                    "Clientes y ventas sin límite."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = if (free) "Cuentivo Pro agrega:" else "Tu plan incluye:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The feature list is the enum, not a paraphrase of it. A capability that stops being
            // paid disappears from here by deleting one entry.
            PaidFeature.entries.forEach { feature -> FeatureLine(feature) }

            if (free) {
                NeoAction("Suscribirme a Pro", onUpgrade, Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.subscription_billing_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.subscription_play_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
