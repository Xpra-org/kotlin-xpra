package org.xpra.client.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.xpra.client.client.TAG
import java.util.concurrent.Executors

/** One discovered session, already filtered down to something we can connect to. */
data class DiscoveredSession(
    val serviceName: String,
    val host: String,
    val port: Int,
    val sessionName: String,
    val sessionType: String,
    val username: String,
    val display: String,
    val uuid: String,
    val clients: Int,
) {
    val uri: String get() = "tcp://$host:$port/"

    /** What to show when the server publishes no session name. */
    val label: String get() = sessionName.ifEmpty { display.ifEmpty { host } }
}

/**
 * Browses `_xpra._tcp` for sessions on the local network.
 *
 * The server publishes to `_xpra._tcp.local.` (`xpra/net/mdns/__init__.py`) with TXT keys
 * `mode`, `display`, `username`, `uuid`, `platform`, `type`, `name` and `clients`
 * (`xpra/server/subsystem/mdns.py:135`). Every value is stringified before publishing —
 * `txt_rec()` does that deliberately to stop zeroconf mangling ints into booleans — so
 * everything is parsed as a string here.
 */
class MdnsDiscovery(private val context: Context) {

    companion object {
        private const val SERVICE_TYPE = "_xpra._tcp"
    }

    fun discover(): Flow<List<DiscoveredSession>> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        // Keyed by uuid: one TCP listener publishes a separate record per upgradeable mode
        // (tcp, ssl, ws, wss, ssh — see mdns.py:115), and they are all the same session.
        val found = LinkedHashMap<String, DiscoveredSession>()
        // resolveService is documented as one-at-a-time on older releases and fails when
        // issued concurrently, so resolutions are serialised onto a single thread.
        val resolver = Executors.newSingleThreadExecutor()

        fun publish() {
            trySend(found.values.sortedBy { it.label.lowercase() })
        }

        fun accept(info: NsdServiceInfo) {
            val attrs = info.attributes.orEmpty()
            fun attr(key: String) = attrs[key]?.let { String(it, Charsets.UTF_8) }.orEmpty()

            // v1 speaks plain TCP only, so the ssl/ws/wss/ssh records are not connectable yet.
            if (attr("mode") != "tcp") return
            val address = hostAddress(info) ?: return
            val uuid = attr("uuid").ifEmpty { "$address:${info.port}" }
            found[uuid] = DiscoveredSession(
                serviceName = info.serviceName.orEmpty(),
                host = address,
                port = info.port,
                sessionName = attr("name"),
                sessionType = attr("type"),
                username = attr("username"),
                display = attr("display"),
                uuid = uuid,
                clients = attr("clients").toIntOrNull() ?: 0,
            )
            publish()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.execute {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.d(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) = accept(resolved)
                    })
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                // Only the service name is available here, so drop by that.
                val gone = found.entries.filter { it.value.serviceName == info.serviceName }
                gone.forEach { found.remove(it.key) }
                if (gone.isNotEmpty()) publish()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to start: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolver.shutdown()
        }
    }

    private fun hostAddress(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            info.host?.hostAddress
        }
}
