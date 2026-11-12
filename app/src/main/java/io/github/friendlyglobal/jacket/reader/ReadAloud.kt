package io.github.friendlyglobal.jacket.reader

import android.app.Application
import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.importer.TextLayout
import io.github.friendlyglobal.jacket.voice.NaturalTtsEngineProvider
import io.github.friendlyglobal.jacket.voice.NaturalTtsPreferences
import io.github.friendlyglobal.jacket.voice.SentenceLookahead
import io.github.friendlyglobal.jacket.voice.SpeechEdits
import io.github.friendlyglobal.jacket.voice.SpokenEngineProvider
import io.github.friendlyglobal.jacket.voice.VoiceRef
import io.github.friendlyglobal.jacket.voice.spokenSentences
import io.github.friendlyglobal.jacket.voices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse
import kotlin.math.roundToInt

/*
 * Read aloud: Readium's TTS, with the words inking in as they're spoken.
 */

/**
 * The pages of a book while a reader has them on screen: what the voice asks of them, and what it
 * tells them. The voice belongs to the app, not to the reader's screen (see [Listening]), and
 * reads on with no reader open; a reader that opens the book shows and steers the same voice by
 * handing it one of these (see [ReadAloud.attach]).
 */
internal class VoicePage(
    /** Readium's pages; null while there are none yet. */
    val navigator: () -> EpubNavigatorFragment?,
    /** The reader's own scope: what is drawn on its page for a moment (the mark where the voice stopped) ends with it. */
    val scope: CoroutineScope,
    /** The page is being put back after the phone folded, unfolded or turned: not the listener moving it. */
    val resizing: () -> Boolean = { false },
    /** When the reader last moved through the book themselves (see [ReaderNav.movedAt]). */
    val movedAt: () -> Long = { 0L },
    /** The pages are in front of the listener: not behind another app, nor with the screen off. */
    val inSight: () -> Boolean = { true },
    /** The chapter of the contents the page is in, or -1: "end of chapter" stops at chapters that share a file by it. */
    val chapter: () -> Int = { -1 },
) {
    /** Highlight tint and page color, for the read-along decorations. */
    var colors: Pair<Int, Int> = 0 to 0

    /** How strong the veil over the words still to come is: as strong as leaves them readable on this page. */
    var veil = 0.6f

    /** How the mark left where the voice stopped comes and goes (the theme's fades). */
    var hereAppears: AnimationSpec<Float> = snap()
    var hereFades: AnimationSpec<Float> = snap()

    /**
     * The voice has started on a sentence, told as a place the pages can go by. While a reader is
     * open it keeps the book's place, by the page the sentence is on. The second is whether it is
     * an earlier sentence the listener sent the voice back to (the previous sentence, one tapped,
     * the chapter before): only then may the book's progress fall for it.
     */
    var onSpoken: (Locator, Boolean) -> Unit = { _, _ -> }

    /** The voice has read the book's last sentence: there is nothing more of it to hear. */
    var onEnded: () -> Unit = {}

    /**
     * Listening stopped while the listener was looking elsewhere in the book: the sentence the
     * voice had reached, which isn't on the page in front of them.
     */
    var onStoppedElsewhere: (Locator) -> Unit = {}
}

/** The voice a book is read with, and the language where that isn't the one the book gives: what a running voice was opened with. */
internal data class Tuning(val voice: VoiceRef?, val language: String?, val phoneVoiceLanguage: String?)

/**
 * Saves [sentence], one of the voice's, as the place of the book [bookId], [progress] of the way
 * through it: with the Readium position it falls in (about a page of text, of [positions]), or,
 * for a text file, the paragraph it is in, kept beside every saved place. The book's progress
 * only goes down for it if the listener sent the voice [back] (see [Library.updateLocator]).
 */
internal fun Library.saveSpoken(bookId: String, sentence: Locator, progress: Double, positions: List<Locator>, textFile: TextLayout?, back: Boolean) {
    if (textFile != null) {
        val paragraph = textFile.sentencePlace(sentence)
        updateLocator(bookId, sentence.toJSON().toString(), progress.toFloat(), paragraph.paragraph, textFile.paragraphs, paragraph.within, back)
    } else {
        val position = positions.indexOfLast { (it.locations.totalProgression ?: 0.0) <= progress }.coerceAtLeast(0)
        updateLocator(bookId, sentence.toJSON().toString(), progress.toFloat(), position, positions.size, back = back)
    }
}

/**
 * Saves [sentence] as the place of the book [bookId] before how far through the book it is can
 * be told (the book's positions are still being counted): the progress stays what it was, and is
 * counted when it can be. The place is what must not be lost if the app is closed meanwhile.
 */
internal fun Library.saveSpokenUncounted(bookId: String, sentence: Locator, textFile: TextLayout?) {
    val book = state.value.find(bookId) ?: return
    if (textFile != null) {
        val paragraph = textFile.sentencePlace(sentence)
        updateLocator(bookId, sentence.toJSON().toString(), book.progress, paragraph.paragraph, textFile.paragraphs, paragraph.within, back = false)
    } else {
        updateLocator(bookId, sentence.toJSON().toString(), book.progress, book.position, book.sectionCount, back = false)
    }
}

/** Whether [saved], the place a book was left at, is the sentence [stopped] that the voice stopped on: voices cut sentences their own way, so it is known by its opening. */
private fun sameSentence(saved: Locator, stopped: Locator?): Boolean {
    if (stopped == null || !saved.isSpoken || !saved.href.removeFragment().isEquivalent(stopped.href.removeFragment())) return false
    val opening = saved.text.highlight?.let(::bare)?.take(SENTENCE_OPENING).orEmpty()
    return opening.isNotEmpty() && opening == stopped.text.highlight?.let(::bare)?.take(SENTENCE_OPENING)
}

/**
 * The book's place while it is read aloud with no reader open: the sentence the voice is on,
 * saved as it goes, and when it pauses, stops or reads the book to its end. (A reader with the
 * book open keeps the place itself, by the page the sentence is on: see EpubPages.) Here there
 * is no page to ask, so the sentence is counted by where it is in its file, carried on from what
 * the book's place was counted as when the voice started (see [spokenWithin] and [carriedLead]):
 * starting the voice from a headphone button doesn't move the number, and it goes up as the
 * voice reads on.
 */
private class UnseenPlace(private val bookId: String, private val open: OpenBook, private val library: Library) {
    /** The sentence the voice is on. */
    var sentence: Locator? = null
    private var saved: Locator? = null
    private var savedAt = 0L

    /** A save asked for before the book's positions were counted (and whether it was the book's end): made once they are. */
    private var owed: Boolean? = null

    /** The file the voice started in and what its count leads the sentence's own by there; looked for with the first save after [rebase]. */
    private var lead: Pair<Int, Double>? = null
    private var based = true

    /** The listener has sent the voice back since the last save: the one thing that lets the book's progress fall. */
    private var back = false

    /** The voice starts, or goes on, with no reader: its count carries on from the book's own as it is now. */
    fun rebase() {
        based = false
        lead = null
        saved = null
        owed = null
        back = false
    }

    /** A reader has the book open and keeps its place from here: nothing more is saved from here, not even a save that was waiting. */
    fun handedOver() {
        owed = null
        back = false
    }

    /** The place the voice starts from was saved by Jacket 1.0.0, by a count of its own: its progress is put right by the first save, up or down. */
    fun recount() {
        back = true
    }

    /**
     * A new sentence: saved often enough that little is heard twice if the app is closed under
     * the voice, and no oftener (every save writes the library and redraws the widgets).
     * [wentBack] when it is an earlier one the listener sent the voice to.
     */
    fun spoken(wentBack: Boolean) {
        if (wentBack) back = true
        if (SystemClock.uptimeMillis() - savedAt >= SPOKEN_SAVE_MS) save()
    }

    /** The book's positions have been counted: a save that waited for them is made. */
    fun counted() {
        owed?.let { save(ended = it) }
    }

