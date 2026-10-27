package app.haul.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** A browser this phone is paired with: links sent from it arrive through [site]'s inbox. */
data class Pairing(val site: String, val id: String, val token: String) {
    val host: String get() = runCatching { URI(site).host }.getOrNull() ?: site

    companion object {
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        /**
         * Reads haul://pair?site=…&id=…&token=…. Null unless every part is there and the site is https,
         * or plain http on this machine when [allowLocalHttp] (debug builds).
         */
        fun fromLink(link: String, allowLocalHttp: Boolean): Pairing? {
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (uri.scheme != "haul" || uri.host != "pair") return null
            val q = query(uri.rawQuery ?: return null)
            val site = q["site"]?.trim()?.trimEnd('/')?.takeIf { siteAllowed(it, allowLocalHttp) } ?: return null
            val id = q["id"]?.takeIf { it.isNotBlank() } ?: return null
            val token = q["token"]?.takeIf { it.isNotBlank() } ?: return null
            return Pairing(site, id, token)
        }

        fun siteAllowed(site: String, allowLocalHttp: Boolean): Boolean {
            val uri = runCatching { URI(site) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return when (uri.scheme?.lowercase()) {
                "https" -> true
                "http" -> allowLocalHttp && host in LOCAL_HOSTS
                else -> false
            }
        }

        private fun query(raw: String): Map<String, String> =
            raw.split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }.toMap()

        fun encode(list: List<Pairing>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("site", it.site).put("id", it.id).put("token", it.token)) }
        }.toString()

        fun decode(json: String?): List<Pairing> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Pairing(o.optString("site"), o.optString("id"), o.optString("token"))
                    .takeIf { it.site.isNotBlank() && it.id.isNotBlank() && it.token.isNotBlank() }
            }
        }
    }
}

/** One link sent from a paired browser. */
data class InboxItem(val id: String, val url: String, val audio: Boolean, val title: String, val sentAt: Long) {
    companion object {
        /** {ok, items:[{id, url, mode: "video"|"audio", title, sent_at}]}. Items without a url are skipped. */
        fun parse(json: String): List<InboxItem> {
            val o = JSONObject(json)
            val items = o.optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                val url = it.optString("url").trim().takeIf { u -> u.startsWith("http") } ?: return@mapNotNull null
                InboxItem(
                    id = it.optString("id"),
                    url = url,
                    audio = it.optString("mode") == "audio",
                    title = if (it.isNull("title")) "" else it.optString("title").trim(),
                    sentAt = it.optLong("sent_at", 0L),
                )
            }
        }
    }
}
