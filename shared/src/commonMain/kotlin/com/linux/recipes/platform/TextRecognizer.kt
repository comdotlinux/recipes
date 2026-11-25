package com.linux.recipes.platform

// Signatures fixed by docs/api.md, "Client, milestone M2". Change only together with that section.

sealed interface Recognition {
    data class Text(val text: String) : Recognition
    data object Unreadable : Recognition
}

/** Google ML Kit on Android, Apple Vision on iOS. On this path the picture never leaves the device. */
interface TextRecognizer {
    suspend fun recognize(jpeg: ByteArray): Recognition
}
