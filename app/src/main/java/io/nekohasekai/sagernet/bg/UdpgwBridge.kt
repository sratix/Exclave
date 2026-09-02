/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026 by Exclave contributors                                 *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * A udpgw (badvpn) client that lets UDP traffic pass through a TCP-only outbound such as SSH.
 *
 * The core hands UDP packets to this bridge through a plain SOCKS5 UDP ASSOCIATE outbound; the
 * bridge re-frames each datagram using the badvpn udpgw wire format and multiplexes all of them
 * onto a single TCP stream. That stream is opened against a loopback dokodemo-door inbound, so the
 * core tunnels it to the udpgw server (usually 127.0.0.1:7300 on the SSH host) exactly like any
 * other proxied connection.
 *
 *     app --udp--> tun --> core --socks5/udp--> bridge --udpgw/tcp--> core --> ssh --> udpgw server
 *
 * The wire format, taken from badvpn's protocol/udpgw.h, is a length-prefixed stream of packets:
 *
 *     [length: u16le][flags: u8][conid: u16le][addr][payload]
 *
 * where `length` counts every byte after itself, `addr` is 6 bytes (IPv4 + port) or 18 bytes (IPv6 +
 * port) in network byte order, and keepalive packets carry neither `addr` nor payload.
 */
class UdpgwBridge(
    private val listenPort: Int,
    private val tunnelPort: Int,
    maxConnections: Int,
) : AbstractInstance {

    /** Connection ids are 16 bit, so the server can never track more than that many. */
    private val connectionLimit = maxConnections.coerceIn(1, 65535)

    companion object {
        const val DEFAULT_PORT = 7300
        const val DEFAULT_MAX_CONNECTIONS = 100

        private const val FLAG_KEEPALIVE = 1 shl 0
        private const val FLAG_REBIND = 1 shl 1
        private const val FLAG_IPV6 = 1 shl 3

        // badvpn caps a single udpgw packet at 64 KiB minus the framing overhead.
        private const val MAX_PACKET_SIZE = 65535
        private const val KEEPALIVE_INTERVAL_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val RECONNECT_DELAY_MAX_MS = 16_000L

        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            // A bridge failure must never take the service down with it.
            if (!closed) Logs.w("udpgw: ${throwable.message}")
        }
    )

    private var socksListener: ServerSocket? = null
    private var socksUdp: DatagramSocket? = null

    @Volatile
    private var closed = false

    @Volatile
    private var tunnelSocket: Socket? = null

    /**
     * Outgoing udpgw frames. UDP is lossy by definition, so a backed up tunnel drops the oldest
     * packet instead of stalling the reader that feeds it.
     */
    private val outgoing = Channel<ByteArray>(256, BufferOverflow.DROP_OLDEST)

    // Connection id bookkeeping. A connection id stands for one client UDP endpoint (one app
    // socket), which is what gives the server-side socket stable NAT behaviour across destinations.
    private val stateLock = Any()
    private val clientToConId = LinkedHashMap<InetSocketAddress, Int>(16, 0.75f, true)
    private val conIdToClient = HashMap<Int, InetSocketAddress>()
    private val rebindPending = HashSet<Int>()
    private var nextConId = 0

    private val resolveCache = ConcurrentHashMap<String, InetAddress>()

    override fun launch() {
        val udp = DatagramSocket(null as SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(LOOPBACK, listenPort))
        }
        val tcp = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(LOOPBACK, listenPort), 64)
        }
        socksUdp = udp
        socksListener = tcp

        scope.launch { acceptLoop(tcp) }
        scope.launch { clientReadLoop(udp) }
        scope.launch { tunnelLoop() }

        Logs.i("udpgw: bridge listening on 127.0.0.1:$listenPort, tunnel via 127.0.0.1:$tunnelPort")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socksListener?.close() }
        runCatching { socksUdp?.close() }
        runCatching { tunnelSocket?.close() }
        outgoing.close()
        scope.cancel()
    }

    // ---------------------------------------------------------------- SOCKS5

    /**
     * The core only ever issues UDP ASSOCIATE here, but it keeps the TCP control connection open for
     * the lifetime of the UDP session, so each one gets its own reader that idles until EOF.
     */
    private suspend fun acceptLoop(listener: ServerSocket) {
        while (scope.isActive && !closed) {
            val socket = try {
                listener.accept()
            } catch (e: Exception) {
                if (!closed) Logs.w("udpgw: accept failed: ${e.message}")
                return
            }
            scope.launch { handleControlConnection(socket) }
        }
    }

    private fun handleControlConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            // Greeting: VER NMETHODS METHODS...
            if (input.readUnsignedByte() != 5) return
            val methodCount = input.readUnsignedByte()
            input.readFully(ByteArray(methodCount))
            output.write(byteArrayOf(5, 0)) // no authentication
            output.flush()

            // Request: VER CMD RSV ATYP DST.ADDR DST.PORT
            if (input.readUnsignedByte() != 5) return
            val command = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            skipAddress(input)
            input.readFully(ByteArray(2)) // port

            if (command != 3) { // not UDP ASSOCIATE
                output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }

            val reply = ByteArray(10)
            reply[0] = 5 // VER
            reply[1] = 0 // succeeded
            reply[3] = 1 // ATYP IPv4
            System.arraycopy(LOOPBACK.address, 0, reply, 4, 4)
            reply[8] = (listenPort ushr 8).toByte()
            reply[9] = listenPort.toByte()
            output.write(reply)
            output.flush()

            // The association lives as long as this connection does.
            @Suppress("ControlFlowWithEmptyBody")
            while (!closed && input.read() >= 0) {
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun skipAddress(input: DataInputStream) {
        when (input.readUnsignedByte()) {
            1 -> input.readFully(ByteArray(4))
            3 -> input.readFully(ByteArray(input.readUnsignedByte()))
            4 -> input.readFully(ByteArray(16))
            else -> throw SocketException("unknown SOCKS5 address type")
        }
    }

    /** Reads datagrams the core sends us and forwards their payload over the udpgw stream. */
    private suspend fun clientReadLoop(udp: DatagramSocket) {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (scope.isActive && !closed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udp.receive(packet)
            } catch (e: Exception) {
                if (!closed) Logs.w("udpgw: receive failed: ${e.message}")
                return
            }
            try {
                forwardToTunnel(packet)
            } catch (e: Exception) {
                Logs.d("udpgw: dropping malformed datagram: ${e.message}")
            }
        }
    }

    private fun forwardToTunnel(packet: DatagramPacket) {
        val data = packet.data
        val end = packet.offset + packet.length
        var offset = packet.offset

        if (packet.length < 10) return
        offset += 2 // RSV
        if (data[offset++].toInt() != 0) return // fragmented datagrams are not supported

        val destination: InetAddress
        when (data[offset++].toInt() and 0xff) {
            1 -> {
                destination = InetAddress.getByAddress(data.copyOfRange(offset, offset + 4))
                offset += 4
            }
            4 -> {
                destination = InetAddress.getByAddress(data.copyOfRange(offset, offset + 16))
                offset += 16
            }
            3 -> {
                val length = data[offset++].toInt() and 0xff
                val host = String(data, offset, length, Charsets.US_ASCII)
                offset += length
                // udpgw addresses the destination by IP only. The outbound is configured with
                // domainStrategy=UseIP, so this is only a fallback for sniffed destinations, and
                // resolving inline would stall every other flow behind one lookup: warm the cache
                // in the background and drop this packet, which UDP callers already handle.
                destination = resolveCache[host] ?: run {
                    scope.launch {
                        runCatching { resolveCache[host] = InetAddress.getByName(host) }
                    }
                    return
                }
            }
            else -> return
        }
        if (offset + 2 > end) return
        val port = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        offset += 2

        val client = InetSocketAddress(packet.address, packet.port)
        val payloadLength = end - offset
        val (conId, rebind) = acquireConId(client)

        var flags = 0
        if (rebind) flags = flags or FLAG_REBIND
        if (destination is Inet6Address) flags = flags or FLAG_IPV6

        val addressLength = if (destination is Inet6Address) 18 else 6
        val body = ByteArray(3 + addressLength + payloadLength)
        body[0] = flags.toByte()
        body[1] = conId.toByte()
        body[2] = (conId ushr 8).toByte()
        val rawAddress = destination.address
        System.arraycopy(rawAddress, 0, body, 3, rawAddress.size)
        body[3 + rawAddress.size] = (port ushr 8).toByte()
        body[4 + rawAddress.size] = port.toByte()
        System.arraycopy(data, offset, body, 3 + addressLength, payloadLength)

        outgoing.trySend(frame(body))
    }

    // ------------------------------------------------------------ connection ids

    private fun acquireConId(client: InetSocketAddress): Pair<Int, Boolean> {
        synchronized(stateLock) {
            clientToConId[client]?.let { return it to rebindPending.remove(it) }

            if (clientToConId.size >= connectionLimit) {
                // LinkedHashMap is in access order, so the first key is the least recently used.
                val oldest = clientToConId.keys.first()
                val freed = clientToConId.remove(oldest)!!
                conIdToClient.remove(freed)
            }

            var conId = nextConId
            var attempts = 0
            while (conIdToClient.containsKey(conId)) {
                conId = (conId + 1) and 0xffff
                if (++attempts > 0xffff) error("no free udpgw connection id")
            }
            nextConId = (conId + 1) and 0xffff

            clientToConId[client] = conId
            conIdToClient[conId] = client
            rebindPending.remove(conId)
            // A reused id still refers to a socket the server holds for someone else, so tell it to
            // rebind rather than leaking the previous endpoint's traffic into this one.
            return conId to true
        }
    }

    private fun clientOf(conId: Int): InetSocketAddress? = synchronized(stateLock) {
        conIdToClient[conId]
    }

    /** The server keeps no state across reconnects, so every live id has to be rebound. */
    private fun invalidateConnections() = synchronized(stateLock) {
        rebindPending.addAll(conIdToClient.keys)
    }

    // ---------------------------------------------------------------- tunnel

    private suspend fun tunnelLoop() {
        var backoff = RECONNECT_DELAY_MS
        while (scope.isActive && !closed) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(LOOPBACK, tunnelPort), 10_000)
                    tunnelSocket = socket
                    Logs.i("udpgw: tunnel established")
                    backoff = RECONNECT_DELAY_MS
                    invalidateConnections()

                    val writer = scope.launch { writeLoop(socket.getOutputStream()) }
                    val keepalive = scope.launch { keepaliveLoop() }
                    try {
                        readLoop(socket.getInputStream())
                    } finally {
                        tunnelSocket = null
                        writer.cancel()
                        keepalive.cancel()
                    }
                }
            } catch (e: Exception) {
                if (!closed) Logs.w("udpgw: tunnel failed: ${e.message}")
            }
            if (closed || !scope.isActive) return
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RECONNECT_DELAY_MAX_MS)
        }
    }

    private suspend fun writeLoop(output: OutputStream) {
        try {
            for (payload in outgoing) {
                output.write(payload)
                output.flush()
            }
        } catch (e: IOException) {
            // The reader sees the same broken stream and drives the reconnect.
            runCatching { tunnelSocket?.close() }
        }
    }

    private suspend fun keepaliveLoop() {
        val keepalive = frame(byteArrayOf(FLAG_KEEPALIVE.toByte(), 0, 0))
        while (scope.isActive && !closed) {
            delay(KEEPALIVE_INTERVAL_MS)
            outgoing.trySend(keepalive)
        }
    }

    private fun readLoop(rawInput: InputStream) {
        val input = DataInputStream(rawInput)
        val header = ByteArray(2)
        while (!closed) {
            input.readFully(header)
            val length = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
            if (length < 3 || length > MAX_PACKET_SIZE) throw SocketException("bad udpgw frame length $length")
            val body = ByteArray(length)
            input.readFully(body)
            deliverToClient(body)
        }
    }

    private fun deliverToClient(body: ByteArray) {
        val flags = body[0].toInt() and 0xff
        if (flags and FLAG_KEEPALIVE != 0) return
        val conId = (body[1].toInt() and 0xff) or ((body[2].toInt() and 0xff) shl 8)
        val client = clientOf(conId) ?: return

        val addressLength = if (flags and FLAG_IPV6 != 0) 16 else 4
        if (body.size < 3 + addressLength + 2) return
        val source = InetAddress.getByAddress(body.copyOfRange(3, 3 + addressLength))
        val portOffset = 3 + addressLength
        val port = ((body[portOffset].toInt() and 0xff) shl 8) or (body[portOffset + 1].toInt() and 0xff)
        val payloadOffset = portOffset + 2

        val reply = ByteArray(6 + addressLength + (body.size - payloadOffset))
        reply[3] = if (source is Inet4Address) 1.toByte() else 4.toByte()
        System.arraycopy(source.address, 0, reply, 4, addressLength)
        reply[4 + addressLength] = (port ushr 8).toByte()
        reply[5 + addressLength] = port.toByte()
        System.arraycopy(body, payloadOffset, reply, 6 + addressLength, body.size - payloadOffset)

        runCatching {
            socksUdp?.send(DatagramPacket(reply, reply.size, client.address, client.port))
        }
    }

    private fun frame(body: ByteArray): ByteArray {
        val out = ByteArray(body.size + 2)
        out[0] = body.size.toByte()
        out[1] = (body.size ushr 8).toByte()
        System.arraycopy(body, 0, out, 2, body.size)
        return out
    }

}
