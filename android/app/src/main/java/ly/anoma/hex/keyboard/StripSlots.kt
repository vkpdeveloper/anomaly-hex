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
     *
     * [late]: [next] has words that came after [before] showed (the next-word model's). Then no word shown
     * goes, not even one [next] dropped: the new words only fill slots left empty, and with [swap] the best
     * of them takes the place of the shown word [next] ranks lowest when it ranks above it. What was typed,
     * the fix and the likeliest word shown never go, so a word isn't swapped as it's tapped.
     */
    fun steady(before: Array<Suggestion?>, next: List<Suggestion>, late: Boolean = false, swap: Boolean = false): List<Suggestion> {
        if (next.any { it.kind == Suggestion.Kind.Phrase } || before.any { it?.kind == Suggestion.Kind.Phrase }) return next
        val wanted = if (late) kept(before, next, swap) else next
        val placed = place(wanted)
        val slots = placed.copyOf()
        settle(before, placed, slots, WORDS)
        settle(before, placed, slots, EMOJI)
        // Back to the order [place] reads, with what doesn't fit after what shows, as in [next].
        val words = (if (inOrder(wanted)) WORDS.map { slots[it] } else listOf(slots[1], slots[0], slots[2])).filterNotNull()
        val emoji = EMOJI.mapNotNull { slots[it] }
        val rest = wanted.filter { s -> slots.none { it === s } }
        return words + rest.filter { it.kind != Suggestion.Kind.Emoji } + emoji + rest.filter { it.kind == Suggestion.Kind.Emoji }
    }

    // [next]'s words for a late strip: the words [before] shows (as [next] marks them, when it has them) in
    // [next]'s order, the swapped one's place taken by the best new word, then the other new words for empty slots.
    private fun kept(before: Array<Suggestion?>, next: List<Suggestion>, swap: Boolean): List<Suggestion> {
        val words = next.filter { it.kind != Suggestion.Kind.Emoji }
        fun rank(s: Suggestion) = words.indexOfFirst { it.text == s.text }.let { if (it < 0) words.size else it }
        // The order [place] read them in, so the likeliest is the one it put in the middle.
        val order = if (before.any { it?.kind == Suggestion.Kind.Typed || it?.kind == Suggestion.Kind.Emoji }) WORDS.toList() else listOf(1, 0, 2)
        val was = order.mapNotNull { before[it] }
        val likeliest = was.firstOrNull { it.kind != Suggestion.Kind.Typed }?.text
        val shown = was.map { s -> words.firstOrNull { it.text == s.text } ?: s }.sortedBy(::rank).toMutableList()
        val fresh = words.filter { n -> shown.none { it.text == n.text } }.toMutableList()
        val weakest = shown.lastOrNull { it.text != likeliest && (it.kind == Suggestion.Kind.Word || it.kind == Suggestion.Kind.Next) }
        val best = fresh.firstOrNull()
        if (swap && weakest != null && best != null && rank(best) < rank(weakest)) {
            shown[shown.indexOf(weakest)] = best; fresh.remove(best)
        }
        return shown + fresh + next.filter { it.kind == Suggestion.Kind.Emoji }
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
