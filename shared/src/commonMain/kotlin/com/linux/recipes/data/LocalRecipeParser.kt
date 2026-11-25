package com.linux.recipes.data

import com.linux.recipes.ui.edit.Limits

/*
 * Turns text read on the phone (lines in reading order) into a recipe draft, without any model. The rules:
 * - The first non-empty line is the title.
 * - "Für 4 Personen", "Serves 4", "4 Portionen", "Makes 12" set the servings; a line starting with
 *   "Vorbereitung"/"Prep" or "Kochzeit"/"Backzeit"/"Garzeit"/"Cook"/"Bake" followed by "20 Min", "1 h 30" or
 *   "45 minutes" sets the times. These lines are used up.
 * - A heading switches the mode: ingredients ("Zutaten", "Ingredients", and "Für den/die/das ...", "For the ...",
 *   which also name the ingredient group) or method ("Zubereitung", "Anleitung", "So geht's", "Method",
 *   "Directions", "Instructions", "Preparation", "Steps").
 * - Before any heading, a line starting with a quantity (digits, ½, 1/2, 2-3, optionally a unit) or a bullet
 *   is an ingredient; any other line belongs to a step. Under an ingredients heading a numbered or sentence-like
 *   line is still a step, and under a method heading a short quantity line is still an ingredient, so text read
 *   from two columns in either order lands in the right place.
 * - An ingredient is split into quantity, unit and item only when that is unambiguous; otherwise the whole
 *   line is the item.
 * - A numbered or bulleted line, or one after a line ending in . ! ? or :, starts a new step; any other line
 *   continues the step above (a wrapped line), and a word broken with a hyphen is joined again.
 * - The language is "de" when German signals outnumber English ones, else "en". Signals are headings, units,
 *   very common words ("und", "mit", "der" against "and", "with", "the") and, for German, ä ö ü ß.
 * Nothing is invented: lines that fit nowhere become steps. Every limit of the form is kept by truncating.
 */

private const val FRACTIONS = "½⅓⅔¼¾⅛⅜⅝⅞⅕⅖⅗⅘⅙⅚"
private const val NUMBER = """(?:\d+\s+\d+/\d+|\d+\s*[$FRACTIONS]|\d+/\d+|\d+(?:[.,]\d+)?|[$FRACTIONS])"""
private val QUANTITY = Regex("""^$NUMBER(?:\s*[-–]\s*$NUMBER|\s+(?:bis|to)\s+$NUMBER)?""")
private val BULLET = Regex("""^[•·*▪◦‣-]\s+""")
private val NUMBERED = Regex("""^(?:\d{1,2}[.)]|(?:schritt|step)\s*\d{1,2}\s*[:.)]?)\s+""", RegexOption.IGNORE_CASE)
private val UNIT_TOKEN = Regex("""^([A-Za-zÄÖÜäöüß]+\.?)(?=\s|$)""")
private val UNITS = setOf(
    "g", "kg", "mg", "ml", "l", "cl", "dl", "el", "tl", "prise", "prisen", "msp", "pck", "päckchen",
    "dose", "dosen", "bund", "zehe", "zehen", "stück", "pkg", "becher", "scheibe", "scheiben", "tasse", "tassen", "glas",
    "cup", "cups", "tbsp", "tsp", "oz", "lb", "lbs", "tablespoon", "tablespoons", "teaspoon", "teaspoons", "pinch",
    "can", "cans", "clove", "cloves", "stick", "sticks", "slice", "slices", "bunch", "piece", "pieces", "package", "packages",
)

private val INGREDIENT_HEADINGS = listOf("zutaten", "ingredients")
private val METHOD_HEADINGS = listOf("zubereitung", "anleitung", "so geht's", "method", "directions", "instructions", "preparation", "steps")
private val GROUP_PREFIXES = listOf("für den ", "für die ", "für das ", "for the ")

private val SERVINGS = listOf(
    Regex("""^(?:für|for)\s+(\d+)\s+(?:personen|portionen|people|persons|servings|portions)"""),
    Regex("""^(\d+)\s+(?:personen|portionen|servings|portions)"""),
    Regex("""^(?:serves|makes|ergibt|yield|yields|servings|portionen|personen)\s*:?\s*(\d+)"""),
)

