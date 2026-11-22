package ly.anoma.hex.typing

/** The paired computer's text replacements (`GET /phone/v1/replacements`), offered on the strip as
 * your own text shortcuts are ([PersonalWords.shortcut]): typing "brb" offers "be right back", and
 * only a tap puts it in. The strip reads one word at a time, so only one-word phrases become
 * shortcuts; a replacement that only changes capitals ("github" -> "GitHub") becomes your word, so
 * the word takes them as you type it. Kept on the phone as [write] stores them until the computer
 * is forgotten or another paired. */
internal class Replacements(val words: List<String>, val shortcuts: Map<String, String>) {
    companion object {
        val None = Replacements(emptyList(), emptyMap())

        /** `[phrase, output]` pairs as the computer lists them, kept as text in the preferences. */
        fun write(pairs: List<Pair<String, String>>) =
            pairs.joinToString("\n") { (phrase, output) -> escape(phrase) + "\t" + escape(output) }

        /** What [write] kept, as words and shortcuts; anything unreadable reads as nothing. */
        fun read(stored: String): Replacements {
            if (stored.isEmpty()) return None
            val words = ArrayList<String>()
            val shortcuts = LinkedHashMap<String, String>()
            for (line in stored.split('\n')) {
                val fields = line.split('\t')
                if (fields.size != 2) continue
                val phrase = unescape(fields[0]).trim()
                val output = unescape(fields[1]).trim()
                if (output.isEmpty() || !oneWord(phrase)) continue
                if (phrase.equals(output, ignoreCase = true)) words.add(output)
                else shortcuts.putIfAbsent(phrase.lowercase(), output)
            }
            return Replacements(words, shortcuts)
        }

        // As the strip reads a word in progress (TypingWord.currentToken): letters, with apostrophes inside.
        private fun oneWord(phrase: String) = phrase.length in 1..TypingWord.MAX_LENGTH &&
            phrase.first().isLetter() && phrase.last().isLetter() && phrase.all { it.isLetter() || it == '\'' }

        private fun escape(text: String) = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        private fun unescape(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    out.append(when (text[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> text[i + 1] })
                    i += 2
                } else { out.append(c); i++ }
            }
            return out.toString()
        }
    }
}
