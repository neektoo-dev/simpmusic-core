package com.maxrave.media3.service

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Binder
import android.os.IBinder
import androidx.car.app.connection.CarConnection
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.carPinnedPlaylists
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.mediaservice.handler.RadioMixState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toCommandButton
import com.maxrave.media3.service.callback.SimpleMediaSessionCallback
import com.maxrave.media3.utils.CoilBitmapLoader
import com.maxrave.media3.utils.sizeLimitedForSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.system.exitProcess

@UnstableApi
internal class SimpleMediaService :
    MediaLibraryService(),
    KoinComponent {
    // Session-level player from DI: the ForwardingPlayer wrapped with Cast support in the
    // full build (plain ForwardingPlayer in the FOSS build).
    private val player: Player by inject<Player>(qualifier = named(com.maxrave.common.Config.MAIN_PLAYER))
    private val coilBitmapLoader: CoilBitmapLoader by inject<CoilBitmapLoader>()

    private var mediaSession: MediaLibrarySession? = null
    private val carControlsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val simpleMediaSessionCallback: MediaLibrarySession.Callback by inject<MediaLibrarySession.Callback>()

    private val simpleMediaServiceHandler: MediaPlayerHandler by inject<MediaPlayerHandler>()

    private val carSettings: DataStoreManager by inject<DataStoreManager>()
    private var mixConfirmationVisible = false
    private val mixSuccessFeedback by lazy {
        MixSuccessFeedback(this, carControlsScope) { visible ->
            mixConfirmationVisible = visible
            carMediaButtonLayout.updateButtons(lastNotificationButtons)
        }
    }
    private var lastNotificationButtons = listOf<GenericCommandButton>(GenericCommandButton.Radio)
    private val carMediaButtonLayout by lazy {
        CarMediaButtonLayout(CarConnection(this).type) { buttons ->
            mediaSession?.setMediaButtonPreferences(buttons.map { button ->
                val command = button.toCommandButton(this)
                if (button == GenericCommandButton.Radio) {
                    val mix = simpleMediaServiceHandler.radioMixState.value
                    val label = when {
                        mix is RadioMixState.Loading -> getString(R.string.mix_loading)
                        mix is RadioMixState.Failed -> getString(R.string.mix_retry)
                        mixConfirmationVisible -> getString(R.string.mix_started)
                        else -> getString(R.string.radio_mix)
                    }
                    CommandButton.Builder(CommandButton.ICON_RADIO).setCustomIconResId(R.drawable.baseline_radio_mix_24)
                        .setSessionCommand(command.sessionCommand!!).setDisplayName(label).build()
                } else command
            })
        }
    }

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        val service: SimpleMediaService
            get() = this@SimpleMediaService

        fun setActivitySession(
            context: Context,
            activity: Class<out Activity>,
        ) {
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, activity),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.w("Service", "Simple Media Service Bound")
        return super.onBind(intent) ?: binder
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        Logger.w("Service", "Simple Media Service Created")

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { MEDIA_NOTIFICATION.NOTIFICATION_ID },
                MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
                R.string.notification_channel_name,
            ).apply {
                setSmallIcon(R.drawable.mono)
            },
        )

        if (mediaSession == null) {
            mediaSession =
                provideMediaLibrarySession(
                    this,
                    player,
                    simpleMediaSessionCallback,
                )
        }

        (simpleMediaSessionCallback as? SimpleMediaSessionCallback)?.onCarBrowseControlsSupported =
            carMediaButtonLayout::setBrowseControlsSupported
        simpleMediaServiceHandler.onUpdateNotification = { buttons ->
            lastNotificationButtons = buttons
            carMediaButtonLayout.updateButtons(buttons)
        }
        carControlsScope.launch {
            // Track metadata can arrive after the notification refresh. Observe both sources so
            // an already open Controls tab updates when a song starts, changes or is liked.
            combine(simpleMediaServiceHandler.nowPlayingState, simpleMediaServiceHandler.controlState) { playing, state ->
                CarBrowseControls.rows(this@SimpleMediaService, playing.songEntity, state, 1)
            }.distinctUntilChanged().collect { rows ->
                mediaSession?.notifyChildrenChanged(CarBrowseControls.ROOT, rows.size, null)
            }
        }
        carControlsScope.launch {
            simpleMediaServiceHandler.radioMixStarted.collect { mixSuccessFeedback.show() }
        }
        carControlsScope.launch {
            simpleMediaServiceHandler.radioMixState.collect { mix ->
                carMediaButtonLayout.updateButtons(lastNotificationButtons)
                if (mix is RadioMixState.Failed) {
                    val message = when (mix.reason) {
                        RadioMixState.Reason.NETWORK -> R.string.mix_error_network
                        RadioMixState.Reason.EMPTY -> R.string.mix_error_empty
                        RadioMixState.Reason.TRACK_CHANGED -> R.string.mix_error_changed
                        RadioMixState.Reason.PLAYER_BUSY -> R.string.mix_error_busy
                    }
                    mediaSession?.sendError(SessionError(SessionError.ERROR_IO, getString(message), Bundle.EMPTY))
                }
            }
        }
        carControlsScope.launch {
            combine(carSettings.carPinnedPlaylists(), simpleMediaServiceHandler.nowPlayingState) { pins, playing ->
                pins to playing.songEntity?.videoId
            }.distinctUntilChanged().collectLatest {
                val callback = simpleMediaSessionCallback as? SimpleMediaSessionCallback ?: return@collectLatest
                val count = withContext(Dispatchers.IO) { callback.carHomeItemCount() }
                mediaSession?.notifyChildrenChanged(SimpleMediaSessionCallback.HOME, count, null)
            }
        }
        carMediaButtonLayout.start()

        val sessionToken = SessionToken(this, ComponentName(this, SimpleMediaService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())
    }

    @UnstableApi
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Logger.w("Service", "Simple Media Service Received Action: ${intent?.action}")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    @UnstableApi
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    @UnstableApi
    fun release() {
        Logger.w("Service", "Starting release process")
        runBlocking {
            try {
                // Release MediaSession (don't release player - CrossfadeExoPlayerAdapter manages it)
                mediaSession?.run {
                    this.player.pause()
                    this.player.playWhenReady = false
                    // Don't call this.player.release() - CrossfadeExoPlayerAdapter manages player lifecycle
                    this.release()
                }
                // Release handler (contains coroutines and jobs, which also releases the adapter)
                simpleMediaServiceHandler.release()
                mediaSession = null
                Logger.w("Service", "Simple Media Service Released")
            } catch (e: Exception) {
                Logger.e("Service", "Error during release")
            }
        }
    }

    @UnstableApi
    override fun onDestroy() {
        mixSuccessFeedback.close()
        carControlsScope.cancel()
        (simpleMediaSessionCallback as? SimpleMediaSessionCallback)?.onCarBrowseControlsSupported = {}
        carMediaButtonLayout.stop()
        super.onDestroy()
        Logger.w("Service", "Simple Media Service Destroyed")
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            release()
        }
    }

    override fun onTrimMemory(level: Int) {
        Logger.w("Service", "Simple Media Service Trim Memory Level: $level")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
    }

    @UnstableApi
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w("Service", "Simple Media Service Task Removed")
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            release()
            super.onTaskRemoved(rootIntent)
            exitProcess(0)
        }
    }

    // Can't inject by Koin because it depend on service
    @UnstableApi
    private fun provideMediaLibrarySession(
        service: MediaLibraryService,
        player: Player,
        callback: MediaLibrarySession.Callback,
    ): MediaLibrarySession =
        MediaLibrarySession
            .Builder(
                service,
                player,
                callback,
            ).setId(this.javaClass.name)
            // Make Radio/Mix available even when a car reconnects to an already paused player,
            // before the handler next refreshes the full notification action list.
            .setMediaButtonPreferences(listOf(GenericCommandButton.Radio.toCommandButton(service)))
            .setCommandButtonsForMediaItems(CarBrowseControls.commandButtons(service))
            // Capped at the platform's own artwork limit so the framework never rescales the shared
            // bitmap in setMetadata — see sizeLimitedForSession (#2500). Falls back to the plain
            // loader if the limit cannot be read.
            .setBitmapLoader(sizeLimitedForSession(service, coilBitmapLoader))
            // Tapping the media notification opens whatever the session activity points at, and the
            // only other place that sets it is MainActivity's bind. A service started without that
            // bind — a headset/Bluetooth play, Android Auto, a widget, a restart after the process
            // was killed — would otherwise post a notification that opens nothing. The launcher
            // activity is resolved at runtime, so this module never has to name MainActivity.
            .apply {
                service.packageManager.getLaunchIntentForPackage(service.packageName)?.let { launch ->
                    setSessionActivity(
                        PendingIntent.getActivity(service, 0, launch, PendingIntent.FLAG_IMMUTABLE),
                    )
                }
            }
            .build()

    private fun isAppInForeground(): Boolean {
        val appProcessInfo = RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
