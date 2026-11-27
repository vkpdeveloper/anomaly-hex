package ly.anoma.hex.fix

/** tinyfix's word rules (`rules.ts`): contractions, British spellings, newer words, and the words the
 * engine must leave alone. Text here follows JavaScript's rules where they differ from Java's, such as
 * what counts as a space, so the port decides as the package does. */
internal object Rules {
    private val CONTRACTIONS = mapOf(
        "arent" to "aren't", "cant" to "can't", "couldnt" to "couldn't", "didnt" to "didn't", "doesnt" to "doesn't",
        "dont" to "don't", "hadnt" to "hadn't", "hasnt" to "hasn't", "havent" to "haven't", "im" to "I'm",
        "isnt" to "isn't", "ive" to "I've", "shouldnt" to "shouldn't", "thats" to "that's", "theres" to "there's",
        "theyre" to "they're", "theyve" to "they've", "wasnt" to "wasn't", "werent" to "weren't", "weve" to "we've",
        "whats" to "what's", "wont" to "won't", "wouldnt" to "wouldn't", "youre" to "you're", "youve" to "you've",
    )

    private val BRITISH_OUR_ENDING = Regex("(.{2,})our(s|ed|ing|ite|ites|ful|able|ably|less|hood|hoods|ly|ist|ists|er|ers)?$")
    private val BRITISH_ISE_ENDING = Regex("(is|ys)(e|es|ed|ing|er|ers|ation|ations|able)$")
    private val BRITISH_DOUBLED_L = Regex("^(un|re|mis|pre)?(travel|cancel|model|label|signal|fuel|level|total|marvel|" +
        "counsel|jewel|tunnel|channel|shovel|dial|equal|quarrel|rival|panel|funnel|pencil|spiral|trial)l(ed|ing|er|ers|or|ors)$")
    private const val MIN_LENGTH_FOR_ISE_RULE = 7
    private val BRITISH_DOUBLED_L_ENDING = Regex("ll(ed|ing|er|ers|or|ors|ist|ists|ous)$")
    private val BRITISH_RE_ENDING = Regex("([^aeiou])re(s|d)?$")
    private const val MIN_LENGTH_FOR_RE_RULE = 5
    private val BRITISH_DIGRAPH = Regex("(ae|oe)")
    private const val MIN_LENGTH_FOR_DIGRAPH_RULE = 7

    private fun withPlurals(words: String) = words.split(" ").flatMap { listOf(it, it + "s") }.toHashSet()

    private val BRITISH_WORDS = withPlurals(
        "theatre centre centred metre litre fibre calibre sombre spectre defence offence licence pretence catalogue analogue programme judgement " +
            "ageing aluminium fulfil enrol skilful wilful jewellery tyre grey mould moulding plough sceptical sceptic cheque kerb draught manoeuvre " +
            "paediatric anaesthetic foetus oestrogen moustache pyjamas storey acknowledgement instalment distil instil appal " +
            "whisky enquiry amongst whilst")

    /** Everyday words newer than the word list, which would otherwise be "corrected" (emoji to meiji). */
    private val NEWER_WORDS = withPlurals(
        "https emoji todo selfie hashtag livestream chatbot vlog vlogger influencer fintech covid podcaster blogger unfollow " +
            "unfriend retweet screenshare webinar onboarding offboarding skincare smartwatch earbud airpod wifi bluetooth " +
            "async runtime frontend backend dataset username login logout signup checkout touchscreen spreadsheet " +
            "workflow timeline toolbar dropdown popup tooltip codebase repo config dev devops cybersecurity crypto " +
            "kombucha matcha ramen udon pho bao boba poke quinoa kale hummus falafel tahini sriracha kimchi gochujang " +
            "tofu tempeh seitan acai chia granola brunch latte cappuccino espresso macchiato americano frappe smoothie " +
            "barista crema robusta arabica croissant focaccia ciabatta brioche sourdough gelato prosecco charcuterie " +
            "hygge wellness mindfulness hangry yeet meme binge rewatch preorder crowdfund carpool rideshare " +
            "staycation glamping bae vibe vibes ghosting doomscroll doomscrolling cringe adulting")

