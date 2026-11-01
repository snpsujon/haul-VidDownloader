package app.haul.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeTest {
    private val now = 1_761_900_000L
    private val day = 24 * 60 * 60L

    private fun n(id: Long, min: Int = 0, max: Int = 0, age: Long = 0) = Notice(id, "N$id", "", null, min, max, now - age)

    @Test fun parsesItems() {
        val items = Notice.parse(
            """{"ok": true, "items": [
                {"id": 3, "title": "New version", "body": "Faster YouTube", "url": "https://stackforge-pro.xyz/",
                 "min_version": 100, "max_version": 0, "created_at": 1761900000},
                {"id": 4, "title": "Maintenance", "body": null, "url": null, "min_version": 0, "max_version": 0,
                 "created_at": "2025-10-31T09:20:00Z"},
                {"id": 5, "title": "Bad link", "url": "javascript:alert(1)", "created_at": 1761900000123},
                {"id": 6, "title": ""}
            ]}""",
        )
        assertEquals(listOf(3L, 4L, 5L), items.map { it.id })
        assertEquals("https://stackforge-pro.xyz/", items[0].url)
        assertEquals(100, items[0].minVersion)
        assertEquals("", items[1].body)
        assertNull(items[1].url)
        assertEquals(1761902400L, items[1].createdAt)
        assertNull(items[2].url)
        assertEquals(1761900000L, items[2].createdAt)
        assertEquals(emptyList<Notice>(), Notice.parse("""{"ok": true}"""))
    }

    @Test fun filtersByVersion() {
        assertTrue(n(1).appliesTo(100))
        assertTrue(n(1, min = 100).appliesTo(100))
        assertFalse(n(1, min = 101).appliesTo(100))
        assertTrue(n(1, max = 100).appliesTo(100))
        assertFalse(n(1, max = 99).appliesTo(100))
    }

    @Test fun showsOnlyNewOnes() {
        val items = listOf(n(5), n(7), n(6, min = 200), n(8, max = 50))
        val (show, last) = Notice.select(items, lastId = 5, versionCode = 100, nowSec = now)
        assertEquals(listOf(7L), show.map { it.id })
        assertEquals(8L, last)
    }

    @Test fun firstRunShowsNewestRecentOnly() {
        val items = listOf(n(1, age = 30 * day), n(2, age = 3 * day), n(3, age = 1 * day))
        val (show, last) = Notice.select(items, lastId = null, versionCode = 100, nowSec = now)
        assertEquals(listOf(3L), show.map { it.id })
        assertEquals(3L, last)

        val (old, lastOld) = Notice.select(listOf(n(9, age = 8 * day)), lastId = null, versionCode = 100, nowSec = now)
        assertTrue(old.isEmpty())
        assertEquals(9L, lastOld)

        val (none, lastNone) = Notice.select(emptyList(), lastId = null, versionCode = 100, nowSec = now)
        assertTrue(none.isEmpty())
        assertEquals(0L, lastNone)
    }

    @Test fun storedIdIsPerSite() {
        val site = "https://stackforge-pro.xyz"
        assertEquals(42L, Notice.lastIdFor(site, site, 42))
        // An id stored against a test server must not hide real news.
        assertNull(Notice.lastIdFor(site, "http://127.0.0.1:8787", 42))
        assertNull(Notice.lastIdFor(site, null, 42))
        assertNull(Notice.lastIdFor(site, site, null))
    }
}
