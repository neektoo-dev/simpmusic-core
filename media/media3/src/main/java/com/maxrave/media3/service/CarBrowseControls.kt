package com.maxrave.media3.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.media3.R

/** Browse actions are independent of the playback action strip, so Radio can stay visible there. */
@UnstableApi
internal object CarBrowseControls {
    const val ROOT = "controls"
    const val LIKE_ADD = "car.favorite.add"
    const val LIKE_REMOVE = "car.favorite.remove"
    const val SHUFFLE_ON = "car.shuffle.on"
    const val SHUFFLE_OFF = "car.shuffle.off"
    const val REPEAT_ONE = "car.repeat.one"
    const val REPEAT_ALL = "car.repeat.all"
    const val REPEAT_OFF = "car.repeat.off"
    private val keys = listOf("favorite", "shuffle", "repeat")
    private val commands = listOf(LIKE_ADD, LIKE_REMOVE, SHUFFLE_ON, SHUFFLE_OFF, REPEAT_ONE, REPEAT_ALL, REPEAT_OFF)
    private val labels = listOf(R.string.car_like_add, R.string.car_like_remove, R.string.car_shuffle_on, R.string.car_shuffle_off,
        R.string.repeat_one, R.string.repeat_all, R.string.repeat_off)
    private val icons = listOf(R.drawable.baseline_favorite_border_24, R.drawable.baseline_favorite_24,
        R.drawable.baseline_shuffle_24, R.drawable.baseline_shuffle_on_24, R.drawable.baseline_repeat_24,
        R.drawable.baseline_repeat_24, R.drawable.baseline_repeat_24)

    fun owns(id: String): Boolean = id == ROOT || id.startsWith("$ROOT/")
    fun commandButtons(context: Context): List<CommandButton> = commands.mapIndexed { index, command ->
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(context.getString(labels[index])).setCustomIconResId(icons[index])
            .setIconUri(iconUri(context, icons[index])).setSessionCommand(SessionCommand(command, Bundle.EMPTY)).build()
    }

    fun root(context: Context): MediaItem = folder(
        ROOT, context.getString(R.string.car_controls), context.getString(R.string.car_controls_subtitle),
        iconUri(context, R.drawable.ic_car_now_playing),
    )

    fun rows(context: Context, song: SongEntity?, state: ControlState, limit: Int): List<MediaItem> {
        if (song == null || limit <= 0) return emptyList()
        val values = listOf(
            context.getString(if (state.isLiked) R.string.car_control_on else R.string.car_control_off),
            context.getString(if (state.isShuffle) R.string.car_control_on else R.string.car_control_off),
            context.getString(when (state.repeatState) {
                RepeatState.One -> R.string.repeat_one
                RepeatState.All -> R.string.repeat_all
                RepeatState.None -> R.string.repeat_off
            }),
        )
        val rowCommands = listOf(
            if (state.isLiked) LIKE_REMOVE else LIKE_ADD,
            if (state.isShuffle) SHUFFLE_OFF else SHUFFLE_ON,
            when (state.repeatState) { RepeatState.None -> REPEAT_ONE; RepeatState.One -> REPEAT_ALL; RepeatState.All -> REPEAT_OFF },
        )
        return rowCommands.mapIndexed { index, command ->
            val actionIndex = commands.indexOf(command)
            val title = if (index == 2) context.getString(R.string.car_control_repeat) else context.getString(labels[actionIndex])
            folder("$ROOT/${song.videoId}/${keys[index]}", title,
                "${values[index]} · ${song.title}", iconUri(context, icons[actionIndex]), listOf(command))
        }
    }

    fun allows(id: String, command: String, song: SongEntity?): Boolean {
        if (song == null || command !in commands) return false
        val key = when (command) { LIKE_ADD, LIKE_REMOVE -> keys[0]; SHUFFLE_ON, SHUFFLE_OFF -> keys[1]; else -> keys[2] }
        return id == "$ROOT/${song.videoId}/$key"
    }

    private fun folder(id: String, title: String, subtitle: String, icon: Uri, commands: List<String> = emptyList()): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title).setSubtitle(subtitle).setArtist(subtitle).setArtworkUri(icon)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setSupportedCommands(commands).setExtras(CarBrowsePresentation.styles()).build(),
        ).build()

    private fun iconUri(context: Context, resource: Int): Uri = Uri.parse("android.resource://${context.packageName}/$resource")
}
