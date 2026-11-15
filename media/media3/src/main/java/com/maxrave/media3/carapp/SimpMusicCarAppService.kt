package com.maxrave.media3.carapp

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.logger.Logger
import com.maxrave.media3.service.SimpleMediaService
import java.util.concurrent.TimeoutException

/**
 * Entry point for the Android Auto templated media experience (Car App
 * Library). Playback keeps flowing through [SimpleMediaService]'s
 * MediaSession as before; this service only contributes the template UI
 * (header + custom queue screen) on top of it.
 */
@UnstableApi
internal class SimpMusicCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator
                .Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = SimpMusicCarSession()
}

@UnstableApi
internal class SimpMusicCarSession : Session() {
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private var readyFuture: SettableFuture<MediaBrowser>? = null
    private val connectionHandler = Handler(Looper.getMainLooper())
    private var connectionGeneration = 0L
    private var retryCount = 0
    private var destroyed = false
    private var playbackTokenRegistered = false
    private var connectionTimeout: Runnable? = null

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    ensureBrowser()
                }

                override fun onStart(owner: LifecycleOwner) {
                    // Returning to the app also retries an exhausted connection attempt.
                    ensureBrowser()
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    destroyed = true
                    connectionGeneration++
                    connectionHandler.removeCallbacksAndMessages(null)
                    readyFuture?.cancel(false)
                    readyFuture = null
                    releaseBrowser()
                }
            },
        )
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // Library tab root sits under the playback screen so BACK lands on
        // browse, matching the classic media surface
        carContext.getCarService(ScreenManager::class.java).push(
            LibraryTabCarScreen(carContext, ::ensureBrowser, ::newPlaybackScreen),
        )
        return newPlaybackScreen()
    }

    override fun onNewIntent(intent: Intent) {
        Logger.w(TAG, "onNewIntent action=${intent.action}")
        ensureBrowser()
        // System intents asking the app to surface its playback screen
        if (intent.action == ACTION_SHOW_MEDIA_PLAYBACK || intent.action == ACTION_MEDIA_SHOW_PLAYBACK_VIEW) {
            val screenManager = carContext.getCarService(ScreenManager::class.java)
            if (screenManager.top !is NowPlayingCarScreen) {
                // Pop first: the browse stack may already sit at the host's 5-screen cap
                screenManager.popToRoot()
                screenManager.push(newPlaybackScreen())
            }
        }
    }

    /**
     * Media3 handshake from the templated-media-app guide: connecting a
     * browser also starts [SimpleMediaService] when needed, then a custom
     * session command returns the platform token, which the car host needs
     * (compat-wrapped) to render playback state and controls.
     */
    private fun ensureBrowser(): ListenableFuture<MediaBrowser> {
        if (destroyed) return Futures.immediateFailedFuture(IllegalStateException("Car session closed"))
        val ready = readyFuture ?: SettableFuture.create<MediaBrowser>().also {
            readyFuture = it
            retryCount = 0
            connectAndRegisterPlaybackToken()
        }
        // A cancelled screen/search must not cancel the connection shared by every car screen.
        return Futures.nonCancellationPropagating(ready)
    }

    private fun newPlaybackScreen(): Screen = NowPlayingCarScreen(carContext, ::isPlaybackReady, ::retryPlaybackConnection)

    private fun isPlaybackReady(): Boolean = playbackTokenRegistered

    private fun retryPlaybackConnection() {
        ensureBrowser()
    }

    private fun connectAndRegisterPlaybackToken() {
        if (destroyed) return
        val generation = ++connectionGeneration
        val mainExecutor = ContextCompat.getMainExecutor(carContext)
        connectionTimeout = Runnable {
            connectionFailed(generation, TimeoutException("Car media connection timed out"))
        }.also { connectionHandler.postDelayed(it, CONNECTION_TIMEOUT_MS) }
        try {
            val future = MediaBrowser.Builder(
                carContext,
                SessionToken(carContext, ComponentName(carContext, SimpleMediaService::class.java)),
            ).setListener(
                object : MediaBrowser.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        connectionFailed(generation, IllegalStateException("Car media browser disconnected"))
                    }
                },
            ).buildAsync()
            browserFuture = future
            future.addListener({
                if (destroyed || generation != connectionGeneration) return@addListener
                try {
                    val browser = future.get()
                    val resultFuture = browser.sendCustomCommand(
                        SessionCommand(MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN, Bundle.EMPTY),
                        Bundle.EMPTY,
                    )
                    resultFuture.addListener({
                        if (!destroyed && generation == connectionGeneration) {
                            try {
                                val result = resultFuture.get()
                                val platformToken = BundleCompat.getParcelable(
                                    result.extras,
                                    MEDIA_CUSTOM_COMMAND.KEY_PLATFORM_TOKEN,
                                    android.media.session.MediaSession.Token::class.java,
                                )
                                check(result.resultCode == SessionResult.RESULT_SUCCESS && platformToken != null) {
                                    "Media session did not return a playback token"
                                }
                                check(browser.isConnected) { "Media browser disconnected before token registration" }
                                (carContext.getCarService(CarContext.MEDIA_PLAYBACK_SERVICE) as MediaPlaybackManager)
                                    .registerMediaPlaybackToken(MediaSessionCompat.Token.fromToken(platformToken))
                                connectionTimeout?.let(connectionHandler::removeCallbacks)
                                connectionTimeout = null
                                retryCount = 0
                                playbackTokenRegistered = true
                                readyFuture?.set(browser)
                                // Refresh a playback template that the host may have received before the token.
                                runCatching { carContext.getCarService(ScreenManager::class.java).top.invalidate() }
                            } catch (e: Exception) {
                                connectionFailed(generation, e)
                            }
                        }
                    }, mainExecutor)
                } catch (e: Exception) {
                    connectionFailed(generation, e)
                }
            }, mainExecutor)
        } catch (e: Exception) {
            connectionFailed(generation, e)
        }
    }

    private fun connectionFailed(generation: Long, error: Exception) {
        if (destroyed || generation != connectionGeneration) return
        // Invalidate listeners before releasing: release itself may deliver onDisconnected.
        connectionGeneration++
        playbackTokenRegistered = false
        connectionTimeout?.let(connectionHandler::removeCallbacks)
        connectionTimeout = null
        releaseBrowser()
        if (readyFuture?.isDone == true) readyFuture = SettableFuture.create()
        Logger.e(TAG, "Car media connection failed (retry $retryCount)", error)
        runCatching { carContext.getCarService(ScreenManager::class.java).top.invalidate() }
        if (retryCount < MAX_CONNECTION_RETRIES) {
            val delayMs = 1_000L shl retryCount++
            connectionHandler.postDelayed({ connectAndRegisterPlaybackToken() }, delayMs)
        } else {
            val failed = readyFuture
            readyFuture = null
            failed?.setException(error)
            // Do not retain a failed future forever; a later screen/request can try again.
        }
    }

    private fun releaseBrowser() {
        playbackTokenRegistered = false
        val future = browserFuture
        browserFuture = null
        future?.let { MediaController.releaseFuture(it) }
    }

    private companion object {
        private const val TAG = "SimpMusicCarSession"
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val MAX_CONNECTION_RETRIES = 3

        // Docs-defined action for templated media apps; no library constant as of car-app 1.9.0-alpha01
        private const val ACTION_SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"

        // Actual action observed in logcat when the minimized control panel is tapped —
        // the (beta) host sends this instead of the documented one
        private const val ACTION_MEDIA_SHOW_PLAYBACK_VIEW = "MEDIA_SHOW_PLAYBACK_VIEW"
    }
}