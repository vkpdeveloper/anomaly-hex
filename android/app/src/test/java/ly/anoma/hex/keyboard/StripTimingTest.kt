package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import java.util.concurrent.Executor
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.NextWordModel
import ly.anoma.hex.typing.NextWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A key's strip shows whole with the key, with what space will put in, whether or not the next-word model
 * has answered; its words, when they come later, move no word the strip shows. */
class StripTimingTest {
    private val grid = Glides.grid()

    @Test fun theFixShowsWithTheKeyThatMakesIt() {
        val k = Keyboard()
        for (slip in listOf("teh", "wprld")) {
            k.keys(slip)
            val fix = k.strip.singleOrNull { it.kind == Suggestion.Kind.Fix }?.text
            assertTrue("$slip: ${k.strip}", fix != null)
            k.keys(" ")
            assertTrue("$slip -> ${k.ed.text}", k.ed.text.endsWith("$fix "))
        }
        assertEquals("the world ", k.ed.text)
    }

    @Test fun eachKeysStripShowsBeforeTheModelAnswers() {
        // The model answers nothing: each key still gets its own strip, the one a keyboard without it shows.
        val k = Keyboard(Model.Waits)
        val plain = Keyboard(Model.None)
        for (c in "hello wo") {
            k.keys("$c"); plain.keys("$c")
            assertEquals("after ${k.ed.text}", plain.strip, k.strip)
        }
    }

    @Test fun theModelsLateWordsMoveNoWordShown() {
        for (soon in listOf(true, false)) {
            val k = Keyboard(Model.Waits)
            k.keys("I am going to the")
            k.keys(" ")
            val first = k.slots()
            if (!soon) k.now += 1_000
            k.answer()
            val late = k.slots()
            // The likeliest word stays in the middle; soon after the strip showed, the model's best word may take
            // the weakest one's slot, and later only an empty slot.
            assertEquals("$first -> $late", first[1], late[1])
            val changed = (0..2).filter { first[it] != late[it] }
            if (soon) assertEquals("$first -> $late", 1, changed.size)
            else assertTrue("$first -> $late", changed.all { first[it] == null })
        }
    }

    @Test fun theWordsAfterAWordAreReadyWhenItsSpaceComes() {
        // The model works out the words after "the" while it's typed, so its space's strip has them at once.
        val k = Keyboard(Model.Waits)
        val ready = Keyboard(Model.AtOnce)
        k.keys("thanks for the"); ready.keys("thanks for the")
        k.answer()
        k.keys(" "); ready.keys(" ")
        assertEquals(ready.strip, k.strip)
        val shown = k.strip
        k.answer()
        assertEquals(shown, k.strip)
    }


    private enum class Model { None, Waits, AtOnce }

    /** An editor that tells the engine of each change once the key's work is done, as Android does. */
    private class Editor : KeyboardTextTarget {
        var text = ""
        var start = 0; var end = 0
        var engine: KeyboardEngine? = null
        private var reported = 0 to 0
        override val connected = true
        override fun before(length: Int) = text.substring(maxOf(0, start - length), start)
        override fun after(length: Int) = text.substring(end, minOf(text.length, end + length))
        override fun selected() = text.substring(start, end)
        override fun capsMode(mask: Int) = 0
        override fun commit(text: String): Boolean {
            this.text = this.text.substring(0, start) + text + this.text.substring(end); start += text.length; end = start; return true
        }
        override fun deleteBefore(length: Int): Boolean {
            val n = minOf(length, start); text = text.removeRange(start - n, start); start -= n; end -= n; return true
        }
        override fun deleteCodePointsBefore(count: Int) = deleteBefore(count)
        override fun deleteAround(before: Int, after: Int): Boolean {
            val a = minOf(after, text.length - end); text = text.removeRange(end, end + a); return deleteBefore(before)
        }
        override fun beginBatch() {}
        override fun endBatch() {}
        override fun setSelection(start: Int, end: Int): Boolean { this.start = start; this.end = end; return true }
        override fun performEditorAction(id: Int) = false
        override fun defaultEditorAction() = false
        override fun sendKey(code: Int) {}
        override fun all() = FieldState(text, start, end)
        fun report() {
            val now = start to end
            if (now != reported) { val old = reported; reported = now; engine?.updateSelection(old.first, old.second, now.first, now.second) }
        }
    }

    private inner class Keyboard(model: Model = Model.None) {
        val ed = Editor()
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        var now = 0L
        // The next-word model's worker: [Model.Waits] answers only when the test says.
        private val asked = ArrayList<Runnable>()
        private val worker = if (model == Model.AtOnce) Executor { it.run() } else Executor { asked.add(it) }
        val engine = KeyboardEngine(ed, prefs, { Spoken.English }, { SpatialDecoder(english) }, learned = LearnedWords(), clock = { now },
            nextWords = if (model == Model.None) null else NextWords({ next }, worker))

        init {
            ed.engine = engine
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
            engine.opened()
            engine.configureTyping()
        }

        fun keys(s: String) {
            for (c in s) {
                now += 150
                if (c == ' ') engine.space()
                else grid.centres.getValue(c.lowercaseChar()).let { engine.letter(c.toString(), it.x, it.y, grid) }
                ed.report()
            }
        }
        /** The model answers what it was asked, and the main thread then runs the strip that follows. */
        fun answer() {
            while (asked.isNotEmpty()) asked.removeAt(0).run()
            engine.nextReady.run()
        }
        fun slots() = StripSlots.place(strip).take(3).map { it?.text }
    }

    private companion object {
        val english = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
        val next by lazy { NextWordModel.file(File("src/main/assets/typing/next.hexnn")) }
    }
}
