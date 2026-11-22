package ly.anoma.hex.typing

/** Two spellings of one word, so the keyboard can offer the one you write ([LearnedWords.preferred]).
 * Hindi in Latin letters has no one spelling: nahi, nhi and nahin; acha, accha and achha; theek and
 * thik; kya and kia; bahut and bht. English has a few too: colour and color, realise and realize,
 * centre and center. The rules are narrow on purpose, since merging two words would put the wrong
 * one in: main and mein, bhi and bhai, kya and kiya, hum and hume, pata and patta stay apart. All
 * spellings are lowercase. */
internal object Spellings {
    /** A bucket every spelling of a word shares ([hindi] and [english] decide within it): the letters
     * left once vowels, h and y go, without a nasal n at the end, doubled letters once, z as s. */
    fun key(word: String): String {
        val w = if (word.length >= 2 && word.last() == 'n' && word[word.length - 2] in VOWELS) word.dropLast(1) else word
        val out = StringBuilder(w.length)
        for (c in w) {
            if (c in VOWELS || c == 'h' || c == 'y' || c == '\'') continue
            val k = if (c == 'z') 's' else c
            if (out.lastOrNull() != k) out.append(k)
        }
        return out.toString()
    }

    /** Whether [a] and [b], two Hindi words of three letters or more (the caller checks that English's
     * model has neither and neither is a name), are spellings of one. Alike once a held last vowel goes
     * ("kyaa" "kya"), and the nasal n of -un or a few words ("kyun" "kyu", "nahin" "nahi"); alike once
     * ee is i, oo is u and, inside a word, cch or chh is ch ("theek" "thik", "accha" "acha"); one
     * edit apart: i for a y after a consonant ("kya" "kia"), or a y left out between vowels ("kiya"
     * "kia"); or the shorter is the longer with one or two short vowels left out between consonants
     * ("nhi" "nahi", "bht" "bahut", "phle" "pehle"). The edits don't chain, so kya (what) and kiya
     * (did) stay apart, though kia can be either. A long aa is never shortened (kal and kaal, mar and
     * maar are other words), and an i is never left out (bina and bana both read "bna"). */
    fun hindi(a: String, b: String): Boolean {
        if (a == b || a.length < 3 || b.length < 3 || a in AMBIGUOUS || b in AMBIGUOUS) return false
        val x = ending(a); val y = ending(b)
        if (x == y) return true
        val nx = digraphs(x); val ny = digraphs(y)
        if (nx == ny) return true
        for (edit in EDITS) if (edit(nx) == ny || edit(ny) == nx) return true
        return vowelsLeftOut(x, y) || vowelsLeftOut(y, x)
    }

    /** Whether [a] and [b] are British and American spellings of one English word, five letters or
     * more: -our and -or after the third letter ("colour"), -ise and -ize ("realise", "organisation"),
     * -yse and -yze, and -tre and -ter ("centre"). */
    fun english(a: String, b: String): Boolean =
        a != b && a.length >= 5 && b.length >= 5 && a !in OTHER_WORDS && b !in OTHER_WORDS && american(a) == american(b)

    private fun american(w: String): String {
        var s = w
        val our = s.indexOf("our", 3)
        if (our > 0) s = s.substring(0, our) + "or" + s.substring(our + 3)
        s = ISE.replace(s) { it.groupValues[1] + "z" + it.groupValues[2] }
        return TRE.replace(s) { it.groupValues[1] + "er" + it.groupValues[2] }
    }

    private fun ending(w: String): String {
        var s = w
        // Not oo, which is a u of its own ("daroo" is liquor, "daro" fear).
        while (s.length > 3 && s.last() in "aeiu" && s[s.length - 2] == s.last()) s = s.dropLast(1)
        if (s.length > 3 && (s.endsWith("un") || s in NASAL)) s = s.dropLast(1)
        return s
    }
    private fun digraphs(w: String) = w.first() +
        w.drop(1).replace("chch", "ch").replace("cchh", "ch").replace("cch", "ch").replace("chh", "ch")
            .replace("ee", "i").replace("oo", "u")

    private fun consonant(c: Char?) = c != null && c !in VOWELS && c != 'y'
    // `short` is `long` with one or two vowels that sit between consonants left out: an a or u, or an e
    // or o before h ("pehle", "bohat"; elsewhere it's a word's own vowel: "lega" isn't "laga"). Never
    // the vowel before the last consonant of a word ending in a vowel with three consonants or more,
    // which makes another verb ("karana" is to have done, "karna" to do).
    private fun vowelsLeftOut(short: String, long: String): Boolean {
        if (short.length < 3 || long.length - short.length !in 1..2) return false
        val last = long.indexOfLast(::consonant)
        val guarded = long.last() in VOWELS && long.count(::consonant) >= 3
        var i = 0
        for ((j, c) in long.withIndex()) {
            if (i < short.length && c == short[i]) i++
            else if (!(c == 'a' || c == 'u' || (c == 'e' || c == 'o') && long.getOrNull(j + 1) == 'h') ||
                !consonant(long.getOrNull(j - 1)) || !consonant(long.getOrNull(j + 1)) || (guarded && j == last - 1)) return false
        }
        return i == short.length
    }

    private const val VOWELS = "aeiou"
    private val GLIDE = Regex("(?<=[ia])y(?=[aei])")
    private val AFTER_CONSONANT = Regex("(?<=[^aeiouy])y")
    private val EDITS = listOf<(String) -> String>(
        { AFTER_CONSONANT.replace(it, "i") },
        // "iya", "iye", "aye", "ayi" without the y; never "aya", or "gaya" (went) would be "gaa" (sing).
        { GLIDE.replace(it) { m -> if (it.getOrNull(m.range.first - 1) == 'a' && it.getOrNull(m.range.last + 1) == 'a') "y" else "" } },
    )
    private val ISE = Regex("^(.{3,}[iy])s(e|es|ed|ing|ation|ations)$")
    private val TRE = Regex("^(.{2,}[tb])re(s|d)?$")
    // Another word, not the other spelling of one ("timbre" isn't "timber", "analyses" may be a noun).
    private val OTHER_WORDS = setOf("timbre", "analyses")
    // Nasal endings that change nothing; elsewhere they do ("kahi" said, "kahin" somewhere).
    private val NASAL = setOf("nahin", "mein", "unhen", "inhen", "humein", "tumhein")
    // Words that are also another word the rules would read as their spelling: "hain" are (not "hai"
    // is), "bhar" fill (not "bahar" outside), "bacha" saved (not "bachcha" child), "kha" eat (not
    // "kaha" said), "nai" new (or "nahi"), "jitna" as much (not "jeetna" to win), "saree" (not "sare"
    // all).
    private val AMBIGUOUS = setOf(
        "hain", "bhar", "bahar", "bacha", "bache", "bachi", "krta", "kurta", "kha", "khaa", "kho", "khu", "bahi", "bhu",
        "mla", "thk", "phd", "evm", "nai", "jitna", "jitne", "jitni", "saree", "sookh", "pite",
    )
}
