package com.maxrave.media3.service

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowToneGenerator
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class MixSuccessFeedbackTest {
    @Test fun shortToastAndToneConfirmSuccessAndTheCarLabelExpires() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var confirmed = false
        val feedback = MixSuccessFeedback(context, scope) { confirmed = it }
        val manager = context.getSystemService(AudioManager::class.java)
        val volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            feedback.show()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Mix started", ShadowToast.getTextOfLatestToast())
            assertEquals(Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
            assertTrue(confirmed)
            val tone = ShadowToneGenerator.getPlayedTones().single()
            assertEquals(ToneGenerator.TONE_PROP_ACK, tone.type())
            assertEquals(Duration.ofMillis(300), tone.duration())
            assertEquals(volume, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
            assertFalse(confirmed)
            assertEquals(1, ShadowToast.shownToastCount())
            assertEquals(1, ShadowToneGenerator.getPlayedTones().size)
        } finally { feedback.close(); scope.cancel() }
    }

    @Test fun aNewSuccessReplacesTheOldToastAndCleanupCancelsFeedback() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var confirmed = false
        val feedback = MixSuccessFeedback(RuntimeEnvironment.getApplication(), scope) { confirmed = it }
        try {
            feedback.show()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
            val oldToast = ShadowToast.getLatestToast()
            feedback.show()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
            assertTrue(shadowOf(oldToast).isCancelled)
            assertTrue(confirmed) // The first timer must not clear the second confirmation.
            feedback.close()
            assertFalse(confirmed)
            assertTrue(shadowOf(ShadowToast.getLatestToast()).isCancelled)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_000))
            assertFalse(confirmed)
            assertEquals(2, ShadowToneGenerator.getPlayedTones().size)
        } finally { feedback.close(); scope.cancel() }
    }
}
