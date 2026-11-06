package com.cuentivo.app.data.local

/**
 * A byte-budgeted LRU cache with no Android dependency, so its eviction rules are plain JVM tests.
 *
 * Entries are evicted oldest-first (access order, so a [get] refreshes recency) until the total
 * byte count fits [maxBytes]. An entry that alone exceeds the budget is dropped instead of being
 * kept and immediately evicting everything else — a bitmap that cannot fit is not worth holding.
 */
class BoundedImageCache<V>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    /** Access-ordered: iteration order is least-recently-used first. */
    private val entries = object : LinkedHashMap<String, V>(0, 0.75f, true) {}
    private var totalBytes = 0L

    fun put(key: String, value: V) {
        if (sizeOf(value) > maxBytes) {
            remove(key)
            return
        }
        entries.put(key, value)?.let { totalBytes -= sizeOf(it) }
        totalBytes += sizeOf(value)
        trimToBudget()
    }

    fun get(key: String): V? = entries[key]

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
