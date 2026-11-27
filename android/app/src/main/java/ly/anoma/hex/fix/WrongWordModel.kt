package ly.anoma.hex.fix

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** tinyfix's wrong-word model (`wrong-word.onnx`), run in Kotlin instead of ONNX Runtime: the file's one
 * graph is a fixed pair of quantized GRUs and two layers, so its weights are read and the same math is
 * done here. Integer matrix products over uint8 activations quantized per tensor (DynamicQuantizeLinear,
 * then MatMulInteger), as ONNX Runtime does them, so scores match its to float rounding. */
internal class WrongWordModel(bytes: ByteArray) {
    companion object {
        const val LEFT = 16
        const val RIGHT = 4
        const val CANDIDATES = 9
        private const val EMBED = 64
        private const val HIDDEN = 256
    }

    /** A uint8 weight matrix [rows × cols] in row-major order, stored with its zero point taken off. */
    private class Linear(val weights: ShortArray, val scale: Float, val rows: Int, val cols: Int, val bias: FloatArray)

    private class Gru(val reset: Linear, val update: Linear, val input: Linear, val state: Linear)

    private val embedding: ByteArray
    private val embeddingScale: Float
    private val embeddingZero: Int
    private val wordBias: FloatArray
    private val typedBonus: FloatArray
    private val left: Gru
    private val right: Gru
    private val mix: Linear
    private val project: Linear
    val vocabularySize: Int

    init {
        val tensors = Onnx.initializers(bytes)
        fun tensor(name: String) = tensors[name] ?: throw IllegalArgumentException("wrong-word.onnx has no $name")
        fun floats(name: String) = tensor(name).floats()
        fun scale(name: String) = floats("${name}_scale").single()
        fun zero(name: String) = tensor("${name}_zero_point").ints().single()
        fun linear(name: String, bias: String = "$name.bias"): Linear {
            val q = tensor("$name.weight_quantized")
            val zp = zero("$name.weight")
            val raw = q.raw
            return Linear(ShortArray(raw.size) { ((raw[it].toInt() and 0xff) - zp).toShort() }, scale("$name.weight"),
                q.dims[0].toInt(), q.dims[1].toInt(), floats(bias))
        }
        fun gru(side: String) = Gru(linear("$side.reset_gate"), linear("$side.update_gate"),
            linear("$side.proposal_input"), linear("$side.proposal_state"))

        val emb = tensor("embedding.weight_quantized")
        require(emb.dims.toList() == listOf(emb.dims[0], EMBED.toLong())) { "unexpected embedding shape" }
        vocabularySize = emb.dims[0].toInt()
        embedding = emb.raw
        embeddingScale = scale("embedding.weight")
        embeddingZero = zero("embedding.weight")
        val biasQ = tensor("word_bias.weight_quantized").raw
        val biasScale = scale("word_bias.weight")
        val biasZero = zero("word_bias.weight")
        wordBias = FloatArray(biasQ.size) { ((biasQ[it].toInt() and 0xff) - biasZero) * biasScale }
        typedBonus = floats("onnx::Add_480")
        require(typedBonus.size == CANDIDATES) { "unexpected candidate count" }
        left = gru("left")
        right = gru("right")
        mix = linear("mix")
        project = linear("project")
    }

    /** Scores each row of [CANDIDATES] token ids against one context: [leftIds] (16 ids, padded at the
     * front) and [rightIds] (4 ids, nearest last, padded at the front). The model quantizes over the whole
     * batch, but every row shares the context, so the summary is worked out once. */
    fun run(leftIds: IntArray, rightIds: IntArray, rows: List<IntArray>): FloatArray {
        require(leftIds.size == LEFT && rightIds.size == RIGHT)
        val context = gru(leftIds, left) + gru(rightIds, right)
        val hidden = linear(context, mix)
        for (i in hidden.indices) hidden[i] = tanh(hidden[i])
        val summary = linear(hidden, project)
        val scores = FloatArray(rows.size * CANDIDATES)
        val vector = FloatArray(EMBED)
        rows.forEachIndexed { row, ids ->
            require(ids.size == CANDIDATES)
            for (c in 0 until CANDIDATES) {
                embed(ids[c], vector)
                var dot = 0f
                for (k in 0 until EMBED) dot += vector[k] * summary[k]
                scores[row * CANDIDATES + c] = dot + wordBias[ids[c]] + typedBonus[c]
            }
        }
        return scores
    }

    private fun embed(id: Int, out: FloatArray) {
        val base = id * EMBED
        for (k in 0 until EMBED) out[k] = ((embedding[base + k].toInt() and 0xff) - embeddingZero) * embeddingScale
    }

    private fun gru(ids: IntArray, p: Gru): FloatArray {
        var h = FloatArray(HIDDEN)
        val x = FloatArray(EMBED)
        val joined = FloatArray(EMBED + HIDDEN)
        for (id in ids) {
            embed(id, x)
            x.copyInto(joined); h.copyInto(joined, EMBED)
            // Both gates read one quantization of [x, h].
            val q = Quantized(joined)
            val r = q.times(p.reset)
            val z = q.times(p.update)
            val n = linear(x, p.input)
            val s = linear(h, p.state)
            val next = FloatArray(HIDDEN)
            for (i in 0 until HIDDEN) {
                val reset = sigmoid(r[i])
                val update = sigmoid(z[i])
                val proposal = tanh(n[i] + reset * s[i])
                next[i] = (1 - update) * proposal + update * h[i]
            }
            h = next
        }
        return h
    }

