package ly.anoma.hex.typing

/** What a space or closing mark does to the text before the cursor: [original], which that text
 * ends with, becomes [replacement]; the space or mark goes in after it. [original] may hold the
 * word before too ("well se" ... "well see" -> "we'll see"). */
internal data class Correction(val original: String, val replacement: String)

/** One slot of the suggestion strip. [Kind.Typed] keeps what was typed as typed; [Kind.Fix] is what
 * space will put in its place (shown highlighted); [Kind.Word] a completion or another reading;
 * [Kind.Next] a word that may follow, offered after a space; [Kind.Phrase] several words you write together,
 * which a tap puts in whole ([LearnedPhrases]); [Kind.Emoji] an emoji for the word ([EmojiWords]). */
internal data class Suggestion(val text: String, val kind: Kind) {
    enum class Kind { Typed, Fix, Word, Next, Phrase, Emoji }
}

/** The current word, and the one a space just finished while the next is typed. Both the keyboard
 * and replay feed the real tap at each letter. */
internal class TypingWord(val decoder: SpatialDecoder, val spatial: Boolean) {
    private val model = decoder.model
    var token = ""
        private set
    private var previous = -1
    private var literalOnly = false
    // A word put back whole ([restored]): it stands as it is until a letter is typed onto it.
    private var restoredWord = false
    // Put back by undoing its autocorrection ([restored] with undone), and then edited by hand once letters
    // go onto it: space then fixes only a slip in what you typed ([holdsBack]).
    private var undone = false
    private var handEdited = false
    private val taps = ArrayList<SpatialDecoder.Tap>()
    // Where each of the word's letters was tapped, in the grid they were scored on, for [TouchOffsets].
    private val touches = ArrayList<KeyPoint>()
    private var touchGrid: KeyGrid? = null
    /** The taps of the word [reset] just ended: its letters as typed, where each was tapped and the grid. */
    class Touched(val typed: String, val points: List<KeyPoint>, val grid: KeyGrid)
    private var touched: Touched? = null
    /** The taps of the last word that ended, once; null when its letters weren't all tapped. */
    fun takeTouched(): Touched? = touched.also { touched = null }
    private var ended: String? = null
    /** The letters of the last word that ended as the keys typed them (lowercase), once; null for a word put in whole. */
    fun takeTyped(): String? = ended.also { ended = null }
    private var decision: SpatialDecoder.Decision? = null
    // The text before the word, the word before it (lowercase, null after punctuation) and
    // whether the word starts a sentence.
    private var before = ""
    private var previousText: String? = null
    // The word before that, when only a space separates the two (lowercase), for your own trigrams.
    private var beforePreviousText: String? = null
    private var sentenceStart = true
    /** The word a space finished, as it reads and as typed, with its taps and its space tap, while the
     * next word may still revise or join it; [anchor] is the text before the cursor right after its space. */
    private class Finished(val text: String, val typed: String, val taps: List<SpatialDecoder.Tap>?,
                           val space: SpatialDecoder.Tap?, val reach: Double, val anchor: String)
    private var pending: Finished? = null
    private var finished: Finished? = null
    var suggestSwearWords = false
        set(value) {
            if (value != field) { field = value; decision = null }
        }
    /** Settings › Keyboard › Auto-capitalisation (iOS AutocorrectEngine.addsCapitals). Off, nothing adds a
     * capital you didn't type: "i" stays "i", "monday" "monday", German nouns as typed, the strip and its
     * predictions lowercase; your own words and shortcuts keep theirs. */
    var addsCapitals = true
    /** The phrases you write ([LearnedPhrases]), offered by [phrase]; none until the keyboard sets them. */
    var phrases: LearnedPhrases? = null
    /** The next-word model ([NextWords]), for English and Hinglish; none, the word pairs and lists alone. */
    var next: NextWords? = null
    // The model's words after `before` that start with `prefix` and this layout knows and may suggest after
    // `previous`; while they are worked out, those in for a shorter prefix ([NextWords.words]), which
    // [NextWords.ready] then follows.
    // A letter alone only when it is a word: the model reads "e.g." and "Dr." as words and guesses letters after them ("G").
    private fun modelWords(before: String, prefix: String, previous: Int) =
        next?.words(NextWords.context(before), prefix)?.filter {
            (it.length > 1 || it in oneLetterWords) && model.index(it) >= 0 && !model.neverSuggest.blocks(it, suggestSwearWords, previous)
        }.orEmpty()
    /** Starts a word after `before`. The word a space just finished stays revisable only when
     * `before` is still the text it left; an empty `before` keeps it for the next call. */
    fun reset(before: String = "") {
        val grid = touchGrid
        touched = if (grid != null && token.isNotEmpty() && !literalOnly && touches.size == token.length) Touched(token, ArrayList(touches), grid) else null
        ended = token.takeIf { it.isNotEmpty() && !literalOnly }?.lowercase()
        token = ""; taps.clear(); touches.clear(); decision = null; literalOnly = false; restoredWord = false
        undone = false; handEdited = false
        this.before = before
        previousText = previousWord(before).takeIf { it.isNotEmpty() }
        previous = model.context(previousText.orEmpty())
        beforePreviousText = LearnedWords.wordBeforeGap(before)?.let { LearnedWords.wordBeforeGap(it.second) }?.first?.lowercase()
        sentenceStart = startsSentence(before)
        finished = null
        if (before.isEmpty()) return
        finished = pending?.takeIf { sameText(before, it.anchor) }
        pending = null
    }
    /** A word put back whole, by delete reaching it or undoing its autocorrection ([undone]): with no taps, space
     * keeps it as it stands. A letter typed onto it makes it a word being typed again ([retap]). */
    fun restored(text: String, before: String, undone: Boolean = false) {
        reset(before); explicit(text); restoredWord = true; this.undone = undone
    }
    /** A word already in the text, read for the strip: unlike [restored] it may be fixed, by its letters alone,
     * read as each key tapped at its centre, so a slip or swapped letters still find the word ("wrods" ->
     * "words"). A letter off the QWERTY keys (ø) leaves it untapped: fixed by the tables alone. */
    fun reread(text: String, before: String) {
        reset(before); token = text
        if (!spatial || text.length > MAX_LENGTH) return
        val centres = text.map { QWERTY.centres[it.lowercaseChar()] ?: QWERTY.centres[base(it)] ?: return }
        for (p in centres) taps.add(decoder.score(QWERTY, p.x, p.y))
    }
    /** Up to three words for the strip between words (iOS #2125): at a sentence's start three common
     * first words, after a comma or other mid-sentence mark words that start a clause, after a word
     * the likeliest next words from the word pairs with the language's commonest words filling the
     * rest; nothing after a handle, path, number or a second space, or before a space is typed. With the
     * next-word model ([next]), its words for the text before come first in each case (after the words
     * you write after that word, after a word; after an emoji, the commonest words).
     * `before` is the text before the cursor; a bare word counts as one just finished. Capitalised
     * as shift would be, by default at a sentence's start. */
    fun predictions(before: String, capitalize: Boolean? = null): List<String> {
        val text = if (before.lastOrNull()?.let { it.isLetter() || it == '\'' } == true) "$before " else before
        val words = when {
            startsSentence(text) -> modelWords(text, "", -1) + sentenceStarters[model.language].orEmpty()
            !text.last().isWhitespace() -> return emptyList()
            text.dropLast(1).lastOrNull()?.let { it in midSentenceMarks } == true -> modelWords(text, "", -1) + clauseStarters[model.language].orEmpty()
            EmojiWords.endsWithEmoji(text.dropLast(1)) -> modelWords(text, "", -1) + commonWords[model.language].orEmpty()
            else -> {
                val finished = currentToken(text.dropLast(1)) ?: return emptyList()
                val previous = finished.lowercase()
                val index = model.context(previous)
                val next = if (index >= 0) model.nextWords(index, suggestSwearWords, fill = false) else emptyList()
                val first = LearnedWords.wordBeforeGap(text.dropLast(1).dropLast(finished.length))?.first?.lowercase()
                (decoder.learned?.next(first, previous).orEmpty() + modelWords(text, "", index) + next + commonWords[model.language].orEmpty()).filter {
                    it.lowercase() != previous && !model.neverSuggest.blocks(it.lowercase(), suggestSwearWords, index)
                }
            }
        }.orEmpty().filterNot { model.neverSuggest.hidden(it) }
        val upper = addsCapitals && (capitalize ?: startsSentence(text))
        val out = ArrayList<String>(3)
        for (word in words) {
            val w = decoder.preferred(word)?.let { matchCase(word, it) } ?: word
            val shown = if (upper) w.replaceFirstChar { it.uppercase() } else if (addsCapitals) displayed(w)
                else if (decoder.personal?.owns(w) == true) w else w.lowercase()
            if (out.none { it.equals(shown, ignoreCase = true) }) out.add(shown)
            if (out.size == 3) break
        }
        return out
    }

