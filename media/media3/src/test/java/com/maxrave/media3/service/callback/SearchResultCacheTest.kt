package com.maxrave.media3.service.callback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultCacheTest {
    @Test
    fun overlappingQueriesKeepTheirOwnResults() {
        val cache = SearchResultCache<String>()
        val first = cache.begin("first artist")
        val second = cache.begin("second artist")
        cache.complete("second artist", second, listOf("b"))
        cache.complete("first artist", first, listOf("a"))
        assertEquals(listOf("a"), cache.page("first artist", 0, 10))
        assertEquals(listOf("b"), cache.page("second artist", 0, 10))
    }

    @Test
    fun olderRequestCannotOverwriteTheSameQueryAfterARetry() {
        val cache = SearchResultCache<String>()
        val old = cache.begin("song")
        val newer = cache.begin("song")
        assertTrue(cache.complete("song", newer, listOf("new result")))
        assertFalse(cache.complete("song", old, listOf("stale result")))
        assertEquals(listOf("new result"), cache.page("song", 0, 10))
    }

    @Test
    fun failedOrPendingSearchCannotReusePreviousResults() {
        val cache = SearchResultCache<String>()
        cache.complete("song", cache.begin("song"), listOf("old result"))
        val retry = cache.begin("song")
        assertNull(cache.page("song", 0, 10))
        cache.complete("song", retry, emptyList())
        assertEquals(emptyList<String>(), cache.page("song", 0, 10))
        assertNull(cache.page("another song", 0, 10))
    }

    @Test
    fun pagesRespectTheLimitAndDoNotOverflow() {
        val cache = SearchResultCache<String>()
        cache.complete("song", cache.begin("song"), listOf("a", "b", "c"))
        assertEquals(listOf("a", "b"), cache.page("song", 0, 2))
        assertEquals(listOf("c"), cache.page("song", 1, 2))
        assertEquals(emptyList<String>(), cache.page("song", 2, 2))
        assertEquals(emptyList<String>(), cache.page("song", Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun evictionAlsoPreventsLateRequestsFromRepopulatingTheCache() {
        val cache = SearchResultCache<String>(capacity = 2)
        val evicted = cache.begin("a")
        cache.begin("b")
        cache.begin("c")
        assertFalse(cache.complete("a", evicted, listOf("late")))
        assertNull(cache.page("a", 0, 10))
    }

    @Test
    fun snapshotsAreNotChangedByTheSearchRepository() {
        val cache = SearchResultCache<String>()
        val items = mutableListOf("a")
        cache.complete("song", cache.begin("song"), items)
        items.clear()
        assertEquals(listOf("a"), cache.page("song", 0, 10))
        assertEquals("a", cache.find { it == "a" })
    }
}