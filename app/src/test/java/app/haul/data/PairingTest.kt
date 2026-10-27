package app.haul.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    @Test fun readsPairLink() {
        val p = Pairing.fromLink("haul://pair?site=https%3A%2F%2Fhaul.example.org%2F&id=p_123&token=t%2Babc", allowLocalHttp = false)!!
        assertEquals(Pairing("https://haul.example.org", "p_123", "t+abc"), p)
        assertEquals("haul.example.org", p.host)
    }

    @Test fun rejectsIncompleteOrInsecureLinks() {
        assertNull(Pairing.fromLink("haul://pair?site=https%3A%2F%2Fa.org&id=1", false))
        assertNull(Pairing.fromLink("haul://save?site=https%3A%2F%2Fa.org&id=1&token=2", false))
        assertNull(Pairing.fromLink("haul://pair?site=http%3A%2F%2Fa.org&id=1&token=2", true))
        assertNull(Pairing.fromLink("haul://pair?site=http%3A%2F%2Flocalhost%3A8787&id=1&token=2", false))
        assertEquals("http://localhost:8787", Pairing.fromLink("haul://pair?site=http%3A%2F%2Flocalhost%3A8787&id=1&token=2", true)?.site)
        assertFalse(Pairing.siteAllowed("ftp://a.org", true))
        assertTrue(Pairing.siteAllowed("https://a.org/api/pair/inbox?id=1", false))
    }

    @Test fun storesSeveralPairings() {
        val list = listOf(Pairing("https://a.org", "1", "x"), Pairing("https://b.org", "2", "y"))
        assertEquals(list, Pairing.decode(Pairing.encode(list)))
        assertEquals(emptyList<Pairing>(), Pairing.decode(null))
        assertEquals(emptyList<Pairing>(), Pairing.decode("not json"))
    }

    @Test fun parsesInbox() {
        val items = InboxItem.parse(
            """{"ok": true, "items": [
                {"id": "i1", "url": "https://www.youtube.com/watch?v=x", "mode": "audio", "title": "Song", "sent_at": 1761562000},
                {"id": "i2", "url": "https://vimeo.com/1", "mode": "video", "title": null},
                {"id": "i3", "url": "javascript:alert(1)", "mode": "video"}
            ]}""",
        )
        assertEquals(listOf("i1", "i2"), items.map { it.id })
        assertTrue(items[0].audio)
        assertEquals(1761562000L, items[0].sentAt)
        assertFalse(items[1].audio)
        assertEquals("", items[1].title)
        assertEquals(emptyList<InboxItem>(), InboxItem.parse("""{"ok": true}"""))
    }
}
