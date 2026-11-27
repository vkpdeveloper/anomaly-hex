package ly.anoma.hex.keyboard

import java.net.HttpURLConnection
import java.net.URL

/** Keyboard › Expand short links (off by default): a copied bit.ly or amzn.to link becomes the link it
 * stands for. Only the shortener is asked, over HTTPS, and only for where it redirects; the site the link
 * ends at is never contacted. */
internal object ShortLinks {
    private val HOSTS = setOf(
        "bit.ly", "bitly.com", "j.mp", "amzn.to", "amzn.eu", "amzn.asia", "amzn.in", "a.co", "t.co", "tinyurl.com",
        "goo.gl", "maps.app.goo.gl", "ow.ly", "buff.ly", "is.gd", "v.gd", "rebrand.ly", "cutt.ly", "shorturl.at",
        "t.ly", "tiny.cc", "rb.gy", "lnkd.in", "fb.me", "spoti.fi", "dub.sh", "trib.al", "dlvr.it", "ift.tt",
        "fkrt.it", "fkrt.cc", "fkrt.co", "bl.ink", "s.id", "shorturl.gg", "qr.ae",
    )
    /** A chain of shorteners is followed this far. */
    private const val MAX_HOPS = 5
    private const val TIMEOUT_MS = 4_000

    /** Whether [link] is on a link shortener. */
    fun isShort(link: String) = Links.host(link) in HOSTS

    /** Where the short [link] leads, asking each shortener in turn; null when one doesn't say. Blocks. */
    fun expand(link: String): String? {
        var current = link
        repeat(MAX_HOPS) {
            if (!isShort(current)) return current
            current = redirect(current) ?: return null
        }
        return current.takeIf { !isShort(it) }
    }

    /** The `Location` a shortener answers with, resolved against [link], over HTTPS only. */
    private fun redirect(link: String): String? {
        val url = runCatching { URL(link.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")) }.getOrNull() ?: return null
        if (url.protocol != "https") return null
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS; connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode !in 300..399) return null
            val location = connection.getHeaderField("Location") ?: return null
            URL(url, location).takeIf { it.protocol == "https" || it.protocol == "http" }?.toString()
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
