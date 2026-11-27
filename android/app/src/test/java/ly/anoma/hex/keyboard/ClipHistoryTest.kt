package ly.anoma.hex.keyboard

import ly.anoma.hex.keyboard.ClipHistory.Companion.KEEP_MS
import ly.anoma.hex.keyboard.ClipHistory.Companion.MAX_PINNED
import ly.anoma.hex.keyboard.ClipHistory.Companion.MAX_RECENT
import ly.anoma.hex.keyboard.ClipHistory.Companion.MAX_RECENT_IMAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clipboard history: what it keeps, for how long, in what order, and what it saves. */
class ClipHistoryTest {
    private fun text(s: String) = ClipContent.Text(s)
    private fun image(n: Int) = ClipContent.Image("%064x.png".format(n), "image/png")
    private fun ClipHistory.texts(now: Long) = list(now).map { (it.content as ClipContent.Text).text }

    @Test fun anUnpinnedClipGoesAnHourAfterItWasCopied() {
        val h = ClipHistory()
        h.add(text("code 4821"), at = 0)
        assertEquals(listOf("code 4821"), h.texts(KEEP_MS - 1))
        assertEquals(emptyList<String>(), h.texts(KEEP_MS))
        assertNull(h.nextExpiry())
    }

    @Test fun aPinnedClipStaysUntilUnpinnedAndThenGetsAFreshHour() {
        val h = ClipHistory()
        val clip = h.add(text("address"), at = 0)!!
        assertTrue(h.pin(clip.id, true, now = 0))
        assertEquals(listOf("address"), h.texts(30 * KEEP_MS))
        assertTrue(h.pin(clip.id, false, now = 30 * KEEP_MS))
        assertEquals(31 * KEEP_MS, h.nextExpiry())
        assertEquals(listOf("address"), h.texts(31 * KEEP_MS - 1))
        assertEquals(emptyList<String>(), h.texts(31 * KEEP_MS))
    }

    @Test fun pinnedClipsComeFirstThenTheNewest() {
        val h = ClipHistory()
        val old = h.add(text("old"), at = 1)!!
        h.add(text("middle"), at = 2)
        h.add(text("new"), at = 3)
        h.pin(old.id, true, now = 3)
        assertEquals(listOf("old", "new", "middle"), h.texts(4))
    }

    @Test fun copyingTheSameTextAgainMovesItsClipToTheTopStillPinned() {
        val h = ClipHistory()
        val first = h.add(text("same"), at = 1)!!
        h.pin(first.id, true, now = 1)
        h.add(text("other"), at = 2)
        val again = h.add(text("same"), at = 3)!!
        assertEquals(first.id, again.id)
        assertTrue(again.pinned)
        assertEquals(3L, again.at)
        assertEquals(2, h.list(4).size)
    }

    @Test fun noMoreThanFiftyPins() {
        val h = ClipHistory()
        repeat(MAX_PINNED) { assertTrue(h.pin(h.add(text("pin $it"), at = it.toLong())!!.id, true, now = 100)) }
        val extra = h.add(text("one more"), at = 100)!!
        assertFalse(h.pin(extra.id, true, now = 100))
        assertFalse(h.list(100).first { it.id == extra.id }.pinned)
        assertFalse(h.pin(-1, true, now = 100))
    }

    @Test fun theOldestRecentClipsGoPastTheBoundButPinnedOnesDoNot() {
        val h = ClipHistory()
        val pinned = h.add(text("pinned"), at = 0)!!
        h.pin(pinned.id, true, now = 0)
        for (i in 1..MAX_RECENT + 5) h.add(text("clip $i"), at = i.toLong())
        val texts = h.texts(100)
        assertEquals(MAX_RECENT + 1, texts.size)
        assertEquals("pinned", texts.first())
        assertEquals("clip ${MAX_RECENT + 5}", texts[1])
        assertFalse("clip 5" in texts)
        assertTrue("clip 6" in texts)
    }

    @Test fun recentImagesHaveTheirOwnSmallerBound() {
        val h = ClipHistory()
        for (i in 1..MAX_RECENT_IMAGES + 2) h.add(image(i), at = i.toLong())
        h.add(text("text"), at = 100)
        val images = h.list(200).mapNotNull { (it.content as? ClipContent.Image)?.file }
        assertEquals((MAX_RECENT_IMAGES + 2 downTo 3).map { "%064x.png".format(it) }, images)
        assertEquals(images.toSet(), h.files())
    }

    @Test fun secretBlankAndHugeCopiesAreNotKept() {
        val h = ClipHistory()
        assertNull(h.add(text("hunter2"), at = 0, secret = true))
        assertNull(h.add(text("  \n "), at = 0))
        assertNull(h.add(text("x".repeat(ClipHistory.MAX_TEXT + 1)), at = 0))
        assertEquals(emptyList<Clip>(), h.list(1))
    }

    @Test fun anImageNamedLikeAPathIsNotKept() {
        val h = ClipHistory()
        assertNull(h.add(ClipContent.Image("../shared_prefs/clipboard.xml", "image/png"), at = 0))
        assertNull(h.add(ClipContent.Image("%064x.png/../x".format(1), "image/png"), at = 0))
        assertEquals(emptySet<String>(), h.files())
    }

    @Test fun clearingKeepsPinnedClips() {
        val h = ClipHistory()
        val pinned = h.add(text("keep"), at = 0)!!
        h.pin(pinned.id, true, now = 0)
        h.add(text("go"), at = 1)
        h.add(image(1), at = 2)
        h.clearRecent()
        assertEquals(listOf("keep"), h.texts(3))
        assertEquals(emptySet<String>(), h.files())
    }

    @Test fun onlyPinnedClipsAreSavedAndTheyReadBackAsTheyWere() {
        val h = ClipHistory()
        val note = h.add(text("tab\there\nnew line, ünïcode 🙂"), at = 10)!!
        val picture = h.add(image(7), at = 20)!!
        h.add(text("not pinned"), at = 30)
        h.pin(note.id, true, now = 30); h.pin(picture.id, true, now = 30)

        val back = ClipHistory().apply { read(h.write()) }
        val clips = back.list(100 * KEEP_MS)
        assertEquals(listOf(picture.content, note.content), clips.map { it.content })
        assertEquals(listOf(20L, 10L), clips.map { it.at })
        assertTrue(clips.all { it.pinned })
    }

    @Test fun readingSkipsBrokenLinesAndImagesWhoseFileIsGone() {
        val h = ClipHistory()
        val kept = h.add(image(1), at = 1)!!; h.pin(kept.id, true, now = 1)
        val gone = h.add(image(2), at = 2)!!; h.pin(gone.id, true, now = 2)
        val saved = h.write() + "t\tnot a time\tYQ==\ni\t3\t../escape.png\timage/png\nt\t4\t%%%\nnonsense\n"
        val back = ClipHistory().apply { read(saved) { it == (kept.content as ClipContent.Image).file } }
        assertEquals(listOf(kept.content), back.list(5).map { it.content })
    }
}