private const val PREP = "vorbereitungszeit|vorbereitung|zubereitungszeit|arbeitszeit|preparation time|prep time|prep"
private const val COOK = "kochzeit|backzeit|garzeit|cooking time|cook time|baking time|bake time|cook|bake"
private val TIME_KEYWORD = Regex("($PREP)|($COOK)")
private const val MINUTES = "minuten|minutes|minute|mins|min"
private val DURATION = Regex(
    """^\s*(?:zeit|time)?\s*[:.\-–]?\s*(?:(\d+)\s*(?:stunden|stunde|std|hours|hour|hrs|hr|h)\.?(?![a-zäöü])\s*(?:(\d+)\s*(?:$MINUTES)?\.?)?|(\d+)\s*(?:$MINUTES)\.?(?![a-zäöü]))""",
)

private val GERMAN_WORDS = setOf(
    "zutaten", "zubereitung", "anleitung", "geht's", "für", "el", "tl", "prise", "prisen", "msp", "pck", "päckchen",
    "personen", "portionen", "vorbereitung", "kochzeit", "backzeit", "garzeit", "std", "minuten",
    "dose", "dosen", "bund", "zehe", "zehen", "stück", "becher", "scheibe", "scheiben", "tasse", "tassen", "glas",
    "und", "mit", "der", "die", "das", "oder", "bis", "den", "dem", "ein", "eine", "in", "zum", "zur",
)
private val ENGLISH_WORDS = setOf(
    "ingredients", "method", "directions", "instructions", "preparation", "steps", "cup", "cups", "tbsp", "tsp", "oz",
    "lb", "lbs", "pinch", "tablespoon", "tablespoons", "teaspoon", "teaspoons", "serves", "servings", "makes", "prep",
    "cook", "bake", "minutes", "hours", "can", "cans", "clove", "cloves", "stick", "sticks", "slice", "slices", "bunch",
    "piece", "pieces", "package", "packages",
    "and", "with", "the", "of", "to", "for", "or", "until", "a", "an", "in", "into",
)

/** "de" when German headings, units, common words, umlauts and ß outnumber the English signals, else "en". */
internal fun recipeLanguage(text: String): String {
    val lower = text.lowercase().replace('’', '\'')
    val tokens = lower.split(Regex("""[^a-zäöüß']+"""))
    val german = tokens.count { it in GERMAN_WORDS } + lower.count { it in "äöüß" }
    val english = tokens.count { it in ENGLISH_WORDS }
    return if (german > english) "de" else "en"
}

private enum class Mode { None, Ingredients, Method }

/** The draft for [text], with the given draft [id]. Never fails; see the rules at the top of this file. */
fun parseRecipeText(text: String, id: String): RecipeInput {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val title = lines.firstOrNull().orEmpty()
    val ingredients = mutableListOf<Ingredient>()
    val steps = mutableListOf<String>()
    var servings: Int? = null
    var prep: Int? = null
    var cook: Int? = null
    var mode = Mode.None
    var group: String? = null
    var lastWasStep = false

    fun addIngredient(line: String) {
        ingredients += ingredientOf(BULLET.replace(line, ""), group)
        lastWasStep = false
    }

    fun addStep(line: String, numbered: Boolean) {
        val bulleted = BULLET.containsMatchIn(line)
        val clean = BULLET.replace(NUMBERED.replace(line, ""), "")
        val last = steps.lastOrNull()
        steps += when {
            numbered || bulleted || !lastWasStep || last == null || last.last() in ".!?:" -> clean
            // A word broken at the line end: "Zwie-" + "beln".
            last.endsWith("-") && last.length > 1 && last[last.length - 2].isLetter() && clean.first().isLowerCase() ->
                steps.removeAt(steps.lastIndex).dropLast(1) + clean
            else -> steps.removeAt(steps.lastIndex) + " " + clean
        }
        lastWasStep = true
    }

    for (line in lines.drop(1)) {
        val lower = line.lowercase().replace('’', '\'')
        servingsOf(lower)?.let {
            servings = it
            continue
        }
        timesOf(lower)?.let { (p, c) ->
            if (p != null) prep = p
            if (c != null) cook = c
            continue
        }
        headingRest(lower, INGREDIENT_HEADINGS)?.let { rest ->
            mode = Mode.Ingredients
            group = null
            servingsOf(rest.trimStart(' ', ':', '('))?.let { servings = it }
            continue
        }
        if (headingRest(lower, METHOD_HEADINGS) != null) {
            mode = Mode.Method
            continue
        }
        if (isGroupHeading(lower, line)) {
            mode = Mode.Ingredients
            group = line.trimEnd(':', ' ').take(Limits.GROUP)
            continue
        }
        val numbered = NUMBERED.containsMatchIn(line)
        val quantity = QUANTITY.containsMatchIn(BULLET.replace(line, ""))
        val sentence = isSentence(line)
        when (mode) {
            Mode.None -> if (!numbered && !sentence && (quantity || BULLET.containsMatchIn(line))) addIngredient(line) else addStep(line, numbered)
            Mode.Ingredients -> if (numbered || sentence) addStep(line, numbered) else addIngredient(line)
            Mode.Method -> if (!numbered && !sentence && quantity && words(line) <= 6) addIngredient(line) else addStep(line, numbered)
        }
    }

    return RecipeInput(
        id = id,
        title = title.take(Limits.TITLE),
        servings = servings,
        prepMinutes = prep,
        cookMinutes = cook,
        ingredients = ingredients.take(Limits.INGREDIENTS),
        steps = steps.take(Limits.STEPS).map { it.take(Limits.STEP) },
        sourceType = "photo",
        language = recipeLanguage(text),
    )
}

