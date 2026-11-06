package com.cuentivo.app.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * La fusión remota contra Room de verdad: base en memoria con `BundledSQLiteDriver` y el
 * `RemoteMerger` de producción. No hay ninguna copia de la regla aquí.
 *
 * Los documentos se arman con `NSNumber` como los entrega Firestore y pasan por
 * `normalizeFirestoreValue`, así que lo que se prueba es la cadena completa de la frontera de dinero:
 * SDK nativo → normalizador → lector de centavos → fila de Room.
 *
 * Corre con `:shared:iosSimulatorArm64Test` en el runner macOS.
 */
class RemoteMergerIosTest {

    private fun database(): CuentivoDatabase =
        Room.inMemoryDatabaseBuilder<CuentivoDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()

    private fun movementDocument(
        id: String = "mv-1",
        amountCents: NSNumber,
        businessId: String = BUSINESS,
    ): RemoteDocument = RemoteDocument(
        entityType = "MOVEMENT",
        entityId = id,
        watermarkEpochMillis = 1_000L,
        fields = normalizedFields(
            "businessId" to businessId,
            "customerId" to CUSTOMER,
            "type" to "CHARGE",
            "amountCents" to amountCents,
            "direction" to "DEBIT",
            "occurredAtEpochMillis" to 1_000L,
            "createdByUserId" to "owner",
        ),
    )

    private fun customerDocument(
        id: String = CUSTOMER,
        name: String,
        updatedAtEpochMillis: Long,
    ): RemoteDocument = RemoteDocument(
        entityType = "CUSTOMER",
        entityId = id,
        watermarkEpochMillis = updatedAtEpochMillis,
        fields = normalizedFields(
            "businessId" to BUSINESS,
            "name" to name,
            "normalizedName" to name.lowercase(),
            "isActive" to true,
            "createdAtEpochMillis" to 1_000L,
            "updatedAtEpochMillis" to updatedAtEpochMillis,
        ),
    )

    private fun saleDocument(
        id: String = "sale-1",
        totalCents: Long,
        isCanceled: Boolean = false,
        updatedAtEpochMillis: Long = 2_000L,
    ): RemoteDocument = RemoteDocument(
        entityType = "SALE",
        entityId = id,
        watermarkEpochMillis = updatedAtEpochMillis,
        fields = normalizedFields(
            "businessId" to BUSINESS,
            "customerId" to CUSTOMER,
            "sellerUserId" to "owner",
            "saleNumber" to "S-1",
            "saleDateEpochDay" to 20_000L,
            "subtotalCents" to totalCents,
            "discountCents" to 0L,
            "totalCents" to totalCents,
            "isCanceled" to isCanceled,
            "createdAtEpochMillis" to 1_000L,
            "updatedAtEpochMillis" to updatedAtEpochMillis,
        ),
    )

