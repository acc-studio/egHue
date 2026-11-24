package app.eghue.hue

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class FoundBridge(val id: String, val name: String, val host: String, val model: String?)

/**
 * Finds Hue bridges on the local network using every method at once: mDNS (`_hue._tcp`), Philips'
 * cloud discovery endpoint, and, if those come up empty, a quick scan of the local /24 subnet.
 * Every candidate is confirmed by asking it for its bridge id.
 */
class Discovery(
    private val context: Context,
    private val internet: OkHttpClient,
    private val client: HueClient,
    private val network: NetworkMonitor,
) {
    fun discover(timeoutMs: Long = 12_000): Flow<FoundBridge> = channelFlow {
        val probed = Collections.synchronizedSet(HashSet<String>())
        val foundAny = AtomicBoolean(false)

        suspend fun probe(host: String) {
            if (!probed.add(host)) return
            val cfg = runCatching { client.config(host, fast = true) }.getOrNull() ?: return
            foundAny.set(true)
            send(FoundBridge(cfg.id, cfg.name, host, cfg.model))
        }

        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                launch { cloudHosts().forEach { h -> launch { probe(h) } } }
                launch { mdnsHosts().collect { h -> launch { probe(h) } } }
                launch {
                    delay(3_000)
                    if (!foundAny.get()) subnetHosts().let { hosts ->
                        val gate = Semaphore(48)
                        hosts.forEach { h -> launch { gate.withPermit { probe(h) } } }
                    }
                }
            }
        }
    }

    private suspend fun cloudHosts(): List<String> = runCatching {
        val req = Request.Builder().url("https://discovery.meethue.com/").build()
        internet.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            HueJson.parseToJsonElement(resp.body!!.string()).jsonArray.objects().mapNotNull { o ->
                val ip = o.str("internalipaddress") ?: return@mapNotNull null
                val port = o.int("port") ?: 443
                if (port == 443) ip else "$ip:$port"
            }
        }
    }.getOrDefault(emptyList())

    private fun subnetHosts(): List<String> {
        val (own, _) = network.lanIpv4() ?: return emptyList()
        val b = own.address
        val prefix = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
        val self = b[3].toInt() and 0xFF
        return (1..254).filter { it != self }.map { prefix + it }
    }

    @Suppress("DEPRECATION")
    private fun mdnsHosts(): Flow<String> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) { close(); return@callbackFlow }
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { toResolve.trySend(info) }
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { close() }
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
            override fun onServiceLost(info: NsdServiceInfo?) = Unit
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices("_hue._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { close(); return@callbackFlow }
        // The legacy resolve API handles one request at a time, so resolve sequentially.
        launch {
            for (info in toResolve) {
                val host = withTimeoutOrNull(3_000) { resolve(nsd, info) } ?: continue
                trySend(host)
            }
        }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): String? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val h = serviceInfo.host
                        val port = serviceInfo.port
                        val host = when (h) {
                            is Inet4Address -> h.hostAddress
                            is Inet6Address -> "[${h.hostAddress?.substringBefore('%')}]"
                            else -> null
                        }?.let { if (port == 443 || port <= 0) it else "$it:$port" }
                        if (cont.isActive) cont.resume(host)
                    }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
}
