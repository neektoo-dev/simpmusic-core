package com.maxrave.media3.service

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Routes phone voice intents through the same session callback used by Android Auto. */
@androidx.annotation.OptIn(UnstableApi::class)
class MediaSearchIntentHandler(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var requestJob: Job? = null

    var hasDispatchedRequest = false
        private set

    fun handle(intent: Intent): Boolean {
        if (intent.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return false
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val extras = Bundle(intent.extras ?: Bundle.EMPTY)
        Logger.i(TAG, "Voice intent received: package=${appContext.packageName}, queryLength=${query.length}")
        hasDispatchedRequest = false
        requestJob?.cancel()
        requestJob = scope.launch {
            try {
                val future = controllerFuture ?: createController().also { controllerFuture = it }
                // Cancelling an obsolete voice request must not cancel the shared connection.
                val controller = withTimeout(10_000L) { Futures.nonCancellationPropagating(future).await() }
                check(
                    controller.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM) &&
                        controller.isCommandAvailable(Player.COMMAND_PREPARE) &&
                        controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE),
                ) { "Media session does not accept search playback" }
                controller.setMediaItem(
                    MediaItem.Builder()
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder()
                                .setSearchQuery(query)
                                .setExtras(extras)
                                .build(),
                        ).build(),
                )
                controller.prepare()
                controller.play()
                hasDispatchedRequest = true
                Logger.i(TAG, "Voice intent dispatched to media session")
            } catch (e: TimeoutCancellationException) {
                releaseController()
                Logger.e(TAG, "Voice search connection timed out", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                releaseController()
                Logger.e(TAG, "Could not dispatch voice search", e)
            }
        }
        return true
    }

    private fun createController(): ListenableFuture<MediaController> =
        MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, SimpleMediaService::class.java)),
        ).setListener(
            object : MediaController.Listener {
                override fun onError(controller: MediaController, sessionError: androidx.media3.session.SessionError) {
                    Logger.e(TAG, "Voice request rejected: code=${sessionError.code}, message=${sessionError.message}")
                }

                override fun onDisconnected(controller: MediaController) {
                    val future = controllerFuture ?: return
                    if (future.isDone && !future.isCancelled && runCatching { future.get() }.getOrNull() === controller) {
                        releaseController()
                    }
                }
            },
        ).buildAsync()

    fun release() {
        requestJob?.cancel()
        requestJob = null
        releaseController()
    }

    private fun releaseController() {
        val future = controllerFuture
        controllerFuture = null
        future?.let { MediaController.releaseFuture(it) }
    }

    private companion object {
        const val TAG = "MediaSearchIntent"
    }
}