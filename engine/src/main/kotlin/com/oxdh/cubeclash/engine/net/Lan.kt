package com.oxdh.cubeclash.engine.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local multiplayer over the same Wi-Fi network or a phone's hotspot: length-prefixed binary frames over TCP,
 * plus UDP broadcast discovery so friends can see rooms without typing an address.
 */
object Lan {
    const val DEFAULT_PORT = 47800
    const val DISCOVERY_PORT = 47801
    private const val QUERY = "CUBECLASH?"
    private const val ANSWER = "CUBECLASH!"
    private const val MAX_FRAME = 1 shl 20

    /** A TCP connection with its own reader and writer threads; sending never blocks the caller. */
    class Connection(private val socket: Socket, private val onMessage: (ByteArray) -> Unit, private val onClosed: (String) -> Unit) {
        private val queue = LinkedBlockingDeque<ByteArray>()
        @Volatile private var open = true

        init {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            Thread({ readLoop() }, "lan-read").apply { isDaemon = true }.start()
            Thread({ writeLoop() }, "lan-write").apply { isDaemon = true }.start()
        }

        fun send(bytes: ByteArray) {
            if (!open) return
            // Back-pressure: on a congested link drop the oldest queued frames (state is resent anyway).
            while (queue.size > 120) queue.pollFirst()
            queue.offerLast(bytes)
        }

        private fun readLoop() {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                while (open) {
                    val len = input.readInt()
                    if (len <= 0 || len > MAX_FRAME) throw IllegalStateException("Bad frame")
                    val b = ByteArray(len)
                    input.readFully(b)
                    onMessage(b)
                }
            } catch (e: Exception) {
                close(e.message ?: "Connection closed")
            }
        }

        private fun writeLoop() {
            try {
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                while (open) {
                    val b = queue.pollFirst(500, TimeUnit.MILLISECONDS) ?: continue
                    out.writeInt(b.size); out.write(b)
                    if (queue.isEmpty()) out.flush()
                }
            } catch (e: Exception) {
                close(e.message ?: "Connection closed")
            }
        }

        fun close(reason: String = "Closed") {
            if (!open) return
            open = false
            try { socket.close() } catch (_: Exception) {}
            onClosed(reason)
        }
    }

    /** TCP server + discovery responder. Use port 0 for an ephemeral port (tests). */
    class HostTransportImpl(
        private val roomName: () -> String,
        private val roomCode: String,
        private val players: () -> Int,
        port: Int = DEFAULT_PORT,
        private val discoveryPort: Int = DISCOVERY_PORT,
    ) : HostTransport {
        private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        val port: Int = server.localPort
        private val conns = ConcurrentHashMap<String, Connection>()
        private val ids = AtomicInteger(1)
        @Volatile private var running = true
        private var discovery: DatagramSocket? = null

        override val description: String get() = (localAddresses().firstOrNull() ?: "this device") + ":" + port

        override fun start(listener: HostTransport.Listener) {
            Thread({
                while (running) {
                    try {
                        val s = server.accept()
                        val key = "lan" + ids.getAndIncrement()
                        conns[key] = Connection(s, { listener.onMessage(key, it) }, { conns.remove(key); listener.onPeerGone(key) })
                    } catch (e: Exception) {
                        if (running) listener.onError(e.message ?: "accept failed")
                    }
                }
            }, "lan-accept").apply { isDaemon = true }.start()
            Thread({
                try {
                    val ds = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(discoveryPort)) }
                    discovery = ds
                    val buf = ByteArray(512)
                    while (running) {
                        val p = DatagramPacket(buf, buf.size)
                        ds.receive(p)
                        if (String(p.data, 0, p.length, Charsets.UTF_8) != QUERY) continue
                        val answer = "$ANSWER|${Protocol.VERSION}|${roomName().replace('|', ' ')}|$roomCode|$port|${players()}".toByteArray(Charsets.UTF_8)
                        ds.send(DatagramPacket(answer, answer.size, p.address, p.port))
                    }
                } catch (e: Exception) {
                    if (running) listener.onError("Discovery unavailable: ${e.message}")
                }
            }, "lan-discovery").apply { isDaemon = true }.start()
        }

        override fun send(peer: String, bytes: ByteArray) { conns[peer]?.send(bytes) }
        override fun broadcast(bytes: ByteArray) { for (c in conns.values) c.send(bytes) }
        override fun drop(peer: String) { conns.remove(peer)?.close("Dropped") }

        override fun stop() {
            running = false
            try { server.close() } catch (_: Exception) {}
            try { discovery?.close() } catch (_: Exception) {}
            Thread.sleep(50) // let queued "room closed" frames go out
            for (c in conns.values) c.close("Host closed")
            conns.clear()
        }
    }

    class ClientTransportImpl(private val host: String, private val port: Int = DEFAULT_PORT) : ClientTransport {
        private var conn: Connection? = null
        override val description: String get() = "$host:$port"

        override fun connect(listener: ClientTransport.Listener) {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 4000)
            conn = Connection(s, listener::onMessage, listener::onDisconnected)
        }

        override fun send(bytes: ByteArray) { conn?.send(bytes) }
        override fun close() { conn?.close("Left"); conn = null }
    }

    data class FoundRoom(val name: String, val code: String, val address: String, val port: Int, val players: Int)

    /**
     * Finds rooms on the local network by UDP broadcast (also unicasts to [extraTargets], e.g. a hotspot gateway).
     * Blocks for [timeoutMs].
     */
    fun discover(timeoutMs: Int = 1500, discoveryPort: Int = DISCOVERY_PORT, extraTargets: List<String> = emptyList()): List<FoundRoom> {
        val found = LinkedHashMap<String, FoundRoom>()
        DatagramSocket().use { ds ->
            ds.broadcast = true
            ds.soTimeout = 250
            val q = QUERY.toByteArray(Charsets.UTF_8)
            val targets = LinkedHashSet<InetAddress>()
            try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
            targets.addAll(broadcastAddresses())
            for (t in extraTargets) try { targets.add(InetAddress.getByName(t)) } catch (_: Exception) {}
            val end = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (t in targets) try { ds.send(DatagramPacket(q, q.size, t, discoveryPort)) } catch (_: Exception) {}
                    nextSend = System.currentTimeMillis() + 500
                }
                try {
                    val buf = ByteArray(512)
                    val p = DatagramPacket(buf, buf.size)
                    ds.receive(p)
                    val parts = String(p.data, 0, p.length, Charsets.UTF_8).split('|')
                    if (parts.size >= 6 && parts[0] == ANSWER && parts[1] == Protocol.VERSION.toString()) {
                        val room = FoundRoom(parts[2], parts[3], p.address.hostAddress ?: continue, parts[4].toIntOrNull() ?: DEFAULT_PORT, parts[5].toIntOrNull() ?: 0)
                        found[room.address + ":" + room.port] = room
                    }
                } catch (_: SocketTimeoutException) {}
            }
        }
        return found.values.toList()
    }

    /** IPv4 addresses of this device on Wi-Fi / hotspot / Ethernet (for "join by address"). */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
            .sortedBy { if (it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")) 0 else 1 }
    } catch (_: Exception) { emptyList() }

    private fun broadcastAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    } catch (_: Exception) { emptyList() }
}
