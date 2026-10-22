package com.cuentivo.app.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cuentivo.app.R

/** "$299.00 al mes", or "2 meses gratis, luego $299.00 al mes" when Play offers a free trial. */
@Composable
internal fun planPriceText(recurringPrice: String, freeTrialMonths: Int): String =
    if (freeTrialMonths > 1) {
        stringResource(R.string.subscription_price_with_trial, freeTrialMonths, recurringPrice)
    } else {
        stringResource(R.string.subscription_price_per_month, recurringPrice)
    }