    /** A phrase you write, as the strip shows it, or null: the words that most reliably follow the words
     * before the cursor ([LearnedPhrases.offer], after a sentence's start or a word and a space, never
     * after a mark, handle, path or number). Its first word is capitalised as [predictions] capitalises, none of its
     * words are one you hid or the model never suggests, and it is cut to fit the strip ([MAX_PHRASE_CHARS]). */
    fun phrase(before: String, capitalize: Boolean? = null): String? {
        val store = phrases ?: return null
        val text = if (before.lastOrNull()?.let { it.isLetter() || it == '\'' } == true) "$before " else before
        val start = startsSentence(text)
        val context = when {
            start -> LearnedPhrases.START
            !text.last().isWhitespace() || text.dropLast(1).lastOrNull()?.let { it in midSentenceMarks } == true -> return null
            else -> currentToken(text.dropLast(1))?.trimEnd('\'')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        }
        fun allowing(first: Int): (String) -> Boolean { var index = first; return { w ->
            (!model.neverSuggest.hidden(w) && !model.neverSuggest.blocks(w, suggestSwearWords, index)).also { index = model.context(w) }
        } }
        val upper = addsCapitals && (capitalize ?: start)
        fun spelled(w: String, first: Boolean): String {
            val s = decoder.learned?.spelling(w)?.takeIf { s -> s.any(Char::isUpperCase) } ?: displayed(w)
            return if (first && upper) s.replaceFirstChar { it.uppercase() } else if (addsCapitals) s else s.lowercase()
        }
        val shown = store.offer(context, allowing(if (start) -1 else model.context(context))).mapIndexed { i, w -> spelled(w, i == 0) }
        // As many words as fit the chip.
        var words = shown.size
        while (words >= 2 && shown.take(words).sumOf { it.length + 1 } - 1 > MAX_PHRASE_CHARS) words--
        return if (words >= 2) shown.take(words).joinToString(" ") else null
    }

    // A model word as the strip shows it: English "i" and its contractions with their capital, and
    // a name the books nearly always capitalise ("monday" -> "Monday").
    private fun displayed(word: String): String {
        if (model.spelling == "en" && (word == "i" || word.startsWith("i'"))) return "I" + word.drop(1)
        return capitalisedName(word) ?: word
    }

