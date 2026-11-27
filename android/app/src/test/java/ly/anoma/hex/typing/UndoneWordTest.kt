package ly.anoma.hex.typing

import ly.anoma.hex.keyboard.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// An undone autocorrection leaves its word alone for a while, and that swap for good.
class UndoneWordTest {
    @Test fun anUndoneWordIsLeftAloneForAWeekAndItsSwapForGood() {
        var now = 1_000L
        val context = Prefs.context()
        val prefs = TypingPreferences(context) { now }
        prefs.undo("en", "Teh", "the")
        assertTrue(prefs.blocked("en", "teh", "them"))
        assertFalse(prefs.blocked("hien", "teh", "them"))
        now += TypingPreferences.UNDONE_MS - 1
        assertTrue(prefs.blocked("en", "teh", "them"))
        now += 1
        assertFalse(prefs.blocked("en", "teh", "them"))
        assertTrue(prefs.blocked("en", "teh", "the"))
        // The next undo drops word stamps that have run out.
        prefs.undo("en", "wprld", "world")
        val stamps = context.getSharedPreferences("typing", 0).all.keys.count { it?.startsWith("undone:") == true }
        assertEquals(1, stamps)
    }
}
