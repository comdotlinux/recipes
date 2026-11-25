package com.linux.recipes.platform

/** The recognizer of this platform: ML Kit on Android, Vision on iOS. */
expect fun platformTextRecognizer(): TextRecognizer

/**
 * Joins recognised lines top to bottom, one per line, so ingredient lists stay lists. [lines] pairs each line's
 * top edge (any unit, growing downwards) with its text. No readable character gives [Recognition.Unreadable].
 */
internal fun linesInReadingOrder(lines: List<Pair<Float, String>>): Recognition {
    val text = lines.sortedBy { it.first }.map { it.second.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    return if (text.isEmpty()) Recognition.Unreadable else Recognition.Text(text)
}
