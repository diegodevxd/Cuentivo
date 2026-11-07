package com.cuentivo.app.ai

import com.cuentivo.app.FUNCTIONS_REGION
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                val task = functions.getHttpsCallable(MEXALM_WARM_CALLABLE_NAME).call()
                Tasks.await(task).data
                true
            }
        } catch (_: Exception) {
            // Ni la red, ni la callable, ni el Ledger pueden tumbar la app por un despertar.
            false
        }
    }
}
