package ly.anoma.hex.keyboard

import ly.anoma.hex.keyboard.ClipPiece.Found
import ly.anoma.hex.keyboard.ClipPiece.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a copy is split once the classifier has looked ([ClipPieces.split]); the classifier itself only runs on a phone. */
class ClipPiecesTest {
    private fun found(text: String, part: String, kind: Kind) = text.indexOf(part).let { Found(it, it + part.length, kind) }

    @Test fun piecesComeInOrderEachOnce() {
        val text = "Call me on +44 20 7946 0958 before Friday 3pm, or mail sam@example.com. +44 20 7946 0958"
        val split = ClipPieces.split(text, listOf(
            found(text, "sam@example.com", Kind.Email),
            found(text, "+44 20 7946 0958", Kind.Phone),
            found(text, "Friday 3pm", Kind.Date),
        ))
        assertEquals(null, split.kind)
        assertEquals(listOf(ClipPiece(Kind.Phone, "+44 20 7946 0958"), ClipPiece(Kind.Date, "Friday 3pm")), split.pieces)
    }

    @Test fun aCopyThatIsOnePieceTakesItsKind() {
        val text = " +91 98765 43210 "
        assertEquals(ClipPiece.Split(Kind.Phone, emptyList()), ClipPieces.split(text, listOf(found(text, "+91 98765 43210", Kind.Phone))))
        // A link the classifier missed is still a link.
        assertEquals(ClipPiece.Split(Kind.Link, emptyList()), ClipPieces.split("https://example.com/a", emptyList()))
    }

    @Test fun overlapsKeepTheFirstAndPlainTextHasNone() {
        val text = "See https://maps.example/x at 10 Main St"
        val split = ClipPieces.split(text, listOf(found(text, "maps.example/x at", Kind.Address), found(text, "10 Main St", Kind.Address)))
        assertEquals(listOf(ClipPiece(Kind.Link, "https://maps.example/x"), ClipPiece(Kind.Address, "10 Main St")), split.pieces)
        assertEquals(ClipPiece.Split(null, emptyList()), ClipPieces.split("just words", emptyList()))
    }

    @Test fun aDateTheClassifierMissesIsFoundToo() {
        val text = "tomorrow text me at 9170439308"
        val split = ClipPieces.split(text, listOf(found(text, "9170439308", Kind.Phone)))
        assertEquals(listOf(ClipPiece(Kind.Date, "tomorrow"), ClipPiece(Kind.Phone, "9170439308")), split.pieces)
        // Where the classifier found the same date, it is one piece.
        val timed = "Friday at 5pm works"
        assertEquals(listOf(ClipPiece(Kind.Date, "Friday at 5pm")),
            ClipPieces.split(timed, listOf(found(timed, "Friday at 5pm", Kind.Date))).pieces)
        assertEquals(ClipPiece.Split(Kind.Date, emptyList()), ClipPieces.split("25 Nov", emptyList()))
    }

    @Test fun datesInTheirUsualShapes() {
        fun dates(text: String) = Dates.find(text).map { text.substring(it) }
        assertEquals(listOf("tomorrow"), dates("tomorrow text me at 9170439308"))
        assertEquals(listOf("today", "tonight", "day after tomorrow", "tmrw morning"), dates("today, tonight, day after tomorrow or tmrw morning"))
        assertEquals(listOf("next Friday", "Sunday evening"), dates("next Friday or Sunday evening?"))
        assertEquals(listOf("25 Nov", "25th of November 2026", "Nov 25th, 2026", "Dec. 3"), dates("25 Nov, 25th of November 2026, Nov 25th, 2026 and Dec. 3"))
        assertEquals(listOf("Friday, 25 Nov"), dates("on Friday, 25 Nov."))
        assertEquals(listOf("22/11/2026", "22-11-26", "2026-11-22"), dates("22/11/2026 22-11-26 2026-11-22"))
        assertEquals(listOf("tomorrow at 9am", "Monday 10:30 pm", "Nov 25 at 14:00"), dates("tomorrow at 9am, Monday 10:30 pm, Nov 25 at 14:00"))
    }

    @Test fun numbersAndWordsThatArentDates() {
        fun dates(text: String) = Dates.find(text).map { text.substring(it) }
        assertEquals(emptyList<String>(), dates("add 3/4 cup, version 1.2.3, order 5531, see you at 5"))
        assertEquals(emptyList<String>(), dates("Mondays, todays, sat on the sun, march on, Nov 2026, 45/12/2026"))
    }
}
