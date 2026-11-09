package io.github.friendlyglobal.jacket.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun comicPagesSortLikeHumansExpect() {
        val pages = listOf("page10.jpg", "page2.jpg", "page1.jpg", "Page03.jpg", "cover.jpg")
        assertEquals(
            listOf("cover.jpg", "page1.jpg", "page2.jpg", "Page03.jpg", "page10.jpg"),
            pages.sortedWith(Comic.NaturalOrder),
        )
    }
}
