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
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.BusinessMemberEntity
import com.cuentivo.app.data.local.CUENTIVO_MIGRATIONS
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.SyncOperationEntity
import com.cuentivo.app.domain.SubscriptionTier
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
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
 * Proves that an anonymous guest session (and an unverified newly-linked account) cannot read or
 * write to Firestore under any circumstances.
 *
 * Requirements:
 * 1. Anonymous session has no proven email address: firestore.rules requires email_verified == true,
 *    so every read and write to Firestore returns PERMISSION_DENIED.
 * 2. After linking an anonymous account (linkWithCredential), the UID is preserved, but email_verified
 *    is still false: Firestore continues to reject every read and write until email verification is complete.
 * 3. Only after the email is verified does Firestore allow access and sync.
 */
@RunWith(AndroidJUnit4::class)
class GuestCloudIsolationEmulatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var entitlements: EntitlementStore

    private val runId = UUID.randomUUID().toString().take(8)

    @Before
    fun setUp() {
        val e2eWasExplicitlyRequested =
            InstrumentationRegistry.getArguments().getString("cuentivo.requireEmulators") == "true"
        if (!BuildConfig.USE_FIREBASE_EMULATORS) {
            if (e2eWasExplicitlyRequested) {
                fail(
                    "GuestCloudIsolationEmulatorTest fue solicitada sin -Pcuentivo.useEmulators=true; " +
                        "se aborta antes de tocar Firebase de producción."
                )
            }
            assumeTrue("GuestCloudIsolationEmulatorTest sólo se ejecuta con Firebase Emulator Suite", false)
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

    private fun resetLocalState() {
        if (::auth.isInitialized) auth.signOut()
        if (::entitlements.isInitialized) entitlements.forget()
        context.deleteDatabase("cuentivo_guest_test.db")
    }

    private fun <T> withDatabase(block: (CuentivoDatabase) -> T): T {
        val database = Room.databaseBuilder(context, CuentivoDatabase::class.java, "cuentivo_guest_test.db")
            .addMigrations(*CUENTIVO_MIGRATIONS)
            .build()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun serverDoc(path: String): DocumentSnapshot =
        Tasks.await(firestore.document(path).get(Source.SERVER))

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
        try {
            connection.outputStream.write("""{"localId":"$uid","emailVerified":true}""".toByteArray())
            check(connection.responseCode in 200..299) { "El emulador de Auth rechazó la verificación: ${connection.responseCode}" }
        } finally {
            connection.disconnect()
        }
        val user = requireNotNull(auth.currentUser)
        Tasks.await(user.reload())
        Tasks.await(user.getIdToken(true))
    }

    private fun assertPermissionDenied(message: String, block: () -> Unit) {
        try {
            block()
            fail("$message — la operación fue permitida cuando debía rechazarse")
        } catch (error: Exception) {
            val code = generateSequence(error as Throwable) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
                ?.code
            assertEquals(
                "$message — se esperaba PERMISSION_DENIED pero llegó: ${error.message}",
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                code,
            )
        }
    }

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

    // ── Test 1: Anonymous session cannot read or write Firestore ─────────────

    @Test
    fun anonymous_guest_session_is_denied_all_firestore_reads_and_writes() {
        val result = Tasks.await(auth.signInAnonymously())
        val user = requireNotNull(result.user)
        assertTrue("La sesión debe ser anónima", user.isAnonymous)
        assertFalse("Una sesión anónima no tiene correo verificado", user.isEmailVerified)

        val guestUid = user.uid
        val businessId = "biz-guest-$runId"
        val now = System.currentTimeMillis()

        assertPermissionDenied("Invitado anónimo no debe poder ESCRIBIR un negocio") {
            Tasks.await(
                firestore.document("businesses/$businessId")
                    .set(businessFields("Negocio Invitado", guestUid, now))
            )
        }

        assertPermissionDenied("Invitado anónimo no debe poder LEER un negocio") {
            serverDoc("businesses/$businessId")
        }

        assertPermissionDenied("Invitado anónimo no debe poder ESCRIBIR clientes") {
            Tasks.await(
                firestore.document("businesses/$businessId/customers/c1")
                    .set(customerFields(businessId, "Cliente Secreto", now))
            )
        }

        assertPermissionDenied("Invitado anónimo no debe poder LEER colecciones de clientes") {
            Tasks.await(firestore.collection("businesses/$businessId/customers").get(Source.SERVER))
        }
    }

    // ── Test 2: Linked account before verification is still denied ───────────

    @Test
    fun linked_account_before_email_verification_is_still_denied_firestore_access() {
        val anonResult = Tasks.await(auth.signInAnonymously())
        val anonUser = requireNotNull(anonResult.user)
        val guestUid = anonUser.uid

        val email = "linked-$runId@cuentivo.test"
        val linkResult = Tasks.await(
            anonUser.linkWithCredential(EmailAuthProvider.getCredential(email, "Password123!"))
        )
        val linkedUser = requireNotNull(linkResult.user)

        assertEquals("El enlace debe conservar el mismo UID", guestUid, linkedUser.uid)
        assertFalse("La cuenta enlazada aún no está verificada", linkedUser.isEmailVerified)

        val businessId = "biz-linked-$runId"
        val now = System.currentTimeMillis()

        assertPermissionDenied("Cuenta recién enlazada sin verificar no debe poder ESCRIBIR en Firestore") {
            Tasks.await(
                firestore.document("businesses/$businessId")
                    .set(businessFields("Negocio Enlazado", guestUid, now))
            )
        }

        assertPermissionDenied("Cuenta recién enlazada sin verificar no debe poder LEER en Firestore") {
            serverDoc("businesses/$businessId")
        }
    }

    // ── Test 3: Linked account after verification is allowed (Positive Control)

    @Test
    fun linked_account_after_email_verification_is_allowed_to_write_and_read() {
        val anonResult = Tasks.await(auth.signInAnonymously())
        val anonUser = requireNotNull(anonResult.user)
        val guestUid = anonUser.uid

        val email = "verified-$runId@cuentivo.test"
        val linkResult = Tasks.await(
            anonUser.linkWithCredential(EmailAuthProvider.getCredential(email, "Password123!"))
        )
        val linkedUser = requireNotNull(linkResult.user)
        assertEquals(guestUid, linkedUser.uid)

        // Mark email verified
        markEmailVerified(guestUid)
        assertTrue("El usuario debe estar verificado", requireNotNull(auth.currentUser).isEmailVerified)

        val businessId = "biz-verified-$runId"
        val now = System.currentTimeMillis()

        // Create business and member document
        Tasks.await(
            firestore.document("businesses/$businessId")
                .set(businessFields("Negocio Verificado", guestUid, now))
        )
        Tasks.await(
            firestore.document("businesses/$businessId/members/$guestUid")
                .set(memberFields(businessId, guestUid, now))
        )

        // Write customer document
        Tasks.await(
            firestore.document("businesses/$businessId/customers/c-verified")
                .set(customerFields(businessId, "Cliente Verificado", now))
        )

        // Read back from server
        val customerDoc = serverDoc("businesses/$businessId/customers/c-verified")
        assertTrue("El cliente debe existir en Firestore tras la verificación", customerDoc.exists())
        assertEquals("Cliente Verificado", customerDoc.getString("name"))
    }
}