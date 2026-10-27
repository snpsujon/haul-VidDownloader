package app.haul.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {
    @Test fun parsesWebViewCookieHeader() {
        val c = Accounts.parse("SID=abc; __Secure-3PSID=x=y=z;  PREF=f6=40000000 ; bad")
        assertEquals(mapOf("SID" to "abc", "__Secure-3PSID" to "x=y=z", "PREF" to "f6=40000000"), c)
    }

    @Test fun writesNetscapeCookies() {
        val text = Accounts.netscape(mapOf("youtube.com" to mapOf("SID" to "abc")), nowSec = 1000)
        val lines = text.trim().lines()
        assertEquals("# Netscape HTTP Cookie File", lines[0])
        assertEquals(".youtube.com\tTRUE\t/\tTRUE\t${1000 + 365L * 24 * 60 * 60}\tSID\tabc", lines[1])
    }

    @Test fun sitesMatchTheirDomains() {
        val yt = Site.byId("youtube")!!
        assertTrue(yt.matches("https://m.youtube.com/watch?v=1"))
        assertTrue(yt.matches("https://www.youtube.com/shorts/1"))
        assertFalse(yt.matches("https://notyoutube.com/x"))
        assertEquals("x", Site.custom("twitter.com/someone")?.id)

        val vimeo = Site.custom("www.vimeo.com/login")!!
        assertEquals("custom:vimeo.com", vimeo.id)
        assertEquals("https://www.vimeo.com/login", vimeo.loginUrl)
        assertEquals(listOf("vimeo.com"), Site.byId(vimeo.id)?.domains)
        assertNull(Site.custom("not a site"))
    }
}
