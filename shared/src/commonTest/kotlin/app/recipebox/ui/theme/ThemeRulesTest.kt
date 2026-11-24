package app.recipebox.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeRulesTest {
    private val schemes = mapOf("light" to LightColors, "dark" to DarkColors)

    @Test
    fun noColourHasHueBetween250And320() {
        val purple = schemes.flatMap { (name, scheme) ->
            scheme.roles().filter { (_, color) -> hue(color)?.let { it in 250.0..320.0 } == true }
                .map { (role, color) -> "$name.$role=${color.hex()} hue=${hue(color)}" }
        }
        assertTrue(purple.isEmpty(), "purple colours: $purple")
    }

    @Test
    fun textOnBackgroundPairsReachContrast4_5() {
        val low = schemes.flatMap { (name, scheme) ->
            scheme.textPairs().map { (role, fg, bg) -> Triple("$name.$role", fg, bg) }
                .filter { (_, fg, bg) -> contrast(fg, bg) < 4.5 }
                .map { (role, fg, bg) -> "$role ${contrast(fg, bg)}" }
        }
        assertTrue(low.isEmpty(), "pairs below 4.5:1: $low")
    }

    @Test
    fun helpersMatchKnownValues() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.01)
        assertEquals(null, hue(Color(0xFF808080)))
        assertEquals(0.0, hue(Color(0xFFFF0000))!!, 0.5)
        assertEquals(120.0, hue(Color(0xFF00FF00))!!, 0.5)
        assertEquals(270.0, hue(Color(0xFF8000FF))!!, 0.5)
    }

    private fun ColorScheme.roles(): List<Pair<String, Color>> = listOf(
        "primary" to primary, "onPrimary" to onPrimary, "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer, "inversePrimary" to inversePrimary,
        "secondary" to secondary, "onSecondary" to onSecondary, "secondaryContainer" to secondaryContainer,
        "onSecondaryContainer" to onSecondaryContainer, "tertiary" to tertiary, "onTertiary" to onTertiary,
        "tertiaryContainer" to tertiaryContainer, "onTertiaryContainer" to onTertiaryContainer,
        "background" to background, "onBackground" to onBackground, "surface" to surface, "onSurface" to onSurface,
        "surfaceVariant" to surfaceVariant, "onSurfaceVariant" to onSurfaceVariant, "surfaceTint" to surfaceTint,
        "inverseSurface" to inverseSurface, "inverseOnSurface" to inverseOnSurface, "error" to error,
        "onError" to onError, "errorContainer" to errorContainer, "onErrorContainer" to onErrorContainer,
        "outline" to outline, "outlineVariant" to outlineVariant, "scrim" to scrim, "surfaceBright" to surfaceBright,
        "surfaceDim" to surfaceDim, "surfaceContainer" to surfaceContainer, "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest, "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainerLowest" to surfaceContainerLowest, "primaryFixed" to primaryFixed,
        "primaryFixedDim" to primaryFixedDim, "onPrimaryFixed" to onPrimaryFixed,
        "onPrimaryFixedVariant" to onPrimaryFixedVariant, "secondaryFixed" to secondaryFixed,
        "secondaryFixedDim" to secondaryFixedDim, "onSecondaryFixed" to onSecondaryFixed,
        "onSecondaryFixedVariant" to onSecondaryFixedVariant, "tertiaryFixed" to tertiaryFixed,
        "tertiaryFixedDim" to tertiaryFixedDim, "onTertiaryFixed" to onTertiaryFixed,
        "onTertiaryFixedVariant" to onTertiaryFixedVariant,
    )

    private fun ColorScheme.textPairs(): List<Triple<String, Color, Color>> {
        val surfaces = listOf(
            "surface" to surface, "surfaceBright" to surfaceBright, "surfaceDim" to surfaceDim,
            "surfaceContainerLowest" to surfaceContainerLowest, "surfaceContainerLow" to surfaceContainerLow,
            "surfaceContainer" to surfaceContainer, "surfaceContainerHigh" to surfaceContainerHigh,
            "surfaceContainerHighest" to surfaceContainerHighest, "surfaceVariant" to surfaceVariant,
        )
        return surfaces.flatMap { (name, bg) ->
            listOf(
                Triple("onSurface/$name", onSurface, bg),
                Triple("onSurfaceVariant/$name", onSurfaceVariant, bg),
                Triple("primary/$name", primary, bg),
            )
        } + listOf(
            Triple("onBackground/background", onBackground, background),
            Triple("primary/background", primary, background),
            Triple("onPrimary/primary", onPrimary, primary),
            Triple("onPrimaryContainer/primaryContainer", onPrimaryContainer, primaryContainer),
            Triple("onSecondary/secondary", onSecondary, secondary),
            Triple("onSecondaryContainer/secondaryContainer", onSecondaryContainer, secondaryContainer),
            Triple("onTertiary/tertiary", onTertiary, tertiary),
            Triple("onTertiaryContainer/tertiaryContainer", onTertiaryContainer, tertiaryContainer),
            Triple("onError/error", onError, error),
            Triple("onErrorContainer/errorContainer", onErrorContainer, errorContainer),
            Triple("inverseOnSurface/inverseSurface", inverseOnSurface, inverseSurface),
            Triple("inversePrimary/inverseSurface", inversePrimary, inverseSurface),
            Triple("onPrimaryFixed/primaryFixed", onPrimaryFixed, primaryFixed),
            Triple("onPrimaryFixed/primaryFixedDim", onPrimaryFixed, primaryFixedDim),
            Triple("onPrimaryFixedVariant/primaryFixed", onPrimaryFixedVariant, primaryFixed),
            Triple("onSecondaryFixed/secondaryFixed", onSecondaryFixed, secondaryFixed),
            Triple("onSecondaryFixed/secondaryFixedDim", onSecondaryFixed, secondaryFixedDim),
            Triple("onSecondaryFixedVariant/secondaryFixed", onSecondaryFixedVariant, secondaryFixed),
            Triple("onTertiaryFixed/tertiaryFixed", onTertiaryFixed, tertiaryFixed),
            Triple("onTertiaryFixed/tertiaryFixedDim", onTertiaryFixed, tertiaryFixedDim),
            Triple("onTertiaryFixedVariant/tertiaryFixed", onTertiaryFixedVariant, tertiaryFixed),
        )
    }

    /** HSL hue in degrees, or null for a grey, which has no hue. */
    private fun hue(c: Color): Double? {
        val r = c.red.toDouble()
        val g = c.green.toDouble()
        val b = c.blue.toDouble()
        val mx = max(r, max(g, b))
        val d = mx - min(r, min(g, b))
        if (d < 1e-9) return null
        val h = when (mx) {
            r -> ((g - b) / d).mod(6.0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return h * 60
    }

    /** WCAG 2 contrast ratio. */
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float) = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun Color.hex(): String =
        listOf(red, green, blue).joinToString("", "#") { (it * 255).roundToInt().toString(16).padStart(2, '0') }
}
