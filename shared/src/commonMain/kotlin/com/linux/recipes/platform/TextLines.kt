package com.linux.recipes.platform

/** The recognizer of this platform: ML Kit on Android, Vision on iOS. */
expect fun platformTextRecognizer(): TextRecognizer

/**
 * Joins recognised lines top to bottom, one per line, so ingredient lists stay lists. [lines] pairs each line's
 * top edge (any unit, growing downwards) with its text. No readable character gives [Recognition.Unreadable].
 */
/**
 * Recognition languages in priority order: each of the user's [preferred] languages that [supported] contains
 * (exactly, else by language: `de-AT` picks `de-DE`), then English last as the catch-all.
 */
internal fun recognitionLanguages(preferred: List<String>, supported: List<String>): List<String> {
    fun language(tag: String) = tag.substringBefore('-').lowercase()
    val picked = preferred.mapNotNull { p ->
        supported.firstOrNull { it.equals(p, ignoreCase = true) } ?: supported.firstOrNull { language(it) == language(p) }
    }
    val english = supported.firstOrNull { it == "en-US" } ?: supported.firstOrNull { language(it) == "en" }
    return (picked.filter { it != english } + listOfNotNull(english)).distinct()
}

internal fun linesInReadingOrder(lines: List<Pair<Float, String>>): Recognition {
    val text = lines.sortedBy { it.first }.map { it.second.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    return if (text.isEmpty()) Recognition.Unreadable else Recognition.Text(text)
}
