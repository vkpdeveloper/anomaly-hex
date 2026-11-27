package ly.anoma.hex.typing

import java.text.Normalizer

/** Positions are all in the same layout coordinates. The IME supplies measured key centres;
 * the replay supplies the original test layout, not a guessed phone screen size. */
internal data class KeyPoint(val x: Double, val y: Double)
/** The space bar's keycap, in the same coordinates as the key centres. */
internal data class KeyRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val centreY get() = (top + bottom) / 2
}
/** `space` is the space bar's keycap, or null when unknown: then a tap on the bottom letter row
 * is never read as a missed space. */
internal class KeyGrid @JvmOverloads constructor(val centres: Map<Char, KeyPoint>, val pitchX: Double, val pitchY: Double, val space: KeyRect? = null,
                       /** Where taps for each key are expected: its centre moved by your habitual miss ([TouchOffsets]). */
                       val aimed: Map<Char, KeyPoint> = centres, val touchVersion: Int = 0,
                       /** How spread out each key's taps are against the other keys' ([TouchOffsets.spread]); 1 where unknown. */
                       val scales: Map<Char, Double> = emptyMap()) {
    val bottomY = centres.values.maxOf { it.y }
    // R1's reference pitch is 39.5 × 56. Keep its vertical spread and its 6-nat split
    // prior on that layout. On shorter rows the travel to space is shorter: scale its
    // Gaussian (squared-distance) cost, down to the Android 2%-omission prior.
    private val referenceY = pitchX * (56.0 / 39.5)
    val verticalSpread = .26 * maxOf(pitchY, referenceY)
    private val rowScale = minOf(1.0, pitchY / referenceY)
    val missedSpaceCost = maxOf(3.9, 6.0 * rowScale * rowScale)
}
internal class SpatialDecoder(val model: TypingModel) {
    /** Your own words (`PersonalWords`), read beside the model's; null or empty without any. */
    @Volatile var personal: PersonalWords? = null
    /** What you've written (`LearnedWords`): its counts mix into the model's costs. */
    @Volatile var learned: LearnedWords? = null
    /** Your recurring slips (`TypoMemory`): a spelling no word has that you have fixed the same way often is fixed that way
     * (an autocorrection you only let stand fills in where the decoder has no fix; a fix you made overrules it). */
    @Volatile var typos: TypoMemory? = null
    /** The words already in the field typed in: each costs [FieldWords.bonus] less ([fieldBonus]). */
    @Volatile var fieldWords: FieldWords? = null
    /** What a word's language-model cost, after the context, falls by for being in the field already. */
    internal fun fieldBonus(text: String): Double {
        val words = fieldWords ?: return 0.0
        return words.bonus(text)
    }
    /** For Hinglish, English's model: its words are English, never spellings of a Hindi word ("sheet" isn't
     * "shit" as "theek" is "thik"). Read in the background; until then Hindi spellings stay apart. */
    @Volatile var english: TypingModel? = null

