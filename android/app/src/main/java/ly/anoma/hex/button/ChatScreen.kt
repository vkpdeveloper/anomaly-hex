package ly.anoma.hex.button

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import ly.anoma.hex.relay.HexClient.ChatLine

/** The chat on screen, for the keyboard's Reply: read through the floating button's accessibility service
 * only when Reply is tapped, never kept. A message on the right is yours, one on the left theirs. */
internal object ChatScreen {
    private const val LINES = 15
    private const val MAX_NODES = 1500

    /** The floating button's service while Android runs it. */
    @Volatile internal var service: AccessibilityService? = null

    val available get() = service != null

    /** A piece of text on screen and where it sits. */
    data class Piece(val text: String, val left: Int, val right: Int, val top: Int, val bottom: Int)

    /** The last lines of the chat in the app in front, oldest first; null without the service. */
    fun read(): List<ChatLine>? {
        val service = service ?: return null
        val window = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val root = window?.root ?: service.rootInActiveWindow ?: return emptyList()
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val pieces = ArrayList<Piece>()
        // The field being typed in, and everything below it, isn't the chat.
        var fieldTop = screen.bottom
        val pending = ArrayDeque(listOf(root))
        var visited = 0
        val bounds = Rect()
        while (visited < MAX_NODES) {
            val node = pending.removeFirstOrNull() ?: break
            visited++
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(bounds)
            if (node.isEditable) { fieldTop = minOf(fieldTop, bounds.top); continue }
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) pieces += Piece(text, bounds.left, bounds.right, bounds.top, bounds.bottom)
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::addLast)
        }
        return lines(pieces, screen.left, screen.right, screen.top + (screen.height() * 0.1f).toInt(), fieldTop)
    }

    private const val MONTH = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?"
    private val TIME = Regex("""^(\d{1,2}[:.]\d{2}(\s?[ap]\.?m\.?)?|today|yesterday|\d{1,2}\s$MONTH(\s\d{4})?|$MONTH\s\d{1,2}(,\s\d{4})?)$""",
        RegexOption.IGNORE_CASE)

    /** The chat's lines from [pieces] between [top] (below the app's title bar) and [bottom] (the field),
     * in reading order: times, dates and one-character labels left out, and a message whose text sits
     * toward the right counted as yours. */
    fun lines(pieces: List<Piece>, left: Int, right: Int, top: Int, bottom: Int): List<ChatLine> {
        val width = (right - left).coerceAtLeast(1)
        return pieces.asSequence()
            .filter { it.top >= top && it.bottom <= bottom && it.text.length > 1 && !TIME.matches(it.text) }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .map { ChatLine(mine = it.left - left > width * 0.25f && it.right - left > width * 0.6f, text = it.text) }
            .toList()
            .takeLast(LINES)
    }
}
