package ly.anoma.hex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Held delete: its pace, and how a hold is told from a slide. */
class DeleteRepeatTest {
    @Test fun aHeldDeleteRepeatsCharactersThenWords() {
        val r = DeleteRepeat()
        assertEquals(KeyboardMetrics.DELETE_REPEAT_DELAY, r.start())
        assertFalse(r.repeating)
        repeat(KeyboardMetrics.DELETE_WORDS_AFTER) {
            assertFalse(r.next())
            assertEquals(KeyboardMetrics.DELETE_REPEAT_EVERY, r.delay)
        }
        assertTrue(r.repeating)
        assertTrue(r.next())
        assertEquals(KeyboardMetrics.DELETE_WORD_EVERY, r.delay)
        assertTrue(r.next())
    }

    @Test fun eachPressStartsOverWithCharacters() {
        val r = DeleteRepeat()
        r.start(); repeat(30) { r.next() }
        r.start()
        assertFalse(r.repeating)
        assertFalse(r.next())
    }

    @Test fun aRollingThumbStillHoldsButASlideDoesNot() {
        val g = DeleteGesture(start = 53f, step = 36f, cancel = 32f, stillSlop = 16f)
        g.down(100f)
        g.move(110f); g.move(92f)
        assertTrue(g.still)
        assertEquals(0, g.move(80f))
        assertFalse(g.still)
        assertTrue(g.tap)
        assertEquals(1, g.move(40f))
        assertFalse(g.tap)
    }
}
