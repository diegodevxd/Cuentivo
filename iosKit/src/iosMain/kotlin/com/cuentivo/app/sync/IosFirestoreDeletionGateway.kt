package com.cuentivo.app.sync

import cocoapods.FirebaseFirestoreInternal.FIRDocumentReference
import cocoapods.FirebaseFirestoreInternal.FIRFirestore
import cocoapods.FirebaseFirestoreInternal.FIRFirestoreSource
import com.cuentivo.app.domain.BusinessDeletionPlanner
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.firestore
import dev.gitlive.firebase.firestore.ios
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * Borra un negocio de Firestore, raíz y ramas, una vez que el dueño lo pidió. Traducción de
 * `FirestoreDeletionGateway`.
 *
 * Lo único que hace legal el borrado es la bandera de un solo sentido que puso
 * `IosFirestoreSyncGateway.requestBusinessDeletion`: `firestore.rules` cierra cada `allow delete`
 * detrás de `canDeleteForDeletion(businessId)`. Nada más puede borrar.
 *
 * El orden lo decide [BusinessDeletionPlanner], no esta clase: hijos antes que padres, lotes de como
 * mucho 500 (el techo de `WriteBatch`) y el documento del negocio solo en el último. Aquí sólo se
 * enumera lo que el planificador necesita y se ejecutan sus lotes, esperando cada uno, para que un
 * fallo corte en un límite limpio y un reintento vuelva a listar sólo lo que queda.
 *
 * Toda lectura es `Source.SERVER`: este flujo tiene que ver lo que de verdad hay en la nube, no lo que
 * una caché vieja recuerda. Un negocio que ya no está significa que el borrado ya se hizo.
 */
class IosFirestoreDeletionGateway(
    private val firestore: FirebaseFirestore = Firebase.firestore,
) {
    private val db: FIRFirestore get() = firestore.ios

    fun deleteBusiness(businessId: String): Result<Unit> = try {
        if (readDocument("businesses/$businessId") == null) {
            // Ya borrado (un reintento después de que el lote del negocio se confirmara y el
            // teléfono muriera). No queda nada por hacer.
            return Result.success(Unit)
        }
        val inventory = fetchInventory(businessId)
        BusinessDeletionPlanner.plan(inventory).forEach { batch ->
            val writeBatch = db.batch()
            batch.documentPaths.forEach { path ->
                writeBatch.deleteDocument(db.documentWithPath(path))
            }
            commit(writeBatch)
        }
        Result.success(Unit)
    } catch (error: Throwable) {
        // Nunca se relanza al flujo de borrado: un borrado remoto a medias no puede llevarse el
        // teléfono por delante. Sin ids ni PII, sólo la señal.
        Result.failure(error)
    }

    private fun fetchInventory(businessId: String): BusinessDeletionPlanner.Inventory {
        fun ids(collection: String): List<String> =
            readCollection("businesses/$businessId/$collection")

        val saleIds = ids("sales")
        val paymentIds = ids("payments")
        return BusinessDeletionPlanner.Inventory(
            businessId = businessId,
            memberIds = ids("members"),
            customerIds = ids("customers"),
            productIds = ids("products"),
            saleIds = saleIds,
            saleItemIds = saleIds.associateWith { saleId -> ids("sales/$saleId/items") },
            paymentIds = paymentIds,
            allocationIds = paymentIds.associateWith { paymentId -> ids("payments/$paymentId/allocations") },
            movementIds = ids("movements"),
            auditLogIds = ids("auditLogs"),
        )
    }

    // ---------------------------------------------------------------- primitivas

    private fun readDocument(path: String): Map<String, Any?>? {
        val document: FIRDocumentReference = db.documentWithPath(path)
        var result: Map<String, Any?>? = null
        var exists = false
        val ok = blocking { done ->
            document.getDocumentWithSource(FIRFirestoreSource.FIRFirestoreSourceServer) {
                    snapshot: cocoapods.FirebaseFirestoreInternal.FIRDocumentSnapshot?,
                    error: NSError?,
                ->
                if (error != null) return@getDocumentWithSource done(false)
                exists = snapshot?.exists == true
                @Suppress("UNCHECKED_CAST")
                result = snapshot?.data() as? Map<String, Any?>
                done(true)
            }
        }
        if (!ok || !exists) return null
        return result
    }

    private fun readCollection(path: String): List<String> {
        val documents = mutableListOf<String>()
        val ok = blocking { done ->
            db.collectionWithPath(path).getDocumentsWithSource(FIRFirestoreSource.FIRFirestoreSourceServer) {
                    snapshot: cocoapods.FirebaseFirestoreInternal.FIRQuerySnapshot?,
                    error: NSError?,
                ->
                if (error != null) return@getDocumentsWithSource done(false)
                snapshot?.documents
                    ?.filterIsInstance<cocoapods.FirebaseFirestoreInternal.FIRDocumentSnapshot>()
                    ?.forEach { documents += it.documentID }
                done(true)
            }
        }
        check(ok) { "Could not read $path from the server" }
        return documents
    }

    private fun commit(batch: cocoapods.FirebaseFirestoreInternal.FIRWriteBatch) {
        val ok = blocking { done ->
            batch.commitWithCompletion { error: NSError? -> done(error == null) }
        }
        check(ok) { "A deletion batch was rejected" }
    }

    /**
     * Espera el callback con semáforo. Mismo contrato que el gateway de sync: nunca en el hilo
     * principal, y la llamada se despacha a una cola global para que el callback no aterrice en él.
     */
    private fun blocking(block: ((Boolean) -> Unit) -> Unit): Boolean {
        check(!NSThread.isMainThread) { "Firestore deletion cannot run on the main thread" }
        val semaphore = dispatch_semaphore_create(0)
        @Volatile var finished = false
        @Volatile var success = false
        dispatch_async(dispatch_get_global_queue(0L, 0uL)) {
            block { ok ->
                success = ok
                finished = true
                dispatch_semaphore_signal(semaphore)
            }
        }
        val waited = dispatch_semaphore_wait(
            semaphore,
            dispatch_time(0uL, TIMEOUT_MILLIS * 1_000_000L),
        )
        check(waited == 0L && finished) { "Firestore deletion timed out" }
        return success
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
