package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.media3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Now-playing screen for Android Auto. The host renders artwork, seek bar and
 * transport controls from the MediaSession token registered by
 * [SimpMusicCarSession]; the app only owns the header — queue name as title
 * plus a queue button, which the classic media surface never allowed us to
 * customize.
 */
internal class NowPlayingCarScreen(
    carContext: CarContext,
    private val isPlaybackReady: () -> Boolean,
    private val retryConnection: () -> Unit,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    screenScope.cancel()
                }
            },
        )
        screenScope.launch {
            handler.queueData.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        if (!isPlaybackReady()) {
            // A MediaPlaybackTemplate is invalid until the host has our session token.
            return MessageTemplate.Builder(carContext.getString(R.string.car_connecting))
                .setHeaderAction(Action.BACK)
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.car_retry))
                        .setOnClickListener { retryConnection() }
                        .build(),
                ).build()
        }
        val queueTitle =
            handler.queueData.value
                ?.data
                ?.playlistName
                ?.takeIf { it.isNotBlank() }
        return MediaPlaybackTemplate
            .Builder()
            .setHeader(
                Header
                    .Builder()
                    .setTitle(queueTitle ?: "Queue")
                    .addEndHeaderAction(
                        Action
                            .Builder()
                            .setIcon(
                                CarIcon
                                    .Builder(
                                        IconCompat.createWithResource(carContext, R.drawable.ic_car_queue_music),
                                    ).build(),
                            ).setOnClickListener {
                                screenManager.push(QueueCarScreen(carContext))
                            }.build(),
                    ).build(),
            ).build()
    }
}