package ly.anoma.hex.typing

import android.content.ContentResolver
import android.provider.ContactsContract

/** The names of your contacts as words, so typing or swiping a name gets it right and the strip completes
 * it with its capitals ([PersonalWords]). A full name gives each of its words ("Priya Nair" is "Priya" and
 * "Nair"). Kept in memory only, never saved. */
internal object ContactNames {
    private const val MAX = 5_000

    /** The words in [own] (your own name), the given and family names in [parts] and the words in
     * [displayNames], each once in the spelling first seen, at most [MAX]. */
    fun words(displayNames: Sequence<String?>, parts: Sequence<String?>, own: Sequence<String?> = emptySequence()): List<String> {
        val out = LinkedHashMap<String, String>()
        fun split(names: Sequence<String?>) = names.flatMap { it.orEmpty().split(' ', '\t').asSequence() }
        for (name in split(own) + parts + split(displayNames)) {
            if (out.size >= MAX) break
            val word = name?.trim(',', '.', ' ')?.takeIf { it.length >= 2 && it.all { c -> c.isLetter() || c == '\'' || c == '-' } } ?: continue
            // A hyphenated name is typed as its parts ("Anne-Marie").
            for (piece in word.split('-')) if (piece.length >= 2) out.putIfAbsent(piece.lowercase(), piece)
        }
        return out.values.toList()
    }

    /** Your contacts' names and your own, from the phone's profile card ("Me"), first; empty without the
     * Contacts permission. Read it off the main thread. */
    fun read(resolver: ContentResolver): List<String> {
        val own = ArrayList<String?>()
        val display = ArrayList<String?>()
        val parts = ArrayList<String?>()
        try {
            resolver.query(ContactsContract.Profile.CONTENT_URI, arrayOf(ContactsContract.Profile.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> while (cursor.moveToNext()) own.add(cursor.getString(0)) }
        } catch (_: RuntimeException) {} // No profile card, or no permission: the contacts below say which.
        try {
            resolver.query(ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME),
                ContactsContract.Data.MIMETYPE + " = ?",
                arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE), null)?.use { cursor ->
                while (cursor.moveToNext() && display.size < MAX) {
                    display.add(cursor.getString(0)); parts.add(cursor.getString(1)); parts.add(cursor.getString(2))
                }
            }
        } catch (_: RuntimeException) { return emptyList() } // SecurityException once the permission goes.
        return words(display.asSequence(), parts.asSequence(), own.asSequence())
    }
}
