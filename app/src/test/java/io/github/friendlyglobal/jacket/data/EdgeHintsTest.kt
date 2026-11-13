package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeHintsTest {
    @Test
    fun `how many times the edges' handles have shown by themselves is kept with the settings`() {
        val text = LibraryJson.encode(LibraryState(settings = Settings(edgeHints = 2)))
        assertEquals(2, LibraryJson.read(text).state.settings.edgeHints)
    }

    @Test
    fun `a library from before the handles has never shown them`() {
        val old = """{"version":1,"settings":{"edgeSwipes":true},"books":[],"annotations":[]}"""
        assertEquals(0, LibraryJson.read(old).state.settings.edgeHints)
        // Nor does a count that makes no sense mean anything.
        val odd = """{"version":1,"settings":{"edgeHints":-4},"books":[],"annotations":[]}"""
        assertEquals(0, LibraryJson.read(odd).state.settings.edgeHints)
    }
}
