package com.cuentivo.app.ai

/**
 * Envoltorios de sólo lectura para las listas y conjuntos del contrato de IA.
 *
 * `java.util.Collections.unmodifiableList` no existe en común, y `toList()` **no** lo sustituye: en
 * la JVM devuelve un `ArrayList`, así que un cast a `MutableList` vuelve a permitir `add` y la
 * inmutabilidad que el contrato promete se cae. Lo que se prueba en `AiContractsTest` es justamente
 * eso —que mutar por un cast lance `UnsupportedOperationException`—, así que el envoltorio tiene que
 * ser de verdad un `MutableList` que se niega.
 */
internal fun <T> unmodifiableList(values: List<T>): List<T> = UnmodifiableList(values)

internal fun <T> unmodifiableSet(values: Set<T>): Set<T> = UnmodifiableSet(values)

internal fun <K, V> unmodifiableMap(values: Map<K, V>): Map<K, V> = UnmodifiableMap(values)

private class UnmodifiableMap<K, V>(private val delegate: Map<K, V>) : AbstractMutableMap<K, V>() {
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> =
        UnmodifiableSet(delegate.entries.mapTo(LinkedHashSet()) { ReadOnlyEntry(it.key, it.value) })

    override val size: Int get() = delegate.size

    override fun containsKey(key: K): Boolean = delegate.containsKey(key)

    override fun get(key: K): V? = delegate[key]

    override fun put(key: K, value: V): V? = throw UnsupportedOperationException("This map is read-only")

    override fun remove(key: K): V? = throw UnsupportedOperationException("This map is read-only")

    override fun clear(): Unit = throw UnsupportedOperationException("This map is read-only")
}

private class ReadOnlyEntry<K, V>(
    override val key: K,
    override val value: V,
) : MutableMap.MutableEntry<K, V> {
    override fun setValue(newValue: V): V = throw UnsupportedOperationException("This map is read-only")

    override fun equals(other: Any?): Boolean =
        other is Map.Entry<*, *> && other.key == key && other.value == value

    override fun hashCode(): Int = key.hashCode() xor value.hashCode()
}

private class UnmodifiableList<T>(private val delegate: List<T>) : AbstractMutableList<T>() {
    override val size: Int get() = delegate.size

    override fun get(index: Int): T = delegate[index]

    override fun add(index: Int, element: T): Unit = throw UnsupportedOperationException("This list is read-only")

    override fun removeAt(index: Int): T = throw UnsupportedOperationException("This list is read-only")

    override fun set(index: Int, element: T): T = throw UnsupportedOperationException("This list is read-only")
}

private class UnmodifiableSet<T>(private val delegate: Set<T>) : AbstractMutableSet<T>() {
    override val size: Int get() = delegate.size

    override fun iterator(): MutableIterator<T> {
        val inner = delegate.iterator()
        return object : MutableIterator<T> {
            override fun hasNext(): Boolean = inner.hasNext()
            override fun next(): T = inner.next()
            override fun remove(): Unit = throw UnsupportedOperationException("This set is read-only")
        }
    }

    override fun add(element: T): Boolean = throw UnsupportedOperationException("This set is read-only")
}
