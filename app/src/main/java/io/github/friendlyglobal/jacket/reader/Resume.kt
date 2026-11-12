package io.github.friendlyglobal.jacket.reader

import androidx.media3.common.Player
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.voice.languageKey
import io.github.friendlyglobal.jacket.voice.regionOf
import io.github.friendlyglobal.jacket.voice.withPhoneRegion
import io.github.friendlyglobal.jacket.widget.canListen
import io.github.friendlyglobal.jacket.widget.readingNow

/*
 * Starting read-aloud with no reader open: the decisions, apart from Android so they can be
 * tested. Which book a Play from a headphone button, the notification, the lock screen or the
 * widget reads; which books the car is offered; and what the player the phone sees is doing.
 * (Where in the book the voice starts is [resumeSpot], with the other rules about places.)
 */

/**
 * The language a book is read aloud in ([readIn]); that language again where it is the
 * listener's choice (or the phone's) rather than the book's own ([chosen]); and the language,
 * with the reader's region, to ask the phone's own voice for ([phoneVoice]; null to go by the book).
 */
internal class Voicing(val readIn: String?, val chosen: String?, val phoneVoice: String?)

/**
 * How a book is voiced. It follows the book's language by itself: the one the library has for it
 * ([bookLanguage]: the listener's choice, or what the book said when first opened), else what its
 * file says ([said]). A text file whose language couldn't be told from its words is read in the
 * phone's ([phoneLanguage]), as it always was.
 */
internal fun voicing(bookLanguage: String?, said: String?, phoneLanguage: String?, text: Boolean): Voicing {
    val readIn = bookLanguage ?: said ?: phoneLanguage.takeIf { text }
    return Voicing(
        readIn = readIn,
        // A language the book doesn't give is the listener's choice (or the phone's), and is read as chosen.
        chosen = readIn?.takeIf { said == null || languageKey(it) != languageKey(said) },
        // The phone's own voice is asked for the reader's accent: "pt" read on a Brazilian phone is "pt-BR".
        phoneVoice = withPhoneRegion(readIn)?.takeIf { regionOf(it).isNotEmpty() && regionOf(said).isEmpty() },
    )
}

/**
 * The book a Play reads when nothing is being read aloud: the one the library counts as being
 * read (the one the widget and the covers show), where it can be read aloud and its file is on
 * this phone ([missing] are the books whose file isn't). Otherwise the most recently opened book
 * that can be: a comic open in the reader doesn't leave a headphone button with nothing to do.
 * Finished books step aside for the ones being read, as they do everywhere, and are read only
 * when there is nothing else. Null when no book can be read aloud.
 */
internal fun resumeBook(state: LibraryState, missing: Set<String>): Book? =
    readingNow(state, missing).firstOrNull { it.canListen }
        ?: state.current?.takeIf { it.canListen && it.id !in missing }
        ?: state.books.filter { it.canListen && it.id !in missing }.maxByOrNull { maxOf(it.lastOpenedAt, it.addedAt) }

/**
 * The books offered to listen to away from the phone's screen (in the car): those being read,
 * the current one first and then the most recently opened, and after them the finished ones.
 * Only books that can be read aloud and whose file is on this phone; no more than [limit].
 */
internal fun booksToListenTo(state: LibraryState, missing: Set<String>, limit: Int = 20): List<Book> {
    val reading = readingNow(state, missing)
    val finished = state.books.filter { it.isFinished && it.id !in missing }.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
    return (reading + finished).filter { it.canListen }.distinctBy { it.id }.take(limit)
}

/**
 * The book asked for by name ("play Dracula"), among [books] in the order they are offered: the
 * first whose title has the words said, or is among them; failing that, the first by an author
 * so named. Null when nothing was said, or nothing matches.
 */
internal fun bookSaid(query: String?, books: List<Book>): Book? {
    val said = query?.trim()?.lowercase().orEmpty()
    if (said.isEmpty()) return null
    fun matches(words: String): Boolean = words.trim().lowercase().let { it.isNotEmpty() && (it.contains(said) || said.contains(it)) }
    return books.firstOrNull { matches(it.title) } ?: books.firstOrNull { matches(it.author) }
}

/**
 * What the phone is told of read-aloud (see [BookPlayer]): the book, and whether it is being
 * read. It is what the notification, the lock screen, headphones and the car go by, whether a
 * reader is open or not.
 */
internal data class PlayerFace(
    /** The book the player is on; null when there is none (nothing is shown, and Play resumes the book being read). */
    val bookId: String?,
    /** One of Media3's `Player.STATE_` values. */
    val playbackState: Int,
    /** The voice is reading, or about to. */
    val playWhenReady: Boolean,
    /** A voice is there to be skipped on and back. */
    val skips: Boolean,
)

/**
 * The player's face, from what read-aloud is doing.
 *
 * [starting] is the book being opened to be read with no reader (Play was pressed away from the
 * reader, and its file is being opened); [reading] the book a voice is on, playing or paused,
 * with [playing] and [preparing] as the reader's own player shows them; [asked] a book chosen
 * (from the car) and not started yet. [wanted] is what the voice has just been asked to do, until
 * it is seen to do it.
 *
 * Getting a book open and a voice ready counts as playing, and is told as buffering: Android
 * gives a service started by a Play a few seconds to show that it is playing, and a natural
 * voice takes a moment to load.
 */
internal fun playerFace(starting: String?, reading: String?, playing: Boolean, preparing: Boolean, asked: String?, wanted: Boolean?): PlayerFace = when {
    starting != null -> PlayerFace(starting, Player.STATE_BUFFERING, playWhenReady = true, skips = false)
    reading != null -> PlayerFace(
        bookId = reading,
        playbackState = if (preparing && playing) Player.STATE_BUFFERING else Player.STATE_READY,
        playWhenReady = wanted ?: playing,
        skips = true,
    )
    else -> PlayerFace(asked, Player.STATE_IDLE, playWhenReady = false, skips = false)
}
