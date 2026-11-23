package ly.anoma.hex.button

import ly.anoma.hex.button.ChatScreen.Piece
import ly.anoma.hex.relay.HexClient.ChatLine
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenTest {
    // A 1080-wide chat: the title bar above 200, the field from 2000.
    @Test fun rightIsMineLeftIsTheirsAndTimesAndTheTitleAreLeftOut() {
        val pieces = listOf(
            Piece("Asha", 160, 400, 80, 140),
            Piece("Today", 470, 610, 260, 300),
            Piece("are you coming tonight?", 40, 700, 400, 460),
            Piece("9:41 pm", 560, 700, 465, 490),
            Piece("yes! what time", 520, 1040, 600, 660),
            Piece("9:42 PM", 900, 1040, 665, 690),
            Piece("8ish, bring cake", 40, 520, 800, 860),
            Piece("Message", 60, 600, 2020, 2080),
        )
        assertEquals(listOf(ChatLine(false, "are you coming tonight?"), ChatLine(true, "yes! what time"),
            ChatLine(false, "8ish, bring cake")), ChatScreen.lines(pieces, 0, 1080, 200, 2000))
    }

    @Test fun onlyTheLastFifteenLines() {
        val pieces = (0 until 20).map { Piece("line $it", 40, 600, 300 + it * 80, 360 + it * 80) }
        val lines = ChatScreen.lines(pieces, 0, 1080, 200, 2000)
        assertEquals(15, lines.size)
        assertEquals("line 19", lines.last().text)
    }
}
