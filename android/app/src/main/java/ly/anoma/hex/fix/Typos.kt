package ly.anoma.hex.fix

/** A compact trie that finds words within a few insertions, deletions, substitutions or adjacent swaps of
 * a query (tinyfix's `WordTrie`). Children are kept newest first, as there, so ties come back in its order. */
internal class WordTrie(words: List<String>) {
    private var character = IntArray(1024)
    private var firstChild = IntArray(1024) { -1 }
    private var nextSibling = IntArray(1024) { -1 }
    private var wordIndex = IntArray(1024) { -1 }
    private var size = 1

    init { words.forEachIndexed { index, word -> insert(word, index) } }

    private fun insert(word: String, index: Int) {
        var node = 0
        for (c in word) node = findOrCreateChild(node, c.code)
        wordIndex[node] = index
    }

    private fun findOrCreateChild(parent: Int, code: Int): Int {
        var child = firstChild[parent]
        while (child != -1) {
            if (character[child] == code) return child
            child = nextSibling[child]
        }
        if (size == character.size) grow()
        val created = size++
        character[created] = code
        firstChild[created] = -1
        nextSibling[created] = firstChild[parent]
        wordIndex[created] = -1
        firstChild[parent] = created
        return created
    }

    private fun grow() {
        val n = character.size * 2
        character = character.copyOf(n)
        firstChild = firstChild.copyOf(n).also { it.fill(-1, size) }
        nextSibling = nextSibling.copyOf(n).also { it.fill(-1, size) }
        wordIndex = wordIndex.copyOf(n).also { it.fill(-1, size) }
    }

    /** The index of every word within [maxEdits] of [query], in the trie's walk order. */
    fun search(query: String, maxEdits: Int): List<Int> {
        val width = query.length + 1
        val matches = ArrayList<Int>()
        fun walk(node: Int, previousCode: Int, row: IntArray, rowBefore: IntArray?) {
            var child = firstChild[node]
            while (child != -1) {
                val code = character[child]
                val next = IntArray(width)
                next[0] = row[0] + 1
                var smallest = next[0]
                for (column in 1 until width) {
                    val substitution = row[column - 1] + if (query[column - 1].code == code) 0 else 1
                    var value = minOf(row[column] + 1, next[column - 1] + 1, substitution)
                    if (rowBefore != null && column > 1 && query[column - 1].code == previousCode && query[column - 2].code == code)
                        value = minOf(value, rowBefore[column - 2] + 1)
                    next[column] = value
                    smallest = minOf(smallest, value)
                }
                if (wordIndex[child] != -1 && next[width - 1] <= maxEdits) matches += wordIndex[child]
                if (smallest <= maxEdits) walk(child, code, next, row)
                child = nextSibling[child]
            }
        }
        walk(0, -1, IntArray(width) { it }, null)
        return matches
    }
}

/** How believable a typo is as a slip of the fingers on a QWERTY keyboard: a weighted edit distance from
 * what was [typed] to what was [intended]. Lower is likelier (tinyfix's `typoCost`). */
internal object TypoCost {
    private const val SWAP = 1.2
    private const val DOUBLED_KEY = 1.2
    private const val ADJACENT_KEY = 1.6
    private const val VOWEL_FOR_VOWEL = 2.0
    private const val EXTRA_NEARBY_KEY = 1.8
    private const val MISSED_KEY = 2.4
    private const val EXTRA_KEY = 3.0
    private const val WRONG_KEY = 3.4
    /** A slip on a word's first letter is less likely, so it costs more. */
    private const val FIRST_LETTER_PENALTY = 1.5
    private const val VOWELS = "aeiou"

    private val neighbors: Map<Char, Set<Char>> = buildMap {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { keyIndex, key ->
                val near = HashSet<Char>()
                for ((otherRow, shift) in listOf(rowIndex to -1, rowIndex to 1, rowIndex - 1 to 0, rowIndex - 1 to 1,
                    rowIndex + 1 to -1, rowIndex + 1 to 0)) {
                    rows.getOrNull(otherRow)?.getOrNull(keyIndex + shift)?.let(near::add)
                }
                put(key, near)
            }
        }
    }

    private fun nearby(first: Char?, second: Char?) = first != null && second != null && neighbors[first]?.contains(second) == true

    private fun substitution(typed: Char, intended: Char) = when {
        nearby(typed, intended) -> ADJACENT_KEY
        typed in VOWELS && intended in VOWELS -> VOWEL_FOR_VOWEL
        else -> WRONG_KEY
    }

    private fun extraKey(typed: String, position: Int): Double {
        val key = typed[position]
        val previous = typed.getOrNull(position - 1)
        val next = typed.getOrNull(position + 1)
        if (key == previous || key == next) return DOUBLED_KEY
        if (nearby(key, previous) || nearby(key, next)) return EXTRA_NEARBY_KEY
        return EXTRA_KEY
    }

    private fun missedKey(intended: String, position: Int) =
        if (position > 0 && intended[position] == intended[position - 1]) DOUBLED_KEY else MISSED_KEY

    private fun start(position: Int) = if (position == 0) FIRST_LETTER_PENALTY else 0.0

    fun of(typed: String, intended: String): Double {
        val rows = typed.length + 1
        val columns = intended.length + 1
        val table = Array(rows) { DoubleArray(columns) }
        for (row in 1 until rows) table[row][0] = table[row - 1][0] + extraKey(typed, row - 1) + start(row - 1)
        for (column in 1 until columns) table[0][column] = table[0][column - 1] + missedKey(intended, column - 1) + start(column - 1)
        for (row in 1 until rows) {
            for (column in 1 until columns) {
                val typedKey = typed[row - 1]
                val intendedKey = intended[column - 1]
                val firstLetter = start(minOf(row, column) - 1)
                val replace = if (typedKey == intendedKey) 0.0 else substitution(typedKey, intendedKey) + firstLetter
                var best = minOf(
                    table[row - 1][column] + extraKey(typed, row - 1) + firstLetter,
                    table[row][column - 1] + missedKey(intended, column - 1) + firstLetter,
                    table[row - 1][column - 1] + replace,
                )
                if (row > 1 && column > 1 && typedKey == intended[column - 2] && typed[row - 2] == intendedKey)
                    best = minOf(best, table[row - 2][column - 2] + SWAP)
                table[row][column] = best
            }
        }
        return table[rows - 1][columns - 1]
    }
}
