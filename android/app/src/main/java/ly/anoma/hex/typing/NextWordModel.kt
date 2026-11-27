package ly.anoma.hex.typing

import android.content.res.AssetManager
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The on-device next-word model (`typing/next.hexnn`, HXNN v1, built by benchmark/typing/neural): a small
 * GPT over SentencePiece word pieces, trained on English and Hinglish chat and prose. [predict] offers the
 * likeliest words after the words before the cursor that start with what is typed so far.
 *
 * Plain Kotlin on int8 weights with a float scale per row, no native code. Not thread-safe: one worker
 * owns it ([NextWords]). The words before keep their keys and values between calls, so a word typed after
 * them costs only its own pieces.
 */
internal class NextWordModel private constructor(data: ByteBuffer) {
    private class Matrix(val rows: Int, val cols: Int, val w: ByteArray, val scale: FloatArray)
    private class Layer(val ln1w: FloatArray, val ln1b: FloatArray, val qkv: Matrix, val proj: Matrix,
                        val ln2w: FloatArray, val ln2b: FloatArray, val ff1: Matrix, val ff2: Matrix)

    private val vocab: Int
    private val ctx: Int
    private val d: Int
    private val heads: Int
    private val pieces: Array<String> // each piece's text without its word-start mark
    private val ids = HashMap<String, Int>() // piece as written ("▁the") -> id, for encoding
    private val scores: FloatArray
    private val longest: Int
    private val unknownScore: Float
    private val starts: BooleanArray // the piece starts a word, or ends the message
    private val startIds: IntArray
    private val first: BooleanArray // can start a predicted word
    private val more: BooleanArray // can go on with one
    private val pos: FloatArray
    private val tok: Matrix
    private val layers: Array<Layer>
    private val lnw: FloatArray
    private val lnb: FloatArray

    init {
        val b = data.order(ByteOrder.LITTLE_ENDIAN)
        require(b.limit() > 32 && b.get(0) == 'H'.code.toByte() && b.get(1) == 'X'.code.toByte() &&
            b.get(2) == 'N'.code.toByte() && b.get(3) == 'N'.code.toByte() && b.getInt(4) == 1) { "Not an HXNN v1 model" }
        vocab = b.getInt(8); ctx = b.getInt(12); d = b.getInt(16); val count = b.getInt(20); heads = b.getInt(24)
        b.position(28)
        val pieceBytes = b.getInt()
        val raw = ByteArray(pieceBytes).also { b.get(it) }
        align(b)
        var at = 0
        pieces = Array(vocab) {
            val length = raw[at].toInt() and 255
            val text = String(raw, at + 1, length, Charsets.UTF_8)
            at += 1 + length
            text
        }
        scores = floats(b, vocab)
        val written = pieces.copyOf()
        var longestPiece = 1
        for (i in 0 until vocab) {
            if (i > 2) { ids[written[i]] = i; longestPiece = maxOf(longestPiece, written[i].length) }
            pieces[i] = written[i].removePrefix(MARK)
        }
        longest = longestPiece
        unknownScore = scores.drop(3).minOrNull()!! - 10f
        starts = BooleanArray(vocab) { written[it].startsWith(MARK) || it == END }
        startIds = (0 until vocab).filter { starts[it] }.toIntArray()
        first = BooleanArray(vocab) { written[it].startsWith(MARK) && pieceOk(pieces[it], true) }
        more = BooleanArray(vocab) { !written[it].startsWith(MARK) && it > 2 && pieces[it].isNotEmpty() && pieceOk(pieces[it], false) }
        pos = floats(b, ctx * d)
        tok = matrix(b, vocab, d)
        layers = Array(count) {
            Layer(floats(b, d), floats(b, d), matrix(b, 3 * d, d), matrix(b, d, d), floats(b, d), floats(b, d),
                matrix(b, 4 * d, d), matrix(b, d, 4 * d))
        }
        lnw = floats(b, d); lnb = floats(b, d)
        require(b.position() == b.limit()) { "HXNN model has trailing bytes" }
    }

