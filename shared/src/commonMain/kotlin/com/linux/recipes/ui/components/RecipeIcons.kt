package com.linux.recipes.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's own line icons: one 24 dp grid, 1.8 dp round strokes, no fills. Drawn here instead of
 * pulling in the Material icon set so every glyph shares the same quiet weight as Figtree.
 * `Icon` tints them with the content colour.
 */
object RecipeIcons {
    val Search = line("search", "M10.5 4.5a6 6 0 1 0 0 12a6 6 0 1 0 0 -12z M15 15l5 5")
    val Add = line("add", "M12 5v14 M5 12h14")
    val Back = line("back", "M19 12H5 M11 6l-6 6l6 6")
    val Close = line("close", "M6 6l12 12 M18 6L6 18")
    val Edit = line("edit", "M4 20h4L19 9l-4 -4L4 16z M13 7l4 4")
    val Delete = line("delete", "M4.5 7h15 M10 4h4 M6.5 7l1 13h9l1 -13 M10 11v5 M14 11v5")
    val Settings = line("settings", "M4 7h9 M17 7h3 M15 5v4 M4 17h3 M11 17h9 M9 15v4")
    val Copy = line("copy", "M9 9h10v11H9z M5 15V4h10")
    val CloudOff = line(
        "cloud_off",
        "M8.5 18H17a3.5 3.5 0 0 0 1.2 -6.8A6 6 0 0 0 8.6 7.3 M6.3 9.6A4.3 4.3 0 0 0 7 18 M3.5 3.5l17 17",
    )
    val CloudPending = line(
        "cloud_pending",
        "M7 18h10a3.5 3.5 0 0 0 0.5 -7A6 6 0 0 0 6.2 9.6A4.3 4.3 0 0 0 7 18z M12 10.5v5 M9.8 13.5l2.2 2.2l2.2 -2.2",
    )
    val Clock = line("clock", "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0 -16z M12 8v4.5l3 2")
    val Bowl = line("bowl", "M3.5 11.5h17a8.5 8.5 0 0 1 -17 0z M9 3.5c-1.2 1.5 1.2 2.5 0 4.5 M13.5 3.5c-1.2 1.5 1.2 2.5 0 4.5")
    val Camera = line("camera", "M4 8.5h3.2L9 6h6l1.8 2.5H20V19H4z M12 10.2a3.3 3.3 0 1 0 0 6.6a3.3 3.3 0 1 0 0 -6.6z")
    val Gallery = line("gallery", "M4 5h16v14H4z M4 16l5 -5l4 4l2.5 -2.5L20 17 M15.5 8.2a1.3 1.3 0 1 0 0 2.6a1.3 1.3 0 1 0 0 -2.6z")
    val Up = line("up", "M12 19V5 M6 11l6 -6l6 6")
    val Down = line("down", "M12 5v14 M6 13l6 6l6 -6")
    val External = line("external", "M14 5h5v5 M19 5l-8 8 M17 14v5H5V7h5")
    val SignOut = line("sign_out", "M10 5H5v14h5 M15 8l4 4l-4 4 M19 12H9")
    val Import = line("import", "M12 4v10.5 M7.5 10l4.5 4.5l4.5 -4.5 M5 19.5h14")
    val Link = line(
        "link",
        "M10.2 13.8a3.8 3.8 0 0 0 5.4 0l3 -3a3.8 3.8 0 0 0 -5.4 -5.4l-1 1 M13.8 10.2a3.8 3.8 0 0 0 -5.4 0l-3 3a3.8 3.8 0 0 0 5.4 5.4l1 -1",
    )
    val Check = line("check", "M5 12.5l4.5 4.5L19 7.5")
    val Tag = line("tag", "M4 4h7.5l8.5 8.5l-7.5 7.5L4 11.5z M8.2 7a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0 -2.4z")

    private fun line(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}
