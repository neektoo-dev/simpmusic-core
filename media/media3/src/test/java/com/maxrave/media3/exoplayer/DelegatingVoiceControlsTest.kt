package com.maxrave.media3.exoplayer

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.ThumbRating
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.maxrave.domain.data.player.GenericMediaMetadata
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class DelegatingVoiceControlsTest {
    private lateinit var delegate: ExoPlayer
    private lateinit var forwarding: DelegatingForwardingPlayer
    private val navigation = Navigation()

    @Before
    fun setUp() {
        delegate = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        delegate.setMediaItem(MediaItem.fromUri("test://current"))
        forwarding = DelegatingForwardingPlayer(delegate)
        forwarding.playlistNavigationProvider = navigation
    }

    @After
    fun tearDown() {
        delegate.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun voiceSeekUsesTheQueueOwnerAndNotItsOneItemDelegate() {
        forwarding.seekTo(0, 30_000L)
        assertEquals(30_000L, navigation.position)
        forwarding.seekToDefaultPosition(0)
        assertEquals(0L, navigation.position)
        forwarding.seekBack()
        forwarding.seekForward()
        assertEquals(1, navigation.back)
        assertEquals(1, navigation.forward)
    }

    @Test
    fun shuffleAndRepeatAffectTheRealQueueAndAreReportedBack() {
        forwarding.shuffleModeEnabled = true
        forwarding.repeatMode = Player.REPEAT_MODE_ALL
        assertTrue(navigation.shuffleModeEnabled)
        assertTrue(forwarding.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, navigation.repeatMode)
        assertEquals(Player.REPEAT_MODE_ALL, forwarding.repeatMode)
        // Repeating the raw single-item delegate would loop the same song forever.
        assertEquals(Player.REPEAT_MODE_OFF, delegate.repeatMode)
        forwarding.shuffleModeEnabled = false
        assertFalse(navigation.shuffleModeEnabled)
    }

    @Test
    fun stopAndNavigationAreForwardedOnce() {
        forwarding.seekToNextMediaItem()
        forwarding.seekToPreviousMediaItem()
        forwarding.stop()
        assertEquals(1, navigation.next)
        assertEquals(1, navigation.previous)
        assertEquals(1, navigation.stops)
    }

    @Test
    fun metadataAdvertisesThumbRatingToTheAssistant() {
        assertTrue(GenericMediaMetadata(title = "Song").toMedia3MediaMetadata().userRating is ThumbRating)
    }

    private class Navigation : DelegatingForwardingPlayer.PlaylistNavigationProvider {
        var position = -1L
        var back = 0
        var forward = 0
        var next = 0
        var previous = 0
        var stops = 0
        override var repeatMode = Player.REPEAT_MODE_OFF
        override var shuffleModeEnabled = false
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() { next++ }
        override fun seekToPrevious() { previous++ }
        override fun play() {}
        override fun pause() {}
        override fun setPlayWhenReady(playWhenReady: Boolean) {}
        override fun stop() { stops++ }
        override fun seekTo(positionMs: Long) { position = positionMs }
        override fun seekBack() { back++ }
        override fun seekForward() { forward++ }
    }
}