package ly.anoma.hex.typing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmailAddressesTest {
    @Test fun theAddressBeingTypedRunsBackToASpaceOrBracket() {
        assertEquals("asha@ex", EmailAddresses.typed("mail asha@ex"))
        assertEquals("asha", EmailAddresses.typed("To: <asha"))
        assertEquals("b@x.co", EmailAddresses.typed("a@x.com,b@x.co"))
        assertNull(EmailAddresses.typed("hello "))
        assertNull(EmailAddresses.typed("what/now"))
    }

    @Test fun aFinishedAddressIsAWholeOneWithoutTheMarksAfterIt() {
        assertEquals("asha@example.com", EmailAddresses.finished("write to asha@example.com. "))
        assertEquals("asha@example.com", EmailAddresses.finished("(asha@example.com), "))
        assertNull(EmailAddresses.finished("asha@example "))
        assertNull(EmailAddresses.finished("hello "))
    }

    @Test fun yoursComeFirstThenTypedThenContactsWhenAsked() {
        val emails = EmailAddresses().apply {
            accounts = listOf("vk@mine.dev")
            contacts = listOf("vera@friends.org", "VK@mine.dev")
        }
        val learned = LearnedWords().apply { learnAddress("vikram@work.com") }
        assertEquals(listOf("vk@mine.dev", "vikram@work.com"), emails.matching("v", learned, withContacts = false))
        assertEquals(listOf("vk@mine.dev", "vikram@work.com", "vera@friends.org"), emails.matching("V", learned, withContacts = true))
        assertEquals(listOf("vera@friends.org"), emails.matching("ve", learned, withContacts = true))
        assertEquals(emptyList<String>(), emails.matching("vk@mine.dev", learned, withContacts = true))
        assertEquals(emptyList<String>(), emails.matching("", learned, withContacts = true))
    }
}