    /** The strip while a word is typed (iOS AutocorrectEngine.suggestions): what was typed
     * ([Suggestion.Kind.Typed]), then what space will put in ([Suggestion.Kind.Fix]) or the likeliest
     * candidate, then the runner-up. An unfinished word that space leaves alone (no word yet, "th")
     * gets three candidates instead. Candidates: your shortcut's phrase ("addr"), which only a tap puts
     * in, as on Gboard; your spelling of a word you typed another way ("nhi" for "nahi",
     * [SpatialDecoder.preferred]); a suggest-only English contraction ("ill" -> "I'll"), then the likeliest
     * longer word, beside the fix too ("alw" offers "always" beside "law"): for a 1–3 letter word the
     * next-word model's, else [longerWords]' first. Then, for a word of three letters or more, the decoder's
     * other readings of the taps, then the rest of the longer words (after two letters the word is usually
     * unfinished: those first). Each in the spelling you write. Never what was typed again. [autocorrect]
     * off: no fix and no readings, completions only. */
    fun strip(autocorrect: Boolean = true): List<Suggestion> {
        if (token.length !in 1..MAX_LENGTH) return emptyList()
        val typed = Suggestion(token, Suggestion.Kind.Typed)
        val shortcut = decoder.personal?.shortcut(token)
        val fix = if (autocorrect) replacement() else null
        val pool = ArrayList<String>(4)
        val lower = token.lowercase()
        val showTyped = fix != null || shortcut != null || blocked(token) || knows(lower)
        val size = if (showTyped) 2 else 3
        val lowered = !addsCapitals && token.none { it.isUpperCase() }
        fun add(candidate: String) {
            val word = preferredSpelling(candidate)
            val s = if (lowered && decoder.personal?.owns(word) != true) word.lowercase() else word
            if (pool.size < size && !s.equals(token, ignoreCase = true) && pool.none { it.equals(s, ignoreCase = true) } &&
                !blocked(s)) pool.add(s)
        }
        fix?.let { pool.add(it) }
        shortcut?.let { pool.add(it) }
        if (fix == null) decoder.preferred(lower)?.let { add(matchCase(token, it)) }
        if (autocorrect && fix == null && model.spelling == "en") suggestOnly[lower]?.let { add(matchCase(token, it)) }
        // Your own word, completed once two letters match, before the dictionary's: a word you added is
        // likelier what you mean than a reading's runner-up ("zor" offers "Zorbex" beside the fix "or").
        // One you only wrote waits for [longerWords], which weighs how often you write it.
        if (token.length >= 2) decoder.personal?.added(token)?.firstOrNull()?.let { add(yours(it)) }
        // A word already in this message, completed once two letters match: names and topic words come again.
        if (token.length >= FIELD_COMPLETES) decoder.fieldWords?.completion(token)?.let { add(if (it.any(Char::isUpperCase)) yours(it) else shown(it)) }
        // The word you write after these two, completed from its first letter (your own phrases).
        val one = previousText
        if (beforePreviousText != null && one != null) decoder.learned?.next(beforePreviousText, one, lower)?.forEach { add(learnedShown(it)) }
        // A 1–3 letter word's likeliest completions start with the next-word model's for the text before.
        val modelled = if (token.length <= 3 && lower.all { it in 'a'..'z' })
            modelWords(before, lower, previous).filter { it.length in lower.length + 1..lower.length + 8 }.map(::shown) else emptyList()
        val longer = modelled + longerWords(lower)
        // The likeliest longer word gets a slot before the taps' other readings, beside a fix too: a word being
        // typed is more often unfinished than mistyped ("climbi" keeps "climbing" beside "climb").
        longer.firstOrNull { s -> pool.none { it.equals(s, ignoreCase = true) } }?.let(::add)
        val readings = autocorrect && spatial && token.length >= 2 && !protected()
        fun reading(text: String) = if (decoder.personal?.owns(text) == true) yours(text) else shown(text)
        if (readings && token.length >= 3) for (r in decision().readings) add(reading(r.text))
        if (token.length <= 3) for (w in longer) add(w)
        // Your own words before the rest of the dictionary's (iOS lists UILexicon terms with them).
        decoder.personal?.added(token)?.let { for (w in it) { if (pool.size >= size) break; add(yours(w)) } }
        for (w in longer) add(w)
        if (readings && token.length < 3) for (r in decision().readings) add(reading(r.text))
        if (pool.isEmpty()) return listOf(typed)
        val first = if (fix != null) Suggestion(fix, Suggestion.Kind.Fix) else Suggestion(pool[0], Suggestion.Kind.Word)
        val words = listOf(first) + pool.drop(1).map { Suggestion(it, Suggestion.Kind.Word) }
        return if (showTyped) listOf(typed) + words else words
    }

    /** The words longer than [lower] that start with it, likeliest first, as the strip shows them: the dictionary's
     * commonest, and for 1–3 letters the likeliest after the word before by the word pairs, then the words you
     * write ([LearnedWords.completions]), all ranked by one cost with what you write mixed in
     * ([LearnedWords.mixed]), so a word you write often comes first ("tum" offers your "tumko"). A word you wrote
     * that the dictionary lacks counts by your use alone, and only from [LearnedWords.PREFERRED_USES] uses: one
     * kept once against a fix ("Wiht") or a slip you let stand ("donr") waits behind the common words until you
     * really write it, and one slip from a dictionary word on the list goes after that word unless you write it
     * far more. */
    private fun longerWords(lower: String): List<String> {
        val learned = decoder.learned
        fun cost(index: Int, text: String): Double {
            val base = if (index >= 0) model.languageCost(previous, index) else Double.POSITIVE_INFINITY
            return learned?.mixed(base, text, previousText, beforePreviousText) ?: base
        }
        class Ranked(val shown: String, val lower: String, val cost: Double, val known: Boolean)
        val ranked = ArrayList<Ranked>(8)
        val dictionary = model.completions(lower, suggestSwearWords, previous) +
            if (lower.length <= 3) model.completionsAfter(lower, previous, suggestSwearWords) else IntArray(0)
        for (w in dictionary) {
            val text = model.text(w)
            if (text.length > lower.length && ranked.none { it.lower == text }) ranked.add(Ranked(spelledYourWay(text), text, cost(w, text), true))
        }
        learned?.completions(token)?.forEach { w ->
            val l = w.lowercase(); val index = model.index(l)
            // Fewer uses than your spelling needs to take a word's place is too little to beat the dictionary's.
            val evidence = index >= 0 || learned.uses(l) >= LearnedWords.PREFERRED_USES
            val c = if (evidence) cost(index, w) else Double.POSITIVE_INFINITY
            if (ranked.none { it.lower == l }) ranked.add(Ranked(learnedShown(w), l, c, index >= 0))
        }
        ranked.sortBy { it.cost }
        if (learned != null) for (slip in ranked.filter { !it.known }) {
            val below = ranked.subList(ranked.indexOf(slip) + 1, ranked.size)
            val word = below.firstOrNull { it.known && EmojiWords.oneSlip(slip.lower, it.lower) } ?: continue
            // It keeps its place on the evidence your spelling of a word needs to take that word's ([LearnedWords.preferred]).
            val written = learned.uses(slip.lower)
            if (written >= LearnedWords.PREFERRED_USES && written >= LearnedWords.PREFERRED_SHARE * learned.uses(word.lower)) continue
            ranked.remove(slip)
            ranked.add(ranked.indexOf(word) + 1, slip)
        }
        return ranked.map { it.shown }
    }
    // A dictionary word as the strip shows it, in the capitals you give it ("hex" you write "Hex").
    private fun spelledYourWay(word: String) = learnedSpelling(word)?.takeUnless(::oddCapitals)?.let(::yours) ?: shown(word)
    // A word you write, as the strip shows it: a dictionary word you once wrote in capitals ("HAPPY", "alO")
    // in its own form, as the dictionary writes it.
    private fun learnedShown(word: String) = when {
        !word.any(Char::isUpperCase) -> shown(word)
        oddCapitals(word) && model.index(word.lowercase()) >= 0 -> shown(word.lowercase())
        else -> yours(word)
    }
    // Capitals past the first letter that aren't a name's or a brand's ("GitHub", "iPhone"): all capitals, or one
    // with no lowercase letter after it.
    private fun oddCapitals(word: String) =
        word.indices.any { i -> i > 0 && word[i].isUpperCase() && word.getOrNull(i + 1)?.isLowerCase() != true }

