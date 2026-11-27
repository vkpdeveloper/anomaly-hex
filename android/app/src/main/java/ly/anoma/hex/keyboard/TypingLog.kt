package ly.anoma.hex.keyboard

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Settings › Keyboard › Keep a typing log, off by default: every key, what the strip offered after it, what
 * you picked and what autocorrect changed, one JSON line per event, so days of real typing show where the
 * suggestions went wrong. Lines wait in memory and are handed to [save] when the keyboard goes away (or
 * every [BATCH] lines); never from a password or incognito field.
 */
internal class TypingLog(
    private val on: () -> Boolean,
    private val save: (List<String>) -> Unit,
    private val now: () -> LocalTime = LocalTime::now,
) {
    private val lines = ArrayList<String>()
    val recording get() = on()

    fun event(kind: String, fields: List<Pair<String, Any?>>) {
        if (!on()) return
        val line = buildString {
            append("{\"at\":").append(json(now().format(TIME))).append(",\"e\":").append(json(kind))
            for ((k, v) in fields) if (v != null) append(',').append(json(k)).append(':').append(json(v))
            append('}')
        }
        lines.add(line)
        if (lines.size >= BATCH) flush()
    }

    /** Hands what was recorded to [save] and starts again. */
    fun flush() {
        if (lines.isEmpty()) return
        val batch = ArrayList(lines)
        lines.clear()
        save(batch)
    }

    companion object {
        const val BATCH = 2_000
        /** Where the files go: Download/Hex, one per day. */
        const val FOLDER = "Download/Hex/"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun fileName(day: LocalDate) = "hex-typing-$day.txt"

        fun json(v: Any?): String = when (v) {
            null -> "null"
            is Boolean, is Int, is Long -> v.toString()
            is Collection<*> -> v.joinToString(",", "[", "]") { json(it) }
            else -> buildString {
                append('"')
                for (c in v.toString()) when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
                append('"')
            }
        }

        /** Appends [lines] to today's file in Download/Hex, which any file manager shows. Runs off the main thread. */
        fun append(resolver: ContentResolver, lines: List<String>, day: LocalDate = LocalDate.now()) {
            val name = fileName(day)
            val uri = find(resolver, name) ?: resolver.insert(DOWNLOADS, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, FOLDER)
            }) ?: return
            resolver.openOutputStream(uri, "wa")?.use { out ->
                out.write(lines.joinToString("\n", postfix = "\n").toByteArray())
            }
        }

        private val DOWNLOADS: Uri by lazy { MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) }

        // Today's file, when this install made it; another install's copy is a new file beside it.
        private fun find(resolver: ContentResolver, name: String): Uri? =
            resolver.query(DOWNLOADS, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(name, FOLDER), null)?.use { c ->
                if (c.moveToFirst()) android.content.ContentUris.withAppendedId(DOWNLOADS, c.getLong(0)) else null
            }
    }
}
