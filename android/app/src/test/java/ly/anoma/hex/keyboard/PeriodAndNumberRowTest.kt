package ly.anoma.hex.keyboard

import ly.anoma.hex.speech.Spoken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The period key's held punctuation, and how Keyboard > Number row sizes the keys. */
class PeriodAndNumberRowTest {
    @Test fun heldPeriodOffersGboardsPunctuationWithTheCommaOverTheKey() {
        assertEquals(listOf(listOf(";", "/", "(", ")", "#", "!", ",", "?"), listOf("&", "%", "+", "\"", "-", ":", "'", "@")),
            LetterFeedback.periodHolds)
        assertEquals(",", LetterFeedback.periodHolds[0][LetterFeedback.PERIOD_DEFAULT])
        // No period in the list: lifting on the key itself types it.
        assertEquals(16, LetterFeedback.periodHolds.flatten().toSet().size)
    }

    @Test fun aPickListChoosesNothingOverTheHeldKeyAndTheOptionUnderTheFingerElsewhere() {
        val sizes = listOf(8, 8)
        // The key from x 300 to 340 below y 500; the options from x 100, 40 px each, rows 50 px up from y 490.
        fun at(x: Float, y: Float) = LetterFeedback.pickAt(x, y, 300, 340, 500, 100, 490, 40, 50, sizes)
        assertNull(at(320f, 520f))                     // still on the period
        assertNull(at(301f, 500f))
        assertEquals(0 to 5, at(320f, 495f))           // just above it: the first row, over the key
        assertEquals(0 to 6, at(345f, 470f))
        assertEquals(1 to 6, at(345f, 420f))           // a row higher
        assertEquals(1 to 7, at(900f, 10f))            // past the list's top right corner: its last option
        assertEquals(0 to 0, at(20f, 520f))            // level with the key but off it, past the left end
    }

    private fun page(symbols: Boolean = false, numpad: Boolean = false) =
        KeyboardPage(Spoken.English, symbols, more = false, shifted = false, locked = false, enter = EnterKey(null, EnterKey.RETURN), numpad = numpad)

    @Test fun theNumberRowAddsARowOverTheLettersWithoutMovingThem() {
        val off = PlaneRows(row = 177, last = 180, numberRow = false)
        val on = off.copy(numberRow = true)
        val letters = page()
        // Touches, glides and the space bar are measured from the letter rows' top: with the number row on
        // those rows keep their height, and so every key centre and the space bar's place in them.
        assertEquals(0, off.numbers(letters))
        assertEquals(177, on.numbers(letters))
        assertEquals(off.rows(letters), on.rows(letters))
        assertEquals(177 * 3, on.rows(letters))
        assertEquals(off.height + 177, on.height)
    }

    @Test fun everyPageIsAsTallSoChangingPagesNeverMovesTheEditor() {
        for (plane in listOf(PlaneRows(177, 180, numberRow = false), PlaneRows(177, 180, numberRow = true))) {
            val letters = page()
            // The symbol pages and the number pad have no number row; their three rows take its height.
            for (p in listOf(page(symbols = true), page(symbols = true, numpad = true))) {
                assertEquals(0, plane.numbers(p))
                assertEquals(plane.numbers(letters) + plane.rows(letters), plane.rows(p))
            }
        }
    }
}