    private fun linear(x: FloatArray, layer: Linear) = Quantized(x).times(layer)

    private fun sigmoid(v: Float) = 1f / (1f + exp(-v))

    /** ONNX DynamicQuantizeLinear: uint8 values over the range of [x] widened to take in zero. */
    private class Quantized(x: FloatArray) {
        val values = IntArray(x.size)
        val scale: Float

        init {
            var hi = 0f
            var lo = 0f
            for (v in x) { hi = max(hi, v); lo = min(lo, v) }
            scale = (hi - lo) / 255f
            if (scale != 0f) {
                val zero = Math.rint((0f - lo / scale).toDouble()).coerceIn(0.0, 255.0).toInt()
                // Each value already has the zero point taken off, ready for MatMulInteger.
                for (i in x.indices) values[i] = Math.rint((x[i] / scale).toDouble()).let { (it + zero).coerceIn(0.0, 255.0).toInt() - zero }
            }
        }

        /** MatMulInteger, then the scales and the bias. */
        fun times(layer: Linear): FloatArray {
            require(values.size == layer.rows)
            val cols = layer.cols
            val acc = IntArray(cols)
            val w = layer.weights
            for (i in values.indices) {
                val a = values[i]
                if (a == 0) continue
                val base = i * cols
                for (j in 0 until cols) acc[j] += a * w[base + j]
            }
            val factor = scale * layer.scale
            return FloatArray(cols) { acc[it] * factor + layer.bias[it] }
        }
    }
}

/** Just enough of the ONNX protobuf format to read a model's initializers (its weights) by name. */
internal object Onnx {
    class Tensor(val dims: LongArray, val type: Int, val raw: ByteArray, private val floatData: FloatArray, private val intData: IntArray) {
        fun floats(): FloatArray {
            require(type == FLOAT)
            if (floatData.isNotEmpty()) return floatData
            val buffer = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(raw.size / 4) { buffer.getFloat(it * 4) }
        }
        /** Integer values, from int32_data or, for uint8, from the raw bytes. */
        fun ints(): IntArray = when {
            intData.isNotEmpty() -> intData
            type == UINT8 -> IntArray(raw.size) { raw[it].toInt() and 0xff }
            else -> throw IllegalArgumentException("unsupported tensor type $type")
        }
    }

    private const val FLOAT = 1
    private const val UINT8 = 2

    fun initializers(bytes: ByteArray): Map<String, Tensor> {
        val graph = Reader(bytes, 0, bytes.size).fields().lastOrNull { it.number == 7 }
            ?: throw IllegalArgumentException("not an ONNX model")
        val out = HashMap<String, Tensor>()
        for (field in graph.message().fields()) if (field.number == 5) tensor(field.message()).let { out[it.first] = it.second }
        return out
    }

    private fun tensor(reader: Reader): Pair<String, Tensor> {
        val dims = ArrayList<Long>()
        var type = 0
        var name = ""
        var raw = ByteArray(0)
        val floats = ArrayList<Float>()
        val ints = ArrayList<Int>()
        for (f in reader.fields()) when (f.number) {
            1 -> if (f.wire == 2) f.message().varints { dims += it } else dims += f.varint
            2 -> type = f.varint.toInt()
            4 -> if (f.wire == 2) f.message().fixed32s { floats += Float.fromBits(it) } else floats += Float.fromBits(f.varint.toInt())
            5 -> if (f.wire == 2) f.message().varints { ints += it.toInt() } else ints += f.varint.toInt()
            8 -> name = String(f.bytes(), Charsets.UTF_8)
            9 -> raw = f.bytes()
        }
        return name to Tensor(dims.toLongArray(), type, raw, floats.toFloatArray(), ints.toIntArray())
    }

    private class Field(val number: Int, val wire: Int, val varint: Long, val source: ByteArray, val start: Int, val end: Int) {
        fun message() = Reader(source, start, end)
        fun bytes() = source.copyOfRange(start, end)
    }

    private class Reader(val b: ByteArray, var at: Int, val end: Int) {
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = b[at++].toInt()
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }
        fun varints(each: (Long) -> Unit) { while (at < end) each(varint()) }
        fun fixed32s(each: (Int) -> Unit) {
            while (at + 4 <= end) {
                each((b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
                    ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24))
                at += 4
            }
        }
        fun fields(): List<Field> {
            val out = ArrayList<Field>()
            while (at < end) {
                val key = varint()
                val number = (key ushr 3).toInt()
                when (val wire = (key and 7).toInt()) {
                    0 -> out += Field(number, wire, varint(), b, 0, 0)
                    1 -> { out += Field(number, wire, 0, b, at, at + 8); at += 8 }
                    2 -> { val length = varint().toInt(); out += Field(number, wire, 0, b, at, at + length); at += length }
                    5 -> { val bits = fixed32(); out += Field(number, wire, bits.toLong() and 0xffffffffL, b, 0, 0) }
                    else -> throw IllegalArgumentException("unsupported protobuf wire type $wire")
                }
            }
            return out
        }
        private fun fixed32(): Int {
            val v = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
                ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
            at += 4
            return v
        }
    }
}
