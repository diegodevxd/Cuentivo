package com.cuentivo.app.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La regla de la primera deuda, sobre un doble en memoria pero invocando la clase real.
 *
 * Que la llave sea exactamente `first_debt_dismissed_<userId>` no es un detalle de estilo: es la
 * llave que ya está escrita en los teléfonos, y cambiarla haría que la pregunta volviera a salir.
 */
class FirstDebtPreferenceStoreTest {

    @Test
    fun a_fresh_install_is_not_dismissed_and_marking_it_makes_it_so() {
        val store = FirstDebtPreferenceStore(InMemoryFlags())

        assertFalse(store.isDismissed("u1"))

        store.markDismissed("u1")

        assertTrue(store.isDismissed("u1"))
    }

    @Test
    fun dismissing_for_one_user_does_not_touch_another() {
        val store = FirstDebtPreferenceStore(InMemoryFlags())

        store.markDismissed("u1")

        assertFalse(store.isDismissed("u2"))
    }

    @Test
    fun a_blank_user_writes_nothing() {
        val flags = InMemoryFlags()
        val store = FirstDebtPreferenceStore(flags)

        assertFalse(store.isDismissed(""))

        store.markDismissed("")
        store.markDismissed("   ")

        assertEquals(emptySet<String>(), flags.keys.toSet())
        assertFalse(store.isDismissed(""))
    }

    @Test
    fun a_blank_user_is_never_dismissed_even_if_its_key_already_exists() {
        // `first_debt_dismissed_` is the key a blank id would produce. Planted on purpose: the guard
        // has to win over whatever is stored, or a session without an id would inherit an answer
        // that was never given.
        val flags = InMemoryFlags(mutableSetOf("first_debt_dismissed_", ""))
        val store = FirstDebtPreferenceStore(flags)

        assertFalse(store.isDismissed(""))
        assertFalse(store.isDismissed("   "))
    }

    @Test
    fun the_key_is_the_one_already_written_on_phones() {
        val flags = InMemoryFlags()

        FirstDebtPreferenceStore(flags).markDismissed("u1")

        assertEquals(listOf("first_debt_dismissed_u1"), flags.keys.toList())
    }

    private class InMemoryFlags(initial: MutableSet<String> = mutableSetOf()) : FirstDebtFlagStore {
        val keys: MutableSet<String> = initial

        override fun isSet(key: String): Boolean = key in keys

        override fun set(key: String) {
            keys += key
        }
    }
}
