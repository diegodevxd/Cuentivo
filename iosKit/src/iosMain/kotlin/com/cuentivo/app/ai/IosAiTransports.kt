package com.cuentivo.app.ai

import com.cuentivo.app.sync.normalizeFirestoreValue
import com.cuentivo.app.ui.ai.AiDebugTransport
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.FirebaseFunctions
import dev.gitlive.firebase.functions.functions
import dev.gitlive.firebase.functions.ios
import kotlinx.cinterop.ExperimentalForeignApi

/** El transporte de la pantalla de IA en iOS. El nombre es el de la interfaz común. */
class IosAiTransport(
    private val gateway: IosCallableAiGateway = IosCallableAiGateway(),
) : AiDebugTransport {
    override suspend fun submit(request: AiInterpretRequest): CallableAiProposalResult =
        gateway.callContractV2(request)
}

/**
 * Disponibilidad remota de IA. Mismo diseño que Android: un fallo devuelve null para conservar el
 * estado conocido; quien decide de verdad es el servidor, que falla cerrado.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAiAvailabilityGateway(
    private val functions: FirebaseFunctions = Firebase.functions(AI_FUNCTIONS_REGION),
) : AiAvailabilityGateway {
    override suspend fun checkAvailability(): Boolean? = try {
        val result = functions.httpsCallable(AI_AVAILABILITY_CALLABLE_NAME, null).invoke()
        parseAvailabilityResponse(normalizeFirestoreValue(result.ios.data()))
    } catch (_: Throwable) {
        null
    }
}

/** Despertar del Ledger: es un favor, ningún error se propaga. */
class IosMexalmLedgerWarmGateway(
    private val functions: FirebaseFunctions = Firebase.functions(AI_FUNCTIONS_REGION),
) : MexalmLedgerWarmGateway {
    override suspend fun warm(): Boolean = try {
        functions.httpsCallable(MEXALM_WARM_CALLABLE_NAME, null).invoke()
        true
    } catch (_: Throwable) {
        false
    }
}
