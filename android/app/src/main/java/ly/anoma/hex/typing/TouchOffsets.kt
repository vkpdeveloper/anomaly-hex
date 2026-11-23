package ly.anoma.hex.typing

import kotlin.math.hypot

/** Where your thumbs actually land: for each letter key, a decayed mean of tap minus key centre in units
 * of key pitch (so it survives a size or orientation change), learned from the words you finish
 * (Gboard's spatial model personalization). The decoder aims each key at its centre plus this offset
 * ([personalised]). A key moves only after [MIN_WEIGHT] recent taps and never more than [MAX_OFFSET]
 * pitches; old taps decay, so it follows your grip and no long tap history can be rebuilt from it.
 *
 * Each key also keeps how spread out its taps are (a decayed variance about that mean). [spread] is the
 * key's spread against the average of the keys that have one, from [MIN_SPREAD_WEIGHT] taps, held within
 * [MIN_SPREAD]..[MAX_SPREAD]: a key hit cleanly is trusted more, one often missed less. Only the relative
 * spread counts, so the balance between the taps and the word list stays as tuned. */
internal class TouchOffsets {
    /** [moment] is the decayed sum of squared offsets over the [spreadWeight] taps that have counted for
     * spread (fewer than [weight] for a file written before spread was kept). */
    private class Stat(var weight: Double = 0.0, var x: Double = 0.0, var y: Double = 0.0,
                       var spreadWeight: Double = 0.0, var moment: Double = 0.0)
    private val stats = HashMap<Char, Stat>()
    @Volatile private var offsets: Map<Char, KeyPoint> = emptyMap()
    @Volatile private var spreads: Map<Char, Double> = emptyMap()
    /** Changes whenever [offset]s do, so a cached [KeyGrid] knows to be rebuilt. */
    @Volatile var version = 0
        private set

    /** [key]'s habitual miss, in pitches (zero until it is known). */
    fun offset(key: Char): KeyPoint = offsets[key.lowercaseChar()] ?: ZERO

    /** How spread out [key]'s taps are against the other keys', 1 until known: below 1 a key you hit
     * cleanly, above 1 one you often miss. */
    fun spread(key: Char): Double = spreads[key.lowercaseChar()] ?: 1.0

    /** [grid] with each key aimed at its centre plus its offset, stamped with the [version] it reads. */
    fun personalised(grid: KeyGrid): KeyGrid {
        // The stamp is read first: publishing sets the offsets before it bumps the version, so a stamp that
        // is behind its offsets only rebuilds once more, and one ahead of them can't happen.
        val stamp = version
        aimedLast?.let { if (it.source === grid && it.stamp == stamp) return it.result }
        val now = offsets
        val scales = spreads
        val result = if (now.isEmpty() && scales.isEmpty()) KeyGrid(grid.centres, grid.pitchX, grid.pitchY, grid.space, grid.centres, stamp) else {
            val aimed = grid.centres.mapValues { (c, p) ->
                val o = now[c] ?: return@mapValues p
                KeyPoint(p.x + o.x * grid.pitchX, p.y + o.y * grid.pitchY)
            }
            KeyGrid(grid.centres, grid.pitchX, grid.pitchY, grid.space, aimed, stamp, scales)
        }
        aimedLast = Aimed(grid, stamp, result)
        return result
    }
    // The last grid aimed, so asking again for the same grid hands back the same one (the decoder keeps
    // a grid's keys by identity, and every touch asks).
    private class Aimed(val source: KeyGrid, val stamp: Int, val result: KeyGrid)
    @Volatile private var aimedLast: Aimed? = null

    /** Learns from [word], finished with these [touches] (one per letter, in [grid]'s coordinates, the
     * grid they were scored on). A touch counts only when it fell within [REACH] pitches of the
     * committed letter's key, so a slip to a neighbour or a word the keyboard rewrote teaches nothing.
     * Returns whether anything was learned. */
    @Synchronized fun learn(word: String, touches: List<KeyPoint>, grid: KeyGrid): Boolean {
        if (touches.size != word.length || word.isEmpty()) return false
        var learned = false
        for ((i, ch) in word.withIndex()) {
            val c = grid.centres[ch.lowercaseChar()] ?: continue
            val dx = (touches[i].x - c.x) / grid.pitchX
            val dy = (touches[i].y - c.y) / grid.pitchY
            if (hypot(dx, dy) > REACH) continue
            val s = stats.getOrPut(ch.lowercaseChar()) { Stat() }
            s.weight = s.weight * DECAY + 1; s.x = s.x * DECAY + dx; s.y = s.y * DECAY + dy
            s.spreadWeight = s.spreadWeight * DECAY + 1; s.moment = s.moment * DECAY + dx * dx + dy * dy
            learned = true
        }
        if (learned) publish()
        return learned
    }

