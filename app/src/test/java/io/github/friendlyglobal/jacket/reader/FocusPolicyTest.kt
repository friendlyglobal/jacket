package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A book being read aloud shares the phone's sound with other apps: it stops for a moment's
 * interruption and goes on after it, and stops for good when another app starts to play.
 * Before this, a ringing call or a spoken direction was talked over, and (in plain text) a
 * moment's interruption stopped the voice for good.
 */
class FocusPolicyTest {
    private val policy = FocusPolicy()

    @Test
    fun `a moment's interruption stops the voice, and it goes on when the sound is back`() {
        assertEquals(FocusAct.PAUSE, policy.lost(forAMoment = true, speaking = true))
        assertTrue(policy.waiting)
        assertEquals(FocusAct.PLAY, policy.gained())
        assertFalse(policy.waiting)
        // Once: the sound coming back again starts nothing more.
        assertEquals(FocusAct.NOTHING, policy.gained())
    }

    @Test
    fun `another app starting to play stops the voice for good`() {
        assertEquals(FocusAct.PAUSE, policy.lost(forAMoment = false, speaking = true))
        assertFalse(policy.waiting)
        assertEquals(FocusAct.NOTHING, policy.gained())
    }

    @Test
    fun `a voice the listener had paused stays paused through an interruption`() {
        assertEquals(FocusAct.NOTHING, policy.lost(forAMoment = true, speaking = false))
        assertFalse(policy.waiting)
        assertEquals(FocusAct.NOTHING, policy.gained())
        assertEquals(FocusAct.NOTHING, policy.lost(forAMoment = false, speaking = false))
        assertEquals(FocusAct.NOTHING, policy.gained())
    }

    @Test
    fun `pausing during an interruption means the voice does not start by itself after it`() {
        policy.lost(forAMoment = true, speaking = true)
        // The listener presses pause (or the sleep timer runs out) while the call is still ringing.
        policy.decidedByListener()
        assertEquals(FocusAct.NOTHING, policy.gained())
    }

    @Test
    fun `starting the voice by hand during an interruption is not answered with a second start`() {
        policy.lost(forAMoment = true, speaking = true)
        policy.decidedByListener()
        assertFalse(policy.waiting)
        assertEquals(FocusAct.NOTHING, policy.gained())
        // And the next interruption is an interruption like any other.
        assertEquals(FocusAct.PAUSE, policy.lost(forAMoment = true, speaking = true))
        assertEquals(FocusAct.PLAY, policy.gained())
    }

    @Test
    fun `a moment's loss that turns into another app playing is not waited out`() {
        assertEquals(FocusAct.PAUSE, policy.lost(forAMoment = true, speaking = true))
        // The voice is already stopped when the sound is taken to keep.
        assertEquals(FocusAct.NOTHING, policy.lost(forAMoment = false, speaking = false))
        assertFalse(policy.waiting)
        assertEquals(FocusAct.NOTHING, policy.gained())
    }

    @Test
    fun `one interruption on top of another is still one wait`() {
        assertEquals(FocusAct.PAUSE, policy.lost(forAMoment = true, speaking = true))
        // A second app takes the sound for a moment while the first still has it.
        assertEquals(FocusAct.NOTHING, policy.lost(forAMoment = true, speaking = false))
        assertTrue(policy.waiting)
        assertEquals(FocusAct.PLAY, policy.gained())
    }

    @Test
    fun `it works the same every time`() {
        repeat(3) {
            assertEquals(FocusAct.PAUSE, policy.lost(forAMoment = true, speaking = true))
            assertEquals(FocusAct.PLAY, policy.gained())
        }
        assertEquals(FocusAct.PAUSE, policy.lost(forAMoment = false, speaking = true))
        assertEquals(FocusAct.NOTHING, policy.gained())
        // Started again by hand, it is interrupted and resumed as before.
        policy.decidedByListener()
        assertEquals(FocusAct.PAUSE, policy.lost(forAMoment = true, speaking = true))
        assertEquals(FocusAct.PLAY, policy.gained())
    }
}
