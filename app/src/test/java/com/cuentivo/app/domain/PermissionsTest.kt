package com.cuentivo.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {

    @Test
    fun `every active member can view the business`() {
        Role.entries.forEach { role ->
            assertTrue("$role should be able to view", Permissions.allows(role, Capability.VIEW_BUSINESS))
        }
    }

    @Test
    fun `a viewer cannot write anything`() {
        val writes = Capability.entries - Capability.VIEW_BUSINESS
        writes.forEach { capability ->
            assertFalse("VIEWER should not have $capability", Permissions.allows(Role.VIEWER, capability))
        }
    }

    @Test
    fun `an editor records sales and payments but cannot undo them`() {
        assertTrue(Permissions.allows(Role.EDITOR, Capability.RECORD_SALE))
        assertTrue(Permissions.allows(Role.EDITOR, Capability.RECORD_PAYMENT))
        assertTrue(Permissions.allows(Role.EDITOR, Capability.MANAGE_CUSTOMERS))
        assertTrue(Permissions.allows(Role.EDITOR, Capability.MANAGE_PRODUCTS))

        assertFalse(Permissions.allows(Role.EDITOR, Capability.CANCEL_SALE))
        assertFalse(Permissions.allows(Role.EDITOR, Capability.CANCEL_PAYMENT))
        assertFalse(Permissions.allows(Role.EDITOR, Capability.REGISTER_ADJUSTMENT))
    }

    @Test
    fun `an editor cannot read the audit log`() {
        assertFalse(Permissions.allows(Role.EDITOR, Capability.VIEW_AUDIT_LOG))
        assertTrue(Permissions.allows(Role.ADMIN, Capability.VIEW_AUDIT_LOG))
        assertTrue(Permissions.allows(Role.OWNER, Capability.VIEW_AUDIT_LOG))
    }

    @Test
    fun `only the owner manages members and the business itself`() {
        // Mirrors firestore.rules, where member writes are gated on isBusinessOwner rather than on
        // the role, so an ADMIN legitimately cannot do this.
        assertTrue(Permissions.allows(Role.OWNER, Capability.MANAGE_MEMBERS))
        assertTrue(Permissions.allows(Role.OWNER, Capability.UPDATE_BUSINESS))

        listOf(Role.ADMIN, Role.EDITOR, Role.VIEWER).forEach { role ->
            assertFalse("$role must not manage members", Permissions.allows(role, Capability.MANAGE_MEMBERS))
            assertFalse("$role must not update the business", Permissions.allows(role, Capability.UPDATE_BUSINESS))
        }
    }

    @Test
    fun `the owner can do everything`() {
        Capability.entries.forEach { capability ->
            assertTrue("OWNER should have $capability", Permissions.allows(Role.OWNER, capability))
        }
    }

    @Test
    fun `a member who has not accepted the invitation has no role at all`() {
        assertNull(Permissions.effectiveRole("ADMIN", "INVITED"))
        assertNull(Permissions.effectiveRole("OWNER", "SUSPENDED"))
        assertNull(Permissions.effectiveRole("EDITOR", null))
    }

    @Test
    fun `an unrecognised role is refused rather than defaulted`() {
        assertNull(Permissions.effectiveRole("SUPERUSER", "ACTIVE"))
        assertNull(Permissions.effectiveRole("", "ACTIVE"))
        assertNull(Permissions.effectiveRole(null, "ACTIVE"))
    }

    @Test
    fun `an active known role resolves`() {
        assertEquals(Role.ADMIN, Permissions.effectiveRole("ADMIN", "ACTIVE"))
        assertEquals(Role.OWNER, Permissions.effectiveRole("OWNER", "ACTIVE"))
    }

    @Test
    fun `a null role grants nothing`() {
        Capability.entries.forEach { capability ->
            assertFalse(Permissions.allows(null, capability))
        }
    }
}