    /** The spelling you write of [word] when you write another spelling of it far more (`Spellings`,
     * [LearnedWords.preferred]), lowercase, or null. English and Hinglish only. */
    fun preferred(word: String): String? =
        if (model.language == "en" || model.language == "hien") learned?.preferred(word, ::alike) else null
    private fun alike(a: String, b: String): Boolean {
        if (Spellings.english(a, b)) return true
        val english = english ?: return false
        // Hindi words are those English lacks and the books don't capitalise (names: "Virat" isn't "vrat").
        fun hindi(w: String) = english.index(w) < 0 && (model.capitals?.percent(w) ?: -1) < 50
        return model.language == "hien" && hindi(a) && hindi(b) && Spellings.hindi(a, b)
    }
    // The model's cost for a word after the context word (and the one before it), mixed with what you write.
    private fun mixedCost(previous: Int, word: Int, beforePrevious: String?): Double {
        val base = model.languageCost(previous, word)
        return learned?.mixed(base, model.text(word), contextText(previous), beforePrevious) ?: base
    }
    /** One of your words: a moderately common word, or likelier when you write it often. */
    internal fun personalCost(text: String, previous: Int, beforePrevious: String?) =
        minOf(USER_WORD_COST, learned?.mixed(Double.POSITIVE_INFINITY, text, contextText(previous), beforePrevious) ?: USER_WORD_COST)
    internal fun contextText(previous: Int) = if (previous in 0 until model.size) model.text(previous) else null
    /** `spaceCost`: what reading this tap as a space the finger missed costs when it landed on
     * the bottom row's upper half, or infinity (`spaceTapCost`). */
    class Tap(val cost: DoubleArray, val choices: IntArray, val belowLetters: Boolean, val missedSpaceCost: Double,
              val spaceCost: Double = Double.POSITIVE_INFINITY)
    data class Reading(val text: String, val cost: Double, val frequency: Int)
    /** [considered]: every one-word reading of the taps better than the letters as typed, finished or one they
     * start ([completions]), at its cheapest, cheapest first; none when your own habit decides ([TypoMemory]). */
    data class Decision(val replace: String?, val readings: List<Reading>, val considered: List<Reading> = emptyList())
    // Google Books English has richer word pairs; these are the shared iOS R1 weights. Hinglish keeps
    // English's pairs and reads noisy taps of both languages' words best with its weight too.
    private val lmWeight = if (model.spelling == "en") 1.5 else 1.25
    // Spanish's many one- and two-letter words ("a", "es", "va") make a letter read as a space too
    // cheap a split ("vaxes" -> "va es"): there, only a tap below the letters is one.
    private val spaceTapCost = if (model.language == "es") Double.POSITIVE_INFINITY else 5.0
    // A grid's keys as letter codes and where taps for them are aimed ([KeyGrid.aimed]), in its own
    // order: built once per grid, since a tap is scored against the same grid again and again.
    // [kernel] is each key's 1 / (2 scale^2) and [norm] its Gaussian's normaliser, 2 ln scale (both axes).
    private class Keys(val grid: KeyGrid, val codes: IntArray, val xs: DoubleArray, val ys: DoubleArray,
                       val kernel: DoubleArray, val norm: DoubleArray)
    @Volatile private var keys: Keys? = null
    private fun keysOf(grid: KeyGrid): Keys {
        keys?.let { if (it.grid === grid) return it }
        val entries = grid.aimed.entries.filter { model.code(it.key) != 0 }
        val scale = DoubleArray(entries.size) { grid.scales[entries[it].key] ?: 1.0 }
        return Keys(grid, IntArray(entries.size) { model.code(entries[it].key) },
            DoubleArray(entries.size) { entries[it].value.x }, DoubleArray(entries.size) { entries[it].value.y },
            DoubleArray(entries.size) { .5 / (scale[it] * scale[it]) }, DoubleArray(entries.size) { 2 * Math.log(scale[it]) })
            .also { keys = it }
    }
    /** The apostrophe key's tap: it lands exactly, so only an apostrophe was meant. */
    fun apostropheTap(): Tap {
        val costs = DoubleArray(model.alphabet + 1) { Double.POSITIVE_INFINITY }
        if (model.apostrophe != 0) costs[model.apostrophe] = 0.0
        return Tap(costs, if (model.apostrophe != 0) intArrayOf(model.apostrophe) else IntArray(0), false, 6.0)
    }
    fun score(grid: KeyGrid, x: Double, y: Double): Tap {
        val costs = DoubleArray(model.alphabet + 1) { Double.POSITIVE_INFINITY }
        val sy = grid.verticalSpread
        val sx = grid.pitchX * .29
        val k = keysOf(grid)
        for (i in k.codes.indices) {
            val dx = (x - k.xs[i]) / sx
            val dy = (y - k.ys[i]) / sy
            costs[k.codes[i]] = k.kernel[i] * (dx * dx + dy * dy) + k.norm[i]
        }
        val min = costs.min()
        // An accented letter also comes from its base letter's key, even when it has a key of its
        // own (ä from a on QWERTZ, ñ from n): whichever is cheaper, as iOS reads it.
        val raw = costs.copyOf()
        for (i in 1 until costs.size) {
            val base = model.bases[i]
            if (base != i && raw[base].isFinite()) costs[i] = minOf(raw[i], raw[base] + ACCENT_COST)
        }
        for (i in 1 until costs.size) costs[i] -= min
        // The letters within reach, cheapest first (ties by code), by insertion: there are only a few.
        val near = IntArray(costs.size)
        var count = 0
        for (code in 1 until costs.size) {
            if (!(costs[code] <= MAX_TAP_COST)) continue
            var at = count++
            while (at > 0 && costs[near[at - 1]] > costs[code]) { near[at] = near[at - 1]; at-- }
            near[at] = code
        }
        return Tap(costs, near.copyOf(count), y > grid.bottomY, grid.missedSpaceCost, spaceCost(grid, x, y))
    }

    // A tap on the bottom letter row's upper half, above the space bar, read as a space the
    // finger missed ("ofbthe"): the more likely the row than the bar, the dearer.
    private fun spaceCost(grid: KeyGrid, x: Double, y: Double): Double {
        val bar = grid.space ?: return Double.POSITIVE_INFINITY
        val slack = grid.pitchX / 2
        if (!spaceTapCost.isFinite() || bar.right <= bar.left || x < bar.left - slack || x > bar.right + slack ||
            y <= grid.bottomY - grid.pitchY / 2 || y > grid.bottomY) return Double.POSITIVE_INFINITY
        val toBar = (y - bar.centreY) / grid.verticalSpread
        val toRow = (y - grid.bottomY) / grid.verticalSpread
        return spaceTapCost + SPACE_TAP_WEIGHT * maxOf(0.0, .5 * (toBar * toBar - toRow * toRow))
    }

