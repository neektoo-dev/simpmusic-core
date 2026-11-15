package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in smoke test against the real catalogue, without account credentials. */
class VoiceCatalogueLiveTest {
    @Test
    fun aSpokenSongQueryCanFindAPlayableYouTubeMusicTrack() = runBlocking {
        assumeTrue("Set SIMPMUSIC_LIVE_SEARCH_TEST=1 to contact YouTube Music", System.getenv("SIMPMUSIC_LIVE_SEARCH_TEST") == "1")
        val youtube = YouTube().apply { locale = YouTubeLocale("FR", "fr") }
        val started = System.nanoTime()
        val songs = withTimeout(20_000L) {
            var result = youtube.search("One More Time Daft Punk", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            val items = result.items.toMutableList()
            repeat(2) {
                val next = result.continuation ?: return@repeat
                result = youtube.searchContinuation(next).getOrThrow()
                items.addAll(result.items)
            }
            // The production song parser expects every item of these filtered pages to be a song.
            assertTrue(items.all { it is SongItem })
            items.filterIsInstance<SongItem>()
        }
        println("Catalogue search: ${songs.size} songs in ${(System.nanoTime() - started) / 1_000_000} ms")
        songs.take(3).forEach { println("${it.id}: ${it.title}") }
        assertTrue(songs.any { it.id.isNotBlank() && it.title.contains("One More Time", ignoreCase = true) })
    }
}