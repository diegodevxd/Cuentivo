package com.cuentivo.app.sync

import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.createCuentivoDatabase
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Cómo terminó una corrida de sync en iOS. */
sealed interface IosSyncOutcome {
    /** Todo lo que había se subió y lo nuevo se fusionó. */
    data object Success : IosSyncOutcome

    /** Algo se quedó a medias y conviene reintentar más tarde. */
    data object Retry : IosSyncOutcome

    /** Ya había una corrida en curso; esta no hizo nada. */
    data object AlreadyRunning : IosSyncOutcome

    /** No hay usuario de Firebase: no se tocó la red. */
    data object SignedOut : IosSyncOutcome
}

/**
 * Una sola base por proceso.
 *
 * El gateway de iOS bloquea y Room abre conexiones: crear otra instancia duplicaría ambas cosas. El
 * builder es el mismo de la Fase 3 (`createCuentivoDatabase`, con `BundledSQLiteDriver` y sin
 * migraciones, porque en iOS la base siempre nace en la versión 8).
 */
object IosDatabaseHolder {
    val instance: CuentivoDatabase by lazy { createCuentivoDatabase() }
}

/**
 * El motor de sync de iOS: traducción de `SyncWorker.doWork`.
 *
 * **Push y luego pull, en ese orden.** Un dispositivo que acaba de escribir debe publicar antes de
 * adoptar, para que la fusión vea sus propias filas como ya presentes en vez de competir con ellas.
 *
 * Siempre corre en `Dispatchers.Default` y **nunca en el hilo principal**: el gateway de Firestore es
 * bloqueante (espera el callback con un semáforo) y desde `main` congelaría la app. Dos llamadas
 * simultáneas no se pisan: la segunda recibe [IosSyncOutcome.AlreadyRunning].
 */
class IosSyncEngine(
    private val database: CuentivoDatabase = IosDatabaseHolder.instance,
    private val gateway: IosFirestoreSyncGateway = IosFirestoreSyncGateway(),
    private val currentUserId: () -> String? = { Firebase.auth.currentUser?.uid },
) {
    private val running = Mutex()

    suspend fun syncNow(): IosSyncOutcome {
        // `tryLock` en vez de `lock`: una segunda corrida no espera, se va.
        if (!running.tryLock()) return IosSyncOutcome.AlreadyRunning
        return try {
            withContext(Dispatchers.Default) { run() }
        } finally {
            running.unlock()
        }
    }

    private suspend fun run(): IosSyncOutcome {
        // Sin usuario no se toca la red: el gateway ni se llama.
        if (currentUserId() == null) return IosSyncOutcome.SignedOut

        return try {
            val pushNeedsRetry = push()
            val pullNeedsRetry = pull()
            if (pushNeedsRetry || pullNeedsRetry) IosSyncOutcome.Retry else IosSyncOutcome.Success
        } catch (error: Throwable) {
            // Igual que Android: lo que escape aquí no puede dejar la cola sin drenar para siempre.
            IosSyncOutcome.Retry
        }
    }

    /**
     * Drena la cola entera, no una página: el mismo `SyncDrainer` de Android con los mismos tamaños
     * de lote y el mismo estacionamiento a los 10 intentos.
     */
    private suspend fun push(): Boolean {
        val dao = database.syncOperationDao()
        dao.reopenSettledPermissionDenials(MAX_ATTEMPTS)
        val coordinator = SyncCoordinator(gateway)
        return SyncDrainer(
            batchSize = BATCH_SIZE,
            maxBatches = MAX_BATCHES,
            pending = { limit ->
                dao.pending(limit).map {
                    PendingSyncOperation(it.id, it.businessId, it.entityType, it.entityId, it.payload)
                }
            },
            process = { coordinator.process(it) },
            onCompleted = { dao.remove(it) },
            onPermanentFailure = { id, error -> dao.markFailure(id, "PERMANENT:$error") },
            onTransientFailure = { dao.markFailure(it, "Retry requested") },
            parkExhausted = { dao.parkExhausted(MAX_ATTEMPTS) },
        ).drain()
    }

    private suspend fun pull(): Boolean {
        // Del dueño que tiene la sesión, como el resto de la app: "el primer negocio de la base" es
        // justo la forma del bug que dejó varado el teléfono reinstalado.
        val userId = currentUserId() ?: return false
        val business = database.businessDao().firstOwnedBy(userId) ?: return false
        val since = database.syncStateDao().lastPulledAt(business.id) ?: 0L
        return when (val result = gateway.download(business.id, since)) {
            is SyncDownloadResult.Success -> {
                // El watermark lo avanza RemoteMerger: máximo visto menos 5 minutos.
                RemoteMerger(database).apply(business.id, result.documents)
                false
            }

            is SyncDownloadResult.Failure -> SyncFailurePolicy.shouldRetry(result.failure)
        }
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val MAX_ATTEMPTS = 10
        const val MAX_BATCHES = 200
    }
}

/**
 * Lo que Swift llama.
 *
 * `syncNow` de Kotlin es `suspend` y Swift no puede esperarlo directo, así que aquí se lanza en un
 * scope de `Dispatchers.Default` y el resultado vuelve como `Boolean` (`true` = la corrida terminó
 * sin pedir reintento). El scope es de la aplicación: no hay un `viewModelScope` en este lado.
 */
object IosSyncBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = IosSyncEngine()

    fun syncNow(completion: (Boolean) -> Unit) {
        scope.launch {
            val outcome = engine.syncNow()
            completion(outcome == IosSyncOutcome.Success)
        }
    }
}