    /** The word pieces of normalized text (lowercase words and marks, one space apart): the highest-scoring
     * split into known pieces, as SentencePiece's unigram model splits it. */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val s = MARK + text.replace(' ', MARK[0])
        val n = s.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val piece = IntArray(n + 1)
        val length = IntArray(n + 1)
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            for (len in 1..minOf(longest, n - i)) {
                val id = ids[s.substring(i, i + len)] ?: if (len == 1) UNKNOWN else continue
                val score = best[i] + if (id == UNKNOWN) unknownScore else scores[id]
                if (score > best[i + len]) { best[i + len] = score; piece[i + len] = id; length[i + len] = len }
            }
        }
        val out = ArrayList<Int>()
        var end = n
        while (end > 0) { out.add(piece[end]); end -= length[end] }
        return out.asReversed().toIntArray()
    }

    // The words before: their pieces from the message's start (or from [windowStart] once they outgrow the
    // window), how many of them the cache holds, and the next piece's log probabilities after them.
    private var context = IntArray(0)
    private var windowStart = 0
    private var cached = 0
    private val keys = FloatArray(layers.size * ctx * d)
    private val values = FloatArray(layers.size * ctx * d)
    private var nextLogp: FloatArray? = null

    private class Hyp(val score: Double, val ids: IntArray, val text: String, val parent: Hyp?, var kv: FloatArray?)

    /**
     * The [k] likeliest words that start with [prefix] after [words] (normalized, see [NextWords.context]):
     * a beam over word pieces, each word scored as its pieces plus the chance that a new word, or the
     * message's end, follows. A hypothesis that can no longer beat the k-th word found is dropped: extending
     * one only lowers its score, so that cut changes nothing. benchmark/typing/neural/evaluate.py runs the same
     * search; the fixture test holds the two together.
     */
    fun predict(words: List<String>, prefix: String, k: Int = 6, beam: Int = BEAM, depth: Int = DEPTH): List<String> {
        val lp = prepare(words)
        val n = cached
        val firstPieces = likeliest(startIds, lp, FIRST_SCAN)
        var hyps = ArrayList<Hyp>()
        for (i in firstPieces) {
            val t = pieces[i]
            if (first[i] && (t.startsWith(prefix) || prefix.startsWith(t))) hyps.add(Hyp(lp[i].toDouble(), intArrayOf(i), t, null, FloatArray(ownSize)))
            if (hyps.size >= beam) break
        }
        val done = LinkedHashMap<String, Double>()
        for (step in 0 until depth) {
            if (hyps.isEmpty()) break
            val rows = forwardHyps(hyps, n)
            for ((j, h) in hyps.withIndex()) {
                if (h.text.isNotEmpty() && h.text.startsWith(prefix) && !h.text.endsWith('\'')) {
                    val score = h.score + boundary(rows, j)
                    done[h.text] = maxOf(done[h.text] ?: Double.NEGATIVE_INFINITY, score)
                }
            }
            val floor = if (done.size >= k) done.values.sortedDescending()[k - 1] else Double.NEGATIVE_INFINITY
            val next = ArrayList<Hyp>()
            for ((j, h) in hyps.withIndex()) {
                if (h.score <= floor) continue
                for (i in topContinuations(rows, j)) {
                    val t = h.text + pieces[i]
                    val score = h.score + rows[j * vocab + i]
                    if (more[i] && score > floor && (t.startsWith(prefix) || prefix.startsWith(t))) next.add(Hyp(score, h.ids + i, t, h, null))
                }
            }
            next.sortByDescending { it.score }
            hyps = ArrayList(next.take(beam))
            // Only the kept hypotheses get keys and values: their parent's, which the next pass extends.
            for (h in hyps) h.kv = h.parent!!.kv!!.copyOf()
        }
        return done.entries.sortedByDescending { it.value }.take(k).map { it.key }
    }

    // Brings the cache up to the pieces of `words` and returns the next piece's log probabilities.
    private fun prepare(words: List<String>): FloatArray {
        val full = intArrayOf(BOS) + encode(words.joinToString(" "))
        var common = 0
        while (common < minOf(full.size, context.size) && full[common] == context[common]) common++
        var start = windowStart
        if (common < start + cached || full.size - start > ctx - DEPTH) {
            // Edited before the cache's end, or grown past the window: keep what still matches, or start over
            // from a shorter window once it outgrows this one.
            if (full.size - start > ctx - DEPTH || common < start) {
                start = if (full.size <= ctx - DEPTH) 0 else full.size - KEEP
                cached = 0
            } else cached = common - start
            nextLogp = null
        }
        windowStart = start
        context = full
        if (cached == full.size - start) nextLogp?.let { return it }
        if (cached == full.size - start) cached-- // the last piece again, for its log probabilities
        val rows = forwardContext(full, start, cached)
        cached = full.size - start
        nextLogp = rows
        return rows
    }

    // The context's pieces from `from` on: each writes its keys and values into the cache and attends to
    // the cache up to itself. Returns the log probabilities after the last.
    private fun forwardContext(full: IntArray, start: Int, from: Int): FloatArray {
        val count = full.size - start - from
        val tokens = IntArray(count) { full[start + from + it] }
        val positions = IntArray(count) { from + it }
        return forward(tokens, positions, null, IntArray(count), lastOnly = true)
    }

    private fun forwardHyps(hyps: List<Hyp>, n: Int): FloatArray {
        val tokens = IntArray(hyps.size) { hyps[it].ids.last() }
        val slots = IntArray(hyps.size) { hyps[it].ids.size - 1 }
        val positions = IntArray(hyps.size) { n + slots[it] }
        return forward(tokens, positions, Array(hyps.size) { hyps[it].kv!! }, slots, lastOnly = false, shared = n)
    }

    private val ownSize get() = 2 * layers.size * DEPTH * d

    /**
     * One pass over a batch of pieces. Without `own`, row r is a context piece at `positions[r]` whose keys and
     * values go to that cache slot and which attends to the cache up to it. With `own`, row r is a
     * hypothesis: it attends to the first `shared` cache slots and to its own pieces' slots up to `slots[r]`.
     * Returns log probabilities: every row's, or the last row's alone.
     */
    private fun forward(tokens: IntArray, positions: IntArray, own: Array<FloatArray>?, slots: IntArray, lastOnly: Boolean, shared: Int = 0): FloatArray {
        val batch = tokens.size
        val x = FloatArray(batch * d)
        for (r in 0 until batch) {
            val t = tokens[r]; val s = tok.scale[t]; val p = positions[r] * d
            for (i in 0 until d) x[r * d + i] = tok.w[t * d + i] * s + pos[p + i]
        }
        val h = FloatArray(batch * d)
        val qkv = FloatArray(batch * 3 * d)
        val att = FloatArray(batch * d)
        val out = FloatArray(batch * d)
        val ff = FloatArray(batch * 4 * d)
        val hd = d / heads
        val scale = (1.0 / sqrt(hd.toDouble())).toFloat()
        val weights = FloatArray(ctx + DEPTH)
        for ((l, layer) in layers.withIndex()) {
            norm(x, layer.ln1w, layer.ln1b, h, batch)
            matmul(layer.qkv, h, batch, qkv)
            val half = layers.size * DEPTH * d // where a hypothesis's values start, after its keys
            for (r in 0 until batch) {
                val q = r * 3 * d
                if (own == null) {
                    val at = (l * ctx + positions[r]) * d
                    System.arraycopy(qkv, q + d, keys, at, d)
                    System.arraycopy(qkv, q + 2 * d, values, at, d)
                } else {
                    val at = (l * DEPTH + slots[r]) * d
                    System.arraycopy(qkv, q + d, own[r], at, d)
                    System.arraycopy(qkv, q + 2 * d, own[r], half + at, d)
                }
            }
            for (r in 0 until batch) {
                val q = r * 3 * d
                val sharedCount = if (own == null) positions[r] + 1 else shared
                val ownCount = if (own == null) 0 else slots[r] + 1
                val mine = own?.get(r)
                for (head in 0 until heads) {
                    val o = head * hd
                    var max = Float.NEGATIVE_INFINITY
                    for (j in 0 until sharedCount) {
                        val at = (l * ctx + j) * d + o
                        var dot = 0f
                        for (i in 0 until hd) dot += qkv[q + o + i] * keys[at + i]
                        weights[j] = dot * scale
                        if (weights[j] > max) max = weights[j]
                    }
                    for (j in 0 until ownCount) {
                        val at = (l * DEPTH + j) * d + o
                        var dot = 0f
                        for (i in 0 until hd) dot += qkv[q + o + i] * mine!![at + i]
                        weights[sharedCount + j] = dot * scale
                        if (weights[sharedCount + j] > max) max = weights[sharedCount + j]
                    }
                    val total = sharedCount + ownCount
                    var sum = 0f
                    for (j in 0 until total) { weights[j] = exp(weights[j] - max); sum += weights[j] }
                    val a = r * d + o
                    for (i in 0 until hd) att[a + i] = 0f
                    for (j in 0 until sharedCount) {
                        val w = weights[j] / sum
                        val at = (l * ctx + j) * d + o
                        for (i in 0 until hd) att[a + i] += w * values[at + i]
                    }
                    for (j in 0 until ownCount) {
                        val w = weights[sharedCount + j] / sum
                        val at = half + (l * DEPTH + j) * d + o
                        for (i in 0 until hd) att[a + i] += w * mine!![at + i]
                    }
                }
            }
            matmul(layer.proj, att, batch, out)
            for (i in x.indices) x[i] += out[i]
            norm(x, layer.ln2w, layer.ln2b, h, batch)
            matmul(layer.ff1, h, batch, ff)
            for (i in ff.indices) ff[i] = gelu(ff[i])
            matmul(layer.ff2, ff, batch, out)
            for (i in x.indices) x[i] += out[i]
        }
        val rows = if (lastOnly) 1 else batch
        val last = FloatArray(rows * d)
        System.arraycopy(x, (batch - rows) * d, last, 0, rows * d)
        val normed = FloatArray(rows * d)
        norm(last, lnw, lnb, normed, rows)
        val logits = FloatArray(rows * vocab)
        matmul(tok, normed, rows, logits)
        for (r in 0 until rows) {
            val o = r * vocab
            var max = Float.NEGATIVE_INFINITY
            for (i in 0 until vocab) if (logits[o + i] > max) max = logits[o + i]
            var sum = 0.0
            for (i in 0 until vocab) sum += exp((logits[o + i] - max).toDouble())
            val z = max + ln(sum).toFloat()
            for (i in 0 until vocab) logits[o + i] -= z
        }
        return logits
    }

    // log P(a new word or the message's end comes next).
    private fun boundary(rows: FloatArray, r: Int): Double {
        val o = r * vocab
        var max = Float.NEGATIVE_INFINITY
        for (i in startIds) if (rows[o + i] > max) max = rows[o + i]
        var sum = 0.0
        for (i in startIds) sum += exp((rows[o + i] - max).toDouble())
        return max + ln(sum)
    }

    // The 8 likeliest pieces that go on with a word, likeliest first.
    private fun topContinuations(rows: FloatArray, r: Int): IntArray {
        val o = r * vocab
        val best = IntArray(CONTINUATIONS) { -1 }
        val values = FloatArray(CONTINUATIONS) { Float.NEGATIVE_INFINITY }
        for (i in 0 until vocab) {
            if (starts[i]) continue
            val v = rows[o + i]
            if (v <= values[CONTINUATIONS - 1]) continue
            var at = CONTINUATIONS - 1
            while (at > 0 && values[at - 1] < v) { values[at] = values[at - 1]; best[at] = best[at - 1]; at-- }
            values[at] = v; best[at] = i
        }
        return best.filter { it >= 0 }.toIntArray()
    }

    // y[b] = W x[b] for each of `batch` vectors, reading each weight once for four vectors at a time.
    private fun matmul(m: Matrix, x: FloatArray, batch: Int, y: FloatArray) {
        val w = m.w; val cols = m.cols; val rows = m.rows
        for (o in 0 until rows) {
            val base = o * cols
            val s = m.scale[o]
            var b = 0
            while (b + 4 <= batch) {
                val x0 = b * cols; val x1 = x0 + cols; val x2 = x1 + cols; val x3 = x2 + cols
                var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
                for (i in 0 until cols) {
                    val wi = w[base + i].toFloat()
                    a0 += wi * x[x0 + i]; a1 += wi * x[x1 + i]; a2 += wi * x[x2 + i]; a3 += wi * x[x3 + i]
                }
                y[b * rows + o] = a0 * s; y[(b + 1) * rows + o] = a1 * s; y[(b + 2) * rows + o] = a2 * s; y[(b + 3) * rows + o] = a3 * s
                b += 4
            }
            while (b < batch) {
                val x0 = b * cols
                var a = 0f
                for (i in 0 until cols) a += w[base + i] * x[x0 + i]
                y[b * rows + o] = a * s
                b++
            }
        }
    }

    private fun norm(x: FloatArray, weight: FloatArray, bias: FloatArray, out: FloatArray, batch: Int) {
        for (r in 0 until batch) {
            val o = r * d
            var mean = 0f
            for (i in 0 until d) mean += x[o + i]
            mean /= d
            var variance = 0f
            for (i in 0 until d) { val c = x[o + i] - mean; variance += c * c }
            val inv = 1f / sqrt(variance / d + 1e-5f)
            for (i in 0 until d) out[o + i] = (x[o + i] - mean) * inv * weight[i] + bias[i]
        }
    }

    companion object {
        private const val MARK = "▁"
        private const val UNKNOWN = 0
        private const val BOS = 1
        private const val END = 2
        /** The search the phone runs: within 1.5 points of a beam of 12 over 5 pieces on every test set
         * (benchmark/typing/README.md), at a tenth of the work. Most words are one or two pieces. */
        const val BEAM = 4
        const val DEPTH = 2
        private const val CONTINUATIONS = 8
        private const val FIRST_SCAN = 400
        // Pieces kept when the words before outgrow the window: the cache then refills only every few words.
        private const val KEEP = 24
        // The `n` ids with the highest `scores[id]`, highest first, ties in `ids` order (as a stable sort gives),
        // without sorting all of `ids`.
        private fun likeliest(ids: IntArray, scores: FloatArray, n: Int): List<Int> {
            if (ids.size <= n) return ids.sortedByDescending { scores[it] }
            val sorted = FloatArray(ids.size) { scores[ids[it]] }.also { it.sort() }
            val cut = sorted[ids.size - n]
            var above = 0
            for (i in ids) if (scores[i] > cut) above++
            val kept = ArrayList<Int>(n)
            var ties = n - above
            for (i in ids) {
                val v = scores[i]
                if (v > cut) kept.add(i) else if (v == cut && ties > 0) { kept.add(i); ties-- }
            }
            return kept.sortedByDescending { scores[it] }
        }

        // Letters, and an apostrophe after a word's first letter ("don't").
        private fun pieceOk(t: String, starting: Boolean) =
            t.all { it.isLetter() || it == '\'' } && !(starting && t.startsWith('\''))

        // GELU with erf (Abramowitz and Stegun 7.1.26, error under 1.5e-7), as PyTorch's nn.GELU computes it.
        private fun gelu(x: Float): Float {
            val z = abs(x) / 1.4142135f
            val t = 1f / (1f + 0.3275911f * z)
            val e = 1f - (((((1.0614054f * t - 1.4531521f) * t) + 1.4214138f) * t - 0.28449672f) * t + 0.2548296f) * t * exp(-z * z)
            return 0.5f * x * (1f + if (x < 0) -e else e)
        }

        private fun align(b: ByteBuffer) { b.position((b.position() + 3) and 3.inv()) }
        private fun floats(b: ByteBuffer, n: Int): FloatArray {
            val a = FloatArray(n)
            b.asFloatBuffer().get(a)
            b.position(b.position() + 4 * n)
            return a
        }
        private fun matrix(b: ByteBuffer, rows: Int, cols: Int): Matrix {
            val w = ByteArray(rows * cols).also { b.get(it) }
            align(b)
            return Matrix(rows, cols, w, floats(b, rows))
        }

        private fun mapped(channel: FileChannel, offset: Long, size: Long): ByteBuffer {
            require(size in 32L..64_000_000L)
            return channel.map(FileChannel.MapMode.READ_ONLY, offset, size)
        }
        fun asset(assets: AssetManager): NextWordModel = assets.openFd("typing/next.hexnn").use { fd ->
            NextWordModel(FileInputStream(fd.fileDescriptor).use { mapped(it.channel, fd.startOffset, fd.declaredLength) })
        }
        fun file(file: File): NextWordModel = NextWordModel(FileInputStream(file).use { mapped(it.channel, 0, file.length()) })
    }
}
