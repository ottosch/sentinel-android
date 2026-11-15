package com.samourai.sentinel.ui.dojo

import okhttp3.Dns
import timber.log.Timber
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.security.SecureRandom
import javax.net.SocketFactory

/**
 * SOCKS5 through Tor with a per-attempt user name and password, so Tor puts
 * the attempt on its own circuits (Tor's IsolateSOCKSAuth, on by default for
 * every SocksPort). Right after a Tor start the first circuit to an onion
 * service can stall for minutes; a retry on the same circuit inherits the
 * stall, a retry with fresh credentials does not.
 *
 * Java's built-in SOCKS proxy can only take credentials from the
 * process-wide Authenticator, so the handshake is done here: OkHttp is set
 * to a direct connection with [dns] (which never resolves anything, it only
 * carries the host name) and [socketFactory] (whose sockets connect to
 * Tor's SOCKS port only and ask Tor to CONNECT to the host name). A socket
 * from this factory never connects anywhere but [torSocks].
 */
class TorIsolatedSocks(
    private val torSocks: InetSocketAddress,
    val isolationToken: String = newToken(),
) {
    /** Keeps the host name unresolved: no DNS lookup, onion or not. */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
    }

    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = IsolatedSocket()
        override fun createSocket(host: String, port: Int): Socket = unsupported()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
        private fun unsupported(): Nothing = throw SocketException("Connect through Tor only")
    }

    private inner class IsolatedSocket : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw SocketException("Unsupported address")
            // The host name as OkHttp asked for it ([dns] keeps it); never an IP lookup.
            val host = target.hostString ?: throw SocketException("No host name")
            super.connect(torSocks, timeout)
            // Debug builds only (no release tree logs at debug level): shows that each
            // attempt uses its own isolation token. The token is random and not secret.
            Timber.d("Community Dojos: Tor stream isolation %s", isolationToken.take(8))
            handshake(this, host, target.port, isolationToken)
        }
    }

    companion object {
        private val random = SecureRandom()

        fun newToken(): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun fromProxy(proxy: Proxy, token: String = newToken()): TorIsolatedSocks {
            val address = proxy.address() as? InetSocketAddress
                ?: throw IllegalArgumentException("Not a SOCKS proxy address")
            val resolved = if (address.isUnresolved) InetSocketAddress(address.hostString, address.port) else address
            return TorIsolatedSocks(resolved, token)
        }

        /** RFC 1928 + RFC 1929: user name/password auth, then CONNECT to a domain name. */
        internal fun handshake(socket: Socket, host: String, port: Int, token: String) {
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(0x05, 0x01, 0x02))
            out.flush()
            if (input.readUnsignedByte() != 0x05 || input.readUnsignedByte() != 0x02) {
                throw SocketException("SOCKS: user name authentication refused")
            }
            val user = token.toByteArray(Charsets.US_ASCII)
            val pass = "x".toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(0x01, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
            out.flush()
            input.readUnsignedByte()
            if (input.readUnsignedByte() != 0x00) throw SocketException("SOCKS: authentication failed")
            val name = host.toByteArray(Charsets.US_ASCII)
            if (name.size > 255) throw SocketException("SOCKS: host name too long")
            out.write(
                byteArrayOf(0x05, 0x01, 0x00, 0x03, name.size.toByte()) + name +
                    byteArrayOf((port shr 8).toByte(), port.toByte())
            )
            out.flush()
            if (input.readUnsignedByte() != 0x05) throw SocketException("SOCKS: bad reply")
            val reply = input.readUnsignedByte()
            input.readUnsignedByte() // reserved
            when (input.readUnsignedByte()) {
                0x01 -> input.skipFully(4)
                0x04 -> input.skipFully(16)
                0x03 -> input.skipFully(input.readUnsignedByte())
                else -> throw SocketException("SOCKS: bad reply")
            }
            input.skipFully(2)
            if (reply != 0x00) {
                // Same shape as Java's SOCKS client: a SocketException naming SOCKS.
                throw SocketException(
                    when (reply) {
                        0x04 -> "SOCKS: Host unreachable"
                        0x05 -> "SOCKS: Connection refused"
                        0x06 -> "SOCKS: TTL expired"
                        else -> "SOCKS server general failure"
                    }
                )
            }
        }

        private fun DataInputStream.skipFully(n: Int) {
            var left = n
            while (left > 0) {
                val skipped = skipBytes(left)
                if (skipped <= 0) {
                    if (read() < 0) throw IOException("SOCKS: unexpected end of stream")
                    left--
                } else {
                    left -= skipped
                }
            }
        }
    }
}
