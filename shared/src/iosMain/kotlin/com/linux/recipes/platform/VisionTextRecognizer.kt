package com.linux.recipes.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSLocale
import platform.Foundation.NSSelectorFromString
import platform.Foundation.dataWithBytes
import platform.Foundation.preferredLanguages
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate

actual fun platformTextRecognizer(): TextRecognizer = VisionTextRecognizer()

/**
 * Apple Vision text recognition (accurate level, language correction on), on the device only.
 * Vision works synchronously, so it runs on [Dispatchers.Default], never on the main thread.
 * Any failure reads as [Recognition.Unreadable]; the repository then sends the photo instead.
 */
class VisionTextRecognizer : TextRecognizer {
    override suspend fun recognize(jpeg: ByteArray): Recognition = try {
        withContext(Dispatchers.Default) { linesInReadingOrder(visionLines(jpeg)) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        Recognition.Unreadable
    }
}

/**
 * Accurate level with language correction, in the user's languages that Vision supports, English last.
 * `supportedRecognitionLanguages` needs iOS 15 (the deployment target); `automaticallyDetectsLanguage` needs
 * iOS 16, so it is only set where the request responds to it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun textRequest(preferred: List<String> = NSLocale.preferredLanguages.filterIsInstance<String>()): VNRecognizeTextRequest =
    VNRecognizeTextRequest().apply {
        recognitionLevel = VNRequestTextRecognitionLevelAccurate
        usesLanguageCorrection = true
        val supported = supportedRecognitionLanguagesAndReturnError(null).orEmpty().filterIsInstance<String>()
        recognitionLanguages(preferred, supported).takeIf { it.isNotEmpty() }?.let { recognitionLanguages = it }
        if (respondsToSelector(NSSelectorFromString("setAutomaticallyDetectsLanguage:"))) automaticallyDetectsLanguage = true
    }

@OptIn(ExperimentalForeignApi::class)
private fun visionLines(jpeg: ByteArray): List<Pair<Float, String>> {
    if (jpeg.isEmpty()) return emptyList()
    val data = jpeg.usePinned { NSData.dataWithBytes(it.addressOf(0), jpeg.size.convert()) }
    val request = textRequest()
    val handler = VNImageRequestHandler(data = data, options = emptyMap<Any?, Any>())
    if (!handler.performRequests(listOf(request), error = null)) return emptyList()
    return request.results.orEmpty().mapNotNull { result ->
        val observation = result as? VNRecognizedTextObservation ?: return@mapNotNull null
        val text = (observation.topCandidates(1u).firstOrNull() as? VNRecognizedText)?.string ?: return@mapNotNull null
        // Vision's box is normalised with the origin at the lower left: the top edge is 1 - (y + height) from the top.
        val top = observation.boundingBox.useContents { 1.0 - (origin.y + size.height) }
        top.toFloat() to text
    }
}
