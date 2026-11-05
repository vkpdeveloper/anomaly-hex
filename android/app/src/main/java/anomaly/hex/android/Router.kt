package anomaly.hex.android

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Picks the route to the paired computer before it's needed, so a dictation
 * goes straight to an address known to answer. Every allowed route is probed
 * at once and the first in preference order that answers wins (Tailscale,
 * then the local network). The pick is kept until the network changes or a
 * request fails.
 */
class Router(context: Context, private val store: Store) {
    private val discovery = Discovery(context)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private data class Pick(val computer: Computer, val route: Route, val at: Long)

    @Volatile
    private var pick: Pick? = null

    fun invalidate() {
        pick = null
    }

    /** Resolves in the background if nothing current is cached. */
    fun prewarm() {
        val cached = pick
        if (cached != null && fresh(cached) && cached.computer == store.computer) return
        io.launch { runCatching { resolve() } }
    }

    /** The computer and the route to it; `cached` when no probe just confirmed it. */
    data class Resolved(val computer: Computer, val route: Route, val cached: Boolean)

    suspend fun resolve(): Resolved = mutex.withLock {
        val computer = store.computer ?: throw HexError.NotPaired
        pick?.takeIf { it.computer == computer && fresh(it) }?.let { return Resolved(it.computer, it.route, cached = true) }
        val preference = store.preference
        val answered = probe(computer, preference)
            ?: discovery.find(computer.name, DISCOVERY_TIMEOUT_MS)?.let { found ->
                val lan = Route(RouteKind.LAN, found.host)
                probe(computer.copy(port = found.port, routes = computer.routes + lan), preference)
            }
            ?: throw HexError.Unreachable
        val (route, hello) = answered
        // The computer's addresses can change (DHCP, Tailscale re-login):
        // keep what it reports, plus the address that just answered.
        val updated = computer.copy(
            name = hello.name,
            port = hello.port,
            model = hello.model,
            routes = (hello.routes + route).distinct(),
        )
        if (updated != computer) store.computer = updated
        pick = Pick(updated, route, SystemClock.elapsedRealtime())
        Resolved(updated, route, cached = false)
    }

    private suspend fun probe(computer: Computer, preference: RoutePreference): Pair<Route, Hello>? {
        val candidates = ordered(computer.routes, preference)
        if (candidates.isEmpty()) return null
        // Not structured: a slower route left behind times out on its own
        // instead of holding up the one that answered.
        val attempts = candidates.map { route ->
            io.async { route to HexClient.hello(route.host, computer.port, computer.code) }
        }
        for (attempt in attempts) {
            try {
                return withContext(Dispatchers.IO) { attempt.await() }
            } catch (error: HexError.WrongCode) {
                throw error
            } catch (_: HexError) {
                continue
            }
        }
        return null
    }

    private fun fresh(pick: Pick) = SystemClock.elapsedRealtime() - pick.at < CACHE_MS

    companion object {
        private const val CACHE_MS = 2 * 60_000L
        private const val DISCOVERY_TIMEOUT_MS = 2_000L

        fun ordered(routes: List<Route>, preference: RoutePreference): List<Route> = when (preference) {
            RoutePreference.AUTOMATIC -> routes.sortedBy { it.kind.ordinal }
            RoutePreference.TAILSCALE -> routes.filter { it.kind == RouteKind.TAILSCALE }
            RoutePreference.LAN -> routes.filter { it.kind == RouteKind.LAN }
        }.distinct()
    }
}
