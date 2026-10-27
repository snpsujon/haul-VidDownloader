package app.haul.data

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import app.haul.engine.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A site the user can sign in to. [domains] are the cookie domains yt-dlp needs for it. */
data class Site(val id: String, val name: String, val loginUrl: String, val domains: List<String>) {
    fun matches(url: String): Boolean {
        val host = MediaInfo.hostOf(url).lowercase()
        return domains.any { host == it || host.endsWith(".$it") }
    }

    companion object {
        private const val CUSTOM = "custom:"

        val presets = listOf(
            Site("youtube", "YouTube", "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.youtube.com%2F", listOf("youtube.com", "google.com")),
            Site("instagram", "Instagram", "https://www.instagram.com/accounts/login/", listOf("instagram.com")),
            Site("x", "X", "https://x.com/i/flow/login", listOf("x.com", "twitter.com")),
            Site("facebook", "Facebook", "https://www.facebook.com/login/", listOf("facebook.com")),
            Site("tiktok", "TikTok", "https://www.tiktok.com/login", listOf("tiktok.com")),
        )

        /** A site from an address the user typed, or null if it isn't one. */
        fun custom(address: String): Site? {
            val url = address.trim().let { if (it.contains("://")) it else "https://$it" }
            val domain = MediaInfo.hostOf(url).lowercase().removePrefix("www.").takeIf { it.contains('.') } ?: return null
            presets.firstOrNull { it.matches(url) }?.let { return it }
            return Site(CUSTOM + domain, domain, url, listOf(domain))
        }

        fun byId(id: String): Site? =
            presets.firstOrNull { it.id == id }
                ?: id.removePrefix(CUSTOM).takeIf { id.startsWith(CUSTOM) }?.let { Site(id, it, "https://$it/", listOf(it)) }
    }
}

/**
 * Sign-ins made in the Accounts screen's WebView. The cookies stay in the WebView's own store;
 * each yt-dlp run for a matching link gets a private copy in Netscape format, deleted afterwards.
 */
class Accounts(private val context: Context, private val settings: SettingsRepository) {

    private val cookies: CookieManager? get() = runCatching { CookieManager.getInstance() }.getOrNull()

    suspend fun signedIn(): List<Site> = settings.current().signedInSites.mapNotNull { Site.byId(it) }

    /** Called when the user taps Done in the sign-in screen. True if the site left cookies behind. */
    suspend fun finishSignIn(site: Site): Boolean {
        val jar = withContext(Dispatchers.Main) {
            cookies?.flush()
            read(site)
        }
        if (jar.isEmpty()) return false
        settings.setSignedIn(site.id, true)
        return true
    }

    suspend fun signOut(site: Site) {
        withContext(Dispatchers.Main) {
            val cm = cookies ?: return@withContext
            for (d in site.domains) {
                for (host in listOf(d, "www.$d")) {
                    val url = "https://$host"
                    cm.getCookie(url)?.let { parse(it) }?.keys?.forEach { name ->
                        // Expire both the domain-wide and the host-only copy.
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$d")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                    }
                }
            }
            cm.flush()
        }
        settings.setSignedIn(site.id, false)
    }

    /** A cookies.txt for [url] if the user is signed in to its site, else null. The caller deletes it. */
    suspend fun cookieFileFor(url: String, name: String): File? {
        val site = signedIn().firstOrNull { it.matches(url) } ?: return null
        val jar = withContext(Dispatchers.Main) { read(site) }
        if (jar.isEmpty()) return null
        return runCatching {
            val dir = File(context.cacheDir, "cookies").apply { mkdirs() }
            File(dir, "$name.txt").apply { writeText(netscape(jar)) }
        }.onFailure { Log.w(TAG, "could not write cookies", it) }.getOrNull()
    }

    /** domain -> (name -> value) for every domain of [site]. */
    private fun read(site: Site): Map<String, Map<String, String>> {
        val cm = cookies ?: return emptyMap()
        val out = LinkedHashMap<String, Map<String, String>>()
        for (d in site.domains) {
            val merged = LinkedHashMap<String, String>()
            for (host in listOf(d, "www.$d")) cm.getCookie("https://$host")?.let { merged.putAll(parse(it)) }
            if (merged.isNotEmpty()) out[d] = merged
        }
        return out
    }

    companion object {
        private const val TAG = "Accounts"
        private const val YEAR_SEC = 365L * 24 * 60 * 60

        /** "a=1; b=2" as the WebView returns it. */
        fun parse(header: String): Map<String, String> =
            header.split(';').mapNotNull { part ->
                val p = part.trim()
                val eq = p.indexOf('=')
                if (eq <= 0) null else p.substring(0, eq) to p.substring(eq + 1)
            }.toMap()

        /** Netscape cookies.txt, as yt-dlp's --cookies reads it. The WebView gives no expiry, so a year from now. */
        fun netscape(jar: Map<String, Map<String, String>>, nowSec: Long = System.currentTimeMillis() / 1000): String = buildString {
            appendLine("# Netscape HTTP Cookie File")
            val expiry = nowSec + YEAR_SEC
            for ((domain, values) in jar) {
                for ((name, value) in values) appendLine(".$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
            }
        }
    }
}
