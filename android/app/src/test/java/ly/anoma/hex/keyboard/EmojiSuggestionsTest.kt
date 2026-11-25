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

    @Test fun aPickEndsTheWordThenTypesTheEmojiAndItsSpaceWaits() {
        val k = Keyboard()
        k.tap("pizza")
        k.engine.pickEmoji("🍕")
        assertEquals("pizza 🍕", k.field.text)
        // A comma goes against the emoji, as after a picked word.
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

    // An incognito field still shows the strip, as Gboard's does, but learns nothing; a password field shows none.
    @Test fun passwordFieldsOfferNoneAndIncognitoFieldsLearnNothing() {
        for ((k, offers) in listOf(Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) to false,
            Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) to true)) {
            k.chars("pizza ")
            assertEquals(offers, k.emoji.isNotEmpty())
            k.engine.emoji("🍕")
            assertTrue(k.picks.of("pizza").isEmpty())
        }
    }

    // A field that asks for no suggestions (React Native's autoCorrect={false}) still shows the strip,
    // as Gboard does, but a space leaves the word as typed.
    @Test fun aFieldThatAsksForNoSuggestionsShowsTheStripButNeverCorrects() {
        val k = Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        k.tap("teh")
        assertTrue(k.strip.isNotEmpty())
        k.engine.space()
        assertEquals("teh ", k.field.text)
    }

    @Test fun aColonSearchShowsEmojiAndAPickTakesItsPlace() {
        val k = Keyboard()
        k.chars("so :fir")
        // Hinglish "fir" (again) has emoji too, but on English "fire" comes first.
        assertEquals("🔥", k.found.first())
        k.engine.pickFound("🔥")
        assertEquals("so 🔥", k.field.text)
        assertTrue(k.found.isEmpty())
        assertEquals("🔥", k.picks.of("fir").single().first)
        assertEquals(listOf("🔥"), k.used)
        // Its space waits for the next word.
        k.tap("x")
        assertEquals("so 🔥 x", k.field.text)
    }

    @Test fun smileysAndTimesNeverStartAColonSearch() {
        for (text in listOf(":)", ":p ", "at 10:30", "https:", "word:fire")) {
            val k = Keyboard()
            k.chars(text)
            assertTrue(text, k.found.isEmpty())
            assertEquals(text, k.field.text)
        }
    }

    @Test fun thePanelSearchTakesTheKeysAndLeavesTheTextAlone() {
        val k = Keyboard()
        k.chars("hi ")
        k.engine.startSearch()
        assertEquals("", k.query)
        k.tap("pizzq")
        k.engine.delete()
        k.tap("a")
        assertEquals("pizza", k.query)
        assertEquals("hi ", k.field.text)
        assertTrue("🍕" in k.searched)
        assertTrue(k.found.isEmpty())
        k.engine.pickFound("🍕")
        assertEquals("hi 🍕", k.field.text)
        assertEquals("pizza", k.query)
        k.engine.stopSearch()
        assertEquals(null, k.query)
        assertTrue(k.searched.isEmpty())
        k.chars("x")
        assertEquals("hi 🍕x", k.field.text)
    }

    @Test fun aChosenSkinToneGoesInButTheEmojiIsLearnedWithoutIt() {
        val k = Keyboard(tones = mapOf("👍" to "👍🏽"))
        k.chars("so :thumbs")
        k.engine.pickFound("👍")
        assertEquals("so 👍🏽", k.field.text)
        assertEquals("👍", k.picks.of("thumbs").single().first)
    }

    @Test fun skinTonesGoAfterTheFirstPersonWithoutItsEmojiStyle() {
        assertEquals(listOf("✌️", "✌🏻", "✌🏼", "✌🏽", "✌🏾", "✌🏿"), EmojiTones.variants("✌️") { true })
        assertEquals("🏃🏽‍♂️", EmojiTones.variants("🏃‍♂️") { true }[3])
        assertTrue(EmojiTones.variants("😀") { it == "😀" }.isEmpty())
    }

    @Test fun picksSurviveBeingWrittenAndRead() {
        val picks = EmojiWords.Picks()
        picks.pick("party", "🥳"); picks.pick("party", "🥳"); picks.pick("party", "🎉"); picks.pick("chai", "☕")
        val read = EmojiWords.Picks.read(picks.write())
        assertEquals(listOf("🥳" to 2, "🎉" to 1), read.of("party"))
        assertEquals(listOf("☕" to 1), read.of("chai"))
    }

    private inner class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0, language: Spoken = Spoken.English,
                                 tones: Map<String, String> = emptyMap()) {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val emoji get() = strip.filter { it.kind == Suggestion.Kind.Emoji }.map { it.text }
        val picks = EmojiWords.Picks()
        var found = emptyList<String>()
        var query: String? = null
        var searched = emptyList<String>()
        val used = ArrayList<String>()
        val engine = KeyboardEngine(field, prefs, { language }, { SpatialDecoder(model(it)) },
            emojiLists = { code ->
                val strip = if (code == "hien") listOf("hien", "en") else listOf("en")
                EmojiWords(strip.map(::lists), names, (strip + listOf("en", "hien").filter { it !in strip }).map(::lists))
            },
            emojiPicks = picks, emojiUsed = { used += it }, emojiTone = { tones[it] ?: it }, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
                override fun emojiRow(emoji: List<String>) { found = emoji }
                override fun emojiSearch(query: String?, found: List<String>) { this@Keyboard.query = query; searched = found }
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
        val names by lazy { EmojiWords.Named.parse(File("src/main/assets/typing/emoji.names").readText()) }
        val parsed = HashMap<String, Map<String, List<String>>>()
        fun lists(code: String) = parsed.getOrPut(code) { EmojiWords.parse(File("src/main/assets/typing/$code.emoji").readText()) }
    }
}
