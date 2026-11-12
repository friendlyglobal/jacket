package io.github.friendlyglobal.jacket.reader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenRemoteTest {
    /** A player that keeps what it was asked to do. */
    private class Recorder : ListenController {
        val skips = ArrayList<Int>()
        override val previousLabel = 0
        override val nextLabel = 0
        override fun start() {}
        override fun toggle() {}
        override fun skip(delta: Int) { skips += delta }
        override fun setRate(rate: Float) {}
        override fun stop() {}
    }

    private val listen = ListenState()
    private val controller = Recorder()
    private var hasController = true
    private var chapters = true
    private var own = 0

    private fun open() {
        ListenRemote.player = ListenRemote.Player(listen, { controller.takeIf { hasController } }, { chapters }, { own })
    }

    @After
    fun close() {
        ListenRemote.player = null
    }

    @Test
    fun `with no book open the buttons have nothing to act on`() {
        assertFalse(ListenRemote.skip(1))
        assertFalse(ListenRemote.nextSleep(now = 0))
    }

    @Test
    fun `a book that is open but not read aloud is left alone`() {
        open()
        assertFalse(ListenRemote.skip(-1))
        assertFalse(ListenRemote.nextSleep(now = 0))
        assertEquals(emptyList<Int>(), controller.skips)
        assertEquals(SleepTimer.OFF, listen.sleep)
        // Nor one whose format can't be read aloud at all.
        listen.active = true
        hasController = false
        assertFalse(ListenRemote.skip(1))
        assertFalse(ListenRemote.nextSleep(now = 0))
    }

    @Test
    fun `back and on go to the reader's own player, playing or paused`() {
        open()
        listen.active = true
        listen.playing = true
        assertTrue(ListenRemote.skip(-1))
        listen.playing = false
        assertTrue(ListenRemote.skip(1))
        assertTrue(ListenRemote.skip(1))
        assertEquals(listOf(-1, 1, 1), controller.skips)
    }

    @Test
    fun `the sleep button sets the reader's own timer, one length after another`() {
        open()
        listen.active = true
        own = 45
        val minute = 60_000L
        val seen = ArrayList<Triple<SleepTimer, Long, Int>>()
        repeat(7) { press ->
            val now = 1_000L * press
            assertTrue(ListenRemote.nextSleep(now))
            seen += Triple(listen.sleep, listen.sleepEndsAt - now, listen.sleepLeft)
        }
        assertEquals(
            listOf(
                Triple(SleepTimer.MIN_15, 15 * minute, 15),
                Triple(SleepTimer.MIN_30, 30 * minute, 30),
                Triple(SleepTimer.MIN_60, 60 * minute, 60),
                Triple(SleepTimer.OTHER, 45 * minute, 45),
                Triple(SleepTimer.CHAPTER, 0L, 0),
                Triple(SleepTimer.OFF, 0L, 0),
                Triple(SleepTimer.MIN_15, 15 * minute, 15),
            ),
            seen,
        )
    }

    @Test
    fun `a book without chapters is never left waiting for the end of one`() {
        open()
        listen.active = true
        chapters = false
        val timers = (1..5).map { ListenRemote.nextSleep(now = 0); listen.sleep }
        assertEquals(listOf(SleepTimer.MIN_15, SleepTimer.MIN_30, SleepTimer.MIN_60, SleepTimer.OFF, SleepTimer.MIN_15), timers)
    }

    @Test
    fun `the reader that closes takes only its own player away`() {
        open()
        val first = ListenRemote.player
        // The next book's reader opens before the last one's has gone.
        open()
        val second = ListenRemote.player
        if (ListenRemote.player === first) ListenRemote.player = null
        assertTrue(ListenRemote.player === second)
    }
}
