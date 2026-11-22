package ly.anoma.hex.keyboard

import ly.anoma.hex.typing.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineChipsTest {
    private val shown = mutableListOf<Pair<List<String>, List<String>>>()
    private val chips = InlineChips<String> { unpinned, pinned -> shown += unpinned to pinned }
    private val pending = mutableMapOf<Int, (String?) -> Unit>()

    /** Offers chips named "chip0", "chip1"… and keeps each one's callback, to finish in any order. */
    private fun offer(vararg pinned: Boolean) = chips.offer(pinned.toList()) { i, done -> pending[i] = done }
    private fun finish(i: Int, chip: String? = "chip$i") = pending.remove(i)!!(chip)

    @Test fun pinnedChipsGoLastAndTheRestKeepTheirOrder() {
        offer(true, false, false, true)
        // Inflation finishes out of order; nothing shows until every chip is back.
        finish(2); finish(0); finish(3)
        assertEquals(emptyList<Any>(), shown)
        finish(1)
        assertEquals(listOf(listOf("chip1", "chip2") to listOf("chip0", "chip3")), shown)
    }

    @Test fun aChipThatFailsIsLeftOut() {
        offer(false, false)
        finish(0, null); finish(1)
        assertEquals(listOf(listOf("chip1") to emptyList<String>()), shown)
    }

    @Test fun anEmptyResponseClears() {
        offer(false); finish(0)
        offer()
        assertEquals(emptyList<String>() to emptyList<String>(), shown.last())
    }

    @Test fun aNewerResponseDropsTheOlderOnesLateChips() {
        offer(false, false)
        val old = pending.toMap(); pending.clear()
        offer(false)
        finish(0, "new")
        old.values.forEach { it("old") }
        assertEquals(listOf(listOf("new") to emptyList<String>()), shown)
    }

    @Test fun onlyAWordBeingTypedTakesTheStripBack() {
        fun word(kind: Suggestion.Kind) = Suggestion("word", kind)
        assertFalse(wordInProgress(emptyList()))
        assertFalse(wordInProgress(listOf(word(Suggestion.Kind.Next), word(Suggestion.Kind.Next))))
        assertTrue(wordInProgress(listOf(word(Suggestion.Kind.Typed), word(Suggestion.Kind.Fix))))
        // The caret put in a word: its other spellings.
        assertTrue(wordInProgress(listOf(word(Suggestion.Kind.Word))))
    }

    @Test fun aClearForAnotherFieldDropsChipsStillInflating() {
        offer(false)
        chips.clear()
        finish(0)
        assertEquals(listOf(emptyList<String>() to emptyList<String>()), shown)
    }
}
