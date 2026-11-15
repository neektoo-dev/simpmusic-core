package com.maxrave.domain.manager

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val CAR_PINS_KEY = "car_pinned_local_playlists"
private val carPinsMutex = Mutex()
fun DataStoreManager.carPinnedPlaylists() = getString(CAR_PINS_KEY).map { value ->
    value.orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { it > 0 }.distinct()
}
suspend fun DataStoreManager.toggleCarPinnedPlaylist(id: Long) = carPinsMutex.withLock {
    if (id <= 0) return@withLock
    val pins = carPinnedPlaylists().first().toMutableList()
    if (!pins.remove(id)) pins.add(0, id)
    putString(CAR_PINS_KEY, pins.joinToString(","))
}
