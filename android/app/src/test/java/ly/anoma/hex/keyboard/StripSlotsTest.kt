package ly.anoma.hex.keyboard

import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.Suggestion.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** A strip worked out again for the same text keeps each word that already showed in its slot. */
class StripSlotsTest {
    private fun typed(t: String) = Suggestion(t, Kind.Typed)
    private fun word(t: String) = Suggestion(t, Kind.Word)
    private fun fix(t: String) = Suggestion(t, Kind.Fix)
    private fun emoji(t: String) = Suggestion(t, Kind.Emoji)
    private fun Array<Suggestion?>.texts() = map { it?.let { s -> if (s.kind == Kind.Fix) "*${s.text}" else s.text } }

    private fun again(before: List<Suggestion>, next: List<Suggestion>): List<String?> {
        val steady = StripSlots.steady(StripSlots.place(before), next)
        // What shows is what [next] alone would show, only placed differently.
        assertEquals(StripSlots.place(next).filterNotNull().toSet(), StripSlots.place(steady).filterNotNull().toSet())
        return StripSlots.place(steady).texts()
    }

    @Test fun onlyTheSlotWhoseWordWentChanges() {
        assertEquals(listOf("so", "some", "society", null, null),
            again(listOf(typed("so"), word("some"), word("social")), listOf(typed("so"), word("society"), word("some"))))
    }

    @Test fun aWordThatBecomesTheFixTurnsBoldWhereItIs() {
        assertEquals(listOf("som", "some", "*something", null, null),
            again(listOf(typed("som"), word("some"), word("something")), listOf(typed("som"), fix("something"), word("some"))))
    }

    @Test fun wordsKeepTheirSlotsWhenEmojiJoin() {
        // Without typed or emoji the likeliest word is in the middle; with emoji, words go in order.
        assertEquals(listOf("world", "worktree", null, "🌍", null),
            again(listOf(word("worktree"), word("world"), word("work")), listOf(word("worktree"), word("world"), word("work"), emoji("🌍"))))
    }

    @Test fun aFirstStripShowsAsGiven() {
        val next = listOf(word("this"), word("how"), word("for"))
        assertEquals(StripSlots.place(next).texts(), again(emptyList(), next))
    }
}
