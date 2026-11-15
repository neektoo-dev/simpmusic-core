package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface RadioMixState {
    data object Idle : RadioMixState
    data class Loading(val seedId: String, val title: String) : RadioMixState
    data class Active(val seedId: String, val title: String, val playlistId: String) : RadioMixState
    data class Failed(val reason: Reason) : RadioMixState
    enum class Reason { NETWORK, EMPTY, TRACK_CHANGED, PLAYER_BUSY }
}

/** One request at a time. A late response must never replace a newly selected song/playlist. */
class RadioMixController(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<RadioMixState>(RadioMixState.Idle)
    val state: StateFlow<RadioMixState> = mutableState
    private val startedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A fresh completion only; reopening a screen or reconnecting the service must stay silent. */
    val started = startedEvents.asSharedFlow()
    private var request: Job? = null

    fun start(
        seed: Track,
        isCurrent: () -> Boolean,
        fetch: () -> Flow<Resource<Pair<List<Track>, String?>>>,
        apply: suspend (List<Track>, String?) -> Boolean,
    ) {
        if (request?.isActive == true) return
        mutableState.value = RadioMixState.Loading(seed.videoId, seed.title)
        request = scope.launch {
            try {
                val result = withTimeout(20_000) { fetch().first() }
                val data = result.data
                val next = data?.first.orEmpty().filter { it.videoId.isNotBlank() && it.videoId != seed.videoId }
                    .distinctBy { it.videoId }
                mutableState.value = when {
                    !isCurrent() -> RadioMixState.Failed(RadioMixState.Reason.TRACK_CHANGED)
                    result !is Resource.Success -> RadioMixState.Failed(RadioMixState.Reason.NETWORK)
                    next.isEmpty() -> RadioMixState.Failed(RadioMixState.Reason.EMPTY)
                    !apply(listOf(seed) + next, data?.second) -> RadioMixState.Failed(RadioMixState.Reason.PLAYER_BUSY)
                    else -> RadioMixState.Active(seed.videoId, seed.title, "RDAMVM${seed.videoId}")
                }
                if (mutableState.value is RadioMixState.Active) startedEvents.tryEmit(Unit)
            } catch (e: TimeoutCancellationException) {
                mutableState.value = RadioMixState.Failed(RadioMixState.Reason.NETWORK)
            } catch (e: CancellationException) {
                mutableState.value = RadioMixState.Idle
                throw e
            } catch (e: Exception) {
                mutableState.value = RadioMixState.Failed(RadioMixState.Reason.NETWORK)
            }
        }
    }
}
