package com.cuentivo.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cuentivo.app.R
import com.cuentivo.app.ui.NeoAction
import com.cuentivo.app.ui.NeoFintechLayoutPolicy
import com.cuentivo.app.ui.NeoPanel
import com.cuentivo.app.ui.NeoSpacing

/**
 * The wall a brand new account meets until it proves its address exists.
 *
 * It is a wall and not a banner on purpose: an account whose address nobody can reach has no way
 * back in if the password is forgotten, and the ledger lives only on that phone. Accounts that
 * predate this screen never see it — see `EmailVerificationPolicy`.
 *
 * "Ya verifiqué" is needed because Firebase caches the verified flag in the local token: clicking
 * the link in the inbox does not tell this app anything until it re-reads the account.
 */
@Composable
fun EmailVerificationScreen(
    email: String?,
    isWorking: Boolean,
    message: String?,
    onCheck: () -> Unit,
    onResend: () -> Unit,
    onUseAnotherAccount: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val layout = NeoFintechLayoutPolicy.forWidth(maxWidth.value.toInt())
        Column(
            modifier = Modifier
                .widthIn(max = layout.contentMaxWidthDp.dp)
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = maxOf(16, layout.horizontalPaddingDp).dp)
                .padding(vertical = NeoSpacing.xl)
                .testTag("email-verification"),
            verticalArrangement = Arrangement.Center,
        ) {
            NeoPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(NeoSpacing.md)) {
                    Text(
                        text = stringResource(R.string.verify_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = email?.let { stringResource(R.string.verify_detail, it) }
                            ?: stringResource(R.string.verify_detail_no_email),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Start,
                        )
                    }
                    NeoAction(
                        label = stringResource(R.string.verify_check),
                        onClick = onCheck,
                        modifier = Modifier.fillMaxWidth().testTag("verify-check"),
                        enabled = !isWorking,
                    )
                    if (isWorking) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                    TextButton(
                        onClick = onResend,
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("verify-resend"),
                    ) { Text(stringResource(R.string.verify_resend)) }
                    TextButton(
                        onClick = onUseAnotherAccount,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("verify-sign-out"),
                    ) { Text(stringResource(R.string.verify_use_another_account)) }
                }
            }
        }
    }
}
