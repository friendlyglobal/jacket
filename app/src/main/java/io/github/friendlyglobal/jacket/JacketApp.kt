package io.github.friendlyglobal.jacket

import android.app.Application
import android.content.Context
import io.github.friendlyglobal.jacket.data.AutoBackup
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.reader.ReadiumBooks
import io.github.friendlyglobal.jacket.voice.Voices

class JacketApp : Application() {
    val library: Library by lazy { Library(this) }
    val readium: ReadiumBooks by lazy { ReadiumBooks(this) }
    val voices: Voices by lazy { Voices(this) }
    val autoBackup: AutoBackup by lazy { AutoBackup(this, library) }

    override fun onCreate() {
        super.onCreate()
        // Watching the library from the start, so a backup the reader keeps up to date stays so.
        autoBackup
    }
}

val Context.library: Library get() = (applicationContext as JacketApp).library
val Context.readium: ReadiumBooks get() = (applicationContext as JacketApp).readium
val Context.voices: Voices get() = (applicationContext as JacketApp).voices
val Context.autoBackup: AutoBackup get() = (applicationContext as JacketApp).autoBackup
