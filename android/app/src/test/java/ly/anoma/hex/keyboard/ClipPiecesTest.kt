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
}
