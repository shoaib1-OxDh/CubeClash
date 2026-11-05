package com.oxdh.cubeclash.engine.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal MQTT 3.1.1 client (QoS 0, TLS) used as an internet relay for online rooms. Public brokers mean no server
 * to run: the host and its friends exchange the same binary [Protocol] messages through room topics.
 */
class MqttConnection(
    private val host: String,
    private val port: Int,
    private val tls: Boolean,
    private val clientId: String,
    private val keepAliveSeconds: Int = 30,
) {
    var onMessage: ((String, ByteArray) -> Unit)? = null
    var onClosed: ((String) -> Unit)? = null
    private lateinit var socket: Socket
    private lateinit var out: OutputStream
    private val queue = LinkedBlockingDeque<ByteArray>()
    @Volatile private var open = false
    private var packetId = 1

    /** Connects and waits for CONNACK. Optional last will is published by the broker if we vanish. */
    fun connect(willTopic: String? = null, willPayload: ByteArray? = null, timeoutMs: Int = 6000) {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        socket = if (tls) (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true).also { (it as SSLSocket).startHandshake() } else raw
        socket.tcpNoDelay = true
        socket.soTimeout = timeoutMs
        out = socket.getOutputStream()
        val body = ByteArrayOutputStream()
        writeStr(body, "MQTT"); body.write(4)
        var flags = 0x02 // clean session
        if (willTopic != null) flags = flags or 0x04
        body.write(flags)
        body.write(keepAliveSeconds shr 8); body.write(keepAliveSeconds and 0xff)
        writeStr(body, clientId)
        if (willTopic != null) { writeStr(body, willTopic); writeBin(body, willPayload ?: ByteArray(0)) }
        out.write(packet(0x10, body.toByteArray())); out.flush()
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val type = input.readUnsignedByte()
        val len = readLength(input)
        val payload = ByteArray(len); input.readFully(payload)
        if (type shr 4 != 2 || len < 2 || payload[1].toInt() != 0) throw IllegalStateException("Relay refused the connection (code ${payload.getOrNull(1)})")
        socket.soTimeout = 0
        open = true
        Thread({ readLoop(input) }, "mqtt-read").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "mqtt-write").apply { isDaemon = true }.start()
        Thread({ while (open) { try { Thread.sleep(keepAliveSeconds * 500L) } catch (_: InterruptedException) {}; if (open) queue.offerLast(byteArrayOf(0xC0.toByte(), 0)) } }, "mqtt-ping").apply { isDaemon = true }.start()
    }

    fun subscribe(vararg topics: String) {
        val body = ByteArrayOutputStream()
        val id = packetId++
        body.write(id shr 8); body.write(id and 0xff)
        for (t in topics) { writeStr(body, t); body.write(0) }
        queue.offerLast(packet(0x82, body.toByteArray()))
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean = false) {
        if (!open) return
        val body = ByteArrayOutputStream(payload.size + topic.length + 4)
        writeStr(body, topic); body.write(payload)
        while (queue.size > 150) queue.pollFirst() // drop the oldest under congestion; state is resent anyway
        queue.offerLast(packet(if (retain) 0x31 else 0x30, body.toByteArray()))
    }

    fun close() {
        if (!open) { try { socket.close() } catch (_: Exception) {}; return }
        try { out.write(byteArrayOf(0xE0.toByte(), 0)); out.flush() } catch (_: Exception) {}
        shutdown("Closed")
    }

    /** Waits (up to [ms]) until queued packets are written. */
    fun flush(ms: Long) { val end = System.currentTimeMillis() + ms; while (queue.isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(10) }

    private fun shutdown(reason: String) {
        if (!open) return
        open = false
        try { socket.close() } catch (_: Exception) {}
        onClosed?.invoke(reason)
    }

    private fun writeLoop() {
        try {
            while (open) {
                val p = queue.pollFirst(500, TimeUnit.MILLISECONDS) ?: continue
                out.write(p)
                if (queue.isEmpty()) out.flush()
            }
        } catch (e: Exception) { shutdown(e.message ?: "Write failed") }
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (open) {
                val h = input.readUnsignedByte()
                val len = readLength(input)
                val b = ByteArray(len); input.readFully(b)
                if (h shr 4 == 3) { // PUBLISH
                    val tl = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                    val topic = String(b, 2, tl, Charsets.UTF_8)
                    var off = 2 + tl
                    if ((h shr 1) and 3 > 0) off += 2 // packet id (not used with QoS 0, tolerated)
                    onMessage?.invoke(topic, b.copyOfRange(off, b.size))
                }
            }
        } catch (e: EOFException) { shutdown("Relay closed the connection") } catch (e: Exception) { shutdown(e.message ?: "Read failed") }
    }

    companion object {
        private fun writeStr(o: ByteArrayOutputStream, s: String) = writeBin(o, s.toByteArray(Charsets.UTF_8))
        private fun writeBin(o: ByteArrayOutputStream, b: ByteArray) { o.write(b.size shr 8); o.write(b.size and 0xff); o.write(b) }

        fun packet(header: Int, body: ByteArray): ByteArray {
            val o = ByteArrayOutputStream(body.size + 5)
            o.write(header)
            var len = body.size
            do { var d = len % 128; len /= 128; if (len > 0) d = d or 0x80; o.write(d) } while (len > 0)
            o.write(body)
            return o.toByteArray()
        }

        fun readLength(input: DataInputStream): Int {
            var mult = 1; var value = 0
            while (true) {
                val d = input.readUnsignedByte()
                value += (d and 127) * mult
                if (d and 128 == 0) return value
                mult *= 128
                if (mult > 128 * 128 * 128) throw IllegalStateException("Bad length")
            }
        }
    }
}

