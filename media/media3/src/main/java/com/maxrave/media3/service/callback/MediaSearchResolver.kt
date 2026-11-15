package com.maxrave.media3.service.callback

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toListTrack
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.IOException

internal class MediaSearchNotFound : Exception()

internal data class ResolvedMediaSearch(val queue: QueueData.Data, val radio: Boolean = false)

internal class MediaSearchResolver(
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val albumRepository: AlbumRepository,
    private val viewLabel: String,
    private val favoritesLabel: String = "Favorites",
    private val downloadsLabel: String = "Downloaded",
) {
    suspend fun resolve(request: MediaSearchRequest): ResolvedMediaSearch = withTimeout(20_000L) {
        when (request.target) {
            MediaSearchTarget.RESUME -> throw MediaSearchNotFound() // Handled without replacing the active queue.
            MediaSearchTarget.FAVORITES -> queue(songRepository.getLikedSongs().first().map { it.toTrack() }, favoritesLabel)
            MediaSearchTarget.DOWNLOADED -> queue(songRepository.getDownloadedSongs().first().orEmpty().map { it.toTrack() }, downloadsLabel)
            MediaSearchTarget.PLAYLIST -> playlist(request)
            MediaSearchTarget.ALBUM -> album(request)
            MediaSearchTarget.ARTIST -> artist(request)
            MediaSearchTarget.GENRE -> {
                val results = searchRepository.getSearchDataFeaturedPlaylist(request.query).value()
                val result = results.firstOrNull() ?: throw MediaSearchNotFound()
                remotePlaylist(result.browseId)
            }
            MediaSearchTarget.SONG, MediaSearchTarget.ANY -> song(request)
        }
    }

    private suspend fun song(request: MediaSearchRequest): ResolvedMediaSearch {
        // Explicit local titles also work offline. Do not use a loose substring here:
        // a downloaded remix must not take precedence over the requested original.
        val local = songRepository.getDownloadedSongs().first().orEmpty().map { it.toTrack() }
        val exactLocal = local.firstOrNull {
            mediaMatchScore(request.query, it.title) == 100 &&
                (request.artist.isBlank() || mediaMatchScore(request.artist, artistNames(it)) > 0) &&
                (request.album.isBlank() || mediaMatchScore(request.album, it.album?.name.orEmpty()) == 100)
        }
        val track = exactLocal ?: searchRepository.getSearchDataSong(request.searchText).value().toListTrack()
            .filter { it.videoId.isNotBlank() }
            .maxByOrNull {
                mediaMatchScore(request.query, it.title) * 2 + mediaMatchScore(request.query, "${it.title} ${artistNames(it)}") +
                    mediaMatchScore(request.artist, artistNames(it)) + mediaMatchScore(request.album, it.album?.name.orEmpty())
            } ?: throw MediaSearchNotFound()
        return queue(listOf(track), track.title, "RDAMVM${track.videoId}", radio = true)
    }

    private suspend fun playlist(request: MediaSearchRequest): ResolvedMediaSearch {
        val local = localPlaylistRepository.getAllLocalPlaylists().first()
            .firstOrNull { mediaMatchScore(request.query, it.title) == 100 }
        if (local != null) {
            return queue(localPlaylistRepository.getFullPlaylistTracks(local.id).map { it.toTrack() }, local.title)
        }
        val saved = playlistRepository.getLikedPlaylists().first()
            .firstOrNull { mediaMatchScore(request.query, it.title) == 100 }
        val savedIds = saved?.tracks.orEmpty()
        if (saved != null && savedIds.isNotEmpty()) {
            val byId = songRepository.getSongsByListVideoId(savedIds).first().associateBy { it.videoId }
            val tracks = savedIds.mapNotNull { byId[it]?.toTrack() }
            if (tracks.size == savedIds.size) return queue(tracks, saved.title, saved.id, PlaylistType.PLAYLIST)
        }
        val found = searchRepository.getSearchDataPlaylist(request.query).value()
            .maxByOrNull { mediaMatchScore(request.query, it.title) } ?: throw MediaSearchNotFound()
        return remotePlaylist(found.browseId)
    }

    private suspend fun remotePlaylist(id: String): ResolvedMediaSearch {
        val (data, continuation) = playlistRepository.getPlaylistData(id, viewLabel).value()
        return queue(data.tracks, data.title, data.id, PlaylistType.PLAYLIST, continuation = continuation)
    }

    private suspend fun album(request: MediaSearchRequest): ResolvedMediaSearch {
        val found = searchRepository.getSearchDataAlbum(request.searchText).value().maxByOrNull {
            mediaMatchScore(request.query, it.title) * 2 + mediaMatchScore(request.artist, it.artists.joinToString(" ") { a -> a.name })
        } ?: throw MediaSearchNotFound()
        val data = albumRepository.getAlbumData(found.browseId).value()
        return queue(data.tracks, data.title, data.audioPlaylistId, PlaylistType.ALBUM)
    }

    private suspend fun artist(request: MediaSearchRequest): ResolvedMediaSearch {
        val songs = searchRepository.getSearchDataSong(request.query).value().toListTrack()
            .filter { mediaMatchScore(request.query, artistNames(it)) > 0 }
        // An artist request should not play an unrelated title that merely mentions the name.
        return queue(songs, request.query)
    }

    private fun queue(
        tracks: List<Track>,
        title: String,
        id: String? = null,
        type: PlaylistType = PlaylistType.LOCAL_PLAYLIST,
        radio: Boolean = false,
        continuation: String? = null,
    ): ResolvedMediaSearch {
        // isAvailable is not authoritative: album/playlist imports also set it to false
        // for songs that play on the phone. Let the shared stream/cache path resolve them.
        val playable = tracks.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }
        if (playable.isEmpty()) throw MediaSearchNotFound()
        return ResolvedMediaSearch(
            QueueData.Data(playable, playable.first(), id, title, if (radio) PlaylistType.RADIO else type, continuation),
            radio,
        )
    }

    private fun artistNames(track: Track): String = track.artists.orEmpty().joinToString(" ") { it.name }

    private suspend fun <T> Flow<Resource<T>>.value(): T {
        val result = first()
        if (result !is Resource.Success) throw IOException("Media catalogue unavailable")
        return result.data ?: throw MediaSearchNotFound()
    }
}
