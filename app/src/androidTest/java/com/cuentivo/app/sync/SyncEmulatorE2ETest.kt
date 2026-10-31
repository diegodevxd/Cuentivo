package com.cuentivo.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.cuentivo.app.BuildConfig
import com.cuentivo.app.billing.EntitlementStore
import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.BusinessMemberEntity
import com.cuentivo.app.data.local.CUENTIVO_MIGRATIONS
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SyncOperationEntity
import com.cuentivo.app.domain.SubscriptionTier
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The sync path, end to end, against the Firebase Emulator Suite.
 *
 * Everything else about sync has been tested with fakes. This is the only place that runs the real
 * [SyncWorker] — its entitlement gate, its own Room instance, the outbox, [SyncDrainer], the
 * Firestore gateway, the security rules, queue removal, and the pull through [RemoteMerger] — in one
 * piece. Faking any of that is how a system passes every test and still has never once written a
 * document.
 *
 * Every read that proves a remote fact uses [Source.SERVER]. Firestore's local cache will happily
 * hand back a document the backend never accepted, which would turn these assertions into false
 * positives.
 */
@RunWith(AndroidJUnit4::class)
class SyncEmulatorE2ETest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var entitlements: EntitlementStore

    /** Unique per run, so nothing depends on emulator state left behind by an earlier one. */
    private val runId = UUID.randomUUID().toString().take(8)

    @Before
    fun setUp() {
        val e2eWasExplicitlyRequested =
            InstrumentationRegistry.getArguments().getString(REQUIRE_EMULATORS_ARGUMENT) == "true"
        if (!BuildConfig.USE_FIREBASE_EMULATORS) {
            // A normal connectedDebugAndroidTest run has no Firebase processes and should simply
            // skip this dedicated suite. The reproducible E2E command sets the argument below; if
            // somebody forgets the build flag there, fail loudly instead of touching production.
            if (e2eWasExplicitlyRequested) {
                fail(
                    "La suite E2E fue solicitada sin -Pcuentivo.useEmulators=true; se aborta antes " +
                        "de tocar Firebase de producción."
                )
            }
            assumeTrue("E2E sólo se ejecuta con Firebase Emulator Suite", false)
        }
        FirebaseEmulators.connectIfEnabled()
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        entitlements = EntitlementStore(context)
        resetLocalState()
    }

    @After
    fun tearDown() {
        resetLocalState()
    }

    /** Local state only. The emulators are wiped by emulators:exec when the process ends. */
    private fun resetLocalState() {
        // JUnit still invokes @After when @Before skips this class in the normal instrumentation
        // suite, before these emulator-only collaborators have been initialized.
        if (::auth.isInitialized) auth.signOut()
        if (::entitlements.isInitialized) entitlements.forget()
        context.deleteDatabase("cuentivo.db")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun signUpUnique(label: String): String {
        val email = "e2e-$runId-$label@cuentivo.test"
        val result = Tasks.await(auth.createUserWithEmailAndPassword(email, "Password123!"))
        val uid = requireNotNull(result.user?.uid) { "El emulador de Auth no devolvió uid" }
        markEmailVerified(uid)
        return uid
    }

    /**
     * Does what clicking the link in a real inbox would do.
     *
     * `firestore.rules` refuses anyone whose `email_verified` claim is false, and the Auth emulator
     * creates accounts unverified. The flag is flipped through the emulator's admin endpoint and
     * then the ID token is force-refreshed, because the claim Firestore reads is the one baked into
     * the token, not whatever the client last decided to believe.
     */
    private fun signUpUniqueUnverified(label: String): String {
        val email = "e2e-$runId-$label@cuentivo.test"
        val result = Tasks.await(auth.createUserWithEmailAndPassword(email, "Password123!"))
        return requireNotNull(result.user?.uid) { "El emulador de Auth no devolvió uid" }
    }

    private fun markEmailVerified(uid: String) {
        val projectId = requireNotNull(FirebaseApp.getInstance().options.projectId)
        val connection = (
            URL("http://10.0.2.2:9099/identitytoolkit.googleapis.com/v1/projects/$projectId/accounts:update")
                .openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer owner")
            setRequestProperty("Content-Type", "application/json")
        }
        connection.use {
            it.outputStream.write("""{"localId":"$uid","emailVerified":true}""".toByteArray())
            check(it.responseCode in 200..299) { "El emulador de Auth rechazó la verificación: ${it.responseCode}" }
        }
        val user = requireNotNull(auth.currentUser)
        Tasks.await(user.reload())
        Tasks.await(user.getIdToken(true))
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    /**
     * Opens the very database [SyncWorker] will open, and closes it again.
     *
     * The Worker builds its own instance of "cuentivo.db"; leaving a second connection open while it
     * runs invites lock contention that has nothing to do with what is being tested.
     */
    private fun <T> withDatabase(block: (CuentivoDatabase) -> T): T {
        val database = Room.databaseBuilder(context, CuentivoDatabase::class.java, "cuentivo.db")
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        return runBlocking { worker.doWork() }
    }

    private fun serverDoc(path: String): DocumentSnapshot =
        Tasks.await(firestore.document(path).get(Source.SERVER))

    /**
     * Reads through the emulator's admin REST endpoint, bypassing security rules.
     *
     * A free account has not uploaded its owner membership, so an ordinary SDK read is correctly
     * denied by the rules whether the business is absent or belongs to someone else. Only the
     * emulator-only admin endpoint can distinguish "absent" from "present but forbidden" without
     * weakening production rules.
     */
    private fun adminDocumentExists(path: String): Boolean {
        val projectId = requireNotNull(FirebaseApp.getInstance().options.projectId)
        val connection = URL(
            "http://10.0.2.2:8080/v1/projects/$projectId/databases/(default)/documents/$path"
        ).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer owner")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> true
                HttpURLConnection.HTTP_NOT_FOUND -> false
                else -> error("Firestore Emulator admin GET devolvió HTTP $status para $path")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun operation(
        businessId: String,
        entityType: String,
        entityId: String,
        revision: Long,
        fields: Map<String, Any?>,
    ) = SyncOperationEntity(
        id = SyncOperationId.create(businessId, entityType, entityId, revision),
        businessId = businessId,
        entityType = entityType,
        entityId = entityId,
        operation = "UPSERT",
        payload = JSONObject(fields).toString(),
        createdAtEpochMillis = revision,
        attemptCount = 0,
        lastError = null,
    )

    // Payload shapes mirror MainActivity exactly. firestore.rules uses hasExactKeys, so a missing or
    // extra key is rejected outright — these maps are part of the contract, not decoration.

    private fun businessFields(name: String, ownerUserId: String, now: Long) = mapOf(
        "name" to name,
        "ownerUserId" to ownerUserId,
        "currency" to "MXN",
        "timezone" to "America/Mexico_City",
        "createdAtEpochMillis" to now,
        "updatedAtEpochMillis" to now,
    )

    private fun memberFields(businessId: String, userId: String, now: Long) = mapOf(
        "businessId" to businessId,
        "userId" to userId,
        "role" to "OWNER",
        "status" to "ACTIVE",
        "invitedAtEpochMillis" to null,
        "acceptedAtEpochMillis" to now,
    )

    private fun customerFields(businessId: String, name: String, now: Long) = mapOf(
        "businessId" to businessId,
        "name" to name,
        "normalizedName" to name.lowercase(),
        "phone" to null,
        "email" to null,
        "address" to null,
        "notes" to null,
        "creditLimitCents" to null,
        "isActive" to true,
        "createdAtEpochMillis" to now,
        "updatedAtEpochMillis" to now,
        "deletedAtEpochMillis" to null,
    )

    private fun saleFields(
        businessId: String,
        customerId: String,
        sellerUserId: String,
        saleNumber: String,
        now: Long,
        totalCents: Long,
    ) = mapOf(
        "businessId" to businessId,
        "customerId" to customerId,
        "sellerUserId" to sellerUserId,
        "saleNumber" to saleNumber,
        "saleDateEpochDay" to 20_000L,
        "dueDateEpochDay" to 20_030L,
        "subtotalCents" to totalCents,
        "discountCents" to 0L,
        "totalCents" to totalCents,
        "isCanceled" to false,
        "notes" to null,
        "createdAtEpochMillis" to now,
        "updatedAtEpochMillis" to now,
    )

    private fun movementFields(
        businessId: String,
        customerId: String,
        saleId: String?,
        movementType: String,
        amountCents: Long,
        direction: String,
        userId: String,
        now: Long,
    ) = mapOf(
        "businessId" to businessId,
        "customerId" to customerId,
        "saleId" to saleId,
        "paymentId" to null,
        "type" to movementType,
        "amountCents" to amountCents,
        "direction" to direction,
        "occurredAtEpochMillis" to now,
        "createdByUserId" to userId,
        "reversesMovementId" to null,
        "notes" to null,
    )

    private fun businessEntity(id: String, ownerUserId: String, now: Long) =
        BusinessEntity(id, "Negocio $runId", ownerUserId, "MXN", "America/Mexico_City", now, now)

    private fun customerEntity(id: String, businessId: String, name: String, now: Long) = CustomerEntity(
        id = id, businessId = businessId, name = name, normalizedName = name.lowercase(),
        phone = null, email = null, address = null, notes = null, creditLimitCents = null,
        isActive = true, createdAtEpochMillis = now, updatedAtEpochMillis = now,
        deletedAtEpochMillis = null,
    )

    /**
     * A business whose owner membership is also queued, which every other write depends on: the
     * rules gate each subcollection behind an ACTIVE member document.
     */
    private fun seedAuthorisedBusiness(uid: String, now: Long): String {
        val businessId = "business-$runId-${UUID.randomUUID().toString().take(6)}"
        withDatabase { database ->
            runBlocking {
                database.businessDao().insert(businessEntity(businessId, uid, now))
                database.businessDao().insertMember(
                    BusinessMemberEntity("$businessId:$uid", businessId, uid, "OWNER", "ACTIVE", null, now)
                )
                val dao = database.syncOperationDao()
                dao.enqueue(operation(businessId, "BUSINESS", businessId, now, businessFields("Negocio $runId", uid, now)))
                // +1 so the member lands after the business: the rules do getAfter() on the business
                // document, so publishing the membership first is rejected.
                dao.enqueue(operation(businessId, "MEMBER", uid, now + 1, memberFields(businessId, uid, now)))
            }
        }
        return businessId
    }

    private fun grantPro(uid: String) =
        entitlements.remember(uid, SubscriptionTier.PRO, System.currentTimeMillis())

    private fun grantFree(uid: String) =
        entitlements.remember(uid, SubscriptionTier.FREE, System.currentTimeMillis())

    /**
     * The whole point of requiring a verified address, proven against the real rules.
     *
     * Everything else is identical to the Pro upload that succeeds in `b_`: same business, same
     * plan, same queue. The only difference is that nobody ever opened the verification mail, and
     * the server refuses the lot. The queue survives, because a refused write is not a lost one.
     */
    @Test
    fun g_an_unverified_account_publishes_nothing_and_keeps_its_queue() {
        val uid = signUpUniqueUnverified("g")
        val now = System.currentTimeMillis()
        val businessId = seedAuthorisedBusiness(uid, now)
        grantPro(uid)

        // Result.retry(), not failure: the Worker never gives up on a queue it could not drain.
        runWorker()

        // Not even the read gets through: `isAuthenticated()` sits under every rule, so an
        // unverified account is refused in both directions rather than merely being unable to write.
        val refusal = runCatching { serverDoc("businesses/$businessId") }.exceptionOrNull()
        assertTrue(
            "Firestore debe negar el acceso, no devolver el documento: $refusal",
            refusal?.message?.contains("PERMISSION_DENIED") == true,
        )
        assertTrue("La cola debe conservarse para reintentar", pendingCount() > 0)
    }

    private fun pendingCount(): Int = withDatabase { database ->
        runBlocking { database.syncOperationDao().pending(1_000).size }
    }

    // ── A. Free is blocked by the Worker itself ──────────────────────────────

    @Test
    fun a_free_account_uploads_nothing_and_keeps_its_queue() {
        val uid = signUpUnique("a")
        val now = System.currentTimeMillis()
        val businessId = seedAuthorisedBusiness(uid, now)
        grantFree(uid)

        val result = runWorker()

        // Not a failure and not a retry: choosing the free plan is a normal state, and retrying
        // forever would burn a free user's battery.
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("La cola no debe tocarse en plan gratis", 2, pendingCount())
        assertFalse(
            "El negocio no debe existir en Firestore para una cuenta gratis",
            adminDocumentExists("businesses/$businessId"),
        )
    }

    // ── B. Pro publishes and drains ──────────────────────────────────────────

    @Test
    fun b_pro_publishes_documents_and_empties_the_queue() {
        val uid = signUpUnique("b")
        val now = System.currentTimeMillis()
        val businessId = seedAuthorisedBusiness(uid, now)
        val customerId = "customer-$runId"
        val saleId = "sale-$runId"
        val movementId = "movement-$runId"
        val totalCents = 12_500L
        withDatabase { database ->
            runBlocking {
                database.customerDao().insert(customerEntity(customerId, businessId, "Ana E2E", now))
                database.financialDao().insertSale(
                    SaleEntity(
                        saleId, businessId, customerId, uid, "V-$runId", 20_000L, 20_030L,
                        totalCents, 0L, totalCents, false, null, now, now,
                    )
                )
                database.financialDao().insertMovement(
                    AccountMovementEntity(
                        movementId, businessId, customerId, saleId, null, "CHARGE", totalCents,
                        "DEBIT", now, uid, null, null,
                    )
                )
                val dao = database.syncOperationDao()
                dao.enqueue(
                    operation(businessId, "CUSTOMER", customerId, now + 2, customerFields(businessId, "Ana E2E", now))
                )
                dao.enqueue(
                    operation(
                        businessId, "SALE", saleId, now + 3,
                        saleFields(businessId, customerId, uid, "V-$runId", now, totalCents),
                    )
                )
                dao.enqueue(
                    operation(
                        businessId, "MOVEMENT", movementId, now + 4,
                        movementFields(businessId, customerId, saleId, "CHARGE", totalCents, "DEBIT", uid, now),
                    )
                )
            }
        }
        grantPro(uid)

        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)

        val business = serverDoc("businesses/$businessId")
        assertTrue("El negocio debe existir en Firestore", business.exists())
        assertEquals(uid, business.getString("ownerUserId"))
        assertEquals("MXN", business.getString("currency"))

        val member = serverDoc("businesses/$businessId/members/$uid")
        assertTrue("La membresía OWNER debe existir", member.exists())
        assertEquals("OWNER", member.getString("role"))
        assertEquals("ACTIVE", member.getString("status"))

        val customer = serverDoc("businesses/$businessId/customers/$customerId")
        assertTrue("El cliente debe existir", customer.exists())
        assertEquals("Ana E2E", customer.getString("name"))
        assertEquals(true, customer.getBoolean("isActive"))
        // Nulls have to survive the JSON round trip, or hasExactKeys rejects the write outright.
        assertTrue("phone debe estar presente aunque sea null", customer.contains("phone"))
        assertNull(customer.get("phone"))

        val sale = serverDoc("businesses/$businessId/sales/$saleId")
        assertTrue("La venta debe existir", sale.exists())
        assertEquals(totalCents, sale.getLong("totalCents"))
        assertEquals(customerId, sale.getString("customerId"))

        val movement = serverDoc("businesses/$businessId/movements/$movementId")
        assertTrue("El cargo debe existir", movement.exists())
        assertEquals("CHARGE", movement.getString("type"))
        assertEquals(totalCents, movement.getLong("amountCents"))

        assertEquals("La cola debe quedar vacía", 0, pendingCount())
    }

    // ── C. Downgrade holds the queue, regaining Pro drains it ────────────────

    @Test
    fun c_downgrade_holds_the_queue_and_regaining_pro_drains_it() {
        val uid = signUpUnique("c")
        val now = System.currentTimeMillis()
        val businessId = seedAuthorisedBusiness(uid, now)
        grantFree(uid)

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("Sin Pro nada debe subir", 2, pendingCount())
        assertFalse(adminDocumentExists("businesses/$businessId"))

        grantPro(uid)
        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertTrue("Al volver Pro debe publicarse", serverDoc("businesses/$businessId").exists())
        assertEquals("Y la cola debe drenar sola", 0, pendingCount())
    }

    // ── D. More than one batch ───────────────────────────────────────────────

    @Test
    fun d_fifty_one_customers_all_reach_firestore_in_one_run() {
        val uid = signUpUnique("d")
        val now = System.currentTimeMillis()
        val businessId = seedAuthorisedBusiness(uid, now)
        val customerIds = (1..51).map { "customer-$runId-$it" }
        withDatabase { database ->
            runBlocking {
                val dao = database.syncOperationDao()
                customerIds.forEachIndexed { index, id ->
                    val name = "Cliente $index"
                    database.customerDao().insert(customerEntity(id, businessId, name, now))
                    dao.enqueue(operation(businessId, "CUSTOMER", id, now + 10 + index, customerFields(businessId, name, now)))
                }
            }
        }
        grantPro(uid)

        // 53 operations against a batch size of 50: the old single-page push left three behind.
        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals("No debe quedar ninguna operación procesable", 0, pendingCount())
        customerIds.forEach { id ->
            assertTrue(
                "Falta el cliente $id en Firestore",
                serverDoc("businesses/$businessId/customers/$id").exists(),
            )
        }
    }

    // ── E. One business cannot touch another ─────────────────────────────────

    @Test
    fun e_a_user_cannot_read_or_write_another_users_business() {
        val uidB = signUpUnique("e-b")
        val now = System.currentTimeMillis()
        val businessB = seedAuthorisedBusiness(uidB, now)
        grantPro(uidB)
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue("El negocio de B debe existir antes de la prueba", serverDoc("businesses/$businessB").exists())

        // Now A, a completely separate account.
        resetLocalState()
        val uidA = signUpUnique("e-a")
        val businessA = seedAuthorisedBusiness(uidA, now)
        grantPro(uidA)
        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertPermissionDenied("A no debe poder LEER el negocio de B") {
            serverDoc("businesses/$businessB")
        }
        assertPermissionDenied("A no debe poder ESCRIBIR en el negocio de B") {
            Tasks.await(
                firestore.document("businesses/$businessB/customers/intruso-$runId")
                    .set(customerFields(businessB, "Intruso", now))
            )
        }
        assertPermissionDenied("A no debe poder leer los clientes de B") {
            Tasks.await(firestore.collection("businesses/$businessB/customers").get(Source.SERVER))
        }

        // And A is still perfectly able to use its own business — the rules block crossing, not work.
        assertTrue(serverDoc("businesses/$businessA").exists())
        Tasks.await(
            firestore.document("businesses/$businessA/customers/propio-$runId")
                .set(customerFields(businessA, "Propio", now))
        )
        assertTrue(serverDoc("businesses/$businessA/customers/propio-$runId").exists())
    }

    private fun assertPermissionDenied(message: String, block: () -> Unit) {
        try {
            block()
            fail("$message — la operación fue permitida")
        } catch (error: Exception) {
            val code = generateSequence(error as Throwable) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
                ?.code
            assertEquals(
                "$message — se esperaba PERMISSION_DENIED y llegó: ${error.message}",
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                code,
            )
        }
    }

    // ── F. Pull, and pulling twice changes nothing ───────────────────────────

    @Test
    fun f_a_remote_change_reaches_room_and_a_second_pull_does_not_duplicate_it() {
        val uid = signUpUnique("f")
        val now = System.currentTimeMillis()
        val businessId = seedAuthorisedBusiness(uid, now)
        grantPro(uid)
        assertEquals(ListenableWorker.Result.success(), runWorker())

        // A customer that exists remotely and has never been seen locally — as if another device had
        // created it.
        val remoteId = "remoto-$runId"
        val remoteMovementId = "movimiento-remoto-$runId"
        val remoteAmountCents = 750L
        Tasks.await(
            firestore.document("businesses/$businessId/customers/$remoteId")
                .set(customerFields(businessId, "Remota", now + 1_000))
        )
        Tasks.await(
            firestore.document("businesses/$businessId/movements/$remoteMovementId")
                .set(
                    movementFields(
                        businessId = businessId,
                        customerId = remoteId,
                        saleId = null,
                        movementType = "CHARGE",
                        amountCents = remoteAmountCents,
                        direction = "DEBIT",
                        userId = uid,
                        now = now + 1_001,
                    )
                )
        )
        withDatabase { database ->
            runBlocking {
                assertNull(
                    "El cliente remoto no debe existir localmente antes del pull",
                    database.customerDao().byId(remoteId),
                )
            }
        }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        withDatabase { database ->
            runBlocking {
                val adopted = database.customerDao().byId(remoteId)
                assertNotNull("RemoteMerger debió insertar el cliente remoto", adopted)
                assertEquals("Remota", adopted?.name)
                assertEquals(businessId, adopted?.businessId)
                assertNotNull(
                    "RemoteMerger debió insertar el movimiento financiero",
                    database.financialDao().movementForId(remoteMovementId),
                )
                assertEquals(
                    remoteAmountCents,
                    database.financialDao().customerBalanceCents(businessId, remoteId),
                )
            }
        }

        // Pulling again re-reads the same window on purpose — the watermark rewinds five minutes.
        // Every merge is meant to be idempotent, so nothing may double.
        assertEquals(ListenableWorker.Result.success(), runWorker())
        withDatabase { database ->
            runBlocking {
                val customers = database.customerDao().activeWithBalances(businessId)
                assertEquals(
                    "Un segundo pull no debe duplicar clientes",
                    customers.map { it.customer.id }.distinct().size,
                    customers.size,
                )
                assertEquals(
                    "El saldo no debe cambiar por releer",
                    remoteAmountCents,
                    database.financialDao().businessReceivableCents(businessId),
                )
                assertEquals(
                    "El movimiento remoto no debe duplicarse",
                    1,
                    database.financialDao().customerMovements(businessId, remoteId).size,
                )
                assertTrue(
                    "Ningún cliente de otro negocio debe haber entrado",
                    customers.all { it.customer.businessId == businessId },
                )
            }
        }
    }

    private companion object {
        const val REQUIRE_EMULATORS_ARGUMENT = "cuentivoRequireEmulators"
    }
}
