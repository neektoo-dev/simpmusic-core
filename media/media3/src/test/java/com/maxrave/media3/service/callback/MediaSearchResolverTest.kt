package com.maxrave.media3.service.callback

import com.maxrave.domain.data.model.browse.album.AlbumBrowse
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.repository.*
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toSongEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class MediaSearchResolverTest {
    @Test
    fun exactSongAndArtistWinOverTheFirstCoverResult() = runBlocking {
        val search = searchSongs(listOf(song("cover", "One More Time", "Cover band"), song("original", "One More Time", "Daft Punk")))
        val result = resolver(search).resolve(MediaSearchRequest.create(null, title = "One More Time", artist = "Daft Punk"))
        assertEquals("original", result.queue.firstPlayedTrack!!.videoId)
        assertTrue(result.radio)
    }

    @Test
    fun artistRequestsDoNotPlayAnUnrelatedSongThatMentionsTheArtist() = runBlocking {
        val search = searchSongs(listOf(song("unrelated", "Daft Punk tribute", "Other band"), song("original", "One More Time", "Daft Punk")))
        val result = resolver(search).resolve(MediaSearchRequest.create(null, artist = "Daft Punk"))
        assertEquals(listOf("original"), result.queue.listTracks.map { it.videoId })
        assertFalse(result.radio)
    }

    @Test
    fun albumRequestLoadsItsTracksInAlbumOrderInsteadOfStartingASongRadio() = runBlocking {
        val artists = listOf(Artist("artist", "Daft Punk"))
        val search = proxy<SearchRepository> { name, args ->
            assertEquals("getSearchDataAlbum", name)
            assertEquals("Discovery Daft Punk", args[0])
            flowOf(Resource.Success(arrayListOf(AlbumsResult(artists, "album-id", "", "", false, "album", emptyList(), "Discovery", "Album", "2001"))))
        }
        val albumTracks = listOf(track("track-2", "First in album"), track("track-1", "Second in album"))
            .map { it.copy(isAvailable = false) } // The actual album parser uses this value.
        val albums = proxy<AlbumRepository> { name, args ->
            assertEquals("getAlbumData", name)
            assertEquals("album-id", args[0])
            flowOf(Resource.Success(AlbumBrowse(artists, "album-playlist", null, null, 360, emptyList(), "Discovery", 2, albumTracks, "Album", "2001")))
        }
        val result = resolver(search, albums).resolve(MediaSearchRequest.create(null, album = "Discovery", artist = "Daft Punk"))
        assertEquals(listOf("track-2", "track-1"), result.queue.listTracks.map { it.videoId })
        assertEquals(PlaylistType.ALBUM, result.queue.playlistType)
        assertFalse(result.radio)
    }

    @Test
    fun networkErrorsRemainErrorsInsteadOfPlayingStaleSearchData() = runBlocking {
        val search = proxy<SearchRepository> { _, _ -> flowOf(Resource.Error<ArrayList<SongsResult>>("Offline")) }
        try {
            resolver(search).resolve(MediaSearchRequest.create("a song"))
            fail("Expected an error")
        } catch (_: IOException) {
            // The session maps this to a useful error for the assistant.
        }
    }

    @Test
    fun emptyResultsAreDifferentFromNetworkErrors() = runBlocking {
        try {
            resolver(searchSongs(emptyList())).resolve(MediaSearchRequest.create("missing"))
            fail("Expected no result")
        } catch (_: MediaSearchNotFound) {
        }
    }

    @Test
    fun savedFavoritesAndDownloadsRemainPlayableWithOldAvailabilityMetadata() = runBlocking {
        val saved = track("saved-id", "Saved title").copy(
            isAvailable = false, thumbnails = listOf(Thumbnail(120, "https://example.com/cover.jpg", 120)),
        )
        val resolver = MediaSearchResolver(
            proxy { name, _ -> error("Unexpected online search: $name") },
            proxy { name, _ ->
                check(name in listOf("getLikedSongs", "getDownloadedSongs"))
                flowOf(listOf(saved.toSongEntity(), saved.toSongEntity().copy(videoId = "")))
            },
            proxy { name, _ -> error(name) }, proxy { name, _ -> error(name) }, proxy { name, _ -> error(name) },
            "views",
        )
        for (request in listOf(
            MediaSearchRequest.create("mes favoris"),
            MediaSearchRequest.create("mes téléchargements"),
            MediaSearchRequest.create(null, title = "Saved title"),
        )) {
            val result = resolver.resolve(request)
            assertEquals(listOf(saved.videoId), result.queue.listTracks.map { it.videoId })
            assertEquals(saved.videoId, result.queue.firstPlayedTrack!!.videoId)
        }
    }

    private fun resolver(
        search: SearchRepository,
        albums: AlbumRepository = proxy { name, _ -> error(name) },
    ): MediaSearchResolver = MediaSearchResolver(
        search,
        proxy { name, _ ->
            assertEquals("getDownloadedSongs", name)
            flowOf(emptyList<com.maxrave.domain.data.entities.SongEntity>())
        },
        proxy { name, _ -> error(name) },
        proxy { name, _ -> error(name) },
        albums,
        "views",
    )

    private fun searchSongs(songs: List<SongsResult>): SearchRepository = proxy { name, _ ->
        assertEquals("getSearchDataSong", name)
        flowOf(Resource.Success(ArrayList(songs)))
    }

    private fun song(id: String, title: String, artist: String) = SongsResult(
        null, listOf(Artist("artist", artist)), "", "3:00", 180, null, false, "song", emptyList(), title, id, "MUSIC_VIDEO_TYPE_ATV", "2001",
    )

    private fun track(id: String, title: String) = Track(
        null, listOf(Artist("artist", "Daft Punk")), "3:00", 180, true, false, null, emptyList(), title, id, "MUSIC_VIDEO_TYPE_ATV", null, null, "song",
    )

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
            when (method.name) {
                "toString" -> T::class.java.simpleName
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                else -> call(method.name, args ?: emptyArray())
            }
        } as T
}
