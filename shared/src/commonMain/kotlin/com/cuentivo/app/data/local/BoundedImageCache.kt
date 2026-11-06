package com.cuentivo.app.data.local

/**
 * A byte-budgeted LRU cache with no Android dependency, so its eviction rules are plain common tests.
 *
 * Entries are evicted oldest-first (recency, so both a [get] and a [put] over an existing key refresh
 * it) until the total byte count fits [maxBytes]. An entry that alone exceeds the budget is dropped
 * instead of being kept and immediately evicting everything else — a bitmap that cannot fit is not
 * worth holding.
 *
 * The map is insertion-ordered on purpose: `LinkedHashMap` is final in Kotlin/Native and does not
 * expose the access-order constructor there, so recency is kept by removing and re-inserting.
 */
class BoundedImageCache<V>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    /** Insertion-ordered: iteration order is least-recently-used first. */
    private val entries = LinkedHashMap<String, V>()
    private var totalBytes = 0L

    fun put(key: String, value: V) {
        // Replacing a key must move it to the end: an insertion-ordered map keeps the old position,
        // which would leave a refreshed entry looking like the eldest.
        remove(key)
        if (sizeOf(value) > maxBytes) return
        entries[key] = value
        totalBytes += sizeOf(value)
        trimToBudget()
    }

    fun get(key: String): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    fun remove(key: String) {
        entries.remove(key)?.let { totalBytes -= sizeOf(it) }
    }

    /** The keys in LRU order (eldest first). Exposed for the eviction tests. */
    fun keys(): List<String> = entries.keys.toList()

    fun sizeBytes(): Long = totalBytes

    private fun trimToBudget() {
        while (totalBytes > maxBytes && entries.isNotEmpty()) {
            remove(entries.keys.first())
        }
    }
}
