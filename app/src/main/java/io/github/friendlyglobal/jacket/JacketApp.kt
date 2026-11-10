package io.github.friendlyglobal.jacket

import android.app.Application
import android.content.Context
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.reader.ReadiumBooks

class JacketApp : Application() {
    val library: Library by lazy { Library(this) }
    val readium: ReadiumBooks by lazy { ReadiumBooks(this) }
}

val Context.library: Library get() = (applicationContext as JacketApp).library
val Context.readium: ReadiumBooks get() = (applicationContext as JacketApp).readium
