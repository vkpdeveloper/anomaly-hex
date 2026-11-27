package ly.anoma.hex.keyboard

import android.content.Context
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import ly.anoma.hex.R

/** A part of a copy worth pasting on its own: a phone number, an email address, a link, an address, a
 * date or a flight, as Gboard's clipboard splits them out. */
internal data class ClipPiece(val kind: Kind, val text: String) {
    enum class Kind(val icon: Int) {
        Phone(R.drawable.ic_call), Email(R.drawable.ic_mail), Link(R.drawable.ic_link),
        Address(R.drawable.ic_place), Date(R.drawable.ic_event), Flight(R.drawable.ic_flight),
    }

    /** What [ClipPieces.split] found in a copy: [kind] when the whole copy is one piece (its chip takes
     * that piece's icon), else the [pieces] to offer beside it. */
    data class Split(val kind: Kind?, val pieces: List<ClipPiece>)

    /** One thing the classifier found, at [start] until [end] in the copy. */
    data class Found(val start: Int, val end: Int, val kind: Kind)
}

/** Finds the pieces in a copy with Android's own on-device text classifier, which Gboard's clipboard
 * uses too. It blocks, so it runs on the clipboard's worker; nothing leaves the phone. */
internal object ClipPieces {
    const val MAX_PIECES = 2
    private const val MAX_TEXT = 2_000

    private val ENTITIES = mapOf(
        TextClassifier.TYPE_PHONE to ClipPiece.Kind.Phone,
        TextClassifier.TYPE_EMAIL to ClipPiece.Kind.Email,
        TextClassifier.TYPE_URL to ClipPiece.Kind.Link,
        TextClassifier.TYPE_ADDRESS to ClipPiece.Kind.Address,
        TextClassifier.TYPE_DATE to ClipPiece.Kind.Date,
        TextClassifier.TYPE_DATE_TIME to ClipPiece.Kind.Date,
        TextClassifier.TYPE_FLIGHT_NUMBER to ClipPiece.Kind.Flight,
    )

    fun find(context: Context, text: String): ClipPiece.Split {
        if (text.length > MAX_TEXT) return split(text, emptyList())
        val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier
        val found = runCatching {
            val request = TextLinks.Request.Builder(text)
                .setEntityConfig(TextClassifier.EntityConfig.createWithExplicitEntityList(ENTITIES.keys.toList()))
                .build()
            classifier?.generateLinks(request)?.links.orEmpty().mapNotNull { link ->
                val type = (0 until link.entityCount).map(link::getEntity).firstOrNull { it in ENTITIES } ?: return@mapNotNull null
                ClipPiece.Found(link.start, link.end, ENTITIES.getValue(type))
            }
        }.getOrDefault(emptyList())
        return split(text, found)
    }

    /** The pieces of [text], from what the classifier [found] plus any web link or date it missed: in the
     * order they appear, each once, at most [MAX_PIECES]. Where two overlap the earlier wins, and at the same
     * start the classifier's. A piece that is the whole copy names its kind instead. */
    fun split(text: String, found: List<ClipPiece.Found>): ClipPiece.Split {
        val links = Links.find(text).mapNotNull { link ->
            val start = text.indexOf(link)
            if (start < 0) null else ClipPiece.Found(start, start + link.length, ClipPiece.Kind.Link)
        }
        val dates = Dates.find(text).map { ClipPiece.Found(it.first, it.last + 1, ClipPiece.Kind.Date) }
        val taken = ArrayList<ClipPiece.Found>()
        for (piece in (found + links + dates).sortedBy { it.start }) {
            if (piece.start < 0 || piece.end > text.length || piece.start >= piece.end) continue
            if (taken.any { piece.start < it.end && it.start < piece.end }) continue
            taken += piece
        }
        val whole = taken.singleOrNull()?.takeIf { text.substring(it.start, it.end).trim() == text.trim() }
        if (whole != null) return ClipPiece.Split(whole.kind, emptyList())
        val pieces = taken.map { ClipPiece(it.kind, text.substring(it.start, it.end).trim()) }.distinct().take(MAX_PIECES)
        return ClipPiece.Split(null, pieces)
    }
}

/** Dates the classifier leaves out, since it marks one only with a time: "tomorrow", "next Friday",
 * "25 Nov", "Nov 25th, 2026", "22/11/2026", each with its time when one follows ("tomorrow at 9am").
 * Day names count in full only, never "sat" or "sun"; a number alone or a fraction is never a date. */
internal object Dates {
    private const val DAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private const val MONTH = "january|february|march|april|may|june|july|august|september|october|november|december" +
        "|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
    private const val NUM = """(?:0?[1-9]|[12]\d|3[01])"""
    private const val NTH = """$NUM(?:st|nd|rd|th)?"""
    private const val YEAR = """(?:,?\s+\d{4})?"""
    // A dot after the month only before its day ("Dec. 3"): one after "25 Nov" ends the sentence.
    private const val DAY_MONTH = """$NTH\s+(?:of\s+)?(?:$MONTH)$YEAR"""
    private const val MONTH_DAY = """(?:$MONTH)\.?\s+$NTH$YEAR"""
    private const val PART = """(?:\s+(?:morning|afternoon|evening|night))?"""
    private const val TIME = """(?:,?\s+(?:at\s+)?\d{1,2}(?::[0-5]\d)?\s*(?:am|pm)|\s+at\s+\d{1,2}:[0-5]\d)?"""
    private val DATE = Regex(
        """(?<![\p{L}\p{N}])(?:""" +
            """(?:(?:the\s+)?day\s+after\s+)?(?:today|tonight|tomorrow|tmrw|tmr)$PART""" +
            """|(?:(?:next|this)\s+)?(?:$DAY)(?:,?\s+(?:the\s+)?(?:$DAY_MONTH|$MONTH_DAY)|$PART)""" +
            """|$DAY_MONTH|$MONTH_DAY""" +
            """|$NUM[/.-]$NUM[/.-]\d{4}|$NUM[/-]$NUM[/-]\d{2}|\d{4}-(?:0?[1-9]|1[0-2])-$NUM""" +
            """)$TIME(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )

    /** Where each date in [text] is. */
    fun find(text: String): List<IntRange> = DATE.findAll(text).map { it.range }.toList()
}
