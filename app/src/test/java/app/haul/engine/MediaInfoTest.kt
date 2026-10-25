package app.haul.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaInfoTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun parsesVideoAndDropsStoryboards() {
        val info = MediaInfo.parse(res("video.json"), "https://vimeo.com/827140226")
        assertEquals("Sourdough from scratch, the full process", info.title)
        assertEquals("Kitchen Notes", info.uploader)
        assertEquals("vimeo.com", info.host)
        assertEquals(2487, info.durationSec)
        assertFalse(info.formats.any { it.id == "sb0" })
        assertEquals(9, info.formats.size)
        assertTrue(info.hasVideo)
        assertEquals(1_860_000_000L, info.formats.first { it.id == "313" }.filesize)
    }

    @Test fun parsesFlatPlaylist() {
        val info = MediaInfo.parse(res("playlist.json"), "https://www.youtube.com/playlist?list=PL1")
        assertTrue(info.isPlaylist)
        assertEquals(2, info.entries.size)
        assertEquals("https://www.youtube.com/watch?v=bbb", info.entries[1].url)
        assertEquals("https://i.example.com/l.jpg", info.entries[1].thumbnail)
        assertEquals(1338 + 1085, info.durationSec)
        assertTrue(info.formats.isEmpty())
    }

    @Test fun hostStripsPrefixes() {
        assertEquals("youtube.com", MediaInfo.hostOf("https://m.youtube.com/watch?v=1"))
        assertEquals("example.org", MediaInfo.hostOf("http://www.example.org"))
    }

    @Test fun codecNames() {
        assertEquals("AVC", Fmt.codecName("avc1.640028"))
        assertEquals("VP9", Fmt.codecName("vp09.00.40.08"))
        assertEquals("AAC", Fmt.codecName("mp4a.40.2"))
        assertEquals("", Fmt.codecName("none"))
    }
}
