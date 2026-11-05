package com.cuentivo.app.domain

/**
 * Roles as stored in `business_members.role` and read by firestore.rules.
 *
 * Status matters as much as the role: firestore.rules `isMember()` requires `status == 'ACTIVE'`,
 * so an invited-but-not-accepted member has a role and still no access at all.
 */
enum class Role { OWNER, ADMIN, EDITOR, VIEWER }

enum class Capability {
    VIEW_BUSINESS,
    MANAGE_CUSTOMERS,
    MANAGE_PRODUCTS,
    RECORD_SALE,
    RECORD_PAYMENT,
    CANCEL_SALE,
    CANCEL_PAYMENT,
    REGISTER_ADJUSTMENT,
    VIEW_AUDIT_LOG,
    MANAGE_MEMBERS,
    UPDATE_BUSINESS,
}

/**
 * Client-side mirror of the role checks in firestore.rules.
 *
 * This is defence in depth, not the enforcement point — the server decides. It exists because
 * hiding a button is not a permission check: any code path that reaches a DAO must be able to ask
 * whether the current member may do this, and get the same answer the rules would give.
 *
 * Kept deliberately aligned, function by function:
 * - [Capability.VIEW_BUSINESS]   ↔ `canReadBusiness`         (any ACTIVE member)
 * - operational writes           ↔ `canWriteOperationalData` (OWNER, ADMIN, EDITOR)
 * - [Capability.VIEW_AUDIT_LOG]  ↔ `canAdministerBusiness`   (OWNER, ADMIN)
 * - [Capability.MANAGE_MEMBERS] and [Capability.UPDATE_BUSINESS] ↔ `isBusinessOwner` (OWNER only —
 *   note ADMIN cannot manage members, because the rules check the business document's ownerUserId
 *   rather than the role)
 *
 * Cancellations and adjustments are the one place this is *stricter* than the plain
 * `canWriteOperationalData` grouping: they rewrite what a balance means, so they are restricted to
 * OWNER and ADMIN here and in the matching rules for CANCELLATION, REFUND and ADJUSTMENT movements.
 */
object Permissions {
    const val ACTIVE_STATUS = "ACTIVE"

    private val GRANTS: Map<Role, Set<Capability>> = mapOf(
        Role.OWNER to Capability.entries.toSet(),
        Role.ADMIN to setOf(
            Capability.VIEW_BUSINESS,
            Capability.MANAGE_CUSTOMERS,
            Capability.MANAGE_PRODUCTS,
            Capability.RECORD_SALE,
            Capability.RECORD_PAYMENT,
            Capability.CANCEL_SALE,
            Capability.CANCEL_PAYMENT,
            Capability.REGISTER_ADJUSTMENT,
            Capability.VIEW_AUDIT_LOG,
        ),
        Role.EDITOR to setOf(
            Capability.VIEW_BUSINESS,
            Capability.MANAGE_CUSTOMERS,
            Capability.MANAGE_PRODUCTS,
            Capability.RECORD_SALE,
            Capability.RECORD_PAYMENT,
        ),
        Role.VIEWER to setOf(Capability.VIEW_BUSINESS),
    )

    /** Nullable on purpose: "not a member" and "a member without this capability" both mean no. */
    fun allows(role: Role?, capability: Capability): Boolean =
        role != null && capability in GRANTS.getValue(role)

    /**
     * Resolves the effective role of a membership row.
     *
     * Returns null for anything that is not an ACTIVE member with a role this build understands.
     * An unrecognised role string is refused rather than defaulted: a future role added by a newer
     * client must not silently degrade into something permissive here.
     */
    fun effectiveRole(rawRole: String?, status: String?): Role? {
        if (status != ACTIVE_STATUS) return null
        return Role.entries.firstOrNull { it.name == rawRole }
    }
}
