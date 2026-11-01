package app.haul.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimTest {
    @Test fun parsesTimes() {
        assertEquals(45, Trim.parse("45"))
        assertEquals(65, Trim.parse("1:05"))
        assertEquals(3725, Trim.parse("1:02:05"))
        assertEquals(30, Trim.parse(" 0:30 "))
        assertNull(Trim.parse(""))
        assertNull(Trim.parse("1:60"))
        assertNull(Trim.parse("1::05"))
        assertNull(Trim.parse("a:05"))
        assertNull(Trim.parse("1:2:3:4"))
    }

    @Test fun formatsTimes() {
        assertEquals("0:05", Trim.format(5))
        assertEquals("1:45", Trim.format(105))
        assertEquals("1:02:05", Trim.format(3725))
        assertEquals("0:30–1:45", TrimRange(30, 105).label)
    }

    @Test fun validatesAgainstDuration() {
        assertNull(Trim.error("0:30", "1:45", 200))
        assertNotNull(Trim.error("1:45", "0:30", 200))
        assertNotNull(Trim.error("0:30", "0:30", 200))
        assertTrue(Trim.error("0:30", "4:00", 200)!!.contains("3:20"))
        // Unknown length: only the order is checked.
        assertNull(Trim.error("0:30", "59:00", 0))
        assertEquals(TrimRange(30, 105), Trim.range("0:30", "1:45", 200))
    }

    @Test fun storesRange() {
        assertEquals("30-105", TrimRange(30, 105).encode())
        assertEquals(TrimRange(30, 105), TrimRange.decode("30-105"))
        assertNull(TrimRange.decode(""))
        assertNull(TrimRange.decode("105-30"))
        assertNull(TrimRange.decode("x-1"))
    }

    @Test fun buildsArgs() {
        assertEquals(
            listOf("--download-sections", "*30-105", "--force-keyframes-at-cuts"),
            Trim.ytDlpArgs(TrimRange(30, 105)),
        )
        val video = Trim.ffmpegAttempts("/in.mp4", "/out (trim).mp4", TrimRange(30, 105), audio = false)
        assertEquals(3, video.size)
        assertEquals(listOf("-hide_banner", "-y", "-ss", "30", "-i", "/in.mp4", "-t", "75"), video[0].take(8))
        assertTrue(video[0].containsAll(listOf("libx264", "aac")))
        assertEquals("/out (trim).mp4", video[0].last())
        assertTrue(video[2].containsAll(listOf("-c", "copy")))
        val mp3 = Trim.ffmpegAttempts("/in.mp3", "/out.mp3", TrimRange(0, 10), audio = true)
        assertTrue(mp3[0].contains("libmp3lame"))
        assertTrue(mp3[0].containsAll(listOf("-map", "0:a:0")))
    }

    @Test fun namesAndSizes() {
        assertEquals("Song (trim).mp3", Trim.trimmedName("Song.mp3"))
        assertEquals("Clip [abc] (trim).mp4", Trim.trimmedName("Clip [abc].mp4"))
        assertEquals(250L, Trim.scaledBytes(1000, 200, TrimRange(0, 50)))
        assertEquals(1000L, Trim.scaledBytes(1000, 200, null))
        assertEquals(1000L, Trim.scaledBytes(1000, 0, TrimRange(0, 50)))
    }
}
