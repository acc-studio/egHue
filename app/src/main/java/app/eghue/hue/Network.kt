package app.eghue.hue

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS for the Hue bridge. Bridges present a certificate whose common name is the bridge id and that
 * is not signed by a public CA, so the system trust store can't validate it. Instead we check, per
 * connection, that the certificate either names the paired bridge (CN == bridge id) or matches the
 * public key we pinned at pairing time. Before pairing (discovery), any bridge is accepted.
 */
class BridgeTls(
    private val bridgeId: () -> String?,
    private val pinnedKey: () -> String?,
) {
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    val trustManager = object : X509TrustManager {
        // Chain validation is replaced by the identity check in [hostnameVerifier].
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }.socketFactory

    val hostnameVerifier = HostnameVerifier { _, session -> verify(session) }

    private fun verify(session: SSLSession): Boolean {
        val cert = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            ?: return false
        val id = bridgeId()
        val pin = pinnedKey()
        if (id == null && pin == null) return true
        if (pin != null && publicKeyPin(cert) == pin) return true
        if (id != null && commonName(cert)?.equals(id, ignoreCase = true) == true) return true
        return false
    }

    companion object {
        fun publicKeyPin(cert: X509Certificate): String =
            Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded), Base64.NO_WRAP)

        fun commonName(cert: X509Certificate): String? =
            Regex("(?:^|,)\\s*CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1)?.trim()
    }
}

/**
 * Watches Wi-Fi/Ethernet networks so bridge traffic can be bound to the LAN the bridge lives on.
 * Without this, Android may route a LAN address over mobile data when the Wi-Fi has no internet.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile private var lan: Network? = null
    @Volatile private var lanLink: LinkProperties? = null

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when local or default connectivity changes, so connections can be re-established. */
    val changes: SharedFlow<Unit> = _changes

    init {
        val lanRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(lanRequest, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    lan = network
                    lanLink = cm.getLinkProperties(network)
                    _changes.tryEmit(Unit)
                }
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (network == lan) lanLink = linkProperties
                }
                override fun onLost(network: Network) {
                    if (network == lan) { lan = null; lanLink = null }
                    _changes.tryEmit(Unit)
                }
            })
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _changes.tryEmit(Unit) }
            })
        }
    }

    val hasLan: Boolean get() = lan != null

    /** The LAN network to bind to if [host] is an IPv4 address on its subnet, else null (default routing). */
    fun networkFor(host: String?): Network? {
        val address = host?.let(::parseIpv4) ?: return null
        val net = lan ?: return null
        val link = lanLink ?: return null
        return if (link.linkAddresses.any { it.contains(address) }) net else null
    }

    /** Our own IPv4 address and prefix length on the LAN, for a subnet scan. */
    fun lanIpv4(): Pair<Inet4Address, Int>? {
        val link = lanLink ?: return null
        val la = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return (la.address as Inet4Address) to la.prefixLength
    }

    private fun LinkAddress.contains(target: Inet4Address): Boolean {
        val own = address as? Inet4Address ?: return false
        val bits = prefixLength
        val a = own.address; val b = target.address
        var remaining = bits
        for (i in 0 until 4) {
            if (remaining <= 0) return true
            val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            remaining -= 8
        }
        return true
    }

    companion object {
        private val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
        fun parseIpv4(hostWithPort: String): Inet4Address? {
            val host = hostWithPort.substringBefore(':')
            if (!ipv4.matches(host)) return null
            return runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull()
        }
    }
}

/** Creates sockets on the LAN network chosen by [network] (re-evaluated per connection). */
class LanSocketFactory(private val network: () -> Network?) : SocketFactory() {
    private fun delegate(): SocketFactory = network()?.socketFactory ?: getDefault()
    override fun createSocket(): Socket = delegate().createSocket()
    override fun createSocket(host: String?, port: Int): Socket = delegate().createSocket(host, port)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate().createSocket(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate().createSocket(host, port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate().createSocket(address, port, localAddress, localPort)
}
