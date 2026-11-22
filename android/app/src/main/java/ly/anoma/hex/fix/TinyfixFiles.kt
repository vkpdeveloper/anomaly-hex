package ly.anoma.hex.fix

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ly.anoma.hex.typing.TypingPreferences

/** The fix button's model and word lists, downloaded from Hugging Face when you turn the button on and
 * never shipped in the app. Pinned to one revision, each file checked against its size and SHA-256. */
internal object TinyfixFiles {
    private const val REVISION = "8efdf5cd91b32584f4077aa0e8ecaf48f7651bef"
    private const val BASE = "https://huggingface.co/paperweight/tinyfix/resolve/$REVISION/"

    private class Pinned(val name: String, val size: Long, val sha256: String)

    private val FILES = listOf(
        Pinned("wrong-word.onnx", 2_092_692, "35d76c0f2cc1fbb5450188a749f4c10b0d569d8806bb939bd66fa926561b47e9"),
        Pinned("wrong-word-vocab.txt", 171_815, "14ddd048052cf547d570c5c75b18843527916f892d2650fd0c3009b783b976a1"),
        Pinned("wrong-word-confusions.txt", 2_908, "9924ff7ed577595ae1b632adbbf3324779a6857d3eb102715e63abf5981d06d3"),
        Pinned("words.txt", 1_282_042, "f84bfae717ff3a4a3b90c824ed06ea08e4b2ed2746f7bba63d4e52f2c8bf85c3"),
    )
    private val TOTAL = FILES.sumOf { it.size }

    sealed interface State {
        data object Off : State
        /** [percent] of the files downloaded so far. */
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data object Failed : State
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Hex fix download") }
    @Volatile private var generation = 0
    private val mutableState = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = mutableState

    fun dir(context: Context) = File(context.filesDir, "tinyfix/$REVISION")

    /** Whether the files are here: each was checked when it arrived, and only then renamed into place. */
    fun ready(dir: File) = FILES.all { File(dir, it.name).length() == it.size }

    /** The current state, as Settings shows it. */
    fun refresh(context: Context, prefs: TypingPreferences) {
        if (mutableState.value is State.Downloading) return
        mutableState.value = if (prefs.fixButton && ready(dir(context))) State.Ready else if (mutableState.value == State.Failed) State.Failed else State.Off
    }

    /** Turns the button on: the files download, then [TypingPreferences.fixButton] turns on, which the keyboard follows. */
    fun enable(context: Context, prefs: TypingPreferences) {
        val app = context.applicationContext
        val dir = dir(app)
        val mine = ++generation
        mutableState.value = State.Downloading(0)
        worker.execute {
            val ok = try {
                download(dir) { done -> if (generation == mine) mutableState.value = State.Downloading((done * 100 / TOTAL).toInt()) }
                generation == mine
            } catch (_: IOException) { false }
            if (generation != mine) return@execute
            if (ok) { prefs.fixButton = true; mutableState.value = State.Ready } else mutableState.value = State.Failed
        }
    }

    /** Turns the button off and deletes the files. */
    fun disable(context: Context, prefs: TypingPreferences) {
        generation++
        prefs.fixButton = false
        mutableState.value = State.Off
        val tinyfix = File(context.filesDir, "tinyfix")
        worker.execute { tinyfix.deleteRecursively() }
    }

    private fun download(dir: File, progress: (Long) -> Unit) {
        // Older revisions are never used again.
        dir.parentFile?.listFiles()?.filter { it.name != REVISION }?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        var done = 0L
        for (file in FILES) {
            val target = File(dir, file.name)
            if (target.length() != file.size) fetch(file, target) { progress(done + it) }
            done += file.size
            progress(done)
        }
    }

    private fun fetch(file: Pinned, target: File, progress: (Long) -> Unit) {
        val partial = File(target.path + ".partial")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(BASE + file.name).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != 200) throw IOException("${file.name}: HTTP ${connection.responseCode}")
            var read = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > file.size) throw IOException("${file.name} is larger than expected")
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                        progress(read)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (read != file.size || hash != file.sha256) throw IOException("${file.name} doesn't match its checksum")
            if (!partial.renameTo(target)) throw IOException("couldn't save ${file.name}")
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    // One fixer for the process, read on first use: the fix button and the spell checker share it, one
    // check at a time (Tinyfix isn't thread safe).
    private val sharedLock = Any()
    private var shared: Tinyfix? = null

    /** Runs [block] with the shared fixer, reading the files first if needed. Never on the main thread. */
    fun <T> withFixer(dir: File, block: (Tinyfix) -> T): T = synchronized(sharedLock) {
        block(shared ?: load(dir).also { shared = it })
    }

    /** Runs [block] with the shared fixer only if it's already read. */
    fun ifLoaded(block: (Tinyfix) -> Unit) = synchronized(sharedLock) { shared?.let(block) }

    /** Drops the shared fixer: the button went off and its files are going. */
    fun drop() = synchronized(sharedLock) { shared = null }

    /** Reads the files into a ready fixer: most of a second (660 ms on a Pixel emulator), so never on the main thread. */
    fun load(dir: File): Tinyfix {
        val model = WrongWordModel(File(dir, "wrong-word.onnx").readBytes())
        return Tinyfix(model::run, Tinyfix.lines(File(dir, "wrong-word-vocab.txt").readText()),
            Tinyfix.lines(File(dir, "wrong-word-confusions.txt").readText()),
            Tinyfix.parseWords(File(dir, "words.txt").readText()))
    }
}
