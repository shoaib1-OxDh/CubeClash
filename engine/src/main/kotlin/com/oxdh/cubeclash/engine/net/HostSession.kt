package com.oxdh.cubeclash.engine.net

import com.oxdh.cubeclash.engine.Chain
import com.oxdh.cubeclash.engine.InputBrain
import com.oxdh.cubeclash.engine.Vec2
import com.oxdh.cubeclash.engine.World
import com.oxdh.cubeclash.engine.WorldEvent
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Authoritative multiplayer host. The host's device runs the only real [World]; remote players send input and
 * receive the world state at [stateRate] Hz. Everything here runs on the game thread via [tick]; transport
 * callbacks are queued.
 *
 * Disconnects: a silent player keeps their chain (an AI stands in so it isn't free food) for [keepSeconds];
 * reconnecting with the same client id re-attaches to the same chain, score and all.
 */
class HostSession(
    val world: World,
    private val transport: HostTransport,
    val roomCode: String,
    val hostName: String,
    private val stateRate: Float = 20f,
    private val timeoutSeconds: Float = 5f,
    private val keepSeconds: Float = 60f,
    private val maxPlayers: Int = 8,
) {
    class Peer(val clientId: String, var key: String, val chain: Chain) {
        var lastSeen = 0.0
        var connected = true
        var lastSeq = Int.MIN_VALUE
        var inputs = 0
        var disconnectedAt = 0.0
    }

    private sealed class Inbound {
        class Msg(val peer: String, val bytes: ByteArray) : Inbound()
        class Gone(val peer: String) : Inbound()
    }

    private val inbox = ConcurrentLinkedQueue<Inbound>()
    private val byClientId = LinkedHashMap<String, Peer>()
    private val byKey = HashMap<String, Peer>()
    private var stateTimer = 0f
    private var fullTimer = 0f
    private var rosterTimer = 0f
    private var tick = 0
    private var rosterDirty = true
    @Volatile var lastError: String? = null; private set

    val peers: Collection<Peer> get() = byClientId.values
    val connectedCount: Int get() = byClientId.values.count { it.connected }
    val transportDescription: String get() = transport.description

    fun start() {
        transport.start(object : HostTransport.Listener {
            override fun onMessage(peer: String, bytes: ByteArray) { inbox.add(Inbound.Msg(peer, bytes)) }
            override fun onPeerGone(peer: String) { inbox.add(Inbound.Gone(peer)) }
            override fun onError(message: String) { lastError = message }
        })
    }

    /** Processes network input, advances the world by [dt] and sends state. Call once per frame on the game thread. */
    fun tick(dt: Float) {
        while (true) {
            when (val m = inbox.poll() ?: break) {
                is Inbound.Msg -> handle(m.peer, m.bytes)
                is Inbound.Gone -> byKey[m.peer]?.let { markDisconnected(it) }
            }
        }
        world.step(dt)
        checkTimeouts()

        stateTimer += dt; fullTimer += dt; rosterTimer += dt
        if (rosterDirty || rosterTimer > 2f) { transport.broadcast(Protocol.encodeRoster(roster())); rosterDirty = false; rosterTimer = 0f }
        if (fullTimer > 10f) { fullTimer = 0f; transport.broadcast(Protocol.encodeFull(fullCollectibles())) }
        if (stateTimer >= 1f / stateRate) {
            stateTimer = 0f
            transport.broadcast(Protocol.encode(buildState()))
        }
    }

    private fun handle(key: String, bytes: ByteArray) {
        val known = byKey[key]
        if (known != null) known.lastSeen = world.time
        when (Protocol.type(bytes)) {
            Protocol.HELLO -> onHello(key, Protocol.decodeHello(bytes))
            Protocol.INPUT -> {
                val p = known ?: return
                val m = Protocol.decodeInput(bytes)
                if (m.seq <= p.lastSeq) return
                p.lastSeq = m.seq
                p.inputs++
                if (!p.connected) reconnected(p)
                val brain = p.chain.brain as? InputBrain ?: return
                brain.direction = if (m.steering) Vec2.fromAngle(m.angle) else null
                brain.boost = m.boost
            }
            Protocol.PING -> { transport.send(key, Protocol.pong(Protocol.decodeTime(bytes))); if (known != null && !known.connected) reconnected(known) }
            Protocol.LEAVE -> known?.let { removePeer(it) }
        }
    }

    private fun onHello(key: String, h: Protocol.Hello) {
        if (h.version != Protocol.VERSION) { transport.send(key, Protocol.reject("Different game version — please update Cube Clash.")); return }
        var p = byClientId[h.clientId]
        if (p == null) {
            if (byClientId.size >= maxPlayers - 1) { transport.send(key, Protocol.reject("Room is full.")); return }
            val chain = world.addHuman(h.name, h.skin, h.trail)
            p = Peer(h.clientId, key, chain)
            byClientId[h.clientId] = p
        } else {
            // Reconnect (possibly through a new connection): re-attach to the same chain.
            if (p.key != key) byKey.remove(p.key)
            p.key = key
            p.lastSeq = Int.MIN_VALUE
        }
        byKey[key] = p
        p.lastSeen = world.time
        reconnected(p)
        transport.send(key, Protocol.encode(Protocol.Welcome(p.chain.id, world.seed, world.mode.mode.ordinal, world.difficulty.ordinal,
            world.arenaRadius, world.obstacles, hostName, roomCode)))
        transport.send(key, Protocol.encodeRoster(roster()))
        transport.send(key, Protocol.encodeFull(fullCollectibles()))
        transport.send(key, Protocol.encode(buildState(includeEvents = false)))
        rosterDirty = true
    }

    private fun reconnected(p: Peer) {
        if (!p.connected) rosterDirty = true
        p.connected = true
        p.chain.connected = true
        if (p.chain.brain !is InputBrain) p.chain.brain = InputBrain()
    }

    private fun markDisconnected(p: Peer) {
        if (!p.connected) return
        p.connected = false
        p.chain.connected = false
        p.disconnectedAt = world.time
        p.chain.brain = world.makeStandInBrain()
        rosterDirty = true
    }

    private fun removePeer(p: Peer) {
        byClientId.remove(p.clientId)
        byKey.remove(p.key)
        transport.drop(p.key)
        world.removeChain(p.chain)
        rosterDirty = true
    }

    private fun checkTimeouts() {
        val now = world.time
        for (p in byClientId.values.toList()) {
            if (p.connected && now - p.lastSeen > timeoutSeconds) markDisconnected(p)
            if (!p.connected && now - p.disconnectedAt > keepSeconds) removePeer(p)
        }
    }

    fun roster(): List<Protocol.RosterEntry> = world.chains.map { c ->
        Protocol.RosterEntry(c.id, c.name, c.isHuman, c.connected, c.skin, c.trailId, c.teamColor)
    }

    private fun fullCollectibles() = world.collectibles.map { Protocol.CollectibleInfo(it.id, it.level, it.x, it.y, it.isDrop) }

    private fun buildState(includeEvents: Boolean = true): Protocol.State {
        val chains = world.chains.map { c ->
            Protocol.ChainState(c.id, c.alive, c.isProtected, c.boost.isBoosting, c.headX, c.headY, c.heading, c.speed, c.boost.energy,
                c.stats.score, c.stats.highestLevel, c.stats.maxChainLength, c.stats.opponentsDefeated,
                IntArray(c.model.length) { c.model.cells[it].level }, world.respawnIn(c) ?: 0f)
        }
        val spawned = world.netSpawned.filter { world.findCollectible(it.id) != null }.map { Protocol.CollectibleInfo(it.id, it.level, it.x, it.y, it.isDrop) }
        val removed = world.netRemoved.toLongArray()
        val moved = world.netMoved.filter { world.findCollectible(it.id) != null }.map { Protocol.CollectibleInfo(it.id, it.level, it.x, it.y, it.isDrop) }
        val events = if (includeEvents) world.netEvents.mapNotNull { toNet(it) } else emptyList()
        world.netSpawned.clear(); world.netRemoved.clear(); world.netMoved.clear(); world.netEvents.clear()
        return Protocol.State(tick++, world.time.toFloat(), chains, spawned, removed, moved, events)
    }

    private fun toNet(e: WorldEvent): Protocol.NetEvent? = when (e) {
        is WorldEvent.Collected -> Protocol.NetEvent(Protocol.Ev.COLLECTED, e.chain.id, 0, e.level, e.x, e.y, 0)
        is WorldEvent.Merged -> Protocol.NetEvent(Protocol.Ev.MERGED, e.chain.id, 0, e.level, e.x, e.y, 0)
        is WorldEvent.ScoreGained -> if (e.chain.isHuman) Protocol.NetEvent(Protocol.Ev.SCORE, e.chain.id, 0, 0, e.x, e.y, e.points) else null
        is WorldEvent.Defeated -> Protocol.NetEvent(Protocol.Ev.DEFEATED, e.victim.id, e.killer?.id ?: 0, e.topLevel, e.x, e.y, 0)
        is WorldEvent.Severed -> Protocol.NetEvent(Protocol.Ev.SEVERED, e.victim.id, e.attacker.id, 0, e.x, e.y, 0)
        is WorldEvent.Bounced -> Protocol.NetEvent(Protocol.Ev.BOUNCED, e.a.id, e.b.id, 0, e.x, e.y, 0)
        is WorldEvent.BoostStarted -> Protocol.NetEvent(Protocol.Ev.BOOST, e.chain.id, 0, 0, 0f, 0f, 0)
        is WorldEvent.Blocked -> Protocol.NetEvent(Protocol.Ev.BLOCKED, e.chain.id, 0, 0, e.x, e.y, 0)
        is WorldEvent.Respawned -> Protocol.NetEvent(Protocol.Ev.RESPAWNED, e.chain.id, 0, 0, 0f, 0f, 0)
    }

    fun close(reason: String = "The host ended the room.") {
        try { transport.broadcast(Protocol.closed(reason)) } catch (_: Exception) {}
        transport.stop()
    }
}
