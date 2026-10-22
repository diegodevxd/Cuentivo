package com.cuentivo.app.ai

import com.cuentivo.app.FUNCTIONS_REGION
import com.cuentivo.app.domain.SubscriptionTier
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WARM_CALLABLE_NAME = "warmMexalmLedger"

/**
 * Quién puede pedir que el Ledger de MexaLM se despierte.
 *
 * Sólo quien tiene CuentivoAI: es el único plan que usa el Ledger, y despertarlo por otra
 * cuenta sería pagar por un servicio que nadie va a usar.
 */
object MexalmLedgerWarmPolicy {
    fun shouldWarm(tier: SubscriptionTier): Boolean = tier == SubscriptionTier.AI
}

/**
 * Despierta el Ledger de MexaLM.
 *
 * El Ledger es un servicio que se apaga solo cuando nadie lo usa y tarda entre 30 y 60 s en
 * volver. Abrir la app con CuentivoAI le pide que empiece a despertarse, para que la primera
 * lectura de la libreta no espere a que la instancia levante.
 *
 * Es un favor, no una promesa: **ningún error se propaga**. Si la red falla, si la callable no
 * existe todavía en el servidor, si el Ledger está caído o si la cuenta no tiene el plan, lo
 * único que pasa es que el Ledger no despertó — y la app sigue haciendo exactamente lo que
 * hacía.
 */
interface MexalmLedgerWarmGateway {
    /**
     * Pide el despertar. `true` cuando el servidor dijo que lo pidió; `false` cuando no se pudo
     * pedir o no había nada que despertar.
     */
    suspend fun warm(): Boolean
}

/**
 * Transporte del despertar: la callable regional `warmMexalmLedger`.
 *
 * El bloqueo de `Tasks.await` vive en `Dispatchers.IO`, como en los demás gateways de
 * callables: la UI nunca espera.
 */
class FirebaseMexalmLedgerWarmGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION),
) : MexalmLedgerWarmGateway {

    override suspend fun warm(): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val task = functions.getHttpsCallable(WARM_CALLABLE_NAME).call()
                Tasks.await(task).data
                true
            }
        } catch (_: Exception) {
            // Ni la red, ni la callable, ni el Ledger pueden tumbar la app por un despertar.
            false
        }
    }
}
