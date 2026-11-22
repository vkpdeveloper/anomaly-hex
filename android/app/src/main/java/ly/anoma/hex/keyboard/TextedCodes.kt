package ly.anoma.hex.keyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import ly.anoma.hex.typing.TypingPreferences

/** The one-time code in a text message, such as "482913 is your verification code". Pure, so it is
 * tested off the device. */
internal object OneTimeCode {
    /** Words that say a message carries a code. Without one, a number is only a number. */
    private val KEYWORD = Regex(
        """\b(code|otp|one[- ]?time|verification|verify|passcode|password|pin|log ?in|sign ?in|authenticat\w*|security|confirm\w*)\b|कोड|ओटीपी""",
        RegexOption.IGNORE_CASE,
    )
    /** Four to eight digits, or two groups of three or four split by a space or dash ("482 913"), not
     * touching other letters, digits or number marks: an account's "XX1234", an amount's "5,000.00", a
     * time, a date, a phone number's groups or a percentage aren't codes. */
    private val CANDIDATE = Regex(
        """(?<![\p{L}\p{N}₹$€£*#.,/:+])(?<!\d[- ])(\d{3,4}[- ]\d{3,4}|\d{4,8})(?![\p{L}\p{N}%])(?![.,/:]\d)(?![- ]\d)""",
    )
    /** A currency just before a number makes it an amount: "Rs 5000", "INR 1200". */
    private val CURRENCY = Regex("""(rs\.?|inr|usd|eur|gbp|aed)\s*$""", RegexOption.IGNORE_CASE)

    /** The code nearest the words that announce it, digits only; null when [message] has none. */
    fun find(message: String): String? {
        val keywords = KEYWORD.findAll(message).map { it.range }.toList()
        if (keywords.isEmpty()) return null
        return CANDIDATE.findAll(message)
            .filterNot { CURRENCY.containsMatchIn(message.substring(maxOf(0, it.range.first - 5), it.range.first)) }
            .minByOrNull { found -> keywords.minOf { distance(found.range, it) } }
            ?.value?.filter(Char::isDigit)
    }

    private fun distance(a: IntRange, b: IntRange) = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }
}

/** Keyboard › Codes from texts (off by default): the newest one-time code a text message brought in
 * the last [FRESH_MS], for the strip's chip, as Gboard offers one. Only with the SMS permission, and only
 * while the keyboard runs: the inbox is read when the keyboard comes up and when a text arrives, only
 * messages from the last [FRESH_MS], and only their codes are kept, in memory. A code goes once it is
 * used, once it is too old, or when a newer copy takes the chip. It never joins the clipboard history. */
internal class TextedCodes(private val context: Context, private val changed: () -> Unit) {
    companion object {
        const val FRESH_MS = ChipOffer.COPY_FRESH_MS
        /** A text arriving writes the inbox a few times; it is read once. */
        private const val QUERY_DELAY_MS = 500L
        /** Texts read at most, newest first. */
        private const val QUERY_LIMIT = 10
        const val PERMISSION = Manifest.permission.READ_SMS
        fun allowed(context: Context) = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        fun on(context: Context) = allowed(context) && TypingPreferences(context).textedCodes
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { Thread(it, "Hex codes") }, ThreadPoolExecutor.DiscardPolicy())
    /** The texts whose codes were used: each is offered once. */
    private val used = HashSet<Long>()
    private var found: List<ChipOffer.Code> = emptyList()
    private var observing = false

    /** The newest code not yet used, while it is fresh. */
    val latest: ChipOffer.Code?
        get() = found.firstOrNull { it.message !in used }
    /** Whether [text] is a code a text brought lately: a copy of it is offered as that code, not kept. */
    fun isCode(text: String) = found.any { it.code == text.filter(Char::isDigit) && text.trim().length <= 9 }

    private val query = Runnable { read() }
    private val inbox = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(query); main.postDelayed(query, QUERY_DELAY_MS) }
    }

    /** The keyboard started or came up, or the setting or permission may have changed. */
    fun sync() {
        val on = on(context)
        if (on != observing) {
            if (on) context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, inbox)
            else context.contentResolver.unregisterContentObserver(inbox)
            observing = on
        }
        if (on) read() else if (found.isNotEmpty()) { found = emptyList(); changed() }
    }

    /** [code] went in, from its text or a copy of it: no text with that code is offered again. */
    fun used(code: ChipOffer.Code) { found.filter { it.code == code.code }.forEach { used += it.message }; changed() }

    fun close() {
        if (observing) context.contentResolver.unregisterContentObserver(inbox)
        observing = false
        main.removeCallbacks(query)
        worker.shutdown()
    }

    private fun read() = worker.execute {
        val since = System.currentTimeMillis() - FRESH_MS
        val columns = arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.BODY)
        val codes = runCatching {
            context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, columns, "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()), "${Telephony.Sms.DATE} DESC")?.use { c ->
                buildList {
                    while (size < QUERY_LIMIT && c.moveToNext()) {
                        val code = OneTimeCode.find(c.getString(2).orEmpty()) ?: continue
                        add(ChipOffer.Code(code, c.getLong(1), c.getLong(0)))
                    }
                }
            }
        }.getOrNull().orEmpty()
        main.post { if (observing && codes != found) { found = codes; changed() } }
    }
}
