package ly.anoma.hex.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.typing.TypingWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's side of a glide: where spaces go around a glided word, what delete and the strip do
 * with it, and its capitals. Glides are synthetic paths over the letter keys (`Glides`). */
class GlideEngineTest {
    private val grid = Glides.grid()

    @Test fun aGlidedWordAfterAWordTakesASpace() {
        val k = Keyboard()
        k.glide("hello")
        assertEquals("hello", k.field.text)
        assertEquals(Suggestion("hello", Suggestion.Kind.Fix), k.strip.first())
        k.glide("world")
        assertEquals("hello world", k.field.text)
    }

    @Test fun marksGoAgainstTheGlidedWordAndTheNextWordSpaces() {
        val k = Keyboard()
        k.glide("hello"); k.engine.type(",")
        assertEquals("hello,", k.field.text)
        k.glide("really")
        assertEquals("hello, really", k.field.text)
        k.engine.type("?")
        assertEquals("hello, really?", k.field.text)
    }

    @Test fun aTappedLetterAfterAGlidePutsTheSpaceFirst() {
        val k = Keyboard()
        k.glide("hello"); k.tap("t")
        assertEquals("hello t", k.field.text)
    }

    @Test fun spaceAfterAGlideTypesOneSpace() {
        val k = Keyboard()
        k.glide("hello"); k.engine.space()
        assertEquals("hello ", k.field.text)
        k.glide("would")
        assertEquals("hello would", k.field.text)
    }

    @Test fun deleteTakesTheWholeGlidedWordBack() {
        val k = Keyboard("Say")
        k.glide("hello")
        assertEquals("Say hello", k.field.text)
        k.engine.delete()
        assertEquals("Say ", k.field.text)
        k.engine.delete()
        assertEquals("Say", k.field.text)
    }

    @Test fun deleteAfterAMarkDeletesOnlyTheMark() {
        val k = Keyboard()
        k.glide("hello"); k.engine.type(",")
        k.engine.delete()
        assertEquals("hello", k.field.text)
    }

    @Test fun aPickReplacesTheGlidedWordAndKeepsItsSpacing() {
        val k = Keyboard("I")
        k.glide("tip")
        val went = k.field.text.removePrefix("I ")
        val other = k.strip.drop(1).first { it.text == "tip" || it.text == "top" }.text
        k.engine.pickSuggestion(other)
        assertEquals("I $other", k.field.text)
        assertEquals(Suggestion(other, Suggestion.Kind.Fix), k.strip.first())
        assertTrue(k.strip.any { it.text == went })
        k.engine.delete()
        assertEquals("I ", k.field.text)
    }

    @Test fun shiftCapitalisesAGlideAndCapsLockWritesItInCapitals() {
        val k = Keyboard()
        k.engine.shiftDown(); k.engine.shiftUp()
        k.glide("hello")
        assertEquals("Hello", k.field.text)
        k.glide("the")
        assertEquals("Hello the", k.field.text)
        k.engine.shiftDown(); k.engine.shiftUp(); k.engine.shiftDown(); k.engine.shiftUp()
        k.glide("keyboard")
        assertEquals("Hello the KEYBOARD", k.field.text)
    }

    @Test fun theSpaceAGlidePutsInCanStartASentence() {
        val k = Keyboard("Done.", autoCapitalize = true)
        k.glide("thanks")
        assertEquals("Done. Thanks", k.field.text)
    }

    @Test fun aWordInProgressStaysAsTyped() {
        val k = Keyboard()
        k.tap("t"); k.tap("h")
        k.glide("really")
        assertEquals("th really", k.field.text)
    }

    @Test fun heldDeleteTakesWholeWordsWithTheSpacesAfterThem() {
        val k = Keyboard("Say hello there  ")
        k.engine.deleteWord()
        assertEquals("Say hello ", k.field.text)
        k.engine.deleteWord()
        assertEquals("Say ", k.field.text)
        val emoji = Keyboard("hi 👍🏽")
        emoji.engine.deleteWord()
        assertEquals("hi ", emoji.field.text)
    }

    @Test fun heldDeleteKeepsTheWordInProgressRight() {
        val k = Keyboard("Say ")
        k.tap("w"); k.tap("o"); k.tap("r")
        k.engine.deleteWord()
        assertEquals("Say ", k.field.text)
        assertTrue(k.strip.isEmpty())
        k.tap("a")
        assertEquals("Say a", k.field.text)
    }

