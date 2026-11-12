package io.github.friendlyglobal.jacket

import android.app.Application
import android.content.Context
import io.github.friendlyglobal.jacket.data.AutoBackup
import io.github.friendlyglobal.jacket.data.CatchUp
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.reader.ListenService
import io.github.friendlyglobal.jacket.reader.Listening
import io.github.friendlyglobal.jacket.reader.ReadiumBooks
import io.github.friendlyglobal.jacket.voice.Voices

class JacketApp : Application() {
    val library: Library by lazy { Library(this) }
    val readium: ReadiumBooks by lazy { ReadiumBooks(this) }
    val voices: Voices by lazy { Voices(this) }
    val autoBackup: AutoBackup by lazy { AutoBackup(this, library) }

    /** Reading on two devices: this one's file in the place they share, and what the others say in theirs. */
    internal val catchUp: CatchUp by lazy { CatchUp(this, library) }

    /** Read-aloud: the one voice the app has, which reads on with no reader open. */
    val listening: Listening by lazy { Listening(this) }

    override fun onCreate() {
        super.onCreate()
        // Watching the library from the start, so a backup the reader keeps up to date stays so.
        autoBackup
        // And so that what the reader does here (and deletes here) is told to their other devices.
        catchUp
        // A read-aloud notification still up from before Jacket was closed shows the book as it was then.
        ListenService.clearLeftover(this)
    }
}

val Context.library: Library get() = (applicationContext as JacketApp).library
val Context.readium: ReadiumBooks get() = (applicationContext as JacketApp).readium
val Context.voices: Voices get() = (applicationContext as JacketApp).voices
val Context.autoBackup: AutoBackup get() = (applicationContext as JacketApp).autoBackup
internal val Context.catchUp: CatchUp get() = (applicationContext as JacketApp).catchUp
val Context.listening: Listening get() = (applicationContext as JacketApp).listening
