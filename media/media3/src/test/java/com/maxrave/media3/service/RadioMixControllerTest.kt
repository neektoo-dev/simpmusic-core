package com.maxrave.media3.service

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.RadioMixController
import com.maxrave.domain.mediaservice.handler.RadioMixState
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class RadioMixControllerTest {
    private val seed = track("seed")
    private fun track(id: String) = Track(
        album = null, artists = emptyList(), duration = "3:00", durationSeconds = 180, isAvailable = true,
        isExplicit = false, likeStatus = "INDIFFERENT", thumbnails = emptyList(), title = id, videoId = id,
        videoType = "MUSIC_VIDEO_TYPE_ATV", category = null, feedbackTokens = null, resultType = "song",
    )

    @Test fun duplicateTapsShareOneRequestAndSuccessfulMixKeepsSeedFirst() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = RadioMixController(scope)
            val response = CompletableDeferred<Unit>()
            var requests = 0
            var applied: List<Track>? = null
            fun start() = controller.start(seed, { true }, {
                flow { requests++; response.await(); emit(Resource.Success(listOf(track("next"), seed, track("next")) to "continuation")) }
            }, { songs, token -> applied = songs; assertEquals("continuation", token); true })
            start(); start()
            assertEquals(1, requests)
            assertTrue(controller.state.value is RadioMixState.Loading)
            response.complete(Unit)
            assertEquals(listOf("seed", "next"), applied!!.map { it.videoId })
            assertEquals("seed", (controller.state.value as RadioMixState.Active).title)
        } finally { scope.cancel() }
    }

    @Test fun songOrQueueChangeRejectsLateResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = RadioMixController(scope)
            val response = CompletableDeferred<Unit>()
            var current = true
            controller.start(seed, { current }, { flow {
                response.await(); emit(Resource.Success(listOf(track("next")) to null))
            } }, { _, _ -> fail("Must keep the newly selected queue"); true })
            current = false
            response.complete(Unit)
            assertEquals(RadioMixState.Failed(RadioMixState.Reason.TRACK_CHANGED), controller.state.value)
        } finally { scope.cancel() }
    }

    @Test fun emptyAndFailedRequestsLeaveQueueAloneAndAllowRetry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = RadioMixController(scope)
            var confirmations = 0
            scope.launch { controller.started.collect { confirmations++ } }
            controller.start(seed, { true }, { flowOf(Resource.Error("offline")) }, { _, _ -> fail(); false })
            assertEquals(RadioMixState.Failed(RadioMixState.Reason.NETWORK), controller.state.value)
            controller.start(seed, { true }, { flowOf(Resource.Success(listOf(seed) to null)) }, { _, _ -> fail(); false })
            assertEquals(RadioMixState.Failed(RadioMixState.Reason.EMPTY), controller.state.value)
            controller.start(seed, { true }, { flow { throw IllegalStateException("network") } }, { _, _ -> fail(); false })
            assertEquals(RadioMixState.Failed(RadioMixState.Reason.NETWORK), controller.state.value)
            assertEquals(0, confirmations)
            controller.start(seed, { true }, { flowOf(Resource.Success(listOf(track("next")) to null)) }, { _, _ -> true })
            assertTrue(controller.state.value is RadioMixState.Active)
            assertEquals(1, confirmations)
        } finally { scope.cancel() }
    }

    @Test fun successfulConfirmationIsEmittedOnceAndNeverReplayedOnReconnection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = RadioMixController(scope)
            val ready = CompletableDeferred<Unit>()
            var confirmations = 0
            val observer = scope.launch { controller.started.collect { confirmations++ } }
            fun start() = controller.start(seed, { true }, { flow {
                ready.await(); emit(Resource.Success(listOf(track("next")) to null))
            } }, { _, _ -> true })
            start(); start()
            assertEquals(0, confirmations)
            ready.complete(Unit)
            assertEquals(1, confirmations)
            observer.cancel()
            var reconnectedConfirmations = 0
            scope.launch { controller.started.collect { reconnectedConfirmations++ } }
            assertTrue(controller.state.value is RadioMixState.Active)
            assertEquals(0, reconnectedConfirmations)
            start()
            assertEquals(1, reconnectedConfirmations)
        } finally { scope.cancel() }
    }

    @Test fun busyPlayerReportsFailureInsteadOfClaimingTheMixStarted() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = RadioMixController(scope)
            controller.start(seed, { true }, { flowOf(Resource.Success(listOf(track("next")) to null)) }, { _, _ -> false })
            assertEquals(RadioMixState.Failed(RadioMixState.Reason.PLAYER_BUSY), controller.state.value)
        } finally { scope.cancel() }
    }
}