    /** How much likelier the space bar than the bottom letter row a space tap at (x, y) was, in nats
     * (0 when the row is likelier); infinity when the bar is unknown or the tap isn't above it. */
    fun rowCost(grid: KeyGrid, x: Double, y: Double): Double {
        val bar = grid.space ?: return Double.POSITIVE_INFINITY
        val slack = grid.pitchX / 2
        if (bar.right <= bar.left || x < bar.left - slack || x > bar.right + slack) return Double.POSITIVE_INFINITY
        val toBar = (y - bar.centreY) / grid.verticalSpread
        val toRow = (y - grid.bottomY) / grid.verticalSpread
        return maxOf(0.0, .5 * (toRow * toRow - toBar * toBar))
    }

    /** One word in place of two, where the space between them was tapped for a letter on the row
     * above ("deli ate" -> "delicate"): the likeliest reading of all the taps, the space read as a
     * letter, when it beats the two words as they read by the margin and the space tap was near
     * enough the row (`reach`, from `rowCost`). Null to keep both. Lowercase spellings. */
    fun merge(first: String, firstTaps: List<Tap>, space: Tap, reach: Double, second: String, secondTaps: List<Tap>,
              previous: Int, firstValid: Boolean, secondValid: Boolean, swears: Boolean): String? {
        if (reach > MERGE_REACH || first.isEmpty() || second.isEmpty() || firstTaps.size != first.length ||
            secondTaps.size != second.length || (first + second).any { model.code(it) == 0 || it == '\'' }) return null
        val taps = firstTaps + space + secondTaps
        // Each word as space would leave it: as typed, or its likeliest reading ("i dkdnt" is "I didn't").
        fun reading(spelling: String, from: Int, before: Int, valid: Boolean): Pair<Double, Int> {
            val typed = model.index(spelling)
            var spatial = 0.0
            for (i in spelling.indices) spatial += taps[from + i].cost[model.code(spelling[i])]
            var best = spatial + lmWeight * (if (typed >= 0) model.languageCost(before, typed) else if (valid) 15.0 else 22.0)
            var word = typed
            for (f in search(taps, from, from + spelling.length, 1, swears, before).sortedWith(spatialOrder).take(3)) {
                val cost = f.cost + lmWeight * model.languageCost(before, f.word)
                if (cost < best) { best = cost; word = f.word }
            }
            return best to word
        }
        val (firstCost, firstWord) = reading(first, 0, previous, firstValid)
        val split = firstCost + reading(second, first.length + 1, firstWord, secondValid).first
        if (!split.isFinite()) return null
        var best = -1
        var bestCost = Double.POSITIVE_INFINITY
        for (f in search(taps, 0, taps.size, 1, swears, previous).sortedWith(spatialOrder).take(3)) {
            if (model.length(f.word) <= maxOf(first.length, second.length) ||
                model.neverSuggest.blocksFollowing(f.word, swears, previous)) continue
            val cost = f.cost + lmWeight * model.languageCost(previous, f.word) + reach
            if (cost < bestCost) { best = f.word; bestCost = cost }
        }
        if (best < 0) return null
        return if (split - bestCost >= if (firstValid && secondValid) 7.0 else 2.0) model.text(best) else null
    }

