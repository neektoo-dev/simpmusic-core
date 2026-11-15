package com.maxrave.media3.exoplayer

import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory
import com.maxrave.media3.waveform.WaveformReader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RadioQueuePreservationTest {
    @Test fun replacesNeighboursWhileKeepingTheActualExoPlayerAndItsPlaybackPosition() {
        val context = RuntimeEnvironment.getApplication()
        // Keep precaching/settings jobs inert: this test exercises queue mutation, not stream fetching.
        val inactiveScope = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Main)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val settings = unused<DataStoreManager>()
        val streams = unused<StreamRepository>()
        val source = DefaultMediaSourceFactory(context)
        val adapter = CrossfadeExoPlayerAdapter(context, inactiveScope, settings,
            MergingMediaSourceFactory(source, source, settings, context, unused<Cache>()),
            AudioAttributes.DEFAULT, streams, WaveformReader(context, DefaultDataSource.Factory(context), streams))
        val exo = ExoPlayer.Builder(context).build()
        try {
            val first = GenericMediaItem("seed", null, GenericMediaMetadata.EMPTY)
            val history = GenericMediaItem("old", null, GenericMediaMetadata.EMPTY)
            val next = GenericMediaItem("next", null, GenericMediaMetadata.EMPTY)
            adapter.addMediaItem(history); adapter.addMediaItem(first); adapter.addMediaItem(history)
            ReflectionHelpers.setField(adapter, "localCurrentMediaItemIndex", 1)
            ReflectionHelpers.setField(adapter, "currentPlayer", exo)
            exo.setMediaItem(MediaItem.Builder().setMediaId("seed").setUri("test://seed").build())
            exo.seekTo(42_000); exo.playWhenReady = false
            var result: Boolean? = null
            var commits = 0
            scope.launch { result = adapter.replaceQueueKeepingCurrent("seed", listOf(next)) { commits++ } }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, result)
            assertEquals(1, commits)
            assertSame(exo, ReflectionHelpers.getField<ExoPlayer>(adapter, "currentPlayer"))
            assertEquals(42_000L, exo.currentPosition)
            assertFalse(exo.playWhenReady)
            assertEquals(listOf("seed", "next"), adapter.getCurrentMediaTimeLine().map { it.mediaId })
            assertEquals(0, adapter.currentMediaItemIndex)
            exo.playWhenReady = true
            scope.launch { result = adapter.replaceQueueKeepingCurrent("seed", listOf(next)) { commits++ } }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, result)
            assertEquals(42_000L, exo.currentPosition)
            assertTrue(exo.playWhenReady)
            scope.launch { result = adapter.replaceQueueKeepingCurrent("stale", listOf(history)) { fail() } }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(false, result)
            assertEquals(listOf("seed", "next"), adapter.getCurrentMediaTimeLine().map { it.mediaId })
        } finally { scope.cancel(); exo.release() }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Unexpected ${T::class.java.simpleName}.${method.name}")
    } as T
}
