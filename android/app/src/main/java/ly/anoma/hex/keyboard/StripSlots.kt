package ly.anoma.hex.keyboard

import ly.anoma.hex.typing.Suggestion

/** Where the strip's suggestions show: three word slots, left to right, then two emoji slots. */
internal object StripSlots {
    const val COUNT = 5
    private val WORDS = 0..2
    private val EMOJI = 3..4

    /** The typing model's slots in its order. With what was typed among them they show as given: typed
     * on the left, then the fix or the likeliest word in the middle. Without it, the likeliest word takes
     * the middle and the next the left, as Gboard places them. With emoji, two words come first, in
     * the order given, and up to two emoji after them in the third word's place. A phrase takes the
     * middle slot, at twice its width, and one word the left. */
    fun place(list: List<Suggestion>): Array<Suggestion?> {
        val shown = arrayOfNulls<Suggestion>(COUNT)
        val emoji = list.filter { it.kind == Suggestion.Kind.Emoji }.take(2)
        val words = list.filter { it.kind != Suggestion.Kind.Emoji }
        val phrase = words.firstOrNull { it.kind == Suggestion.Kind.Phrase }
        if (phrase != null) {
            shown[1] = phrase; shown[0] = words.firstOrNull { it !== phrase }
        } else if (inOrder(list)) {
            words.take(if (emoji.isEmpty()) 3 else 2).forEachIndexed { i, slot -> shown[i] = slot }
        } else { shown[1] = words.getOrNull(0); shown[0] = words.getOrNull(1); shown[2] = words.getOrNull(2) }
        emoji.forEachIndexed { i, slot -> shown[EMOJI.first + i] = slot }
        return shown
    }

    private fun inOrder(list: List<Suggestion>) =
        list.any { it.kind == Suggestion.Kind.Emoji || it.kind == Suggestion.Kind.Typed }

    /**
     * [next] in the order that keeps what [before] (as [place] put it) already showed where it was: the
     * same suggestions show as [next] alone would show, but one that was shown stays in its slot (a word
     * that became the fix turns bold where it is) and only the slots whose suggestion went take the new
     * ones. For a strip worked out again for the same text, so nothing moves while you read it.
     */
    fun steady(before: Array<Suggestion?>, next: List<Suggestion>): List<Suggestion> {
        val placed = place(next)
        if (placed.any { it?.kind == Suggestion.Kind.Phrase } || before.any { it?.kind == Suggestion.Kind.Phrase }) return next
        val slots = placed.copyOf()
        settle(before, placed, slots, WORDS)
        settle(before, placed, slots, EMOJI)
        // Back to the order [place] reads, with what doesn't fit after what shows, as in [next].
        val words = (if (inOrder(next)) WORDS.map { slots[it] } else listOf(slots[1], slots[0], slots[2])).filterNotNull()
        val emoji = EMOJI.mapNotNull { slots[it] }
        val rest = next.filter { s -> slots.none { it === s } }
        return words + rest.filter { it.kind != Suggestion.Kind.Emoji } + emoji + rest.filter { it.kind == Suggestion.Kind.Emoji }
    }

    // [placed]'s suggestions in [range] go back to the slot [before] showed them in, the others to the
    // slots left, their own first. Only the slots [placed] fills are used, so [place] can show the result.
    private fun settle(before: Array<Suggestion?>, placed: Array<Suggestion?>, slots: Array<Suggestion?>, range: IntRange) {
        val free = range.filter { placed[it] != null }.toMutableList()
        val left = ArrayList<Suggestion>()
        range.forEach { slots[it] = null }
        for (s in range.mapNotNull { placed[it] }) {
            val was = free.firstOrNull { before[it]?.text == s.text }
            if (was != null) { slots[was] = s; free.remove(was) } else left += s
        }
        for (s in left) {
            val own = range.first { placed[it] === s }
            val i = if (own in free) own else free.first()
            slots[i] = s; free.remove(i)
        }
    }
}