    /** JavaScript's `\s`: Java's leaves out the Unicode spaces. */
    fun isSpace(c: Char) = c in "\t\n\u000B\u000C\r       　﻿" || c in ' '..' '

    fun isNewerWord(lower: String) = lower in NEWER_WORDS

    /** JavaScript's `String.replace` with a pattern and no g flag: the first match only. */
    private fun replaceFirst(text: String, regex: Regex, replacement: (MatchResult) -> String): String {
        val match = regex.find(text) ?: return text
        return text.substring(0, match.range.first) + replacement(match) + text.substring(match.range.last + 1)
    }

    /** British spellings, so "colour" isn't taken for a misspelling of "color". */
    fun isBritishSpelling(lower: String, isKnown: (String) -> Boolean): Boolean {
        if (lower in BRITISH_WORDS) return true
        if (BRITISH_DOUBLED_L.containsMatchIn(lower)) return true
        val withOr = replaceFirst(lower, BRITISH_OUR_ENDING) { it.groupValues[1] + "or" + it.groupValues[2] }
        if (withOr != lower && isKnown(withOr)) return true
        val singleL = replaceFirst(lower, BRITISH_DOUBLED_L_ENDING) { "l" + it.groupValues[1] }
        if (singleL != lower && isKnown(singleL)) return true
        val withEr = if (lower.length < MIN_LENGTH_FOR_RE_RULE) lower else replaceFirst(lower, BRITISH_RE_ENDING) {
            val ending = it.groupValues[2]
            it.groupValues[1] + "er" + if (ending == "d") "ed" else ending
        }
        if (withEr != lower && isKnown(withEr)) return true
        if (lower.length >= MIN_LENGTH_FOR_DIGRAPH_RULE && BRITISH_DIGRAPH.containsMatchIn(lower) &&
            isKnown(replaceFirst(lower, BRITISH_DIGRAPH) { "e" })) return true
        if (lower.length < MIN_LENGTH_FOR_ISE_RULE) return false
        val withZ = replaceFirst(lower, BRITISH_ISE_ENDING) { (if (it.groupValues[1] == "is") "iz" else "yz") + it.groupValues[2] }
        return withZ != lower && isKnown(withZ)
    }

    /** `/(^|[.!?:\n]\s*|^\s*[-*•]\s+)$/`: the text before ends a sentence, a line, or opens a list item. */
    fun isSentenceStart(textBefore: String): Boolean {
        if (textBefore.isEmpty()) return true
        val trimmed = textBefore.trimEnd(::isSpace)
        val trailing = textBefore.substring(trimmed.length)
        if ('\n' in trailing) return true
        val last = trimmed.lastOrNull() ?: return false
        if (last in ".!?:") return true
        return trailing.isNotEmpty() && last in "-*•" && trimmed.dropLast(1).all(::isSpace)
    }

    private fun capitalized(word: String) = word[0] != word[0].lowercaseChar()

    /** The typed casing on a replacement, so "Teh" becomes "The". */
    fun matchCase(typed: String, replacement: String) =
        if (capitalized(typed)) replacement[0].uppercaseChar() + replacement.substring(1) else replacement

    fun applyFixedRule(word: String): String? {
        if (word == "i") return "I"
        val contraction = CONTRACTIONS[word.lowercase()] ?: return null
        return if (contraction.startsWith("I")) contraction else matchCase(word, contraction)
    }

    private fun asciiLetters(word: String) = word.isNotEmpty() && word.all { it in 'a'..'z' || it in 'A'..'Z' }
    private val NOT_A_WORD_BEFORE = Regex("[/@\\\\_#]|\\.[A-Za-z0-9_]")

    /** Names, acronyms, identifiers and anything not plainly a word are left alone. */
    fun isCheckable(word: String, textBefore: String): Boolean {
        if (word.length < 3 || !asciiLetters(word)) return false
        if (word == word.uppercase()) return false
        if (word.substring(1).any { it in 'A'..'Z' }) return false
        if (capitalized(word) && !isSentenceStart(textBefore)) return false
        val trimmed = textBefore.trimEnd(::isSpace)
        val previousToken = trimmed.substring(trimmed.indexOfLast(::isSpace) + 1)
        return !NOT_A_WORD_BEFORE.containsMatchIn(previousToken)
    }
}
