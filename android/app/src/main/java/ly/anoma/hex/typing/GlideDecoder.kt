package ly.anoma.hex.typing

import kotlin.math.hypot
import kotlin.math.sqrt

/** Reads a glide, a finger's path across the letter keys, as the words it most likely spells.
 *
 * The path is resampled in key units (a column's pitch across, a row's pitch down), and each key
 * gets its passes: the points where the path comes closest to it. The word list is walked letter by
 * letter, each letter matched in order to a pass of its key: the first letter where the path starts,
 * the last where it ends. A letter costs how far its pass misses the key's centre; the path between
 * two matched letters costs how far it bends away from the straight line, so a corner no letter
 * explains ("te" read from t, h, e) is dear. The likeliest words then add the language model's cost
 * after the previous word, mixed with how often you write it ([LearnedWords]). Your own words are walked
 * the same way, scored as a moderately common word or likelier when you write them often, as the tap
 * decoder scores them. */
internal class GlideDecoder(private val model: TypingModel) {
    /** [text] is the model's lowercase spelling, or yours ([personal]) as you wrote it. */
    data class Candidate(val text: String, val cost: Double, val personal: Boolean)

    private class State(val lo: Int, val hi: Int, val j: Int, val key: Int, val cost: Double)

    /** The likeliest words for [path] (in [grid]'s coordinates), best first, at most [limit].
     * [previous] is the word before as the model's context index (-1 for none). */
    fun decode(path: List<KeyPoint>, grid: KeyGrid, previous: Int, swears: Boolean,
               personal: PersonalWords?, learned: LearnedWords? = null, limit: Int = 3,
               beforePrevious: String? = null, fieldWords: FieldWords? = null): List<Candidate> {
        // A path that strays far from its keys still gets an answer: read again with twice the reach.
        for (reach in REACHES) {
            val read = Reading.of(path, grid, model, reach) ?: return emptyList()
            val found = candidates(read, previous, swears, personal, learned, limit, beforePrevious, fieldWords)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun candidates(read: Reading, previous: Int, swears: Boolean, personal: PersonalWords?, learned: LearnedWords?,
                           limit: Int, beforePrevious: String?, fieldWords: FieldWords?): List<Candidate> {
        val found = HashMap<String, Candidate>()
        fun offer(text: String, cost: Double, yours: Boolean) {
            val key = text.lowercase()
            if (cost < (found[key]?.cost ?: Double.POSITIVE_INFINITY)) found[key] = Candidate(text, cost, yours)
        }
        val context = model.neverSuggest.restrictedContext(previous)
        val before = if (previous in 0 until model.size) model.text(previous) else null
        for ((word, spatial) in walk(read, model)) {
            if (model.neverSuggest.blocks(word, swears, context)) continue
            val text = model.text(word)
            val lm = model.languageCost(previous, word).let { learned?.mixed(it, text, before, beforePrevious) ?: it }
            offer(text, spatial + LM_WEIGHT * (lm - (fieldWords?.bonus(text) ?: 0.0)), false)
        }
        if (personal != null && personal.size > 0) for ((word, spatial) in walk(read, personal)) {
            val text = personal.text(word)
            val lm = minOf(USER_WORD_COST, learned?.mixed(Double.POSITIVE_INFINITY, text, before, beforePrevious) ?: USER_WORD_COST)
            offer(text, spatial + LM_WEIGHT * (lm - (fieldWords?.bonus(text) ?: 0.0)), true)
        }
        return found.values.sortedWith(compareBy<Candidate> { it.cost }.thenBy { it.text }).take(limit)
    }

    /** Every word of [lexicon] the path can spell, with its spatial cost. */
    private fun walk(read: Reading, lexicon: Lexicon): Map<Int, Double> {
        val n = read.size
        val done = HashMap<Int, Double>()
        var layer = ArrayList<State>()
        for (k in read.keys.indices) {
            val d = read.distance[k][0]
            if (d >= START_RADIUS * read.reach) continue
            for (code in read.codes[k]) {
                val child = lexicon.child(0, lexicon.size, 0, code)
                if (child != 0L) layer.add(State((child ushr 32).toInt(), child.toInt(), 0, k,
                    endCost(d) + read.accent[code]))
            }
        }
        var depth = 1
        while (layer.isNotEmpty() && depth < TypingWord.MAX_LENGTH) {
            // The same stable order sortBy gives, without boxing a cost for every comparison.
            if (layer.size > BEAM) { layer.sortWith { a, b -> java.lang.Double.compare(a.cost, b.cost) }; layer.subList(BEAM, layer.size).clear() }
            val next = HashMap<Long, State>()
            fun push(child: Long, j: Int, key: Int, cost: Double) {
                if (child == 0L) return
                val lo = (child ushr 32).toInt()
                val id = (lo.toLong() shl 16) or j.toLong()
                if (cost < (next[id]?.cost ?: Double.POSITIVE_INFINITY)) next[id] = State(lo, child.toInt(), j, key, cost)
            }
            for (s in layer) {
                // A word ends where the path does.
                if (s.j == n - 1 && lexicon.length(s.lo) == depth && s.cost < (done[s.lo] ?: Double.POSITIVE_INFINITY))
                    done[s.lo] = s.cost
                // An apostrophe costs nothing and moves nowhere: "dont" reads as "don't".
                if (model.apostrophe != 0) push(lexicon.child(s.lo, s.hi, depth, model.apostrophe), s.j, s.key, s.cost)
                for (k in read.keys.indices) {
                    val codes = read.codes[k]
                    if (k == s.key) {
                        // A double letter stays on its key: "hello", "too".
                        for (code in codes) push(lexicon.child(s.lo, s.hi, depth, code), s.j, k, s.cost + read.accent[code])
                        continue
                    }
                    val at = read.after(k, s.j)
                    if (at.isEmpty()) continue
                    for (code in codes) {
                        val child = lexicon.child(s.lo, s.hi, depth, code)
                        if (child == 0L) continue
                        for (j in at) push(child, j, k, s.cost + read.accent[code] + read.bend(s.j, j) +
                            if (j == n - 1) endCost(read.distance[k][j]) else letterCost(read.distance[k][j]))
                    }
                }
            }
            layer = ArrayList(next.values)
            depth++
        }
        for (s in layer) if (s.j == n - 1 && lexicon.length(s.lo) == depth && s.cost < (done[s.lo] ?: Double.POSITIVE_INFINITY))
            done[s.lo] = s.cost
        return done
    }

    /** The resampled path and what each key makes of it. */
    private class Reading(val xs: FloatArray, val ys: FloatArray, val keys: List<Char>,
                          val distance: Array<FloatArray>, private val passes: Array<IntArray>,
                          val codes: Array<IntArray>, val accent: DoubleArray, val reach: Float) {
        val size = xs.size
        private val bends = FloatArray(size * size) { -1f }
        // The path's length up to each point, in keys.
        private val travelled = FloatArray(size).also {
            for (i in 1 until size) it[i] = it[i - 1] + length(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        private val afters = arrayOfNulls<IntArray>(keys.size * size)

        /** Where letter key [k] may match after path point [j]: its first passes, and the path's end. */
        fun after(k: Int, j: Int): IntArray = afters[k * size + j] ?: matches(k, j).also { afters[k * size + j] = it }

        private fun matches(k: Int, j: Int): IntArray {
            val all = passes[k]
            var first = 0
            while (first < all.size && all[first] <= j) first++
            val end = size - 1
            val ends = j < end && distance[k][end] < END_RADIUS * reach
            val count = minOf(PASS_FANOUT, all.size - first)
            val hasEnd = ends && (0 until count).none { all[first + it] == end }
            if (count == 0 && !hasEnd) return EMPTY
            val out = IntArray(count + if (hasEnd) 1 else 0)
            for (i in 0 until count) out[i] = all[first + i]
            if (hasEnd) out[count] = end
            return out
        }

        /** What the path between points [a] and [b] costs for leaving the straight line between them:
         * bending away from it (a corner), or going further than it does (doubling back, "the" read
         * from t, h, e, r, e). Either means a letter the word lacks. */
        fun bend(a: Int, b: Int): Double {
            if (b - a < 2) return 0.0
            val cached = bends[a * size + b]
            if (cached >= 0) return cached.toDouble()
            var far = 0f
            for (i in a + 1 until b) far = maxOf(far, segmentDistance(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b]))
            val over = maxOf(0f, far - BEND_FREE)
            val extra = maxOf(0f, travelled[b] - travelled[a] - length(xs[b] - xs[a], ys[b] - ys[a]) - DETOUR_FREE)
            val cost = (BEND_WEIGHT * over * over + DETOUR_WEIGHT * extra).toFloat()
            bends[a * size + b] = cost
            return cost.toDouble()
        }

        companion object {
            private val EMPTY = IntArray(0)

            fun of(path: List<KeyPoint>, grid: KeyGrid, model: TypingModel, reach: Float): Reading? {
                if (path.size < 2 || grid.pitchX <= 0 || grid.pitchY <= 0) return null
                val (xs, ys) = resample(path, grid.pitchX, grid.pitchY) ?: return null
                val keys = ArrayList<Char>()
                val centres = ArrayList<KeyPoint>()
                for ((c, p) in grid.centres) {
                    val code = model.code(c)
                    if (code == 0 || code == model.apostrophe) continue
                    keys.add(c); centres.add(KeyPoint(p.x / grid.pitchX, p.y / grid.pitchY))
                }
                if (keys.isEmpty()) return null
                // Each letter's key: its own, else its base letter's (é on e), which costs a little more.
                val own = HashMap<Int, Int>()
                for ((k, c) in keys.withIndex()) own[model.code(c)] = k
                val byKey = Array(keys.size) { ArrayList<Int>() }
                val accent = DoubleArray(model.alphabet + 1)
                for (code in 1..model.alphabet) {
                    if (code == model.apostrophe) continue
                    val k = own[code] ?: own[model.bases[code]]?.also { accent[code] = ACCENT_COST } ?: continue
                    byKey[k].add(code)
                }
                val n = xs.size
                val distance = Array(keys.size) { k ->
                    val c = centres[k]
                    FloatArray(n) { j -> (xs[j] - c.x).let { dx -> (ys[j] - c.y).let { dy -> sqrt(dx * dx + dy * dy) } }.toFloat() }
                }
                return Reading(xs, ys, keys, distance, Array(keys.size) { passes(distance[it], PASS_RADIUS * reach) },
                    Array(keys.size) { byKey[it].toIntArray() }, accent, reach)
            }

            // Evenly spaced points along the path in key units, at most MAX_POINTS of them.
            private fun resample(path: List<KeyPoint>, sx: Double, sy: Double): Pair<FloatArray, FloatArray>? {
                val px = DoubleArray(path.size) { path[it].x / sx }
                val py = DoubleArray(path.size) { path[it].y / sy }
                var length = 0.0
                for (i in 1 until path.size) length += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
                if (length < MIN_LENGTH) return null
                val step = maxOf(STEP, length / (MAX_POINTS - 1))
                val count = (length / step).toInt() + 1
                val xs = FloatArray(count + 1); val ys = FloatArray(count + 1)
                xs[0] = px[0].toFloat(); ys[0] = py[0].toFloat()
                var out = 1
                var carried = 0.0
                for (i in 1 until path.size) {
                    val seg = hypot(px[i] - px[i - 1], py[i] - py[i - 1])
                    if (seg == 0.0) continue
                    var along = step - carried
                    while (along <= seg && out < count) {
                        val t = along / seg
                        xs[out] = (px[i - 1] + t * (px[i] - px[i - 1])).toFloat()
                        ys[out] = (py[i - 1] + t * (py[i] - py[i - 1])).toFloat()
                        out++; along += step
                    }
                    carried = seg - (along - step)
                }
                // The path's last point is always kept: the word's last letter matches it.
                xs[out] = px.last().toFloat(); ys[out] = py.last().toFloat(); out++
                return xs.copyOf(out) to ys.copyOf(out)
            }

            // The points where the distance to a key bottoms out within [radius]. A dip only
            // counts once the distance climbs HYSTERESIS above it, so a wobble is one pass.
            private fun passes(d: FloatArray, radius: Float): IntArray {
                val out = ArrayList<Int>()
                var falling = true
                var low = 0
                var high = 0
                for (j in 1 until d.size) {
                    if (falling) {
                        if (d[j] <= d[low]) low = j
                        else if (d[j] > d[low] + HYSTERESIS) {
                            if (d[low] < radius) out.add(low)
                            falling = false; high = j
                        }
                    } else {
                        if (d[j] >= d[high]) high = j
                        else if (d[j] < d[high] - HYSTERESIS) { falling = true; low = j }
                    }
                }
                if (falling && d[low] < radius) out.add(low)
                return out.toIntArray()
            }

            private fun segmentDistance(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
                val dx = bx - ax; val dy = by - ay
                val length = dx * dx + dy * dy
                val t = if (length == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
                return length(x - (ax + t * dx), y - (ay + t * dy))
            }

            // The distance of a float offset, as hypot gives it (rounded to a float) without hypot's
            // overflow-proof slowness: these are a few keys long, and it was a quarter of a glide's time.
            private fun length(x: Float, y: Float) = sqrt(x.toDouble() * x + y.toDouble() * y).toFloat()
        }
    }

    private companion object {
        /** Path points every fifth of a key, at most this many. */
        const val STEP = .2
        const val MAX_POINTS = 160
        /** A path shorter than this, in keys, is no glide. */
        const val MIN_LENGTH = .3
        /** How far, in keys, the path may pass a letter's key, start from the first or end from the last. */
        const val PASS_RADIUS = 1.0f
        const val START_RADIUS = 1.0f
        const val END_RADIUS = 1.0f
        val REACHES = floatArrayOf(1f, 2f)
        const val HYSTERESIS = .12f
        /** A letter's pass in its key's centre costs nothing; SIGMA keys away it costs half a nat. */
        const val SIGMA = .4
        /** The path may bend this far from a straight line between two letters for free (an arc). */
        const val BEND_FREE = .3f
        const val BEND_WEIGHT = 8.0f
        /** Path length beyond the straight line, in keys, that costs nothing (an arc), and what each key more costs. */
        const val DETOUR_FREE = .25f
        const val DETOUR_WEIGHT = 3f
        /** How many passes of a key after the last letter's are tried for the next letter. */
        const val PASS_FANOUT = 2
        const val BEAM = 400
        const val ACCENT_COST = 1.0
        const val LM_WEIGHT = 1.0
        /** One of your own words scores as a moderately common one, as `SpatialDecoder` scores it. */
        const val USER_WORD_COST = 11.0
        /** The first and last letters are where the finger went down and lifted: held to their keys more tightly. */
        const val END_SIGMA = .3
        fun letterCost(d: Float) = d * d / (2 * SIGMA * SIGMA)
        fun endCost(d: Float) = d * d / (2 * END_SIGMA * END_SIGMA)
    }
}
