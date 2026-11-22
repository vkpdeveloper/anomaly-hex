package ly.anoma.hex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code a text carries ([OneTimeCode.find]), and which links count as short ([ShortLinks.isShort]). */
class OneTimeCodeTest {
    @Test fun theCodeNearestItsWords() {
        assertEquals("482913", OneTimeCode.find("482913 is your Link verification code. Don't share it."))
        assertEquals("123456", OneTimeCode.find("G-123456 is your Google verification code."))
        assertEquals("482913", OneTimeCode.find("Your code: 482 913"))
        assertEquals("7316", OneTimeCode.find("Use OTP 7316 to log in. Valid for 10 mins."))
        assertEquals("552190", OneTimeCode.find("आपका ओटीपी 552190 है"))
    }

    @Test fun amountsAccountsTimesAndPhoneNumbersArentCodes() {
        assertEquals("904417", OneTimeCode.find("OTP for txn of Rs 5000 on card XX1234 is 904417. Do not share."))
        assertEquals("904417", OneTimeCode.find("INR 1,250.00 at 10:30 on 22/11/2026: your OTP is 904417"))
        assertNull(OneTimeCode.find("Call +91 98765 43210 for your security"))
        assertNull(OneTimeCode.find("Your order 5531 has shipped"))
        assertNull(OneTimeCode.find("Your verification is complete"))
    }

    @Test fun shortLinksAreKnownByTheirShortener() {
        assertTrue(ShortLinks.isShort("https://amzn.to/3xYz"))
        assertTrue(ShortLinks.isShort("http://bit.ly/abc"))
        assertTrue(ShortLinks.isShort("https://maps.app.goo.gl/xyz"))
        assertFalse(ShortLinks.isShort("https://www.amazon.in/dp/B0"))
        assertFalse(ShortLinks.isShort("https://youtu.be/dQw4w9WgXcQ"))
    }
}