private fun words(line: String) = line.split(Regex("""\s+""")).size

/** Ends like a sentence and is long enough to be one: "Mix everything and bake." */
private fun isSentence(line: String) = line.last() in ".!?" && words(line) >= 4

private fun servingsOf(lower: String): Int? {
    if (words(lower) > 6) return null
    return SERVINGS.firstNotNullOfOrNull { it.find(lower)?.groupValues?.get(1)?.toIntOrNull() }
        ?.takeIf { it in Limits.SERVINGS_MIN..Limits.SERVINGS_MAX }
}

/** Prep and cook minutes from a line that starts with a time keyword, or null when it is no such line. */
private fun timesOf(lower: String): Pair<Int?, Int?>? {
    // "Bake 30 minutes until golden." is a step, not a time line.
    if (isSentence(lower)) return null
    val keywords = TIME_KEYWORD.findAll(lower).toList()
    if (keywords.firstOrNull()?.range?.first != 0) return null
    var prep: Int? = null
    var cook: Int? = null
    keywords.forEachIndexed { i, keyword ->
        val end = keywords.getOrNull(i + 1)?.range?.first ?: lower.length
        val minutes = minutesOf(lower.substring(keyword.range.last + 1, end)) ?: return@forEachIndexed
        if (keyword.groups[1] != null) prep = minutes else cook = minutes
    }
    return if (prep == null && cook == null) null else prep to cook
}

private fun minutesOf(segment: String): Int? {
    val m = DURATION.find(segment) ?: return null
    val (hours, afterHours, plain) = m.destructured
    val total = if (hours.isNotEmpty()) hours.toInt() * 60 + (afterHours.toIntOrNull() ?: 0) else plain.toInt()
    return total.takeIf { it <= Limits.MINUTES_MAX }
}

/** What follows a heading keyword ("" for a bare heading), or null when [lower] is not such a heading. */
private fun headingRest(lower: String, headings: List<String>): String? {
    val line = lower.trimEnd(':', '.', ' ')
    for (heading in headings) {
        if (line == heading) return ""
        if (!line.startsWith(heading) || line[heading.length] !in " :(") continue
        val rest = line.substring(heading.length)
        if (words(rest.trim()) <= 4 && !lower.endsWith(".")) return rest
    }
    return null
}

private fun isGroupHeading(lower: String, line: String) =
    GROUP_PREFIXES.any { lower.startsWith(it) } && line.none { it.isDigit() || it == ',' } && !line.endsWith(".") && words(line) <= 6

/** Quantity, unit and item where the split is unambiguous, else the whole line as the item. */
private fun ingredientOf(line: String, group: String?): Ingredient {
    fun whole() = Ingredient(item = line.take(Limits.ITEM), group = group)
    val quantity = QUANTITY.find(line)?.value ?: return whole()
    val rest = line.substring(quantity.length)
    val attached = rest.isNotEmpty() && !rest.first().isWhitespace()
    val token = UNIT_TOKEN.find(rest.trimStart())?.value
    val unit = token?.takeIf { it.lowercase().trimEnd('.') in UNITS }
    val item = (if (unit != null) rest.trimStart().substring(unit.length) else rest).trim()
    if (item.isEmpty() || (attached && unit == null)) return whole()
    return Ingredient(quantity.trim().take(Limits.QUANTITY), unit.orEmpty().take(Limits.UNIT), item.take(Limits.ITEM), group)
}
