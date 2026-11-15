package com.maxrave.media3.service.callback

import java.text.Normalizer
import java.util.Locale

internal enum class MediaSearchTarget { ANY, SONG, ARTIST, ALBUM, PLAYLIST, GENRE, FAVORITES, DOWNLOADED, RESUME }

/** Keeps the assistant's structured intent: an album is a queue, not its first matching song. */
internal data class MediaSearchRequest(
    val target: MediaSearchTarget,
    val query: String,
    val artist: String = "",
    val album: String = "",
) {
    val searchText: String
        get() = listOf(query, artist, album).filter(String::isNotBlank).distinct().joinToString(" ")

    companion object {
        fun create(
            query: String?,
            focus: MediaSearchTarget = MediaSearchTarget.ANY,
            title: String? = null,
            artist: String? = null,
            album: String? = null,
            playlist: String? = null,
            genre: String? = null,
        ): MediaSearchRequest {
            val text = query.orEmpty().trim()
            val hints = mapOf(
                MediaSearchTarget.SONG to title,
                MediaSearchTarget.ARTIST to artist,
                MediaSearchTarget.ALBUM to album,
                MediaSearchTarget.PLAYLIST to playlist,
                MediaSearchTarget.GENRE to genre,
            )
            val target = if (focus != MediaSearchTarget.ANY) focus else when {
                !playlist.isNullOrBlank() -> MediaSearchTarget.PLAYLIST
                !title.isNullOrBlank() -> MediaSearchTarget.SONG
                !album.isNullOrBlank() -> MediaSearchTarget.ALBUM
                !artist.isNullOrBlank() -> MediaSearchTarget.ARTIST
                !genre.isNullOrBlank() -> MediaSearchTarget.GENRE
                else -> MediaSearchTarget.ANY
            }
            if (target != MediaSearchTarget.ANY) {
                if (text.isBlank() && hints.values.all { it.isNullOrBlank() }) {
                    return MediaSearchRequest(MediaSearchTarget.RESUME, "")
                }
                val name = hints[target]?.trim()?.takeIf(String::isNotBlank) ?: text
                if (target == MediaSearchTarget.PLAYLIST) {
                    libraryTarget(normalizedMediaText(name))?.let { return MediaSearchRequest(it, "") }
                }
                return MediaSearchRequest(
                    target,
                    name,
                    artist.orEmpty().trim().takeIf { target == MediaSearchTarget.SONG || target == MediaSearchTarget.ALBUM }.orEmpty(),
                    album.orEmpty().trim().takeIf { target == MediaSearchTarget.SONG }.orEmpty(),
                )
            }
            // Some assistants only send the raw phrase. Recognize explicit French/English
            // categories, but leave ordinary song names intact (including a title named "Play").
            val raw = text.replace(Regex("(?i)\\s+(?:sur|on)\\s+simpmusic(?:\\s+dev)?[.!?]*$"), "").trim()
            val normalized = normalizedMediaText(raw)
            if (normalized in setOf("", "musique", "de la musique", "music", "play music", "mets de la musique", "joue de la musique")) {
                return MediaSearchRequest(MediaSearchTarget.RESUME, "")
            }
            val withoutVerb = normalized.replace(Regex("^(?:joue|lance|mets|ecoute|play)\\s+"), "")
            libraryTarget(withoutVerb)?.let { return MediaSearchRequest(it, "") }
            val category = Regex(
                "(?i)^(?:(?:joue|lance|mets|écoute|ecoute|play)\\s+)?(?:(?:ma|mon|la|le|une?|my|the)\\s+|l['’])?" +
                    "(playlist|album|artiste?|titre|chanson|song)\\s+(.+)$",
            ).matchEntire(raw)
            if (category != null) {
                val kind = when (category.groupValues[1].lowercase(Locale.ROOT)) {
                    "playlist" -> MediaSearchTarget.PLAYLIST
                    "album" -> MediaSearchTarget.ALBUM
                    "artist", "artiste" -> MediaSearchTarget.ARTIST
                    else -> MediaSearchTarget.SONG
                }
                if (kind == MediaSearchTarget.PLAYLIST) {
                    libraryTarget(normalizedMediaText(category.groupValues[2]))?.let { return MediaSearchRequest(it, "") }
                }
                return MediaSearchRequest(kind, category.groupValues[2].trim())
            }
            return MediaSearchRequest(MediaSearchTarget.ANY, raw)
        }

        private fun libraryTarget(name: String): MediaSearchTarget? = when (name) {
            "favoris", "mes favoris", "mes titres favoris", "mes chansons preferees", "favorites", "favourites",
            "my favorites", "my favourites", "liked songs" -> MediaSearchTarget.FAVORITES
            "telechargements", "mes telechargements", "ma musique telechargee", "downloaded music", "my downloads" -> MediaSearchTarget.DOWNLOADED
            else -> null
        }
    }
}

internal fun normalizedMediaText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

/** Exact matches win over covers/longer names; ties keep the repository's ranking. */
internal fun mediaMatchScore(query: String, candidate: String): Int {
    val wanted = normalizedMediaText(query)
    val actual = normalizedMediaText(candidate)
    if (wanted.isEmpty() || actual.isEmpty()) return 0
    if (wanted == actual) return 100
    val wantedWords = wanted.split(' ').toSet()
    val actualWords = actual.split(' ').toSet()
    return if (actualWords.containsAll(wantedWords)) 60 else 0
}