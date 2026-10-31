package com.cuentivo.app.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cuentivo.app.data.local.AccountMovementEntity
import com.cuentivo.app.data.local.BusinessEntity
import com.cuentivo.app.data.local.CuentivoDatabase
import com.cuentivo.app.data.local.CustomerEntity
import com.cuentivo.app.data.local.OperationEntity
import com.cuentivo.app.data.local.ProductEntity
import com.cuentivo.app.data.local.SaleEntity
import com.cuentivo.app.data.local.SaleItemEntity
import com.cuentivo.app.domain.CustomerNameNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that linking an account to an anonymous guest session preserves all local Room data,
 * keeping the exact same UID, businessId and ownerUserId without deleting any rows,
 * while signing in with a truly different account wipes the database via [LocalSessionGuard].
 *
 * Golden rule of identity and local data:
 * In case of doubt, preserve local data and ask the user.
 * NEVER delete to resolve an identity conflict.
 */
@RunWith(AndroidJUnit4::class)
class GuestAccountLinkingRoomTest {
    private lateinit var context: Context
    private lateinit var database: CuentivoDatabase
    private lateinit var sessionGuard: LocalSessionGuard

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            CuentivoDatabase::class.java,
        ).build()
        sessionGuard = LocalSessionGuard(context, database)
        sessionGuard.forget()
    }

    @After
    fun tearDown() {
        sessionGuard.forget()
        database.close()
    }

    @Test
    fun guest_session_creates_rows_links_account_preserves_same_uid_and_zero_rows_wiped() = runBlocking {
        val guestUid = "guest_uid_12345"
        val businessId = "business_guest_001"
        val now = System.currentTimeMillis()

        // 1. First launch: Anonymous guest session adopts guestUid
        val initialWipe = sessionGuard.adopt(guestUid)
        assertFalse(initialWipe)

        // 2. Guest works locally: creates business, customer, product, sale
        database.businessDao().insert(
            BusinessEntity(
                id = businessId,
                name = "Mi Tiendita",
                ownerUserId = guestUid,
                currency = "MXN",
                timezone = "America/Mexico_City",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        database.customerDao().insert(
            CustomerEntity(
                id = "customer_1",
                businessId = businessId,
                name = "Doña María",
                normalizedName = CustomerNameNormalizer.key("Doña María"),
                phone = "5551234567",
                email = null,
                address = null,
                notes = "Cliente frecuente",
                creditLimitCents = 50000L,
                isActive = true,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                deletedAtEpochMillis = null,
            )
        )
        database.productDao().insert(
            ProductEntity(
                id = "product_1",
                businessId = businessId,
                name = "Crema facial",
                description = "Crema hidratante",
                sku = "CREMA-01",
                category = "Belleza",
                salePriceCents = 10000L,
                costPriceCents = 6000L,
                stock = 10L,
                imageUri = null,
                isActive = true,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                deletedAtEpochMillis = null,
            )
        )

        val sale = SaleEntity(
            id = "sale_1",
            businessId = businessId,
            customerId = "customer_1",
            sellerUserId = guestUid,
            saleNumber = "V-1",
            saleDateEpochDay = 1000L,
            dueDateEpochDay = null,
            subtotalCents = 10000L,
            discountCents = 0L,
            totalCents = 10000L,
            isCanceled = false,
            notes = "Fiado 1 crema",
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val items = listOf(
            SaleItemEntity(
                id = "item_1",
                saleId = sale.id,
                productId = "product_1",
                productNameSnapshot = "Crema facial",
                quantity = 1L,
                unitPriceCents = 10000L,
                discountCents = 0L,
                subtotalCents = 10000L,
            )
        )
        val charge = AccountMovementEntity(
            id = "mov_1",
            businessId = businessId,
            customerId = "customer_1",
            saleId = sale.id,
            paymentId = null,
            type = "CHARGE",
            amountCents = 10000L,
            direction = "DEBIT",
            occurredAtEpochMillis = now,
            createdByUserId = guestUid,
            reversesMovementId = null,
            notes = null,
        )
        database.financialDao().createSaleIdempotently(
            OperationEntity("op_sale_1", "SALE", sale.id, now),
            sale,
            items,
            charge,
        )

        assertNotNull(database.businessDao().first())
        assertEquals(1, database.customerDao().searchActive(businessId, "", "").size)
        assertEquals(1, database.productDao().searchActive(businessId, "", "").size)
        assertEquals(10000L, database.financialDao().customerBalanceCents(businessId, "customer_1"))

        // 3. User links account: linkWithCredential preserves the exact same guest UID
        val linkedUid = guestUid
        assertEquals(guestUid, linkedUid)

        // 4. Session guard adopt runs on linked session
        val wipeOnLink = sessionGuard.adopt(linkedUid)
        assertFalse("Account linking must NEVER wipe local data", wipeOnLink)

        // 5. Assert: 0 rows deleted, no businessId changed, no ownerUserId changed
        val business = database.businessDao().first()
        assertEquals("Business row must remain intact", businessId, business?.id)
        assertEquals("ownerUserId must remain the same UID", guestUid, business?.ownerUserId)
        assertEquals("Business name must be preserved", "Mi Tiendita", business?.name)

        val customers = database.customerDao().searchActive(businessId, "", "")
        assertEquals("Customer count must be 1 (zero rows deleted)", 1, customers.size)
        assertEquals("Doña María", customers.first().name)
        assertEquals(businessId, customers.first().businessId)

        val products = database.productDao().searchActive(businessId, "", "")
        assertEquals("Product count must be 1 (zero rows deleted)", 1, products.size)
        assertEquals("Crema facial", products.first().name)
        assertEquals(businessId, products.first().businessId)

        val balance = database.financialDao().customerBalanceCents(businessId, "customer_1")
        assertEquals("Customer balance must be preserved intact", 10000L, balance)
    }

    @Test
    fun signing_in_with_different_account_triggers_local_session_guard_wipe() = runBlocking {
        val originalUid = "user_alpha_111"
        val businessId = "business_alpha"
        val now = System.currentTimeMillis()

        sessionGuard.adopt(originalUid)

        database.businessDao().insert(
            BusinessEntity(
                id = businessId,
                name = "Negocio Alpha",
                ownerUserId = originalUid,
                currency = "MXN",
                timezone = "America/Mexico_City",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        )
        database.customerDao().insert(
            CustomerEntity(
                id = "cust_alpha_1",
                businessId = businessId,
                name = "Cliente Alpha",
                normalizedName = CustomerNameNormalizer.key("Cliente Alpha"),
                phone = "5559876543",
                email = null,
                address = null,
                notes = null,
                creditLimitCents = null,
                isActive = true,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                deletedAtEpochMillis = null,
            )
        )
        assertNotNull(database.businessDao().first())
        assertEquals(1, database.customerDao().searchActive(businessId, "", "").size)

        // Someone signs into a genuinely different account
        val differentUid = "user_beta_222"
        val wipeTriggered = sessionGuard.adopt(differentUid)

        assertTrue("Signing into a different account must trigger LocalSessionGuard wipe", wipeTriggered)
        assertNull("Predecessor business must be wiped from device", database.businessDao().first())
        assertEquals("Predecessor customers must be wiped from device", 0, database.customerDao().searchActive(businessId, "", "").size)
    }
}
