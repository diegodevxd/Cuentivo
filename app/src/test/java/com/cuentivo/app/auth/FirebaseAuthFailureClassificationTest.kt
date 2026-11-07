package com.cuentivo.app.auth

import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirebaseAuthFailureClassificationTest {

    @Test
    fun `user collision is classified and keeps its code`() {
        val failure = classifyFirebaseAuthFailure(FirebaseAuthUserCollisionException("c1", "m"))

        assertEquals(AuthFailureKind.USER_COLLISION, failure.kind)
        assertEquals("c1", failure.code)
    }

    @Test
    fun `recent login required is classified and keeps its code`() {
        val failure = classifyFirebaseAuthFailure(FirebaseAuthRecentLoginRequiredException("c2", "m"))

        assertEquals(AuthFailureKind.RECENT_LOGIN_REQUIRED, failure.kind)
        assertEquals("c2", failure.code)
    }

    @Test
    fun `another firebase auth exception is other with its code`() {
        val failure = classifyFirebaseAuthFailure(FirebaseAuthException("c3", "m"))

        assertEquals(AuthFailureKind.OTHER, failure.kind)
        assertEquals("c3", failure.code)
    }

    @Test
    fun `a non firebase error is other without a code`() {
        val failure = classifyFirebaseAuthFailure(IllegalStateException("m"))

        assertEquals(AuthFailureKind.OTHER, failure.kind)
        assertNull(failure.code)
    }
}
