package ly.anoma.hex.keyboard

/**
 * Whether a recording has a voice in it, from the peak of each 20 ms chunk, so one of only the room is
 * never sent. It never ends a recording: dictation stops only when the mic is tapped again (or the finger
 * lifts from a held mic), so a pause to think never cuts it off.
 *
 * The noise floor starts at the quietest of the first 200 ms and then follows the quiet between words,
 * so a fan or street raises the bar instead of counting as a voice.
 */
internal class VoiceHeard {
    private var floor = 0f
    private var faintMs = 0
    private var elapsedMs = 0

    /** Feeds one chunk. */
    fun feed(peak: Float, chunkMs: Int = 20) {
        elapsedMs += chunkMs
        // The first moments after the tap hear the room, not the voice: the quietest of them is the floor.
        if (elapsedMs <= CALIBRATE_MS) {
            floor = if (elapsedMs == chunkMs) peak else minOf(floor, peak)
            return
        }
        val speech = peak > maxOf(MIN_SPEECH, floor * 3f)
        if (peak > maxOf(MIN_FAINT, floor * 2f)) faintMs += chunkMs
        // Falls fast; rises slowly with the quiet, and barely with speech, so talking never lifts it to the voice.
        val rate = if (peak < floor) 0.3f else if (speech) 0.0005f else 0.01f
        floor += (peak - floor) * rate
    }

    /** Some voice, even a quiet one, rose above the room for [HEARD_MS]. */
    val heard get() = faintMs >= HEARD_MS

    companion object {
        const val MIN_SPEECH = 0.04f
        /** A quiet voice, a little above a quiet room. */
        const val MIN_FAINT = 0.02f
        const val HEARD_MS = 300
        const val CALIBRATE_MS = 200
    }
}
