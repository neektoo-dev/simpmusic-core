package com.maxrave.media3.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.widget.Toast
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Service-owned feedback works for phone and car requests, without touching the music player. */
internal class MixSuccessFeedback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConfirmationVisible: (Boolean) -> Unit,
) {
    private var feedbackJob: Job? = null
    private var toast: Toast? = null
    private var generation = 0L

    fun show() {
        val currentGeneration = ++generation
        feedbackJob?.cancel()
        toast?.cancel()
        // A platform text toast disappears automatically and respects accessibility timing.
        toast = Toast.makeText(context, R.string.mix_started, Toast.LENGTH_SHORT).also { it.show() }
        feedbackJob = scope.launch {
            var tone: ToneGenerator? = null
            onConfirmationVisible(true)
            try {
                try {
                    // Same volume/routing category as music. No audio-focus request, pause or volume change.
                    // ToneGenerator's 0..100 scale is logarithmic: 12 attenuates by about
                    // 44 dB in AOSP, easily masked by a playing song. 80 is about -10 dB.
                    tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                    // ACK has two 100ms beeps separated by 100ms; let both be heard.
                    if (!tone.startTone(ToneGenerator.TONE_PROP_ACK, 300)) {
                        Logger.w("MixFeedback", "Confirmation tone could not start")
                    }
                } catch (e: RuntimeException) {
                    // Audio resources may be unavailable: the visual confirmation still succeeds.
                    Logger.w("MixFeedback", "Confirmation tone unavailable: ${e.javaClass.simpleName}")
                }
                // Keep the output alive beyond the sound itself so buffered audio can drain,
                // including on slower Bluetooth/car routes. This does not extend the beep.
                delay(1_000)
                tone?.release()
                tone = null
                delay(1_000)
            } finally {
                tone?.release()
                if (generation == currentGeneration) onConfirmationVisible(false)
            }
        }
    }

    fun close() {
        generation++
        feedbackJob?.cancel()
        toast?.cancel()
        toast = null
        onConfirmationVisible(false)
    }
}