    fun save(ended: Boolean = false) {
        val sentence = sentence ?: return
        if (sentence === saved && !ended) return
        val counted = open.counted ?: run {
            // How far through the book it is can't be told yet. The sentence is saved all the
            // same, at the number the book has: closed now, Jacket would lose the place otherwise.
            library.saveSpokenUncounted(bookId, sentence, open.text?.layout)
            savedAt = SystemClock.uptimeMillis()
            owed = ended || owed == true
            return
        }
        owed = null
        val file = open.contents.resourceOf(sentence.href)
        val told = sentence.locations.progression ?: 0.0
        if (!based) {
            based = true
            val was = library.state.value.find(bookId)?.progress?.toDouble()
            val own = counted.spans.total(file, told)
            if (was != null && own != null) {
                val (wasFile, within) = counted.spans.locate(was)
                lead = carriedLead(sameFile = wasFile == file, counted = was, own = own, within = within, told = told)?.let { file to it }
            }
        }
        val within = spokenWithin(told, page = null, lead = lead?.takeIf { it.first == file }?.second, pages = null)
        // A voice that has read the book to its end has read all of it (see [spokenIsAllOfTheBook]).
        val progress = if (ended) 1.0 else counted.spans.total(file, within) ?: return
        // However it is counted here, the number the book shows doesn't go down for it, unless
        // the listener sent the voice back (the previous sentence, from a headset or the widget).
        library.saveSpoken(bookId, sentence, progress, counted.positions, open.text?.layout, back)
        back = false
        saved = sentence
        savedAt = SystemClock.uptimeMillis()
    }
}

/** What a tap on the page did while the book was read aloud. */
internal enum class TapOutcome {
    /** It wasn't on the words: it's the page's ordinary tap. */
    NO_TEXT,

    /** The voice went to the sentence tapped. */
    MOVED,

    /** It was on words the voice's reading of the file couldn't find. */
    LOST,
}

/**
 * A book read aloud: Readium's read-aloud navigator, and everything Jacket does around it.
 *
 * It belongs to the app, not to the reader's screen (see [Listening]): started from a headphone
 * button, the notification, the widget or the car, it reads with no reader open. A reader that
 * has the book open hands it its pages ([attach]): the same voice then turns them, marks the
 * sentence being read and takes taps on the words. Without pages there is nothing to turn or to
 * ask, and the book's place is kept here (see [UnseenPlace]).
 */
