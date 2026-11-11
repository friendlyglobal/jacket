package io.github.friendlyglobal.jacket.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.friendlyglobal.jacket.R

/**
 * Free books: a shelf of classics whose copyright has run out everywhere, kept by the Jacket
 * project on its own GitHub release so every link points to one place we look after. Jacket has
 * no internet permission of its own, so the shelf opens in the browser; a book downloaded there
 * opens in Jacket like any other file.
 */
object FreeBooks {
    /** The catalogue: the release notes list the books by language, each linking to its file. */
    const val URL = "https://github.com/friendlyglobal/jacket/releases/tag/books"

    val Icon: ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M12 11.55C9.64 9.35 6.48 8 3 8v11c3.48 0 6.64 1.35 9 3.55 2.36-2.19 5.52-3.55 9-3.55V8c-3.48 0-6.64 1.35-9 3.55zM12 8c1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3 1.34 3 3 3z"),
            fill = SolidColor(Color.Black),
        )
        .build()

    fun open(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.free_books_no_browser, Toast.LENGTH_LONG).show()
        }
    }
}
