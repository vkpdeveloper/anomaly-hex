package ly.anoma.hex.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHeardTest {
    /** Feeds [ms] of chunks at [peak]. */
    private fun VoiceHeard.run(ms: Int, peak: Float) { for (t in 20..ms step 20) feed(peak) }

    @Test fun onlyARecordingWithAVoiceInItIsHeard() {
        val room = VoiceHeard().apply { run(4_000, 0.01f) }
        assertFalse(room.heard)
        // A fan loud from the start is the room, not a voice.
        val fan = VoiceHeard().apply { run(4_000, 0.06f) }
        assertFalse(fan.heard)
        val click = VoiceHeard().apply { run(500, 0.005f); run(100, 0.5f); run(2_000, 0.005f) }
        assertFalse(click.heard)
        val quiet = VoiceHeard().apply { run(500, 0.005f); run(1_000, 0.03f) }
        assertTrue(quiet.heard)
        // A long pause to think after speaking still leaves it heard.
        val paused = VoiceHeard().apply { run(500, 0.01f); run(1_000, 0.3f); run(30_000, 0.01f) }
        assertTrue(paused.heard)
    }
}
