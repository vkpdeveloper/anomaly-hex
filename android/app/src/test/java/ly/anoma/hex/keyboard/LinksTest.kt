package ly.anoma.hex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    @Test fun campaignTagsAndClickIdsGoAndTheRestStaysInOrder() {
        assertEquals("https://shop.example/p?id=7&size=m#reviews",
            Links.cleanLink("https://shop.example/p?utm_source=x&id=7&fbclid=abc&size=m&utm_medium=y#reviews"))
        assertEquals("https://example.com/a", Links.cleanLink("https://example.com/a?gclid=1&utm_campaign=z"))
    }

    @Test fun siteTagsGoOnlyOnTheirSite() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=42", Links.cleanLink("https://youtu.be/dQw4w9WgXcQ?si=Ab12&t=42"))
        assertEquals("https://x.com/hex/status/1", Links.cleanLink("https://x.com/hex/status/1?s=20&t=abc"))
        // Elsewhere "s" and "t" can be a search or a time.
        assertEquals("https://search.example/?s=cats&t=2", Links.cleanLink("https://search.example/?s=cats&t=2"))
        assertEquals("https://open.spotify.com/track/1", Links.cleanLink("https://open.spotify.com/track/1?si=9f"))
    }

    @Test fun amazonLosesItsReferralPathAndTags() {
        assertEquals("https://www.amazon.co.uk/dp/B0TEST",
            Links.cleanLink("https://www.amazon.co.uk/dp/B0TEST/ref=sr_1_1?crid=2&tag=aff-21"))
        assertEquals("https://amazon.in/dp/B1?keywords=pen", Links.cleanLink("https://amazon.in/dp/B1?keywords=pen&pd_rd_w=x"))
    }

    @Test fun textAroundLinksIsLeftAlone() {
        assertEquals("Look: https://example.com/a?id=1. And https://youtu.be/v!",
            Links.clean("Look: https://example.com/a?id=1&utm_source=chat. And https://youtu.be/v?si=q!"))
        assertEquals("no links here, utm_source=x", Links.clean("no links here, utm_source=x"))
    }

    @Test fun aCopyThatIsOneLinkHasItsHost() {
        assertEquals("https://github.com/anomalyco", Links.only("  https://github.com/anomalyco  "))
        assertEquals("github.com", Links.host("https://www.github.com/anomalyco"))
        assertEquals("example.co.uk", Links.host("http://user@m.example.co.uk:8080/x"))
        assertNull(Links.only("see https://github.com"))
        assertNull(Links.only("https://"))
    }
}
