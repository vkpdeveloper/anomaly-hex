package ly.anoma.hex.typing

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract

/** The email addresses the strip offers while one is typed: yours (the phone's accounts Hex can see,
 * which needs no permission, and with the Contacts permission and Settings › Keyboard's switch, every
 * account syncing contacts, such as each Google account signed in), the ones you've typed
 * ([LearnedWords.addresses]) and, with that permission and switch, your contacts'. Thread-safe. */
internal class EmailAddresses {
    /** Set off the main thread; empty until read. */
    @Volatile var accounts: List<String> = emptyList()
    @Volatile var contacts: List<String> = emptyList()
    /** The accounts the contacts read names; Android hides other apps' accounts, Google's included,
     * from [accounts]. */
    @Volatile var signedIn: List<String> = emptyList()

    /** Addresses that start with [typed] (in any case) and are longer: yours first, then the ones you've
     * typed most, then your contacts' when [withContacts], each once. */
    fun matching(typed: String, learned: LearnedWords, withContacts: Boolean, limit: Int = 3): List<String> {
        if (typed.isEmpty()) return emptyList()
        val out = ArrayList<String>(limit)
        fun add(address: String) {
            if (out.size < limit && address.length > typed.length && address.startsWith(typed, ignoreCase = true) &&
                out.none { it.equals(address, ignoreCase = true) }) out.add(address)
        }
        accounts.forEach(::add)
        signedIn.forEach(::add)
        learned.addresses(typed, limit).forEach(::add)
        if (withContacts) contacts.forEach(::add)
        return out
    }

    companion object {
        private val ADDRESS = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
        private const val ADDRESS_CHARS = "._%+-@"
        // What separates an address from the text around it.
        private const val OPENERS = ",;<([{\"'"
        private const val CLOSERS = ".,;:!?)]}>\"'"
        private const val MAX_CONTACTS = 5_000
        private const val MAX_ACCOUNTS = 100

        /** Whether [text] is a whole email address. */
        fun valid(text: String) = text.length <= 254 && ADDRESS.matches(text)

        /** The address being typed at the end of [before] (the text before the cursor): the run back to a
         * space, comma, semicolon or opening bracket or quote, or null when that is empty or holds
         * anything an address can't. */
        fun typed(before: String): String? {
            val run = before.takeLastWhile { !it.isWhitespace() && it !in OPENERS }
            return run.takeIf { it.isNotEmpty() && it.length <= 254 && it.all { c -> c.isLetterOrDigit() || c in ADDRESS_CHARS } }
        }

        /** The whole address that ends [before] once a space, comma, semicolon or line break went in after
         * it, with any closing marks it ended a sentence with, or null. */
        fun finished(before: String): String? =
            typed(before.trimEnd().trimEnd { it in CLOSERS })?.takeIf(::valid)

        /** The phone's accounts that are email addresses, those Android shows Hex without a permission. */
        fun accounts(context: Context): List<String> = try {
            AccountManager.get(context).accounts.map { it.name.trim() }.filter(::valid).distinctBy { it.lowercase() }
        } catch (_: RuntimeException) { emptyList() }

        /** The phone's accounts that hold contacts and are email addresses: every Google account signed in
         * has its groups there even with no contacts. Empty without the permission; read it off the main
         * thread. */
        fun signedIn(resolver: ContentResolver): List<String> {
            val out = LinkedHashMap<String, String>()
            for (uri in listOf(ContactsContract.Groups.CONTENT_URI, ContactsContract.Settings.CONTENT_URI, ContactsContract.RawContacts.CONTENT_URI)) {
                try {
                    resolver.query(uri, arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext() && out.size < MAX_ACCOUNTS) {
                            val name = cursor.getString(0)?.trim() ?: continue
                            if (valid(name)) out.putIfAbsent(name.lowercase(), name)
                        }
                    }
                } catch (_: RuntimeException) {}
            }
            return out.values.toList()
        }

        /** Your contacts' email addresses, at most [MAX_CONTACTS]; empty without the permission. Read it off
         * the main thread. */
        fun contacts(resolver: ContentResolver): List<String> {
            val out = LinkedHashMap<String, String>()
            try {
                resolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext() && out.size < MAX_CONTACTS) {
                        val address = cursor.getString(0)?.trim() ?: continue
                        if (valid(address)) out.putIfAbsent(address.lowercase(), address)
                    }
                }
            } catch (_: RuntimeException) { return emptyList() } // SecurityException once the permission goes.
            return out.values.toList()
        }
    }
}
