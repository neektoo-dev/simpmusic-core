package com.maxrave.media3.service

import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.maxrave.domain.data.player.GenericCommandButton

/**
 * The classic Android Auto playback view has one optional primary action slot. Multiple custom
 * actions turn it into an overflow menu, even if Radio is first. Publish only Radio while connected
 * to a car, retaining the latest full layout for disconnection. This also affects the phone's system
 * media notification because legacy controllers share a platform session; in-app controls are separate.
 */
internal class CarMediaButtonLayout(
    private val connectionType: LiveData<Int>,
    private val publish: (List<GenericCommandButton>) -> Unit,
) {
    private var latestButtons: List<GenericCommandButton> = listOf(GenericCommandButton.Radio)
    private var isConnectedToCar = false
    private var supportsBrowseControls: Boolean? = null
    private val connectionObserver = Observer<Int> { type ->
        val connected =
            type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
        if (isConnectedToCar && !connected) supportsBrowseControls = null
        isConnectedToCar = connected
        publishLayout()
    }

    fun setBrowseControlsSupported(supported: Boolean) {
        supportsBrowseControls = supported
        publishLayout()
    }

    fun start() {
        // MediaLibraryService isn't a LifecycleOwner. Pair this with stop() in onDestroy.
        connectionType.observeForever(connectionObserver)
    }

    fun updateButtons(buttons: List<GenericCommandButton>) {
        latestButtons = buttons.toList()
        publishLayout()
    }

    fun stop() {
        connectionType.removeObserver(connectionObserver)
    }

    private fun publishLayout() {
        // Older hosts cannot render the Controls screen: keep all actions in their playback menu.
        publish(if (isConnectedToCar && supportsBrowseControls != false) listOf(GenericCommandButton.Radio) else latestButtons)
    }
}
