package com.cuentivo.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The stable graphite ground and functional accents sampled from the approved Stitch captures.
 *
 * Every value is derived from [CuentivoPalette] so the scheme and the accessibility floors over it
 * can never drift apart.
 */
val NeoGraphite = Color(CuentivoPalette.Dark.background)
val NeoGraphiteRaised = Color(CuentivoPalette.Dark.surface)
val NeoGraphiteHigh = Color(CuentivoPalette.Dark.surfaceVariant)
val NeoHairline = Color(CuentivoPalette.Dark.outlineVariant)
val NeoText = Color(CuentivoPalette.Dark.onSurface)
val NeoMuted = Color(CuentivoPalette.Dark.onSurfaceVariant)

/** Cyan is the dark scheme's primary/receivable colour. */
val NeoCyan = Color(CuentivoPalette.Dark.primary)

/** Green is reserved for money collected and positive payment status. */
val NeoPayment = Color(CuentivoPalette.Dark.secondary)
val NeoAcid = NeoPayment

/** Violet is the dark scheme's tertiary role, muted and sober. */
val NeoViolet = Color(CuentivoPalette.Dark.tertiary)

/** Red is reserved for overdue, destructive, and error states. */
val NeoOverdue = Color(CuentivoPalette.Dark.error)

/**
 * The middle of the payment traffic light: pays, but late.
 *
 * Amber rather than a paler red, because "chase this one" and "this one is a loss" have to be
 * distinguishable at a glance on a phone held at arm's length behind a counter.
 */
val NeoSlow = Color(CuentivoPalette.Dark.warning)
