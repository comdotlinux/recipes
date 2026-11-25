package com.linux.recipes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

actual fun platformTextRecognizer(): TextRecognizer = MlKitTextRecognizer()

/**
 * Google ML Kit text recognition, Latin script, with the model bundled in the app (no Play Services download).
 * Decoding runs on [Dispatchers.Default] and ML Kit runs on its own threads, so the main thread never waits.
 * Any failure reads as [Recognition.Unreadable]; the repository then sends the photo instead.
 */
class MlKitTextRecognizer internal constructor(
    /** Each recognised line with the top of its bounding box in pixels; the Int is the rotation in degrees. */
    private val detect: suspend (Bitmap, Int) -> List<Pair<Float, String>>,
) : TextRecognizer {
    constructor() : this(::mlKitLines)

    override suspend fun recognize(jpeg: ByteArray): Recognition = try {
        withContext(Dispatchers.Default) {
            // BitmapFactory ignores the EXIF orientation. Pictures picked in the app arrive upright already:
            // FileKit 0.17.1 compressImage rotates by EXIF (FileKit.android.kt lines 145-146 and 174-205).
            // The tag is still honoured here, for bytes from any other source.
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext Recognition.Unreadable
            try {
                linesInReadingOrder(detect(bitmap, exifRotation(jpeg)))
            } finally {
                bitmap.recycle()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Includes a native model library that failed to load: the photo path still works without on-device text.
        Recognition.Unreadable
    }
}

/** Clockwise rotation in degrees that the JPEG's EXIF orientation asks for; mirrored orientations count as 0. */
internal fun exifRotation(jpeg: ByteArray): Int = try {
    when (ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
} catch (_: Exception) {
    0
}

private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

private suspend fun mlKitLines(bitmap: Bitmap, rotationDegrees: Int): List<Pair<Float, String>> = suspendCancellableCoroutine { continuation ->
    client.process(InputImage.fromBitmap(bitmap, rotationDegrees))
        .addOnSuccessListener { text ->
            continuation.resume(text.textBlocks.flatMap { block -> block.lines.map { (it.boundingBox?.top ?: 0).toFloat() to it.text } })
        }
        .addOnFailureListener { continuation.resumeWithException(it) }
}
