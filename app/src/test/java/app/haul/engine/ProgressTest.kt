package app.haul.engine

import app.haul.data.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    private fun t(d: Long, total: String, speed: String = "1000", eta: String = "5") = "[haul]$d|$total|NA|$speed|$eta"

    @Test fun addsUpVideoThenAudioStreams() {
        val p = ProgressParser(estimatedTotal = 1000)
        p.onLine("[download] Destination: a.f137.mp4", 0f)
        val half = p.onLine(t(400, "800"), 0f)!!
        assertEquals(0.4f, half.progress, 0.001f)
        p.onLine(t(800, "800"), 0f)
        p.onLine("[download] Destination: a.f140.m4a", 0f)
        val s = p.onLine(t(100, "200"), 0f)!!
        assertEquals(900L, s.downloaded)
        assertEquals(1000L, s.total)
        assertEquals(0.9f, s.progress, 0.001f)
        assertEquals(1000L, s.speedBps)
        assertEquals(5L, s.etaSec)
    }

    @Test fun postProcessingIsMerging() {
        val p = ProgressParser(0)
        p.onLine(t(100, "100"), 0f)
        val m = p.onLine("[Merger] Merging formats into \"a.mp4\"", 0f)!!
        assertTrue(m.processing)
        assertEquals(1f, m.progress, 0f)
    }

    @Test fun ignoresNoiseAndBadLines() {
        val p = ProgressParser(0)
        assertNull(p.onLine("[vimeo] 827140226: Downloading webpage", 0f))
        assertNull(p.onLine("[haul]NA|NA|NA|NA|NA", 0f))
        assertFalse(p.snapshot().processing)
    }

    @Test fun classifiesErrors() {
        assertEquals(ErrorKind.EXTRACTOR, Errors.classify("ERROR: [vimeo] 827140226: Unable to extract player config"))
        assertEquals(ErrorKind.NETWORK, Errors.classify("ERROR: Unable to download webpage: <urlopen error [Errno 101] Network is unreachable>"))
        assertEquals(ErrorKind.STORAGE, Errors.classify("OSError: [Errno 28] No space left on device"))
        assertEquals(ErrorKind.UNSUPPORTED, Errors.classify("ERROR: Unsupported URL: https://example.com/x"))
        assertEquals(ErrorKind.PRIVATE, Errors.classify("ERROR: [youtube] x: Private video. Sign in if you've been granted access"))
        assertEquals(ErrorKind.UNKNOWN, Errors.classify("something odd"))
    }

    @Test fun errorCards() {
        val c = Errors.card(ErrorKind.EXTRACTOR, 44)
        assertEquals("This site changed how it serves video.", c.title)
        assertTrue(c.fix.endsWith("resumes from 44%."))
        assertTrue(c.suggestUpdate)
        assertEquals(
            "ERROR: one\nERROR: two",
            Errors.technical("[x] a\nERROR: one\n[y] b\nERROR: two\n"),
        )
    }
    @Test fun transientErrorsRetry() {
        assertTrue(Errors.isTransient("ERROR: Read timed out."))
        assertTrue(Errors.isTransient("ERROR: unable to download video data: HTTP Error 503: Service Unavailable"))
        assertTrue(Errors.isTransient("ERROR: HTTP Error 429: Too Many Requests"))
        assertFalse(Errors.isTransient("ERROR: [youtube] abc: Private video. Sign in if you've been granted access"))
        assertFalse(Errors.isTransient("ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader"))
        assertFalse(Errors.isTransient("ERROR: The uploader has not made this video available in your country"))
        assertFalse(Errors.isTransient("ERROR: HTTP Error 404: Not Found"))
        assertFalse(Errors.isTransient("OSError: [Errno 28] No space left on device"))
    }

    @Test fun retryBackoff() {
        assertEquals(30_000L, Errors.retryDelayMs(0))
        assertEquals(120_000L, Errors.retryDelayMs(1))
        assertEquals(600_000L, Errors.retryDelayMs(2))
        assertEquals(600_000L, Errors.retryDelayMs(7))
    }
}
