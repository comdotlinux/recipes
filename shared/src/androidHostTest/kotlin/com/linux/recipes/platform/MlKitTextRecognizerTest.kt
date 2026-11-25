package com.linux.recipes.platform

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wrapper around ML Kit: decoding, error and empty paths, reading order. Robolectric cannot run ML Kit's
 * native model, so the real recognition (quality, speed, rotated pictures) is only checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MlKitTextRecognizerTest {
    private val jpeg: ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
    }.toByteArray()

    @Test
    fun bytesThatAreNoPictureAreUnreadable() = runTest {
        var called = false
        val recognizer = MlKitTextRecognizer { called = true; emptyList() }
        assertEquals(Recognition.Unreadable, recognizer.recognize(byteArrayOf(1, 2, 3)))
        assertEquals(Recognition.Unreadable, recognizer.recognize(ByteArray(0)))
        assertTrue("ML Kit is not asked without a picture", !called)
    }

    @Test
    fun aFailingRecognitionIsUnreadableInsteadOfThrowing() = runTest {
        assertEquals(Recognition.Unreadable, MlKitTextRecognizer { throw IllegalStateException("model missing") }.recognize(jpeg))
        assertEquals(Recognition.Unreadable, MlKitTextRecognizer { throw UnsatisfiedLinkError("no native library") }.recognize(jpeg))
    }

    @Test
    fun noTextIsUnreadable() = runTest {
        assertEquals(Recognition.Unreadable, MlKitTextRecognizer { emptyList() }.recognize(jpeg))
    }

    @Test
    fun linesComeBackTopToBottom() = runTest {
        var size = 0 to 0
        val recognizer = MlKitTextRecognizer { bitmap ->
            size = bitmap.width to bitmap.height
            listOf(20f to "1 kg Kartoffeln", 2f to "Kartoffelsalat")
        }
        assertEquals(Recognition.Text("Kartoffelsalat\n1 kg Kartoffeln"), recognizer.recognize(jpeg))
        assertEquals(64 to 32, size)
    }
}
