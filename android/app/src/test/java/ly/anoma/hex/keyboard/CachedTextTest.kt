package ly.anoma.hex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class CachedTextTest {
    @Test fun typingReadsTheEditorNoMore() {
        val t = Setup("Hi there")
        t.text.commit(" you"); t.text.commit("r")
        t.text.deleteBefore(1); t.text.commit("!")
        assertEquals("Hi there you!", t.text.before(96))
        assertEquals("", t.text.after(1))
        assertEquals("", t.text.selected())
        // The editor's reports arrive late and coalesced; the copy holds.
        t.report(); assertEquals("there you!", t.text.before(10))
        assertEquals(0, t.editor.reads)
    }

    @Test fun textAfterTheCursorCutByTheEditorIsReadAgain() {
        val t = Setup("")
        val long = "x".repeat(1500)
        // All of the text before the cursor came, so a short text after it is all there is.
        t.text.started(2, 2, "ab", "cd")
        assertEquals("cd", t.text.after(96)); assertEquals(0, t.editor.reads)
        // Only part of the text before the cursor came: the editor may have cut the text after it too.
        t.editor.text = long; t.editor.caret = 1200
        t.text.started(1200, 1200, long.take(1024), "x".repeat(100))
        assertEquals(300, t.text.after(400)!!.length); assertEquals(1, t.editor.reads)
    }

    @Test fun aTapInTheTextReadsItAgain() {
        val t = Setup("one two")
        t.editor.caret = 3; t.text.selectionChanged(3, 3)
        assertEquals("one", t.text.before(96))
        assertEquals(" two", t.text.after(96))
        assertEquals(2, t.editor.reads)
    }

    @Test fun anEditTheEditorNeverReportsReadsItAgain() {
        val t = Setup("abc")
        t.editor.refuse = true
        t.text.commit("d") // A full field: the editor drops it and reports nothing.
        assertEquals("abcd", t.text.before(96))
        t.now += 2_000
        assertEquals("abc", t.text.before(96))
    }

    @Test fun deleteTakesWholeCodePoints() {
        val t = Setup("ok 👍")
        t.text.deleteCodePointsBefore(1)
        assertEquals("ok ", t.text.before(96))
        assertEquals("ok ", t.editor.text)
        assertEquals(0, t.editor.reads)
    }

    private class Setup(initial: String) {
        var now = 0L
        val editor = Editor(initial)
        val text = CachedText(editor, { now }, capsOf = { _, _ -> 0 })
        init { text.started(initial.length, initial.length, initial, "") }
        /** The editor reports where its cursor is now, once, as editors coalesce their reports. */
        fun report() = text.selectionChanged(editor.caret, editor.caret)
    }

    private class Editor(var text: String) : KeyboardTextTarget {
        var caret = text.length
        var reads = 0
        var refuse = false
        override val connected = true
        override fun before(length: Int): String { reads++; return text.substring(maxOf(0, caret - length), caret) }
        override fun after(length: Int): String { reads++; return text.substring(caret, minOf(text.length, caret + length)) }
        override fun selected(): String { reads++; return "" }
        override fun capsMode(mask: Int): Int { reads++; return 0 }
        override fun commit(text: String): Boolean {
            if (refuse) return true
            this.text = this.text.substring(0, caret) + text + this.text.substring(caret); caret += text.length; return true
        }
        override fun deleteBefore(length: Int): Boolean {
            val n = minOf(length, caret); text = text.removeRange(caret - n, caret); caret -= n; return true
        }
        override fun deleteCodePointsBefore(count: Int): Boolean {
            val n = text.substring(0, caret).let { it.length - it.offsetByCodePoints(it.length, -count) }
            return deleteBefore(n)
        }
        override fun deleteAround(before: Int, after: Int): Boolean {
            text = text.removeRange(caret, minOf(text.length, caret + after)); return deleteBefore(before)
        }
        override fun beginBatch() {}
        override fun endBatch() {}
        override fun setSelection(start: Int, end: Int): Boolean { caret = start; return true }
        override fun performEditorAction(id: Int) = false
        override fun defaultEditorAction() = false
        override fun sendKey(code: Int) {}
    }
}
