package com.maxrave.media3.service.callback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.Rating
import androidx.media3.common.ThumbRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_TIMELINE
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.common.Config
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toListTrack
import com.maxrave.domain.utils.toPlaylistEntity
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.exoplayer.toMedia3MediaItem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.carPinnedPlaylists
import com.maxrave.media3.service.CarBrowsePresentation
import com.maxrave.media3.service.CarBrowseControls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

private const val TAG = "AndroidAuto"

@UnstableApi
internal class SimpleMediaSessionCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val homeRepository: HomeRepository,
    private val streamRepository: StreamRepository,
    private val albumRepository: AlbumRepository,
    private val dataStoreManager: DataStoreManager? = null,
) : MediaLibrarySession.Callback {
    var onCarBrowseControlsSupported: (Boolean) -> Unit = {}
    var toggleLike: () -> Unit = {
        mediaPlayerHandler.toggleLike()
    }
    var toggleRadio: () -> Unit = {
        mediaPlayerHandler.toggleRadio()
    }
    private val searchResults = SearchResultCache<Track>()
    private var mediaRequestGeneration = 0L
    private var searchPlaybackJob: Job? = null

    private val mediaSearchResolver = MediaSearchResolver(
        searchRepository, songRepository, localPlaylistRepository, playlistRepository, albumRepository, context.getString(R.string.view_count),
        context.getString(R.string.favorites), context.getString(R.string.downloaded),
    )

    private data class BrowsePlayback(val result: ResolvedMediaSearch, val index: Int)

    private data class PendingSearchPlayback(
        val generation: Long,
        val controller: MediaSession.ControllerInfo,
        val playback: BrowsePlayback?,
        var scheduled: Boolean = false,
        var playWhenReady: Boolean = false,
    )

    private var pendingSearchPlayback: PendingSearchPlayback? = null
    private val listHomeItem = mutableListOf<HomeItem>()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        Logger.w(TAG, "onConnect: ${controller.packageName}")
        val sessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                // Item actions remain separate from the one-button playback strip.
                .apply { CarBrowseControls.commandButtons(context).forEach { add(it.sessionCommand!!) } }
                // Add custom commands
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.LIKE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.REPEAT, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.RADIO, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.SHUFFLE, Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN, Bundle()))
                .build()
        return MediaSession.ConnectionResult
            .AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(
                Player.Commands
                    .Builder()
                    .addAllCommands()
                    .remove(COMMAND_GET_TIMELINE)
                    .build(),
            ).build()
    }

    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int,
    ): Int {
        Logger.i(TAG, "Player command received: command=$playerCommand, caller=${controller.packageName}")
        // Media3 forwards this to DelegatingForwardingPlayer. Executing it here too skips twice.
        if (playerCommand == Player.COMMAND_STOP) {
            mediaRequestGeneration++
            searchPlaybackJob?.cancel()
            pendingSearchPlayback = null
        }
        return super.onPlayerCommandRequest(session, controller, playerCommand)
    }

    @UnstableApi
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val browseItemId = args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)
        if (browseItemId != null && !CarBrowseControls.allows(
                browseItemId, customCommand.customAction, mediaPlayerHandler.nowPlayingState.value.songEntity,
            )
        ) {
            // Browse rows describe a particular song. Do not apply a delayed tap to its successor.
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        }
        when (customCommand.customAction) {
            CarBrowseControls.LIKE_ADD, CarBrowseControls.LIKE_REMOVE -> {
                if (browseItemId == null) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                mediaPlayerHandler.like(customCommand.customAction == CarBrowseControls.LIKE_ADD)
            }
            CarBrowseControls.SHUFFLE_ON, CarBrowseControls.SHUFFLE_OFF -> {
                if (browseItemId == null) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val wanted = customCommand.customAction == CarBrowseControls.SHUFFLE_ON
                scope.launch {
                    if (mediaPlayerHandler.controlState.value.isShuffle != wanted) mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle)
                }
            }
            CarBrowseControls.REPEAT_ONE, CarBrowseControls.REPEAT_ALL, CarBrowseControls.REPEAT_OFF -> {
                if (browseItemId == null) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                session.player.repeatMode = when (customCommand.customAction) {
                    CarBrowseControls.REPEAT_ONE -> Player.REPEAT_MODE_ONE
                    CarBrowseControls.REPEAT_ALL -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            MEDIA_CUSTOM_COMMAND.LIKE -> {
                toggleLike()
            }

            MEDIA_CUSTOM_COMMAND.REPEAT -> {
                session.player.repeatMode =
                    when (session.player.repeatMode) {
                        ExoPlayer.REPEAT_MODE_OFF -> ExoPlayer.REPEAT_MODE_ONE
                        ExoPlayer.REPEAT_MODE_ONE -> ExoPlayer.REPEAT_MODE_ALL
                        ExoPlayer.REPEAT_MODE_ALL -> ExoPlayer.REPEAT_MODE_OFF
                        else -> ExoPlayer.REPEAT_MODE_OFF
                    }
            }

            MEDIA_CUSTOM_COMMAND.RADIO -> {
                toggleRadio()
            }

            MEDIA_CUSTOM_COMMAND.SHUFFLE -> {
                scope.launch {
                    mediaPlayerHandler.onPlayerEvent(PlayerEvent.Shuffle)
                }
            }

            MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN -> {
                return Futures.immediateFuture(
                    SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putParcelable(MEDIA_CUSTOM_COMMAND.KEY_PLATFORM_TOKEN, session.platformToken)
                        },
                    ),
                )
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /**
     * Playback resumption: a `play` command that arrives with nothing loaded in the player.
     *
     * This is how a Samsung Routine (and any headset or Bluetooth `play` button, and the system
     * resumption chip) reaches the app when it is not running: `MediaButtonReceiver` starts
     * [com.maxrave.media3.service.SimpleMediaService] with `startForegroundService()`, Media3
     * sees a play request against an empty player, and calls this.
     *
     * Leaving it unimplemented is what caused a background ANR. The default returns
     * `Futures.immediateFailedFuture(UnsupportedOperationException())`; Media3 logs that and
     * still calls `prepare() + play()`, but on an empty player that lands in ENDED rather than
     * BUFFERING, so `MediaNotificationManager` never considers the session user-engaged and
     * never calls `startForeground()`. Nothing is blocked - the main thread sits in
     * `nativePollOnce`, which is exactly what the ANR trace shows - the service simply misses
     * the foreground-start deadline and the system tears it down with "Context
     * .startForegroundService() did not then call Service.startForeground()".
     *
     * The queue is loaded through [MediaPlayerHandler] instead of being handed back to Media3.
     * The session player is a single-item delegate in front of `CrossfadeExoPlayerAdapter`,
     * which owns the real playlist, so a playlist pushed in through `setMediaItems` here would
     * desync the two. Media3 first finishes its transport request with an empty list;
     * the queue owner then restores the actual items on the next main-thread turn.
     *
     * Ordering matters: Media3 runs its own success handling inline the moment this future
     * completes, so the restore is deliberately started on a later main-thread message. Priming
     * the player first would have it cleared right back out.
     *
     * `isForPlayback == false` is a different question - Android Auto asking for the recent item
     * to show after a device boot - and is left answered the way the default answered it, with
     * nothing. A raw song `MediaItem` would show up there as an entry [onSetMediaItems] cannot
     * parse (it wants a `song/<id>` style media id), i.e. one that does nothing when tapped.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Logger.w(TAG, "onPlaybackResumption: ${controller.packageName}, isForPlayback=$isForPlayback")
        // A Media3 set/prepare/play search batch temporarily has an empty delegate. Its
        // resolved search will be loaded when the interaction finishes; do not restore an
        // unrelated saved queue while that batch is still being applied.
        if (isForPlayback) {
            val pending = pendingSearchPlayback
            if (pending?.controller == controller) pending.playWhenReady = true else resumeSavedQueue()
        }
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET),
        )
    }

    private fun resumeSavedQueue() {
        scope.launch {
            if (!mediaPlayerHandler.restoreQueueAndPlay()) {
                Logger.w(TAG, "onPlaybackResumption: nothing to resume")
            }
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (session.isAutoCompanionController(browser) || session.isAutomotiveController(browser)) {
            onCarBrowseControlsSupported(browser.maxCommandsForMediaItems > 0)
            Logger.i(TAG, "Car browse actions: limit=${browser.maxCommandsForMediaItems}, caller=${browser.packageName}")
        }
        return Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                MediaLibraryService.LibraryParams
                    .Builder()
                    .setExtras(Bundle(params?.extras ?: Bundle.EMPTY).apply {
                        putBoolean(MEDIA_SEARCH_SUPPORTED, true)
                        putAll(CarBrowsePresentation.styles(grid = true))
                    })
                    .build(),
            ),
        )
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        Logger.i(TAG, "Browse search received: caller=${browser.packageName}, queryLength=${query.length}")
        val requestId = searchResults.begin(query)
        return scope.future {
            try {
                val tracks = searchTracks(query.trim())
                if (searchResults.complete(query, requestId, tracks)) {
                    Logger.i(TAG, "Browse search resolved: tracks=${tracks.size}, caller=${browser.packageName}")
                    session.notifySearchResultChanged(browser, query, tracks.size, params)
                }
                LibraryResult.ofVoid()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Clear the failed query, rather than displaying an earlier search's songs.
                if (searchResults.complete(query, requestId, emptyList())) {
                    session.notifySearchResultChanged(browser, query, 0, params)
                }
                Logger.e(TAG, "Media search failed", e)
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }
    }

    @UnstableApi
    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            if (page < 0 || pageSize <= 0) return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            val tracks = searchResults.page(query, page, pageSize)
                ?: return@future LibraryResult.ofError(SessionError.ERROR_INVALID_STATE)
            Logger.i(TAG, "Browse search results requested: page=$page, tracks=${tracks.size}, caller=${browser.packageName}")
            LibraryResult.ofItemList(
                tracks.map {
                    it.toMediaItemWithoutPath()
                },
                params,
            )
        }

    private suspend fun searchTracks(query: String): List<Track> {
        if (query.isBlank()) return emptyList()
        val result = withTimeoutOrNull(15_000L) { searchRepository.getSearchDataSong(query).lastOrNull() }
        currentCoroutineContext().ensureActive()
        if (result !is Resource.Success) throw IOException("Media search unavailable")
        return result.data?.toListTrack().orEmpty().filter { it.videoId.isNotBlank() }
    }

    @UnstableApi
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            if (page < 0 || pageSize <= 0) return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            if (CarBrowseControls.owns(parentId)) {
                val rows = controlRows(browser)
                val items = if (parentId == CarBrowseControls.ROOT) {
                    rows
                } else {
                    if (rows.none { it.mediaId == parentId }) return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    listOfNotNull(mediaPlayerHandler.nowPlayingState.value.songEntity?.toMediaItem(SONG))
                }
                val start = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
                return@future LibraryResult.ofItemList(items.drop(start).take(pageSize), params)
            }
            val rootExtras =
                Bundle().apply {
                    putBoolean(
                        MEDIA_SEARCH_SUPPORTED,
                        true,
                    )
                }
            val libraryParams =
                MediaLibraryService.LibraryParams
                    .Builder()
                    .setExtras(rootExtras)
                    .build()
            return@future LibraryResult.ofItemList(
                when (parentId) {
                    ROOT -> rootItems(browser)
                    LIBRARY -> libraryItems()

                    SONG -> {
                        songRepository
                            .getAllSongs(1000)
                            .first()
                            .sortedBy { it.inLibrary }
                            .map { it.toMediaItem(parentId) }
                    }

                    FAVORITE -> {
                        songRepository
                            .getLikedSongs()
                            .first()
                            .map { it.toMediaItem(parentId) }
                    }

                    DOWNLOADED -> {
                        songRepository
                            .getDownloadedSongs()
                            .first()
                            ?.map { it.toMediaItem(parentId) }
                            ?: emptyList()
                    }

                    ALBUM -> albumRepository.getAllAlbums(100).first().sortedByDescending { it.inLibrary }.map { album ->
                        browsableMediaItem("$ALBUM/${album.browseId}", album.title, album.artistName?.joinToString(", "),
                            album.thumbnails?.toUri() ?: drawableUri(R.drawable.baseline_album_24), MediaMetadata.MEDIA_TYPE_ALBUM)
                    }
                    PLAYLIST -> {
                        localPlaylistRepository
                            .getAllLocalPlaylists()
                            .first()
                            .sortedBy { it.inLibrary }
                            .map {
                                browsableMediaItem(
                                    "$PLAYLIST/${it.id}",
                                    it.title,
                                    "${it.tracks?.size ?: 0} ${context.getString(R.string.track)}",
                                    it.thumbnail?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                )
                            }
                    }

                    HOME -> carHomeItems()
                    RECENT -> songRepository.getAllSongs(100).first().filter { it.totalPlayTime > 0 }
                        .sortedByDescending { it.inLibrary }.take(20).map { it.toMediaItem(SONG) }
                    RECOMMENDATIONS -> {
                        val temp =
                            homeRepository
                                .getHomeData(
                                    viewString = context.getString(R.string.view_count),
                                    songString = context.getString(R.string.song),
                                ).lastOrNull()
                                ?.data
                        listHomeItem.clear()
                        listHomeItem.addAll(temp?.second ?: emptyList())
                        listHomeItem.map {
                            browsableMediaItem(
                                "$HOME/${it.title}",
                                it.title,
                                it.subtitle,
                                null,
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            )
                        }
                    }

                    else -> {
                        when {
                            parentId.startsWith("$HOME/") -> {
                                if (parentId.split("/").size == 2) {
                                    val homeItem =
                                        listHomeItem.find {
                                            it.title == parentId.split("/").getOrNull(1)
                                        }
                                    homeItem
                                        ?.contents
                                        ?.filter { it?.playlistId != null || it?.videoId != null }
                                        ?.mapNotNull {
                                            if (it?.playlistId != null) {
                                                browsableMediaItem(
                                                    id = "$HOME/${homeItem.title}/$PLAYLIST/${it.playlistId}",
                                                    title = it.title,
                                                    subtitle = it.description,
                                                    iconUri =
                                                        it.thumbnails
                                                            .lastOrNull()
                                                            ?.url
                                                            ?.toUri(),
                                                    mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                                )
                                            } else if (it?.videoId != null) {
                                                it
                                                    .toTrack()
                                                    .toSongEntity()
                                                    .toMediaItem("$HOME/${homeItem.title}/$SONG")
                                            } else {
                                                null
                                            }
                                        }
                                        ?: emptyList()
                                } else {
                                    val playlistId = parentId.split("/").getOrNull(3)
                                    if (playlistId != null) {
                                        val playlist =
                                            playlistRepository
                                                .getFullPlaylistData(playlistId, context.getString(R.string.view_count))
                                                .lastOrNull()
                                        if (playlist?.data?.tracks.isNullOrEmpty()) {
                                            emptyList()
                                        } else {
                                            playlist.data?.toPlaylistEntity()?.let { playlistRepository.insertAndReplacePlaylist(it) }
                                            playlist.data?.tracks?.map { track ->
                                                track
                                                    .toSongEntity()
                                                    .also {
                                                        songRepository.insertSong(it).first()
                                                    }.toMediaItem(parentId)
                                            } ?: emptyList()
                                        }
                                    } else {
                                        emptyList()
                                    }
                                }
                            }

                            parentId.startsWith("$ALBUM/") -> albumTracks(parentId.substringAfter('/')).map {
                                it.toMediaItemWithoutPath(parentId)
                            }
                            parentId.startsWith("$PLAYLIST/") -> {
                                val playlistId = parentId.split("/").getOrNull(1)
                                if (playlistId != null) {
                                    val playlist = localPlaylistRepository.getLocalPlaylist(playlistId.toLong())
                                        .first { it !is com.maxrave.domain.utils.LocalResource.Loading }.data
                                    if (playlist != null) {
                                        Logger.d(TAG, "onGetChildren: local playlist $playlistId")
                                        val tracks = playlist.tracks
                                        if (tracks.isNullOrEmpty()) {
                                            emptyList()
                                        } else {
                                            localPlaylistRepository.getFullPlaylistTracks(playlist.id)
                                                .map { it.toMediaItem(parentId) }
                                        }
                                    } else {
                                        emptyList()
                                    }
                                } else {
                                    emptyList()
                                }
                            }

                            else -> {
                                emptyList()
                            }
                        }
                    }
                },
                libraryParams,
            )
        }

    @UnstableApi
    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            if (CarBrowseControls.owns(mediaId)) {
                val item = if (mediaId == CarBrowseControls.ROOT && browser.maxCommandsForMediaItems > 0) {
                    CarBrowseControls.root(context)
                } else {
                    controlRows(browser).find { it.mediaId == mediaId }
                }
                return@future item?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
            if (mediaId == RESUME || mediaId == RECENT || mediaId == RECOMMENDATIONS) {
                return@future carHomeItems().find { it.mediaId == mediaId }?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
            if (mediaId.startsWith("$ALBUM/") && mediaId.count { it == '/' } == 1) {
                val album = albumRepository.getAlbum(mediaId.substringAfter('/')).first()
                    ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                return@future LibraryResult.ofItem(browsableMediaItem(mediaId, album.title, album.artistName?.joinToString(", "),
                    album.thumbnails?.toUri(), MediaMetadata.MEDIA_TYPE_ALBUM), null)
            }
            (rootItems(browser) + libraryItems()).find { it.mediaId == mediaId }?.let {
                return@future LibraryResult.ofItem(it, null)
            }
            val songId = mediaId.substringAfterLast('/')
            val track = songRepository.getSongById(songId).first()?.toTrack()
                ?: searchResults.find { it.videoId == songId }
                ?: streamRepository.getFullMetadata(songId).first().data
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItem(
                track.toMediaItemWithoutPath(path = null).buildUpon().setMediaId(mediaId).build(),
                null,
            )
        }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        if (parentId == HOME || (parentId == CarBrowseControls.ROOT && browser.maxCommandsForMediaItems > 0)) {
            Futures.immediateFuture(LibraryResult.ofVoid())
        } else {
            super.onSubscribe(session, browser, parentId, params)
        }

    private fun controlRows(browser: MediaSession.ControllerInfo): List<MediaItem> = CarBrowseControls.rows(
        context, mediaPlayerHandler.nowPlayingState.value.songEntity, mediaPlayerHandler.controlState.value,
        browser.maxCommandsForMediaItems,
    )

    @UnstableApi
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val generation = ++mediaRequestGeneration
        searchPlaybackJob?.cancel()
        pendingSearchPlayback = null
        if (mediaItems.isEmpty()) {
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET))
        }
        return scope.future {
            try {
                val item = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
                    ?: throw IllegalArgumentException("Invalid start index")
                val metadata = item.requestMetadata
                val voice = metadata.searchQuery != null || listOf(
                    MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.EXTRA_MEDIA_TITLE, MediaStore.EXTRA_MEDIA_ARTIST,
                    MediaStore.EXTRA_MEDIA_ALBUM, MediaStore.EXTRA_MEDIA_PLAYLIST, MediaStore.EXTRA_MEDIA_GENRE,
                ).any { !metadata.extras?.getString(it).isNullOrBlank() }
                Logger.i(TAG, "Media request received: caller=${controller.packageName}, search=$voice, uri=${metadata.mediaUri != null}")
                val request = if (voice) voiceRequest(item) else null
                Logger.i(TAG, "Media request type=${request?.target ?: "MEDIA_ID"}, queryLength=${request?.query?.length ?: 0}")
                val resume = item.mediaId == RESUME || request?.target == MediaSearchTarget.RESUME
                val playback = if (resume) null else withTimeout(20_000L) {
                    if (request != null) {
                        BrowsePlayback(mediaSearchResolver.resolve(request), 0)
                    } else {
                        resolveBrowsePlayback(item)
                    }
                }
                if (generation != mediaRequestGeneration) throw CancellationException("Media request superseded")
                pendingSearchPlayback = PendingSearchPlayback(generation, controller, playback)
                Logger.i(TAG, "Media request resolved: tracks=${playback?.result?.queue?.listTracks?.size ?: 0}, resume=$resume")
                // Media3 must receive the playable result of a successful search. Returning an
                // empty list clears the session and leaves assistants observing no selected media.
                // The handler synchronizes its own queue AFTER the set/prepare/play batch below.
                val current = mediaSession.player.currentMediaItem
                if (playback != null) {
                    val selected = playback.result.queue.firstPlayedTrack!!.toGenericMediaItem().toMedia3MediaItem()
                    Logger.i(TAG, "Playable search result returned to Media3: request=$generation")
                    MediaSession.MediaItemsWithStartPosition(listOf(selected), 0, C.TIME_UNSET)
                } else if (current != null) {
                    MediaSession.MediaItemsWithStartPosition(listOf(current), 0, mediaSession.player.currentPosition)
                } else {
                    MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                }
            } catch (e: TimeoutCancellationException) {
                if (generation == mediaRequestGeneration) {
                    reportMediaRequestError(mediaSession, R.string.media_search_unavailable, SessionError.ERROR_IO, e)
                }
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaSearchNotFound) {
                if (generation == mediaRequestGeneration) {
                    reportMediaRequestError(mediaSession, R.string.media_search_no_results, SessionError.ERROR_BAD_VALUE, e)
                }
                throw e
            } catch (e: Exception) {
                if (generation == mediaRequestGeneration) {
                    reportMediaRequestError(mediaSession, R.string.media_search_unavailable, SessionError.ERROR_IO, e)
                }
                throw e
            }
        }
    }

    private fun voiceRequest(item: MediaItem): MediaSearchRequest {
        val extras = item.requestMetadata.extras
        val focus = when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> MediaSearchTarget.SONG
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> MediaSearchTarget.ARTIST
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> MediaSearchTarget.ALBUM
            MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> MediaSearchTarget.PLAYLIST
            MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE -> MediaSearchTarget.GENRE
            else -> MediaSearchTarget.ANY
        }
        return MediaSearchRequest.create(
            item.requestMetadata.searchQuery,
            focus,
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_GENRE),
        )
    }

    private suspend fun resolveBrowsePlayback(item: MediaItem): BrowsePlayback {
        val path = item.mediaId.split('/')
        val songId = path.last()
        var playlistId: String? = null
        var title: String? = null
        var radio = false
        // Match phone playback: album/playlist parsers have persisted isAvailable=false
        // for valid songs. That metadata must not reject a row we advertised as playable.
        // The normal stream/cache resolver determines actual availability when loading it.
        val tracks = when (path.firstOrNull()) {
            SONG -> {
                val track = songRepository.getSongById(songId).first()?.toTrack()
                    ?: searchResults.find { it.videoId == songId }
                    ?: streamRepository.getFullMetadata(songId).first().data
                    ?: throw MediaSearchNotFound()
                radio = true
                playlistId = "RDAMVM$songId"
                title = track.title
                listOf(track)
            }
            FAVORITE -> {
                title = context.getString(R.string.favorites)
                songRepository.getLikedSongs().first().map { it.toTrack() }
            }
            DOWNLOADED -> {
                title = context.getString(R.string.downloaded)
                songRepository.getDownloadedSongs().first().orEmpty().map { it.toTrack() }
            }
            PLAYLIST -> {
                val id = path.getOrNull(1)?.toLongOrNull() ?: throw MediaSearchNotFound()
                title = localPlaylistRepository.getAllLocalPlaylists().first().find { it.id == id }?.title
                localPlaylistRepository.getFullPlaylistTracks(id).map { it.toTrack() }
            }
            ALBUM -> {
                val albumId = path.getOrNull(1) ?: throw MediaSearchNotFound()
                val album = albumRepository.getAlbum(albumId).first() ?: throw MediaSearchNotFound()
                title = album.title
                playlistId = album.audioPlaylistId
                albumTracks(albumId)
            }
            HOME -> when (path.getOrNull(2)) {
                SONG -> {
                    radio = true
                    playlistId = "RDAMVM$songId"
                    listHomeItem.find { it.title == path.getOrNull(1) }?.contents
                        .orEmpty().filterNotNull().filter { it.videoId != null }.map { it.toTrack() }
                }
                PLAYLIST -> {
                    playlistId = path.getOrNull(3) ?: throw MediaSearchNotFound()
                    val playlist = playlistRepository.getPlaylist(playlistId).first() ?: throw MediaSearchNotFound()
                    title = playlist.title
                    val ids = playlist.tracks.orEmpty()
                    val byId = songRepository.getSongsByListVideoId(ids).first().associateBy { it.videoId }
                    ids.mapNotNull { byId[it]?.toTrack() }
                }
                else -> throw MediaSearchNotFound()
            }
            else -> throw MediaSearchNotFound()
        }.filter { it.videoId.isNotBlank() }
        val index = tracks.indexOfFirst { it.videoId == songId }
        if (index < 0) throw MediaSearchNotFound()
        val queue = QueueData.Data(
            listTracks = tracks,
            firstPlayedTrack = tracks[index],
            playlistId = playlistId,
            playlistName = title,
            playlistType = when {
                radio -> PlaylistType.RADIO
                path.firstOrNull() == ALBUM -> PlaylistType.ALBUM
                else -> PlaylistType.LOCAL_PLAYLIST
            },
            continuation = null,
        )
        return BrowsePlayback(ResolvedMediaSearch(queue, radio), index)
    }

    override fun onPlayerInteractionFinished(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        playerCommands: Player.Commands,
    ) {
        val pending = pendingSearchPlayback ?: return
        if (pending.controller != controllerInfo || pending.scheduled ||
            (!playerCommands.contains(Player.COMMAND_SET_MEDIA_ITEM) && !playerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        ) return
        pending.scheduled = true
        pending.playWhenReady = pending.playWhenReady || playerCommands.contains(Player.COMMAND_PLAY_PAUSE)
        searchPlaybackJob = scope.launch(Dispatchers.Main) {
            if (pending.generation != mediaRequestGeneration) return@launch
            try {
                val playback = pending.playback
                if (playback == null) {
                    if (mediaPlayerHandler.player.currentMediaItem != null) {
                        if (pending.playWhenReady) mediaPlayerHandler.player.play()
                    } else if (pending.playWhenReady) {
                        if (!mediaPlayerHandler.restoreQueueAndPlay()) throw MediaSearchNotFound()
                    } else {
                        mediaPlayerHandler.mayBeRestoreQueue()
                    }
                } else {
                    mediaPlayerHandler.setQueueData(playback.result.queue)
                    val type = when {
                        !pending.playWhenReady -> Config.RECOVER_TRACK_QUEUE
                        playback.result.radio -> Config.SONG_CLICK
                        else -> Config.PLAYLIST_CLICK
                    }
                    mediaPlayerHandler.loadMediaItem(playback.result.queue.firstPlayedTrack!!, type, playback.index)
                    // prepareFromSearch must prepare the whole requested playlist without starting it.
                    if (type == Config.RECOVER_TRACK_QUEUE && !playback.result.radio) {
                        mediaPlayerHandler.loadPlaylistOrAlbum(playback.index)
                    }
                    if (pending.playWhenReady) mediaPlayerHandler.player.play()
                }
                Logger.i(TAG, "Media request applied: play=${pending.playWhenReady}, caller=${controllerInfo.packageName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportMediaRequestError(session, R.string.media_search_unavailable, SessionError.ERROR_IO, e)
            } finally {
                if (pendingSearchPlayback === pending) pendingSearchPlayback = null
            }
        }
    }

    private fun reportMediaRequestError(session: MediaSession, message: Int, code: Int, error: Exception) {
        Logger.e(TAG, "Media request failed: ${error.javaClass.simpleName}", error)
        // Broadcasting also updates the legacy platform playback state used by the assistant.
        session.sendError(SessionError(code, context.getString(message), Bundle.EMPTY))
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        rating: Rating,
    ): ListenableFuture<SessionResult> {
        val id = mediaPlayerHandler.player.currentMediaItem?.mediaId
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        return onSetRating(session, controller, id, rating)
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaId: String,
        rating: Rating,
    ): ListenableFuture<SessionResult> {
        val liked = when (rating) {
            is ThumbRating -> rating.isRated && rating.isThumbsUp
            is HeartRating -> rating.isRated && rating.isHeart
            else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        val songId = mediaId.substringAfterLast('/').removePrefix("Video")
        return scope.future {
            val current = mediaPlayerHandler.player.currentMediaItem?.mediaId?.removePrefix("Video")
            if (songRepository.getSongById(songId).first() == null) {
                return@future SessionResult(SessionError.ERROR_BAD_VALUE)
            }
            songRepository.updateLikeStatus(songId, if (liked) 1 else 0)
            if (songId == current) mediaPlayerHandler.like(liked)
            Logger.i(TAG, "Media rating applied: liked=$liked, caller=${controller.packageName}")
            SessionResult(SessionResult.RESULT_SUCCESS)
        }
    }

    private suspend fun albumTracks(id: String): List<Track> {
        val album = albumRepository.getAlbum(id).first() ?: throw MediaSearchNotFound()
        val ids = album.tracks.orEmpty()
        val byId = if (ids.isEmpty()) emptyMap() else songRepository.getSongsByListVideoId(ids).first().associateBy { it.videoId }
        if (ids.isNotEmpty() && ids.all { it in byId }) return ids.map { byId.getValue(it).toTrack() }
        val result = withTimeout(15_000) { albumRepository.getAlbumData(id).first() }
        return result.data?.tracks ?: throw MediaSearchNotFound()
    }

    internal suspend fun carHomeItemCount(): Int = carHomeItems().size

    private suspend fun carHomeItems(): List<MediaItem> = buildList {
        val current = mediaPlayerHandler.nowPlayingState.value.songEntity
        val savedId = dataStoreManager?.recentMediaId?.first().orEmpty().removePrefix("Video")
        val resumeSong = current ?: savedId.takeIf { it.isNotBlank() }?.let { songRepository.getSongById(it).first() }
        if (resumeSong != null) add(resumeSong.toMediaItem(SONG).buildUpon().setMediaId(RESUME)
            .setMediaMetadata(resumeSong.toMediaItem(SONG).mediaMetadata.buildUpon()
                .setTitle(context.getString(R.string.car_resume)).setSubtitle(resumeSong.title)
                .setExtras(CarBrowsePresentation.styles(group = context.getString(R.string.car_shortcuts))).build()).build())
        add(browsableMediaItem(RECENT, context.getString(R.string.car_recent), null, drawableUri(R.drawable.baseline_album_24)))
        add(browsableMediaItem(DOWNLOADED, context.getString(R.string.car_downloads), null, drawableUri(R.drawable.baseline_downloaded)))
        val pins = dataStoreManager?.carPinnedPlaylists()?.first().orEmpty()
        if (pins.isNotEmpty()) {
            val playlists = localPlaylistRepository.getAllLocalPlaylists().first().associateBy { it.id }
            pins.take(8).mapNotNull { playlists[it] }.forEach { playlist ->
                val item = browsableMediaItem("$PLAYLIST/${playlist.id}", playlist.title,
                    "${playlist.tracks?.size ?: 0} ${context.getString(R.string.track)}",
                    playlist.thumbnail?.toUri() ?: drawableUri(R.drawable.baseline_playlist_add_24), MediaMetadata.MEDIA_TYPE_PLAYLIST)
                add(item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                    .setExtras(CarBrowsePresentation.styles(group = context.getString(R.string.car_pinned), singleGrid = true)).build()).build())
            }
        }
        add(browsableMediaItem(RECOMMENDATIONS, context.getString(R.string.car_discover),
            context.getString(R.string.available_online), drawableUri(R.drawable.home_android_auto)))
    }

    private fun rootItems(browser: MediaSession.ControllerInfo): List<MediaItem> = buildList {
        add(browsableMediaItem(
            HOME, context.getString(R.string.home), context.getString(R.string.car_shortcuts),
            drawableUri(R.drawable.home_android_auto), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        ))
        if (browser.maxCommandsForMediaItems > 0) add(CarBrowseControls.root(context))
        add(libraryItems().first { it.mediaId == FAVORITE })
        // Android Auto displays at most four root tabs. Keep all collections under Library.
        add(browsableMediaItem(
            LIBRARY, context.getString(R.string.library), null, drawableUri(R.drawable.baseline_album_24),
            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        ))
    }

    private fun libraryItems(): List<MediaItem> = listOf(
        browsableMediaItem(ALBUM, context.getString(R.string.car_albums), null, drawableUri(R.drawable.baseline_album_24)),
        browsableMediaItem(
            SONG, context.getString(R.string.songs), null, drawableUri(R.drawable.baseline_album_24),
            MediaMetadata.MEDIA_TYPE_PLAYLIST,
        ),
        browsableMediaItem(
            FAVORITE, context.getString(R.string.favorites), null, drawableUri(R.drawable.baseline_favorite_24),
            MediaMetadata.MEDIA_TYPE_PLAYLIST,
        ),
        browsableMediaItem(
            DOWNLOADED, context.getString(R.string.downloaded), null, drawableUri(R.drawable.baseline_downloaded),
            MediaMetadata.MEDIA_TYPE_PLAYLIST,
        ),
        browsableMediaItem(
            PLAYLIST, context.getString(R.string.playlists), null, drawableUri(R.drawable.baseline_playlist_add_24),
            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
        ),
    )

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setExtras(CarBrowsePresentation.styles(grid = id == LIBRARY || id == PLAYLIST || id == ALBUM || id == RECOMMENDATIONS || id.startsWith("$HOME/")))
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun SongEntity.toMediaItem(path: String) =
        MediaItem
            .Builder()
            .setMediaId("$path/${this.videoId}")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(this.title)
                    .setSubtitle(this.artistName?.joinToString(", "))
                    .setArtist(this.artistName?.joinToString(" "))
                    .setArtworkUri(this.thumbnails?.toUri())
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()

    private fun Track.toMediaItemWithoutPath(path: String? = SONG) =
        MediaItem
            .Builder()
            .setMediaId(if (path == null) this.videoId else "$path/${this.videoId}")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(this.title)
                    .setSubtitle(this.artists?.toListName()?.connectArtists())
                    .setArtist(this.artists?.toListName()?.connectArtists())
                    .setArtworkUri(
                        this.thumbnails
                            ?.lastOrNull()
                            ?.url
                            ?.toUri(),
                    ).setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()

    companion object {
        const val RESUME = "resume"
        const val RECENT = "recent"
        const val RECOMMENDATIONS = "recommendations"
        const val ROOT = "root"
        const val ALBUM = "album"
        const val LIBRARY = "library"
        const val SONG = "song"
        const val HOME = "home"
        const val ONLINE_PLAYLIST = "online_playlist"
        const val PLAYLIST = "playlist"
        const val FAVORITE = "favorite"
        const val DOWNLOADED = "downloaded"
        const val MEDIA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}
