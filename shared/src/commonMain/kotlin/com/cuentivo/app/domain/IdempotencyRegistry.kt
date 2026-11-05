package com.cuentivo.app.domain

class IdempotencyRegistry<T> {
    private val results = mutableMapOf<String, T>()

    fun execute(key: String, operation: () -> T): T {
        require(key.isNotBlank()) { "Operation key cannot be blank." }
        return results.getOrPut(key, operation)
    }
}
