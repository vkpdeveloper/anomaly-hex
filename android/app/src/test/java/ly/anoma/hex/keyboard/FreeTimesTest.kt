package ly.anoma.hex.keyboard

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTimesTest {
    // Monday 23 November 2026.
    private val monday: LocalDate = LocalDate.of(2026, 11, 23)
    private fun at(day: LocalDate, h: Int, m: Int = 0): LocalDateTime = day.atTime(h, m)
    private fun busy(day: LocalDate, vararg hours: Pair<Double, Double>) = hours.map { (a, b) ->
        FreeTimes.Busy(at(day, a.toInt(), ((a % 1) * 60).toInt()), at(day, b.toInt(), ((b % 1) * 60).toInt()))
    }

    @Test fun theGapsBetweenBusyTimesFromNowTillNine() {
        val day = busy(monday, 10.0 to 11.5, 12.0 to 15.0, 18.25 to 19.0)
        assertEquals(listOf(LocalTime.of(9, 0) to LocalTime.of(10, 0), LocalTime.of(11, 30) to LocalTime.of(12, 0),
            LocalTime.of(15, 0) to LocalTime.of(18, 0), LocalTime.of(19, 0) to LocalTime.of(21, 0)),
            FreeTimes.windows(day, monday, at(monday, 7)))
        assertEquals(listOf(LocalTime.of(16, 0) to LocalTime.of(18, 0), LocalTime.of(19, 0) to LocalTime.of(21, 0)),
            FreeTimes.windows(day, monday, at(monday, 15, 40)))
    }

    @Test fun timesAreSaidPlainly() {
        assertEquals("3 to 5 pm", FreeTimes.say(LocalTime.of(15, 0), LocalTime.of(17, 0), h24 = false))
        assertEquals("10 am to 12 pm", FreeTimes.say(LocalTime.of(10, 0), LocalTime.of(12, 0), h24 = false))
        assertEquals("9:30 to 11 am", FreeTimes.say(LocalTime.of(9, 30), LocalTime.of(11, 0), h24 = false))
        assertEquals("15:00 to 17:00", FreeTimes.say(LocalTime.of(15, 0), LocalTime.of(17, 0), h24 = true))
    }

    @Test fun freeWithADayAsksForThatDay() {
        val asked = ArrayList<LocalDate>()
        val chips = FreeTimes.offer("I'm free on friday ", at(monday, 20), h24 = false) { asked += it; busy(it, 9.0 to 17.0) }
        assertEquals(listOf(LocalDate.of(2026, 11, 27)), asked)
        assertEquals(listOf("5 to 9 pm"), chips.map { it.text })
    }

    @Test fun freeAloneSaysTodayElseTomorrow() {
        assertEquals("today 7:30 to 9 pm", FreeTimes.offer("I'm free ", at(monday, 19, 10), false) { emptyList() }.single().text)
        assertEquals("tomorrow 9 am to 9 pm", FreeTimes.offer("available ", at(monday, 20, 45), false) { emptyList() }.single().text)
    }

    @Test fun nothingWithoutTheCalendarOrTheWord() {
        assertTrue(FreeTimes.offer("I'm free ", at(monday, 10), false) { null }.isEmpty())
        assertTrue(FreeTimes.offer("I'm free", at(monday, 10), false) { emptyList() }.isEmpty())
        assertTrue(FreeTimes.offer("sugar free cake ", at(monday, 10), false) { emptyList() }.isEmpty())
    }
}
