package ly.anoma.hex.typing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingsTest {
    private fun alike(a: String, b: String) = Spellings.key(a) == Spellings.key(b) && Spellings.hindi(a, b) && Spellings.hindi(b, a)

    @Test fun hindiSpellingsOfOneWordAreAlike() {
        for ((a, b) in listOf("nahi" to "nhi", "nahi" to "nahin", "kya" to "kia", "kya" to "kyaa", "acha" to "accha",
            "acha" to "achha", "accha" to "achcha", "theek" to "thik", "bahut" to "bht", "kyu" to "kyun", "mujhe" to "mjhe",
            "pehle" to "phle", "kiya" to "kia"))
            assertTrue("$a $b", alike(a, b))
    }

    // Two words, not two spellings: putting one in for the other would change what was meant.
    @Test fun distinctHindiWordsStayApart() {
        for ((a, b) in listOf("main" to "mein", "kya" to "kiya", "bhi" to "bhai", "hai" to "hain", "kal" to "kaal",
            "mar" to "maar", "karna" to "karana", "bana" to "bina", "laga" to "lega", "kaha" to "kha", "jitna" to "jeetna",
            "daro" to "daroo", "sare" to "saree", "gaya" to "gaa", "kahi" to "kahin", "hum" to "hume", "pata" to "patta",
            "nahi" to "nai"))
            assertFalse("$a $b", Spellings.key(a) == Spellings.key(b) && (Spellings.hindi(a, b) || Spellings.hindi(b, a)))
    }

    @Test fun britishAndAmericanSpellingsAreAlike() {
        for ((a, b) in listOf("colour" to "color", "favourite" to "favorite", "realise" to "realize", "organisation" to "organization",
            "analyse" to "analyze", "centre" to "center", "theatres" to "theaters"))
            assertTrue("$a $b", Spellings.key(a) == Spellings.key(b) && Spellings.english(a, b))
        for ((a, b) in listOf("timbre" to "timber", "analyses" to "analyzes", "four" to "for", "hour" to "hor", "rise" to "rize"))
            assertFalse("$a $b", Spellings.english(a, b))
    }

    @Test fun theKeyDropsVowelsHAndNasalEndings() {
        assertEquals("ks", Spellings.key("kaise"))
        assertEquals(Spellings.key("nahi"), Spellings.key("nahin"))
        assertEquals(Spellings.key("accha"), Spellings.key("acha"))
    }
}
