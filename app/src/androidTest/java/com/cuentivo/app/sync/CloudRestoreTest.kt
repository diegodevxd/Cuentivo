package com.cuentivo.app.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.data.local.CuentivoDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the reinstall path end to end against a real (in-memory) Room, with the network faked.
 *
 * This is the regression test for a reported data loss: a Pro phone was reinstalled, the app offered
 * to create a business, and the customers already in Firestore became unreachable under the new
 * random id. What has to hold is that an empty device signing in finds the business by its *owner*
 * and adopts the same businessId it had before — not a new one.
 *
 * It lives in androidTest rather than the JVM suite only because [CloudRestore] writes through Room.
 */
@RunWith(AndroidJUnit4::class)
class CloudRestoreTest {

    private lateinit var database: CuentivoDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CuentivoDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun an_empty_device_recovers_the_business_its_account_already_owns() = runBlocking {
        val gateway = FakeCloudGateway(
            ownedBusinessId = BUSINESS_ID,
            documents = listOf(businessDocument(), customerDocument("c1", "Ana"), customerDocument("c2", "Beto")),
        )

        val result = restoreWith(gateway).restore(OWNER)

        assertEquals(CloudRestoreResult.Restored(BUSINESS_ID, customers = 2, sales = 0), result)
        // The businessId is the one from the cloud, so every query that filters by it now reaches
        // the customers that were already up there. A fresh UUID here is the bug itself.
        assertNotNull(database.businessDao().byId(BUSINESS_ID))
        assertEquals(BUSINESS_ID, database.businessDao().firstOwnedBy(OWNER)?.id)
        assertEquals(2, database.customerDao().activeWithBalances(BUSINESS_ID).size)
        // Downloading from zero must ask for everything, not from some watermark this device
        // cannot have.
        assertEquals(listOf(0L), gateway.downloadedSince)
    }

    @Test
    fun the_owner_membership_is_republished_so_later_writes_are_not_denied() = runBlocking {
        val gateway = FakeCloudGateway(BUSINESS_ID, listOf(businessDocument()))

        restoreWith(gateway).restore(OWNER)

        // Every subcollection rule is gated behind this document. The restored device's outbox is
        // empty, so if the restore does not enqueue it, nothing ever will and the next sale is
        // rejected with PERMISSION_DENIED.
        assertNotNull(database.businessDao().memberFor(BUSINESS_ID, OWNER))
        assertTrue(database.syncOperationDao().pending(50).any { it.entityType == "MEMBER" })
    }

    @Test
    fun restoring_twice_changes_nothing() = runBlocking {
        val gateway = FakeCloudGateway(BUSINESS_ID, listOf(businessDocument(), customerDocument("c1", "Ana")))
        val restore = restoreWith(gateway)

        restore.restore(OWNER)
        val second = restore.restore(OWNER)

        assertEquals(CloudRestoreResult.Restored(BUSINESS_ID, customers = 1, sales = 0), second)
        assertEquals(1, database.customerDao().activeWithBalances(BUSINESS_ID).size)
    }

    @Test
    fun restoring_adopts_whichever_business_the_lookup_chose() = runBlocking {
        // The lookup picks the newest business, because an account that reinstalled a few times owns
        // several and all but the last are empty. Restoring the wrong one looks exactly like the bug
        // it was supposed to fix: the app opens empty and the real data stays out of reach.
        val newest = "el-negocio-mas-reciente"
        val gateway = FakeCloudGateway(
            ownedBusinessId = newest,
            documents = listOf(
                RemoteDocument("BUSINESS", newest, NOW, businessDocument().fields),
                RemoteDocument("CUSTOMER", "c1", NOW, customerDocument("c1", "Ana").fields + ("businessId" to newest)),
            ),
        )

        val result = restoreWith(gateway).restore(OWNER)

        assertEquals(CloudRestoreResult.Restored(newest, customers = 1, sales = 0), result)
        assertEquals(newest, database.businessDao().firstOwnedBy(OWNER)?.id)
        assertEquals(1, database.customerDao().activeWithBalances(newest).size)
    }

    @Test
    fun a_brand_new_account_owns_nothing_and_that_is_not_an_error() = runBlocking {
        val result = restoreWith(FakeCloudGateway(ownedBusinessId = null)).restore(OWNER)

        assertEquals(CloudRestoreResult.NothingInCloud, result)
        assertEquals(null, database.businessDao().first())
    }

    @Test
    fun a_failed_lookup_leaves_the_device_untouched_and_reportable() = runBlocking {
        val failure = SyncFailure.Transient("network down")
        val result = restoreWith(FakeCloudGateway(lookupFailure = failure)).restore(OWNER)

        assertEquals(CloudRestoreResult.Failed(failure, CloudRestoreStage.LOOKUP), result)
        // Nothing half-written: the next attempt starts from the same clean slate.
        assertEquals(null, database.businessDao().first())
    }

    private fun restoreWith(gateway: FakeCloudGateway) = CloudRestore(
        database = database,
        lookup = gateway,
        gateway = gateway,
        membershipBootstrap = OwnerMembershipBootstrap(
            database.businessDao(),
            database.syncOperationDao(),
            SyncQueue(database.syncOperationDao(), ApplicationProvider.getApplicationContext()),
        ),
    )

    private fun businessDocument() = RemoteDocument(
        entityType = "BUSINESS",
        entityId = BUSINESS_ID,
        watermarkEpochMillis = NOW,
        fields = mapOf(
            "name" to "Mi negocio",
            "ownerUserId" to OWNER,
            "currency" to "MXN",
            "timezone" to "America/Mexico_City",
            "createdAtEpochMillis" to NOW,
            "updatedAtEpochMillis" to NOW,
        ),
    )

    private fun customerDocument(id: String, name: String) = RemoteDocument(
        entityType = "CUSTOMER",
        entityId = id,
        watermarkEpochMillis = NOW,
        fields = mapOf(
            "businessId" to BUSINESS_ID,
            "name" to name,
            "normalizedName" to name.lowercase(),
            "phone" to null,
            "email" to null,
            "address" to null,
            "notes" to null,
            "creditLimitCents" to null,
            "isActive" to true,
            "createdAtEpochMillis" to NOW,
            "updatedAtEpochMillis" to NOW,
            "deletedAtEpochMillis" to null,
        ),
    )

    private class FakeCloudGateway(
        private val ownedBusinessId: String? = null,
        private val documents: List<RemoteDocument> = emptyList(),
        private val lookupFailure: SyncFailure? = null,
    ) : SyncRemoteBusinessLookup, SyncRemotePullGateway {

        val downloadedSince = mutableListOf<Long>()

        override fun findBusinessOwnedBy(userId: String): BusinessLookupResult = when {
            lookupFailure != null -> BusinessLookupResult.Failure(lookupFailure)
            ownedBusinessId != null -> BusinessLookupResult.Found(ownedBusinessId)
            else -> BusinessLookupResult.None
        }

        override fun download(businessId: String, sinceEpochMillis: Long): SyncDownloadResult {
            downloadedSince += sinceEpochMillis
            return SyncDownloadResult.Success(documents)
        }
    }

    private companion object {
        const val BUSINESS_ID = "business-de-antes-de-reinstalar"
        const val OWNER = "uid-de-diego"
        const val NOW = 1_700_000_000_000L
    }
}
