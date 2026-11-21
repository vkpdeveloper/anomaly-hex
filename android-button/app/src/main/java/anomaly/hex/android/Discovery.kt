package anomaly.hex.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/** A computer advertising Hex's phone dictation on this network. */
data class Found(val name: String, val host: String, val port: Int)

/**
 * Browses the LAN for `_hex-phone._tcp` (the computer's DNS-SD advert).
 * Tailscale carries no multicast, so its addresses come from the computer's
 * hello instead.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Reports each computer as it resolves, on the main thread, until closed. */
    fun browse(onFound: (Found) -> Unit, onLost: (String) -> Unit): AutoCloseable {
        // Older Android resolves one service at a time.
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var closed = false

        fun resolveNext() {
            if (closed || resolving) return
            val next = pending.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) = main.post {
                    resolving = false
                    val host = ipv4(info)
                    if (!closed && host != null) onFound(Found(info.serviceName, host, info.port))
                    resolveNext()
                }.let {}

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = main.post {
                    resolving = false
                    resolveNext()
                }.let {}
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = main.post {
                pending.addLast(info)
                resolveNext()
            }.let {}

            override fun onServiceLost(info: NsdServiceInfo) = main.post {
                if (!closed) onLost(info.serviceName)
            }.let {}

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        return AutoCloseable {
            closed = true
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    /** Looks for one computer by name, for when its saved addresses stop answering. */
    suspend fun find(name: String, timeoutMs: Long): Found? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            lateinit var browsing: AutoCloseable
            browsing = browse(
                onFound = { found ->
                    if (found.name == name && continuation.isActive) {
                        browsing.close()
                        continuation.resume(found)
                    }
                },
                onLost = {},
            )
            continuation.invokeOnCancellation { main.post { browsing.close() } }
        }
    }

    private fun ipv4(info: NsdServiceInfo): String? {
        val address = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }
        } else {
            @Suppress("DEPRECATION")
            info.host?.takeIf { it is Inet4Address }
        }
        return address?.hostAddress
    }

    private companion object {
        const val SERVICE_TYPE = "_hex-phone._tcp"
    }
}
