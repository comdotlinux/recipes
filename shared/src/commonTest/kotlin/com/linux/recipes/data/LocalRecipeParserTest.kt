package com.linux.recipes.data

import com.linux.recipes.ui.edit.Limits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalRecipeParserTest {
    private val id = "5b1c2d3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e"

    private class Case(
        val name: String,
        val text: String,
        val title: String,
        val ingredients: List<Ingredient>,
        val steps: List<String>,
        val language: String,
        val servings: Int? = null,
        val prep: Int? = null,
        val cook: Int? = null,
    )

    private val cases = listOf(
        Case(
            name = "German with headings, servings and times",
            text = """
                Schwäbischer Kartoffelsalat
                Für 4 Personen
                Vorbereitung: 20 Min
                Kochzeit: 25 Min
                Zutaten
                1 kg festkochende Kartoffeln
                1 rote Zwiebel
                250 ml heiße Brühe
                4 EL Weißweinessig
                1 TL Senf
                1 Prise Salz
                Zubereitung
                1. Kartoffeln in der Schale kochen, bis sie gerade
                weich sind.
                2. Noch warm pellen und in dünne Scheiben schneiden.
                3. Mit Brühe, Essig und Senf mischen und 30 Minuten ziehen lassen.
            """,
            title = "Schwäbischer Kartoffelsalat",
            ingredients = listOf(
                Ingredient("1", "kg", "festkochende Kartoffeln"),
                Ingredient("1", "", "rote Zwiebel"),
                Ingredient("250", "ml", "heiße Brühe"),
                Ingredient("4", "EL", "Weißweinessig"),
                Ingredient("1", "TL", "Senf"),
                Ingredient("1", "Prise", "Salz"),
            ),
            steps = listOf(
                "Kartoffeln in der Schale kochen, bis sie gerade weich sind.",
                "Noch warm pellen und in dünne Scheiben schneiden.",
                "Mit Brühe, Essig und Senf mischen und 30 Minuten ziehen lassen.",
            ),
            language = "de",
            servings = 4,
            prep = 20,
            cook = 25,
        ),
        Case(
            name = "English with headings, fractions, ranges and a group",
            text = """
                Lemon Drizzle Cake
                Serves 12
                Prep time: 15 minutes
                Bake: 1 h 5
                Ingredients
                225 g butter
                1 ½ cups self-raising flour
                1/2 tsp baking powder
                2-3 lemons
                For the drizzle:
                ¾ cup icing sugar
                Method
                Heat the oven to 180C.
                Beat the butter and sugar, then fold in the flour.
                Bake for 45 minutes and pour over the drizzle while warm.
            """,
            title = "Lemon Drizzle Cake",
            ingredients = listOf(
                Ingredient("225", "g", "butter"),
                Ingredient("1 ½", "cups", "self-raising flour"),
                Ingredient("1/2", "tsp", "baking powder"),
                Ingredient("2-3", "", "lemons"),
                Ingredient("¾", "cup", "icing sugar", group = "For the drizzle"),
            ),
            steps = listOf(
                "Heat the oven to 180C.",
                "Beat the butter and sugar, then fold in the flour.",
                "Bake for 45 minutes and pour over the drizzle while warm.",
            ),
            language = "en",
            servings = 12,
            prep = 15,
            cook = 65,
        ),
        Case(
            name = "German without headings",
            text = """
                Pfannkuchen
                250 g Mehl
                3 Eier
                500 ml Milch
                Alles zu einem glatten Teig verrühren und kurz ruhen lassen.
                In heißem Fett dünne Pfannkuchen ausbacken.
            """,
            title = "Pfannkuchen",
            ingredients = listOf(Ingredient("250", "g", "Mehl"), Ingredient("3", "", "Eier"), Ingredient("500", "ml", "Milch")),
            steps = listOf(
                "Alles zu einem glatten Teig verrühren und kurz ruhen lassen.",
                "In heißem Fett dünne Pfannkuchen ausbacken.",
            ),
            // No heading and only neutral units: common words and umlauts say German.
            language = "de",
        ),
        Case(
            name = "English without headings, bullets and numbered steps",
            text = """
                Quick tomato sauce
                • 2 tbsp olive oil
                • 1 can tomatoes
                - salt and pepper
                1) Warm the oil in a pan.
                2) Add the tomatoes and simmer
                for 20 minutes.
            """,
            title = "Quick tomato sauce",
            ingredients = listOf(
                Ingredient("2", "tbsp", "olive oil"),
                Ingredient("1", "can", "tomatoes"),
                Ingredient("", "", "salt and pepper"),
            ),
            steps = listOf("Warm the oil in a pan.", "Add the tomatoes and simmer for 20 minutes."),
            language = "en",
        ),
        Case(
            name = "Two-column OCR order: both headings first, then both columns",
            text = """
                Pasta e ceci
                Ingredients
                Method
                400 g chickpeas
                150 g ditalini
                2 garlic cloves
                Soften the garlic in olive oil for two minutes.
                Add chickpeas, stock and pasta; simmer until thick.
            """,
            title = "Pasta e ceci",
            ingredients = listOf(
                Ingredient("400", "g", "chickpeas"),
                Ingredient("150", "g", "ditalini"),
                Ingredient("2", "", "garlic cloves"),
            ),
            steps = listOf("Soften the garlic in olive oil for two minutes.", "Add chickpeas, stock and pasta; simmer until thick."),
            language = "en",
        ),
        Case(
            name = "German two groups, So geht's, servings in the heading",
            text = """
                Zwetschgendatschi
                Zutaten für 1 Blech
                Für den Teig
                500 g Mehl
                1 Pck. Trockenhefe
                Für den Belag
                2 kg Zwetschgen
                So geht's
                Den Teig kneten und eine Stunde gehen lassen.
                Ausrollen, mit Zwetschgen belegen und 40 Min backen.
            """,
            title = "Zwetschgendatschi",
            ingredients = listOf(
                Ingredient("500", "g", "Mehl", group = "Für den Teig"),
                Ingredient("1", "Pck.", "Trockenhefe", group = "Für den Teig"),
                Ingredient("2", "kg", "Zwetschgen", group = "Für den Belag"),
            ),
            steps = listOf(
                "Den Teig kneten und eine Stunde gehen lassen.",
                "Ausrollen, mit Zwetschgen belegen und 40 Min backen.",
            ),
            language = "de",
        ),
        Case(
            name = "Ambiguous quantities stay whole, attached units split, makes sets servings",
            text = """
                Muffins
                Makes 12
                Ingredients
                200g sugar
                2x eggs
                a pinch of salt
                Directions
                Mix everything and bake.
            """,
            title = "Muffins",
            ingredients = listOf(
                Ingredient("200", "g", "sugar"),
                Ingredient("", "", "2x eggs"),
                Ingredient("", "", "a pinch of salt"),
            ),
            steps = listOf("Mix everything and bake."),
            language = "en",
            servings = 12,
        ),
        Case(
            name = "Not a recipe: the title and every other line kept as steps",
            text = """
                Dear Anna,
                thank you for the lovely evening
                and the flowers. See you soon!
                Love, Maria
            """,
            title = "Dear Anna,",
            ingredients = emptyList(),
            steps = listOf("thank you for the lovely evening and the flowers. See you soon!", "Love, Maria"),
            language = "en",
        ),
        Case(
            name = "German Portionen, hours and minutes, hyphenated wrap",
            text = """
                Rinderrouladen
                4 Portionen
                Vorbereitung 30 Min
                Garzeit 1 Std. 30 Min
                Zutaten
                4 Rinderrouladen
                2 EL Senf
                Anleitung
                Die Rouladen mit Senf bestreichen und mit Zwie-
                beln belegen.
                Aufrollen und anbraten.
            """,
            title = "Rinderrouladen",
            ingredients = listOf(Ingredient("4", "", "Rinderrouladen"), Ingredient("2", "EL", "Senf")),
            steps = listOf("Die Rouladen mit Senf bestreichen und mit Zwiebeln belegen.", "Aufrollen und anbraten."),
            language = "de",
            servings = 4,
            prep = 30,
            cook = 90,
        ),
    )

    @Test
    fun everyCase() {
        for (c in cases) {
            val draft = parseRecipeText(c.text.trimIndent(), id)
            assertEquals(c.title, draft.title, c.name)
            assertEquals(c.ingredients, draft.ingredients, c.name)
            assertEquals(c.steps, draft.steps, c.name)
            assertEquals(c.language, draft.language, c.name)
            assertEquals(c.servings, draft.servings, c.name)
            assertEquals(c.prep, draft.prepMinutes, c.name)
            assertEquals(c.cook, draft.cookMinutes, c.name)
            assertEquals("photo", draft.sourceType, c.name)
            assertEquals(id, draft.id, c.name)
            assertEquals("", draft.description, c.name)
            assertEquals(null, draft.sourceUrl, c.name)
        }
    }

    @Test
    fun languageFromHeadingsUnitsCommonWordsAndUmlauts() {
        val cases = listOf(
            "Pfannkuchen\n250 g Mehl\n500 ml Milch\nMehl mit der Milch verrühren und ausbacken." to "de",
            "Pancakes\n250 g flour\n500 ml milk\nWhisk the flour with the milk and fry." to "en",
            "Apfelkuchen\n2 cups flour\n1 tsp cinnamon\nMix the flour and bake." to "en",
            "Grießbrei\n1 l Milch\n100 g Grieß\nIn die Milch rühren." to "de",
            "" to "en",
        )
        for ((text, expected) in cases) assertEquals(expected, recipeLanguage(text), text)
    }

    @Test
    fun countableUnitsAreSplitOffTheItem() {
        val cases = listOf(
            "1 Dose Tomaten" to Ingredient("1", "Dose", "Tomaten"),
            "2 Dosen Kichererbsen" to Ingredient("2", "Dosen", "Kichererbsen"),
            "1 Bund Petersilie" to Ingredient("1", "Bund", "Petersilie"),
            "3 Zehen Knoblauch" to Ingredient("3", "Zehen", "Knoblauch"),
            "2 Stück Ingwer" to Ingredient("2", "Stück", "Ingwer"),
            "1 Becher Sahne" to Ingredient("1", "Becher", "Sahne"),
            "4 Scheiben Toast" to Ingredient("4", "Scheiben", "Toast"),
            "1 Glas Kapern" to Ingredient("1", "Glas", "Kapern"),
            "1 Msp. Muskat" to Ingredient("1", "Msp.", "Muskat"),
            "2 cans chickpeas" to Ingredient("2", "cans", "chickpeas"),
            "3 cloves garlic" to Ingredient("3", "cloves", "garlic"),
            "1 stick butter" to Ingredient("1", "stick", "butter"),
            "1 bunch basil" to Ingredient("1", "bunch", "basil"),
            "2 pieces ginger" to Ingredient("2", "pieces", "ginger"),
            "1 pkg yeast" to Ingredient("1", "pkg", "yeast"),
            "2 garlic cloves" to Ingredient("2", "", "garlic cloves"),
        )
        for ((line, expected) in cases) {
            assertEquals(listOf(expected), parseRecipeText("Title\nZutaten\n$line", id).ingredients, line)
        }
    }

    @Test
    fun limitsAreKeptByTruncatingNeverByFailing() {
        val long = "x".repeat(5000)
        val text = buildString {
            appendLine("T".repeat(300))
            appendLine("Für 500 Personen")
            appendLine("Zutaten")
            appendLine("1".repeat(50) + " g " + "Mehl".repeat(100))
            repeat(150) { appendLine("$it g Zucker") }
            appendLine("Für " + "den langen Teig ".repeat(4))
            appendLine("Zubereitung")
            repeat(150) { appendLine("$long.") }
        }
        val draft = parseRecipeText(text, id)
        assertEquals(Limits.TITLE, draft.title.length)
        assertEquals(null, draft.servings, "500 is out of range and not invented into a valid value")
        assertEquals(Limits.INGREDIENTS, draft.ingredients.size)
        assertTrue(draft.ingredients.all { it.quantity.length <= Limits.QUANTITY && it.unit.length <= Limits.UNIT && it.item.length <= Limits.ITEM && it.item.isNotBlank() })
        assertEquals(Limits.STEPS, draft.steps.size)
        assertTrue(draft.steps.all { it.length <= Limits.STEP })
    }

    @Test
    fun aGroupHeadingIsTruncatedToTheGroupLimit() {
        val draft = parseRecipeText("Kuchen\nFür den " + "Sehrlanger".repeat(10) + "teig\n200 g Mehl", id)
        assertEquals(Limits.GROUP, draft.ingredients.single().group?.length)
    }
}
