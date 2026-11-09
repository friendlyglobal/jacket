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
    val Headphones = icon("M12 3a9 9 0 0 0-9 9v7c0 1.1.9 2 2 2h4v-8H5v-1a7 7 0 0 1 14 0v1h-4v8h4c1.1 0 2-.9 2-2v-7a9 9 0 0 0-9-9z")
    val Pause = icon("M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val SkipForward = icon("M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z", mirror = true)
    val SkipBack = icon("M6 6h2v12H6zm3.5 6 8.5 6V6z", mirror = true)
    val Person = icon("M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-3.3 0-8 1.7-8 5v1h16v-1c0-3.3-4.7-5-8-5z")
    val Search = icon("M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z")
    val Close = icon("M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    val Contents = icon("M3 9h14V7H3v2zm0 4h14v-2H3v2zm0 4h14v-2H3v2zm16 0h2v-2h-2v2zm0-10v2h2V7h-2zm0 6h2v-2h-2v2z")
    val TextFormat = icon("M2.5 4v3h5v12h3V7h5V4h-13zm19 5h-9v3h3v7h3v-7h3V9z")
    val Info = icon("M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z")
    val Code = icon("M9.4 16.6 4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0 4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z")
    val Palette = icon("M12 3a9 9 0 0 0 0 18c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3-4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm5 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3 4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z")

    private fun icon(path: String, mirror: Boolean = false): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f, autoMirror = mirror)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
