package com.linux.recipes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.figtree_medium
import recipes.shared.generated.resources.figtree_regular
import recipes.shared.generated.resources.figtree_semibold
import recipes.shared.generated.resources.young_serif

// Two bundled OFL families (licences in composeResources/files/licenses): Young Serif, a soft
// cookbook serif, for display and headline text; Figtree, a round geometric sans, for everything
// a cook reads while working. Both cover the ten M4 languages.

@Composable
private fun serif() = FontFamily(Font(Res.font.young_serif, FontWeight.Normal))

@Composable
private fun sans() = FontFamily(
    Font(Res.font.figtree_regular, FontWeight.Normal),
    Font(Res.font.figtree_medium, FontWeight.Medium),
    Font(Res.font.figtree_semibold, FontWeight.SemiBold),
)

@Composable
fun recipeTypography(): Typography {
    val serif = serif()
    val sans = sans()
    fun s(family: FontFamily, size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
        TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em)
    return Typography(
        displayLarge = s(serif, 52, 60, tracking = -0.02),
        displayMedium = s(serif, 42, 50, tracking = -0.02),
        displaySmall = s(serif, 34, 42, tracking = -0.015),
        headlineLarge = s(serif, 30, 38, tracking = -0.01),
        headlineMedium = s(serif, 26, 34, tracking = -0.01),
        headlineSmall = s(serif, 22, 30),
        titleLarge = s(serif, 21, 28),
        titleMedium = s(sans, 17, 24, FontWeight.SemiBold),
        titleSmall = s(sans, 15, 20, FontWeight.SemiBold),
        bodyLarge = s(sans, 17, 26),
        bodyMedium = s(sans, 15, 22),
        bodySmall = s(sans, 13, 18),
        labelLarge = s(sans, 15, 20, FontWeight.SemiBold, 0.01),
        labelMedium = s(sans, 13, 16, FontWeight.Medium, 0.02),
        labelSmall = s(sans, 12, 16, FontWeight.SemiBold, 0.06),
    )
}