internal class ReadAloud(
    context: Context,
    /** The book, as the library had it when this was made (its place is asked of the library when it is needed). */
    val book: Book,
    private val open: OpenBook,
    /** The player's state: what a reader showing this book shows. */
    override val listen: ListenState,
    /** The app's scope: the voice goes on when a reader's screen has gone. */
    private val scope: CoroutineScope,
    private val owner: Listening,
) : ListenController, BookVoice {
    private val context: Context = context.applicationContext
    private val publication: Publication = open.publication

    /** The book's files as the voice reads them: where its sentences are, in the pages' terms. */
    private val files: SpokenFiles = open.spoken
    override val bookId: String = book.id
    override val attached: Boolean get() = page != null

    /** The pages that follow the voice, while a reader has the book open (see [attach]); null with none. */
    var page: VoicePage? by mutableStateOf(null)
        private set

    /** Readium's pages, when a reader has them on screen. */
    private fun navigator(): EpubNavigatorFragment? = page?.navigator?.invoke()

    /** When the page last moved to follow the voice: moves just after are the voice's, not the listener's. */
    private var followedAt = 0L
    private var here: Job? = null

    /** The sentence the voice is on, as Readium's read-aloud names it, and ([lastPlaced]) as the pages are sent to it. */
    private var lastSpoken: Locator? = null
    private var lastPlaced: Locator? = null

    /** The sentence the voice is on, as a place the pages can go by: where a reader opening the book finds it. Null when nothing is being read. */
    val sentence: Locator? get() = lastPlaced

    /** The book's place while no reader is there to keep it. */
    private val unseen = UnseenPlace(bookId, open, owner.library)

    /** The voice has spoken since it was last started: the book it reads is the one being read. */
    private var heard = false

    /**
     * The listener has just sent the voice somewhere (a sentence on or back, a tapped sentence,
     * another chapter), and it hasn't said where it landed yet. The voice also steps back by
     * itself, a sentence or two when it is taken up again after a while: that is not the
     * listener going back, and the book's progress doesn't fall for it.
     */
    private var sent = false

    /** The chapter the voice is in, by name, as far as the book's files tell (chapters that share a file are told by the page). */
    var chapter by mutableStateOf("")
        private set

    /**
     * The sentence the voice stopped on (or, from the book's saved place, last stopped on): started
     * again on the page that holds it, the voice carries on from it rather than from the top of the page.
     */
    var stoppedAt: Locator? = book.locator?.let { json -> runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull() }

    /** When it stopped there (elapsed-time millis); 0 for a sentence the book was left on some other day. */
    private var stoppedAtTime = 0L

    /** When the voice was last paused (elapsed-time millis); 0 while it speaks, and once the pause has been answered for. */
    private var pausedAt = 0L

    /** The voice is on its way to another chapter (see [readChapter]). */
    private var chapterJob: Job? = null
    private var speed = 1f

    /** The voice and the language the running voice was opened with (see [retune]). */
    private var tuned: Tuning? = null

    /** The natural voice that reads this book, or null for the phone's own. */
    var voice: VoiceRef? = null

    /** The language the listener chose to read in, when it isn't the one the book gives. */
    var language: String? = null
    /** The language, with the reader's region, to ask the phone's own voice for; null to go by the book. */
    var phoneVoiceLanguage: String? = null

    /**
     * What the voice is given in place of the page's words ("Say it this way"): the listener's
     * respellings, and the numbers of notes left out. Whichever voice reads, it reads through this.
     */
    val edits = SpeechEdits()

    /** The notes' numbers of the chapter files the voice is in and near. */
    private val notes = SpokenNotes(publication)

    /** The numbers that point to notes are passed over. */
    var skipNotes = false
        set(value) {
            if (field == value) return
            field = value
            edits.noteMarks = if (value) notes::marksIn else null
            // Switched on while listening: from the next paragraph on.
            if (value) lastSpoken?.let { notesNear(it.href) }
        }

    override val previousLabel = R.string.listen_previous_sentence
    override val nextLabel = R.string.listen_next_sentence
    private val systemFactory by lazy {
        val app = context.applicationContext as Application
        TtsNavigatorFactory(app, publication, SpokenEngineProvider(AndroidTtsEngineProvider(app), edits), tokenizerFactory = spokenSentences(edits))
    }

    /**
     * Has the chapter file [file] read for its notes' numbers before the voice is sent into it,
     * and the files next to it read meanwhile (see [SpokenNotes]). A long file isn't waited for
     * for long: the voice starts, and passes the numbers over from wherever the reading is done.
     */
    private suspend fun readNotes(file: Url?) {
        if (!skipNotes || file == null) return
        val reading = scope.launch { notes.prepare(file) }
        withTimeoutOrNull(NOTES_WAIT_MS) { reading.join() }
        notesNear(file)
    }

    /** The files the voice comes to next from [file] (and the one it would step back into), read for their notes' numbers in good time. */
    private fun notesNear(file: Url) {
        if (!skipNotes) return
        val files = publication.readingOrder
        val at = files.indexOfFirst { it.url().removeFragment().isEquivalent(file.removeFragment()) }
        if (at < 0) return
        scope.launch {
            // Its own first: it may be the first the voice has been in since they were switched on.
            for (i in listOf(at, at + 1, at + 2, at - 1)) files.getOrNull(i)?.let { notes.prepare(it.url()) }
        }
    }

    /** Readium's read-aloud navigator, whichever voice is behind it. */
    private var tts: TtsNavigator<*, *, *, *>? = null

    /** The phone's sound, shared with other apps: the voice stops for a call or a spoken direction, and goes on after it (see [VoiceFocus]). */
    private val focus = VoiceFocus(
        context,
        speaking = { tts?.playback?.value?.playWhenReady == true },
        pause = { tts?.pause() },
        play = { tts?.play() },
    )
    private var submitRate: (Float) -> Unit = {}
    private var session: Job? = null

    override fun start() {
        tts?.let {
            it.play()
            return
        }
        // A fresh start reads from the page in view: the listener is where the voice is.
        listen.offPage = false
        // Still starting up (the voice takes a moment): one session at a time.
        if (session?.isActive == true) return
        begin(from = null)
    }

    /** Starts reading at [from]; else at the top of what's on screen, or, with no page in front of the listener, at the place the book was left at. */
    private fun begin(from: Locator?, playing: Boolean = true) {
        // The voice is back: the mark of where it stopped has done its work.
        here?.cancel()
        tappedSentence = null
        // The app has one voice: whichever was reading stops for this one.
        if (!owner.speaking(this)) return
        heard = false
        listen.active = true
        listen.playing = playing
        // A natural voice needs a moment to load and to make its first sentence: until its first
        // word the player says so, rather than look as if it were already reading.
        listen.preparing = playing && voice != null
        session = scope.launch {
            // The pages to start from are a reader's, in front of the listener. Started with none
            // (a headphone button, the notification, the widget, the car), or with the reader
            // behind another app, the voice starts from the place the book was left at.
            val n = navigator()?.takeIf { page?.inSight?.invoke() == true }
            var left: Locator? = null
            val resume = when {
                from != null -> null
                // Started again on the page where it stopped: the sentence it stopped on, in the block of
                // Readium's own reading of the file that holds it.
                n != null -> resumeOn(n)
                else -> {
                    left = leftAt()
                    left?.let { resumeFrom(it) }
                }
            }
            // Otherwise the first block of text on screen (wherever the page is, if it can't say), as
            // Readium's own read-aloud knows it: named the page's way, the voice could start at the
            // top of the chapter file instead, and take the reader's place there with it.
            val start = from ?: resume?.first?.locator ?: n?.let {
                // What the page shows from its first word on finds the block wherever the page begins.
                val top = runCatching { n.evaluateJavascript(TOP_TEXT_SCRIPT)?.let(::JSONObject)?.optString("text") }.getOrNull()
                files.readAloudStart(n.firstVisibleElementLocator(), n.currentLocator.value, top)
            } ?: left
            // With no reader to count the place, it is counted here: on from what the book says now.
            if (page == null) keepUnseen()
            val tts = open(start) ?: return@launch
            // Readium can only start at a block of text, and the first one on a page has usually
            // begun on the page before. A fresh start passes over the sentences that lie wholly
            // before the page in view, so the voice begins with the first one in front of the
            // listener and the page has no call to move; a change of voice passes over the ones
            // before the sentence it interrupted.
            // Carrying on from where it stopped, it steps on to that sentence first (and from there,
            // still, past any that lie wholly before the page).
            val onPage = if (from == null) {
                resume?.let { (block, offset) -> stepTo(tts, bare(block.text), offset) }
                // Taken up again after a long while, it starts a sentence or two before the one
                // it stopped on, so the listener finds the thread; the page goes there with it
                // if that sentence isn't on the one in view.
                val back = if (resume != null) rewindAfterStop() else 0
                if (back > 0) {
                    stepBack(tts, back)
                    false
                } else {
                    n?.let { skipToPage(tts, it) } == true
                }
            } else {
                skipToSentence(tts, from).let { false }
            }
            if (!isActive) {
                // Stopped while the voice was starting.
                tts.close()
                return@launch
            }
            this@ReadAloud.tts = tts
            // Jacket asks for the phone's sound itself (see VoiceFocus): Readium's way talks over a ringing call.
            // (The lock screen, the notification and headphone buttons reach the voice through the
            // app's own player, whichever book it is on: see BookPlayer.)
            VoiceFocus.leaveTheSoundTo(tts.asMedia3Player())
            launch {
                // A voice that hasn't been told to play yet reports "not playing": that is where it
                // starts from, not a pause, and showing it would flick the player to paused and back.
                var started = !playing
                // Whether it has been asked to speak: told apart from speaking, which waits on the voice.
                var asked = false
                tts.playback.collect { playback ->
                    val now = playback.playWhenReady && playback.state is TtsNavigator.State.Ready
                    if (now) started = true
                    // A pause is timed, wherever it came from (the player, a headphone button, the
                    // sleep timer), and a long one is answered for when the voice is asked to read on:
                    // here, if it wasn't the player's own button that asked (see toggle).
                    if (!jumping) {
                        if (asked && !playback.playWhenReady) pausedAt = SystemClock.elapsedRealtime()
                        if (!asked && playback.playWhenReady) takeRewind().takeIf { it > 0 }?.let { back -> launch { stepBack(tts, back) } }
                    }
                    // Whoever asked, the voice reads only while it may have the phone's sound.
                    if (asked != playback.playWhenReady) focus.asked(playback.playWhenReady)
                    asked = playback.playWhenReady
                    // Held still while a tap moves the voice: it carries on reading where it lands.
                    if (started && !jumping) {
                        // Paused with no reader open: the sentence it paused on is saved there and then.
                        if (listen.playing && !now && page == null) unseen.save()
                        listen.playing = now
                        if (!now) listen.preparing = false
                    }
                    when (val state = playback.state) {
                        is TtsNavigator.State.Ended -> {
                            // Read to the end of the book: all of it has been heard, and is saved as that.
                            page?.onEnded?.invoke() ?: unseen.save(ended = true)
                            stop()
                        }
                        is TtsNavigator.State.Failure -> fail(context.getString(R.string.listen_stopped_error, state.error.message))
                        else -> {}
                    }
                }
            }
            launch {
                // A page the voice didn't turn (a swipe, a scroll, a jump) means the listener went to
                // look at something: the page stops following until they go back. The navigator is
                // swapped when the phone folds, so whichever is current is watched.
                snapshotFlow { navigator() }.filterNotNull().collectLatest { n ->
                    n.currentLocator.drop(1).collect {
                        val now = SystemClock.uptimeMillis()
                        // Only when the listener moved it (a swipe, a page turn, a jump): a page re-flowing
                        // by itself (the player arriving, the controls) isn't going elsewhere.
                        val byListener = now - (page?.movedAt?.invoke() ?: 0L) < LISTENER_MOVE_MS
                        if (listen.active && !listen.offPage && byListener && now - followedAt > FOLLOW_SETTLE_MS && page?.resizing?.invoke() != true) listen.offPage = true
                    }
                }
            }
            launch {
                var utterance: Locator? = null
                // The sentence a fresh start begins with is in front of the listener already,
                // though it may have begun on the page before: the page stays where it is for it.
                var stay = onPage
                var file: Url? = null
                var named: Url? = null
                tts.location.collect { location ->
                    // In a new chapter file: the ones after it are read for their notes' numbers.
                    if (location.href != file) {
                        file = location.href
                        notesNear(location.href)
                    }
                    // The sentences a tap moves the voice past aren't shown, said or followed.
                    if (jumping) return@collect
                    listen.spokenFile = open.chapterFileOf(location.href)
                    if (location.href != named) {
                        named = location.href
                        chapter = open.contents.run { titleOf(chapterAt(resourceOf(location.href))) }.orEmpty()
                    }
                    // Paused, the voice reports no word: the sentence keeps the ink it has.
                    val spokenTo = location.range?.last?.plus(1)
                    // Its first word: it's reading now.
                    if (spokenTo != null) listen.preparing = false
                    if (location.utterance != listen.phrase) {
                        listen.phrase = location.utterance
                        listen.spoken = spokenTo ?: 0
                    } else if (spokenTo != null) {
                        listen.spoken = spokenTo
                    }
                    // A new sentence: while the voice speaks, it is the reader's place in the book.
                    // The pages are told where it is in their own terms, not in read-aloud's: sent
                    // after a sentence they can't find by its words, they go by where it is.
                    if (location.utteranceLocator != lastSpoken) {
                        val placed = files.placed(location.utteranceLocator)
                        lastPlaced = placed
                        spoken(placed)
                    }
                    lastSpoken = location.utteranceLocator
                    val n = navigator() ?: return@collect
                    // Turn the page to follow the voice, unless the listener has gone to look elsewhere.
                    if (location.utteranceLocator != utterance) {
                        utterance = location.utteranceLocator
                        // A tapped sentence is in front of the listener: no call to move the page for it.
                        if (!listen.offPage && !stay && location.utteranceLocator != tappedSentence) {
                            followedAt = SystemClock.uptimeMillis()
                            n.go(lastPlaced ?: location.utteranceLocator, animated = false)
                            followedAt = SystemClock.uptimeMillis()
                        }
                        stay = false
                    }
                    n.applyDecorations(decorationsFor(location), GROUP)
                }
            }
            if (playing) tts.play()
        }
    }

    /**
     * Passes over the sentences that lie wholly before the page in view, so a fresh start begins
     * with the first sentence the listener can see. The page itself says where each one is: it is
     * laid out there as an invisible decoration, the way the read-along highlight is, and asked
     * for. True if the voice now stands on a sentence that is, at least in part, on the page;
     * otherwise it is left where Readium started it.
     */
    private suspend fun skipToPage(tts: TtsNavigator<*, *, *, *>, n: EpubNavigatorFragment): Boolean {
        val overflow = n.overflow.value
        val script = "$WHERE_SCRIPT(${overflow.scroll}, ${overflow.readingProgression == ReadingProgression.RTL})"
        val first = tts.location.value
        var passed = 0
        // How many sentences in a row, just passed, the page couldn't say where they are.
        var unplaced = 0
        // Which laying-out of the probe the page last answered for: each one is numbered.
        var answered = -1
        try {
            while (passed < MAX_START_SKIPS) {
                val at = tts.location.value
                // Not into the next chapter file: its pages aren't the one being asked.
                if (at.href != first.href) break
                n.applyDecorations(listOf(Decoration("probe", at.utteranceLocator.findable(), HereMark(0))), PROBE)
                // Readium lays a decoration out on the page's next frame, not at once: ask until
                // this one is there. (One the page can't find never is.)
                val found = withTimeoutOrNull(PROBE_MS) {
                    var answer: JSONObject? = null
                    while (answer == null) {
                        answer = runCatching { n.evaluateJavascript(script)?.let(::JSONObject) }.getOrNull()?.takeIf { it.optInt("n", -1) > answered }
                        if (answer == null) delay(PROBE_POLL_MS)
                    }
                    answer
                }
                if (found != null) answered = found.optInt("n", -1)
                when (found?.optInt("at", UNPLACED) ?: UNPLACED) {
                    0 -> {
                        // The sentences just before it that the page couldn't place may be the
                        // page's first: the voice begins with them. Better heard again than not at all.
                        repeat(unplaced) { if (!skipSentence(tts, tts.location.value, forward = false)) return true }
                        return true
                    }
                    -1 -> {
                        unplaced = 0
                        if (skipSentence(tts, at, forward = true)) passed++ else break
                    }
                    // A sentence the page can't place says nothing of where the page is: the next
                    // one will. (Taken for one after the page, it sent the voice back to the top
                    // of its paragraph, and the page after it: pages back, in a long one.)
                    UNPLACED -> if (++unplaced <= MAX_UNPLACED && skipSentence(tts, at, forward = true)) passed++ else break
                    else -> {
                        // Past the page: it was one of those the page couldn't place, if any.
                        if (unplaced == 0) break
                        repeat(unplaced) { if (!skipSentence(tts, tts.location.value, forward = false)) return true }
                        return true
                    }
                }
            }
            // No sentence found on the page: back to where it started, and the old way from there.
            repeat(passed) { if (!skipSentence(tts, tts.location.value, forward = false)) return false }
            return false
        } finally {
            withContext(NonCancellable) { runCatching { n.applyDecorations(emptyList(), PROBE) } }
        }
    }

    /**
     * How many sentences back the voice starts, when it is started again on the sentence it stopped
     * on (see [sentencesToRewind]). None where it wasn't the voice that left the book there: a page
     * someone was reading is where reading aloud starts.
     */
    private fun rewindAfterStop(): Int {
        if (stoppedAt?.isSpoken != true) return 0
        val away = if (stoppedAtTime > 0L) SystemClock.elapsedRealtime() - stoppedAtTime else UNKNOWN_PAUSE_MS
        return sentencesToRewind(away)
    }

    /** How many sentences back the voice goes for the pause it is coming out of; asked once for each pause. */
    private fun takeRewind(): Int {
        val since = pausedAt
        pausedAt = 0L
        return if (since > 0L) sentencesToRewind(SystemClock.elapsedRealtime() - since) else 0
    }

    /** Steps the voice [sentences] sentences back, or as many as there are before it. */
    private suspend fun stepBack(tts: TtsNavigator<*, *, *, *>, sentences: Int) {
        // (The voice's own doing, not the listener's: see [sent].)
        sent = false
        repeat(sentences) { if (!skipSentence(tts, tts.location.value, forward = false)) return }
    }

    /**
     * Takes the voice to the chapter at [link], and the page with it: the next or the previous
     * chapter, from the player. The page goes first, as it would for a reader; the voice then reads
     * on from the chapter's first words (those the contents point at, else the first on the page).
     */
    fun readChapter(link: Link) {
        val n = navigator() ?: return
        val tts = tts
        if (tts == null) {
            // Still starting up: the page goes, and the voice starts from it.
            n.go(link, animated = false)
            return
        }
        chapterJob?.cancel()
        chapterJob = scope.launch {
            val reading = tts.playback.value.playWhenReady
            var arrived = false
            // Held still on the way, as for a tap on a sentence: nothing of the old place is said or followed.
            jumping = true
            pausedAt = 0L
            // Said before the page moves: "end of chapter" is waiting for this chapter's end, and
            // the page arriving in another one is not it.
            listen.skippingChapter = true
            listen.chapterSkips++
            try {
                tts.pause()
                followedAt = SystemClock.uptimeMillis()
                val before = n.currentLocator.value
                n.go(link, animated = false)
                withTimeoutOrNull(ARRIVAL_MS) { n.currentLocator.first { it !== before } }
                // A moment for the chapter's first page to be laid out where the page has landed.
                delay(CHAPTER_SETTLE_MS)
                // A chapter that begins part of the way down the file next to the one that was in
                // view: Readium has shown that file's first or last page instead. Asked again now
                // that the file is the one in view, it goes to the chapter.
                if (link.url().fragment?.isNotEmpty() == true && !n.currentLocator.value.href.isEquivalent(before.href)) {
                    followedAt = SystemClock.uptimeMillis()
                    n.go(link, animated = false)
                    delay(CHAPTER_SETTLE_MS)
                }
                followedAt = SystemClock.uptimeMillis()
                val page = n.currentLocator.value
                val blocks = files.of(page.href, page.mediaType).blocks
                // The block the contents point at, where they name one...
                val named = link.url().fragment?.takeIf { it.isNotEmpty() }?.let { id ->
                    blocks.firstOrNull { block ->
                        val name = block.locator.locations.cssSelector
                        name == "#$id" || name?.startsWith("#$id ") == true || name?.startsWith("#$id>") == true
                    }
                }
                // ...else the one the page now opens with.
                val top = runCatching { n.evaluateJavascript(TOP_TEXT_SCRIPT)?.let(::JSONObject)?.optString("text") }.getOrNull().orEmpty()
                val spot = if (named != null) null else tappedAt(blocks.map { it.text }, "", top)
                val block = named ?: spot?.let { blocks[it.block] }
                if (block != null) {
                    arrived = true
                    jumpTo(tts, block, spot?.offset ?: 0)
                }
            } finally {
                listen.skippingChapter = false
                if (!arrived) {
                    jumping = false
                    // A chapter that opens on a page without words: the voice stays where it was,
                    // and the listener is offered the way back to it.
                    if (this@ReadAloud.tts === tts && listen.active) {
                        listen.offPage = true
                        if (reading) tts.play()
                    }
                }
            }
        }
    }

    /**
     * Passes over the sentences of its paragraph that come before [sentence]: a change of voice
     * carries on from the sentence it interrupted, not from the top of the paragraph. (Voices
     * don't all cut sentences alike, so the one holding its first words will do.)
     */
    private suspend fun skipToSentence(tts: TtsNavigator<*, *, *, *>, sentence: Locator) {
        val wanted = sentence.text.highlight?.takeIf { it.isNotBlank() } ?: return
        repeat(MAX_START_SKIPS) {
            val at = tts.location.value
            val reached = at.utterance == wanted || at.utterance.contains(wanted.take(SAME_SENTENCE_CHARS)) ||
                wanted.contains(at.utterance.take(SAME_SENTENCE_CHARS))
            // Not past the paragraph it was in: better a sentence heard twice than one never heard.
            if (reached || at.utteranceLocator.locations != sentence.locations || !skipSentence(tts, at, forward = true)) return
        }
    }

    /**
     * Steps the voice, not speaking, sentence by sentence to the one holding the letter [offset]
     * letters into [text] (its block, spaces aside). Starting from the block after it, it steps back.
     */
    private suspend fun stepTo(tts: TtsNavigator<*, *, *, *>, text: String, offset: Int) {
        var first = true
        var onward = false
        repeat(MAX_START_SKIPS) {
            val at = tts.location.value
            val said = bare(at.utterance)
            // Where the sentence starts in the block: voices cut and space sentences their own
            // way, so it is known by its opening.
            val start = text.indexOf(said.take(SAME_SENTENCE_CHARS))
            val forward = when {
                start < 0 -> if (first) false else return
                start > offset -> false
                start + said.length <= offset -> true
                else -> return
            }
            first = false
            // Turned back after going on: the offset fell between two of the voice's sentences.
            // The earlier one will do.
            val turning = onward && !forward
            onward = forward
            if (!skipSentence(tts, at, forward) || turning) return
        }
    }

    /**
     * Where the voice stopped ([stoppedAt]), if that sentence is on the page [n] shows, at least in
     * part: the block of Readium's reading holding it, and how far into the block (spaces aside)
     * it begins. Null when the listener has gone on to another page, or it can't be found.
     */
    private suspend fun resumeOn(n: EpubNavigatorFragment): Pair<Content.TextElement, Int>? {
        val sentence = stoppedAt ?: return null
        val said = sentence.text.highlight?.let(::bare)?.takeIf { it.isNotEmpty() } ?: return null
        if (!n.currentLocator.value.href.isEquivalent(sentence.href)) return null
        val opening = said.take(SAME_SENTENCE_CHARS)
        val file = files.of(sentence.href, sentence.mediaType)
        // Its own block, by name; else the one block that holds its words.
        val block = file.blockOf(sentence)?.let(file.blocks::get) ?: return null
        // On the page in view? The page says, the sentence laid out there as an unseen mark.
        try {
            n.applyDecorations(listOf(Decoration("resume", sentence.findable(), HereMark(0))), PROBE)
            val script = "$WHERE_SCRIPT(${n.overflow.value.scroll}, ${n.overflow.value.readingProgression == ReadingProgression.RTL})"
            val found = withTimeoutOrNull(PROBE_MS) {
                var answer: JSONObject? = null
                while (answer == null) {
                    answer = runCatching { n.evaluateJavascript(script)?.let(::JSONObject) }.getOrNull()
                    if (answer == null) delay(PROBE_POLL_MS)
                }
                answer
            }
            if (found?.optInt("at", 1) != 0) return null
        } finally {
            withContext(NonCancellable) { runCatching { n.applyDecorations(emptyList(), PROBE) } }
        }
        return block to bare(block.text).indexOf(opening)
    }

    /** Moves the voice, not yet speaking, to the sentence after [at], or the one before it; false if it can't. */
    private suspend fun skipSentence(tts: TtsNavigator<*, *, *, *>, at: TtsNavigator.Location, forward: Boolean): Boolean {
        if (forward) {
            if (!tts.hasNextUtterance()) return false
            tts.skipToNextUtterance()
        } else {
            if (!tts.hasPreviousUtterance()) return false
            tts.skipToPreviousUtterance()
        }
        return withTimeoutOrNull(START_SKIP_MS) { tts.location.first { it.utteranceLocator != at.utteranceLocator } } != null
    }

    /**
     * Opens Readium's read-aloud navigator on the chosen voice: a natural one if there is one for
     * this book, otherwise (or if it can't be loaded) the phone's own. Null, with the reason
     * shown, if neither can speak.
     */
    private suspend fun open(from: Locator?): TtsNavigator<*, *, *, *>? {
        val app = context.applicationContext as Application
        val listener = object : TtsNavigator.Listener {
            override fun onStopRequested() = stop()
        }
        val forced = language?.let(::Language)
        tuned = Tuning(voice, language, phoneVoiceLanguage)
        // Before Readium cuts the first paragraph into sentences: the notes' numbers in it.
        readNotes(from?.href ?: navigator()?.currentLocator?.value?.href)
        voice?.let { ref ->
            val lookahead = SentenceLookahead(edits)
            val engine = SpokenEngineProvider(NaturalTtsEngineProvider(context.voices, ref, lookahead, language), edits)
            val factory = TtsNavigatorFactory(app, publication, engine, tokenizerFactory = lookahead::tokenizer)
            val natural = factory?.createNavigator(listener, from, NaturalTtsPreferences(language = forced, speed = speed.toDouble()))?.getOrNull()
            if (natural != null) {
                submitRate = { rate -> natural.submitPreferences(NaturalTtsPreferences(language = forced, speed = rate.toDouble())) }
                return natural
            }
            // The natural voice can't be loaded (too little memory, say): the phone's reads on.
            Toast.makeText(context, R.string.voice_fell_back, Toast.LENGTH_LONG).show()
            listen.preparing = false
        }
        val factory = systemFactory ?: run {
            fail(context.getString(R.string.listen_no_readable_text))
            return null
        }
        // The phone's voice speaks the reader's accent when the book only names its language.
        val heard = forced ?: phoneVoiceLanguage?.let(::Language)
        val system = factory.createNavigator(listener, from, AndroidTtsPreferences(language = heard, speed = speed.toDouble())).getOrElse {
            fail(context.getString(R.string.listen_no_voice))
            return null
        }
        submitRate = { rate -> system.submitPreferences(AndroidTtsPreferences(language = heard, speed = rate.toDouble())) }
        return system
    }

    /** The voice or the language was changed while listening: carry on with it from the same sentence. */
    fun retune() {
        val running = tts ?: return
        // The voice and the language it is reading with already: a reader that opens the book the
        // voice is reading sets them as they are, and nothing is to be heard of it.
        if (tuned == Tuning(voice, language, phoneVoiceLanguage)) return
        val playing = running.playback.value.playWhenReady
        val at = lastSpoken
        session?.cancel()
        running.close()
        tts = null
        begin(at, playing)
    }

    /**
     * The sentence being read gets a soft highlight; the words still to come sit under a veil of
     * the page color, so each one inks in, light to full black (or white), as it's spoken.
     */
    private fun decorationsFor(location: TtsNavigator.Location): List<Decoration> {
        val (tint, paper) = page?.colors ?: (0 to 0)
        val veil = page?.veil ?: 0.6f
        val sentence = location.utteranceLocator
        val spoken = location.range?.last?.plus(1) ?: 0
        val unread = location.utterance.length - spoken
        return buildList {
            add(Decoration("sentence", sentence, Decoration.Style.Highlight(tint = tint)))
            if (unread > 0 && location.utterance.substring(spoken).isNotBlank()) {
                val rest = sentence.copy(text = sentence.text.substring(spoken until location.utterance.length))
                add(Decoration("unread", rest, UnreadVeil(paper.withAlpha(veil))))
            }
        }
    }

    override fun backToVoice() {
        listen.offPage = false
        val at = lastPlaced ?: lastSpoken ?: return
        scope.launch {
            followedAt = SystemClock.uptimeMillis()
            navigator()?.go(at, animated = false)
            followedAt = SystemClock.uptimeMillis()
        }
    }

    /** The voice is being moved to a tapped sentence (see [listenAt]). */
    private var jumping = false

    /** The sentence a tap sent the voice to: in front of the listener already, so the page stays put for it. */
    private var tappedSentence: Locator? = null

    /**
     * "Read from here": a tap on a sentence on the page [n] shows sends the voice to that sentence,
     * and it reads on from there, paused or not before. [point] is where the tap was, as Readium
     * gives it. The page is asked for the words under the finger, and they are found in Readium's
     * own reading of the file, which is where the voice reads from.
     */
    suspend fun listenAt(n: EpubNavigatorFragment, point: PointF): TapOutcome {
        val tts = tts ?: return TapOutcome.NO_TEXT
        val tapped = runCatching { n.evaluateJavascript("$TAPPED_SCRIPT(${point.x}, ${point.y})")?.let(::JSONObject) }.getOrNull()
            ?: return TapOutcome.NO_TEXT
        if (jumping) return TapOutcome.MOVED
        val page = n.currentLocator.value
        val blocks = files.of(page.href, page.mediaType).blocks
        val spot = tappedAt(blocks.map { it.text }, tapped.optString("before"), tapped.optString("after"))
        if (spot == null) return TapOutcome.LOST
        jumpTo(tts, blocks[spot.block], spot.offset)
        return TapOutcome.MOVED
    }

    /**
     * Moves the voice to the sentence of [block] holding the letter [offset] letters in (spaces
     * aside), held still on the way, so the sentences it passes are neither heard nor shown.
     *
     * Readium can only send a voice to a block, and a paused voice sent there doesn't say where
     * it is until it next moves: it is moved on by a sentence to say so, and from there forward or
     * back, sentence by sentence, to the one the tap is in.
     */
    private suspend fun jumpTo(tts: TtsNavigator<*, *, *, *>, block: Content.TextElement, offset: Int) {
        here?.cancel()
        jumping = true
        sent = true
        // Sent somewhere by hand, it reads from exactly there, however long it had been paused.
        pausedAt = 0L
        try {
            tts.pause()
            val text = bare(block.text)
            val was = tts.location.value
            val reading = bare(was.utterance)
            // Already in the block (a tap on the paragraph being read), the voice only steps to the
            // sentence; sent there, it could land on the very sentence it is on, and say nothing.
            val inBlock = was.utteranceLocator.locations == block.locator.locations ||
                (reading.length >= SAME_SENTENCE_CHARS && text.contains(reading.take(SAME_SENTENCE_CHARS)))
            if (!inBlock) {
                readNotes(block.locator.href)
                tts.go(block.locator)
                tts.skipToNextUtterance()
                // Readium finds the block by reading the file up to it, which in a long chapter
                // file takes a moment.
                withTimeoutOrNull(JUMP_GO_MS) { tts.location.first { it.utteranceLocator != was.utteranceLocator } }
            }
            // A block of one sentence: moving on said so from the next block. Back into it.
            stepTo(tts, text, offset)
        } finally {
            jumping = false
            // Wherever it landed, it reads on from there, the page as it is (unless listening was
            // stopped meanwhile).
            if (this.tts === tts && listen.active) {
                val now = tts.location.value
                tappedSentence = now.utteranceLocator
                listen.offPage = false
                listen.phrase = now.utterance
                listen.spoken = 0
                navigator()?.let { n -> scope.launch { n.applyDecorations(decorationsFor(now), GROUP) } }
                listen.preparing = voice != null
                listen.playing = true
                tts.play()
            }
        }
    }

    override fun toggle() {
        val tts = tts ?: return start() // start() ignores taps while it's still starting up
        if (tts.playback.value.playWhenReady) {
            tts.pause()
            return
        }
        // After a long pause it goes back a sentence or two first, so the listener finds the thread.
        val back = takeRewind()
        if (back == 0) {
            tts.play()
        } else {
            scope.launch {
                stepBack(tts, back)
                if (this@ReadAloud.tts === tts) tts.play()
            }
        }
    }

    override fun skip(delta: Int) {
        val tts = tts ?: return
        sent = true
        if (delta < 0) tts.skipToPreviousUtterance() else tts.skipToNextUtterance()
    }

    override fun setRate(rate: Float) {
        // (A reader that opens the book the voice is reading sets the speed it already has.)
        if (rate == speed) return
        speed = rate
        if (tts != null) submitRate(rate)
    }

    /** The speed the voice reads at, 1 being its own pace. */
    val rate: Float get() = speed

    /**
     * Where the voice is on the screen: the line holding the next word to be spoken (the first of
     * those still under the veil), else the last line of the sentence that is on this page. Asked
     * of the page itself, which is where the read-along decorations were laid out.
     */
    override suspend fun spokenLine(): Rect? {
        if (!listen.active || listen.offPage) return null
        return navigator()?.let { lineOnPage(it) }
    }

    /** The line of the read-along that is on the page [n] is showing, in the window's pixels; null if none of it is. */
    private suspend fun lineOnPage(n: EpubNavigatorFragment): Rect? = runCatching {
        val line = n.evaluateJavascript(SPOKEN_LINE_SCRIPT)?.let(::JSONObject) ?: return null
        val at = IntArray(2).also(n.publicationView::getLocationInWindow)
        Rect(
            at[0] + line.getDouble("l").toFloat(), at[1] + line.getDouble("t").toFloat(),
            at[0] + line.getDouble("r").toFloat(), at[1] + line.getDouble("b").toFloat(),
        )
    }.getOrNull()

    /**
     * "You were here": the sentence the voice stopped on keeps its highlight for a moment, a
     * little stronger, then lets go of it, on the theme's own fades. Nothing else on the page
     * changes. With animations removed it simply shows, and goes.
     */
    fun markHere(sentence: Locator) {
        here?.cancel()
        // Drawn by the reader, in its own time: its animations run on the screen's clock, and end with the screen.
        val page = page ?: return
        here = page.scope.launch {
            val n = navigator() ?: return@launch
            val tint = page.colors.first
            // It starts from about the strength the read-along highlight had.
            val strength = Animatable(HERE_FROM)
            try {
                coroutineScope {
                    val painter = launch {
                        snapshotFlow { (strength.value * HERE_STEPS).roundToInt() }.collect { step ->
                            val wash = HERE_ALPHA * step / HERE_STEPS
                            n.applyDecorations(
                                if (step <= 0) emptyList() else listOf(Decoration("here", sentence, HereMark(tint.withAlpha(wash)))),
                                HERE,
                            )
                        }
                    }
                    strength.animateTo(1f, page.hereAppears)
                    delay(HERE_HOLD_MS)
                    strength.animateTo(0f, page.hereFades)
                    painter.cancel()
                }
            } finally {
                // Whatever cut it short (the voice starting again, the book closing), nothing is left behind.
                withContext(NonCancellable) { runCatching { n.applyDecorations(emptyList(), HERE) } }
            }
        }
    }

    override fun stop() {
        val wasListening = listen.active
        val elsewhere = listen.offPage
        val spoken = lastSpoken
        val placed = lastPlaced
        val page = page
        // Stopped with no reader open: the sentence it stopped on is saved there and then. (A
        // reader saves it itself, by the page it is on.)
        if (page == null && wasListening) unseen.save()
        lastSpoken = null
        lastPlaced = null
        if (spoken != null) {
            stoppedAt = spoken
            stoppedAtTime = SystemClock.elapsedRealtime()
        }
        pausedAt = 0L
        chapterJob?.cancel()
        session?.cancel()
        session = null
        listen.spokenFile = -1
        listen.phrase = ""
        chapter = ""
        tts?.close()
        tts = null
        tuned = null
        focus.release()
        listen.active = false
        listen.playing = false
        listen.preparing = false
        listen.offPage = false
        val n = navigator()
        if (page != null) {
            page.scope.launch {
                // A listener who had gone elsewhere may have had the voice catch up with the page they
                // are on: the page can tell, while the read-along is still laid out on it.
                val inView = !elsewhere || (n != null && lineOnPage(n) != null)
                n?.applyDecorations(emptyList(), GROUP)
                // The page stays as it is. With the sentence the voice stopped on in view, that
                // sentence is marked; otherwise the listener is offered the way back to it.
                if (wasListening && spoken != null) if (inView) markHere(spoken) else page.onStoppedElsewhere(placed ?: spoken)
            }
        }
        owner.stopped(this)
    }

    private fun fail(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        stop()
    }

    /**
     * The voice has started on a sentence. Told whether or not a page can follow: shut in a
     * pocket or behind another app it can't turn, and this is the only word of where the voice is.
     * The sentence is told as a place the pages can go by (see [SpokenFiles.placed]).
     */
    private fun spoken(placed: Locator) {
        // Sent there by the listener, and it is before the sentence the voice was on: they went back.
        val was = unseen.sentence
        val back = sent && was != null && comesBefore(
            open.contents.resourceOf(placed.href), placed.locations.progression ?: 0.0,
            open.contents.resourceOf(was.href), was.locations.progression ?: 0.0,
        )
        sent = false
        unseen.sentence = placed
        val page = page
        if (!heard) {
            heard = true
            // Read aloud with no reader open, it is the book being read all the same: the widget
            // and the covers show it.
            if (page == null) owner.heard(bookId)
        }
        // A reader keeps the place by the page the sentence is on; with none it is kept here.
        if (page != null) page.onSpoken(placed, back) else unseen.spoken(back)
    }

    // ---- With and without a reader ----------------------------------------------------------

    /**
     * A reader has the book open: the voice has its pages to turn, to mark and to ask. A voice
     * that was reading already goes on as it was, with nothing to be heard of the change: the
     * page is taken to the sentence it is on, and follows from there. From here the reader keeps
     * the book's place, by the page the sentence is on.
     */
    fun attach(page: VoicePage) {
        if (this.page === page) return
        this.page = page
        // The reader keeps the place from here. A save that was still waiting to be made here
        // would land after the reader's own, on top of wherever they have turned to since.
        unseen.handedOver()
        listen.offPage = false
        val at = lastPlaced ?: return
        page.onSpoken(at, false)
        scope.launch {
            val n = navigator() ?: return@launch
            followedAt = SystemClock.uptimeMillis()
            n.go(at, animated = false)
            followedAt = SystemClock.uptimeMillis()
            tts?.location?.value?.let { n.applyDecorations(decorationsFor(it), GROUP) }
        }
    }

    /**
     * The reader has gone. Closing a book stops its voice first (see ReaderScreen); a screen that
     * goes some other way (Android clearing it away behind another app) leaves the voice reading,
     * with no pages, and the book's place is kept here from now on.
     */
    fun detach(page: VoicePage) {
        if (this.page !== page) return
        here?.cancel()
        chapterJob?.cancel()
        this.page = null
        listen.offPage = false
        tappedSentence = null
        if (listen.active) keepUnseen()
    }

    /** No reader has the book open: its place is kept here, counted on from what the library says of it now. */
    private fun keepUnseen() {
        unseen.rebase()
        scope.launch {
            // How far through the book a sentence is, is told by the book's positions: counting them reads all of it, once.
            runCatching { open.count() }
            // (Unless a reader has opened the book meanwhile: the place is the reader's to keep now.)
            if (page == null) unseen.counted()
        }
    }

    /** The place the book was left at, as the library has it now; null for a book never opened. */
    private suspend fun leftAt(): Locator? {
        val now = owner.library.state.value.find(bookId) ?: book
        val left = runCatching { open.left(now) }.getOrNull()
        val saved = left?.place ?: return null
        if (left.oldCount) unseen.recount()
        // The sentence this voice stopped on itself keeps the time it stopped at: how long ago
        // that was says how far back it starts. Any other place is from some other day.
        if (!sameSentence(saved, stoppedAt)) {
            stoppedAt = saved
            stoppedAtTime = 0L
        }
        return saved
    }

    /**
     * Where the voice starts from [saved], the place the book was left at, when there is no page
     * to start from: the block of Readium's reading that holds the place, and how far into the
     * block (spaces aside) it is (see [resumeSpot]). Null where its file has no words.
     */
    private suspend fun resumeFrom(saved: Locator): Pair<Content.TextElement, Int>? {
        val file = files.of(saved.href, saved.mediaType)
        val spot = resumeSpot(
            texts = file.blocks.map { it.text },
            places = file.places,
            words = saved.text.highlight,
            around = saved.locations.progression,
            block = if (saved.isSpoken) file.blockOf(saved) else null,
        ) ?: return null
        return file.blocks[spot.block] to spot.offset
    }

    /**
     * Play, from away from the reader. A paused voice reads on (after a long pause a sentence or
     * two back, as from the player's own button); one that isn't reading starts.
     */
    fun play() {
        val tts = tts ?: return start()
        if (!tts.playback.value.playWhenReady) toggle()
    }

    /**
     * Pause, from away from the reader: a headphone button, the notification, the lock screen,
     * the widget, the car. A voice that is still getting ready is stopped instead: nothing of it
     * has been heard yet.
     */
    fun pause() {
        val tts = tts
        if (tts != null) tts.pause() else if (session?.isActive == true) stop()
    }

    /** The book has chapters: "end of chapter" is one of the sleep timers. */
    val hasChapters: Boolean get() = open.contents.entries.isNotEmpty()

    /**
     * Sets the voice up as a reader with the book open does (see EpubPages): the voice that reads
     * its language, the listener's respellings, the speed. For a voice started with no reader.
     */
    fun tune(state: LibraryState, phoneLanguage: String?) {
        val settings = state.settings
        val said = publication.metadata.language?.code
        val voicing = voicing((state.find(bookId) ?: book).language, said, phoneLanguage, text = open.text != null)
        voice = context.voices.choose(voicing.readIn, settings)
        language = voicing.chosen
        phoneVoiceLanguage = voicing.phoneVoice
        edits.respellings = state.respellingsFor(bookId)
        skipNotes = settings.skipNoteNumbers
        setRate(settings.listenRate)
    }

    // ---- Kept while it is in use --------------------------------------------------------------

    private var held = false
    private var watch: Job? = null

    /**
     * Keeps the book open for the voice and starts the sleep timer's watch, until [release]. False
     * if the book has been closed meanwhile (its reader long gone): there is nothing to read from.
     */
    override fun hold(): Boolean {
        if (held) return true
        if (!owner.books.retain(open)) return false
        held = true
        watch = scope.launch { watchSleep() }
        return true
    }

    /** Lets the book go: the voice has stopped, and no reader has it open. */
    override fun release() {
        if (!held) return
        held = false
        watch?.cancel()
        watch = null
        owner.books.release(open)
    }

    /**
     * The sleep timer: pause after the chosen time, or when the chapter changes. It runs with the
     * voice, not with a screen: set from the widget, or running on when the reader has gone, it
     * stops the voice all the same.
     */
    private suspend fun watchSleep() = coroutineScope {
        launch {
            // (Waiting for a chapter's end, it starts over when a reader comes or goes: which chapter the page is in is the reader's to say.)
            snapshotFlow { SleepSet(listen.sleep, listen.sleepEndsAt, listen.chapterSkips, page.takeIf { listen.sleep == SleepTimer.CHAPTER }) }.collectLatest { set ->
                if (!set.timer.timed) listen.sleepLeft = 0
                if (set.timer == SleepTimer.OFF) return@collectLatest
                if (set.timer == SleepTimer.CHAPTER) {
                    // A chapter skipped to from the player is the one whose end is waited for: once the
                    // voice has arrived in it, and the page has said which chapter that is.
                    if (listen.chapterSkips > 0) {
                        snapshotFlow { listen.skippingChapter }.first { !it }
                        delay(CHAPTER_SKIP_SETTLE_MS)
                    }
                    // Whichever comes first: the voice moving into the next chapter file, or the page
                    // (following the voice) reaching the next chapter in the contents.
                    val onPage = { set.page?.chapter?.invoke() ?: -1 }
                    val start = listen.spokenFile to onPage()
                    snapshotFlow { listen.spokenFile to onPage() }.first { (file, chapter) -> chapterEnded(start.first, start.second, file, chapter) }
                } else {
                    // Counted down a minute at a time: the widget's moon says how many are left.
                    while (true) {
                        val left = set.endsAt - SystemClock.uptimeMillis()
                        if (left <= 0L) break
                        listen.sleepLeft = sleepMinutesShown(left)
                        delay(sleepMinuteEndsIn(left))
                    }
                }
                if (listen.playing) toggle()
                listen.sleepLeft = 0
                listen.sleep = SleepTimer.OFF
            }
        }
        // Stopping read-aloud clears the timer.
        launch { snapshotFlow { listen.active }.collect { active -> if (!active) listen.sleep = SleepTimer.OFF } }
    }

    /** What the sleep timer is set to, and (waiting for a chapter's end) the pages that say which chapter is in view. */
    private data class SleepSet(val timer: SleepTimer, val endsAt: Long, val skips: Int, val page: VoicePage?)

    private companion object {
        const val GROUP = "read-aloud"

        /** After a chapter is skipped to, how long the page is given to say which chapter it is in before its end is waited for. */
        const val CHAPTER_SKIP_SETTLE_MS = 1_500L

        /** Where a sentence is laid out, to tell whether it is on the page in view (never seen). */
        const val PROBE = "start-probe"

        /** The page couldn't say where a sentence is; and how many of those in a row a start passes over before it gives up. */
        const val UNPLACED = 2
        const val MAX_UNPLACED = 3

        /** No start passes over more sentences than this, nor waits longer than this for the voice to move on by one. */
        const val MAX_START_SKIPS = 80
        const val START_SKIP_MS = 1_000L

        /** How long the first page of a chapter gone to is given to settle before it is asked for its words. */
        const val CHAPTER_SETTLE_MS = 250L

        /** The longest a tap waits for the voice to be at the tapped block. */
        const val JUMP_GO_MS = 3_000L

        /** How long the voice waits for a chapter file to be read for its notes' numbers before it starts in it. */
        const val NOTES_WAIT_MS = 1_200L

        /** Two voices' sentences are the same one if they open with this many of the same characters. */
        const val SAME_SENTENCE_CHARS = SENTENCE_OPENING

        /** How long the page is given to lay the probe out, and how often it is asked meanwhile (about a frame). */
        const val PROBE_MS = 400L
        const val PROBE_POLL_MS = 16L

        /**
         * Where the sentence laid out by [PROBE] is, as { n, at }: n numbers the laying-out (each
         * is one more than the last), and at is -1 wholly before the page in view (on an earlier
         * page, or scrolled off the top), 0 at least partly on it, 1 after it, [UNPLACED] when
         * the page found the sentence nowhere it could draw. Null until the page has laid it
         * out. Called with (scroll, rightToLeft).
         */
        val WHERE_SCRIPT = """
            (function(scroll, rtl) {
              var group = document.querySelector('[data-group="$PROBE"]'), item = group && group.lastElementChild;
              if (!item) return null;
              var w = window.innerWidth, h = window.innerHeight, any = false, here = false, before = true;
              for (var i = 0; i < item.children.length; i++) {
                var r = item.children[i].getBoundingClientRect();
                if (!r.width || !r.height) continue;
                any = true;
                if (r.right > 1 && r.left < w - 1 && r.bottom > 1 && r.top < h - 1) here = true;
                if (!(scroll ? r.bottom <= 1 : (rtl ? r.left >= w - 1 : r.right <= 1))) before = false;
              }
              return { n: parseInt(item.id.slice(item.id.lastIndexOf('-') + 1)) || 0, at: !any ? $UNPLACED : here ? 0 : before ? -1 : 1 };
            })
        """.trimIndent()

        /**
         * The words either side of a tap at (x, y) in the navigator's pixels, as { before, after },
         * running on across the text around it; null if the tap isn't on text (a margin, the
         * space past a line's end, a picture). The tap is taken back to the start of its word.
         */
        val TAPPED_SCRIPT = """
            (function(px, py) {
              var d = window.devicePixelRatio, x = px / d, y = py / d;
              var c = document.caretRangeFromPoint(x, y);
              if (!c || c.startContainer.nodeType !== 3) return null;
              var node = c.startContainer, t = node.textContent, off = c.startOffset;
              if (!t.trim()) return null;
              // On the words, not beside them: a letter next to the caret is under the finger.
              var near = false;
              for (var i = Math.max(0, off - 1); i <= Math.min(off, t.length - 1) && !near; i++) {
                var r = document.createRange();
                r.setStart(node, i);
                r.setEnd(node, i + 1);
                var b = r.getBoundingClientRect();
                if (b.width + b.height > 0 && x > b.left - 32 && x < b.right + 32 && y > b.top - 12 && y < b.bottom + 12) near = true;
              }
              if (!near) return null;
              while (off > 0 && /\S/.test(t[off - 1])) off--;
              var before = t.slice(0, off), after = t.slice(off), w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), m;
              w.currentNode = node;
              while (before.length < 160 && (m = w.previousNode())) before = m.textContent + ' ' + before;
              w.currentNode = node;
              while (after.length < 160 && (m = w.nextNode())) after = after + ' ' + m.textContent;
              return { before: before.slice(-160), after: after.slice(0, 160) };
            })
        """.trimIndent()

        /** The mark left on the sentence where the voice stopped. */
        const val HERE = "here"

        /** Its wash at full strength, the strength it starts from, and the steps its fades are painted in. */
        const val HERE_ALPHA = 0.3f
        const val HERE_FROM = 0.6f
        const val HERE_STEPS = 12

        /** How long it stays before it fades: about two seconds in all. */
        const val HERE_HOLD_MS = 1_800L

        /**
         * The line the voice is on, in the web view's pixels: the first box of the veil over the
         * words still to come (see [UnreadVeil]) that is on this page, widened to the sentence's
         * boxes on the same line; without one (the whole sentence spoken, a voice that doesn't
         * report its words, the rest of the sentence on the next page), the lowest line of the
         * sentence that is on this page.
         */
        val SPOKEN_LINE_SCRIPT = """
            (function() {
              var group = document.querySelector('[data-group="$GROUP"]');
              if (!group) return null;
              var w = window.innerWidth, h = window.innerHeight, d = window.devicePixelRatio;
              function seen(r) { return r.width > 0 && r.height > 0 && r.right > 0 && r.left < w && r.bottom > 0 && r.top < h; }
              var veil = [], sentence = [];
              group.querySelectorAll(':scope > div > *').forEach(function(box) {
                var r = box.getBoundingClientRect();
                if (seen(r)) (box.dataset.jacket === 'unread' ? veil : sentence).push(r);
              });
              var line = veil[0];
              if (!line) {
                for (var i = 0; i < sentence.length; i++) if (!line || sentence[i].bottom > line.bottom) line = sentence[i];
              }
              if (!line) return null;
              var l = line.left, r = line.right;
              for (var j = 0; j < sentence.length; j++) {
                var s = sentence[j];
                if (s.top < line.bottom && s.bottom > line.top) { l = Math.min(l, s.left); r = Math.max(r, s.right); }
              }
              return { l: l * d, t: line.top * d, r: r * d, b: line.bottom * d };
            })()
        """.trimIndent()
    }
}

private fun Int.withAlpha(alpha: Float): Int = (this and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