    /** Los valores pasan por el normalizador real, como si vinieran del SDK. */
    private fun normalizedFields(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        pairs.associate { (key, value) -> key to normalizeFirestoreValue(value) }

    // ------------------------------------------------------------------ centavos

    @Test
    fun an_exact_long_amount_is_stored_exactly() = runBlocking {
        val database = database()
        try {
            val report = RemoteMerger(database).apply(
                BUSINESS,
                listOf(movementDocument(amountCents = NSNumber(longLong = 12345L))),
            )

            assertEquals(1, report.adopted)
            val stored = database.financialDao().movementForId("mv-1")
            assertNotNull(stored)
            assertEquals(12345L, stored.amountCents)
        } finally {
            database.close()
        }
    }

    @Test
    fun a_decimal_amount_is_rejected_without_writing_anything_and_the_batch_continues() = runBlocking {
        val database = database()
        try {
            val batch = listOf(
                movementDocument(id = "mv-decimal", amountCents = NSNumber(double = 10.5)),
                movementDocument(id = "mv-whole-double", amountCents = NSNumber(double = 10.0)),
                movementDocument(id = "mv-good", amountCents = NSNumber(longLong = 999L)),
            )

            val report = RemoteMerger(database).apply(BUSINESS, batch)

            // Los dos decimales se rechazan; el resto del lote sigue y sí se escribe.
            assertEquals(2, report.rejected)
            assertEquals(1, report.adopted)
            assertNull(database.financialDao().movementForId("mv-decimal"))
            assertNull(database.financialDao().movementForId("mv-whole-double"))
            assertEquals(999L, database.financialDao().movementForId("mv-good")?.amountCents)
        } finally {
            database.close()
        }
    }

    // ------------------------------------------------------------------ financiero y cancelación

    @Test
    fun an_existing_sale_is_never_rewritten_by_a_remote_copy_with_another_total() = runBlocking {
        val database = database()
        try {
            val merger = RemoteMerger(database)
            merger.apply(BUSINESS, listOf(saleDocument(totalCents = 1_500L)))
            val before = database.financialDao().saleForId("sale-1")
            assertNotNull(before)

            // Copia remota más nueva y con otro total: no se reescribe.
            merger.apply(
                BUSINESS,
                listOf(saleDocument(totalCents = 9_900L, updatedAtEpochMillis = 5_000L)),
            )

            val after = database.financialDao().saleForId("sale-1")
            assertEquals(1_500L, after?.totalCents)
            assertEquals(before.updatedAtEpochMillis, after?.updatedAtEpochMillis)
        } finally {
            database.close()
        }
    }

    @Test
    fun a_remote_cancellation_only_seals_the_flag() = runBlocking {
        val database = database()
        try {
            val merger = RemoteMerger(database)
            merger.apply(BUSINESS, listOf(saleDocument(totalCents = 1_500L)))
            val before = database.financialDao().saleForId("sale-1")
            assertNotNull(before)
            assertEquals(false, before.isCanceled)

            val report = merger.apply(
                BUSINESS,
                listOf(saleDocument(totalCents = 1_500L, isCanceled = true, updatedAtEpochMillis = 9_000L)),
            )

            assertEquals(1, report.cancellationsApplied)
            val after = database.financialDao().saleForId("sale-1")
            assertEquals(true, after?.isCanceled)
            // La fila en sí no se toca: mismo total y misma marca de tiempo.
            assertEquals(1_500L, after?.totalCents)
            assertEquals(before.updatedAtEpochMillis, after?.updatedAtEpochMillis)
        } finally {
            database.close()
        }
    }

    // ------------------------------------------------------------------ descriptivos

    @Test
    fun a_newer_remote_customer_overwrites_and_a_tie_keeps_the_local_copy() = runBlocking {
        val database = database()
        try {
            val merger = RemoteMerger(database)
            merger.apply(BUSINESS, listOf(customerDocument(name = "Rosa", updatedAtEpochMillis = 2_000L)))

            // Más nuevo: sobrescribe.
            merger.apply(BUSINESS, listOf(customerDocument(name = "Rosa Lopez", updatedAtEpochMillis = 3_000L)))
            assertEquals("Rosa Lopez", database.customerDao().byId(CUSTOMER)?.name)

            // Empate con contenido distinto: gana el local.
            merger.apply(BUSINESS, listOf(customerDocument(name = "Otra Cosa", updatedAtEpochMillis = 3_000L)))
            assertEquals("Rosa Lopez", database.customerDao().byId(CUSTOMER)?.name)
        } finally {
            database.close()
        }
    }

    // ------------------------------------------------------------------ idempotencia

    @Test
    fun replaying_the_same_batch_does_not_duplicate_rows() = runBlocking {
        val database = database()
        try {
            val merger = RemoteMerger(database)
            val batch = listOf(
                customerDocument(name = "Rosa", updatedAtEpochMillis = 2_000L),
                saleDocument(totalCents = 1_500L),
                movementDocument(amountCents = NSNumber(longLong = 12345L)),
            )

            merger.apply(BUSINESS, batch)
            merger.apply(BUSINESS, batch)

            assertEquals("Rosa", database.customerDao().byId(CUSTOMER)?.name)
            assertEquals(1_500L, database.financialDao().saleForId("sale-1")?.totalCents)
            assertEquals(12345L, database.financialDao().movementForId("mv-1")?.amountCents)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val BUSINESS = "biz-1"
        const val CUSTOMER = "cust-1"
    }
}
