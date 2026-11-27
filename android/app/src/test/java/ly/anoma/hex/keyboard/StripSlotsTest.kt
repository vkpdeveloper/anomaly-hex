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

    // The next-word model's words, late: none shown goes, so a word isn't swapped as it's tapped. Soon after the
    // strip showed, the best of them may take the weakest word's slot; never the likeliest word's or the fix's.
    private fun late(before: List<Suggestion>, next: List<Suggestion>, swap: Boolean) =
        StripSlots.place(StripSlots.steady(StripSlots.place(before), next, late = true, swap = swap)).texts()
    private fun next(t: String) = Suggestion(t, Kind.Next)

    @Test fun lateWordsChangeNoWordShown() {
        val shown = listOf(next("the"), next("a"), next("to"))
        val model = listOf(next("advice"), next("tip"), next("the"))
        // Worked out again for any other reason, the strip shows what the model says.
        assertEquals(listOf("tip", "the", "advice", null, null), again(shown, model))
        assertEquals(listOf("a", "the", "to", null, null), late(shown, model, swap = false))
        assertEquals(listOf("a", "the", "advice", null, null), late(shown, model, swap = true))
    }

    @Test fun lateWordsFillEmptySlotsAndNeverTakeTheFixOrTheLikeliest() {
        assertEquals(listOf("ka", "kar", "kal", null, null),
            late(listOf(typed("ka"), word("kar"), word("ke")), listOf(typed("ka"), word("kal"), word("kab")), swap = true))
        assertEquals(listOf("teh", "*the", "tea", null, null),
            late(listOf(typed("teh"), fix("the")), listOf(typed("teh"), fix("the"), word("tea")), swap = false))
        // The likeliest word shown stays though the model ranks it below the others.
        assertEquals(listOf("tip", "the", "to", null, null),
            late(listOf(next("the"), next("a"), next("to")), listOf(next("tip"), next("to"), next("a")), swap = true))
        // A word the late strip ranks above the new ones stays.
        assertEquals(listOf("so", "some", "soon", null, null),
            late(listOf(typed("so"), word("some"), word("soon")), listOf(typed("so"), word("soon"), word("some"), word("social")), swap = true))
    }

    @Test fun aFirstStripShowsAsGiven() {
        val next = listOf(word("this"), word("how"), word("for"))
        assertEquals(StripSlots.place(next).texts(), again(emptyList(), next))
    }
}
