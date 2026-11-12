package io.github.friendlyglobal.jacket.reader

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.importer.DocumentCloser
import io.github.friendlyglobal.jacket.importer.NoWordsInPdf
import io.github.friendlyglobal.jacket.importer.PdfChapter
import io.github.friendlyglobal.jacket.importer.PdfLayout
import io.github.friendlyglobal.jacket.importer.PdfLocked
import io.github.friendlyglobal.jacket.importer.PdfPlace
import io.github.friendlyglobal.jacket.importer.PdfSpot
import io.github.friendlyglobal.jacket.importer.PdfWords
import io.github.friendlyglobal.jacket.importer.PdfWordsFile
import io.github.friendlyglobal.jacket.importer.PdfWordsUnavailable
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.listening
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.motionRemoved
import io.github.friendlyglobal.jacket.voice.Respelling
import io.github.friendlyglobal.jacket.voices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import java.io.File

/*
 * A PDF read aloud, as its reader shows it.
 *
 * The voice is the app's (see Listening), and reads the plain pages made of the PDF's words (see
 * PdfWords.kt), so it is the same voice and the same player as for any other book. Here it is
 * given the PDF's own pages to go by: they turn with it, the sentence it is on is marked on them
 * where it is drawn, and the book's place is kept as the page that sentence is on and how far
 * down it.
 */

/** One line, or part of one, of the sentence being read: its box on [page] in the page's own units, and which of the sentence's letters it holds. */
internal class MarkedLine(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val start: Int, val end: Int)

/** A word of the sentence being read: the letter of the sentence it starts at, the line it is on (of the sentence's), and its left and right edges there. */
internal class MarkedWord(val start: Int, val line: Int, val left: Float, val right: Float)

/** The sentence the voice is on, as it is marked on the PDF's pages. */
internal class MarkedSentence(val sentence: PdfSentence, val text: String, val lines: List<MarkedLine>, val words: List<MarkedWord>)

/** What a PDF's pages show of the voice, and what the voice asks of them. Held by the PDF's reader for as long as it is open. */
@Stable
internal class PdfAloud(val listen: ListenState) {
    /** Where the PDF's paragraphs are on its pages; null until its words have been read (and for a PDF that can't be read aloud). */
    var layout: PdfLayout? by mutableStateOf(null)

    /** The sentence marked on the pages: the one being read, or (for a moment) the one the voice stopped on. */
    var marked: MarkedSentence? by mutableStateOf(null)

    /** How strongly it is marked: it comes with the voice, and fades a moment after the voice stops. */
    val strength = Animatable(0f)

    /** The mark's colour, and whether the words still to come are veiled (on a page that isn't white the veil would show as patches). */
    var tint: Color = Color.Transparent

    /** Raised to ask the pages to go to the sentence being read, wherever they are. */
    var calls by mutableIntStateOf(0)

    /** When the pages last moved to follow the voice (uptime millis), and whether they are moving for it now. */
    var followedAt = 0L
    var following = false

    /** The pages answer for themselves: what is at the top of the screen, whether a place is in view, and where a line is in the window. */
    var top: () -> PdfPlace? = { null }
    var goTo: (PdfPlace) -> Unit = {}

    /** Takes the pages to a line (a search result), set a little way down the screen. */
    var show: (PdfPlace) -> Unit = {}

    /** The search result that was gone to, as it is marked on the pages: a box for each line it is on. */
    var found: List<MarkedLine> by mutableStateOf(emptyList())
    var inView: (PdfPlace) -> Boolean = { false }
    var lineInWindow: (MarkedLine) -> Rect? = { null }

    /** A tap on the page at ([x], [y]) of [page], in the page's own units, while it is read aloud: true if the voice went there. */
    var onTap: (page: Int, x: Float, y: Float) -> Boolean = { _, _, _ -> false }

    /** The voice holds the book's place: it is speaking, and the page in view is not what is saved. */
    val holdsThePlace: Boolean get() = voiceHoldsThePlace(listen.active, listen.playing)

