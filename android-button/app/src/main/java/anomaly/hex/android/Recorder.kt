package anomaly.hex.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Records 16 kHz mono 16-bit PCM, the rate Hex's models take, so the
 * computer never resamples. Reports the peak of each 20 ms chunk, and
 * stops by itself at the most the computer accepts (about 8.7 minutes),
 * calling [onFull] from the recording thread so the caller can send it.
 */
class Recorder(private val onLevel: (Float) -> Unit, private val onFull: () -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream(SAMPLE_RATE * 2 * 30)

    @Volatile
    private var running = false

    /** Starts recording; false when the microphone can't be opened. */
    @SuppressLint("MissingPermission") // The caller checks RECORD_AUDIO.
    fun start(): Boolean {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minimum <= 0) return false
        val audio = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, minimum * 4)
        } catch (_: Exception) {
            return false
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return false
        }
        try {
            audio.startRecording()
        } catch (_: IllegalStateException) {
            audio.release()
            return false
        }
        pcm.reset()
        record = audio
        running = true
        thread = Thread({ pump(audio) }, "hex-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun pump(audio: AudioRecord) {
        val chunk = ShortArray(SAMPLE_RATE / 50)
        val bytes = ByteArray(chunk.size * 2)
        while (running) {
            val read = audio.read(chunk, 0, chunk.size)
            if (read <= 0) continue
            var peak = 0
            for (i in 0 until read) {
                val sample = chunk[i].toInt()
                peak = maxOf(peak, abs(sample))
                bytes[i * 2] = (sample and 0xFF).toByte()
                bytes[i * 2 + 1] = (sample shr 8 and 0xFF).toByte()
            }
            val full = synchronized(pcm) {
                pcm.write(bytes, 0, minOf(read * 2, MAX_PCM_BYTES - pcm.size()))
                pcm.size() >= MAX_PCM_BYTES
            }
            onLevel(peak / 32768f)
            if (full) {
                running = false
                onFull()
            }
        }
    }

    /** Stops and returns the recording as a WAV, or null when shorter than [MIN_MS]. */
    fun stop(): ByteArray? {
        halt()
        val samples = synchronized(pcm) { pcm.toByteArray() }
        if (samples.size < SAMPLE_RATE * 2 * MIN_MS / 1000) return null
        return wav(samples, SAMPLE_RATE)
    }

    fun cancel() {
        halt()
        synchronized(pcm) { pcm.reset() }
    }

    private fun halt() {
        running = false
        record?.let { runCatching { it.stop() } }
        thread?.join(500)
        record?.release()
        record = null
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_MS = 300
        /** The computer's 16 MiB upload limit, less the WAV header. */
        const val MAX_PCM_BYTES = 16 * 1024 * 1024 - 44
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}

/** A canonical 44-byte-header WAV around mono 16-bit little-endian PCM. */
fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val out = ByteArray(44 + pcm.size)
    fun ascii(at: Int, text: String) = text.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
    fun int32(at: Int, value: Int) = repeat(4) { out[at + it] = (value shr (8 * it) and 0xFF).toByte() }
    fun int16(at: Int, value: Int) = repeat(2) { out[at + it] = (value shr (8 * it) and 0xFF).toByte() }
    ascii(0, "RIFF")
    int32(4, 36 + pcm.size)
    ascii(8, "WAVE")
    ascii(12, "fmt ")
    int32(16, 16)
    int16(20, 1) // PCM
    int16(22, 1) // mono
    int32(24, sampleRate)
    int32(28, sampleRate * 2)
    int16(32, 2)
    int16(34, 16)
    ascii(36, "data")
    int32(40, pcm.size)
    pcm.copyInto(out, 44)
    return out
}
