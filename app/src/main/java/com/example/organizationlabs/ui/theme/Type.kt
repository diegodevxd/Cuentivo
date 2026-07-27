package com.cuentivo.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The scale that decides what the eye reads first.
 *
 * This file used to be the Android Studio template: one overridden `bodyLarge` and a commented-out
 * block. Everything therefore rendered at roughly the same weight, which is why "Por cobrar" — the
 * number the whole app exists to answer — looked exactly like "Vendido este mes". Hierarchy is not
 * decoration here; it is the difference between glancing at the phone and having to read it.
 *
 * No custom font on purpose. A downloaded typeface is a dependency plus APK weight, and this ships
 * to cheap phones on slow connections. [FontFamily.Default] with deliberate sizes and weights does
 * the job.
 *
 * Tighter letter spacing than Material's defaults on the big styles: large text set loose looks
 * unfinished, and money in particular reads better packed.
 */
val Typography = Typography(
    // The hero amount, and nothing else. If two things on a screen use this, one of them is wrong.
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1).sp,
    ),
    // Secondary amounts: the month totals, a customer's balance in their statement.
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    // Screen titles.
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.3).sp,
    ),
    // Section headers — "Quién me debe", "Tu cuenta".
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    // A person's name, a product's name: the thing you are scanning a list for.
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    // Buttons and chips. Medium rather than Normal so a tappable thing looks tappable.
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    // Captions and helper text. Never used for money or for anything a person must act on.
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    // Nav bar labels. 11sp is the floor: smaller stops being legible on a cheap screen outdoors.
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
)
