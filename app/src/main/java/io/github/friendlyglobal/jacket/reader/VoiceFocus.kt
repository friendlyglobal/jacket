package io.github.friendlyglobal.jacket.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player

/*
 * Sharing the phone's sound with other apps while a book is read aloud.
 *
 * When another app takes the sound for a moment (a call ringing, a spoken direction, the
 * assistant), the voice stops, and goes on by itself when the sound is given back. When another
 * app takes it to keep (music, a video), the voice stops and stays stopped until the listener
 * starts it again.
 *
 * Readium's read-aloud asks for the sound itself, but only knows the second of these: for a
 * moment's loss it carries on speaking, over the call or the direction. So it is told to leave
 * the sound alone (see [leaveTheSoundTo]), and Jacket asks for it here.
 */

/** What the voice is to do about a change in who has the phone's sound. */
internal enum class FocusAct { NOTHING, PAUSE, PLAY }

/**
 * The decisions, apart from Android so they can be tested: what to do when the sound is lost or
 * given back, going by whether the voice was speaking and who stopped it.
 */
internal class FocusPolicy {
    /** The voice was speaking when another app took the sound for a moment: it goes on when the sound is back. */
    var waiting = false
        private set

    /**
     * Another app has the sound: [forAMoment], or to keep. [speaking] is whether the voice was
     * reading just then. A voice the listener had already paused is left as they left it.
     */
    fun lost(forAMoment: Boolean, speaking: Boolean): FocusAct {
        // Taken to keep, even in the middle of a moment's loss: nothing is waited for any more.
        if (!forAMoment) waiting = false
        if (!speaking) return FocusAct.NOTHING
        if (forAMoment) waiting = true
        return FocusAct.PAUSE
    }

    /** The sound is back: the voice goes on only if it was stopped for that moment, and nobody has decided otherwise since. */
    fun gained(): FocusAct {
        val resume = waiting
        waiting = false
        return if (resume) FocusAct.PLAY else FocusAct.NOTHING
    }

    /**
     * The listener paused or started the voice themselves (the player, a headphone button, the
     * sleep timer): from here it is as they left it, whatever happens to the sound.
     */
    fun decidedByListener() {
        waiting = false
    }
}

/**
 * Asks Android for the sound while a book is read aloud, and stops and starts the voice as other
 * apps take it and give it back (see [FocusPolicy]). [pause] and [play] stop and start the voice;
 * [speaking] says whether it has been asked to read.
 */
internal class VoiceFocus(context: Context, private val speaking: () -> Boolean, private val pause: () -> Unit, private val play: () -> Unit) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val policy = FocusPolicy()
    private var held = false

    /** What the voice was last told to do from here (true to read), until it is seen to have done it: a change that isn't this one is the listener's. */
    private var told: Boolean? = null

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        // Speech can't usefully be turned down under a spoken direction: it stops for it instead.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> changed(change) }, Handler(Looper.getMainLooper()))
        .build()

    /**
     * Takes the sound, before the voice reads; true if it may. Another app may refuse to give it
     * up (a phone call does): the voice then stays paused.
     */
    fun acquire(): Boolean {
        if (!held) held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    /** Gives the sound up: read-aloud is over. */
    fun release() {
        policy.decidedByListener()
        told = null
        if (!held) return
        held = false
        audio.abandonAudioFocusRequest(request)
    }

    /**
     * The voice has been asked to read ([reading]) or to stop, by whoever asked: what was told
     * from here is expected, anything else is the listener's own doing. Reading, it needs the sound.
     */
    fun asked(reading: Boolean) {
        val mine = told == reading
        told = null
        if (mine) return
        policy.decidedByListener()
        if (reading && !acquire()) tell(false)
    }

    private fun changed(change: Int) {
        val act = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                held = true
                policy.gained()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Gone for good: it is asked for again when the listener next starts the voice.
                held = false
                policy.lost(forAMoment = false, speaking = speaking())
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> policy.lost(forAMoment = true, speaking = speaking())
            else -> FocusAct.NOTHING
        }
        when (act) {
            FocusAct.PAUSE -> tell(false)
            FocusAct.PLAY -> tell(true)
            FocusAct.NOTHING -> {}
        }
    }

    private fun tell(reading: Boolean) {
        if (speaking() == reading) return
        told = reading
        if (reading) play() else pause()
    }

    companion object {
        /**
         * Has Readium's read-aloud leave the sound alone: [player] is the navigator's own Media3
         * player. Its handling of the sound goes by the kind of sound it is told it plays, and
         * for this kind it asks for nothing and stops for nothing. (Only its asking is changed:
         * the voice itself is played as speech, as ever, and unplugged headphones still pause it.)
         */
        fun leaveTheSoundTo(player: Player) {
            runCatching {
                player.setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_VOICE_COMMUNICATION_SIGNALLING).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                    false,
                )
            }
        }
    }
}
