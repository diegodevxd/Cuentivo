package com.cuentivo.app.domain

/**
 * The financial actions worth recording in `audit_logs`.
 *
 * [summary] is a fixed phrase, never interpolated with a customer name, a phone or an amount. The
 * audit row already carries entityType and entityId, so the detail is one join away for whoever is
 * allowed to read it — and keeping the text constant means an audit trail can be shown, exported or
 * shipped somewhere without leaking who owes what.
 */
enum class AuditAction(val code: String, val summary: String) {
    SALE_CREATED("SALE_CREATED", "Venta registrada"),
    SALE_CANCELED("SALE_CANCELED", "Venta cancelada"),
    /** One event, not a cancel plus a create: a correction is a single decision by one person. */
    SALE_CORRECTED("SALE_CORRECTED", "Venta corregida"),
    PAYMENT_REGISTERED("PAYMENT_REGISTERED", "Abono registrado"),
    PAYMENT_CANCELED("PAYMENT_CANCELED", "Abono cancelado"),
    ADJUSTMENT_REGISTERED("ADJUSTMENT_REGISTERED", "Ajuste de saldo registrado"),
}

object AuditTrail {
    /**
     * Derived from the operation's idempotency key, so replaying an operation that was already
     * applied cannot append a second audit row for the same event.
     */
    fun eventId(idempotencyKey: String): String = "audit-$idempotencyKey"
}
