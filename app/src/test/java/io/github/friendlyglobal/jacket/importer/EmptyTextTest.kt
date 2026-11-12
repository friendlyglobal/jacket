package io.github.friendlyglobal.jacket.importer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A text file with nothing in it isn't added as a book: it would only ever open on "This file is
 * empty or couldn't be read". Caught on the emulator with an empty file shared from another app.
 */
class EmptyTextTest {
    private fun hasText(bytes: ByteArray): Boolean {
        val folder = Files.createTempDirectory("jacket-empty-text").toFile()
        try {
            return BookImporter.hasText(File(folder, "note.txt.part").apply { writeBytes(bytes) })
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun hasText(text: String) = hasText(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `a file of no bytes has nothing to read`() {
        assertFalse(hasText(""))
    }

    @Test
    fun `nor has one of spaces and line breaks`() {
        assertFalse(hasText("   \n\n \t\r\n"))
        // The spaces a word processor leaves: no-break, ideographic.
        assertFalse(hasText(" 　\n"))
        // The mark some editors put at the very start of a file, and nothing after it.
        assertFalse(hasText("﻿"))
        assertFalse(hasText("﻿\n  \n"))
    }

    @Test
    fun `one word is something to read`() {
        assertTrue(hasText("Hello"))
        assertTrue(hasText("﻿\n\n   Hello\n"))
        // A scene break is a paragraph to the reader too.
        assertTrue(hasText("\n* * *\n"))
        assertTrue(hasText("1"))
    }

    @Test
    fun `a long run of blank lines is read through to the word after it`() {
        assertTrue(hasText("\n".repeat(50_000) + "The end."))
        assertFalse(hasText("\n".repeat(50_000)))
    }

    @Test
    fun `text that isn't UTF-8 is still text`() {
        // "café" as an old Windows editor saves it: the reader reads such a file as Latin-1.
        assertTrue(hasText(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())))
        assertTrue(hasText(byteArrayOf(0xE9.toByte())))
    }

    @Test
    fun `a file that isn't there has nothing to read`() {
        assertFalse(BookImporter.hasText(File("/nowhere/at/all.txt")))
    }
}
