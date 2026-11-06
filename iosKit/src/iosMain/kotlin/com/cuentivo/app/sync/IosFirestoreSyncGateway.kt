@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.cuentivo.app.sync

import com.cuentivo.app.domain.BusinessClaimPolicy
import com.cuentivo.app.domain.BusinessName
import com.cuentivo.app.domain.DeletionRequestPolicy
import com.cuentivo.app.domain.RemoteBusiness
import cocoapods.FirebaseFirestoreInternal.FIRDocumentReference
import cocoapods.FirebaseFirestoreInternal.FIRDocumentSnapshot
import cocoapods.FirebaseFirestoreInternal.FIRFilter
import cocoapods.FirebaseFirestoreInternal.FIRFirestore
import cocoapods.FirebaseFirestoreInternal.FIRFirestoreSource
import cocoapods.FirebaseFirestoreInternal.FIRQuery
import cocoapods.FirebaseFirestoreInternal.FIRQuerySnapshot
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
 * El gateway de Firestore de iOS: traducción de `FirestoreSyncGateway` de Android.
 *
 * Habla con el SDK nativo por el `.ios` de GitLive (`FIRFirestore`) y no por la API de objetos de
 * GitLive, para que el JSON del payload y los valores leídos pasen por las mismas dos fronteras que
 * en Android — [parseJsonToFirestoreMap] al subir y [normalizeFirestoreValue] al bajar — y ninguna de
 * las dos redondee un centavo.
 *
 * **Bloqueante por contrato.** Las tres interfaces son síncronas, así que cada llamada espera el
 * callback con un semáforo y 30 s de timeout. Por eso **no se puede llamar desde el hilo principal**:
 * si se hace, devuelve un fallo en vez de congelar la app (`NSThread.isMainThread`). La llamada a
 * Firestore se despacha a una cola global para que el callback no aterrice en la principal.
 *
 * Todo lee con `Source.SERVER` (`FIRFirestoreSource.FIRFirestoreSourceServer`), igual que Android: la
 * caché responde *sin error* cuando no hay red, y en un teléfono recién instalado eso responde "no hay
 * nada", que es exactamente la confusión que la descarga no se puede permitir.
 *
 * Los nombres nativos salen de GitLive v2.8.0 (`firebase-firestore/src/appleMain/...`): los wrappers
 * usan `collectionWithPath`, `documentWithPath`, `getDocumentWithSource`, `getDocumentsWithSource`,
 * `setData(data, merge, completion)`, `queryWhereFilter(FIRFilter...)`, `queryOrderedByField`,
 * `queryLimitedTo`, `documentID`, `exists` y `documents`.
 */
