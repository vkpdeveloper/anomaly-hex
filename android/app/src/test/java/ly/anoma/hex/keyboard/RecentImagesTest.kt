package ly.anoma.hex.keyboard

import ly.anoma.hex.keyboard.RecentImages.CHIP_MS
import ly.anoma.hex.keyboard.RecentImages.PANEL_MAX
import ly.anoma.hex.keyboard.RecentImages.PANEL_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which new screenshots the keyboard offers, and what the toolbar's chip shows. */
class RecentImagesTest {
    private val now = 10 * PANEL_MS
    private fun shot(id: Long, ago: Long, path: String = "Pictures/Screenshots/", mime: String = "image/png") =
        RecentImage(id, now - ago, mime, path)

    @Test fun onlyScreenshotsAreOffered() {
        val rows = listOf(
            shot(1, 0, "DCIM/Camera/", "image/jpeg"),
            shot(2, 0, "Pictures/Screenshots/"),
            shot(3, 0, "DCIM/Screenshots/"),
            shot(4, 0, "Pictures/WhatsApp/"),
            shot(5, 0, "Download/"),
            shot(6, 0, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "image/jpeg"),
            shot(7, 0, "DCIM/Camera/", "video/mp4"),
        )
        assertEquals(setOf(2L, 3L), RecentImages.select(rows, now).map { it.id }.toSet())
    }

    @Test fun thePanelShowsTheLastHourNewestFirst() {
        val rows = listOf(shot(1, PANEL_MS), shot(2, PANEL_MS - 1), shot(3, 0), shot(4, -30_000), shot(5, -10 * 60_000))
        // A clock a little behind still shows a photo; one far ahead isn't believed.
        assertEquals(listOf(4L, 3L, 2L), RecentImages.select(rows, now).map { it.id })
    }

    @Test fun thePanelShowsAtMostEight() {
        val rows = (1..PANEL_MAX + 4L).map { shot(it, ago = it * 1_000) } + shot(1, 1_000)
        assertEquals((1..PANEL_MAX.toLong()).toList(), RecentImages.select(rows, now).map { it.id })
    }

    @Test fun theChipOffersTheNewestWhileFreshAndUntilUsed() {
        val photos = listOf(shot(1, 60_000), shot(2, 10_000))
        assertEquals(2L, RecentImages.chip(photos, now, used = emptySet())?.id)
        assertNull(RecentImages.chip(photos, now, used = setOf(2L)))
        assertNull(RecentImages.chip(photos, now + CHIP_MS, used = emptySet()))
        assertNull(RecentImages.chip(emptyList(), now, used = emptySet()))
    }

    @Test fun theChipOffersTheNewerOfAFreshCopyAndAPhoto() {
        val photo = shot(1, 60_000)
        val newer = ChipOffer.Text("copied", sensitive = false, at = now - 1_000)
        val older = ChipOffer.Text("copied", sensitive = false, at = now - 120_000)
        assertEquals(newer, ChipOffer.pick(newer, photo, now) { true })
        assertEquals(ChipOffer.Photo(photo), ChipOffer.pick(older, photo, now) { true })
        assertEquals(older, ChipOffer.pick(older, null, now) { true })
        assertNull(ChipOffer.pick(older, null, now + ChipOffer.COPY_FRESH_MS) { true })
    }

    @Test fun aTextedCodeTakesTheChipWhileNewerAndFresh() {
        val copied = ChipOffer.Text("copied", sensitive = false, at = now - 120_000)
        val code = ChipOffer.Code("482913", at = now - 30_000, message = 7)
        assertEquals(code, ChipOffer.pick(copied, null, now, code) { true })
        assertEquals(copied, ChipOffer.pick(copied, null, now, code.copy(at = now - 300_000)) { true })
        assertNull(ChipOffer.pick(null, null, now + ChipOffer.COPY_FRESH_MS, code) { true })
    }

    @Test fun anUnusedOfferKnowsWhenItGoes() {
        assertEquals(CHIP_MS - 60_000, ChipOffer.Photo(shot(1, 60_000)).staleIn(now))
        assertEquals(ChipOffer.COPY_FRESH_MS - 1_000, ChipOffer.Text("copied", sensitive = false, at = now - 1_000).staleIn(now))
    }

    @Test fun aScreenshotIsOfferedOnlyWhereTheFieldTakesImages() {
        val older = ChipOffer.Text("copied", sensitive = false, at = now - 120_000)
        val photo = shot(1, 60_000)
        assertEquals(ChipOffer.Photo(photo), ChipOffer.pick(older, photo, now) { it.startsWith("image/") })
        assertEquals(older, ChipOffer.pick(older, photo, now) { false })
    }
}
