package com.cuentivo.app.platform

/** Lo que la UI necesita para programar o cancelar los recordatorios locales. */
interface ReminderScheduler {
    fun sync()
    fun cancel()
}