class IosFirestoreSyncGateway(
    private val firestore: FirebaseFirestore = Firebase.firestore,
) : SyncRemoteGateway, SyncRemotePullGateway, SyncRemoteBusinessLookup {

    private val db: FIRFirestore get() = firestore.ios

    // ---------------------------------------------------------------- SyncRemoteBusinessLookup

    override fun findBusinessOwnedBy(userId: String): BusinessLookupResult {
        val documents = try {
            readDocuments(
                db.collectionWithPath("businesses")
                    .queryWhereFilter(FIRFilter.filterWhereField("ownerUserId", isEqualTo = userId)),
            )
        } catch (error: Throwable) {
            return BusinessLookupResult.Failure(classify(error))
        }
        return buildLookup(documents)
    }

    private fun buildLookup(documents: List<FIRDocumentSnapshot>): BusinessLookupResult {
        val byId = documents.associateBy { it.documentID }
        val owned = byId.values.map { snapshot ->
            val data = normalizedData(snapshot)
            RemoteBusiness(snapshot.documentID, data["createdAtEpochMillis"] as? Long ?: 0L)
        }
        // El más antiguo, no el más nuevo: un duplicado es por definición el que se creó después.
        val chosen = BusinessClaimPolicy.preferred(owned) ?: return BusinessLookupResult.None
        val data = normalizedData(byId.getValue(chosen.businessId))
        return BusinessLookupResult.Found(
            businessId = chosen.businessId,
            name = data["name"] as? String ?: BusinessName.DEFAULT,
            currency = data["currency"] as? String ?: "MXN",
            timezone = data["timezone"] as? String ?: "America/Mexico_City",
            createdAtEpochMillis = chosen.createdAtEpochMillis,
            updatedAtEpochMillis = data["updatedAtEpochMillis"] as? Long ?: 0L,
        )
    }

    // ---------------------------------------------------------------- SyncRemoteGateway

    override fun upload(operation: PendingSyncOperation): SyncUploadResult {
        // Igual que Android: la ruta se resuelve ANTES del bloque de red, porque un tipo no soportado
        // o un payload sin el id del padre no va a arreglarse por reintentarlo.
        val path = try {
            FirestorePaths.pathFor(
                entityType = operation.entityType,
                businessId = operation.businessId,
                entityId = operation.entityId,
                parentId = parentIdOf(operation.payload),
            )
        } catch (error: IllegalArgumentException) {
            return SyncUploadResult.Failure(SyncFailure.Permanent(error.message ?: "Unsupported sync operation"))
        }

        // Igual que Android: el payload se parsea DENTRO del try, así que un JSON ilegible cae en la
        // misma clasificación que allá (transitorio) y no se estaciona con un criterio distinto.
        return try {
            val payload = parseJsonToFirestoreMap(operation.payload)
            setDocument(document = db.documentWithPath(path), payload = payload, merge = false)
            SyncUploadResult.Success
        } catch (error: Throwable) {
            val failure = classify(error)
            val payload = runCatching { parseJsonToFirestoreMap(operation.payload) }.getOrNull()
            val worthChecking = payload != null && SyncRemoteImmutabilityRules.shouldCheckWhetherAlreadyPublished(
                entityType = operation.entityType,
                role = payload["role"] as? String,
                isPermissionDenied = isPermissionDenied(error),
            )
            if (worthChecking && payload != null) {
                resolveAgainstPublishedCopy(path, payload, failure)
            } else {
                SyncUploadResult.Failure(failure)
            }
        }
    }

    /**
     * Qué significa una denegación, una vez preguntado al servidor qué hay publicado.
     *
     * Tres respuestas distintas: el mismo documento ya está (la fila terminó), otro documento bajo el
     * mismo id (nunca se puede reescribir: se estaciona diciendo eso), o nada (la denegación era real
     * y se queda). Siempre del servidor: en el teléfono que mostró el problema, la caché era justo lo
     * que no se podía creer.
     */
    private fun resolveAgainstPublishedCopy(
        path: String,
        payload: Map<Any?, Any?>,
        denial: SyncFailure,
    ): SyncUploadResult {
        val remote = try {
            readDocument(db.documentWithPath(path))
        } catch (error: Throwable) {
            // No se pudo preguntar: eso no es "nunca va a funcionar".
            return SyncUploadResult.Failure(
                SyncFailure.Transient(error.message ?: "could not read the published copy"),
            )
        } ?: return SyncUploadResult.Failure(denial)

        return if (SyncRemoteImmutabilityRules.isAlreadyTheSameDocument(payload, remote)) {
            SyncUploadResult.Success
        } else {
            SyncUploadResult.Failure(SyncFailure.Permanent("already published with different content at $path"))
        }
    }

    // ---------------------------------------------------------------- SyncRemotePullGateway

    override fun download(businessId: String, sinceEpochMillis: Long): SyncDownloadResult = try {
        val root = db.documentWithPath("businesses/$businessId")
        val documents = mutableListOf<RemoteDocument>()

        readDocument(root)?.let { data ->
            documents += RemoteDocument(
                entityType = "BUSINESS",
                entityId = businessId,
                watermarkEpochMillis = data["updatedAtEpochMillis"] as? Long ?: 0L,
                fields = data,
            )
        }

        readDocuments(root.collectionWithPath("members")).forEach { snapshot ->
            documents += RemoteDocument("MEMBER", snapshot.documentID, 0L, normalizedData(snapshot))
        }

        PULL_SPECS.forEach { spec -> documents += pull(root, spec, sinceEpochMillis) }

        // Los ítems y las asignaciones viven en subcolecciones y no traen fecha propia: se buscan por
        // padre recién descargado, lo que evita índices de grupo de colecciones.
        documents.filter { it.entityType == "SALE" }.forEach { sale ->
            documents += children(
                parent = root.collectionWithPath("sales").documentWithPath(sale.entityId),
                collection = "items",
                entityType = "SALE_ITEM",
                watermark = sale.watermarkEpochMillis,
            )
        }
        documents.filter { it.entityType == "PAYMENT" }.forEach { payment ->
            documents += children(
                parent = root.collectionWithPath("payments").documentWithPath(payment.entityId),
                collection = "allocations",
                entityType = "PAYMENT_ALLOCATION",
                watermark = payment.watermarkEpochMillis,
            )
        }

        SyncDownloadResult.Success(documents)
    } catch (error: Throwable) {
        SyncDownloadResult.Failure(classify(error))
    }

    private fun pull(root: FIRDocumentReference, spec: PullSpec, sinceEpochMillis: Long): List<RemoteDocument> {
        val query: FIRQuery = root.collectionWithPath(spec.collection)
            .queryWhereFilter(FIRFilter.filterWhereField(spec.watermarkField, isGreaterThan = sinceEpochMillis))
            .queryOrderedByField(spec.watermarkField, descending = false)
            .queryLimitedTo(SYNC_PAGE_SIZE)
        return readDocuments(query).mapNotNull { snapshot ->
            val data = normalizedData(snapshot)
            val watermark = data[spec.watermarkField] as? Long ?: return@mapNotNull null
            RemoteDocument(spec.entityType, snapshot.documentID, watermark, data)
        }
    }

    private fun children(
        parent: FIRDocumentReference,
        collection: String,
        entityType: String,
        watermark: Long,
    ): List<RemoteDocument> =
        readDocuments(parent.collectionWithPath(collection)).map { snapshot ->
            RemoteDocument(entityType, snapshot.documentID, watermark, normalizedData(snapshot))
        }

    // ---------------------------------------------------------------- borrado

    /**
     * Marca el negocio como "el dueño pidió borrarlo" y espera al servidor.
     *
     * Igual que Android: no va por la cola, porque el paso siguiente de ese flujo es borrar la cuenta
     * de Firebase Auth y ya no quedaría credencial para el job. La decisión es de
     * [DeletionRequestPolicy], así que preguntar dos veces es seguro.
     */
    fun requestBusinessDeletion(businessId: String, nowEpochMillis: Long): Result<Unit> = try {
        val document = db.documentWithPath("businesses/$businessId")
        val snapshot = readDocumentSnapshot(document)
        when (
            DeletionRequestPolicy.decide(
                exists = snapshot.exists,
                deletionRequestedAtEpochMillis = snapshot.data?.get("deletionRequestedAtEpochMillis") as? Long,
            )
        ) {
            DeletionRequestPolicy.Outcome.AlreadyGone -> Unit
            is DeletionRequestPolicy.Outcome.AlreadyRequested -> Unit
            DeletionRequestPolicy.Outcome.NeedsWrite -> setDocument(
                document = document,
                payload = mapOf("deletionRequestedAtEpochMillis" to nowEpochMillis),
                merge = true,
            )
        }
        Result.success(Unit)
    } catch (error: Throwable) {
        Result.failure(error)
    }

    // ---------------------------------------------------------------- primitivas bloqueantes

    /** El documento del servidor, normalizado, o `null` si no existe. */
    private fun readDocument(document: FIRDocumentReference): Map<String, Any?>? {
        val snapshot = readDocumentSnapshot(document)
        // `exists` es una propiedad en el binding de Foundation, no una función.
        if (!snapshot.exists) return null
        return snapshot.data
    }

    private fun readDocumentSnapshot(document: FIRDocumentReference): DocumentRead {
        val outcome = blocking<FIRDocumentSnapshot?> { done ->
            document.getDocumentWithSource(FIRFirestoreSource.FIRFirestoreSourceServer) {
                    snapshot: FIRDocumentSnapshot?,
                    error: NSError?,
                ->
                if (error != null) done(Failure(error)) else done(Success(snapshot))
            }
        }
        return when (outcome) {
            is Success<*> -> {
                val snapshot = outcome.value as? FIRDocumentSnapshot
                DocumentRead(exists = snapshot?.exists == true, data = snapshot?.let { normalizedData(it) })
            }
            is Failure -> throw FirestoreSyncException(failureMessage(outcome.error), outcome.error)
            TimedOut -> throw FirestoreSyncException(TIMEOUT_MESSAGE)
        }
    }

    private fun readDocuments(query: FIRQuery): List<FIRDocumentSnapshot> {
        val outcome = blocking<FIRQuerySnapshot?> { done ->
            query.getDocumentsWithSource(FIRFirestoreSource.FIRFirestoreSourceServer) {
                    snapshot: FIRQuerySnapshot?,
                    error: NSError?,
                ->
                if (error != null) done(Failure(error)) else done(Success(snapshot))
            }
        }
        return when (outcome) {
            is Success<*> -> (outcome.value as? FIRQuerySnapshot)
                ?.documents
                ?.filterIsInstance<FIRDocumentSnapshot>()
                .orEmpty()
            is Failure -> throw FirestoreSyncException(failureMessage(outcome.error), outcome.error)
            TimedOut -> throw FirestoreSyncException(TIMEOUT_MESSAGE)
        }
    }

    private fun setDocument(document: FIRDocumentReference, payload: Map<Any?, Any?>, merge: Boolean) {
        val outcome = blocking<Unit> { done ->
            document.setData(payload, merge) { error: NSError? ->
                if (error != null) done(Failure(error)) else done(Success(Unit))
            }
        }
        when (outcome) {
            is Success<*> -> Unit
            is Failure -> throw FirestoreSyncException(failureMessage(outcome.error), outcome.error)
            TimedOut -> throw FirestoreSyncException(TIMEOUT_MESSAGE)
        }
    }

    private fun normalizedData(snapshot: FIRDocumentSnapshot): Map<String, Any?> {
        val raw = snapshot.data() ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>(raw.size)
        for ((key, value) in raw) {
            val name = normalizeFirestoreValue(key) as? String ?: key.toString()
            out[name] = normalizeFirestoreValue(value)
        }
        return out
    }

    // ---------------------------------------------------------------- utilidades

    private fun parentIdOf(payload: String): String? = try {
        val map = parseJsonToFirestoreMap(payload)
        map["saleId"] as? String ?: map["paymentId"] as? String
    } catch (error: Throwable) {
        null
    }

    private fun classify(error: Throwable): SyncFailure =
        classifyFirestoreFailure(firestoreCodeName(error), error.message ?: "Firestore call failed")

    private fun isPermissionDenied(error: Throwable): Boolean = firestoreCodeName(error) == "PERMISSION_DENIED"

    private fun failureMessage(error: NSError): String =
        error.localizedDescription.ifEmpty { "Firestore call failed" }

    /**
     * El nombre del código de Firestore, para usar la **misma** tabla de clasificación que Android.
     * Los números son los de `FIRFirestoreErrorCode`, que siguen los códigos canónicos de gRPC.
     */
    private fun firestoreCodeName(error: Throwable): String? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            val nsError = (current as? FirestoreSyncException)?.error
            if (nsError != null && nsError.domain == FIRESTORE_ERROR_DOMAIN) {
                return when (nsError.code) {
                    3L -> "INVALID_ARGUMENT"
                    5L -> "NOT_FOUND"
                    6L -> "ALREADY_EXISTS"
                    7L -> "PERMISSION_DENIED"
                    9L -> "FAILED_PRECONDITION"
                    11L -> "OUT_OF_RANGE"
                    12L -> "UNIMPLEMENTED"
                    15L -> "DATA_LOSS"
                    else -> null
                }
            }
            current = current.cause
            depth++
        }
        return null
    }

    /**
     * Puente bloqueante: despacha a una cola global, espera el callback con semáforo y devuelve
     * [TimedOut] si se agota el tiempo. Nunca se llama desde el hilo principal.
     */
    private fun <T> blocking(block: ((Outcome<T>) -> Unit) -> Unit): Outcome<T> {
        if (NSThread.isMainThread) {
            throw FirestoreSyncException(MAIN_THREAD_MESSAGE)
        }
        val gate = Gate<T>()
        // Prioridad por defecto (0) y cola concurrente: bloquear un worker no frena el callback.
        dispatch_async(dispatch_get_global_queue(0L, 0uL)) {
            block { outcome: Outcome<T> -> gate.complete(outcome) }
        }
        return gate.await()
    }

    private class Gate<T> {
        private val semaphore = dispatch_semaphore_create(0)
        @Volatile private var outcome: Outcome<T>? = null

        fun complete(value: Outcome<T>) {
            outcome = value
            dispatch_semaphore_signal(semaphore)
        }

        fun await(): Outcome<T> {
            val waited = dispatch_semaphore_wait(
                semaphore,
                dispatch_time(0uL, TIMEOUT_MILLIS * 1_000_000L),
            )
            if (waited != 0L) return TimedOut
            return outcome ?: TimedOut
        }
    }

    private class DocumentRead(val exists: Boolean, val data: Map<String, Any?>?)

    private companion object {
        const val FIRESTORE_ERROR_DOMAIN = "FIRFirestoreErrorDomain"
        const val TIMEOUT_MILLIS = 30_000L
        const val TIMEOUT_MESSAGE = "Firestore did not answer within 30000 ms"
        const val MAIN_THREAD_MESSAGE = "Firestore sync cannot be called from the main thread"
    }
}

/** Un fallo del gateway que conserva el `NSError` para poder clasificarlo arriba. */
@OptIn(ExperimentalForeignApi::class)
private class FirestoreSyncException(
    override val message: String,
    val error: NSError? = null,
) : Exception(message)

/** Resultado de un callback de Firestore, para no mezclar el valor con el error. */
private sealed interface Outcome<out T>

private class Success<T>(val value: T) : Outcome<T>

private class Failure(val error: NSError) : Outcome<Nothing>

private data object TimedOut : Outcome<Nothing>
