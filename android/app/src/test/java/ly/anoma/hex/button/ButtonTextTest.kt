package ly.anoma.hex.button

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonTextTest {
    @Test
    fun dictationsDoNotRunTogether() {
        assertEquals(" world", joinAtCursor("hello", "world"))
        assertEquals("world", joinAtCursor("hello ", "world"))
        assertEquals(", then", joinAtCursor("hello", ", then"))
        assertEquals("first", joinAtCursor("", "first"))
    }

    @Test
    fun placeholdersAreNotText() {
        assertEquals("", fieldText("Message", "Message", showingHint = true, cursor = 0))
        // WhatsApp-style: the hint as text, unflagged, with no cursor.
        assertEquals("", fieldText("Message", "Message", showingHint = false, cursor = -1))
        // Typed text that happens to match the hint keeps its cursor past the start.
        assertEquals("Message", fieldText("Message", "Message", showingHint = false, cursor = 7))
        assertEquals("hello", fieldText("hello", "Message", showingHint = false, cursor = -1))
    }
}
