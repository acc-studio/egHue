package app.eghue.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory

class LanSocketFactoryTest {
    /** Behaves like Network.socketFactory when a non-bypassable VPN (e.g. AdGuard) is running. */
    private val vpnBlocked = object : SocketFactory() {
        private fun fail(): Nothing = throw SocketException("Binding socket to network 776 failed: EPERM (Operation not permitted)")
        override fun createSocket(): Socket = fail()
        override fun createSocket(host: String?, port: Int): Socket = fail()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = fail()
        override fun createSocket(host: InetAddress?, port: Int): Socket = fail()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = fail()
    }

    @Test fun fallsBackToDefaultRoutingWhenBindingIsRefused() {
        val factory = LanSocketFactory { vpnBlocked }
        assertNotNull(factory.createSocket())
    }

    @Test fun connectedSocketsAlsoFallBack() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val factory = LanSocketFactory { vpnBlocked }
            factory.createSocket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                assertEquals(server.localPort, s.port)
            }
        }
    }

    @Test fun usesDefaultRoutingWhenNoLanNetwork() {
        assertNotNull(LanSocketFactory { null }.createSocket())
    }
}
