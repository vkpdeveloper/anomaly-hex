package ly.anoma.hex.keyboard

import java.util.Base64

/** What a clip holds: text, or an image the keyboard copied into its own files, named by its SHA-256. */
internal sealed interface ClipContent {
    data class Text(val text: String) : ClipContent
    data class Image(val file: String, val mime: String) : ClipContent
}

/** One copy in the history: [at] is when it was copied (or last copied again), in wall-clock milliseconds. */
internal data class Clip(val id: Long, val content: ClipContent, val at: Long, val pinned: Boolean)

private const val DAY_MS = 24 * 60 * 60_000L

/** Settings › Keyboard › Keep clipboard history: how long an unpinned clip stays after it was copied. [ms] null
 * keeps it until it is deleted, cleared or pushed out by newer ones. Saved by name, so the order can change. */
internal enum class ClipKeep(val ms: Long?) {
    Hour(60 * 60_000L), Day(DAY_MS), Week(7 * DAY_MS), Month(30 * DAY_MS), Quarter(90 * DAY_MS), Year(365 * DAY_MS), Forever(null);
    companion object {
        val DEFAULT = Month
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The clipboard history: what was copied, pinned clips first, newest first in each. Unpinned clips go [keep]
 * after they were copied (30 days unless Settings says otherwise); pinned clips stay until unpinned or deleted.
 * Both are saved ([write]) in the keyboard's private files, which no backup copies. One thread owns it
 * ([ClipKeeper]'s worker). */
internal class ClipHistory {
    companion object {
        const val MAX_RECENT = 200
        /** Unpinned images take disk space until they expire, so fewer of them stay. */
        const val MAX_RECENT_IMAGES = 5
        const val MAX_PINNED = 50
        /** Longer copies are not kept; the toolbar's chip still pastes them. */
        const val MAX_TEXT = 50_000
        /** The image files' names: a SHA-256 and a short extension, never a path. */
        private val FILE_NAME = Regex("[0-9a-f]{64}\\.[a-z0-9]{1,5}")
        fun validFile(name: String) = FILE_NAME.matches(name)

        /** The text clips holding every word of [query], ignoring case, in [clips]' order; a blank [query] matches all. */
        fun search(clips: List<Clip>, query: String): List<Clip> {
            val words = query.lowercase().split(' ', '\n', '\t').filter(String::isNotEmpty)
            return clips.filter { clip ->
                val text = (clip.content as? ClipContent.Text)?.text?.lowercase() ?: return@filter false
                words.all(text::contains)
            }
        }
    }
    private val clips = ArrayList<Clip>()
    private var nextId = 1L
    /** How long an unpinned clip stays, in milliseconds; null keeps it. */
    var keep: Long? = ClipKeep.DEFAULT.ms

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

    /** Pins or unpins a clip. Unpinning starts its time again, so an old pin doesn't vanish at once.
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

    /** Drops unpinned clips [keep] old, then lists the rest: pinned first, newest first in each. */
    fun list(now: Long): List<Clip> {
        expire(now)
        return clips.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.at })
    }
    fun expire(now: Long) { keep?.let { keep -> clips.removeAll { !it.pinned && now - it.at >= keep } } }
    /** When the next unpinned clip expires, or null when none will. */
    fun nextExpiry(): Long? = keep?.let { keep -> clips.filter { !it.pinned }.minOfOrNull { it.at + keep } }

    /** The image files the clips still name; any other file in the folder can go. */
    fun files(): Set<String> = clips.mapNotNull { (it.content as? ClipContent.Image)?.file }.toSet()

    /** The [pinned] clips or the others, one per line: `t at base64(text)` or `i at file mime`, tab-separated. */
    fun write(pinned: Boolean = true): String = buildString {
        for (c in clips) {
            if (c.pinned != pinned) continue
            when (val content = c.content) {
                is ClipContent.Text -> append("t\t").append(c.at).append('\t')
                    .append(Base64.getEncoder().encodeToString(content.text.toByteArray()))
                is ClipContent.Image -> append("i\t").append(c.at).append('\t').append(content.file).append('\t').append(content.mime)
            }
            append('\n')
        }
    }

    /** What [write] wrote, as [pinned] clips or not. A line it can't read, or an image whose file is gone, is skipped. */
    fun read(text: String, pinned: Boolean = true, exists: (String) -> Boolean = { true }) {
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
            if (pinned && clips.count { it.pinned } >= MAX_PINNED) break
            val clip = add(content, at) ?: continue
            if (pinned) pin(clip.id, true, at)
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
