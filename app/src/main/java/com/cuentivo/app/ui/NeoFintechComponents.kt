package com.cuentivo.app.ui

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
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

/** A compact action that still reserves the Android 48 dp minimum touch target. */
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
    val motionEnabled = rememberMotionEnabled()
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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
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
            Text(title, style = MaterialTheme.typography.titleLarge)
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

/** A dense, reusable directory row for customers and products. */
@Composable
internal fun NeoDirectoryRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    amountCents: Long? = null,
    status: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    NeoPanel(modifier = rowModifier, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                status?.let {
                    Spacer(Modifier.height(NeoSpacing.xs))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1)
                }
            }
            amountCents?.let {
                NeoMoney(
                    amountCents = it,
                    modifier = Modifier.padding(start = NeoSpacing.sm),
                    color = accent,
                )
            }
        }
    }
}

/** Compatibility bridge while existing screens migrate to the dedicated primitives. */
@Composable
internal fun NeoResponsiveColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(NeoSpacing.md),
    content: @Composable ColumnScope.(NeoFintechLayout) -> Unit,
) = NeoScreen(modifier, verticalArrangement, content)

@Composable
internal fun NeoSectionTitle(
    title: String,
    eyebrow: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) = NeoSectionHeader(title = title, eyebrow = eyebrow, trailing = trailing)

/** Aurora stays inside the hero bounds; the application backdrop itself remains still. */
@Composable
internal fun NeoBalanceHero(
    label: String,
    amountCents: Long,
    supportingText: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 126.dp)
            .drawBehind { drawNeoHeroAura() },
        shape = NeoShapes.hero,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(horizontal = NeoSpacing.lg, vertical = NeoSpacing.md)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(NeoSpacing.xs))
            NeoMoney(
                amountCents = amountCents,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(NeoSpacing.sm))
            Text(supportingText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun DrawScope.drawNeoHeroAura() {
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF10161A), Color(0xFF111318), Color(0xFF111319)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        cornerRadius = CornerRadius(18.dp.toPx()),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(MaterialThemeColorCyan.copy(alpha = 0.18f), Color.Transparent),
            center = Offset(size.width * 0.16f, size.height * 0.12f),
            radius = size.maxDimension * 0.68f,
        ),
        radius = size.maxDimension * 0.68f,
        center = Offset(size.width * 0.16f, size.height * 0.12f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(MaterialThemeColorViolet.copy(alpha = 0.12f), Color.Transparent),
            center = Offset(size.width * 0.94f, size.height),
            radius = size.maxDimension * 0.58f,
        ),
        radius = size.maxDimension * 0.58f,
        center = Offset(size.width * 0.94f, size.height),
    )
}

private val MaterialThemeColorCyan = Color(0xFF21E6F3)
private val MaterialThemeColorViolet = Color(0xFF9B7BFF)

@Composable
internal fun NeoPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = NeoAction(label, onClick, modifier, enabled)

@Composable
internal fun NeoMoneyPill(
    label: String,
    amountCents: Long,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    NeoPanel(modifier = modifier, accent = accent) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(NeoSpacing.xs))
        NeoMoney(amountCents, color = accent)
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
