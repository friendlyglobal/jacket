package io.github.friendlyglobal.jacket.reader

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** What a key, or a turn of the mouse wheel, asks of the open book. */
enum class ReaderCommand {
    NEXT_PAGE,
    PREVIOUS_PAGE,

    /** Pause the voice, or have it read on. */
    PLAY_PAUSE,

    /** The key is the book's, and does nothing just now: its page is out of sight. */
    NOTHING,
}

/** What the open book is doing, as far as its keys care. */
data class KeyContext(
    /** The book reads right to left: the left arrow is the way forward. */
    val rightToLeft: Boolean = false,
    /** The book is being read aloud (speaking or paused): Space is the player's. */
    val listening: Boolean = false,
    /** "Turn pages with volume keys" is on. */
    val volumeKeys: Boolean = false,
    /** The page is in sight to be turned (not under the cover of a folded phone). */
    val pages: Boolean = true,
    /** The book is laid out in pages, which a mouse wheel turns; a scrolling one it scrolls, as ever. */
    val paged: Boolean = false,
)

/**
 * What the key [keyCode] does while a book is open; null for a key that isn't the book's.
 *
 * The arrows across go by the book's direction (in a right-to-left book the left arrow is the
 * next page); the arrows up and down, Page Up and Page Down, and Space with or without Shift, by
 * reading order. These are what a keyboard, a remote and a Bluetooth page-turner send. While the
 * book is read aloud, Space pauses the voice and has it read on. The volume keys turn pages when
 * that is switched on, and not while listening, when they are for the volume.
 */
internal fun readerCommand(keyCode: Int, shift: Boolean, context: KeyContext): ReaderCommand? {
    val forward = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> !context.rightToLeft
        KeyEvent.KEYCODE_DPAD_LEFT -> context.rightToLeft
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> true
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> false
        KeyEvent.KEYCODE_SPACE -> if (context.listening) return ReaderCommand.PLAY_PAUSE else !shift
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
            // Left to the phone unless they are the book's: its volume panel shows as ever.
            if (!context.volumeKeys || context.listening || !context.pages) return null
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        }
        else -> return null
    }
    // Kept from the page all the same: the page under a cover would scroll at an arrow key.
    if (!context.pages) return ReaderCommand.NOTHING
    return if (forward) ReaderCommand.NEXT_PAGE else ReaderCommand.PREVIOUS_PAGE
}

/**
 * A key held down repeats many times a second. Pages turn at a pace that can be followed, and the
 * voice is paused or started by a press, never by a key resting under a thumb.
 */
internal class KeyRepeats {
    private var turnedAt = Long.MIN_VALUE / 2

    /** Whether [command] is carried out for a key press that is the [repeat]th repeat (0 for the press itself) at [at] milliseconds. */
    fun accepts(command: ReaderCommand, repeat: Int, at: Long): Boolean = when (command) {
        ReaderCommand.NOTHING -> false
        ReaderCommand.PLAY_PAUSE -> repeat == 0
        ReaderCommand.NEXT_PAGE, ReaderCommand.PREVIOUS_PAGE -> (repeat == 0 || at - turnedAt >= HELD_PAGE_MS).also { if (it) turnedAt = at }
    }

    private companion object {
        /** A key held down turns a page this often. */
        const val HELD_PAGE_MS = 180L
    }
}

/**
 * Turns of a mouse wheel into page turns. A click of a wheel is a page. A touchpad sends many small
 * movements for one swipe: they are gathered until they make a click, and what is left of the swipe
 * is let go, so one swipe is one page and not a chapter.
 */
internal class WheelPages {
    private var gathered = 0f
    private var movedAt = Long.MIN_VALUE / 2
    private var turnedAt = Long.MIN_VALUE / 2

    /**
     * The wheel moved [down] (positive towards the reader, as for scrolling down) and [right], at
     * [at] milliseconds. Returns +1 for the next page, -1 for the previous one, 0 for neither yet.
     * Up and down go by reading order; across goes by the book's direction, like the arrow keys.
     */
    fun turned(down: Float, right: Float, rightToLeft: Boolean, at: Long): Int {
        val forward = if (abs(down) >= abs(right)) down else if (rightToLeft) -right else right
        // A wheel left alone starts afresh, as does one turned back.
        if (at - movedAt > QUIET_MS || gathered * forward < 0f) gathered = 0f
        movedAt = at
        if (at - turnedAt < REST_MS) return 0
        gathered += forward
        if (abs(gathered) < CLICK) return 0
        val page = if (gathered > 0f) 1 else -1
        gathered = 0f
        turnedAt = at
        return page
    }

    private companion object {
        /** One click of a mouse wheel, as Android reports it. */
        const val CLICK = 1f

        /** After a page has turned, the wheel is not listened to for this long. */
        const val REST_MS = 220L

        /** Movements further apart than this are separate gestures. */
        const val QUIET_MS = 400L
    }
}

/**
 * Where the activity sends key presses and turns of the mouse wheel while a book is open. Keys
 * reach the activity whatever has the focus (the page, a button, nothing), so a page-turner works
 * without the page being tapped first. A sheet over the reader (search, a note being written) is a
 * window of its own, with its own keys.
 */
object ReaderKeys {
    /** The open book: what its keys do just now ([context]), and the doing of it ([perform]). */
    class Target(val context: () -> KeyContext, val perform: (ReaderCommand) -> Unit)

    /** Set while a book is open. */
    var target: Target? = null

    private val repeats = KeyRepeats()
    private val wheel = WheelPages()

    /** True if [event] was the book's: the activity passes it no further. */
    fun key(event: KeyEvent): Boolean {
        val target = target ?: return false
        // With Ctrl, Alt or the system key held it's a shortcut of someone else's.
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val command = readerCommand(event.keyCode, event.isShiftPressed, target.context()) ?: return false
        // The release is the book's too: nothing else acts on half a key press.
        if (event.action == KeyEvent.ACTION_DOWN && repeats.accepts(command, event.repeatCount, event.eventTime)) target.perform(command)
        return true
    }

    /** True if [event] was a turn of the wheel over a book laid out in pages. */
    fun wheel(event: MotionEvent): Boolean {
        val target = target ?: return false
        if (event.action != MotionEvent.ACTION_SCROLL || !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return false
        val context = target.context()
        if (!context.paged) return false
        if (!context.pages) return true
        // Android counts a wheel turned towards the reader (scrolling down) as negative.
        val page = wheel.turned(
            down = -event.getAxisValue(MotionEvent.AXIS_VSCROLL),
            right = event.getAxisValue(MotionEvent.AXIS_HSCROLL),
            rightToLeft = context.rightToLeft,
            at = event.eventTime,
        )
        if (page != 0) target.perform(if (page > 0) ReaderCommand.NEXT_PAGE else ReaderCommand.PREVIOUS_PAGE)
        return true
    }
}
