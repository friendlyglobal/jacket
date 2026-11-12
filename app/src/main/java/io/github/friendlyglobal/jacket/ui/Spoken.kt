package io.github.friendlyglobal.jacket.ui

/**
 * What a screen reader says of something shown as several pieces (a cover, a title under it, a
 * badge in its corner): the pieces that have something to say, in the order given, as one phrase.
 * Said piece by piece they are several stops for a finger and the same book read out more than
 * once; a piece that only repeats the one before it (a book whose author is given as its title)
 * is said once.
 */
internal fun spoken(separator: String, vararg parts: String?): String {
    val said = ArrayList<String>()
    for (part in parts) {
        val words = part?.trim().orEmpty()
        if (words.isEmpty() || said.lastOrNull().equals(words, ignoreCase = true)) continue
        said += words
    }
    return said.joinToString(separator)
}