    /** Whether the letters typed are likelier the whole word than the start of a longer one: no word of the
     * model's is longer and starts with them, or they spell one at least as common as the commonest that is.
     * Only then may emoji join the strip for them ([EmojiWords]), so they never take a slot from a word still
     * being typed: "fire" offers 🔥, but "tha" no 🙏 for "thanks". */
    fun spellsWhole(): Boolean {
        val lower = token.lowercase()
        val longer = model.completions(lower, suggestSwearWords, previous).firstOrNull { model.length(it) > lower.length } ?: return true
        val own = model.index(lower)
        return own >= 0 && model.frequencyCost(own) <= model.frequencyCost(longer)
    }

    // The spelling you write of a candidate, cased as it is; the candidate itself while it completes what
    // was typed and yours doesn't ("nah" offers "nahi", not "nhi").
    private fun preferredSpelling(word: String): String {
        val yours = decoder.preferred(word) ?: return word
        if (word.startsWith(token, ignoreCase = true) && !yours.startsWith(token, ignoreCase = true)) return word
        return matchCase(word, yours)
    }
    // A candidate cased as the word was typed, with the capital the strip shows for names.
    private fun shown(word: String) = matchCase(token, if (token.firstOrNull()?.isUpperCase() == true) word else displayed(word))
    fun letter(text: String, x: Double, y: Double, grid: KeyGrid) {
        if (restoredWord) retap()
        token += text; decision = null
        if (spatial && !literalOnly && taps.size < MAX_LENGTH) {
            taps.add(decoder.score(grid, x, y)); touches.add(KeyPoint(x, y)); touchGrid = grid
        }
    }
    /** Whether an apostrophe typed now is part of this word: English's own, once, after a letter ("don't"). */
    fun joinsApostrophe() = model.spelling == "en" && spatial && !literalOnly && token.isNotEmpty() && token.last().isLetter() &&
        token.none { it == '\'' || it == '\u2019' } && taps.size == token.length && token.length < MAX_LENGTH - 1
    /** The apostrophe key typed inside a word, tapped exactly, so the letters after it are still read with the word. */
    fun apostrophe(text: String) {
        token += text; decision = null
        taps.add(decoder.apostropheTap()); touches.add(KeyPoint(0.0, 0.0))
    }
    fun explicit(text: String) {
        token += text; taps.clear(); touches.clear(); decision = null; literalOnly = true; restoredWord = false
    }
    // The restored word's letters read as tapped at their keys' centres, as [reread] reads them, so the word
    // you go on typing can be fixed; their touches stay unknown, so [TouchOffsets] learns nothing from them.
    private fun retap() {
        restoredWord = false
        handEdited = undone
        if (!spatial || token.length >= MAX_LENGTH) return
        val centres = token.map { QWERTY.centres[it.lowercaseChar()] ?: QWERTY.centres[base(it)] ?: return }
        literalOnly = false; taps.clear(); touches.clear()
        for (p in centres) taps.add(decoder.score(QWERTY, p.x, p.y))
    }
    fun pop() {
        if (token.isEmpty()) return
        if (taps.size == token.length) { taps.removeAt(taps.lastIndex); if (touches.isNotEmpty()) touches.removeAt(touches.lastIndex) }
        token = token.dropLast(1); decision = null
    }
    private val glider by lazy { GlideDecoder(model) }
    /** The words a glide spells after `before` (the text before the cursor), likeliest first, spelled as
     * the strip shows them: a name with its capital, your own word your way, and the spelling you write
     * of a word written several ways ("nhi" for "nahi"). A dictionary word you once wrote in capitals
     * glides in its own form ("can", not "CAN"). */
    fun glide(path: List<KeyPoint>, grid: KeyGrid, before: String): List<String> =
        glider.decode(path, grid, model.context(previousWord(before).lowercase()), suggestSwearWords, decoder.personal, decoder.learned,
            beforePrevious = LearnedWords.wordBeforeGap(before)?.let { LearnedWords.wordBeforeGap(it.second) }?.first?.lowercase(),
            fieldWords = decoder.fieldWords)
            .map { c ->
                val yours = decoder.preferred(c.text)?.takeUnless(::blocked)
                if (yours != null) glidedSpelling(yours)
                else if (c.personal) c.text else glidedSpelling(c.text)
            }.distinct()
    private fun glidedSpelling(word: String) = learnedSpelling(word)?.takeUnless(::oddCapitals) ?: displayed(word)
    // How you write a model word when you give it capitals it doesn't have ("hex" -> "Hex").
    private fun learnedSpelling(word: String) = decoder.learned?.spelling(word)?.takeIf { it != word && it.lowercase() == word }
    private fun decision() = decision ?: decoder.decide(token.lowercase().replace('\u2019', '\''), taps, previous, suggestSwearWords, beforePreviousText).also { decision = it }
    // Your own words are always yours to offer, as iOS offers UILexicon terms.
    private fun blocked(spelling: String) = model.neverSuggest.blocks(spelling.lowercase(), suggestSwearWords, previous) &&
        decoder.personal?.owns(spelling) != true
    /** The key a touch near a key's edge presses (iOS FullKeyboardEngine.key(at:), #2091): the key it
     * landed on ([hit]), or a neighbouring letter whose touch area is within [REACH] of it and which
     * is enough likelier to come next in the word, by the word list ("th" then a touch on the e/d
     * border types e). [near] gives each neighbouring letter's distance from the touch to its touch
     * area, in dp (iOS's points), only those under [REACH]; [keys] is every letter on the keyboard,
     * so a letter without a key of its own counts as its base letter's (é as e). Only while
     * autocorrect is on in an ordinary text field, never with caps lock; the keyboard decides that.
     * With [touch], a miss counts for more or less by how you tap each key: a neighbour you hit cleanly
     * is dearer to reach for, one you often miss is cheaper, and a touch beside a key you hit cleanly is
     * likelier to have meant its neighbour ([TouchOffsets.spread]). */
    fun likelierKey(before: String, hit: Char, near: Map<Char, Double>, keys: Collection<Char>, touch: TouchOffsets? = null): Char {
        if (near.isEmpty()) return hit
        val token = currentToken(before)
        if (token == null && before.lastOrNull()?.let { it.isLetterOrDigit() || it == '\'' } == true) return hit
        val continuations = model.continuations((token ?: "").lowercase()) ?: return hit
        val keyed = keyed(keys)
        val mass = DoubleArray(continuations.size)
        for (code in 1 until continuations.size) if (continuations[code] > 0)
            mass[if (code < keyed.size && keyed[code]) code else model.bases[code]] += continuations[code]
        val total = mass.sum()
        // Right after an elided article or pronoun ("l", "qu"), the apostrophe key is the likeliest: the
        // books split the commonest elisions off ("l'", "d'"), so the word list can't say so itself.
        val elided = token != null && token.lowercase() in ELISIONS[model.language].orEmpty() &&
            before.dropLast(token.length).lastOrNull().let { boundary(it) }
        if (elided && model.apostrophe != 0) mass[model.apostrophe] = maxOf(mass[model.apostrophe], total)
        fun odds(c: Char) = model.code(c.lowercaseChar()).takeIf { it != 0 }?.let { kotlin.math.ln(mass[it] + total * 1e-4) }
        var best = hit
        var bestScore = odds(hit) ?: return hit
        val own = touch?.spread(hit) ?: 1.0
        for ((c, distance) in near) {
            if (distance >= REACH) continue
            val score = (odds(c) ?: continue) - ODDS_PER_DP * distance * own / (touch?.spread(c) ?: 1.0)
            if (score > bestScore) { best = c; bestScore = score }
        }
        return if (hit.isUpperCase()) best.uppercaseChar() else best
    }