    private class State(val lo: Int, val hi: Int, val tap: Int, val edits: Int, val cost: Double)
    private data class Found(val word: Int, val cost: Double)
    /** A spelling all the taps read as that words go on from: their range in the model, its length, the taps' cost. */
    private class Prefix(val lo: Int, val hi: Int, val depth: Int, val cost: Double)
    // Keep at most 48 promising prefixes at each spelling depth. One extra, missing or
    // transposed letter is possible; an untyped apostrophe costs nothing.
    // The words of [lexicon] (the model unless given) the taps from..end read as. Only the model's
    // words are checked against the never-suggest list: your own words are yours to offer.
    // With [prefixes], also every spelling of [MIN_PREFIX] letters or more the taps read as, by its range, at its cheapest.
    private fun search(taps: List<Tap>, from: Int, end: Int, edits: Int, swears: Boolean, previous: Int,
                       lexicon: Lexicon = model, prefixes: HashMap<Long, Prefix>? = null): List<Found> {
        val depthLimit = end - from + edits + 2
        val context = model.neverSuggest.restrictedContext(previous)
        val own = lexicon === model
        val layers = Array(depthLimit + 1) { ArrayList<State>() }
        layers[0].add(State(0, lexicon.size, from, 0, 0.0))
        val found = HashMap<Int, Double>()
        // Dropping a clearly aimed, distinct tap needs more evidence than dropping a repeat or a
        // tap between keys. Accents share their physical key.
        val extraTap = DoubleArray(end - from) { i ->
            val t = from + i
            val code = taps[t].choices.firstOrNull() ?: return@DoubleArray EDIT_COST
            val key = model.bases[code]
            for (n in intArrayOf(t - 1, t + 1)) if (n in from until end &&
                taps[n].choices.firstOrNull()?.let { model.bases[it] == key } == true) return@DoubleArray EDIT_COST
            val gap = taps[t].choices.firstOrNull { model.bases[it] != key }?.let { taps[t].cost[it] } ?: MAX_TAP_COST
            EDIT_COST + minOf(4.0, gap)
        }
        for (d in 0..depthLimit) {
            val layer = layers[d]
            if (layer.size > 48) { layer.sortBy { it.cost }; layer.subList(48, layer.size).clear() }
            var i = 0
            while (i < layer.size) {
                val s = layer[i++]
                if (s.tap == end && lexicon.length(s.lo) == d && (!own || !model.neverSuggest.blocks(s.lo, swears, context)) &&
                    s.cost < (found[s.lo] ?: Double.POSITIVE_INFINITY))
                    found[s.lo] = s.cost
                if (prefixes != null && s.tap == end && d >= MIN_PREFIX) {
                    val range = (s.lo.toLong() shl 32) or s.hi.toLong()
                    if (s.cost < (prefixes[range]?.cost ?: Double.POSITIVE_INFINITY)) prefixes[range] = Prefix(s.lo, s.hi, d, s.cost)
                }
                if (d == depthLimit) continue
                fun push(c: Int, next: Int, e: Int, cost: Double) {
                    val child = lexicon.child(s.lo, s.hi, d, c)
                    if (child != 0L) layers[d + 1].add(State((child ushr 32).toInt(), child.toInt(), next, e, cost))
                }
                val t = s.tap
                if (t < end) for (c in taps[t].choices) push(c, t + 1, s.edits, s.cost + taps[t].cost[c])
                if (d > 0 && model.apostrophe != 0) push(model.apostrophe, t, s.edits, s.cost)
                if (s.edits >= edits) continue
                for (c in 1..model.alphabet) if (c != model.apostrophe) push(c, t, s.edits + 1, s.cost + EDIT_COST)
                if (t < end) layer.add(State(s.lo, s.hi, t + 1, s.edits + 1, s.cost + extraTap[t - from]))
                if (t + 1 >= end || d + 2 > depthLimit) continue
                val first = taps[t + 1]; val second = taps[t]
                for (ai in 0 until minOf(3, first.choices.size)) {
                    val a = first.choices[ai]; val r = lexicon.child(s.lo, s.hi, d, a)
                    if (r == 0L) continue
                    for (bi in 0 until minOf(3, second.choices.size)) {
                        val b = second.choices[bi]; if (b == a) continue
                        val child = lexicon.child((r ushr 32).toInt(), r.toInt(), d + 1, b)
                        if (child != 0L) layers[d+2].add(State((child ushr 32).toInt(), child.toInt(), t+2,
                            s.edits+1, s.cost + first.cost[a] + second.cost[b] + 5))
                    }
                }
            }
        }
        return found.map { Found(it.key, it.value) }
    }

