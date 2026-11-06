package com.cuentivo.app.ui.account

import androidx.compose.runtime.Composable
// Los accesos de Res.string son propiedades de extensión generadas una por clave.
import com.cuentivo.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/** "$299.00 al mes", or "2 meses gratis, luego $299.00 al mes" when Play offers a free trial. */
@Composable
fun planPriceText(recurringPrice: String, freeTrialMonths: Int): String =
    if (freeTrialMonths > 1) {
        stringResource(Res.string.subscription_price_with_trial, freeTrialMonths, recurringPrice)
    } else {
        stringResource(Res.string.subscription_price_per_month, recurringPrice)
    }
