package com.maxrave.media3.service.callback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSearchRequestTest {
    @Test
    fun structuredAlbumRequestDoesNotBecomeASong() {
        val request = MediaSearchRequest.create("play Discovery", MediaSearchTarget.ALBUM, album = "Discovery", artist = "Daft Punk")
        assertEquals(MediaSearchTarget.ALBUM, request.target)
        assertEquals("Discovery Daft Punk", request.searchText)
    }

    @Test
    fun structuredSongUsesTitleAndArtistInsteadOfTheWholeUtterance() {
        val request = MediaSearchRequest.create("joue ce morceau", title = "One More Time", artist = "Daft Punk")
        assertEquals(MediaSearchTarget.SONG, request.target)
        assertEquals("One More Time Daft Punk", request.searchText)
    }

    @Test
    fun structuredPlaylistAndArtistWorkWithoutQuery() {
        assertEquals(MediaSearchRequest(MediaSearchTarget.PLAYLIST, "Trajet"), MediaSearchRequest.create(null, playlist = "Trajet"))
        assertEquals(MediaSearchRequest(MediaSearchTarget.ARTIST, "Daft Punk"), MediaSearchRequest.create("", artist = "Daft Punk"))
    }

    @Test
    fun rawFrenchAndEnglishPlaylistRequestsAreRecognized() {
        for (query in listOf("joue ma playlist Trajet sur SimpMusic Dev", "play my playlist Trajet", "playlist Trajet")) {
            assertEquals(MediaSearchRequest(MediaSearchTarget.PLAYLIST, "Trajet"), MediaSearchRequest.create(query))
        }
        assertEquals(MediaSearchRequest(MediaSearchTarget.ALBUM, "Discovery"), MediaSearchRequest.create("mets l’album Discovery"))
    }

    @Test
    fun emptyAndGenericRequestsResumeRatherThanSearch() {
        for (query in listOf(null, " ", "mets de la musique", "play music")) {
            assertEquals(MediaSearchTarget.RESUME, MediaSearchRequest.create(query).target)
        }
    }

    @Test
    fun explicitTitlesArePreservedEvenWhenTheySoundLikeCommands() {
        assertEquals(MediaSearchTarget.SONG, MediaSearchRequest.create(null, title = "Music").target)
        assertEquals("Play", MediaSearchRequest.create("Play").query)
        assertEquals("Joue pas", MediaSearchRequest.create("Joue pas").query)
    }

    @Test
    fun favoritesAndDownloadsCanBeRequestedWithoutTheNetwork() {
        assertEquals(MediaSearchTarget.FAVORITES, MediaSearchRequest.create("mets mes chansons préférées").target)
        assertEquals(MediaSearchTarget.DOWNLOADED, MediaSearchRequest.create("joue mes téléchargements").target)
        assertEquals(MediaSearchTarget.FAVORITES, MediaSearchRequest.create(null, playlist = "Mes favoris").target)
        assertEquals(MediaSearchTarget.FAVORITES, MediaSearchRequest.create("joue la playlist favoris").target)
    }

    @Test
    fun matchingIgnoresAccentsCaseAndPunctuationButNotWords() {
        assertEquals(100, mediaMatchScore("ÉTÉ !", "été"))
        assertEquals(0, mediaMatchScore("Air", "Chair"))
        assertTrue(mediaMatchScore("One More Time", "One More Time") > mediaMatchScore("One More Time", "One More Time live"))
    }
}