    fun decide(typed: String, taps: List<Tap>, previous: Int, swears: Boolean, beforePrevious: String? = null): Decision {
        val none = Decision(null, emptyList())
        if (typed.length !in 2..32 || taps.size != typed.length || model.neverSuggest.blocks(typed, swears, previous) ||
            typed.any { model.code(it) == 0 }) return none
        val literal = model.index(typed)
        val personal = personal?.takeIf { it.size > 0 }
        val yours = personal?.index(typed) ?: -1
        val apostrophes = typed.count { it == '\'' }
        if (apostrophes > 0 && !contraction(typed, apostrophes, literal, yours, personal)) return none
        val knownLiteral = literal >= 0 || model.known(typed) || yours >= 0
        // A rare word the books use, outside the model: what it costs by its count, not a known word's.
        val listed = if (literal < 0 && yours < 0) model.validCost(typed) else null
        var lm = when {
            literal >= 0 -> mixedCost(previous, literal, beforePrevious)
            listed != null -> listed
            knownLiteral -> 15.0
            else -> 22.0
        }
        // One of your own words scores as a moderately common one (about the 10,000th).
        if (yours >= 0) lm = minOf(lm, personalCost(typed, previous, beforePrevious))
        lm -= fieldBonus(typed)
        var typedSpatial = 0.0
        for (i in taps.indices) typedSpatial += taps[i].cost[model.code(typed[i])]
        val cost = lmWeight * lm + typedSpatial
        if (!cost.isFinite()) return none
        val readings = ArrayList<Reading>()
        val prefixes = if (typed.length >= MIN_PREFIX && apostrophes == 0) HashMap<Long, Prefix>() else null
        // iOS applies the language score only to the five best spatial readings.
        for ((w, spatial) in search(taps, 0, taps.size, 1, swears, previous, prefixes = prefixes).sortedWith(spatialOrder).take(5)) if (w != literal) {
            val text = model.text(w)
            readings.add(Reading(text, spatial + lmWeight * (mixedCost(previous, w, beforePrevious) - fieldBonus(text)), model.frequencyCost(w)))
        }
        if (personal != null) for ((w, spatial) in search(taps, 0, taps.size, 1, swears, previous, personal).sortedWith(spatialOrder).take(3))
            if (w != yours) {
                val text = personal.text(w)
                readings.add(Reading(text, spatial + lmWeight * (personalCost(text, previous, beforePrevious) - fieldBonus(text)), (USER_WORD_COST * 8).toInt()))
            }
        if (taps.size >= 3 && apostrophes == 0) {
            fun alone(f: Found) = model.length(f.word) > 1 || model.frequencyCost(f.word) < 56
            fun half(a: Int, b: Int, prev: Int) = search(taps, a, b, 0, swears, prev).sortedWith(spatialOrder)
                .take(3).filter(::alone)
            // Around a tap on the bottom row read as space, each half only as the letters typed, so
            // a name's middle letter ("rima") is never a space with slips around it.
            fun exact(a: Int, b: Int): List<Found> {
                val spelling = typed.substring(a, b)
                val word = model.index(spelling)
                if (word < 0 || model.neverSuggest.blocks(word, swears)) return emptyList()
                var cost = 0.0
                for (i in a until b) cost += taps[i].cost[model.code(typed[i])]
                return listOf(Found(word, cost)).filter(::alone)
            }
            fun pair(left: List<Found>, right: List<Found>, extra: Double) {
                for (l in left) for (r in right) {
                    if (model.neverSuggest.blocksFollowing(r.word, swears, l.word)) continue
                    val lm = model.languageCost(previous, l.word) + model.languageCost(l.word, r.word)
                    readings.add(Reading(model.text(l.word) + " " + model.text(r.word), l.cost + r.cost + extra + lmWeight * lm,
                        model.frequencyCost(l.word) + model.frequencyCost(r.word)))
                }
            }
            for (k in 1 until taps.size) {
                val left = half(0, k, previous)
                if (left.isNotEmpty()) pair(left, half(k, taps.size, -1), taps[k].missedSpaceCost)
                if (k + 1 >= taps.size) continue
                if (taps[k].belowLetters) {
                    if (left.isNotEmpty()) pair(left, half(k + 1, taps.size, -1), taps[k].missedSpaceCost)
                } else if (taps[k].spaceCost.isFinite()) {
                    val exactLeft = exact(0, k)
                    if (exactLeft.isNotEmpty()) pair(exactLeft, exact(k + 1, taps.size), taps[k].spaceCost)
                }
            }
        }
        val completions = prefixes?.let { completions(typed, typedSpatial, it, previous, swears, beforePrevious) }
        // A word still being typed is no missed space: no split ("some th", "under sat") where a word the typed
        // letters start is likelier. A finished word starts none, or only rarer ones ("ofthe", "iam"), and still splits.
        val unfinished = completions?.unfinished ?: Double.POSITIVE_INFINITY
        readings.removeAll { ' ' in it.text && it.cost >= unfinished }
        // A word with an apostrophe stays one word with one: never "dog's" -> "dogs" or two words.
        if (apostrophes > 0) readings.removeAll { r -> r.text.count { it == '\'' } != apostrophes }
        readings.sortWith(compareBy<Reading> { it.cost }.thenBy { it.text })
        val best = readings.firstOrNull()
        // A listed rare word takes a one-word fix as a spelling nobody knows does, but is split into
        // words only as a known word is.
        val margin = if (best == null || !knownLiteral || foldAccents(best.text.lowercase()) == foldAccents(typed) ||
            (listed != null && ' ' !in best.text)) 2.0 else 7.0
        var replace = if (best != null && cost - best.cost >= margin) best.text else null
        var shown = readings.asSequence().filter { it.cost < cost && !it.text.equals(typed, ignoreCase = true) }
            .distinctBy { it.text.lowercase() }.toMutableList()
        // Your own slip, fixed the way you always fix it, whatever the decoder makes of the taps.
        val habit = if (knownLiteral) null else typos?.fix(typed)?.takeIf {
            (it.own || replace == null) && !model.neverSuggest.blocks(it.word.lowercase(), swears, previous)
        }?.word
        if (habit != null) {
            replace = habit
            shown.removeAll { it.text.equals(habit, ignoreCase = true) }
            shown.add(0, Reading(habit, cost - 1.0, (USER_WORD_COST * 8).toInt()))
        }
        // Nothing above read a spelling that is no word at all: a missed space may still explain it,
        // with a slip in the second word or three or more words run together (iOS #2244). Never a
        // known spelling or a likelier reading that only missed the margin; not German, whose model
        // is lowercase and whose nouns take their capital in the one-word path.
        if (replace == null && !knownLiteral && model.language != "de" && typed.length >= 5 && apostrophes == 0) {
            missedSpaceReading(taps, typed, previous, swears, cost)?.takeIf { it.cost < unfinished }?.let { fallback ->
                val likeliest = shown.firstOrNull()
                if (likeliest == null || fallback.cost <= likeliest.cost || likeliest.text.equals(fallback.text, ignoreCase = true)) {
                    replace = fallback.text
                    shown.removeAll { it.text.equals(fallback.text, ignoreCase = true) }
                    shown.add(0, fallback)
                }
            }
        }
        // Words a slip early in the word hides ("somr", "somth" -> "something"), by cost among the readings, after the fix.
        // A reading that is one of them takes its cheaper cost ("yiu": "your" as "you" and more, not "you" and an "r").
        // Not after a missed space: two finished words ("iam", "alot") start no word.
        if (replace?.contains(' ') != true) for (c in completions?.slipped.orEmpty()) {
            if (c.cost >= cost) continue
            val at = shown.indexOfFirst { it.text.equals(c.text, ignoreCase = true) }
            if (at < 0) shown.add(c) else if (c.cost < shown[at].cost && !shown[at].text.equals(replace, ignoreCase = true)) shown[at] = c
        }
        // In English and Hinglish, a word cut short is likelier unfinished than mistyped: space completes it ("peop" ->
        // "people", not "prop") when the likeliest word the taps start beats the fix, or the letters as typed, by
        // [COMPLETE_MARGIN] and no other comes within [COMPLETE_GAP] ("wor" stays "work", not "world"). One behind a
        // slip as well only when common and four letters or more are typed ("somth" -> "something", never "isba" ->
        // "disbanded" or "aac" -> "academic"). Elsewhere a short unknown word is as often a name ("vi a tom ayer").
        val likeliest = completions?.likeliest
        val against = shown.firstOrNull { it.text.equals(replace, ignoreCase = true) }?.cost ?: cost
        val english = model.spelling == "en" && !knownLiteral && habit == null
        if (english && likeliest != null && likeliest.cost + COMPLETE_MARGIN <= against &&
            completions.runnerUp - likeliest.cost >= COMPLETE_GAP &&
            (likeliest !in completions.slipped || likeliest.frequency <= COMMON_COMPLETION && typed.length >= 4)) {
            replace = likeliest.text
            shown.removeAll { it.text.equals(likeliest.text, ignoreCase = true) }
            shown.add(likeliest)
        } else if (english && replace != null && ' ' !in replace && !replace.startsWith(typed) && unfinished < against) {
            // Nor does a fix go in that a word the letters start is likelier than, when it drops the last letter typed (not
            // a doubled one: "aac" is on its way to "aacha", not "aa"), or by [COMPLETE_MARGIN] ("inte" starts
            // "international" and "interest", each far likelier than "itne"). Space leaves the letters; the strip offers both.
            val dropsLast = replace == typed.dropLast(1) && typed.last() != typed[typed.length - 2]
            if (dropsLast || typed.length >= 4 && unfinished + COMPLETE_MARGIN <= against) replace = null
        }
        shown.sortWith(compareBy<Reading> { !it.text.equals(replace, ignoreCase = true) }.thenBy { it.cost }.thenBy { it.text })
        val considered = if (habit != null) emptyList() else (shown + completions?.all.orEmpty())
            .filter { it.cost < cost && ' ' !in it.text }.sortedBy { it.cost }.distinctBy { it.text.lowercase() }
        if (replace == null && shown.isEmpty() && considered.isEmpty()) return none
        return Decision(replace, shown.take(3), considered)
    }

