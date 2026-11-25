package ly.anoma.hex.keyboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/** The small icon of a copied link's site, for the strip's chip. It comes from the site itself (its
 * `/favicon.ico`, else the icon its home page names), over HTTPS, never through a third party, and only
 * once the chip shows. Each icon is kept in memory while the keyboard runs and in [dir] (the app's
 * cache, which Android may clear) for [FRESH_MS], so a site is asked once a month at most. A site
 * without one isn't asked again until the keyboard restarts. */
internal class Favicons(private val dir: File) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "hex-favicons").apply { isDaemon = true } }
    private val icons = LruCache<String, Bitmap>(64)
    private val missing = HashSet<String>()

    /** Calls [show] on the main thread with [link]'s site icon, at once when it is known. */
    fun get(link: String, show: (Bitmap) -> Unit) {
        val host = runCatching { URI(link).host?.lowercase() }.getOrNull() ?: return
        icons.get(host)?.let { show(it); return }
        if (host in missing) return
        worker.execute {
            val icon = saved(host) ?: runCatching { image(URL("https://$host/favicon.ico")) ?: pageIcon(host)?.let(::image) }
                .getOrNull()?.also { save(host, it) }
            main.post { if (icon != null) { icons.put(host, icon); show(icon) } else missing += host }
        }
    }

    /** A host as a file name; null for one that isn't plain letters, digits, dots and hyphens. */
    private fun file(host: String) = if (SAFE_HOST.matches(host)) File(dir, "$host.png") else null

    private fun saved(host: String): Bitmap? {
        val file = file(host)?.takeIf { System.currentTimeMillis() - it.lastModified() < FRESH_MS } ?: return null
        return BitmapFactory.decodeFile(file.path)
    }

    /** Writes [icon] whole or not at all, then drops the oldest icons past [MAX_SAVED]. */
    private fun save(host: String, icon: Bitmap) {
        val file = file(host) ?: return
        runCatching {
            dir.mkdirs()
            val part = File(dir, "${file.name}.part")
            part.outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!part.renameTo(file)) part.delete()
            dir.listFiles { f -> f.name.endsWith(".png") }?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_SAVED)?.forEach { it.delete() }
        }
    }

    private fun image(url: URL): Bitmap? {
        val bytes = fetch(url, MAX_IMAGE) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The icon the home page's `<link rel="icon">` names, skipping SVG, which Android can't draw here. */
    private fun pageIcon(host: String): URL? {
        val base = URL("https://$host/")
        val page = fetch(base, MAX_PAGE)?.toString(Charsets.UTF_8) ?: return null
        val href = LINK_TAG.findAll(page).map { it.value }
            .filter { ICON_REL.containsMatchIn(it) }
            .mapNotNull { HREF.find(it)?.groupValues?.get(2) }
            .firstOrNull { !it.substringBefore('?').endsWith(".svg", ignoreCase = true) && !it.startsWith("data:") }
            ?: return null
        return URL(base, href.replace("&amp;", "&")).takeIf { it.protocol == "https" }
    }

    /** At most [limit] bytes of an HTTPS response, without cookies; null for anything but a 200. */
    private fun fetch(url: URL, limit: Int): ByteArray? {
        if (url.protocol != "https") return null
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS; connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
            if (connection.responseCode != 200) null else connection.inputStream.use { read(it, limit) }
        } finally {
            connection.disconnect()
        }
    }

    private fun read(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 4_000
        const val MAX_IMAGE = 256 * 1024
        const val MAX_PAGE = 128 * 1024
        /** Decoded no smaller than this, a few times the chip's 16 dp icon. */
        const val ICON_PX = 64
        const val FRESH_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_SAVED = 200
        val SAFE_HOST = Regex("[a-z0-9.-]{1,200}")
        val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        val ICON_REL = Regex("rel\\s*=\\s*[\"']?[^\"'>]*\\bicon\\b", RegexOption.IGNORE_CASE)
        val HREF = Regex("href\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
    }
}
