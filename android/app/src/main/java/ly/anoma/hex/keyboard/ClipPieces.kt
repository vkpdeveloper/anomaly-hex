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

    /** The pieces of [text], from what the classifier [found] plus any web link it missed: in the order
     * they appear, each once, at most [MAX_PIECES]. A piece that is the whole copy names its kind instead. */
    fun split(text: String, found: List<ClipPiece.Found>): ClipPiece.Split {
        val links = Links.find(text).mapNotNull { link ->
            val start = text.indexOf(link)
            if (start < 0) null else ClipPiece.Found(start, start + link.length, ClipPiece.Kind.Link)
        }
        val taken = ArrayList<ClipPiece.Found>()
        for (piece in (found + links).sortedBy { it.start }) {
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
