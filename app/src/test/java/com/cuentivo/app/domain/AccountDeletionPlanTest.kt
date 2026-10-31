package com.cuentivo.app.domain

import com.cuentivo.app.domain.AccountDeletionPlan.FailureAction
import com.cuentivo.app.domain.AccountDeletionPlan.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D · El orden del borrado de cuenta (v44).
 *
 * El defecto: se borraba la nube y Room y **al final** se intentaba borrar el usuario de Auth. Con
 * una sesión vieja Firebase lo rechazaba, y la persona quedaba con sesión abierta y sin datos —el
 * estado raro de Diego— con la pantalla colgada. El orden correcto es re-autenticar → nube → local →
 * Auth, y ninguna parada deja seguir adelante tras un fallo.
 */
class AccountDeletionPlanTest {

    @Test
    fun `una cuenta con Google re-autentica primero`() {
        assertEquals(Step.REAUTHENTICATE, AccountDeletionPlan.firstStep(isAnonymous = false))
    }

    @Test
    fun `una cuenta invitada no re-autentica con Google`() {
        // La cuenta anónima no tiene Google que confirmar: arranca directo en la nube.
        assertEquals(Step.ERASE_REMOTE, AccountDeletionPlan.firstStep(isAnonymous = true))
    }

    @Test
    fun `el orden es re-autenticar nube local auth y terminar`() {
        assertEquals(Step.ERASE_REMOTE, AccountDeletionPlan.nextStepAfterSuccess(Step.REAUTHENTICATE))
        assertEquals(Step.WIPE_LOCAL, AccountDeletionPlan.nextStepAfterSuccess(Step.ERASE_REMOTE))
        assertEquals(Step.DELETE_AUTH, AccountDeletionPlan.nextStepAfterSuccess(Step.WIPE_LOCAL))
        assertEquals(Step.DONE, AccountDeletionPlan.nextStepAfterSuccess(Step.DELETE_AUTH))
        assertEquals(Step.DONE, AccountDeletionPlan.nextStepAfterSuccess(Step.DONE))
    }

    @Test
    fun `un fallo de auth por sesion vieja pide volver a iniciar sesion`() {
        assertEquals(
            FailureAction.ASK_RECENT_LOGIN,
            AccountDeletionPlan.onFailure(
                Step.DELETE_AUTH,
                AccountDeletionFailure.ERROR_REQUIRES_RECENT_LOGIN,
            ),
        )
    }

    @Test
    fun `un fallo de auth por otra causa cierra la sesion igualmente`() {
        // Los datos YA se borraron: no se puede quedar la app con sesión abierta y sin datos. Por eso
        // DELETE_AUTH no devuelve ABORT_WITH_MESSAGE (que significa "no se borró nada"), sino
        // SIGN_OUT_AND_MESSAGE.
        assertEquals(FailureAction.SIGN_OUT_AND_MESSAGE, AccountDeletionPlan.onFailure(Step.DELETE_AUTH, null))
        assertEquals(FailureAction.SIGN_OUT_AND_MESSAGE, AccountDeletionPlan.onFailure(Step.DELETE_AUTH, "network-error"))
    }

    @Test
    fun `un fallo antes de borrar el usuario aborta sin seguir`() {
        // La re-autenticación cancelada, la nube caída y el disco lleno abortan igual: nunca se sigue
        // adelante tras un fallo, que es exactamente cómo se llegó al estado "sesión abierta y sin datos".
        assertEquals(FailureAction.ABORT_WITH_MESSAGE, AccountDeletionPlan.onFailure(Step.REAUTHENTICATE, null))
        assertEquals(FailureAction.ABORT_WITH_MESSAGE, AccountDeletionPlan.onFailure(Step.ERASE_REMOTE, null))
        assertEquals(FailureAction.ABORT_WITH_MESSAGE, AccountDeletionPlan.onFailure(Step.WIPE_LOCAL, null))
    }

    @Test
    fun `con marca de borrado pendiente se retoma en borrar el usuario de auth`() {
        // La parte destructiva ya ocurrió (o el proceso murió a mitad): lo único que falta es borrar
        // el usuario de Auth. No se crea ni se adopta ningún negocio.
        assertEquals(Step.DELETE_AUTH, AccountDeletionPlan.resumeStep(hasPendingDeletion = true))
    }

    @Test
    fun `sin marca de borrado pendiente no hay nada que retomar`() {
        assertEquals(null, AccountDeletionPlan.resumeStep(hasPendingDeletion = false))
    }
}
