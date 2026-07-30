package com.cuentivo.app.ui

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cuentivo.app.domain.Money

@Composable
internal fun NeoResponsiveColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(14.dp),
    content: @Composable ColumnScope.(NeoFintechLayout) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = layout.horizontalPaddingDp.dp),
            verticalArrangement = verticalArrangement,
        ) {
            content(layout)
        }
    }
}

@Composable
internal fun NeoSectionTitle(
    title: String,
    eyebrow: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            eyebrow?.let {
                Text(
                    it.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(3.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        trailing?.invoke()
    }
}

/**
 * A GPU-drawn aurora rather than a bitmap. Compose turns these gradients into platform shaders, so
 * the same card works on API 24 without touching RuntimeShader (API 33).
 */
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
            .drawBehind { drawNeoAurora() },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                Money.format(amountCents),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.drawNeoAurora() {
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF11191E), Color(0xFF12131B), Color(0xFF0E171A)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x5521E6F3), Color.Transparent),
            center = Offset(size.width * 0.12f, size.height * 0.1f),
            radius = size.maxDimension * 0.72f,
        ),
        radius = size.maxDimension * 0.72f,
        center = Offset(size.width * 0.12f, size.height * 0.1f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x339B7BFF), Color.Transparent),
            center = Offset(size.width * 0.92f, size.height),
            radius = size.maxDimension * 0.62f,
        ),
        radius = size.maxDimension * 0.62f,
        center = Offset(size.width * 0.92f, size.height),
    )
}

@Composable
internal fun NeoPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motionEnabled = rememberMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motionEnabled) 0.97f else 1f,
        animationSpec = tween(190),
        label = "neo-button-press",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .scale(scale),
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun NeoMoneyPill(
    label: String,
    amountCents: Long,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .size(7.dp)
                    .drawBehind { drawCircle(accent) },
            )
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                Money.format(amountCents),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = accent,
                maxLines = 1,
            )
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
