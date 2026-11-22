package ly.anoma.hex.typing

import android.content.ContentResolver
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The computer's text replacements, kept on the phone and offered as text shortcuts. */
class ReplacementsTest {
    @Test fun oneWordPhrasesBecomeShortcutsAndCapitalsBecomeWords() {
        val kept = Replacements.read(Replacements.write(listOf(
            "brb" to "be right back",
            "addr" to "12 Long Road\nFlat\t3",
            "open code" to "OpenCode", // two words: the strip reads one at a time
            "github" to "GitHub",
            "c++" to "C plus plus",
            "um" to " ",
        )))
        assertEquals(mapOf("brb" to "be right back", "addr" to "12 Long Road\nFlat\t3"), kept.shortcuts)
        assertEquals(listOf("GitHub"), kept.words)
        assertEquals(Replacements.None.shortcuts, Replacements.read("not\ta\tpair").shortcuts)
    }

    @Test fun yourOwnShortcutWinsOverTheComputers() {
        val replacements = Replacements(listOf("GitHub"), mapOf("brb" to "be right back", "omw" to "on my way"))
        // A resolver that reads no personal dictionary, as Android answers an app that isn't the keyboard.
        val words = PersonalWords.read(object : ContentResolver(null) {}, model, emptyList(), replacements)
        assertEquals("be right back", words.shortcut("brb"))
        assertEquals("On my way", words.shortcut("Omw"))
        assertEquals("GitHub", words.text(words.index("github")))
        assertNull(words.shortcut("open"))

        val mine = PersonalWords(model, emptyList(), mapOf("brb" to "mine"))
        assertEquals("mine", mine.shortcut("brb"))
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
