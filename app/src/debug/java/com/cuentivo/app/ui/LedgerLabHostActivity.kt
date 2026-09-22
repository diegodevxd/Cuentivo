package com.cuentivo.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import com.cuentivo.app.ai.ledger.LedgerExtractionCallResult
import com.cuentivo.app.ai.ledger.LedgerExtractionPayload
import com.cuentivo.app.ai.ledger.LedgerOperation
import com.cuentivo.app.ai.ledger.ProposedLedgerKind
import com.cuentivo.app.ai.ledger.UncertainLedgerField
import com.cuentivo.app.data.local.AiDailyChatRepository
import com.cuentivo.app.data.local.AiDailyMessageDao
import com.cuentivo.app.data.local.AiDailyMessageEntity
import com.cuentivo.app.ui.ai.AiDebugLabScreen
import com.cuentivo.app.ui.ai.AiDebugTransport

/**
 * Hosts the AI lab on an Activity with no configChanges, so rotating it recreates the Activity
 * for real. The production MainActivity absorbs orientation changes itself; the recreation the
 * review saver exists to survive still happens to it through process death and uiMode changes,
 * and this host reproduces the mechanism faithfully.
 *
 * The doubles live in the companion so they survive the recreation too — the whole point is that
 * the counter must observe both lives of the Activity.
 */
class LedgerLabHostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides PickerRegistryOwner(ImmediatePickerRegistry()),
            ) {
                AiDebugLabScreen(
                    transport = object : AiDebugTransport {
                        override suspend fun submit(request: com.cuentivo.app.ai.AiInterpretRequest) =
                            com.cuentivo.app.ai.CallableAiProposalResult.Unavailable
                    },
                    repository = repositoryStub(),
                    onClose = {},
                    activeBusinessId = "business-1",
                    extractLedgerPage = { _, _, _ ->
                        extractionCalls++
                        LedgerExtractionCallResult.Extracted(pagePayload())
                    },
                    ledgerBalancesResolver = { emptyMap() },
                    ledgerCustomersResolver = { customerCandidates },
                    ledgerProductsResolver = { emptyList() },
                    ledgerRegisteredResolver = { _, _, _ -> emptyList() },
                    confirmLedgerBatch = { _, _, _, _, _, _, _, _ -> com.cuentivo.app.domain.LedgerBatchConfirmation.Failed },
                )
            }
        }
    }

    /** A registry that answers the photo picker with the URI this test controls. */
    private class ImmediatePickerRegistry : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
            input: I,
            options: androidx.core.app.ActivityOptionsCompat?,
        ) {
            if (contract is androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia) {
                dispatchResult(requestCode, pickedUri)
            }
        }
    }

    private class PickerRegistryOwner(private val registry: ActivityResultRegistry) : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    private fun repositoryStub(): AiDailyChatRepository {
        val dao = object : AiDailyMessageDao {
            override suspend fun insert(message: AiDailyMessageEntity): Long = 1L
            override suspend fun messagesForDay(ownerUserId: String, dayIso: String): List<AiDailyMessageEntity> = emptyList()
            override suspend fun deleteDay(ownerUserId: String, dayIso: String) {}
            override suspend fun deleteExpiredDays(ownerUserId: String, cutoffDayIso: String) {}
        }
        return AiDailyChatRepository(dao = dao, ownerUserId = "test-user")
    }

    companion object {
        /** Counts every invocation of the extraction callable. One per page, ever. */
        var extractionCalls = 0

        /** The URI the stubbed picker hands to the gallery flow. */
        lateinit var pickedUri: android.net.Uri

        /**
         * Página alternativa, para la prueba que graba el video de la ficha.
         *
         * Por defecto `null` y el host sirve la de siempre, así que la prueba de rotación (su único
         * otro usuario) no se entera de que esto existe. La prueba del video la pone para recorrer la
         * misma libreta inventada de las capturas.
         */
        var pagePayloadOverride: LedgerExtractionPayload? = null

        /**
         * Clientas que ya existen, para que la libreta del video pueda traer el aviso de "este cliente
         * ya existe" —el mismo que vende la captura 8—. Vacío por defecto.
         */
        var customerCandidates: List<com.cuentivo.app.domain.CustomerCandidate> = emptyList()

        fun pagePayload(): LedgerExtractionPayload = pagePayloadOverride ?: defaultPagePayload()

        /** The page the callable double reads: two rows, one of them doubtful. */
        fun defaultPagePayload() = LedgerExtractionPayload(
            operations = listOf(
                LedgerOperation(
                    lineIndex = 0,
                    proposedKind = ProposedLedgerKind.SALE,
                    customerName = "Rosa Martinez",
                    amountCents = "25000",
                    date = null,
                ),
                LedgerOperation(
                    lineIndex = 1,
                    proposedKind = ProposedLedgerKind.PAYMENT,
                    customerName = "Rosa Martinez",
                    amountCents = "10000",
                    date = null,
                    uncertainFields = listOf(UncertainLedgerField.AMOUNT),
                ),
            ),
            discards = emptyList(),
            unreadTextRemaining = false,
            pageIncomplete = false,
            detectedPageDate = com.cuentivo.app.ai.ledger.LedgerDetectedPageDate(
                com.cuentivo.app.ai.ledger.SafeDateCandidate(2026, 7, 30),
                0.95,
            ),
        )
    }
}
