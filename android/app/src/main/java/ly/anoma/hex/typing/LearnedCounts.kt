package ly.anoma.hex.typing

import kotlin.math.pow

/** How much a use counts as time passes: a use fades by half every [HALF_LIFE_MS]. Instead of shrinking
 * every stored count each day, a use is stored as [unit], what a use weighs now, which grows as the half-life
 * passes: stored weights stay as they were and only [real] reads them back, so a word used daily this week
 * outweighs one used far more a year ago, and nothing is rewritten as time passes. The clock is read at
 * most every [TICK_MS]; weights inside a tick are equal, so equally used words stay tied. */
internal class Fade(private val now: () -> Long) {
    private var epoch = now()
    private var at = epoch
    /** What one use weighs now. */
    var unit = 1.0
        private set
    /** When [unit] was last read from the clock. */
    val time get() = at

    fun tick() {
        val t = now()
        if (t - at < TICK_MS && t >= at) return
        at = t; unit = 2.0.pow((t - epoch).toDouble() / HALF_LIFE_MS)
    }

    /** A stored weight as the number of fresh uses it is worth now. */
    fun real(weight: Double) = weight / unit

    /** What a weight written [savedAt] (as fresh uses, [real]) is stored as now, faded by the time since. */
    fun stored(fresh: Double, savedAt: Long) = fresh * unit * 2.0.pow(-maxOf(0L, at - savedAt).toDouble() / HALF_LIFE_MS)

    /** Whether weights have grown large enough that [rebase] should bring them back to one. */
    val rebaseDue get() = unit > REBASE_AT

    /** Starts the scale over at now; the caller multiplies every stored weight by the returned factor. */
    fun rebase(): Double {
        val factor = 1 / unit
        epoch = at; unit = 1.0
        return factor
    }

    companion object {
        const val HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000
        private const val TICK_MS = 10L * 60 * 1000
        private const val REBASE_AT = 1e9
    }
}

/** One counted thing: how many times it was used in all, and how much of that is left ([Fade]). */
internal class Use(var n: Int, var w: Double)

/** What followed one context: each word's [Use], and their total weight (kept as uses go in and out, so a
 * context's evidence is one read). */
internal class Follows {
    var sum = 0.0
        private set
    val map = HashMap<String, Use>(4)

    /** Whether [word] is new here. */
    fun add(word: String, n: Int, w: Double): Boolean {
        var fresh = false
        val use = map.getOrPut(word) { fresh = true; Use(0, 0.0) }
        use.n += n; use.w += w; sum += w
        return fresh
    }

    /** Takes back one use worth [w]; returns whether [word] left. */
    fun take(word: String, w: Double): Boolean {
        val use = map[word] ?: return false
        val taken = minOf(w, use.w)
        use.n--; use.w -= taken; sum -= taken
        if (use.n > 0 && use.w > 0) return false
        sum -= use.w; map.remove(word)
        return true
    }

    fun remove(word: String): Boolean = map.remove(word)?.also { sum -= it.w } != null

    /** Drops what weighs [floor] or less; returns how many went. */
    fun dropUpTo(floor: Double): Int {
        val before = map.size
        map.values.removeAll { it.w <= floor }
        sum = map.values.sumOf { it.w }
        return before - map.size
    }

    /** Keeps only the words [keep] says to. */
    fun retain(keep: (String) -> Boolean) {
        map.keys.removeAll { !keep(it) }
        sum = map.values.sumOf { it.w }
    }

    fun scale(factor: Double) { for (u in map.values) u.w *= factor; sum *= factor }
}

internal object Interpolation {
    /** [base] mixed with the counts of one context, Witten-Bell style: the context's own [uses] (of [total]
     * recorded there, [types] different words, [evidence] fresh uses in all) get the share
     * `evidence / (evidence + k * types + prior)`. A context seen a few times, or followed by something new
     * every time, leaves [base] nearly as it was; one seen often with the same few followers wins. The
     * [prior] is how much the model's own say is worth in uses, so a store of a few words, whose shares
     * are all large, still sounds like the model. */
    fun blend(base: Double, uses: Double, total: Double, evidence: Double, types: Int, k: Double, prior: Double): Double {
        if (total <= 0.0 || types == 0) return base
        val share = evidence / (evidence + k * types + prior)
        return share * (uses / total) + (1 - share) * base
    }
}