    /** [unfinished]: what the likeliest word the typed letters start, two letters or more longer, costs, or
     * infinity. One letter more is as much a finished word missing its last letter, which the readings have
     * ("handjob" stays "hand job", not "handjobs" to come), and a slip's words don't count: "iam" is "I am",
     * not "ima" on the way to "image".
     * [slipped]: words a spelling one slip from the typed letters starts, where they beat the typed letters' own.
     * [likeliest]: the cheapest word of all these, and [runnerUp] what the next one costs.
     * [all]: the typed letters' own and every slip's words, whatever they cost. */
    private class Completions(val unfinished: Double, val slipped: List<Reading>, val likeliest: Reading?, val runnerUp: Double,
                              val all: List<Reading>)

    // The words the taps may be the start of: the commonest after the word before of each range [search]
    // read the taps as. The typed letters' own completions are the strip's (TypingModel.completions);
    // another range's words are offered only when likelier than those by [SLIP_MARGIN], so a word typed
    // right keeps its own completions first.
    private fun completions(typed: String, typedSpatial: Double, prefixes: Map<Long, Prefix>, previous: Int, swears: Boolean,
                            beforePrevious: String?): Completions {
        fun cost(spatial: Double, w: Int) = spatial + lmWeight * (mixedCost(previous, w, beforePrevious) - fieldBonus(model.text(w)))
        var own = Double.POSITIVE_INFINITY
        var unfinished = Double.POSITIVE_INFINITY
        var likeliest: Reading? = null
        var runnerUp = Double.POSITIVE_INFINITY
        val all = ArrayList<Reading>()
        fun rank(r: Reading) {
            val top = likeliest
            if (top == null || r.cost < top.cost) { runnerUp = top?.cost ?: Double.POSITIVE_INFINITY; likeliest = r }
            else runnerUp = minOf(runnerUp, r.cost)
        }
        for (w in model.completions(typed, swears, previous)) if (model.length(w) > typed.length) {
            val c = cost(typedSpatial, w)
            own = minOf(own, c)
            if (model.length(w) >= typed.length + 2) unfinished = minOf(unfinished, c)
            rank(Reading(model.text(w), c, model.frequencyCost(w)).also(all::add))
        }
        val range = model.range(typed)
        val lo = (range ushr 32).toInt(); val hi = range.toInt()
        val context = model.neverSuggest.restrictedContext(previous)
        val found = HashMap<Int, Double>()
        // A range inside the typed letters' own holds only their completions.
        for (p in prefixes.values.filter { it.lo < lo || it.hi > hi }.sortedBy { it.cost }.take(SLIP_PREFIXES))
            for (w in model.commonest(p.lo, p.hi, p.depth, 3) { model.neverSuggest.blocks(it, swears, context) }) {
                val c = cost(p.cost, w)
                if (c < (found[w] ?: Double.POSITIVE_INFINITY)) found[w] = c
            }
        val slipped = found.entries.filter { it.value < own - SLIP_MARGIN }
            .map { Reading(model.text(it.key), it.value, model.frequencyCost(it.key)) }
        slipped.forEach(::rank)
        for ((w, c) in found) all.add(Reading(model.text(w), c, model.frequencyCost(w)))
        return Completions(unfinished, slipped, likeliest, runnerUp, all)
    }