    /** Takes back the [learn] that just learned from [word] and [touches] on [grid], exactly: a word
     * undone at once (an autocorrection you reverted) teaches the keys nothing. */
    @Synchronized fun unlearn(word: String, touches: List<KeyPoint>, grid: KeyGrid) {
        if (touches.size != word.length || word.isEmpty()) return
        var changed = false
        for ((i, ch) in word.withIndex().reversed()) {
            val c = grid.centres[ch.lowercaseChar()] ?: continue
            val dx = (touches[i].x - c.x) / grid.pitchX
            val dy = (touches[i].y - c.y) / grid.pitchY
            if (hypot(dx, dy) > REACH) continue
            val s = stats[ch.lowercaseChar()] ?: continue
            s.weight = (s.weight - 1) / DECAY; s.x = (s.x - dx) / DECAY; s.y = (s.y - dy) / DECAY
            s.spreadWeight = maxOf(0.0, (s.spreadWeight - 1) / DECAY); s.moment = maxOf(0.0, (s.moment - dx * dx - dy * dy) / DECAY)
            if (s.weight < 1e-9) stats.remove(ch.lowercaseChar())
            changed = true
        }
        if (changed) publish()
    }

    private fun publish() {
        val next = HashMap<Char, KeyPoint>()
        for ((c, s) in stats) {
            if (s.weight < MIN_WEIGHT) continue
            val shrink = s.weight / (s.weight + SHRINK)
            var x = s.x / s.weight * shrink; var y = s.y / s.weight * shrink
            val length = hypot(x, y)
            if (length > MAX_OFFSET) { x *= MAX_OFFSET / length; y *= MAX_OFFSET / length }
            next[c] = KeyPoint(x, y)
        }
        offsets = next
        spreads = spreadsOf()
        version++
    }

    // Each key's spread (the root of its mean squared distance from its own mean tap, in pitches) against the
    // average of those known, pulled toward 1 while it rests on few taps.
    private fun spreadsOf(): Map<Char, Double> {
        val known = HashMap<Char, Double>()
        for ((c, s) in stats) {
            if (s.spreadWeight < MIN_SPREAD_WEIGHT || s.weight <= 0) continue
            val mx = s.x / s.weight; val my = s.y / s.weight
            known[c] = Math.sqrt(maxOf(s.moment / s.spreadWeight - mx * mx - my * my, 0.0))
        }
        if (known.size < 2) return emptyMap()
        val mean = known.values.average()
        if (mean <= 1e-6) return emptyMap()
        return known.mapValues { (c, sigma) ->
            val trust = stats.getValue(c).spreadWeight.let { it / (it + SPREAD_SHRINK) }
            Math.exp(Math.log(sigma / mean).coerceIn(-5.0, 5.0) * trust).coerceIn(MIN_SPREAD, MAX_SPREAD)
        }
    }

    @Synchronized fun clear() { stats.clear(); publish() }

    @Synchronized fun write(): String = buildString {
        append(HEADER).append('\n')
        for ((c, s) in stats) append(c).append('\t').append(s.weight).append('\t').append(s.x).append('\t').append(s.y)
            .append('\t').append(s.spreadWeight).append('\t').append(s.moment).append('\n')
    }

    @Synchronized fun read(text: String) {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != HEADER) return
        stats.clear()
        for (line in lines) {
            val f = line.split('\t')
            // Four fields from before spread was kept: its spread starts over.
            if ((f.size != 4 && f.size != 6) || f[0].length != 1) continue
            val w = f[1].toDoubleOrNull() ?: continue
            val x = f[2].toDoubleOrNull() ?: continue
            val y = f[3].toDoubleOrNull() ?: continue
            val sw = f.getOrNull(4)?.toDoubleOrNull() ?: 0.0
            val m = f.getOrNull(5)?.toDoubleOrNull() ?: 0.0
            if (w.isFinite() && x.isFinite() && y.isFinite() && w > 0 && sw.isFinite() && m.isFinite() && sw >= 0 && m >= 0)
                stats[f[0][0]] = Stat(minOf(w, LIMIT), x, y, minOf(sw, LIMIT), m)
        }
        publish()
    }

    companion object {
        const val FILE = "touch-offsets.tsv"
        private const val HEADER = "hex-touch 1"
        private val ZERO = KeyPoint(0.0, 0.0)
        /** A touch farther than this from its letter's key (in pitches) isn't evidence about that key. */
        const val REACH = .75
        /** Each new tap of a key counts 1 and the older ones this much: about the last 50 taps matter. */
        const val DECAY = .98
        const val MIN_WEIGHT = 8.0
        private const val SHRINK = 4.0
        /** A key's spread counts after this many recent taps, and rests on them until [SPREAD_SHRINK] more come. */
        const val MIN_SPREAD_WEIGHT = 12.0
        private const val SPREAD_SHRINK = 12.0
        /** How far a key's spread may differ from the average of the keys', as a factor. */
        const val MIN_SPREAD = .8
        const val MAX_SPREAD = 1.3
        /** The farthest a key's aim moves from its centre, in pitches. */
        const val MAX_OFFSET = .35
        /** Whether [committed] is [typed] or one slip from it, so taps are only matched to the word they typed. */
        fun alike(typed: String, committed: String) =
            typed.length == committed.length && typed.indices.count { typed[it].equals(committed[it], ignoreCase = true) } * 2 >= typed.length
        private const val LIMIT = 1 / (1 - DECAY)
    }
}
