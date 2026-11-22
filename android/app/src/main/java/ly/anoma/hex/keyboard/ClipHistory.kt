package ly.anoma.hex.keyboard

import java.util.Base64

/** What a clip holds: text, or an image the keyboard copied into its own files, named by its SHA-256. */
internal sealed interface ClipContent {
    data class Text(val text: String) : ClipContent
    data class Image(val file: String, val mime: String) : ClipContent
}

/** One copy in the history: [at] is when it was copied (or last copied again), in wall-clock milliseconds. */
internal data class Clip(val id: Long, val content: ClipContent, val at: Long, val pinned: Boolean)

/** The clipboard history: what was copied, pinned clips first, newest first in each. Unpinned clips go
 * [KEEP_MS] after they were copied, as Gboard's do: the clipboard carries one-time codes, addresses and
 * things copied in passing, and an hour covers copying here to paste there without the keyboard keeping
 * a record. Pinned clips stay until unpinned or deleted, and they alone are saved ([write]). One thread
 * owns it ([ClipKeeper]'s worker). */
internal class ClipHistory {
    companion object {
        const val KEEP_MS = 60 * 60_000L
        const val MAX_RECENT = 20
        /** Unpinned images take disk space until they expire, so fewer of them stay. */
        const val MAX_RECENT_IMAGES = 5
        const val MAX_PINNED = 50
        /** Longer copies are not kept; the toolbar's chip still pastes them. */
        const val MAX_TEXT = 50_000
        /** The image files' names: a SHA-256 and a short extension, never a path. */
        private val FILE_NAME = Regex("[0-9a-f]{64}\\.[a-z0-9]{1,5}")
        fun validFile(name: String) = FILE_NAME.matches(name)
    }
    private val clips = ArrayList<Clip>()
    private var nextId = 1L

    /** A copy at [at]. The same text or image again moves its clip to the top, still pinned if it was.
     * Returns the clip, or null when it isn't kept: a [secret] (marked sensitive, as password managers
     * mark theirs, or copied in a password field), blank, too long, or a file name that isn't one. */
    fun add(content: ClipContent, at: Long, secret: Boolean = false): Clip? {
        if (secret) return null
        when (content) {
            is ClipContent.Text -> if (content.text.isBlank() || content.text.length > MAX_TEXT) return null
            is ClipContent.Image -> if (!validFile(content.file)) return null
        }
        val old = clips.firstOrNull { same(it.content, content) }
        if (old != null) clips.remove(old)
        val clip = Clip(old?.id ?: nextId++, content, at, old?.pinned == true)
        clips.add(clip)
        trim()
        return clip
    }

    /** Pins or unpins a clip. Unpinning starts its hour again, so an old pin doesn't vanish at once.
     * False when there is no such clip, or [MAX_PINNED] are already pinned. */
    fun pin(id: Long, pinned: Boolean, now: Long): Boolean {
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0) return false
        if (clips[i].pinned == pinned) return true
        if (pinned && clips.count { it.pinned } >= MAX_PINNED) return false
        clips[i] = clips[i].copy(pinned = pinned, at = if (pinned) clips[i].at else now)
        return true
    }

    fun delete(id: Long): Boolean = clips.removeAll { it.id == id }

    /** Clears what isn't pinned. */
    fun clearRecent() { clips.removeAll { !it.pinned } }

    /** Drops unpinned clips [KEEP_MS] old, then lists the rest: pinned first, newest first in each. */
    fun list(now: Long): List<Clip> {
        expire(now)
        return clips.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.at })
    }
    fun expire(now: Long) { clips.removeAll { !it.pinned && now - it.at >= KEEP_MS } }
    /** When the next unpinned clip expires, or null when none will. */
    fun nextExpiry(): Long? = clips.filter { !it.pinned }.minOfOrNull { it.at + KEEP_MS }

    /** The image files the clips still name; any other file in the folder can go. */
    fun files(): Set<String> = clips.mapNotNull { (it.content as? ClipContent.Image)?.file }.toSet()

    /** The pinned clips, one per line: `t at base64(text)` or `i at file mime`, tab-separated. */
    fun write(): String = buildString {
        for (c in clips) {
            if (!c.pinned) continue
            when (val content = c.content) {
                is ClipContent.Text -> append("t\t").append(c.at).append('\t')
                    .append(Base64.getEncoder().encodeToString(content.text.toByteArray()))
                is ClipContent.Image -> append("i\t").append(c.at).append('\t').append(content.file).append('\t').append(content.mime)
            }
            append('\n')
        }
    }

    /** What [write] wrote, as pinned clips. A line it can't read, or an image whose file is gone, is skipped. */
    fun read(text: String, exists: (String) -> Boolean = { true }) {
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            val at = f.getOrNull(1)?.toLongOrNull() ?: continue
            val content = when {
                f.size == 3 && f[0] == "t" ->
                    runCatching { ClipContent.Text(String(Base64.getDecoder().decode(f[2]))) }.getOrNull()
                f.size == 4 && f[0] == "i" && validFile(f[2]) && f[3].startsWith("image/") && exists(f[2]) ->
                    ClipContent.Image(f[2], f[3])
                else -> null
            } ?: continue
            if (clips.count { it.pinned } >= MAX_PINNED) break
            val clip = add(content, at) ?: continue
            pin(clip.id, true, at)
        }
    }

    private fun same(a: ClipContent, b: ClipContent) = when (a) {
        is ClipContent.Text -> b is ClipContent.Text && a.text == b.text
        is ClipContent.Image -> b is ClipContent.Image && a.file == b.file
    }
    // The oldest unpinned clips go once there are too many, images first by their own count.
    private fun trim() {
        val recent = clips.filter { !it.pinned }.sortedByDescending { it.at }
        val images = recent.filter { it.content is ClipContent.Image }
        val drop = recent.drop(MAX_RECENT).toSet() + images.drop(MAX_RECENT_IMAGES)
        if (drop.isNotEmpty()) clips.removeAll(drop)
    }
}
