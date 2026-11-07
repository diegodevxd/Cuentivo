package com.cuentivo.app.auth

import com.cuentivo.app.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingAccountDeletionStoreTest {

    @Test
    fun mark_persists_the_user_id_under_the_known_key() {
        val backing = InMemoryKeyValueStore()
        val store = PendingAccountDeletionStore(backing)

        store.mark("uid-123")

        // La clave literal es el contrato con las instalaciones existentes: si cambia, el borrado
        // pendiente de una versión anterior deja de retomarse.
        assertEquals("uid-123", backing.getString("pendingDeletionUserId"))
        assertEquals("uid-123", store.pendingUserId())
    }

    @Test
    fun mark_ignores_a_blank_user_id() {
        val backing = InMemoryKeyValueStore()
        val store = PendingAccountDeletionStore(backing)

        store.mark("   ")

        assertNull(store.pendingUserId())
    }

    @Test
    fun is_pending_for_the_same_user_is_true() {
        val store = PendingAccountDeletionStore(InMemoryKeyValueStore())
        store.mark("uid-123")

        assertTrue(store.isPendingFor("uid-123"))
    }

    @Test
    fun is_pending_for_another_user_is_false() {
        val store = PendingAccountDeletionStore(InMemoryKeyValueStore())
        store.mark("uid-123")

        assertFalse(store.isPendingFor("uid-456"))
    }

    @Test
    fun is_pending_for_null_is_false() {
        val store = PendingAccountDeletionStore(InMemoryKeyValueStore())
        store.mark("uid-123")

        assertFalse(store.isPendingFor(null))
    }

    @Test
    fun clear_removes_the_mark() {
        val backing = InMemoryKeyValueStore()
        val store = PendingAccountDeletionStore(backing)
        store.mark("uid-123")

        store.clear()

        assertNull(store.pendingUserId())
        assertNull(backing.getString("pendingDeletionUserId"))
        assertFalse(store.isPendingFor("uid-123"))
    }
}
