package com.cuentivo.app.ui

internal enum class NeoPhoneWidth {
    COMPACT,
    STANDARD,
    LARGE,
}

internal data class NeoFintechLayout(
    val widthClass: NeoPhoneWidth,
    val horizontalPaddingDp: Int,
    val contentMaxWidthDp: Int,
    val stackPrimaryActions: Boolean,
    val stackLedgerAmounts: Boolean,
)

/**
 * Pure layout decisions shared by the key screens.
 *
 * Keeping breakpoints outside Compose makes the narrowest supported phones deterministic and
 * testable instead of relying on whichever device happened to be used during development.
 */
internal object NeoFintechLayoutPolicy {
    private const val STANDARD_MIN_DP = 360
    private const val LARGE_MIN_DP = 412

    fun forWidth(widthDp: Int): NeoFintechLayout = when {
        widthDp >= LARGE_MIN_DP -> NeoFintechLayout(
            widthClass = NeoPhoneWidth.LARGE,
            horizontalPaddingDp = 20,
            contentMaxWidthDp = 480,
            stackPrimaryActions = false,
            stackLedgerAmounts = false,
        )

        widthDp >= STANDARD_MIN_DP -> NeoFintechLayout(
            widthClass = NeoPhoneWidth.STANDARD,
            horizontalPaddingDp = 16,
            contentMaxWidthDp = 480,
            stackPrimaryActions = false,
            stackLedgerAmounts = false,
        )

        else -> NeoFintechLayout(
            widthClass = NeoPhoneWidth.COMPACT,
            horizontalPaddingDp = 14,
            contentMaxWidthDp = 480,
            stackPrimaryActions = true,
            stackLedgerAmounts = true,
        )
    }
}
