package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.EmailAddresses
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strip's email addresses, what teaches them, and text shortcuts, through the engine. */
class AddressesAndShortcutsTest {
    private val grid = Glides.grid()

    @Test fun anAddressFieldOffersAddressesFirstAndAPickTakesNoSpace() {
        val k = Keyboard(type = EMAIL)
        k.emails.accounts = listOf("vk@mine.dev")
        k.emails.contacts = listOf("vera@friends.org")
        k.learned.learnAddress("vikram@work.com")
        k.chars("v")
        assertEquals(listOf("vk@mine.dev", "vikram@work.com", "vera@friends.org"), k.strip.map { it.text })
        k.chars("ikram@")
        assertEquals("vikram@work.com", k.strip.first().text)
        k.engine.pickSuggestion("vikram@work.com")
        assertEquals("vikram@work.com", k.field.text)
        assertTrue(k.strip.isEmpty())
    }

    @Test fun anOrdinaryFieldOffersOneAddressLastAndContactsOnlyAfterTheAt() {
        val k = Keyboard()
        k.learned.learnAddress("asha@example.com")
        k.emails.contacts = listOf("ashwin@friends.org")
        k.tap("as")
        assertTrue(k.strip.none { '@' in it.text })
        k.tap("h")
        assertEquals(Suggestion("asha@example.com", Suggestion.Kind.Word), k.strip.last())
        assertTrue(k.strip.size <= 3)
        k.engine.pickSuggestion("asha@example.com")
        assertEquals("asha@example.com", k.field.text)

        val c = Keyboard()
        c.emails.contacts = listOf("ashwin@friends.org")
        c.tap("ashwin")
        assertTrue(c.strip.none { '@' in it.text })
        c.chars("@")
        assertEquals("ashwin@friends.org", c.strip.last().text)
    }

    @Test fun anAddressTypedIsLearnedOnceItEnds() {
        val k = Keyboard()
        k.chars("write to asha@example.com. or b@x.io, ")
        assertEquals(listOf("asha@example.com"), k.learned.addresses("a"))
        assertEquals(listOf("b@x.io"), k.learned.addresses("b"))
        // Leaving the field teaches the last one too.
        k.chars("c@x.io")
        k.engine.leave()
        assertEquals(listOf("c@x.io"), k.learned.addresses("c"))
    }

    @Test fun passwordAndIncognitoFieldsTeachNoAddressesAndOfferNone() {
        for (k in listOf(Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            Keyboard(type = EMAIL, options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))) {
            k.learned.learnAddress("asha@example.com")
            k.chars("as")
            assertTrue(k.strip.none { '@' in it.text })
            k.chars("ha@secret.org ")
            k.engine.leave()
            assertTrue(k.learned.addresses("asha@s").isEmpty())
        }
    }

    // As Gboard: the phrase is offered, and only a tap puts it in.
    @Test fun aShortcutIsOfferedAndSpaceLeavesItAlone() {
        val k = Keyboard(shortcuts = mapOf("addr" to "12 Long Road"))
        k.tap("addr")
        assertTrue(k.strip.toString(), k.strip.any { it.text == "12 Long Road" })
        k.engine.space()
        assertEquals("addr ", k.field.text)
        k.tap("addr")
        k.engine.pickSuggestion("12 Long Road")
        assertEquals("addr 12 Long Road", k.field.text)
    }

    private inner class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                                 options: Int = 0, shortcuts: Map<String, String> = emptyMap()) {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val learned = LearnedWords()
        val emails = EmailAddresses()
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) },
            readPersonal = { m, mine -> PersonalWords(m, mine, shortcuts) }, learned = learned, emails = emails, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = type; imeOptions = options; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        // Taps on the letter keys, as the decoder reads them.
        fun tap(letters: String) {
            for (c in letters) grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
        }
        // Characters as committed text, space as the space bar, so nothing is autocorrected.
        fun chars(text: String) { for (c in text) if (c == ' ') engine.space() else engine.type(c.toString()) }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
        const val EMAIL = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    }
}
