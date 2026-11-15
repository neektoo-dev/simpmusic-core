package com.maxrave.media3.service.callback

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.maxrave.media3.exoplayer.DelegatingForwardingPlayer

/** CastPlayer uses this forwarding base around our local player when casting is inactive. */
@UnstableApi
class CastForwardedVoiceSessionTest : MediaVoiceSessionTest() {
    override fun sessionPlayer(player: Player): Player = ForwardingSimpleBasePlayer(DelegatingForwardingPlayer(player))
}