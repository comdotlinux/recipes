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
 * The wrapper around ML Kit: decoding, orientation, error and empty paths, reading order. Robolectric cannot run
 * ML Kit's native model, so the real recognition (quality, speed) is only checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MlKitTextRecognizerTest {
    private val jpeg: ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
    }.toByteArray()

    /** [jpeg] with an EXIF segment right after the start marker: one IFD entry, Orientation = [orientation]. */
    private fun withOrientation(orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big-endian TIFF header, IFD at offset 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00, // Orientation, SHORT
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun bytesThatAreNoPictureAreUnreadable() = runTest {
        var called = false
        val recognizer = MlKitTextRecognizer { _, _ -> called = true; emptyList() }
        assertEquals(Recognition.Unreadable, recognizer.recognize(byteArrayOf(1, 2, 3)))
        assertEquals(Recognition.Unreadable, recognizer.recognize(ByteArray(0)))
        assertTrue("ML Kit is not asked without a picture", !called)
    }

    @Test
    fun aFailingRecognitionIsUnreadableInsteadOfThrowing() = runTest {
        assertEquals(Recognition.Unreadable, MlKitTextRecognizer { _, _ -> throw IllegalStateException("model missing") }.recognize(jpeg))
        assertEquals(Recognition.Unreadable, MlKitTextRecognizer { _, _ -> throw UnsatisfiedLinkError("no native library") }.recognize(jpeg))
    }

    @Test
    fun noTextIsUnreadable() = runTest {
        assertEquals(Recognition.Unreadable, MlKitTextRecognizer { _, _ -> emptyList() }.recognize(jpeg))
    }

    @Test
    fun linesComeBackTopToBottom() = runTest {
        var size = 0 to 0
        val recognizer = MlKitTextRecognizer { bitmap, _ ->
            size = bitmap.width to bitmap.height
            listOf(20f to "1 kg Kartoffeln", 2f to "Kartoffelsalat")
        }
        assertEquals(Recognition.Text("Kartoffelsalat\n1 kg Kartoffeln"), recognizer.recognize(jpeg))
        assertEquals(64 to 32, size)
    }

    @Test
    fun theExifOrientationReachesMlKitAsRotation() = runTest {
        val rotations = mutableListOf<Int>()
        val recognizer = MlKitTextRecognizer { _, rotation -> rotations += rotation; listOf(0f to "x") }
        recognizer.recognize(withOrientation(6)) // camera held upright: rotate 90 degrees clockwise
        recognizer.recognize(withOrientation(3))
        recognizer.recognize(withOrientation(8))
        recognizer.recognize(withOrientation(1))
        recognizer.recognize(jpeg)
        assertEquals(listOf(90, 180, 270, 0, 0), rotations)
        assertEquals(0, exifRotation(byteArrayOf(1, 2, 3)))
    }
}