    // The letter codes of the keys last asked about. The keyboard hands over the same keys on every touch
    // (its own map, refilled when the layout changes), so they are compared letter by letter, not by identity.
    private class Keyed(val chars: CharArray, val codes: BooleanArray)
    private var lastKeyed: Keyed? = null
    private fun keyed(keys: Collection<Char>): BooleanArray {
        lastKeyed?.let { last ->
            if (last.chars.size == keys.size) {
                var same = true; var i = 0
                for (c in keys) { if (last.chars[i++] != c) { same = false; break } }
                if (same) return last.codes
            }
        }
        val chars = CharArray(keys.size); var i = 0
        for (c in keys) chars[i++] = c
        val codes = BooleanArray(model.alphabet + 1)
        for (c in chars) model.code(c.lowercaseChar()).let { if (it in codes.indices) codes[it] = true }
        return codes.also { lastKeyed = Keyed(chars, it) }
    }

    /** Whether a letter typed after `before` starts a word the keyboard should read: after a space,
     * an opening mark (`boundary`) or an emoji, a straight quote that opens a quotation ("'teh"), the hyphen inside
     * a word ("well-kn", as [currentToken] reads it), and in French and Italian also right after an
     * elided article or pronoun ("l'", "qu'", "dell'"), so "l'hpmme" is read as "homme" after "l'". */
    fun startsWord(before: String): Boolean {
        if (boundary(before.lastOrNull()) || EmojiWords.endsWithEmoji(before)) return true
        // A straight quote is an apostrophe after a letter ("don'"), an opening quote after a space or mark.
        if (before.lastOrNull() == '\'' && boundary(before.dropLast(1).lastOrNull())) return true
        if (before.lastOrNull() == '-' && currentToken(before.dropLast(1)) != null) return true
        val elisions = ELISIONS[model.language] ?: return false
        if (before.lastOrNull() != '\'' && before.lastOrNull() != '\u2019') return false
        val prefix = before.dropLast(1).takeLastWhile { it.isLetter() }.lowercase()
        return prefix in elisions && before.dropLast(1 + prefix.length).lastOrNull().let { boundary(it) }
    }

