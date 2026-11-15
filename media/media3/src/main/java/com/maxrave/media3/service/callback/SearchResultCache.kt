package com.maxrave.media3.service.callback

/** Bounded snapshots: overlapping searches must never return another query's tracks. */
internal class SearchResultCache<T>(
    private val capacity: Int = 8,
) {
    private data class Entry<T>(val requestId: Long, val items: List<T>?)

    private val entries = linkedMapOf<String, Entry<T>>()
    private var nextRequestId = 0L

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun begin(query: String): Long {
        val requestId = ++nextRequestId
        entries.remove(query)
        entries[query] = Entry(requestId, null)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return requestId
    }

    @Synchronized
    fun complete(query: String, requestId: Long, items: List<T>): Boolean {
        if (entries[query]?.requestId != requestId) return false
        entries[query] = Entry(requestId, items.toList())
        return true
    }

    @Synchronized
    fun page(query: String, page: Int, pageSize: Int): List<T>? {
        require(page >= 0 && pageSize > 0)
        val items = entries[query]?.items ?: return null
        // Hosts may request Int.MAX_VALUE items; do the arithmetic without Int overflow.
        val start = page.toLong() * pageSize
        if (start >= items.size) return emptyList()
        return items.subList(start.toInt(), minOf(start + pageSize, items.size.toLong()).toInt()).toList()
    }

    @Synchronized
    fun find(predicate: (T) -> Boolean): T? =
        entries.values.toList().asReversed().firstNotNullOfOrNull { entry ->
            entry.items?.firstOrNull(predicate)
        }
}