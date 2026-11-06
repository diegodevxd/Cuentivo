package com.cuentivo.app.ai

import com.cuentivo.app.sync.normalizeFirestoreValue
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.functions.FirebaseFunctions
import dev.gitlive.firebase.functions.functions
import dev.gitlive.firebase.functions.ios
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * El transporte del callable `interpretAiRequest` en iOS. Traducción de `FirebaseCallableAiGateway`.
 *
 * Mismos nombres de función, misma región (`us-south1`) y el mismo contrato v2, que ahora vive en
 * común para que no haya dos copias. El parseo de la respuesta es el mismo `parseCallableProposalResponse`,
 * así que un cambio de forma en el servidor falla igual en las dos plataformas.
 *
 * Los enteros no pasan por `Double`: la respuesta se lee del objeto nativo (`HttpsCallableResult.ios`)
 * y se normaliza con [normalizeFirestoreValue] antes de parsearla, igual que los documentos de
 * Firestore. La alternativa —la deserialización de GitLive— convertiría un centavo grande en un
 * decimal sin que nadie lo notara.
 *
 * Todo fallo —error de red, timeout, forma inesperada, dato malformado— colapsa a
 * [CallableAiProposalResult.Unavailable], sin logs y sin exponer mensajes, ids ni texto.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCallableAiGateway(
    private val functions: FirebaseFunctions = Firebase.functions(AI_FUNCTIONS_REGION),
) {

    suspend fun callContractV2(request: AiInterpretRequest): CallableAiProposalResult = try {
        val payload = buildAiCallablePayload(request)
        val result = functions.httpsCallable(AI_CALLABLE_NAME, null).invoke(payload)
        parseCallableProposalResponse(
            data = normalizeFirestoreValue(result.ios.data()),
            expectedRequestId = request.requestId,
        )
    } catch (_: Throwable) {
        CallableAiProposalResult.Unavailable
    }
}
