package io.github.friendlyglobal.jacket.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The handful of icons the app needs, as plain path data (keeps the APK free of icon packs). */
object JacketIcons {
    val Back = icon("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z", mirror = true)
    val Add = icon("M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z")
    val Check = icon("M9.55 17.6 4.3 12.35l1.4-1.4 3.85 3.85 8.75-8.75 1.4 1.4z")
    val Previous = icon("M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z", mirror = true)
    val Next = icon("M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z", mirror = true)
    val TextSmaller = icon("M3 12h7v2H3z M14 5h3v14h-3z M11 5h9v3h-9z")
    val TextLarger = icon("M2 12h3V9h2v3h3v2H7v3H5v-3H2z M14 5h3v14h-3z M11 5h9v3h-9z")
    val Delete = icon("M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6zM19 4h-3.5l-1-1h-5l-1 1H5v2h14z")
    val More = icon("M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")
    val Book = icon("M7 2h11a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3zm0 2a1 1 0 0 0-1 1v11.2c.3-.1.6-.2 1-.2h11V4zm0 14a1 1 0 1 0 0 2h11v-2z")
    val OuterScreen = icon("M5 3h6v18H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm8 0h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6zm2 2.5v13h3.5v-13z")
    val Play = icon("M8 5.5v13l10.5-6.5z")
    val Pin = icon("M16 3v2h-1v6l2 3v2h-4.2v5h-1.6v-5H7v-2l2-3V5H8V3z")

    private fun icon(path: String, mirror: Boolean = false): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f, autoMirror = mirror)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
