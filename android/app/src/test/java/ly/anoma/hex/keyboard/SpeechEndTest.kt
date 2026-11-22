package ly.anoma.hex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechEndTest {
    /** Feeds [ms] of chunks at [peak]; the millisecond of that stretch the end came at, or null. */
    private fun SpeechEnd.run(ms: Int, peak: Float): Int? {
        for (t in 20..ms step 20) if (feed(peak)) return t
        return null
    }

    @Test fun endsAfterQuietThatFollowsSpeech() {
        val end = SpeechEnd()
        assertNull(end.run(500, 0.01f))
        assertNull(end.run(2_000, 0.3f))
        assertEquals(SpeechEnd.QUIET_MS, end.run(3_000, 0.01f))
    }

    @Test fun aPauseShorterThanTheQuietKeepsListening() {
        val end = SpeechEnd()
        assertNull(end.run(1_000, 0.3f))
        assertNull(end.run(800, 0.01f))
        assertNull(end.run(1_000, 0.3f))
        assertEquals(SpeechEnd.QUIET_MS, end.run(3_000, 0.01f))
    }

    @Test fun silenceEndsAfterTheNoSpeechLimit() {
        assertEquals(SpeechEnd.NO_SPEECH_MS, SpeechEnd().run(10_000, 0.01f))
    }

    @Test fun steadyNoiseIsNotSpeech() {
        // A loud fan from the tap on: it sets the floor, then a voice above it still counts.
        val end = SpeechEnd()
        assertNull(end.run(3_000, 0.06f))
        assertNull(end.run(1_000, 0.4f))
        assertEquals(SpeechEnd.QUIET_MS, end.run(3_000, 0.06f))
    }

    @Test fun aClickIsNotSpeech() {
        val end = SpeechEnd()
        assertNull(end.run(500, 0.01f))
        assertNull(end.run(200, 0.5f))
        assertEquals(SpeechEnd.NO_SPEECH_MS - 700, end.run(10_000, 0.01f))
    }
}
