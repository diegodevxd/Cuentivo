package com.cuentivo.app.ui.theme

import androidx.compose.ui.graphics.Color

// Cuentivo's own identity: deep teal-navy with a mint accent. Nothing borrowed from any catalogue
// brand, and deliberately not the purple Android Studio template palette.

// Dark theme — the one the app is designed around.
val DeepNavy = Color(0xFF0A1220)
val DeepTeal = Color(0xFF0D2A2E)
val MintAccent = Color(0xFF4ADE9B)
val SkyAccent = Color(0xFF5EA9FF)
val WarmSand = Color(0xFFF2C879)

/**
 * Translucent surfaces are what make the glass effect work: cards let the background gradient show
 * through instead of sitting on a flat sheet of grey.
 */
val GlassDark = Color(0x1FFFFFFF)
val GlassDarkRaised = Color(0x2EFFFFFF)
val GlassDarkBorder = Color(0x33FFFFFF)

/**
 * Floating surfaces — dialogs and bottom sheets — are nearly opaque on purpose.
 *
 * A card can be see-through because it sits on the gradient. A dialog sits on top of a full screen
 * of text, and at card opacity the two layers read as one unreadable mess.
 */
val OverlayDark = Color(0xF0142029)

// Light theme — same shapes, brighter glass.
val PaleMist = Color(0xFFEFF4F8)
val DeepInk = Color(0xFF0B1B23)
val TealPrimaryLight = Color(0xFF0F766E)
val BlueSecondaryLight = Color(0xFF2563EB)

val GlassLight = Color(0x99FFFFFF)
val GlassLightRaised = Color(0xCCFFFFFF)
val GlassLightBorder = Color(0x33122B33)
val OverlayLight = Color(0xF7FBFDFF)