    // A capitalised word of yours that is also an ordinary lowercase word ("Will", "Bill") stays
    // lowercase when typed so, as iOS's withoutStrayCapital.
    private fun ordinary(word: String) = word.lowercase().let { model.index(it) >= 0 && (model.capitals?.percent(it) ?: -1) < 95 }
    // Typed without capitals, a word of yours takes only capitals that are how it is spelled: inside it
    // ("GitHub"), or a name your dictionary or contacts give. A dictionary word in capitals ("COMPANY", from
    // a contact) is never shouted, and a first capital that is only how you last wrote it ("Opencode") isn't
    // a habit: you write it as you type it.
    private fun yours(word: String): String {
        val lower = word.lowercase()
        if (token.drop(1).none { it.isUpperCase() } && word.length >= 2 && word.none { it.isLowerCase() } && model.index(lower) >= 0)
            return matchCase(token, lower)
        if (token.firstOrNull()?.isUpperCase() != true && word.drop(1).none { it.isUpperCase() } &&
            (ordinary(word) || decoder.learned?.spelling(lower) == word)) return lower
        return matchCase(token, word)
    }
    // A word you've written that the dictionary lacks, one key off a far commoner word ("likr" for "like",
    // "wiht" for "with"): a slip you let stand twice, not a word of yours, so space fixes it. A word your
    // dictionary or contacts give ([LearnedWords] never saw it) is left alone.
    private fun commonerSlip(): String? {
        val lower = token.lowercase()
        if (!spatial || lower.length < 4 || token.drop(1).any { it.isUpperCase() } || model.index(lower) >= 0 || model.known(lower) ||
            decoder.learned?.spelling(lower) == null) return null
        var best: String? = null
        var bestCost = COMMON_SLIP + 1
        fun consider(candidate: String) {
            val i = model.index(candidate)
            if (i < 0 || model.frequencyCost(i) >= bestCost || model.neverSuggest.blocks(candidate, suggestSwearWords, previous)) return
            best = candidate; bestCost = model.frequencyCost(i)
        }
        val letters = lower.toCharArray()
        for (i in letters.indices) {
            val at = QWERTY.centres[letters[i]] ?: return null
            for ((c, p) in QWERTY.centres) if (c != letters[i] && kotlin.math.hypot(p.x - at.x, p.y - at.y) <= NEIGHBOUR) {
                letters[i] = c; consider(String(letters)); letters[i] = lower[i]
            }
            if (i + 1 < letters.size && letters[i] != letters[i + 1]) {
                letters[i] = lower[i + 1]; letters[i + 1] = lower[i]; consider(String(letters))
                letters[i] = lower[i]; letters[i + 1] = lower[i + 1]
            }
        }
        return best
    }
    // A word stretched for feeling, its last letter held ("Thankss", "Yayyy", "Whyy", "Cooll"): it stands
    // when the word under the stretch is one. Only doubling the end of a two-letter word ("nee" over "ne")
    // is likelier a slip.
    private fun stretched(lower: String): Boolean {
        val last = lower.last()
        if (lower.length < 3 || lower[lower.length - 2] != last || !last.isLetter()) return false
        val stem = lower.trimEnd(last)
        val held = lower.length - stem.length
        return knows(stem + last) && (held >= 3 || stem.length >= 2) || knows(stem + last + last)
    }
    // A fix the decoder found that space still doesn't make: a word typed in lowercase into a name that
    // starts with another letter ("typa" -> "Gupta"); a dictionary word into a rarer one ("aadha" -> "aacha");
    // in Hinglish, a short word ending in a vowel, as its words do, that no list has, into one two or more
    // letters off that isn't its completion ("kuru" -> "jury", "jaysa" -> "jyada"; "becau" -> "because" goes in); in Hinglish, a key read as a missed space when no
    // part is four letters or more, since its short words split nearly anything ("konse" -> "ko se",
    // "gyantoh" -> "gya toh"); and a word edited by hand after its autocorrection was undone, unless the fix
    // is one slip off.
    private fun holdsBack(lower: String, fix: String): Boolean {
        val target = fix.lowercase()
        val hinglish = model.language == "hien"
        if (' ' in target) {
            if (handEdited) return true
            val lostKey = target.replace(" ", "") != lower.replace("'", "")
            return hinglish && lostKey && target.split(' ').none { it.length >= 4 }
        }
        if (token.first().isLowerCase() && target.first() != lower.first() &&
            (fix.first().isUpperCase() || capitalisedName(target) != null)) return true
        // English and Hinglish only: elsewhere an accent's word may be the rarer one and still meant ("ou" -> "où").
        val literal = model.index(lower)
        val other = model.index(target)
        if (model.spelling == "en" && literal >= 0 && other >= 0 && model.frequencyCost(other) > model.frequencyCost(literal)) return true
        val distance = TypoMemory.distance(lower, target)
        if (hinglish && lower.length in 3..5 && lower.last() in "aeiou" && distance >= 2 && !knows(lower) &&
            !target.startsWith(lower) && decoder.english?.index(lower)?.let { it >= 0 } != true) return true
        return handEdited && distance > 1
    }
    fun literalChip(suggestions: List<String>) = token.takeIf { it.isNotEmpty() && (suggestions.isEmpty() || blocked(it)) }
    private fun protected() = token.drop(1).any { it.isUpperCase() } ||
        (model.spelling == "en" && token.lowercase() in shorthand)
    private fun cased(s: String) = if (token.firstOrNull()?.isUpperCase() == true) s.replaceFirstChar { it.uppercase() } else s
    /** What space puts in place of the word alone, or null. */
    fun replacement(): String? {
        val fix = withCapitals() ?: return null
        if (addsCapitals || token.any { it.isUpperCase() } || decoder.personal?.owns(fix) == true) return fix
        return fix.lowercase().takeIf { it != token }
    }
    private fun withCapitals(): String? {
        if (token.isEmpty()) return null
        val personal = decoder.personal
        // Your shortcut stays as typed: its phrase is the strip's to offer.
        if (personal?.shortcut(token) != null) return null
        // Your own word: never corrected into another, and spelled your way ("github" -> "GitHub").
        personal?.index(token)?.takeIf { it >= 0 }?.let { i ->
            if (!literalOnly) commonerSlip()?.let { return cased(it) }
            return yours(personal.text(i)).takeIf { s -> s != token }
        }
        if (literalOnly || blocked(token)) return null
        // A short part after a hyphen is seldom a slip: "hi-fi", "anti-vax" and "bf-gf" stay ("hi-if" otherwise).
        if (before.lastOrNull() == '-' && token.length < HYPHEN_PART_FIXED) return null
        val lower = token.lowercase()
        val allCaps = token.length >= 2 && token.all { it.isUpperCase() }
        if (token.drop(1).any { it.isUpperCase() } && !allCaps) return null
        if (model.spelling == "en" && lower in shorthand) return null
        if (!knows(lower) && stretched(lower)) return null
        // A word the same letters spell with an apostrophe or an accent, or one that sounds alike,
        // chosen by the word before when it says so clearly ("yes id" -> "yes I'd", "the id" stays).
        if (!allCaps && '\'' !in token) ContextWords.choice(model, token, previousText, null, sentenceStart)
            ?.takeUnless(::blocked)?.let { return it }
        if (model.spelling == "en") {
            englishFixes[lower]?.takeUnless(::blocked)?.let { return if (allCaps) {
                if (token.length >= 3) it.uppercase() else null
            } else cased(it) }
        }
        if (allCaps || !spatial) return null
        // A name, day or place typed in lowercase takes its capital ("mary" -> "Mary"), and so does a
        // German noun ("haus" -> "Haus"), as the capitals list beside the model says.
        val found = decision().replace ?: return capitalised(token)
        // In the spelling you write, which may be what you typed ("nhi" when you write it, not "nahi").
        // Whether to hold back is the decoder's word's question: "nahu" is one slip from "nahi", not two from "nhi".
        val replace = if (' ' in found || decoder.personal?.owns(found) == true) found
            else decoder.preferred(found)?.takeUnless(::blocked) ?: found
        if (replace.equals(token, ignoreCase = true) || holdsBack(lower, found)) return capitalised(token)
        val fix = if (decoder.personal?.owns(replace) == true) yours(replace) else cased(replace)
        // A missed space after English "i" ("iwant") gives "I want", as "i" alone does.
        if (model.spelling == "en" && ' ' in fix) return fix.split(' ').joinToString(" ") { if (it == "i") "I" else it }
        // German's model is lowercase: a noun it corrects to takes its capital ("hsus" -> "Haus").
        return if (model.language == "de" && ' ' !in fix) capitalised(fix) ?: fix else fix
    }

    private fun capitalisedName(word: String): String? {
        if (word.length < 2 || word != word.lowercase() || (model.capitals?.percent(word) ?: -1) < 95) return null
        val first = word.first().uppercase()
        return if (first.length == 1) first + word.drop(1) else null
    }

    // `word` with its capital when it is all lowercase and the books nearly always write it with one
    // (95%), or, in German, half the time after an article or determiner ("das leben"); else null.
    private fun capitalised(word: String): String? {
        if (word.length < 2 || word != word.lowercase()) return null
        val share = model.capitals?.percent(word) ?: return null
        if (share < 95 && !(model.language == "de" && share >= 50 && previousText in germanDeterminers)) return null
        val first = word.first().uppercase()
        return if (first.length == 1) first + word.drop(1) else null
    }

