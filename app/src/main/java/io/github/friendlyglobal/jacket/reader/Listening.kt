package io.github.friendlyglobal.jacket.reader

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.data.ReadingMoment
import io.github.friendlyglobal.jacket.data.Saying
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.importer.Bitmaps
import io.github.friendlyglobal.jacket.importer.NoWordsInPdf
import io.github.friendlyglobal.jacket.importer.PdfLocked
import io.github.friendlyglobal.jacket.importer.NothingToRead
import io.github.friendlyglobal.jacket.importer.PdfWords
import io.github.friendlyglobal.jacket.importer.PdfWordsUnavailable
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.widget.canListen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.isRestricted
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-aloud, for the whole app: the one voice Jacket has, whichever book it is on and whether or
 * not a reader is open.
 *
 * The voice used to live in the reader's screen, and went with it: once Android had closed
 * Jacket, a headphone button or the notification's Play had nothing to play. Now it lives here,
 * for as long as the app does. Readium's read-aloud navigator and the book it reads are held by a
 * [ReadAloud], as Readium's own test app holds them in a service rather than in its reader; the
 * phone sees it through one player ([BookPlayer], behind [ListenService]'s media session), and a
 * reader that has the book open shows and steers the same voice by handing it its pages.
 *
 * So Play from a headphone button, the notification, the lock screen, the widget or the car reads
 * the book being read from the place it was left at ([play]), with nothing on screen; and opening
 * that book finds the voice at the sentence it is on, reading on.
 *
 * Main thread only, like the voice itself.
 */
class Listening(private val app: Application) {
    internal val library: Library get() = app.library

    /** The books that are open: the voice and the reader share one opening of a book. */
    internal val books = OpenBooks(app)

    /** The voice's scope: it outlives any screen. Something going wrong in it stops the voice rather than the app. */
    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Read-aloud failed", e)
            runCatching { voice?.stop() }
        },
    )

    /** Who has the voice: the app, reading with no reader open, or a reader's screen (see [HandOver]). */
    private val hand = HandOver<ReadAloud>()

    /**
     * The voice: reading a book aloud (or paused in one), or waiting in an open reader to be
     * started. One at a time: a voice that starts takes the place of whichever was reading.
     */
    internal val voice: ReadAloud? get() = hand.voice

    /** The book being opened to be read with no reader: Play was pressed away from the reader. */
    private var starting: Book? by mutableStateOf(null)
    private var startJob: Job? = null

    /** A book asked for (chosen in the car) and not started yet: the next Play reads it. */
    private var asked: String? by mutableStateOf(null)

    /** What the voice has just been asked to do (true to read), until it is seen to do it. */
    private var wanted: Boolean? by mutableStateOf(null)
    private var wantedJob: Job? = null

    /** The cover shown with the book being read aloud (on the notification, the lock screen, in the car), and whose it is. */
    private var art: Pair<String, ByteArray?>? by mutableStateOf(null)

    /** What the phone sees of read-aloud: one player, whichever book the voice is on. */
    internal val player = BookPlayer(this)

    /** Listening time: it runs while the voice is really speaking, and what it counts goes to the library as reading time. */
    private val listened = ListeningClock()

    /** And the time the player is up (the voice speaking, paused or getting ready), which a page on screen isn't read in (see [listeningMs]). */
    private val playerUp = ListeningClock()

    init {
        // The player's state is Compose state, as the reader's screen needs it to be, and what
        // follows it here does so by watching that state. Compose only tells such watchers of a
        // change when it is asked to, and it is a screen that asks. With no screen of Jacket's
        // (the voice reading with the app closed) nothing would: the notification would go on
        // saying "getting ready", and the sleep timer would never be seen to be set. So it is
        // asked here, a moment after anything changes, as a screen does.
        val telling = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        Snapshot.registerGlobalWriteObserver {
            if (telling.compareAndSet(false, true)) {
                main.post {
                    telling.set(false)
                    Snapshot.sendApplyNotifications()
                }
            }
        }
        // The phone's view of the voice follows it.
        scope.launch { snapshotFlow { face() to art }.collect { player.changed() } }
        scope.launch { snapshotFlow { voice?.listen?.playing }.collect { want(null) } }
        // Once nothing is being read aloud any more (and nothing is starting), the service has no
        // more to do. (Only once it has been: a service just started for a Play is about to be busy.)
        scope.launch { snapshotFlow { busy }.drop(1).collect { if (!it) ListenService.rest() } }
        // The widget's skip and sleep buttons act on the voice, wherever it is reading (see ListenRemote).
        scope.launch {
            snapshotFlow { voice }.collect { v ->
                ListenRemote.player = v?.let { ListenRemote.Player(it.listen, { it }, { it.hasChapters }, { library.state.value.settings.sleepMinutes }) }
            }
        }
        // Read aloud with no reader open, the widget and the covers still show that it is, and
        // the sleep timer's minutes. (A reader that has the book open says so itself, with the
        // chapter and the place in it.)
        scope.launch {
            var reported: String? = null
            snapshotFlow {
                hand.reading?.takeIf { hand.inReader != it.bookId }?.let {
                    ReadingMoment(
                        bookId = it.book.id,
                        chapter = it.chapter,
                        listening = true,
                        playing = it.listen.playing,
                        sleepMinutes = if (it.listen.sleep.timed) it.listen.sleepLeft else 0,
                        sleepAtChapterEnd = it.listen.sleep == SleepTimer.CHAPTER,
                    )
                }
            }.collect { moment ->
                val before = reported
                reported = moment?.bookId
                if (moment != null) library.report(moment)
                // (Not when a reader has opened that book: it is the one reporting now.)
                else if (before != null && hand.inReader != before) library.endMoment(before)
            }
        }
        // The cover of the book the player is on.
        scope.launch {
            snapshotFlow { face().bookId?.let(library.state.value::find)?.let { it.id to it.coverFile } }.collectLatest { shown ->
                val book = shown?.first?.let(library.state.value::find)
                art = if (book == null) null else book.id to withContext(Dispatchers.IO) { coverBytes(book) }
            }
        }
        // A voice reading with no reader open is set up as a reader sets it up (see ReadAloud.tune),
        // and goes on being: a word given another way to be said, the notes' numbers switched
        // off, another speed or another voice chosen while it reads take effect as they do with
        // the book open, where the reader's screen sees to it. (With no screen, nothing did: the
        // voice kept what it started with until it was next started.)
        scope.launch {
            library.state.map { state -> Tuned(state.sayings, state.settings, hand.reading?.let { state.find(it.bookId)?.language }) }
                .distinctUntilChanged().drop(1).collect {
                    val reading = hand.reading?.takeIf { !it.attached } ?: return@collect
                    reading.tune(library.state.value, app.resources.configuration.locales[0]?.toLanguageTag())
                    reading.retune()
                }
        }
        // A book removed from the library can't go on being read.
        scope.launch {
            library.state.collect { state ->
                hand.reading?.takeIf { state.find(it.bookId) == null }?.stop()
            }
        }
        // Listening counts as reading. While the voice is really speaking (not paused, by the
        // listener, the sleep timer or a call; not stopped; not still getting ready) its time goes
        // to the day's reading and to the book: a minute at a time, so the library's "min today"
        // keeps up, and whatever is left over the moment it stops. It is the app's one voice,
        // so this is all listening there is: with the book open, the screen off, Jacket behind
        // another app, from the cover screen's player, the widget or a headset.
        scope.launch {
            snapshotFlow { voice?.takeIf { it.listen.active && it.listen.playing && !it.listen.preparing }?.bookId }.collectLatest { speaking ->
                count(listened.speaking(speaking, SystemClock.uptimeMillis()))
                if (speaking == null) return@collectLatest
                while (true) {
                    delay(LISTENED_EVERY_MS)
                    count(listened.tick(SystemClock.uptimeMillis()))
                }
            }
        }
        // (By the clock a reader times its pages with, which runs on while the phone sleeps.)
        scope.launch {
            snapshotFlow { voice?.takeIf { it.listen.active }?.bookId }.collect { playerUp.speaking(it, SystemClock.elapsedRealtime()) }
        }
    }

    private fun count(stretch: ListeningClock.Heard?) {
        if (stretch != null) library.addListening(stretch.bookId, stretch.millis)
    }

    /**
     * How long listening has been on in [bookId] since Jacket started (the voice speaking, paused
     * or getting ready), in milliseconds of [SystemClock.elapsedRealtime]. A reader compares two of
     * these to know how much of a page's time on screen was spent with the player up: that is
     * listening (counted while the voice speaks, here), never reading as well. A page read before
     * Listen was pressed, or after the player was closed, is reading as ever.
     */
    fun listeningMs(bookId: String): Long = playerUp.timeIn(bookId, SystemClock.elapsedRealtime())

    // ---- For the phone: the player behind the notification, headphones, the widget, the car ----

    /** Something is being read aloud, paused in the middle of being read, or about to be. */
    internal val busy: Boolean get() = starting != null || hand.reading != null

    /** The book the voice is on (reading, paused, or getting ready); null when nothing is being read aloud. */
    internal val bookId: String? get() = starting?.id ?: hand.reading?.bookId

    internal fun face(): PlayerFace {
        val reading = hand.reading
        return playerFace(
            starting = starting?.id,
            reading = reading?.bookId,
            playing = reading?.listen?.playing == true,
            preparing = reading?.listen?.preparing == true,
            asked = asked,
            wanted = wanted,
        )
    }

    /** The book being read aloud and whether the voice is reading or paused, for the library to show (see [Aloud]); null when nothing is. */
    fun aloud(): Aloud? = aloud(face())

    /** The cover to show for [bookId], once it has been read from its file. */
    internal fun artOf(bookId: String): ByteArray? = art?.takeIf { it.first == bookId }?.second

    /**
     * The book a Play reads when nothing is being read aloud: the one open in a reader, if one is
     * (it is what the listener is looking at); else the book being read (see [resumeBook]).
     */
    internal fun toResume(): Book? {
        val state = library.state.value
        return voice?.takeIf { it.attached }?.let { state.find(it.bookId) } ?: resumeBook(state, library.unreadable)
    }

    /** A Play would have something to read. */
    internal fun canPlay(): Boolean = busy || asked != null || toResume() != null

    /** A book is asked for by a controller (chosen in the car, or the one Android was told Play resumes): the next [play] reads it. */
    internal fun choose(bookId: String?) {
        asked = bookId?.takeIf { id -> library.state.value.find(id)?.canListen == true }
    }

    /**
     * Play. A voice that is paused reads on. Otherwise the book asked for, or the book being
     * read, is read from the place it was left at: by the voice a reader has waiting for it, from
     * the page in view, or, with no reader open, by a voice made here.
     */
    internal fun play() {
        val chosen = asked
        val ready = voice?.takeIf { chosen == null || it.bookId == chosen }
        if (ready != null) {
            asked = null
            want(true)
            ready.play()
            return
        }
        val state = library.state.value
        val book = chosen?.let(state::find)?.takeIf { it.canListen && it.id !in library.unreadable } ?: toResume()
        asked = null
        if (book == null) {
            // Nothing here can be read aloud: no book at all, or only comics (and PDFs, on a phone
            // that can't get at their words). Said in words, rather than leave a Play that does nothing.
            if (starting == null) nothingToRead()
            return
        }
        if (starting?.id == book.id) return
        start(book)
    }

    /** Play was pressed (a headphone button, the widget, the notification) and no book can be read aloud: says so, once for a press. */
    internal fun nothingToRead() {
        val now = SystemClock.uptimeMillis()
        if (now - saidNothingAt < SAID_NOTHING_MS) return
        saidNothingAt = now
        // (PDFs are read aloud where the phone's Android can get at their words: only there is it comics alone that can't be.)
        Toast.makeText(app, if (PdfWords.available) R.string.listen_nothing_to_read_comics else R.string.listen_nothing_to_read, Toast.LENGTH_LONG).show()
    }

    private var saidNothingAt = -SAID_NOTHING_MS

    private fun start(book: Book) {
        startJob?.cancel()
        starting = book
        startJob = scope.launch {
            // A PDF that turns out to have no words (a scan): the book that is read instead.
            var instead: Book? = null
            try {
                val open = books.acquire(book)
                try {
                    ensureActive()
                    val refused = when {
                        // (As the reader says of such books: see EpubReader.)
                        open.publication.isRestricted -> R.string.reader_error_protected
                        open.publication.readingOrder.isEmpty() -> R.string.reader_error_no_pages
                        else -> null
                    }
                    if (refused != null) {
                        Toast.makeText(app, refused, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    // A reader may have opened the book, and made its voice, while the file was opened here.
                    val voice = voice?.takeIf { it.bookId == book.id }
                        ?: ReadAloud(app, book, open, hand.stateForVoice(book.id), scope, this@Listening).also {
                            it.tune(library.state.value, app.resources.configuration.locales[0]?.toLanguageTag())
                        }
                    voice.play()
                } finally {
                    // (A voice that started has the book open for itself by now.)
                    books.release(open)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open ${book.sourceName} to read it aloud", e)
                // Whether a PDF has any words is only known once it has been read through. One
                // that has none is passed over from now on (see Library.wordless), and this Play
                // goes to the book that would have been read had that been known: a headphone
                // button is not left with nothing to do because the book on top is a scan.
                // So is a PDF locked with a password that nobody has given yet (see Library.pdfPages).
                instead = if (e is NoWordsInPdf || e is PdfLocked) toResume()?.takeIf { it.id != book.id } else null
                if (instead == null) {
                    val message = when (e) {
                        is NothingToRead -> app.getString(R.string.reader_error_text)
                        // A PDF that can't be read aloud says which of the two reasons it is.
                        is NoWordsInPdf -> app.getString(R.string.pdf_listen_no_text)
                        is PdfWordsUnavailable -> app.getString(R.string.pdf_listen_unavailable)
                        is PdfLocked -> app.getString(R.string.pdf_listen_locked)
                        else -> app.getString(R.string.reader_error_open_book, e.message.orEmpty())
                    }
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                if (starting?.id == book.id) starting = null
            }
            instead?.let(::start)
        }
    }

    internal fun pause() {
        startJob?.cancel()
        starting = null
        want(null)
        voice?.pause()
    }

    /** Stops reading aloud: the sentence the voice is on is the book's place. */
    fun stop() {
        startJob?.cancel()
        starting = null
        asked = null
        hand.reading?.stop()
    }

    /** Next ([delta] +1) and Previous (-1), from a headset, the notification or the car: a sentence on or back. */
    internal fun skip(delta: Int) {
        hand.reading?.skip(delta)
    }

    /** Says what the voice has just been asked to do, for the few moments it takes to be seen doing it; null once it has. */
    private fun want(reading: Boolean?) {
        wantedJob?.cancel()
        wanted = reading
        if (reading != null) {
            wantedJob = scope.launch {
                delay(WANTED_MS)
                wanted = null
            }
        }
    }

    // ---- For the voice --------------------------------------------------------------------------

    /**
     * [voice] is about to read: it is the app's voice from here, and whichever was reading stops
     * (the sentence it was on is its book's place). False if its book has been closed meanwhile.
     */
    internal fun speaking(voice: ReadAloud): Boolean {
        if (!hand.speaking(voice)) return false
        if (starting != null && starting?.id != voice.bookId) {
            startJob?.cancel()
            starting = null
        }
        asked = null
        // Lock screen, notification and headphone controls; keeps playing with the screen off.
        ListenService.start(app)
        return true
    }

    /** [voice] has stopped. With no reader open on its book there is nothing to keep it for. */
    internal fun stopped(voice: ReadAloud) {
        if (this.voice === voice) want(null)
        hand.stopped(voice)
    }

    /**
     * The voice has read its first sentence with no reader open: the book it reads is the one
     * being read now, as if it had been opened, so the widget and the covers show it.
     */
    internal fun heard(bookId: String) {
        if (hand.inReader != bookId) library.markOpened(bookId)
    }

    // ---- For a reader ---------------------------------------------------------------------------

    /**
     * The player's state for the reader of [bookId] to show: that of the voice reading the book
     * already, if one is, so the reader opens on the same player (see [HandOver.stateFor]).
     */
    fun stateFor(bookId: String): ListenState = hand.stateFor(bookId)

    /** A reader has [bookId] on screen. Opening a book ends listening to any other. */
    fun entered(bookId: String) {
        hand.entered(bookId)
        if (starting != null && starting?.id != bookId) {
            startJob?.cancel()
            starting = null
        }
        if (asked != bookId) asked = null
    }

    /** The reader of [bookId], which showed [listen], has gone. A voice still reading goes on without it. */
    fun left(bookId: String, listen: ListenState) = hand.left(bookId, listen)

    /**
     * The voice for the book a reader has open, showing [listen]: the one reading it already
     * (started with no reader open), or a new one that waits to be started. Nothing is changed by
     * asking: the reader says when it is on screen ([install]).
     */
    internal fun voiceFor(book: Book, open: OpenBook, listen: ListenState): ReadAloud =
        hand.voiceOf(book.id, listen) ?: ReadAloud(app, book, open, listen, scope, this)

    /** [voice]'s reader is on screen (see [HandOver.install]). */
    internal fun install(voice: ReadAloud) = hand.install(voice)

    /** [voice]'s reader has gone: unless it is reading still, it goes too. */
    internal fun uninstall(voice: ReadAloud) = hand.uninstall(voice)

    /** The sentence the voice is on in [bookId], if it is reading that book now: a reader opening the book opens there. */
    internal fun sentenceOf(bookId: String): Locator? = hand.reading?.takeIf { it.bookId == bookId }?.sentence

    /** The library's cover of [book], as a JPEG small enough to hand to the phone; null if it has none. */
    internal suspend fun cover(book: Book): ByteArray? = withContext(Dispatchers.IO) { coverBytes(book) }

    private fun coverBytes(book: Book): ByteArray? = runCatching {
        val cover = library.coverOf(book)?.let { Bitmaps.decodeFile(it, COVER_PX) } ?: return null
        ByteArrayOutputStream().also { cover.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }.getOrNull()

    /** What a voice reading with no reader open is set up from: the words to say another way, the settings, and its book's language. */
    private data class Tuned(val sayings: List<Saying>, val settings: Settings, val language: String?)

    private companion object {
        const val TAG = "Listening"

        /** A press of a button comes as a press and a release, and a held one repeats: "nothing to read aloud" is said once for them all. */
        const val SAID_NOTHING_MS = 3_000L

        /** How long the voice is taken at its word that it is about to read, before what it is doing is believed instead. */
        const val WANTED_MS = 3_000L

        /** The cover shown with the player is no taller than this. */
        const val COVER_PX = 512

        /** While the voice speaks, its time is counted as reading this often (and when it stops). */
        const val LISTENED_EVERY_MS = 60_000L
    }
}