    @Test fun passwordAndNumberFieldsTakeNoGlides() {
        assertTrue(Keyboard().engine.glides)
        assertFalse(Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).engine.glides)
        assertFalse(Keyboard(type = InputType.TYPE_CLASS_NUMBER).engine.glides)
        val off = Keyboard()
        off.prefs.glide = false
        assertFalse(off.engine.glides)
    }

    @Test fun aNameYouTypeTwiceGlidesAsYouWriteIt() {
        val k = Keyboard()
        k.prefs.autocorrect = false
        repeat(2) { k.type("hi Vaibhav ") }
        assertTrue("Vaibhav" in k.learned.words())
        k.glide("vaibhav")
        assertTrue(k.field.text, k.field.text.endsWith(" Vaibhav"))
    }

    @Test fun aGlidedWordIsLearnedOnlyOnceItStays() {
        val k = Keyboard()
        k.glide("hello"); k.engine.delete()
        assertEquals(Double.POSITIVE_INFINITY, k.learned.cost("hello", null), 0.0)
        k.glide("hello"); k.engine.space()
        assertTrue(k.learned.cost("hello", null).isFinite())
    }

    @Test fun passwordFieldsTeachNothing() {
        val k = Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        k.type("secret secret ")
        assertTrue(k.learned.words().isEmpty())
        assertEquals(Double.POSITIVE_INFINITY, k.learned.cost("secret", null), 0.0)
    }

    private inner class Keyboard(text: String = "", autoCapitalize: Boolean = false,
                                 type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) {
        val field = Field(text)
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = autoCapitalize }
        var strip = emptyList<Suggestion>()
        val learned = LearnedWords()
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) },
            readPersonal = { m, mine -> PersonalWords(m, mine, emptyMap()) }, learned = learned, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply {
                inputType = type; initialSelStart = text.length; initialSelEnd = text.length
            })
            engine.opened()
            engine.configureTyping()
        }

        fun glide(word: String) = engine.glide(Glides.path(word, grid, smooth = .5 * grid.pitchX), grid)
        fun tap(letter: String) {
            val p = grid.centres.getValue(letter.single().lowercaseChar())
            engine.letter(letter, p.x, p.y, grid)
        }
        fun type(text: String) { for (c in text) if (c == ' ') engine.space() else tap(c.toString()) }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}

/** An editor with the caret at the end of [text]'s edits. */
internal class Field(var text: String) : KeyboardTextTarget {
    private var caret = text.length
    override val connected = true
    override fun before(length: Int) = text.substring(maxOf(0, caret - length), caret)
    override fun after(length: Int) = text.substring(caret, minOf(text.length, caret + length))
    override fun selected() = ""
    // As Android's: a sentence starts at the text's start or after a sentence's end and a space.
    override fun capsMode(mask: Int) = if (TypingWord.startsSentence(before(caret))) mask else 0
    override fun commit(text: String): Boolean {
        this.text = this.text.substring(0, caret) + text + this.text.substring(caret); caret += text.length; return true
    }
    override fun deleteBefore(length: Int): Boolean {
        val n = minOf(length, caret); text = text.removeRange(caret - n, caret); caret -= n; return true
    }
    override fun deleteCodePointsBefore(count: Int) = deleteBefore(count)
    override fun deleteAround(before: Int, after: Int): Boolean {
        val a = minOf(after, text.length - caret); text = text.removeRange(caret, caret + a); return deleteBefore(before)
    }
    override fun beginBatch() {}
    override fun endBatch() {}
    override fun setSelection(start: Int, end: Int): Boolean { caret = start; return true }
    override fun performEditorAction(id: Int) = false
    override fun defaultEditorAction() = false
    override fun sendKey(code: Int) {}
    override fun all() = FieldState(text, caret, caret)
}

/** In-memory preferences behind a context, for the classes that keep their settings in SharedPreferences. */
internal object Prefs {
    fun context(): Context = object : ContextWrapper(null) {
        val prefs = Memory()
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    class Memory : SharedPreferences {
        val values = HashMap<String?, Any?>()
        override fun getAll(): Map<String?, *> = values
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String?) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = apply { values[key] = value }
            override fun putStringSet(key: String?, values: Set<String>?) = apply { this@Memory.values[key] = values }
            override fun putInt(key: String?, value: Int) = apply { values[key] = value }
            override fun putLong(key: String?, value: Long) = apply { values[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { values[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { values[key] = value }
            override fun remove(key: String?) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun commit() = true
            override fun apply() {}
        }
    }
}
