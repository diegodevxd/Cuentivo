package com.cuentivo.app.ui.theme

import androidx.compose.ui.graphics.Color

// Cuentivo's own identity: a light base with fluorescent accents. Nothing borrowed from any
// catalogue brand, and deliberately not the purple Android Studio template palette.
//
// LIGHT IS THE PRIMARY THEME. That is not a preference: this app gets used standing on the street in
// full sun, where a dark background with 12%-white translucent surfaces is the worst possible thing
// to read. The neon is what makes it feel alive — but only as an accent, never as the page.

// ─────────────────────────────────────────────────────────────────────────────
// Light base — the backdrop and the ink that sits on it.
// ─────────────────────────────────────────────────────────────────────────────
val CloudWhite = Color(0xFFF7FAFC)
val MistBlue = Color(0xFFE6EDFB)
val MintHaze = Color(0xFFE2F7F0)
val BlushHaze = Color(0xFFFBE9F3)

/** Primary text. ~17:1 on the light backdrop — readable outdoors, which is the whole point. */
val DeepInk = Color(0xFF0B1B23)

/** Secondary text. ~6.9:1 on white: dimmer than the primary, still well past the 4.5:1 floor. */
val SlateInk = Color(0xFF4A5D68)

// ─────────────────────────────────────────────────────────────────────────────
// Neon — FILLS ONLY.
//
// These are gorgeous and nearly invisible as text on a light surface: NeonMint on white is about
// 1.7:1, far under the 4.5:1 minimum. Use them as backgrounds, borders, indicators and glows, with
// [DeepInk] on top — NeonMint filled with DeepInk text measures ~10:1, which is excellent.
// For neon-coloured *text*, use the Ink variants below instead.
// ─────────────────────────────────────────────────────────────────────────────
val NeonMint = Color(0xFF00E5A0)
val NeonCyan = Color(0xFF22D3EE)
val NeonMagenta = Color(0xFFFF2E93)
val NeonLime = Color(0xFFC6FF4A)

// ─────────────────────────────────────────────────────────────────────────────
// Ink variants — the same hues, darkened until they are safe as TEXT on light.
// Measured against white: mint 5.4:1, cyan 5.4:1, magenta 5.9:1.
// ─────────────────────────────────────────────────────────────────────────────
val MintInk = Color(0xFF00795A)
val CyanInk = Color(0xFF0E7490)
val MagentaInk = Color(0xFFC2185B)

/** Overdue money. Deliberately not the neon magenta: bad news has to survive sunlight too. */
val AlertInk = Color(0xFFB3261E)

// ─────────────────────────────────────────────────────────────────────────────
// Light surfaces.
//
// Cards stay slightly translucent so the animated backdrop shows through — that is where the glass
// comes from, since Modifier.blur does not exist on minSdk 24. Dialogs and sheets are near-opaque on
// purpose: a Card floats over a gradient, but a dialog floats over a full screen of text, and at card
// opacity the two layers read as one unreadable mess. That bug already shipped once.
// ─────────────────────────────────────────────────────────────────────────────
val GlassLight = Color(0xB3FFFFFF)
val GlassLightRaised = Color(0xE6FFFFFF)
val GlassLightBorder = Color(0x1F0B1B23)
val OverlayLight = Color(0xFAFCFEFF)

// ─────────────────────────────────────────────────────────────────────────────
// Dark theme — the alternative, not the design. Same neon, inverted ground.
// Here the neon CAN carry text: NeonMint on DeepNavy is ~11:1.
// ─────────────────────────────────────────────────────────────────────────────
val DeepNavy = Color(0xFF080D18)
val DeepTeal = Color(0xFF0B2430)
val DeepPlum = Color(0xFF160B24)
val PaleMist = Color(0xFFE9F1F5)

val GlassDark = Color(0x1FFFFFFF)
val GlassDarkRaised = Color(0x2EFFFFFF)
val GlassDarkBorder = Color(0x33FFFFFF)
val OverlayDark = Color(0xF00C1721)

// Neo-Fintech system. Graphite is the ground; fluorescent hues carry meaning, never decoration.
val NeoGraphite = Color(0xFF090B0E)
val NeoGraphiteRaised = Color(0xFF12161B)
val NeoGraphiteHigh = Color(0xFF191E25)
val NeoHairline = Color(0xFF2A313A)
val NeoText = Color(0xFFF4F7FA)
val NeoMuted = Color(0xFF9AA5B1)
val NeoCyan = Color(0xFF21E6F3)
val NeoAcid = Color(0xFFA8FF35)
val NeoViolet = Color(0xFF9B7BFF)
val NeoOverdue = Color(0xFFFF6B78)
