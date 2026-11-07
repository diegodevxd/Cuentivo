package com.cuentivo.app.platform

import com.cuentivo.app.ui.onboarding.FirstDebtFlagStore
import platform.Foundation.NSUserDefaults

/**
 * La respuesta de la primera deuda en iOS, en `NSUserDefaults`.
 *
 * Misma interfaz que Android: la llave la construye `FirstDebtPreferenceStore` y aquí solo se
 * guarda. Sólo el interruptor de onboarding llega a este almacén: ni deudas, ni clientes, ni saldos.
 *
 * Pendiente de CI: usa `NSUserDefaults`, que existe en el simulador de pruebas; esta clase se apoya
 * en la misma interfaz que ya prueba `FirstDebtPreferenceStore` en común.
 */
class NsUserDefaultsFirstDebtFlagStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : FirstDebtFlagStore {

    override fun isSet(key: String): Boolean = defaults.boolForKey(key)

    override fun set(key: String) = defaults.setBool(true, forKey = key)
}
