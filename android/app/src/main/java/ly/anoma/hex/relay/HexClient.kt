package ly.anoma.hex.relay

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import ly.anoma.hex.speech.Spoken

/** Why the computer didn't hand back a transcript. */
sealed class HexError(message: String) : Exception(message) {
    data object NotPaired : HexError("Connect a computer in Hex")
    data object Unreachable : HexError("Can't reach Hex")
    /** The route refused or timed out before anything was sent, so resending is safe. */
    data object ConnectFailed : HexError("Can't reach Hex")
    data object WrongCode : HexError("Pairing code changed")
    data object ModelNotReady : HexError("Model isn't ready")
    data object Busy : HexError("Hex is busy")
    data class Failed(val status: Int, val code: String) : HexError("Hex couldn't transcribe ($code)")
}

data class Hello(val name: String, val port: Int, val routes: List<Route>, val model: String)

/** The computer's phone dictation API (`docs/phone-dictation.md`). */
object HexClient {
    const val DEFAULT_PORT = 47213
    const val PROBE_TIMEOUT_MS = 800

    fun hello(host: String, port: Int, code: String, timeoutMs: Int = PROBE_TIMEOUT_MS): Hello {
        val json = request(host, port, code, "GET", "/phone/v1/hello", null, timeoutMs, timeoutMs * 2)
        val routes = json.optJSONArray("routes")
        return Hello(
            name = json.optString("name", "Hex"),
            port = json.optInt("port", port),
            model = json.optString("model"),
            routes = (0 until (routes?.length() ?: 0)).mapNotNull {
                val route = routes!!.getJSONObject(it)
                val kind = when (route.optString("kind")) {
                    "tailscale" -> RouteKind.TAILSCALE
                    "lan" -> RouteKind.LAN
                    else -> return@mapNotNull null
                }
                Route(kind, route.getString("host"))
            },
        )
    }

    /** How often each word, and each pair of neighbouring words, appears in the computer's Hex History. */
    data class Words(val words: List<Pair<String, Int>>, val pairs: List<Triple<String, String, Int>>)

    fun words(host: String, port: Int, code: String): Words {
        val json = request(host, port, code, "GET", "/phone/v1/words", null, 2_000, 15_000)
        val words = json.optJSONArray("words")
        val pairs = json.optJSONArray("pairs")
        return Words(
            words = (0 until (words?.length() ?: 0)).mapNotNull { i ->
                words!!.optJSONArray(i)?.takeIf { it.length() == 2 }?.let { it.optString(0) to it.optInt(1) }
            },
            pairs = (0 until (pairs?.length() ?: 0)).mapNotNull { i ->
                pairs!!.optJSONArray(i)?.takeIf { it.length() == 3 }?.let { Triple(it.optString(0), it.optString(1), it.optInt(2)) }
            },
        )
    }

    /** A transcript and the language the computer was set to hear, or null when it chose ("auto") or
     * didn't say (an older Hex). */
    data class Heard(val text: String, val language: String?)

    /** Where a dictation was spoken: the app's name as the phone shows it, its package, and the
     * keyboard layout's language. The computer picks the mode for that app; the language is only a
     * hint. Any of them may be unknown. */
    data class Origin(val app: String? = null, val packageName: String? = null, val language: String? = null) {
        /** The query the transcription carries, or "" with nothing known. */
        fun query(): String = listOf("app" to app, "package" to packageName, "language" to language)
            .mapNotNull { (name, value) -> value?.trim()?.takeIf { it.isNotEmpty() }?.let { "$name=${URLEncoder.encode(it, "UTF-8")}" } }
            .joinToString("&")
            .let { if (it.isEmpty()) "" else "?$it" }

        companion object {
            /** The app behind [packageName], named as the launcher names it when Android lets Hex see it:
             * an input method always sees the app it types into; the floating button sees launchable apps. */
            fun of(context: Context, packageName: String?, language: String?): Origin {
                val app = packageName?.let {
                    runCatching {
                        val pm = context.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
                    }.getOrNull()
                }
                return Origin(app?.takeIf { it.isNotBlank() && it != packageName }, packageName, language)
            }

            /** The language a keyboard layout is spoken in, as the computer names it: Hinglish is Hindi. */
            internal fun language(layout: Spoken) = if (layout == Spoken.Hinglish) "hi" else layout.code
        }
    }

    /** Sends a WAV and returns the trimmed transcript. */
    fun transcribe(host: String, port: Int, code: String, wav: ByteArray, origin: Origin = Origin()): Heard {
        val json = request(host, port, code, "POST", "/phone/v1/transcriptions" + origin.query(), wav, 2_000, 60_000)
        val language = json.optString("language").takeIf { it.isNotEmpty() && it != "auto" }
        return Heard(json.optString("transcript").trim(), language)
    }

    /** The computer's text replacements (Settings › Output), each `phrase to output`. */
    fun replacements(host: String, port: Int, code: String): List<Pair<String, String>> {
        val json = request(host, port, code, "GET", "/phone/v1/replacements", null, 2_000, 15_000)
        val rows = json.optJSONArray("replacements")
        return (0 until (rows?.length() ?: 0)).mapNotNull { i ->
            rows!!.optJSONArray(i)?.takeIf { it.length() == 2 }?.let { it.optString(0) to it.optString(1) }
        }
    }

    private fun request(
        host: String,
        port: Int,
        code: String,
        method: String,
        path: String,
        wav: ByteArray?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        contentType: String = "audio/wav",
    ): JSONObject {
        val connection = try {
            URL("http", host, port, path).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            throw HexError.Unreachable
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer ${normalizeCode(code)}")
            if (wav != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                // Streams straight to the socket instead of buffering a copy.
                connection.setFixedLengthStreamingMode(wav.size)
            }
            // Kept apart from later failures: a read timeout or a reset after
            // the upload may mean the computer is still transcribing.
            try {
                connection.connect()
            } catch (_: IOException) {
                throw HexError.ConnectFailed
            }
            if (wav != null) connection.outputStream.use { it.write(wav) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().decodeToString() }
                .orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            return when (status) {
                in 200..299 -> json
                401 -> throw HexError.WrongCode
                409 -> throw if (json.optString("code") == "model-not-ready") HexError.ModelNotReady
                else HexError.Failed(status, json.optString("code"))
                429 -> throw HexError.Busy
                else -> throw HexError.Failed(status, json.optString("code", "http-$status"))
            }
        } catch (error: HexError) {
            throw error
        } catch (_: IOException) {
            throw HexError.Unreachable
        } finally {
            connection.disconnect()
        }
    }
}