    // Whether a word typed with an apostrophe may be corrected: English's one inside the word ("ler's"),
    // not a possessive of a word that is one ("Jeff's", "the dog's") nor one of your own.
    private fun contraction(typed: String, apostrophes: Int, literal: Int, yours: Int, personal: PersonalWords?): Boolean {
        if (model.spelling != "en" || apostrophes != 1 || typed.first() == '\'' || typed.last() == '\'') return false
        if (literal >= 0 || yours >= 0) return true
        val stem = typed.substringBefore('\'')
        return !(typed.endsWith("'s") && (model.index(stem) >= 0 || (personal?.index(stem) ?: -1) >= 0))
    }

    // What a missed space can still make of taps nothing else read (iOS missedSpaceReading): the
    // cheaper of a word and a slipped second word, or three to six words spelled as typed, taken only
    // when its words, without the spaces' cost, cost no more than the run nobody knows does.
    private fun missedSpaceReading(taps: List<Tap>, typed: String, previous: Int, swears: Boolean, literalCost: Double): Reading? {
        val space = taps[0].missedSpaceCost
        fun affordable(r: Reading?) = r?.takeIf { it.cost - space * (it.text.count { c -> c == ' ' }) <= literalCost }
        val slipped = affordable(slippedSecondWord(taps, typed, previous, swears))
        val segmented = affordable(exactSegmentation(taps, typed, previous, swears))
        return when {
            slipped != null && segmented != null -> if (segmented.cost < slipped.cost) segmented else slipped
            else -> slipped ?: segmented
        }
    }

    private fun canStandAlone(word: Int) = model.length(word) > 1 || model.frequencyCost(word) < 56

