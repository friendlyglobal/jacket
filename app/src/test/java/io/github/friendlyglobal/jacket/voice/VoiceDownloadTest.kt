package io.github.friendlyglobal.jacket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URL
import java.security.MessageDigest

class VoiceDownloadTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val vocoder = "the vocoder's weights".toByteArray()
    private val tokens = "a b c".toByteArray()
    private val pack = VoicePack(
        id = "test",
        name = "Test",
        files = mapOf("model" to "model.onnx", "tokens" to "tokens.txt"),
        languages = listOf("en"),
        voices = listOf(PackVoice("M1", "M1", 0)),
        license = PackLicense("Test"),
        downloads = listOf(
            VoiceFile("model.onnx", "https://github.com/x/y/releases/download/voices-1/test.model.onnx", vocoder.size.toLong(), sha256(vocoder)),
            VoiceFile("tokens.txt", "https://github.com/x/y/releases/download/voices-1/test.tokens.txt", tokens.size.toLong(), sha256(tokens)),
        ),
    )

    @Test
    fun onlyGitHubOverHttpsIsAllowed() {
        assertTrue(VoiceDownload.allowed(URL("https://github.com/friendlyglobal/jacket/releases/download/voices-1/cadu.tokens.txt")))
        assertTrue(VoiceDownload.allowed(URL("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sig=x")))
        assertTrue(VoiceDownload.allowed(URL("https://GitHub.com:443/a")))
        assertFalse(VoiceDownload.allowed(URL("http://github.com/a")))
        assertFalse(VoiceDownload.allowed(URL("https://github.com.example.org/a")))
        assertFalse(VoiceDownload.allowed(URL("https://example.org/github.com")))
        assertFalse(VoiceDownload.allowed(URL("https://github.com:8443/a")))
        assertFalse(VoiceDownload.allowed(URL("https://user:secret@github.com/a")))
    }

    @Test
    fun aContentRangeIsRead() {
        assertEquals(ContentRange(100, 199, 968), ContentRange.parse("bytes 100-199/968"))
        assertEquals(ContentRange(0, 9, null), ContentRange.parse(" bytes 0-9/* "))
        assertNull(ContentRange.parse("bytes */968"))
        assertNull(ContentRange.parse("bytes 9-0/968"))
        assertNull(ContentRange.parse(null))
    }

    @Test
    fun theRealCatalogIsDownloadableAndChecked() {
        val catalog = File("src/main/assets/voices/catalog.json").readText()
        val packs = VoiceCatalog.parse(catalog)
        assertEquals(listOf("supertonic3", "cadu"), packs.map { it.id })
        for (pack in packs) {
            assertTrue("${pack.id} can't be downloaded", pack.downloadable)
            for (file in pack.downloads) {
                assertEquals("https://github.com/friendlyglobal/jacket/releases/download/voices-1/${pack.id}.${file.name}", file.url)
                assertTrue(VoiceDownload.allowed(URL(file.url)))
                assertEquals(64, file.sha256.length)
            }
        }
        // About 145 MB for 31 languages, and about 32 MB for Brazilian Portuguese alone.
        assertEquals(145_295_768L, packs[0].bytes)
        assertEquals(31_790_449L, packs[1].bytes)
    }

    @Test
    fun aFileIsUsedOnlyOnceItChecksOut() {
        val store = VoiceStore(temp.root)
        store.prepare(pack)
        assertFalse(store.isReady(pack))
        assertEquals(pack.bytes, store.bytesToGo(pack))

        // Half of one file has arrived: it counts as here, and isn't used.
        val model = pack.downloads[0]
        store.partOf(pack, model).writeBytes(vocoder.copyOf(5))
        assertEquals(5L, store.bytesHere(pack))
        assertFalse(store.isChecked(pack, model))

        // The rest arrives, and it checks out: it moves into place.
        store.partOf(pack, model).appendBytes(vocoder.copyOfRange(5, vocoder.size))
        assertTrue(store.accept(pack, model))
        assertTrue(store.isChecked(pack, model))
        assertFalse(store.partOf(pack, model).exists())
        assertFalse(store.isReady(pack))

        // A damaged one is deleted, never kept.
        val list = pack.downloads[1]
        store.partOf(pack, list).writeBytes("a b x".toByteArray())
        assertFalse(store.accept(pack, list))
        assertFalse(store.partOf(pack, list).exists())
        assertFalse(store.placeOf(pack, list).exists())

        store.partOf(pack, list).writeBytes(tokens)
        assertTrue(store.accept(pack, list))
        assertTrue(store.isReady(pack))
        assertEquals(0L, store.bytesToGo(pack))

        store.remove(pack)
        assertFalse(store.isReady(pack))
        assertFalse(store.folder(pack).exists())
    }

    @Test
    fun aNewReleaseOfAFileReplacesTheOldOne() {
        val store = VoiceStore(temp.root)
        store.prepare(pack)
        for (file in pack.downloads) {
            store.partOf(pack, file).writeBytes(if (file.name == "tokens.txt") tokens else vocoder)
            assertTrue(store.accept(pack, file))
        }
        assertTrue(store.isReady(pack))
        // The catalog now lists another tokens.txt: the one here no longer counts, and goes when the download starts.
        val newer = "a b d".toByteArray()
        val next = pack.copy(downloads = pack.downloads.map { if (it.name == "tokens.txt") it.copy(sha256 = sha256(newer)) else it })
        assertFalse(store.isReady(next))
        File(store.folder(next), "left-over.part").writeText("from an older catalog")
        store.prepare(next)
        assertFalse(store.placeOf(next, next.downloads[1]).exists())
        assertFalse(File(store.folder(next), "left-over.part").exists())
        assertTrue(store.isChecked(next, next.downloads[0]))
        store.partOf(next, next.downloads[1]).writeBytes(newer)
        assertTrue(store.accept(next, next.downloads[1]))
        assertTrue(store.isReady(next))
    }
}
