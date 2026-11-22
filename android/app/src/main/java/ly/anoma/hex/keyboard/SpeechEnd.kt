package ly.anoma.hex.keyboard

/**
 * Decides when a tapped dictation is over, from the peak of each 20 ms chunk: once the person has
 * spoken (300 ms of chunks above the room's noise), [QUIET_MS] of quiet ends it. A recording that
 * never hears speech ends after [NO_SPEECH_MS], and the computer decides there was nothing to type.
 *
 * The noise floor starts at the quietest of the first 200 ms and then follows the quiet between words,
 * so a fan or street raises the bar for speech instead of keeping the recording open.
 */
internal class SpeechEnd {
    private var floor = 0f
    private var spokenMs = 0
    private var quietMs = 0
    private var elapsedMs = 0

    /** Feeds one chunk; true once the recording should stop. */
    fun feed(peak: Float, chunkMs: Int = 20): Boolean {
        elapsedMs += chunkMs
        // The first moments after the tap hear the room, not the voice: the quietest of them is the floor.
        if (elapsedMs <= CALIBRATE_MS) {
            floor = if (elapsedMs == chunkMs) peak else minOf(floor, peak)
            return false
        }
        val speech = peak > maxOf(MIN_SPEECH, floor * 3f)
        // Falls fast; rises slowly with the quiet, and barely with speech, so talking never lifts it to the voice.
        val rate = if (peak < floor) 0.3f else if (speech) 0.0005f else 0.01f
        floor += (peak - floor) * rate
        if (speech) {
            spokenMs += chunkMs
            quietMs = 0
        } else {
            quietMs += chunkMs
        }
        return if (spokenMs >= SPOKEN_MS) quietMs >= QUIET_MS else elapsedMs >= NO_SPEECH_MS
    }

    companion object {
        const val MIN_SPEECH = 0.04f
        const val CALIBRATE_MS = 200
        const val SPOKEN_MS = 300
        const val QUIET_MS = 1_200
        const val NO_SPEECH_MS = 8_000
    }
}