    // The first letters as a word and the rest one slip from a word after it, clearing the margin it
    // would need typed after that first word ("specialchsracter" -> "special character"). A rest that
    // is a word already is the ordinary split's; the first word is two letters or more.
    private fun slippedSecondWord(taps: List<Tap>, typed: String, previous: Int, swears: Boolean): Reading? {
        val n = typed.length
        var best: Reading? = null
        for (k in 2..n - 3) {
            val left = model.index(typed.substring(0, k))
            if (left < 0 || !canStandAlone(left) || model.neverSuggest.blocks(left, swears, previous) || model.index(typed.substring(k)) >= 0) continue
            var leftSpatial = 0.0
            var rightLiteral = 0.0
            for (i in 0 until k) leftSpatial += taps[i].cost[model.code(typed[i])]
            for (i in k until n) rightLiteral += taps[i].cost[model.code(typed[i])]
            rightLiteral += lmWeight * 22.0
            val leftCost = leftSpatial + lmWeight * model.languageCost(previous, left)
            for (f in search(taps, k, n, 1, swears, left).sortedWith(spatialOrder).take(5)) {
                if (model.neverSuggest.blocksFollowing(f.word, swears, left)) continue
                val rightCost = f.cost + lmWeight * model.languageCost(left, f.word)
                if (rightLiteral - rightCost < 2.0) continue
                val cost = leftCost + rightCost + taps[k].missedSpaceCost
                if (best == null || cost < best.cost)
                    best = Reading(model.text(left) + " " + model.text(f.word), cost, model.frequencyCost(left) + model.frequencyCost(f.word))
            }
        }
        return best
    }

    private class Path(val words: List<Int>, val cost: Double)

    // Three to six words typed with no spaces, each spelled as typed ("seeyoutomorrow" -> "see you
    // tomorrow"): the cheapest path per end, last word and word count, words found by walking the
    // model's list one letter at a time from each start.
    private fun exactSegmentation(taps: List<Tap>, typed: String, previous: Int, swears: Boolean): Reading? {
        val n = typed.length
        if (n !in 5..32) return null
        val spatial = DoubleArray(n + 1)
        for (i in 0 until n) spatial[i + 1] = spatial[i] + taps[i].cost[model.code(typed[i])]
        val states = Array(n + 1) { HashMap<Long, Path>() }
        states[0][key(-1, 0)] = Path(emptyList(), 0.0)
        var best: Path? = null
        for (start in 0 until n) {
            if (states[start].isEmpty()) continue
            val ends = ArrayList<Pair<Int, Int>>()
            var lo = 0; var hi = model.size
            for (end in start + 1..n) {
                val child = model.child(lo, hi, end - start - 1, model.code(typed[end - 1]))
                if (child == 0L) break
                lo = (child ushr 32).toInt(); hi = child.toInt()
                if (model.length(lo) == end - start && canStandAlone(lo) && !model.neverSuggest.blocks(lo, swears)) ends.add(end to lo)
            }
            if (ends.isEmpty()) continue
            for ((k, path) in states[start]) {
                val last = (k shr 8).toInt()
                val before = if (last >= 0) last else previous
                for ((end, word) in ends) {
                    if (model.neverSuggest.blocksFollowing(word, swears, before)) continue
                    val words = path.words + word
                    val cost = path.cost + (spatial[end] - spatial[start]) + lmWeight * model.languageCost(before, word) +
                        (if (path.words.isEmpty()) 0.0 else taps[start].missedSpaceCost)
                    if (end == n) {
                        if (words.size in 3..6 && (best == null || cost < best.cost)) best = Path(words, cost)
                        continue
                    }
                    if (words.size >= 6) continue
                    val next = key(word, words.size)
                    val known = states[end][next]
                    if (known != null && known.cost <= cost) continue
                    states[end][next] = Path(words, cost)
                }
            }
        }
        return best?.let { p -> Reading(p.words.joinToString(" ") { model.text(it) }, p.cost, p.words.sumOf { model.frequencyCost(it) }) }
    }

    private fun key(last: Int, count: Int) = (last.toLong() shl 8) or count.toLong()
    private companion object {
        val spatialOrder = compareBy<Found> { it.cost }.thenBy { it.word }
        const val EDIT_COST = 7.0
        const val MAX_TAP_COST = 9.0
        // An accented letter typed as its base letter's key (é from e).
        const val ACCENT_COST = 1.0
        const val SPACE_TAP_WEIGHT = .25
        // A space tap reads as a letter only within about the bar's top fifth.
        const val MERGE_REACH = 3.0
        const val USER_WORD_COST = 11.0
        // Completing a spelling one slip from the typed letters: from 3 letters, the cheapest few spellings,
        // and only when likelier than the typed letters' own completions by this many nats.
        const val MIN_PREFIX = 3
        const val SLIP_PREFIXES = 16
        const val SLIP_MARGIN = 2.0
        // How much likelier a word the typed letters start must be than the fix for space to complete it, and how
        // far ahead of the next such word.
        const val COMPLETE_MARGIN = 4.0
        const val COMPLETE_GAP = .5
        // A completion behind a slip goes in only when this common (frequency cost; "something" is 70).
        const val COMMON_COMPLETION = 80
    }
    private fun foldAccents(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).filter { it !in '\u0300'..'\u0308' || it == '\u0303' }
}
