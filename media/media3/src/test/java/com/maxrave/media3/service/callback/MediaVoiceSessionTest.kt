package com.maxrave.media3.service.callback

import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.media.session.MediaSessionManager
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.ThumbRating
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.legacy.PlaybackStateCompat
import com.maxrave.common.Config
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.*
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.LocalResource
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.media3.extension.toCommandButton
import com.maxrave.media3.service.CarMediaButtonLayout
import com.maxrave.media3.service.CarBrowseControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaSession
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.lang.reflect.Proxy

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [35], shadows = [MediaVoiceSessionTest.CallerMediaSessionShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
open class MediaVoiceSessionTest {
    @Implements(android.media.session.MediaSession::class)
    class CallerMediaSessionShadow : ShadowMediaSession() {
        @Implementation
        fun getCurrentControllerInfo(): MediaSessionManager.RemoteUserInfo =
            MediaSessionManager.RemoteUserInfo("com.example.voice", 123, 456)
    }

    class TestService : MediaLibraryService() {
        override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = null
    }

    private lateinit var service: TestService
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibraryService.MediaLibrarySession
    private lateinit var callback: SimpleMediaSessionCallback
    private lateinit var controller: MediaController
    private lateinit var scope: CoroutineScope
    private val first = track("first000001", "One More Time")
    private val second = track("second00002", "Aerodynamic")
    private var librarySongs = listOf(first.toSongEntity(), second.toSongEntity())
    private val album = AlbumEntity(browseId = "album1", audioPlaylistId = "albumqueue", description = "", duration = null,
        durationSeconds = 360, thumbnails = null, title = "Discovery", trackCount = 2,
        tracks = listOf(second.videoId, first.videoId), type = "Album", year = "2001")
    private var current: GenericMediaItem? = null
    private var queue: QueueData.Data? = null
    private var loaded: Track? = null
    private var loadType: String? = null
    private var loadIndex: Int? = null
    private var restored = 0
    private var playCalls = 0
    private var libraryLike: Boolean? = null
    private var handlerLike: Boolean? = null
    private var querySeen: String? = null
    private val carPins = MutableStateFlow<String?>(null)
    private var shuffleRequests = 0
    private val playingState = MutableStateFlow(NowPlayingTrackState.initial())
    private val controls = MutableStateFlow(ControlState(
        isPlaying = false, isShuffle = false, repeatState = RepeatState.None, isLiked = false,
        isNextAvailable = true, isPreviousAvailable = true, isCrossfading = false, volume = 1f,
    ))
    private var sessionErrorReceived = false
    private var remoteResults: CompletableDeferred<List<SongsResult>>? = null

    protected open fun sessionPlayer(player: Player): Player = player

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        service = Robolectric.buildService(TestService::class.java).create().get()
        player = ExoPlayer.Builder(service).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        player.addListener(object : Player.Listener {
            override fun onRepeatModeChanged(repeatMode: Int) {
                controls.value = controls.value.copy(repeatState = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatState.One
                    Player.REPEAT_MODE_ALL -> RepeatState.All
                    else -> RepeatState.None
                })
            }
        })
        val genericPlayer = proxy<MediaPlayerInterface> { name, _ ->
            when (name) {
                "getCurrentMediaItem" -> current
                "play" -> { playCalls++; Unit }
                else -> error("Unexpected player call $name")
            }
        }
        val handler = proxy<MediaPlayerHandler> { name, args ->
            when (name) {
                "getPlayer" -> genericPlayer
                "getNowPlayingState" -> playingState
                "getControlState" -> controls
                "onPlayerEvent" -> {
                    assertEquals(PlayerEvent.Shuffle, args[0]); shuffleRequests++
                    controls.value = controls.value.copy(isShuffle = !controls.value.isShuffle); Unit
                }
                "setQueueData" -> { queue = args[0] as QueueData.Data; Unit }
                "loadMediaItem" -> {
                    // Media3 has already selected the resolved title before the domain queue is loaded.
                    assertEquals((args[0] as Track).videoId, player.currentMediaItem?.mediaId)
                    loaded = args[0] as Track
                    loadType = args[1] as String
                    loadIndex = args[2] as Int
                    current = GenericMediaItem(loaded!!.videoId, null, GenericMediaMetadata(title = loaded!!.title))
                    player.setMediaItem(MediaItem.Builder().setMediaId(loaded!!.videoId).setUri("test://song").build())
                    Unit
                }
                "loadPlaylistOrAlbum", "mayBeRestoreQueue" -> Unit
                "restoreQueueAndPlay" -> { restored++; true }
                "like" -> { handlerLike = args[0] as Boolean; controls.value = controls.value.copy(isLiked = handlerLike!!); Unit }
                else -> error("Unexpected handler call $name")
            }
        }
        val songRepository = proxy<SongRepository> { name, args ->
            when (name) {
                "getSongsByListVideoId" -> flowOf(librarySongs)
                "getAllSongs" -> flowOf(librarySongs.mapIndexed { index, song -> if (index == 0) song.copy(totalPlayTime = 120) else song })
                "getDownloadedSongs" -> flowOf(librarySongs.take(1))
                "getLikedSongs" -> flowOf(librarySongs)
                "getSongById" -> flowOf(librarySongs.find { it.videoId == args[0] })
                "updateLikeStatus" -> { libraryLike = args[1] == 1; Unit }
                else -> error("Unexpected song repository call $name")
            }
        }
        val searchRepository = proxy<SearchRepository> { name, args ->
            querySeen = args[0] as String
            when (name) {
                "getSearchDataSong" -> flow<Resource<ArrayList<SongsResult>>> {
                    emit(Resource.Success(ArrayList(remoteResults?.await().orEmpty())))
                }
                else -> error("Unexpected search call $name")
            }
        }
        val playlists = proxy<LocalPlaylistRepository> { name, _ ->
            when (name) {
                "getAllLocalPlaylists" -> MutableStateFlow(listOf(LocalPlaylistEntity(id = 7, title = "Trajet")))
                "getLocalPlaylist" -> flowOf(LocalResource.Success(LocalPlaylistEntity(
                    id = 7, title = "Trajet", tracks = librarySongs.reversed().map { it.videoId },
                )))
                "getFullPlaylistTracks" -> librarySongs.reversed()
                else -> error("Unexpected playlist call $name")
            }
        }
        callback = SimpleMediaSessionCallback(
            service, scope, handler, searchRepository, songRepository, playlists,
            proxy { name, _ -> error(name) }, proxy { name, _ -> error(name) }, proxy { name, _ -> error(name) },
            proxy<AlbumRepository> { name, _ -> when (name) {
                "getAlbum" -> flowOf(album)
                "getAllAlbums" -> flowOf(listOf(album))
                else -> error("Unexpected album call $name")
            } },
            proxy<DataStoreManager> { name, _ -> when (name) {
                "getRecentMediaId" -> flowOf("")
                "getString" -> carPins
                else -> error(name)
            } },
        )
        session = MediaLibraryService.MediaLibrarySession.Builder(service, sessionPlayer(player), callback)
            .setCommandButtonsForMediaItems(CarBrowseControls.commandButtons(service))
            .build()
        val future = MediaController.Builder(service, session.token).setListener(object : MediaController.Listener {
            override fun onError(controller: MediaController, sessionError: androidx.media3.session.SessionError) {
                sessionErrorReceived = true
            }
        }).buildAsync()
        await { future.isDone }
        controller = future.get()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.release()
        if (::scope.isInitialized) scope.cancel()
        if (::session.isInitialized) session.release()
        if (::player.isInitialized) player.release()
        if (::service.isInitialized) service.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun radioMixHasASingleCarActionWithoutLosingTransportControls() {
        var radioRequests = 0
        callback.toggleRadio = { radioRequests++ }
        val connection = MutableLiveData(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
        val layout = CarMediaButtonLayout(connection) { buttons ->
            session.setMediaButtonPreferences(buttons.map { it.toCommandButton(service) })
        }
        layout.start()
        layout.updateButtons(
            listOf(
                GenericCommandButton.Radio, GenericCommandButton.Like(false),
                GenericCommandButton.Shuffle(false), GenericCommandButton.Repeat(RepeatState.None),
            ),
        )
        // Both skip actions must remain available: putting Radio into their slots would
        // remove the corresponding legacy transport flags (including for voice controllers).
        player.setMediaItems(
            listOf(first, second, first).map { MediaItem.Builder().setMediaId(it.videoId).setUri("test://song").build() },
            1, 0,
        )
        // The notification controller publishes the available commands to the shared
        // platform session, which Android Auto consumes.
        val notificationFuture = MediaController.Builder(service, session.token)
            .setConnectionHints(Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true) })
            .buildAsync()
        await { notificationFuture.isDone }
        val notificationController = notificationFuture.get()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            // Robolectric does not bridge platform controllers over Binder. Exercise Media3's
            // actual legacy state conversion and callback, as in legacySearch below.
            val impl = ReflectionHelpers.callInstanceMethod<Any>(session, "getImpl")
            val legacy = ReflectionHelpers.callInstanceMethod<Any>(impl, "getMediaSessionLegacyStub")
            val wrapper = ReflectionHelpers.callInstanceMethod<Any>(impl, "getPlayerWrapper")
            fun playbackState(): PlaybackStateCompat {
                shadowOf(Looper.getMainLooper()).idle()
                return ReflectionHelpers.callInstanceMethod(
                    legacy, "createPlaybackStateCompat",
                    ClassParameter.from(Class.forName("androidx.media3.session.PlayerWrapper") as Class<Any>, wrapper),
                )
            }
            val phoneState = playbackState()
            assertEquals(4, phoneState.customActions.size)
            assertNotEquals(0L, phoneState.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            assertNotEquals(0L, phoneState.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            assertNotEquals(0L, phoneState.actions and PlaybackStateCompat.ACTION_PLAY_PAUSE)

            connection.value = CarConnection.CONNECTION_TYPE_PROJECTION
            val carState = playbackState()
            val radio = carState.customActions.single()
            assertEquals(phoneState.actions, carState.actions)
            assertEquals(MEDIA_CUSTOM_COMMAND.RADIO, radio.action)
            assertEquals("Radio/Mix", radio.name.toString())
            assertNotEquals(0, radio.icon)
            assertNotNull(service.getDrawable(radio.icon))
            ReflectionHelpers.callInstanceMethod<Unit>(
                legacy, "onCustomAction",
                ClassParameter.from(String::class.java, radio.action),
                ClassParameter.from(Bundle::class.java, Bundle.EMPTY),
            )
            await { radioRequests == 1 }
            assertNull(loaded)
            assertNull(querySeen)

            // A like/shuffle refresh during driving must not put overflow buttons back.
            layout.updateButtons(
                listOf(
                    GenericCommandButton.Radio, GenericCommandButton.Like(true),
                    GenericCommandButton.Shuffle(true), GenericCommandButton.Repeat(RepeatState.One),
                ),
            )
            assertEquals(MEDIA_CUSTOM_COMMAND.RADIO, playbackState().customActions.single().action)

            // Unsupported hosts must retain the controls in their playback overflow menu.
            layout.setBrowseControlsSupported(false)
            assertEquals(4, playbackState().customActions.size)
            assertEquals(phoneState.actions, playbackState().actions)
            layout.setBrowseControlsSupported(true)
            assertEquals(MEDIA_CUSTOM_COMMAND.RADIO, playbackState().customActions.single().action)

            // Disconnecting restores the latest states, not the values from before the drive.
            connection.value = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
            val restoredState = playbackState()
            assertEquals(4, restoredState.customActions.size)
            assertEquals("Liked", restoredState.customActions[1].name.toString())
            assertEquals("Repeat One", restoredState.customActions[3].name.toString())
            assertEquals(phoneState.actions, restoredState.actions)
            connection.value = CarConnection.CONNECTION_TYPE_PROJECTION
            assertEquals(MEDIA_CUSTOM_COMMAND.RADIO, playbackState().customActions.single().action)

        } finally {
            layout.stop()
            notificationController.release()
        }
        assertFalse(connection.hasObservers())
    }

    @Test
    fun browseControlsExposeThreeActionsEvenWhenTheHostOnlyAllowsOnePerItem() {
        showCurrentTrack(first)
        val browser = browseController(1)
        try {
            assertEquals(0, result(browser.getLibraryRoot(null)).resultCode)
            val roots = result(browser.getChildren(SimpleMediaSessionCallback.ROOT, 0, 10, null)).value!!
            assertEquals(4, roots.size)
            assertTrue(roots.any { it.mediaId == CarBrowseControls.ROOT })
            val library = result(browser.getChildren(SimpleMediaSessionCallback.LIBRARY, 0, 10, null)).value!!
            assertEquals(listOf("album", "song", "favorite", "downloaded", "playlist"), library.map { it.mediaId })
            val rows = result(browser.getChildren(CarBrowseControls.ROOT, 0, 10, null)).value!!
            assertEquals(3, rows.size)
            assertEquals(listOf("Add to favorites", "Enable shuffle", "Repeat"), rows.map { it.mediaMetadata.title.toString() })
            for (row in rows) {
                assertEquals(false, row.mediaMetadata.isPlayable)
                assertEquals(true, row.mediaMetadata.isBrowsable)
                val button = browser.getCommandButtonsForMediaItem(row).single()
                assertNotNull(button.sessionCommand)
                assertEquals("android.resource", button.iconUri!!.scheme)
                assertNotNull(service.getDrawable(button.iconUri!!.lastPathSegment!!.toInt()))
                assertEquals(row.mediaId, result(browser.getItem(row.mediaId)).value!!.mediaId)
                val children = result(browser.getChildren(row.mediaId, 0, 10, null)).value!!
                assertEquals("song/${first.videoId}", children.single().mediaId)
            }
            assertEquals(rows[1].mediaId, result(browser.getChildren(CarBrowseControls.ROOT, 1, 1, null)).value!!.single().mediaId)
            assertTrue(result(browser.getChildren(CarBrowseControls.ROOT, Int.MAX_VALUE, Int.MAX_VALUE, null)).value!!.isEmpty())
            assertNull(loaded)
            assertEquals(0, playCalls)
        } finally {
            browser.release()
        }
    }

    @Test
    fun browseActionsChangeControlsWithoutRestartingPlaybackAndRejectStaleSongRows() {
        showCurrentTrack(first)
        player.setMediaItem(MediaItem.Builder().setMediaId(first.videoId).setUri("test://song").build())
        player.seekTo(20_000)
        val browser = browseController(3)
        try {
            val rows = result(browser.getChildren(CarBrowseControls.ROOT, 0, 10, null)).value!!
            fun perform(index: Int): Int {
                val row = result(browser.getChildren(CarBrowseControls.ROOT, 0, 10, null)).value!![index]
                val command = browser.getCommandButtonsForMediaItem(row).single().sessionCommand!!
                return result(browser.sendCustomCommand(command, row, Bundle.EMPTY)).resultCode
            }
            assertEquals(0, perform(0))
            assertEquals(true, handlerLike)
            assertEquals(0, perform(1))
            await { shuffleRequests == 1 }
            assertEquals(0, perform(2))
            assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
            assertEquals(0, perform(2))
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            assertEquals(0, perform(2))
            assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
            controls.value = controls.value.copy(isLiked = true, isShuffle = true, repeatState = RepeatState.All)
            val refreshed = result(browser.getChildren(CarBrowseControls.ROOT, 0, 10, null)).value!!
            assertTrue(refreshed[0].mediaMetadata.subtitle.toString().startsWith("On"))
            assertTrue(refreshed[1].mediaMetadata.subtitle.toString().startsWith("On"))
            assertTrue(refreshed[2].mediaMetadata.subtitle.toString().startsWith("Repeat All"))
            assertEquals(first.videoId, player.currentMediaItem!!.mediaId)
            assertEquals(20_000L, player.currentPosition)
            assertNull(loaded)
            assertNull(queue)
            assertEquals(0, playCalls)

            showCurrentTrack(second)
            handlerLike = null
            val staleAction = browser.getCommandButtonsForMediaItem(rows[0]).single().sessionCommand!!
            assertEquals(SessionError.ERROR_BAD_VALUE, result(browser.sendCustomCommand(staleAction, rows[0], Bundle.EMPTY)).resultCode)
            assertNull(handlerLike)
            assertEquals(SessionError.ERROR_BAD_VALUE, result(browser.getItem(rows[0].mediaId)).resultCode)
        } finally {
            browser.release()
        }
    }

    @Test
    fun browseControlsHandleAnEmptyPlayerAndHideTheTabForUnsupportedHosts() {
        val browser = browseController(1)
        val oldBrowser = browseController(0)
        try {
            assertTrue(result(browser.getChildren(CarBrowseControls.ROOT, 0, 10, null)).value!!.isEmpty())
            assertEquals(0, result(browser.subscribe(CarBrowseControls.ROOT, null)).resultCode)
            showCurrentTrack(first)
            assertEquals(3, result(browser.getChildren(CarBrowseControls.ROOT, 0, 10, null)).value!!.size)
            val roots = result(oldBrowser.getChildren(SimpleMediaSessionCallback.ROOT, 0, 10, null)).value!!
            assertFalse(roots.any { it.mediaId == CarBrowseControls.ROOT })
            assertTrue(result(oldBrowser.getChildren(CarBrowseControls.ROOT, 0, 10, null)).value!!.isEmpty())
            assertEquals(SessionError.ERROR_BAD_VALUE, result(oldBrowser.getItem(CarBrowseControls.ROOT)).resultCode)
        } finally {
            browser.release()
            oldBrowser.release()
        }
    }

    @Test
    fun carHomeIsLocalAndPinnedPlaylistsAndRecentTracksRemainPlayable() {
        showCurrentTrack(first)
        carPins.value = "7,999"
        val browser = browseController(1)
        try {
            val home = result(browser.getChildren(SimpleMediaSessionCallback.HOME, 0, 30, null)).value!!
            assertEquals(listOf("resume", "recent", "downloaded", "playlist/7", "recommendations"), home.map { it.mediaId })
            assertEquals(true, home.first().mediaMetadata.isPlayable)
            assertEquals("Trajet", home[3].mediaMetadata.title.toString())
            val recent = result(browser.getChildren(SimpleMediaSessionCallback.RECENT, 0, 30, null)).value!!
            assertEquals(listOf("song/${first.videoId}"), recent.map { it.mediaId })
            current = GenericMediaItem(first.videoId, null, GenericMediaMetadata.EMPTY)
            player.setMediaItem(MediaItem.Builder().setMediaId(first.videoId).setUri("test://song").build())
            player.seekTo(35_000)
            browser.setMediaItem(home.first())
            browser.prepare(); browser.play()
            await { playCalls > 0 }
            assertNull(querySeen)
            assertNull(loaded)
            assertEquals(35_000L, player.currentPosition)
            val library = result(browser.getItem(SimpleMediaSessionCallback.PLAYLIST)).value!!
            val style = library.mediaMetadata.extras!!.getInt(
                androidx.media3.session.legacy.MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
            assertEquals(androidx.media3.session.legacy.MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, style)
        } finally { browser.release() }
    }

    @Test
    fun carAlbumBrowsingAndPlaybackKeepTheAlbumOrderWithoutNetworkWhenCached() {
        val browser = browseController(1)
        try {
            val albums = result(browser.getChildren(SimpleMediaSessionCallback.ALBUM, 0, 20, null)).value!!
            assertEquals("album/album1", albums.single().mediaId)
            val tracks = result(browser.getChildren(albums.single().mediaId, 0, 20, null)).value!!
            assertEquals(listOf("album/album1/${second.videoId}", "album/album1/${first.videoId}"), tracks.map { it.mediaId })
            browser.setMediaItem(tracks.first()); browser.prepare(); browser.play()
            await { loaded != null }
            assertEquals(listOf(second.videoId, first.videoId), queue!!.listTracks.map { it.videoId })
            assertEquals(com.maxrave.domain.mediaservice.handler.PlaylistType.ALBUM, queue!!.playlistType)
            assertNull(querySeen)
        } finally { browser.release() }
    }

    private fun showCurrentTrack(track: Track) {
        playingState.value = NowPlayingTrackState(
            GenericMediaItem(track.videoId, null, GenericMediaMetadata(title = track.title)), track, track.toSongEntity(),
        )
    }

    private fun browseController(limit: Int): MediaBrowser = result(
        MediaBrowser.Builder(service, session.token).setMaxCommandsForMediaItems(limit).buildAsync(),
    )

    private fun <T> result(future: ListenableFuture<T>): T {
        await { future.isDone }
        return future.get()
    }

    @Test
    fun media3SetPreparePlayLoadsTheRequestedTrackAfterTheTransportBatch() {
        controller.setMediaItem(searchItem("One More Time"))
        controller.prepare()
        controller.play()
        await { loaded != null }
        assertEquals(first.videoId, loaded!!.videoId)
        assertEquals(Config.SONG_CLICK, loadType)
        assertEquals(0, restored)
        assertTrue(playCalls > 0)
    }

    @Test
    fun legacyPlayFromSearchAlsoStartsPlayback() {
        legacySearch(play = true)
        await { loaded != null }
        assertEquals(first.videoId, loaded!!.videoId)
        assertEquals(Config.SONG_CLICK, loadType)
        assertEquals(0, restored)
    }

    @Test
    fun prepareFromSearchDoesNotStartPlayback() {
        legacySearch(play = false)
        await { loaded != null }
        assertEquals(Config.RECOVER_TRACK_QUEUE, loadType)
        assertEquals(0, playCalls)
    }

    @Test
    fun playlistFocusPreservesTheWholeLocalPlaylistInItsOriginalOrder() {
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_PLAYLIST, "Trajet")
        }
        controller.setMediaItem(searchItem("ma playlist", extras))
        controller.prepare()
        controller.play()
        await { loaded != null }
        assertEquals(listOf(second.videoId, first.videoId), queue!!.listTracks.map { it.videoId })
        assertEquals(Config.PLAYLIST_CLICK, loadType)
        assertNull(querySeen)
    }

    @Test
    fun missingSearchReportsAnErrorAndKeepsTheExistingTrack() {
        player.setMediaItem(MediaItem.Builder().setMediaId("existing").setUri("test://existing").build())
        controller.setMediaItem(searchItem("unknown title"))
        controller.prepare()
        controller.play()
        await { sessionErrorReceived }
        assertNull(loaded)
        assertEquals("existing", player.currentMediaItem?.mediaId)
    }

    @Test
    fun ratingIsIdempotentAndSavedToTheLibrary() {
        current = GenericMediaItem(first.videoId, null, GenericMediaMetadata.EMPTY)
        val result = controller.setRating(first.videoId, ThumbRating(true))
        await { result.isDone }
        assertEquals(0, result.get().resultCode)
        assertEquals(true, libraryLike)
        assertEquals(true, handlerLike)
        val again = controller.setRating(first.videoId, ThumbRating(true))
        await { again.isDone }
        assertEquals(true, libraryLike)
    }

    @Test
    fun classicBrowseSelectionAlsoLoadsAfterTheMedia3TransportOperation() {
        controller.setMediaItem(MediaItem.Builder().setMediaId("favorite/${second.videoId}").build())
        controller.prepare()
        controller.play()
        await { loaded != null }
        assertEquals(second.videoId, loaded!!.videoId)
        assertEquals(listOf(first.videoId, second.videoId), queue!!.listTracks.map { it.videoId })
    }

    @Test
    fun carLibraryRowsRemainPlayableWithLegacyUnavailableMetadata() {
        // Album/playlist parsers have historically persisted false even for playable songs.
        // Exercise the actual rows sent to the car, including a non-first favorite selection.
        librarySongs = librarySongs.map { it.copy(isAvailable = false) }
        val browser = browseController(1)
        try {
            val cases = listOf(
                Triple("favorite", second.videoId, listOf(first.videoId, second.videoId)),
                Triple("downloaded", first.videoId, listOf(first.videoId)),
                Triple("playlist/7", second.videoId, listOf(second.videoId, first.videoId)),
                Triple("album/album1", second.videoId, listOf(second.videoId, first.videoId)),
                Triple("song", first.videoId, listOf(first.videoId)),
            )
            for ((folder, selectedId, expectedQueue) in cases) {
                loaded = null
                val children = result(browser.getChildren(folder, 0, 20, null))
                assertEquals("Could not browse $folder", 0, children.resultCode)
                val rows = children.value!!
                val selected = rows.first { it.mediaId == "$folder/$selectedId" }
                assertEquals(true, selected.mediaMetadata.isPlayable)
                browser.setMediaItem(selected)
                browser.prepare(); browser.play()
                await { loaded != null || sessionErrorReceived }
                assertFalse("$folder rejected a saved song before trying playback", sessionErrorReceived)
                assertEquals(selectedId, loaded!!.videoId)
                assertEquals(expectedQueue, queue!!.listTracks.map { it.videoId })
                assertEquals(expectedQueue.indexOf(selectedId), loadIndex)
                assertNull(querySeen)
            }
        } finally { browser.release() }
    }

    @Test
    fun legacyAndroidAutoPlayFromMediaIdAcceptsAFavoriteWithOldAvailabilityFlag() {
        librarySongs = librarySongs.map { it.copy(isAvailable = false) }
        val impl = ReflectionHelpers.callInstanceMethod<Any>(session, "getImpl")
        val legacy = ReflectionHelpers.callInstanceMethod<Any>(impl, "getMediaSessionLegacyStub")
        ReflectionHelpers.callInstanceMethod<Unit>(
            legacy, "onPlayFromMediaId",
            ClassParameter.from(String::class.java, "favorite/${second.videoId}"),
            ClassParameter.from(Bundle::class.java, Bundle.EMPTY),
        )
        await { loaded != null || sessionErrorReceived }
        assertFalse(sessionErrorReceived)
        assertEquals(second.videoId, loaded!!.videoId)
        assertEquals(1, loadIndex)
        assertEquals(listOf(first.videoId, second.videoId), queue!!.listTracks.map { it.videoId })
        assertNull(querySeen)
    }

    @Test
    fun missingFavoriteDoesNotSubstituteAnotherSongOrClearCurrentPlayback() {
        player.setMediaItem(MediaItem.Builder().setMediaId("existing").setUri("test://existing").build())
        controller.setMediaItem(MediaItem.Builder().setMediaId("favorite/removed-song").build())
        controller.prepare(); controller.play()
        await { sessionErrorReceived }
        assertNull(loaded)
        assertNull(queue)
        assertEquals("existing", player.currentMediaItem?.mediaId)
        assertNull(querySeen)
    }

    @Test
    fun structuredTitleWorksEvenWithoutASearchQuery() {
        val extras = Bundle().apply { putString(MediaStore.EXTRA_MEDIA_TITLE, "One More Time") }
        controller.setMediaItem(searchItem(null, extras))
        controller.prepare()
        controller.play()
        await { loaded != null }
        assertEquals(first.videoId, loaded!!.videoId)
    }

    @Test
    fun genericMusicRequestRestoresTheSavedQueue() {
        controller.setMediaItem(searchItem(""))
        controller.prepare()
        controller.play()
        await { restored > 0 }
        assertEquals(1, restored)
        assertNull(querySeen)
        assertNull(loaded)
    }

    @Test
    fun genericMusicRequestKeepsTheCurrentTrack() {
        current = GenericMediaItem(first.videoId, null, GenericMediaMetadata.EMPTY)
        player.setMediaItem(MediaItem.Builder().setMediaId(first.videoId).setUri("test://existing").build())
        controller.setMediaItem(searchItem("musique"))
        controller.prepare()
        controller.play()
        await { playCalls > 0 }
        assertEquals(first.videoId, player.currentMediaItem?.mediaId)
        assertEquals(0, restored)
        assertNull(loaded)
        assertNull(querySeen)
    }

    @Test
    fun delayedOnlineSearchLoadsAfterReplacingTheActiveTrack() {
        remoteResults = CompletableDeferred()
        current = GenericMediaItem(first.videoId, null, GenericMediaMetadata.EMPTY)
        player.setMediaItem(MediaItem.Builder().setMediaId(first.videoId).setUri("test://existing").build())
        controller.setMediaItem(searchItem("Get Lucky"))
        controller.prepare()
        controller.play()
        await { querySeen == "Get Lucky" }
        assertNull(loaded)
        assertEquals(first.videoId, player.currentMediaItem?.mediaId)
        remoteResults!!.complete(listOf(
            SongsResult(
                album = null, artists = listOf(Artist("artist", "Daft Punk")), category = "Song",
                duration = "3:00", durationSeconds = 180, feedbackTokens = null, isExplicit = false,
                resultType = "Song", thumbnails = listOf(Thumbnail(120, "https://example.com/cover.jpg", 120)),
                title = "Get Lucky", videoId = "newremote01", videoType = "MUSIC_VIDEO_TYPE_ATV", year = "",
            ),
        ))
        await { loaded != null }
        assertEquals("newremote01", loaded!!.videoId)
        assertEquals(0, restored)
        assertTrue(playCalls > 0)
        assertFalse(sessionErrorReceived)
    }

    @Test
    fun successfulSearchReturnsAPlayableItemInsteadOfAnEmptyPlaylist() {
        val request = callback.onSetMediaItems(
            session, session.connectedControllers.first { it.packageName == service.packageName },
            mutableListOf(searchItem("One More Time")), 0, 0,
        )
        await { request.isDone }
        val items = request.get().mediaItems
        assertEquals(1, items.size)
        assertEquals(first.videoId, items.single().mediaId)
        assertNotNull(items.single().localConfiguration)
        assertEquals(first.title, items.single().mediaMetadata.title)
    }

    @Test
    fun carSearchSelectionCanPlayATrackThatWasNeverSavedToTheLibrary() {
        val remoteId = "newsearch01"
        remoteResults = CompletableDeferred(listOf(
            SongsResult(
                album = null, artists = listOf(Artist("artist", "Daft Punk")), category = "Song",
                duration = "3:00", durationSeconds = 180, feedbackTokens = null, isExplicit = false,
                resultType = "Song", thumbnails = listOf(Thumbnail(120, "https://example.com/cover.jpg", 120)),
                title = "Get Lucky", videoId = remoteId, videoType = "MUSIC_VIDEO_TYPE_ATV", year = "",
            ),
        ))
        assertTrue(librarySongs.none { it.videoId == remoteId })
        val browser = browseController(1)
        try {
            assertEquals(0, result(browser.search("Get Lucky", null)).resultCode)
            val selected = result(browser.getSearchResult("Get Lucky", 0, 20, null)).value!!.single()
            assertEquals("song/$remoteId", selected.mediaId)
            browser.setMediaItem(selected); browser.prepare(); browser.play()
            await { loaded != null || sessionErrorReceived }
            assertFalse(sessionErrorReceived)
            assertEquals(remoteId, loaded!!.videoId)
            assertEquals(listOf(remoteId), queue!!.listTracks.map { it.videoId })
            assertEquals(0, loadIndex)
        } finally { browser.release() }
    }

    private fun searchItem(query: String?, extras: Bundle = Bundle.EMPTY): MediaItem = MediaItem.Builder()
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build()).build()

    private fun legacySearch(play: Boolean) {
        // Robolectric does not deliver platform controller commands over Binder. Enter
        // Media3's real legacy callback directly, retaining its translation and batching.
        val impl = ReflectionHelpers.callInstanceMethod<Any>(session, "getImpl")
        val legacy = ReflectionHelpers.callInstanceMethod<Any>(impl, "getMediaSessionLegacyStub")
        ReflectionHelpers.callInstanceMethod<Unit>(
            legacy, if (play) "onPlayFromSearch" else "onPrepareFromSearch",
            ClassParameter.from(String::class.java, "One More Time"),
            ClassParameter.from(Bundle::class.java, Bundle.EMPTY),
        )
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Timed out waiting for the media session", condition())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
            when (method.name) {
                "toString" -> T::class.java.simpleName
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                else -> call(method.name, args ?: emptyArray())
            }
        } as T

    private fun track(id: String, title: String): Track = Track(
        album = null, artists = listOf(Artist("artist", "Daft Punk")), duration = "3:00", durationSeconds = 180,
        isAvailable = true, isExplicit = false, likeStatus = "INDIFFERENT",
        thumbnails = listOf(Thumbnail(120, "https://example.com/cover.jpg", 120)), title = title,
        videoId = id, videoType = "MUSIC_VIDEO_TYPE_ATV", category = null, feedbackTokens = null, resultType = "song",
    )
}
