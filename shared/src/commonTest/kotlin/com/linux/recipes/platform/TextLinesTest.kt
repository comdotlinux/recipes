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
    fun nothingReadableIsUnreadable() {
        assertEquals(Recognition.Unreadable, linesInReadingOrder(emptyList()))
        assertEquals(Recognition.Unreadable, linesInReadingOrder(listOf(1f to "  ", 2f to "")))
    }
}
