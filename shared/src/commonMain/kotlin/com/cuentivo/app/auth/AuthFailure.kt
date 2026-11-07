package com.cuentivo.app.auth

enum class AuthFailureKind { USER_COLLISION, RECENT_LOGIN_REQUIRED, OTHER }

class AuthFailure(val kind: AuthFailureKind, val code: String?)
