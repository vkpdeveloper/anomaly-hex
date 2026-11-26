package ly.anoma.hex.typing

import java.util.concurrent.Executor

/**
 * [NextWordModel]'s words for the strip, worked out on [worker] so typing never waits for them. [words]
 * answers from what was already worked out and otherwise asks the worker, which calls [ready] once the
 * answer is in; only the newest question is worked on, older ones waiting behind it are dropped. The model
 * loads on the worker the first time it is asked.
 */
internal class NextWords(private val load: () -> NextWordModel, private val worker: Executor) {
    /** Called on the worker once an answer [words] didn't have is ready. */
    @Volatile var ready: () -> Unit = {}
    private var model: NextWordModel? = null
    @Volatile private var broken = false
    private data class Key(val context: List<String>, val prefix: String)
    private val answers = object : LinkedHashMap<Key, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Key, List<String>>?) = size > 64
    }
    @Volatile private var wanted: Key? = null

    /** The likeliest words after [context] ([context]'s words) that start with [prefix], likeliest first,
     * or null while they are being worked out; none once the model couldn't be read. Lowercase, and not
     * yet filtered for the layout. */
    fun words(context: List<String>, prefix: String): List<String>? {
        if (broken) return emptyList()
        val key = Key(context, prefix)
        synchronized(answers) { answers[key]?.let { return it } }
        if (wanted != key) {
            wanted = key
            worker.execute { answer(key) }
        }
        // A worker that runs the task at once (tests, the bench) has the answer now.
        return if (broken) emptyList() else synchronized(answers) { answers[key] }
    }

    private fun answer(key: Key) {
        if (wanted != key || broken) return
        val words = try {
            val m = model ?: load().also { model = it }
            m.predict(key.context, key.prefix, WORDS)
        } catch (_: Exception) {
            // An unreadable model leaves the strip to the word pairs, as before it shipped.
            broken = true
            return
        }
        synchronized(answers) { answers[key] = words }
        if (wanted == key) ready()
    }

    companion object {
        /** The layouts the model was trained for. */
        val LANGUAGES = setOf("en", "hien")
        /** Words worked out per question: the strip shows three, some may be filtered out for the layout. */
        const val WORDS = 6
        /** Words before the cursor the model reads, at most. */
        private const val CONTEXT_WORDS = 40
        private val LINK = Regex("""https?://\S+|www\.\S+|@\w+|#\w+""")
        private val TOKEN = Regex("""[a-z0-9]+(?:'[a-z]+)?|[.,?!]""")

        /** The text before the cursor as the model was trained to read it (benchmark/typing/neural/text.py):
         * lowercase words and . , ? ! marks, without links, handles and tags. */
        fun context(before: String): List<String> {
            val text = LINK.replace(before.lowercase().replace('’', '\''), " ")
            val words = TOKEN.findAll(text).map { it.value }.toList()
            // The first word kept moves on 20 at a time, so the model's cache of the words before stays
            // good while the next ones are typed.
            val over = words.size - CONTEXT_WORDS
            return if (over <= 0) words else words.drop((over + 19) / 20 * 20)
        }
    }
}
