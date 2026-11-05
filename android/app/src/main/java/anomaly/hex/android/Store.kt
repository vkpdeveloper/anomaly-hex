package anomaly.hex.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class RouteKind { TAILSCALE, LAN }

/** One address the paired computer answers on. */
data class Route(val kind: RouteKind, val host: String)

/** Which routes the phone may use. Automatic tries Tailscale first. */
enum class RoutePreference { AUTOMATIC, TAILSCALE, LAN }

data class Computer(
    val name: String,
    val port: Int,
    val code: String,
    val routes: List<Route>,
    val model: String,
)

/** Saved pairing and preferences, read fresh by the service and the screen. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("hex", Context.MODE_PRIVATE)

    var computer: Computer?
        get() = prefs.getString("computer", null)?.let(::decodeComputer)
        set(value) = prefs.edit().putString("computer", value?.let(::encodeComputer)).apply()

    var preference: RoutePreference
        get() = prefs.getString("preference", null)
            ?.let { runCatching { RoutePreference.valueOf(it) }.getOrNull() }
            ?: RoutePreference.AUTOMATIC
        set(value) = prefs.edit().putString("preference", value.name).apply()

    /** The bubble's distance from the top of the screen, in pixels; -1 is unset. */
    var bubbleY: Int
        get() = prefs.getInt("bubble_y", -1)
        set(value) = prefs.edit().putInt("bubble_y", value).apply()
}

private fun encodeComputer(computer: Computer): String = JSONObject()
    .put("name", computer.name)
    .put("port", computer.port)
    .put("code", computer.code)
    .put("model", computer.model)
    .put(
        "routes",
        JSONArray(computer.routes.map { JSONObject().put("kind", it.kind.name).put("host", it.host) }),
    )
    .toString()

private fun decodeComputer(text: String): Computer? = runCatching {
    val json = JSONObject(text)
    val routes = json.getJSONArray("routes")
    Computer(
        name = json.getString("name"),
        port = json.getInt("port"),
        code = json.getString("code"),
        model = json.optString("model"),
        routes = (0 until routes.length()).map {
            val route = routes.getJSONObject(it)
            Route(RouteKind.valueOf(route.getString("kind")), route.getString("host"))
        },
    )
}.getOrNull()