    /** What a space (or a closing mark, [ending]) does as it finishes the word: the word's own fix,
     * the word before revised now that this one follows ("well see" -> "we'll see"), or both words
     * joined when the space between them was tapped for a letter ("deli ate" -> "delicate"). Null
     * leaves the text as typed. [space] is where the space bar was tapped, in [grid]'s coordinates.
     * A correction [undone] says was undone before is passed over for the next one down, down to the
     * word's own fix. Call it once per space or mark, then `reset` with the text after it. */
    fun finish(ending: Char = ' ', space: KeyPoint? = null, grid: KeyGrid? = null, undone: (Correction) -> Boolean = { false }): Correction? {
        val word = finished
        finished = null; pending = null
        if (token.isEmpty() || (ending == '.' && token.lowercase() in abbreviations)) return null
        var result: Correction? = null
        if (word?.taps != null && word.space != null && spatial && !literalOnly && taps.size == token.length &&
            listOf(word.typed, token).none { it.lowercase() in shorthand || decoder.personal?.owns(it) == true }) {
            val prev = previousWord(before.dropLast(word.text.length + 1)).takeIf { it.isNotEmpty() }
            decoder.merge(word.typed.lowercase(), word.taps, word.space, word.reach, token.lowercase(), taps,
                model.context(prev.orEmpty()), knows(word.typed), knows(token), suggestSwearWords)?.let { merged ->
                result = Correction(word.text + " " + token, matchCase(word.typed, merged)).takeUnless(undone)
            }
        }
        if (result == null) {
            val fix = replacement()
            val now = fix ?: token
            val revised = if (word != null && '\'' !in word.typed) revision(word.text, now) else null
            if (revised != null) result = Correction(word!!.text + " " + token, "$revised $now").takeUnless(undone)
            // The word before's revision undone, this word's own fix (the strip's) still goes in.
            if (result == null && fix != null) result = Correction(token, fix).takeUnless(undone)
        }
        if (ending == ' ') remember(result, space, grid)
        return result
    }

    // The word before, revised now that `next` follows it, or null: words the context decides
    // (`ContextWords`); a word typed with its own apostrophe never gets here.
    private fun revision(text: String, next: String): String? {
        if (!ContextWords.decides(model.spelling) || text.isEmpty() || (model.spelling == "en" && text.length < 2)) return null
        // Typed in capitals, left alone; one capital letter is a sentence's start ("E andato" -> "È andato").
        if (text.length >= 2 && text == text.uppercase() && text != text.lowercase() && !text.startsWith("I'")) return null
        val rest = before.dropLast(text.length + 1)
        val prev = previousWord(rest).takeIf { it.isNotEmpty() }
        val choice = ContextWords.choice(model, text, prev, next.substringBefore(' ').lowercase(), startsSentence(rest)) ?: return null
        if (model.neverSuggest.blocks(choice.lowercase(), suggestSwearWords, model.context(prev.orEmpty()))) return null
        return if (addsCapitals || text.first().isUpperCase()) choice else choice.lowercase().takeIf { it != text }
    }

    /** The word [reread] read is none Hex knows: not the dictionary's, nor yours, nor a shortcut. */
    fun unknown() = token.isNotEmpty() && decoder.personal?.shortcut(token) == null && !knows(token)

    private fun knows(spelling: String) = spelling.lowercase().let {
        model.index(it) >= 0 || model.known(it) || it in shorthand || (decoder.personal?.index(it) ?: -1) >= 0
    }

