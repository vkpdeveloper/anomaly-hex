package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.EmojiWords
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emoji on the strip, through the engine: which word they're for, what a pick types, and what it learns. */
class EmojiSuggestionsTest {
    private val grid = Glides.grid()

    @Test fun aWordBeingTypedFindsEmojiThroughItsSuggestions() {
        val k = Keyboard()
        k.tap("medicin")
        assertEquals(listOf("💊", "💉"), k.emoji)
        assertTrue(k.strip.count { it.kind != Suggestion.Kind.Emoji } >= 2)
    }

    @Test fun afterASpaceTheWordBeforeItGetsEmoji() {
        val k = Keyboard()
        k.chars("pizza ")
        assertEquals("🍕", k.emoji.first())
        k.chars("and ")
        assertTrue(k.emoji.isEmpty())
    }

    @Test fun aPickEndsTheWordThenTypesTheEmojiWithItsSpace() {
        val k = Keyboard()
        k.tap("pizza")
        k.engine.pickEmoji("🍕")
        assertEquals("pizza 🍕 ", k.field.text)
        // A comma takes the emoji's space, as after a picked word.
        k.chars(",")
        assertEquals("pizza 🍕,", k.field.text)
    }

    @Test fun theEmojiYouPickComeFirstNextTime() {
        val k = Keyboard()
        k.chars("party ")
        assertEquals(listOf("🎉", "🥳"), k.emoji)
        k.engine.pickEmoji("🥳")
        k.chars("party ")
        assertEquals(listOf("🥳", "🎉"), k.emoji)
        assertEquals("🥳", k.picks.of("party").single().first)
    }

    @Test fun anEmojiFromThePanelShowsForItsWordOncePickedTwice() {
        val k = Keyboard()
        k.chars("dude ")
        assertTrue(k.emoji.isEmpty())
        k.engine.emoji("🤙")
        k.chars(" dude ")
        assertTrue(k.emoji.isEmpty())
        k.engine.emoji("🤙")
        k.chars(" dude ")
        assertEquals(listOf("🤙"), k.emoji)
    }

    @Test fun aWordInProgressComesBackAfterAPanelClosesUntouched() {
        val k = Keyboard()
        k.tap("medicin")
        val before = k.strip
        k.engine.pause()
        assertTrue(k.strip.isEmpty())
        k.engine.configureTyping(keepWord = true)
        assertEquals(before.map { it.text }, k.strip.map { it.text })
    }

    @Test fun hinglishWordsHaveTheirOwnEmojiAndEnglishOnesToo() {
        val k = Keyboard(language = Spoken.Hinglish)
        k.chars("chai ")
        assertEquals("☕", k.emoji.first())
        k.chars("pizza ")
        assertEquals("🍕", k.emoji.first())
    }

    @Test fun passwordAndIncognitoFieldsOfferNoneAndLearnNothing() {
        for (k in listOf(Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))) {
            k.chars("pizza ")
            assertTrue(k.emoji.isEmpty())
            k.engine.emoji("🍕")
            assertTrue(k.picks.of("pizza").isEmpty())
        }
    }

    @Test fun picksSurviveBeingWrittenAndRead() {
        val picks = EmojiWords.Picks()
        picks.pick("party", "🥳"); picks.pick("party", "🥳"); picks.pick("party", "🎉"); picks.pick("chai", "☕")
        val read = EmojiWords.Picks.read(picks.write())
        assertEquals(listOf("🥳" to 2, "🎉" to 1), read.of("party"))
        assertEquals(listOf("☕" to 1), read.of("chai"))
    }

    private inner class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0, language: Spoken = Spoken.English) {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val emoji get() = strip.filter { it.kind == Suggestion.Kind.Emoji }.map { it.text }
        val picks = EmojiWords.Picks()
        val engine = KeyboardEngine(field, prefs, { language }, { SpatialDecoder(model(it)) },
            emojiLists = { code -> EmojiWords((if (code == "hien") listOf("hien", "en") else listOf("en")).map(::lists)) },
            emojiPicks = picks, clock = { 0L })

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

        fun tap(letters: String) {
            for (c in letters) grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
        }
        fun chars(text: String) { for (c in text) if (c == ' ') engine.space() else engine.type(c.toString()) }
    }

    private companion object {
        val models = HashMap<String, TypingModel>()
        fun model(code: String) = models.getOrPut(code) { TypingModel.file(File("src/main/assets/typing/$code.hextm"), code) }
        val parsed = HashMap<String, Map<String, List<String>>>()
        fun lists(code: String) = parsed.getOrPut(code) { EmojiWords.parse(File("src/main/assets/typing/$code.emoji").readText()) }
    }
}
