package io.github.friendlyglobal.jacket.data

/**
 * What the open book's reader is doing right now, for the outer screen and the widget: the chapter
 * you're in, how far through it, and whether the book is being read aloud. The saved [Book] only
 * knows the place in the whole book. Not saved: it lasts as long as the book is open.
 */
data class ReadingMoment(
    val bookId: String,
    /** The chapter's name, for formats that have chapters; empty otherwise. */
    val chapter: String = "",
    /** 0..1 through [chapter]; negative while unknown. */
    val chapterProgress: Float = -1f,
    /** Audiobook mode is on (playing or paused). */
    val listening: Boolean = false,
    /** The voice is speaking right now. */
    val playing: Boolean = false,
    /** Where the book's chapters start, as fractions of the whole (0..1): the cover draws the book as its chapters. */
    val marks: List<Float> = emptyList(),
    /** The whole minutes a sleep timer still has to run; 0 without a timed one. */
    val sleepMinutes: Int = 0,
    /** The sleep timer is waiting for the chapter to end. */
    val sleepAtChapterEnd: Boolean = false,
)

/** The stages of a book, as the cover screen and the widget tell them apart. */
enum class CoverPhase { NEW, READING, ALMOST_DONE, LISTENING, FINISHED }

/**
 * Everything the cover screen and the widget show about progress, worked out in one place so they
 * always agree: the [phase], the place in the book and, while it's open, in the chapter.
 */
data class CoverStatus(
    val phase: CoverPhase,
    val progress: Float,
    val percent: Int,
    /** Empty when the format has no chapters or the book isn't open. */
    val chapter: String = "",
    /** 0..1 through the chapter, or null when unknown. */
    val chapterProgress: Float? = null,
    val playing: Boolean = false,
    val finishedAt: Long = 0L,
    /** Chapter starts as fractions of the book, when the open book has told us. */
    val marks: List<Float> = emptyList(),
) {
    /** Both scales are known: the chapter gets the wave, the book a thinner line under it. */
    val hasChapterScale: Boolean get() = chapter.isNotEmpty() && chapterProgress != null

    companion object {
        /** The last stretch of a book gets its own accent from here on. */
        const val ALMOST_DONE_FROM = 0.9f

        /** [moment] only counts if it belongs to [book] (another book may be open in the reader). */
        fun of(book: Book, moment: ReadingMoment?): CoverStatus {
            val now = moment?.takeIf { it.bookId == book.id }
            val phase = when {
                // Listening wins: it's what is happening, whatever the book's place.
                now?.listening == true -> CoverPhase.LISTENING
                book.isFinished -> CoverPhase.FINISHED
                book.isNew || book.percent == 0 -> CoverPhase.NEW
                book.progress >= ALMOST_DONE_FROM -> CoverPhase.ALMOST_DONE
                else -> CoverPhase.READING
            }
            return CoverStatus(
                phase = phase,
                progress = book.progress.coerceIn(0f, 1f),
                percent = book.percent,
                chapter = now?.chapter.orEmpty(),
                chapterProgress = now?.chapterProgress?.takeIf { it >= 0f }?.coerceAtMost(1f),
                playing = now?.playing == true,
                finishedAt = book.finishedAt,
                marks = now?.marks.orEmpty(),
            )
        }
    }
}