/** Online rooms over public MQTT relays. The first character of a room code selects the relay. */
object Online {
    data class Relay(val host: String, val port: Int, val tls: Boolean = true)

    val RELAYS = listOf(
        Relay("broker.hivemq.com", 8883),
        Relay("broker.emqx.io", 8883),
        Relay("test.mosquitto.org", 8883),
    )

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun relayFor(code: String): Relay? {
        val i = ALPHABET.indexOf(code.firstOrNull()?.uppercaseChar() ?: return null)
        return if (i < 0) null else RELAYS[i % RELAYS.size]
    }

    /** A 6-character code (no 0/O/1/I) whose first character selects [relayIndex]. */
    fun newCode(relayIndex: Int): String {
        val firsts = ALPHABET.indices.filter { it % RELAYS.size == relayIndex }
        val sb = StringBuilder().append(ALPHABET[firsts[random.nextInt(firsts.size)]])
        repeat(5) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    fun normalize(code: String) = code.trim().uppercase().replace("O", "0").filter { it in ALPHABET }

    fun isValidCode(code: String) = code.length == 6 && code.all { it in ALPHABET }

    private fun base(code: String) = "cubeclash/v1/$code"
    fun randomId(prefix: String) = prefix + java.lang.Long.toHexString(random.nextLong())

    /**
     * Host side: subscribes to every player's inbox topic and publishes state to the room topic. Tries each relay in
     * turn until one accepts the connection; [code] is generated to point at that relay.
     */
    class HostTransportImpl(private val hostName: String, private val players: () -> Int) : HostTransport {
        lateinit var code: String; private set
        private var relay: Relay? = null
        private var conn: MqttConnection? = null
        private val lastSeen = ConcurrentHashMap<String, Long>()
        @Volatile private var running = false

        override val description: String get() = "online via ${relay?.host ?: "relay"}"

        /** Connects to the first reachable relay (blocking, call off the UI thread). Returns the room code. */
        fun open(preferred: Int = 0): String {
            var last: Exception? = null
            for (k in RELAYS.indices) {
                val i = (preferred + k) % RELAYS.size
                val r = RELAYS[i]
                val c = newCode(i)
                val m = MqttConnection(r.host, r.port, r.tls, randomId("cch-"))
                try {
                    m.connect()
                    conn = m; relay = r; code = c
                    return c
                } catch (e: Exception) { last = e; m.close() }
            }
            throw IllegalStateException("No online relay reachable: ${last?.message}")
        }

        override fun start(listener: HostTransport.Listener) {
            val m = conn ?: throw IllegalStateException("open() first")
            running = true
            val prefix = base(code) + "/s/"
            m.onMessage = { topic, payload ->
                if (topic.startsWith(prefix)) {
                    val peer = topic.substring(prefix.length)
                    lastSeen[peer] = System.currentTimeMillis()
                    if (Protocol.type(payload) == Protocol.GONE) listener.onPeerGone(peer) else listener.onMessage(peer, payload)
                }
            }
            m.onClosed = { reason -> if (running) listener.onError("Online relay disconnected: $reason") }
            m.subscribe(base(code) + "/s/+")
            announce()
            Thread({
                var t = 0
                while (running) {
                    Thread.sleep(1000); t++
                    if (t % 10 == 0) announce()
                }
            }, "online-announce").apply { isDaemon = true }.start()
        }

        private fun announce() { conn?.publish(base(code) + "/info", Protocol.roomInfo(hostName, players()), retain = true) }

        override fun send(peer: String, bytes: ByteArray) { conn?.publish(base(code) + "/c/" + peer, bytes) }
        override fun broadcast(bytes: ByteArray) { conn?.publish(base(code) + "/h", bytes) }
        override fun drop(peer: String) { lastSeen.remove(peer) }

        override fun stop() {
            running = false
            conn?.let { it.publish(base(code) + "/info", ByteArray(0), retain = true); it.flush(500); it.close() }
            conn = null
        }
    }

    /** Client side: publishes to its own inbox topic on the host, listens to the room and its private topic. */
    class ClientTransportImpl(private val code: String, private val clientId: String) : ClientTransport {
        private var conn: MqttConnection? = null
        override val description: String get() = "online room $code"

        override fun connect(listener: ClientTransport.Listener) {
            val r = relayFor(code) ?: throw IllegalArgumentException("Invalid room code")
            val m = MqttConnection(r.host, r.port, r.tls, randomId("ccc-"))
            // If we vanish without saying goodbye, the relay tells the host.
            m.connect(base(code) + "/s/" + clientId, Protocol.gone())
            var infoSeen = false
            m.onMessage = { topic, payload ->
                if (topic.endsWith("/info")) infoSeen = payload.isNotEmpty()
                else listener.onMessage(payload)
            }
            m.onClosed = { listener.onDisconnected(it) }
            m.subscribe(base(code) + "/h", base(code) + "/c/" + clientId, base(code) + "/info")
            conn = m
        }

        override fun send(bytes: ByteArray) { conn?.publish(base(code) + "/s/" + clientId, bytes) }
        override fun close() { conn?.close(); conn = null }
    }
}
