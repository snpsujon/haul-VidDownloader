package app.haul.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test fun bytes() {
        assertEquals("—", Pretty.bytes(0))
        assertEquals("412 MB", Pretty.bytes(412_000_000))
        assertEquals("1.9 GB", Pretty.bytes(1_860_000_000))
        assertEquals("1.86 GB", Pretty.bytes(1_860_000_000, gbDecimals = 2))
        assertEquals("255/412 MB", Pretty.bytesPair(255_000_000, 412_000_000))
    }

    @Test fun durations() {
        assertEquals("41:27", Pretty.duration(2487))
        assertEquals("1:12:05", Pretty.duration(4325))
        assertEquals("6 H 12 MIN", Pretty.longDuration(6 * 3600 + 12 * 60))
    }

    @Test fun links() {
        assertEquals("https://vimeo.com/827140226", Links.extract("Watch this: https://vimeo.com/827140226."))
        assertEquals("https://youtu.be/abc", Links.extract("youtu.be/abc"))
        assertNull(Links.extract("no link here"))
        assertEquals("vimeo.com/827140226", Pretty.shortUrl("https://www.vimeo.com/827140226/"))
    }
}
