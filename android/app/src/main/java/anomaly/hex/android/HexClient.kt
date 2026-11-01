package anomaly.hex.android

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Why the computer didn't hand back a transcript. */
sealed class HexError(message: String) : Exception(message) {
    data object NotPaired : HexError("Connect a computer in Hex")
    data object Unreachable : HexError("Can't reach Hex")
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

    /** Sends a WAV and returns the trimmed transcript. */
    fun transcribe(host: String, port: Int, code: String, wav: ByteArray): String {
        val json = request(host, port, code, "POST", "/phone/v1/transcriptions", wav, 2_000, 60_000)
        return json.optString("transcript").trim()
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
                connection.setRequestProperty("Content-Type", "audio/wav")
                // Streams straight to the socket instead of buffering a copy.
                connection.setFixedLengthStreamingMode(wav.size)
                connection.outputStream.use { it.write(wav) }
            }
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
