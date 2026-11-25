package com.linux.recipes.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class TextLinesTest {
    @Test
    fun linesAreJoinedTopToBottom() {
        val lines = listOf(
            30f to "1 kg Kartoffeln",
            10f to " Kartoffelsalat ",
            50f to "2 Zwiebeln",
            40f to "Zutaten am Rand", // a second block that sits between the list lines
        )
        assertEquals(
            Recognition.Text("Kartoffelsalat\n1 kg Kartoffeln\nZutaten am Rand\n2 Zwiebeln"),
            linesInReadingOrder(lines),
        )
    }

    @Test
    fun recognitionLanguagesFollowTheUserWithEnglishLast() {
        val supported = listOf("en-US", "fr-FR", "it-IT", "de-DE", "es-ES")
        assertEquals(listOf("de-DE", "fr-FR", "en-US"), recognitionLanguages(listOf("de-AT", "en-GB", "fr-FR", "ja-JP"), supported))
        assertEquals(listOf("en-US"), recognitionLanguages(listOf("ja-JP"), supported))
        assertEquals(listOf("de-DE", "en-US"), recognitionLanguages(listOf("de", "de-DE"), supported))
        assertEquals(emptyList(), recognitionLanguages(listOf("de-DE"), emptyList()))
    }

    @Test
    fun nothingReadableIsUnreadable() {
        assertEquals(Recognition.Unreadable, linesInReadingOrder(emptyList()))
        assertEquals(Recognition.Unreadable, linesInReadingOrder(listOf(1f to "  ", 2f to "")))
    }
}
