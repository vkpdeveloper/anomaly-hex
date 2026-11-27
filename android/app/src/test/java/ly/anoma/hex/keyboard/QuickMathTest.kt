package ly.anoma.hex.keyboard

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickMathTest {
    private val us = Locale.US

    @Test fun aSumBecomesItsAnswer() {
        assertEquals(StripAction.Insert("= 540", 5, "540"), QuickMath.offer("45*12", us))
        assertEquals(StripAction.Insert("= 14", 10, "14 "), QuickMath.offer("total 2 + 3 × 4 ", us))
        assertEquals("= 2,500.5", QuickMath.offer("(1000+4001)/2", us)?.label)
        assertEquals("= 18", QuickMath.offer("120*15%", us)?.label)
    }

    @Test fun anEqualsSignKeepsTheSumAndAddsTheAnswer() {
        assertEquals(StripAction.Insert("= 540", 0, "540"), QuickMath.offer("45*12=", us))
    }

    @Test fun datesPhoneNumbersAndPlainNumbersAreNotSums() {
        assertNull(QuickMath.offer("23/11", us))
        assertNull(QuickMath.offer("2024-11-23", us))
        assertNull(QuickMath.offer("call 98765-43210", us))
        assertNull(QuickMath.offer("540", us))
        assertNull(QuickMath.offer("4/0", us))
        assertNull(QuickMath.offer("see you at 5", us))
    }

    @Test fun anAmountFinishedWithASpaceIsConverted() {
        assertNull(QuickMath.offer("250 usd", us))
        assertEquals(StripAction.Insert("≈ €230", 0, "(≈ €230) "), QuickMath.offer("250 usd ", us))
        assertEquals("≈ $298", QuickMath.offer("it was 25,000 rupees ", us)?.label)
        assertEquals("≈ $11.90", QuickMath.offer("₹1000 ", us)?.label)
        assertEquals("≈ 5 mi", QuickMath.offer("ran 8 km ", us)?.label)
        assertEquals("≈ 86 °F", QuickMath.offer("30°c ", us)?.label)
        assertNull(QuickMath.offer("8 apples ", us))
    }
}
