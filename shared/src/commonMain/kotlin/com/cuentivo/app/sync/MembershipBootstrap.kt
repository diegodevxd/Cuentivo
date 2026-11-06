package com.cuentivo.app.sync

import com.cuentivo.app.data.local.BusinessEntity

/**
 * Vuelve a publicar la membresía del dueño después de adoptar un negocio restaurado.
 *
 * Es una interfaz porque la implementación de Android necesita `SyncQueue` (y con ella un
 * `Context`); `CloudRestore` sólo necesita que alguien lo haga.
 */
interface MembershipBootstrap {
    suspend fun ensure(business: BusinessEntity)
}
