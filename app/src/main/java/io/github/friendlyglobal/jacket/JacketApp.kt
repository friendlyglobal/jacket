package io.github.friendlyglobal.jacket

import android.app.Application
import android.content.Context
import io.github.friendlyglobal.jacket.data.Library

class JacketApp : Application() {
    val library: Library by lazy { Library(this) }
}

val Context.library: Library get() = (applicationContext as JacketApp).library