    /** The line of the sentence that holds the next word to be spoken: what the pages keep in view. */
    fun line(): MarkedLine? {
        val marked = marked ?: return null
        val spoken = if (listen.phrase == marked.text) listen.spoken else 0
        return marked.lines.firstOrNull { it.end > spoken } ?: marked.lines.lastOrNull()
    }

    /**
     * Draws what is marked on [page], whose image is being drawn [width] units wide: a wash of
     * the mark's colour over the sentence's lines, and, on a white page, a veil over the words
     * still to come, so each one comes up dark as it is spoken (as in any other book).
     */
    fun DrawScope.drawMarks(page: Int, width: Float, veil: Boolean) {
        val marked = marked ?: return
        val strong = strength.value
        if (strong <= 0f || width <= 0f) return
        val scale = size.width / width
        val spoken = if (listen.phrase == marked.text) listen.spoken else Int.MAX_VALUE
        marked.lines.forEachIndexed { index, line ->
            if (line.page != page) return@forEachIndexed
            val top = (line.top - LINE_ROOM) * scale
            val height = (line.bottom - line.top + 2 * LINE_ROOM) * scale
            val left = (line.left - WORD_ROOM) * scale
            val right = (line.right + WORD_ROOM) * scale
            // Ink stays ink under it: the wash darkens the paper, not the letters.
            drawRect(tint, Offset(left, top), Size(right - left, height), alpha = WASH * strong, blendMode = BlendMode.Multiply)
            if (!veil || !listen.active || spoken >= line.end) return@forEachIndexed
            if (spoken <= line.start) {
                // Not reached yet: all of it. (So is the rest of a word broken at the end of the line above.)
                drawRect(Color.White, Offset(left, top), Size(right - left, height), alpha = VEIL * strong)
                return@forEachIndexed
            }
            // From the first word not yet spoken to the end of the line (which, in writing that runs right to left, is its left).
            val words = marked.words.filter { it.line == index }
            val next = words.firstOrNull { it.start >= spoken } ?: return@forEachIndexed
            val leftward = words.size > 1 && words.first().left > words.last().left
            val from = if (leftward) left else next.left * scale
            val to = if (leftward) next.right * scale else right
            if (to > from) drawRect(Color.White, Offset(from, top), Size(to - from, height), alpha = VEIL * strong)
        }
    }

    /** Draws the search result that was gone to, where it is on [page] (drawn [width] units wide): a wash of the mark's colour, as on any other book's page. */
    fun DrawScope.drawFound(page: Int, width: Float) {
        if (width <= 0f) return
        val scale = size.width / width
        for (line in found) {
            if (line.page != page) continue
            val left = (line.left - WORD_ROOM) * scale
            val top = (line.top - LINE_ROOM) * scale
            drawRect(
                tint, Offset(left, top), Size((line.right + WORD_ROOM) * scale - left, (line.bottom - line.top + 2 * LINE_ROOM) * scale),
                alpha = FOUND, blendMode = BlendMode.Multiply,
            )
        }
    }

    private companion object {
        /** How much room the mark takes around a line and its words, in the page's units. */
        const val LINE_ROOM = 1.5f
        const val WORD_ROOM = 1f

        /** The wash's strength, and the veil's; and the wash over a search result. */
        const val WASH = 0.3f
        const val VEIL = 0.5f
        const val FOUND = 0.4f
    }
}

