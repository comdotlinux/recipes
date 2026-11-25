package com.linux.recipes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
    /** Each recognised line with the top of its bounding box in pixels. */
    private val detect: suspend (Bitmap) -> List<Pair<Float, String>>,
) : TextRecognizer {
    constructor() : this(::mlKitLines)

    override suspend fun recognize(jpeg: ByteArray): Recognition = try {
        withContext(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext Recognition.Unreadable
            try {
                linesInReadingOrder(detect(bitmap))
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

private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

private suspend fun mlKitLines(bitmap: Bitmap): List<Pair<Float, String>> = suspendCancellableCoroutine { continuation ->
    client.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { text ->
            continuation.resume(text.textBlocks.flatMap { block -> block.lines.map { (it.boundingBox?.top ?: 0).toFloat() to it.text } })
        }
        .addOnFailureListener { continuation.resumeWithException(it) }
}