    // Keeps the word this space finished for the next word's revision or join. After a split or a
    // join it reads other letters than were typed: kept as it reads, without taps.
    private fun remember(result: Correction?, space: KeyPoint?, grid: KeyGrid?) {
        val full = before + token
        val after = (if (result == null) full else full.dropLast(result.original.length) + result.replacement) + " "
        val text = after.dropLast(1).takeLastWhile { it.isLetter() || it == '\'' }
        if (text.isEmpty()) return
        val sameLetters = text.count { it != '\'' } == token.length
        val wordTaps = if (sameLetters && spatial && !literalOnly && taps.size == token.length) ArrayList(taps) else null
        val spaceTap = if (wordTaps != null && space != null && grid != null) decoder.score(grid, space.x, space.y) else null
        val reach = if (spaceTap != null) decoder.rowCost(grid!!, space!!.x, space.y) else Double.POSITIVE_INFINITY
        pending = Finished(text, if (sameLetters) token else text, wordTaps, spaceTap, reach, after)
    }
    fun suggestions(): List<String> {
        if (token.length !in 1..MAX_LENGTH) return emptyList()
        val options = LinkedHashMap<String, Int>()
        for (w in model.completions(token.lowercase(), suggestSwearWords, previous)) options[model.text(w)] = model.frequencyCost(w)
        if (spatial && options.size < 3 && token.length >= 2 && !protected()) for (r in decision().readings)
            options.putIfAbsent(r.text, r.frequency)
        return options.entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3).map { cased(it.key) }
    }
    companion object {
        const val MAX_LENGTH = 32
        /** The fewest letters a part after a hyphen needs before space corrects it ("well-knwn"). */
        private const val HYPHEN_PART_FIXED = 4
        // How common a word must be ([TypingModel.frequencyCost], lower is commoner: "like" is 56) for a word
        // of yours one key off it to read as a slip of it ([commonerSlip]).
        private const val COMMON_SLIP = 60
        // Keys whose centres are this close on [QWERTY]'s reference keys (dp) are neighbours: beside, or above and below.
        private const val NEIGHBOUR = 60.0
        private const val FIELD_COMPLETES = 2
        /** The longest phrase the strip's wide slot shows without cutting it. */
        const val MAX_PHRASE_CHARS = 24
        // R1's reference keys, for a word read without its taps ([reread]).
        private val QWERTY = KeyGrid(buildMap {
            for ((row, letters) in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").withIndex())
                for ((i, c) in letters.withIndex()) put(c, KeyPoint(201 + (i - (letters.length - 1) / 2.0) * 39.5, 66.5 + 56 * row))
        }, 39.5, 56.0)
        private fun base(c: Char) =
            java.text.Normalizer.normalize(c.lowercase(), java.text.Normalizer.Form.NFD).firstOrNull() ?: c
        /** How far past its own touch area a likelier letter may reach, in dp, and the nats of odds
         * (ln of how many times likelier) it needs for each dp it claims (iOS letterReach, letterOddsPerPoint). */
        const val REACH = 5.0
        const val ODDS_PER_DP = .8
        // Words the strip offers in place of these, but space never puts in: each is a word too.
        private val suggestOnly = mapOf("ill" to "I'll", "id" to "I'd", "were" to "we're", "wed" to "we'd",
            "well" to "we'll", "its" to "it's", "lets" to "let's", "hell" to "he'll", "shell" to "she'll")
        // Hex's own short lists, as iOS: a sentence's common first words, a clause's after a comma, and
        // the commonest words for next-word slots the model can't fill. Hinglish's are its corpus's
        // commonest, with English's.
        private val sentenceStarters = mapOf("en" to listOf("I", "the", "I'm"), "es" to listOf("yo", "el", "la"),
            "de" to listOf("ich", "das", "die"), "nb" to listOf("jeg", "det", "vi"), "fr" to listOf("je", "le", "la"),
            "it" to listOf("io", "il", "la"), "hien" to listOf("ye", "bhai", "I"))
        private val clauseStarters = mapOf("en" to listOf("I", "but", "and"), "es" to listOf("que", "pero", "y"),
            "de" to listOf("und", "aber", "ich"), "nb" to listOf("og", "men", "jeg"), "fr" to listOf("et", "mais", "je"),
            "it" to listOf("e", "ma", "che"), "hien" to listOf("aur", "I", "ye"))
        private val commonWords = mapOf("en" to listOf("the", "to", "and", "a", "I", "of"),
            "es" to listOf("de", "que", "y", "la", "el", "en"), "de" to listOf("und", "die", "der", "ich", "ist", "das"),
            "nb" to listOf("og", "i", "det", "er", "på", "som"), "fr" to listOf("de", "la", "et", "le", "je", "à"),
            "it" to listOf("di", "e", "che", "la", "il", "a"), "hien" to listOf("hai", "to", "ke", "the", "nahi", "and"))
        private val midSentenceMarks = ",;:)\"'\u201D\u2019\u2014"
        // The next-word model's languages (English, Hinglish) write these letters alone as words.
        private val oneLetterWords = setOf("a", "i")
        /** The word right before the cursor, or null inside a handle, path, address or number. */
        fun currentToken(before: CharSequence): String? {
            var start = before.length
            while (start > 0) {
                val c = before[start - 1]
                if (c.isLetter() || (c == '\'' && start < before.length && start >= 2 && before[start - 2].isLetter())) start-- else break
            }
            if (start == before.length) return null
            // A hyphenated word's last part is a word of its own ("well-kn"), when the whole starts where a word does.
            var head = start
            while (head >= 2 && before[head - 1] == '-' && before[head - 2].isLetter()) {
                head--
                while (head > 0 && before[head - 1].isLetter()) head--
            }
            if (head > 0 && before[head - 1].let { it in "@/.#_-:" || it.isDigit() }) return null
            return before.substring(start).takeIf { it.first().isLetter() }
        }
        /** Marks that close a word and correct it as space does. */
        fun endsWord(c: Char) = c in ",.!?;:)\""
        // A period after these makes an abbreviation ("approx.", "etc."), never a typo.
        private val abbreviations = ("approx appt apt assn asst ave blvd cf co corp dept dr eg est etc fig govt ie inc jr lb " +
            "lbs ltd misc mr mrs ms mt no nr oz pp prof pt rd sr st vol vs wk yr").split(' ').toSet()
        // Whether the next word starts a sentence: the start, a new line, or . ! ? (and closing quotes
        // or brackets) and a space.
        fun startsSentence(before: String): Boolean {
            val last = before.lastOrNull() ?: return true
            if (last == '\n') return true
            if (!last.isWhitespace()) return false
            var rest = before.trimEnd { it.isWhitespace() && it != '\n' }
            val written = rest.lastOrNull() ?: return true
            if (written == '\n') return true
            rest = rest.trimEnd { it in "\"')]\u201D\u2019" }
            return rest.lastOrNull()?.let { it in ".!?" } ?: false
        }
        // After these German writes the noun with a capital ("das Haus", "im Haus", "mein Haus"), so
        // a word the books write both ways is a noun here (iOS GermanCapitals.determiners).
        private val germanDeterminers = ("der die das den dem des ein eine einen einem einer eines kein keine keinen keinem " +
            "keiner keines mein meine meinen meinem meiner meines dein deine deinen deinem deiner deines sein seine " +
            "seinen seinem seiner seines ihre ihren ihrem ihrer ihres unser unsere unseren unserem unserer unseres euer " +
            "eure euren eurem eurer eures dies dieser diese dieses diesem diesen jeder jede jedes jedem jeden im in am " +
            "zum zur vom beim ins ans").split(' ').toSet()
        // Hosts give a window of the text: compare its last 64 characters once both are that long.
        private fun sameText(a: String, b: String) =
            if (a.length >= 64 && b.length >= 64) a.takeLast(64) == b.takeLast(64) else a == b
        // `target` cased as `typed` was: all capitals, or a capital first letter.
        private fun matchCase(typed: String, target: String) = when {
            typed.length >= 2 && typed.all { it.isUpperCase() } -> target.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> target.replaceFirstChar { it.uppercase() }
            else -> target
        }
        private val shorthand = "ok okay ty thx pls plz tmrw tbh idk imo imho lol lmao omg brb btw afaik fyi nvm rn irl jk np yw smh ikr ngl fr gg dm pr prs ci ui ux ios macos api apis url urls json sql ssh cli gpu cpu ram sdk hex wisp seer rune kit gonna wanna gotta kinda sorta yeah yep nope nah hey yo".split(' ').toSet()
        // The word before the cursor, lowercase; an elided one without its apostrophe ("l'" as "l").
        fun previousWord(before: String) = before.trimEnd().takeLastWhile { it.isLetter() || it == '\'' || it == '\u2019' }
            .lowercase().replace('\u2019', '\'').trimEnd('\'')
        // The articles and pronouns French and Italian elide before a vowel; a word starts after them.
        private val ELISIONS = mapOf(
            "fr" to setOf("c", "d", "j", "l", "m", "n", "s", "t", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu", "presqu"),
            "it" to setOf("c", "d", "l", "m", "n", "s", "t", "v", "un", "all", "dall", "dell", "nell", "sull", "coll",
                "quest", "quell", "bell", "sant", "tutt", "nessun", "buon"))
        fun boundary(c: Char?) = c == null || c.isWhitespace() || c in "([{“‘\",;!?"
    }
}
