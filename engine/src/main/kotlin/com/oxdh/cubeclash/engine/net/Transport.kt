package com.oxdh.cubeclash.engine.net

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host side of a connection technology (LAN TCP, online relay, in-memory for tests). Callbacks arrive on network
 * threads; sessions queue them and handle them on the game thread. Sends never block the caller.
 */
interface HostTransport {
    /** Human-readable address shown to players (e.g. "192.168.1.20:47800" or the online server). */
    val description: String
    fun start(listener: Listener)
    fun send(peer: String, bytes: ByteArray)
    fun broadcast(bytes: ByteArray)
    /** Forget a peer (it left or timed out). */
    fun drop(peer: String) {}
    fun stop()

    interface Listener {
        fun onMessage(peer: String, bytes: ByteArray)
        fun onPeerGone(peer: String)
        fun onError(message: String) {}
    }
}

/** Client side of a connection technology. */
interface ClientTransport {
    val description: String
    /** Connects (blocking up to a few seconds, call off the UI thread). Throws on failure. */
    fun connect(listener: Listener)
    fun send(bytes: ByteArray)
    fun close()

    interface Listener {
        fun onMessage(bytes: ByteArray)
        fun onDisconnected(reason: String)
    }
}

/** Factory so clients can open a fresh connection when reconnecting. */
fun interface ClientTransportFactory { fun create(): ClientTransport }

/** In-memory transport used by tests (and usable for split-screen experiments). Delivers synchronously. */
class InMemoryHub {
    private var host: HostTransport.Listener? = null
    private val clients = ConcurrentHashMap<String, ClientTransport.Listener>()
    private val ids = AtomicInteger(1)
    /** Simulated outage: messages are silently dropped while true. */
    @Volatile var dropAll = false

    val hostTransport = object : HostTransport {
        override val description = "in-memory"
        override fun start(listener: HostTransport.Listener) { host = listener }
        override fun send(peer: String, bytes: ByteArray) { if (!dropAll) clients[peer]?.onMessage(bytes) }
        override fun broadcast(bytes: ByteArray) { if (!dropAll) clients.values.forEach { it.onMessage(bytes) } }
        override fun drop(peer: String) { clients.remove(peer) }
        override fun stop() { clients.values.forEach { it.onDisconnected("Host closed") }; clients.clear(); host = null }
    }

    fun client(): ClientTransport = object : ClientTransport {
        val key = "mem" + ids.getAndIncrement()
        override val description = "in-memory"
        override fun connect(listener: ClientTransport.Listener) {
            checkNotNull(host) { "No host" }
            clients[key] = listener
        }
        override fun send(bytes: ByteArray) { if (!dropAll) host?.onMessage(key, bytes) }
        override fun close() { clients.remove(key); host?.onPeerGone(key) }
    }
}
