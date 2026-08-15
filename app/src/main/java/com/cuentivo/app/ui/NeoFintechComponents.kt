package com.cuentivo.app.ui

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cuentivo.app.domain.Money

/**
 * The common screen rail. It protects the 320 dp composition while preventing large phones from
 * stretching a phone-sized panel beyond the 480 dp reference width.
 */
@Composable
internal fun NeoScreen(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(NeoSpacing.md),
    content: @Composable ColumnScope.(NeoFintechLayout) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        Column(
            modifier = Modifier
                .widthIn(max = layout.contentMaxWidthDp.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = layout.horizontalPaddingDp.dp),
            verticalArrangement = verticalArrangement,
        ) {
            content(layout)
        }
    }
}

/** Compact graphite surface with the hairline separation used by the references. */
@Composable
internal fun NeoPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = NeoShapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(
            1.dp,
            (accent ?: MaterialTheme.colorScheme.outlineVariant).copy(alpha = if (accent == null) 1f else 0.42f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .padding(NeoSpacing.md),
            content = content,
        )
    }
}

/**
 * A compact action that still reserves the Android 48 dp minimum touch target.
 *
 * **A disabled button here keeps its surface and readable text; only the contrast drops.** Material's
 * own defaults are `onSurface` at 12 % for the fill and 38 % for the label, which on this app's
 * graphite ground (`#0D0F11`) leaves nothing on screen at all — the button reads as broken rather
 * than as "not yet". Since every CTA in the app goes through here, that one default was why
 * "Guardar producto", "Agregar al carrito" and "Guardar abono" all looked missing at once.
 */
@Composable
internal fun NeoAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motionEnabled = LocalMotionEnabled.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && motionEnabled) NeoMotion.PRESSED_SCALE else 1f,
        animationSpec = tween(NeoMotion.QUICK_MS),
        label = "neo-action-press",
    )
    val touchModifier = modifier
        .defaultMinSize(minHeight = 48.dp)
        .scale(scale)

    if (primary) {
        Button(
            onClick = onClick,
            modifier = touchModifier,
            enabled = enabled,
            interactionSource = interactionSource,
            shape = NeoShapes.row,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                // Dimmed cyan, not absence: still obviously the same button, just not ready.
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = NeoDisabled.SURFACE),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = NeoDisabled.LABEL),
            ),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = touchModifier,
            enabled = enabled,
            interactionSource = interactionSource,
            shape = NeoShapes.row,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = NeoDisabled.LABEL),
            ),
            // The border fades with the label. Passing it explicitly is what used to keep it at full
            // strength while the text faded, so a disabled outlined button looked like a live one
            // with missing text.
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else NeoDisabled.BORDER),
            ),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun NeoMoney(
    amountCents: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
) {
    Text(
        text = Money.format(amountCents),
        modifier = modifier,
        style = style.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
    )
}

@Composable
internal fun NeoSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Structural screen sections announce themselves as headings so TalkBack users can navigate by
     * them; a decorative caption that is not a section boundary should pass `false`.
     */
    heading: Boolean = true,
) {
    // Centred, not bottom-aligned: a trailing TextButton carries its own vertical padding, so
    // bottom alignment pushed the action visibly above the title's baseline.
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            eyebrow?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(NeoSpacing.xs))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = if (heading) Modifier.semantics { heading() } else Modifier,
            )
        }
        trailing?.invoke()
    }
}

@Composable
internal fun NeoEmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    NeoPanel(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(NeoSpacing.xs))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(NeoSpacing.sm))
            NeoAction(actionLabel, onAction, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