/** What the player and the reader's keys steer: the PDF's voice once there is one, and the wait for it until then. */
private class PdfListen(
    private val aloud: PdfAloud,
    private val voice: () -> ReadAloud?,
    /** Listen was pressed with no voice yet: starts as soon as there is one, or says why there won't be. */
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
) : ListenController {
    override val previousLabel = R.string.listen_previous_sentence
    override val nextLabel = R.string.listen_next_sentence

    override fun start() {
        voice()?.start() ?: onStart()
    }

    override fun toggle() {
        voice()?.toggle() ?: onStart()
    }

    override fun skip(delta: Int) {
        voice()?.skip(delta)
    }

    override fun setRate(rate: Float) {
        voice()?.setRate(rate)
    }

    override fun stop() {
        onStop()
        voice()?.stop()
    }

    override fun backToVoice() {
        voice()?.backToVoice()
        aloud.listen.offPage = false
        aloud.calls++
    }

    override suspend fun spokenLine(): Rect? {
        if (!aloud.listen.active || aloud.listen.offPage) return null
        return aloud.line()?.let(aloud.lineInWindow)
    }
}

/** The book's place while the voice speaks with the PDF open: the sentence it is on, saved as it goes. */
private class SpokenPdf {
    var sentence: Locator? = null
    var at: PdfSentence? = null
    var back = false
    var ended = false
    var savedAt = 0L
    var saved: Locator? = null
}

/**
 * Read-aloud for the PDF [book], open in its reader: gives [nav] the player's controller, the
 * PDF's contents (its outline) and the steps through them, and returns what the pages need to
 * follow the voice. [jumpTo] takes the pages to a page of the PDF.
 *
 * On a phone whose Android can't get at a PDF's words, Listen stays where it is and says so when
 * it is pressed; so it does for a PDF that turns out to have no words.
 */
