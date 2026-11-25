package com.linux.recipes.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * The Vision wrapper's error paths and the platform wiring. Runs only on the macOS CI job. Recognition quality on
 * real photos (and on the simulator's Vision model) is checked on a device, not here.
 */
class VisionTextRecognizerTest {
    @Test
    fun bytesThatAreNoPictureAreUnreadable() = runTest {
        val recognizer = VisionTextRecognizer()
        assertEquals(Recognition.Unreadable, recognizer.recognize(ByteArray(0)))
        assertEquals(Recognition.Unreadable, recognizer.recognize(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun theIosRecognizerIsVision() {
        assertIs<VisionTextRecognizer>(platformTextRecognizer())
    }
}
