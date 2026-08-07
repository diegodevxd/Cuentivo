package com.cuentivo.app.ui.theme

import androidx.compose.ui.graphics.Color

/** The stable graphite ground and functional accents sampled from the approved Stitch captures. */
val NeoGraphite = Color(0xFF0D0F11)
val NeoGraphiteRaised = Color(0xFF1B1D1F)
val NeoGraphiteHigh = Color(0xFF232629)
val NeoHairline = Color(0xFF303438)
val NeoText = Color(0xFFF2F5F4)
val NeoMuted = Color(0xFFA9B0B4)

/** Cyan is the only primary/receivable colour. */
val NeoCyan = Color(0xFF18D8E6)

/** Green is reserved for money collected and positive payment status. */
val NeoPayment = Color(0xFFB7FF35)
val NeoAcid = NeoPayment

/** Violet appears only in the restrained hero aura and secondary highlights. */
val NeoViolet = Color(0xFFA98BFA)

/** Red is reserved for overdue, destructive, and error states. */
val NeoOverdue = Color(0xFFFF7B82)

/**
 * The middle of the payment traffic light: pays, but late.
 *
 * Amber rather than a paler red, because "chase this one" and "this one is a loss" have to be
 * distinguishable at a glance on a phone held at arm's length behind a counter.
 */
val NeoSlow = Color(0xFFFFC65C)