@Composable
internal fun rememberPdfAloud(book: Book, settings: Settings, page: PageColors, nav: ReaderNav, respellings: List<Respelling>): PdfAloud {
    val context = LocalContext.current
    val library = context.library
    val listening = context.listening
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val aloud = remember(book.id, nav) { PdfAloud(nav.listen) }
    aloud.tint = page.link

    // The PDF's words are read as soon as it is opened (once: the pages made of them are kept),
    // so that Listen finds them ready. Until they are, Listen waits for them.
    val opened by produceState<Result<OpenBook>?>(null, book.id) {
        if (!PdfWords.available) {
            value = Result.failure(PdfWordsUnavailable())
            return@produceState
        }
        var open: OpenBook? = null
        try {
            value = try {
                Result.success(listening.books.acquire(book).also { open = it })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            awaitCancellation()
        } finally {
            open?.let(listening.books::releaseSoon)
        }
    }
    val open = opened?.getOrNull()
    val voice = remember(open) { open?.let { listening.voiceFor(book, it, nav.listen) } }
    SideEffect { aloud.layout = open?.pdf?.layout }

    // Listen pressed before the words were ready: the player says it is getting ready, and the
    // voice starts when they are. A PDF that can't be read aloud says why instead.
    var wanted by remember(book.id) { mutableStateOf(false) }
    fun explain(failure: Throwable) {
        val message = when (failure) {
            is PdfWordsUnavailable -> resources.getString(R.string.pdf_listen_unavailable)
            is NoWordsInPdf -> resources.getString(R.string.pdf_listen_no_text)
            is PdfLocked -> resources.getString(R.string.pdf_listen_locked)
            else -> resources.getString(R.string.reader_error_open_book, failure.message.orEmpty())
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val openedNow by rememberUpdatedState(opened)
    val voiceNow by rememberUpdatedState(voice)
    val controller = remember(aloud) {
        PdfListen(
            aloud,
            voice = { voiceNow },
            onStart = {
                val failure = openedNow?.exceptionOrNull()
                if (failure != null) {
                    explain(failure)
                } else {
                    wanted = true
                    nav.listen.active = true
                    nav.listen.playing = true
                    nav.listen.preparing = true
                }
            },
            onStop = {
                if (wanted) {
                    wanted = false
                    nav.listen.active = false
                    nav.listen.playing = false
                    nav.listen.preparing = false
                }
            },
        )
    }
    LaunchedEffect(opened, voice) {
        if (!wanted) return@LaunchedEffect
        val failure = opened?.exceptionOrNull()
        if (voice != null) {
            wanted = false
            voice.start()
        } else if (failure != null) {
            wanted = false
            nav.listen.active = false
            nav.listen.playing = false
            nav.listen.preparing = false
            explain(failure)
        }
    }
    DisposableEffect(controller) {
        nav.listenController = controller
        onDispose { if (nav.listenController === controller) nav.listenController = null }
    }

    // The PDF's contents, where it has an outline: the list to choose from, the chapter in view,
    // and the steps from one to the next. Without one, the steps go page by page as ever.
    val layout = open?.pdf?.layout
    val chapters = layout?.chapters.orEmpty()
    SideEffect {
        nav.toc = chapters.mapIndexed { index, chapter -> TocItem(chapter.title, chapter.level, index) }
        val current = layout?.chapterAt(nav.section) ?: -1
        nav.tocIndex = current
        nav.chapter = chapters.getOrNull(current)?.title.orEmpty()
        // A chapter opens where its first words are: part of the way down a page, as often as not.
        fun show(chapter: PdfChapter) {
            val place = chapter.paragraph.takeIf { it >= 0 }?.let { layout?.placeOf(it) }?.takeIf { it.page == chapter.page }
            // (Through the pages' own jump first: it keeps the way back to where the reader was.)
            nav.jumpTo(chapter.page)
            if (place != null && place.within > 0.02f) aloud.goTo(place)
        }
        nav.openToc = { item -> chapters.getOrNull(item.index)?.let(::show) }
        nav.sectionGroup = { step -> layout?.chapterAt(step) ?: step }
        nav.marks = if (layout == null || layout.pageCount < 2) emptyList() else chapters.filter { it.level == 0 }.map { it.page / (layout.pageCount - 1f) }.distinct()
        // Read aloud, the steps are the voice's: it goes to the next chapter (or page), and the pages follow.
        nav.stepsByPage = chapters.isEmpty() && voice != null
        fun target(delta: Int): PdfSpot? {
            if (layout == null) return null
            val reading = voice?.pdfSentence?.takeIf { nav.listen.active }
            if (chapters.isEmpty()) {
                val from = reading?.let { layout.placeOf(it.paragraph, it.from)?.page } ?: return null
                return if (delta > 0) layout.nextPageFrom(reading.paragraph, reading.from, reading.to) else layout.textPageBefore(from)?.let(layout::firstOn)
            }
            val from = if (reading != null) layout.chapterOf(reading.paragraph) else return null
            // (Chapters that open on the same words are one step.)
            val here = chapters.getOrNull(from)?.paragraph ?: -1
            val next = if (delta > 0) chapters.drop(from + 1).firstOrNull { it.paragraph > here } else chapters.take(from.coerceAtLeast(0)).lastOrNull { it.paragraph in 0 until here }
            return next?.takeIf { it.paragraph >= 0 }?.let { PdfSpot(it.paragraph, 0) }
        }
        // Read by eye: the chapter after the one the top of the screen is in, or the start of that one (and then the one before).
        fun stepFrom(delta: Int): PdfChapter? {
            val top = aloud.top() ?: PdfPlace(nav.section, 0f)
            fun placeOf(chapter: PdfChapter): PdfPlace = chapter.paragraph.takeIf { it >= 0 }?.let { layout?.placeOf(it) }?.takeIf { it.page == chapter.page } ?: PdfPlace(chapter.page, 0f)
            fun before(a: PdfPlace, b: PdfPlace) = a.page < b.page || (a.page == b.page && a.within < b.within - 0.02f)
            return if (delta > 0) chapters.firstOrNull { before(top, placeOf(it)) } else chapters.lastOrNull { before(placeOf(it), top) }
        }
        nav.step = step@{ delta ->
            val listened = voice?.takeIf { nav.listen.active && it.pdfSentence != null }
            if (listened != null && open != null) {
                val spot = target(delta) ?: return@step
                scope.launch { open.pdfStart(spot, resumed = false)?.let { listened.readFrom(it.block, it.offset, chapter = chapters.isNotEmpty()) } }
            } else if (chapters.isEmpty()) {
                nav.jumpTo(nav.section + delta)
            } else {
                // From before the first chapter, the first; from in between, the one before or after.
                stepFrom(delta)?.let(::show)
            }
        }
        nav.canStep = { delta ->
            when {
                voice != null && nav.listen.active && voice.pdfSentence != null -> target(delta) != null
                chapters.isEmpty() -> nav.section + delta in 0 until nav.sectionCount
                else -> stepFrom(delta) != null
            }
        }
    }

    if (open == null || voice == null || layout == null) return aloud

    // The voice has these pages for as long as the PDF is open here. They aren't Readium's, so
    // it has no navigator to ask: the pages say where it starts, and follow what it says of where it is.
    val voicePage = remember(voice) {
        VoicePage(
            navigator = { null },
            scope = scope,
            movedAt = { nav.movedAt },
            inSight = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
            chapter = { nav.tocIndex },
        )
    }
    voicePage.startAt = { stopped ->
        // The sentence the voice stopped on, if it is still in view; else the first words on screen.
        val resumed = stopped?.takeIf { it.isSpoken }?.let { open.pdfSentence(it, 1) }
            ?.takeIf { sentence -> layout.placeOf(sentence.paragraph, sentence.from)?.let(aloud.inView) == true }
        when {
            resumed != null -> open.pdfStart(PdfSpot(resumed.paragraph, resumed.from), resumed = true)
            else -> (aloud.top() ?: PdfPlace(nav.section, 0f)).let { top -> layout.spotAt(top) ?: layout.firstOn(top.page) }?.let { open.pdfStart(it, resumed = false) }
        }
    }

    // While the voice speaks, the book's place is the sentence it is on: the page it is drawn on,
    // and how far down it. It never takes the book's progress down unless the listener went back.
    val spoken = remember(voice) { SpokenPdf() }
    fun save() {
        val sentence = spoken.sentence ?: return
        val at = spoken.at ?: return
        if (sentence === spoken.saved && !spoken.ended) return
        val place = layout.placeOf(at.paragraph, at.from) ?: return
        val progress = if (spoken.ended) 1f else layout.progressOf(place)
        library.updateLocator(book.id, sentence.toJSON().toString(), progress, place.page, layout.pageCount, place.within, back = spoken.back)
        spoken.back = false
        spoken.saved = sentence
        spoken.savedAt = SystemClock.uptimeMillis()
    }
    voicePage.onSpoken = { sentence, back ->
        spoken.sentence = sentence
        spoken.at = voice.pdfSentence
        if (back) spoken.back = true
        // Often enough that little is heard twice if the app is closed under the voice, and no oftener.
        if (SystemClock.uptimeMillis() - spoken.savedAt >= SPOKEN_SAVE_MS) save()
    }
    voicePage.onEnded = {
        spoken.ended = true
        save()
        spoken.ended = false
    }
    // Stopped while the listener was looking elsewhere: the way back to where the voice stopped is offered, as after any jump.
    voicePage.onStoppedElsewhere = {
        spoken.at?.let { at -> layout.placeOf(at.paragraph, at.from) }?.let { place ->
            nav.cameFrom = Origin((layout.progressOf(place) * 100).toInt().coerceIn(0, 100), page = place.page + 1) { nav.jumpTo(place.page) }
        }
    }
    // Paused or stopped, and when the screen is left: the sentence it is on is saved there and then.
    LaunchedEffect(voice, nav.listen.playing) { if (!nav.listen.playing) save() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { save() }

    // The voice that reads the PDF's language, the listener's own way of saying words, the speed.
    val voices = context.voices
    val said = open.publication.metadata.language?.code
    nav.bookLanguage = said
    LaunchedEffect(book.id, said) { if (book.language == null && said != null) library.setLanguage(book.id, said) }
    val phoneLanguage = LocalConfiguration.current.locales[0]?.toLanguageTag()
    val voicing = voicing(book.language, said, phoneLanguage, text = true)
    val natural = remember(settings.voice, settings.voices, voicing.readIn) { voices.choose(voicing.readIn, settings) }
    voice.voice = natural
    voice.language = voicing.chosen
    voice.phoneVoiceLanguage = voicing.phoneVoice
    voice.edits.respellings = respellings
    voice.skipNotes = false
    LaunchedEffect(voice, natural, voicing.chosen) { voice.retune() }
    LaunchedEffect(voice, settings.listenRate) { voice.setRate(settings.listenRate) }

    val saveLast by rememberUpdatedState { save() }
    DisposableEffect(voice) {
        listening.install(voice)
        onDispose {
            saveLast()
            voice.detach(voicePage)
            listening.uninstall(voice)
        }
    }
    // A voice that was reading this PDF before it was opened here is joined once its first page is up.
    LaunchedEffect(voice, voicePage) {
        if (nav.listen.active) while (!nav.contentReady) delay(JOIN_POLL_MS)
        voice.attach(voicePage)
    }

    // The sentence the voice is on, found on the PDF's pages: the PDF reader is asked where
    // its lines and its words are drawn. (A second opening of the file, for its words alone: the
    // one that draws the pages is busy drawing them.)
    val words = remember(book.id) { PdfWordsSource(library.fileOf(book)) { library.passwords.of(book.id) } }
    DisposableEffect(words) { onDispose { words.close() } }

    // Search: the PDF's words are looked through as they are read aloud, and a result is gone to
    // and marked where it is drawn (see PdfSearch.kt).
    val search = remember(open, layout, words) {
        PdfSearch(
            layout = layout,
            paragraphs = { file -> open.pdfParagraphs(file) },
            pageName = { page -> resources.getString(R.string.reader_page_number, page + 1) },
            scope = scope,
            go = go@{ match ->
                val place = layout.placeOf(match.paragraph, match.from) ?: return@go
                // A result on the screen already is only marked. Any other is gone to, and the
                // way back to where the reader was is on offer for a while, as after any jump.
                if (aloud.inView(place)) return@go
                val from = aloud.top() ?: PdfPlace(nav.section, 0f)
                nav.cameFrom = Origin((layout.progressOf(from) * 100).toInt().coerceIn(0, 100), page = from.page + 1) { aloud.goTo(from) }
                nav.movedAt = SystemClock.uptimeMillis()
                aloud.show(place)
            },
            mark = { match, text ->
                aloud.found = if (match == null) emptyList() else words.marked(layout, PdfSentence(match.paragraph, match.from, match.to), text).lines
            },
        )
    }
    DisposableEffect(search) {
        nav.search = search
        onDispose { if (nav.search === search) nav.search = null }
    }
    LaunchedEffect(voice, words) {
        snapshotFlow { voice.pdfSentence }.collectLatest { sentence ->
            // (When the voice stops, what was marked stays to fade: see below.)
            if (sentence == null) return@collectLatest
            val text = open.pdfBlock(sentence.paragraph)?.text ?: return@collectLatest
            aloud.marked = words.marked(layout, sentence, text.substring(sentence.from.coerceAtMost(text.length), sentence.to.coerceAtMost(text.length)))
        }
    }
    // It is marked while the voice is on it, and for a moment after the voice stops: "you were here".
    val appears = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val fades = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    LaunchedEffect(aloud, nav.listen.active) {
        if (nav.listen.active) {
            if (motionRemoved()) aloud.strength.snapTo(1f) else aloud.strength.animateTo(1f, appears)
        } else if (aloud.marked != null) {
            delay(HERE_MS)
            if (motionRemoved()) aloud.strength.snapTo(0f) else aloud.strength.animateTo(0f, fades)
            aloud.marked = null
        }
    }

    // A tap on a line of the page, while it is read aloud: the voice reads on from there.
    aloud.onTap = { tapped, x, y ->
        val spot = if (nav.listen.active && voice.pdfSentence != null) layout.spotNear(tapped, x, y) else null
        if (spot != null) scope.launch { open.pdfStart(spot, resumed = false)?.let { voice.readFrom(it.block, it.offset) } }
        spot != null
    }
    return aloud
}

/** How long the sentence the voice stopped on stays marked before it fades. */
private const val HERE_MS = 1_800L

/** How often a reader that opens on a voice already reading asks whether its first page is up. */
private const val JOIN_POLL_MS = 50L

/**
 * The PDF's file, opened for its words alone (with its [password], if it is locked with one):
 * asked where the sentence being read, or a search result, is drawn. One thing at a time, and off
 * the main thread.
 */
private class PdfWordsSource(private val file: File, private val password: () -> String?) {
    private val one = Dispatchers.IO.limitedParallelism(1)
    private var pdf: PdfWordsFile? = null
    private var closed = false

    /**
     * [sentence], which reads [text], as it is marked on the pages: a box for each line it takes
     * up (each line's own height, and as wide as the sentence's words on it), and where each of
     * its words begins and ends, for the veil that lifts as they are spoken.
     */
    suspend fun marked(layout: PdfLayout, sentence: PdfSentence, text: String): MarkedSentence = withContext(one) {
        val pieces = layout.marks(sentence.paragraph, sentence.from, sentence.to)
        // Every stretch whose place in the page's text is known is asked for: the lines, then the words.
        val spans = WORD.findAll(text).map { it.range }.toList()
        val parts = spans.map { span -> layout.marks(sentence.paragraph, sentence.from + span.first, sentence.from + span.last + 1).firstOrNull() }
        val asked = (pieces + parts.filterNotNull()).filter { it.from >= 0 }.groupBy { it.page }
        val boxes = HashMap<Pair<Int, IntRange>, Pair<Float, Float>>()
        if (!closed && asked.isNotEmpty()) {
            val pdf = pdf ?: runCatching { PdfWordsFile(file, password()) }.getOrNull()?.also { pdf = it }
            if (pdf != null) {
                for ((page, marks) in asked) {
                    val ranges = marks.map { it.from until it.to }.distinct()
                    pdf.boxes(page, ranges).forEachIndexed { index, found ->
                        if (found.isNotEmpty()) boxes[page to ranges[index]] = found.minOf { it.left } to found.maxOf { it.right }
                    }
                }
            }
        }
        val lines = pieces.mapIndexed { index, piece ->
            // Where the reader couldn't say, the whole of the line the words are on.
            val (left, right) = boxes[piece.page to (piece.from until piece.to)] ?: (piece.left to piece.right)
            // A sentence that runs on to another line takes this one to its end: the hyphen of a
            // word broken there is no part of the words read, and is part of the line.
            val runsOn = pieces.getOrNull(index + 1)?.let { it.page != piece.page || it.top != piece.top } == true
            MarkedLine(piece.page, left, piece.top, if (runsOn) maxOf(right, piece.right) else right, piece.bottom, piece.start - sentence.from, piece.end - sentence.from)
        }
        val wordMarks = spans.indices.mapNotNull { index ->
            val part = parts[index] ?: return@mapNotNull null
            val (left, right) = boxes[part.page to (part.from until part.to)] ?: return@mapNotNull null
            val line = pieces.indexOfFirst { it.page == part.page && it.start <= part.start && part.start < it.end }
            if (line < 0) null else MarkedWord(spans[index].first, line, left, right)
        }
        MarkedSentence(sentence, text, lines, wordMarks)
    }

    fun close() {
        DocumentCloser.launch(one) {
            closed = true
            pdf?.close()
            pdf = null
        }
    }

    private companion object {
        val WORD = Regex("\\S+")
    }
}
