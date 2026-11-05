package com.oxdh.cubeclash.engine.net

import com.oxdh.cubeclash.engine.AIDifficulty
import com.oxdh.cubeclash.engine.Chain
import com.oxdh.cubeclash.engine.GameConfig
import com.oxdh.cubeclash.engine.GameMode
import com.oxdh.cubeclash.engine.GameModeSettings
import com.oxdh.cubeclash.engine.InputBrain
import com.oxdh.cubeclash.engine.MathUtil
import com.oxdh.cubeclash.engine.World
import com.oxdh.cubeclash.engine.WorldEvent
import com.oxdh.cubeclash.engine.WorldOptions
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A remote player's view of a room. Keeps a replica [World] that mirrors the host (no local simulation), smooths
 * other chains with snapshot interpolation, sends this player's input ~20 times per second, and reconnects
 * automatically when the connection drops.
 */
class ClientSession(
    private val factory: ClientTransportFactory,
    val clientId: String,
    val name: String,
    private val skin: String,
    private val trail: String,
    /** Interpolation delay in seconds (bigger = smoother on bad networks, but more latency). */
    private val interpolationDelay: Float = 0.1f,
    private val hostTimeout: Float = 4f,
) {
    enum class Status { CONNECTING, PLAYING, RECONNECTING, CLOSED }

    @Volatile var status = Status.CONNECTING; private set
    @Volatile var statusMessage = "Connecting…"; private set
    var world: World? = null; private set
    var welcome: Protocol.Welcome? = null; private set
    /** Written by the UI (touch) or a bot; sent to the host. */
    val input = InputBrain()
    var statesReceived = 0; private set
    var lastRttMs = -1L; private set
    val roster = LinkedHashMap<Int, Protocol.RosterEntry>()

    private val inbox = ConcurrentLinkedQueue<ByteArray>()
    private val drops = ConcurrentLinkedQueue<String>()
    @Volatile private var transport: ClientTransport? = null
    private var sinceState = 0f
    private var sendTimer = 0f
    private var pingTimer = 0f
    private var seq = 0
    private var lastSentSteering = false
    private var lastSentBoost = false
    private var lastSentAngle = 0f
    private var clock = 0f
    private var reconnectTimer = 0f
    private var reconnectAttempts = 0
    private val pendingEvents = ArrayList<WorldEvent>()
    private var lastTick = -1

    private class Sample(val t: Float, val x: Float, val y: Float, val heading: Float, val speed: Float)
    private class Track { val samples = ArrayDeque<Sample>(); var needsReset = true }
    private val tracks = HashMap<Int, Track>()
    private var hostTimeOffset = Float.NaN

    /** Opens the connection on a background thread. */
    fun start() = connectAsync()

    private fun connectAsync() {
        val attempt = ++reconnectAttempts
        Thread({
            val t = factory.create()
            try {
                t.connect(object : ClientTransport.Listener {
                    override fun onMessage(bytes: ByteArray) { inbox.add(bytes) }
                    override fun onDisconnected(reason: String) { drops.add(reason) }
                })
                transport = t
                t.send(Protocol.encode(Protocol.Hello(clientId, name, skin, trail)))
            } catch (e: Exception) {
                drops.add(e.message ?: "Could not connect")
            }
        }, "cc-connect-$attempt").apply { isDaemon = true }.start()
    }

    /** Manual "Reconnect" button. */
    fun reconnectNow() {
        if (status == Status.CLOSED && welcome == null) return
        transport?.close(); transport = null
        status = Status.RECONNECTING; statusMessage = "Reconnecting…"
        reconnectTimer = 0f
        connectAsync()
    }

    fun leave() {
        try { transport?.send(Protocol.leave()) } catch (_: Exception) {}
        transport?.close()
        transport = null
        status = Status.CLOSED; statusMessage = "Left the room"
    }

    /** Call once per frame on the game thread. */
    fun tick(dt: Float) {
        clock += dt
        while (true) handle(inbox.poll() ?: break)
        while (true) {
            val reason = drops.poll() ?: break
            if (status == Status.CLOSED) continue
            transport = null
            if (welcome == null && status == Status.CONNECTING && reconnectAttempts >= 3) { status = Status.CLOSED; statusMessage = reason }
            else { status = Status.RECONNECTING; statusMessage = "Connection lost — reconnecting…" }
        }

        if (status == Status.PLAYING) {
            sinceState += dt
            if (sinceState > hostTimeout) { status = Status.RECONNECTING; statusMessage = "Host not responding — reconnecting…"; transport?.close(); transport = null }
        }
        if (status == Status.RECONNECTING || (status == Status.CONNECTING && transport == null)) {
            reconnectTimer += dt
            if (reconnectTimer > 2f) { reconnectTimer = 0f; connectAsync() }
        }

        val t = transport
        if (t != null && status != Status.CLOSED) {
            sendTimer += dt; pingTimer += dt
            // Send input when it changes (at most ~12/s) plus a slow heartbeat: relays rate-limit publishers.
            val d = input.direction
            val angle = d?.angle ?: 0f
            val changed = (d != null) != lastSentSteering || input.boost != lastSentBoost ||
                (d != null && kotlin.math.abs(MathUtil.wrapAngle(angle - lastSentAngle)) > 0.06f)
            if ((changed && sendTimer >= 0.08f) || sendTimer >= 0.4f) {
                sendTimer = 0f
                lastSentSteering = d != null; lastSentBoost = input.boost; lastSentAngle = angle
                try { t.send(Protocol.encode(Protocol.Input(++seq, d != null, angle, input.boost))) } catch (_: Exception) {}
            }
            if (pingTimer >= 2f) { pingTimer = 0f; try { t.send(Protocol.ping(System.nanoTime())) } catch (_: Exception) {} }
        }

        val w = world ?: return
        interpolate(w)
        w.replicaFrame(dt, pendingEvents)
        pendingEvents.clear()
    }

    private fun handle(bytes: ByteArray) {
        when (Protocol.type(bytes)) {
            Protocol.WELCOME -> onWelcome(Protocol.decodeWelcome(bytes))
            Protocol.ROSTER -> onRoster(Protocol.decodeRoster(bytes))
            Protocol.FULL -> world?.let { w ->
                w.replicaClearCollectibles()
                for (c in Protocol.decodeFull(bytes)) w.replicaSpawnCollectible(c.id, c.level, c.x, c.y, c.isDrop)
            }
            Protocol.STATE -> onState(Protocol.decodeState(bytes))
            Protocol.PONG -> lastRttMs = (System.nanoTime() - Protocol.decodeTime(bytes)) / 1_000_000
            Protocol.CLOSED -> { status = Status.CLOSED; statusMessage = Protocol.decodeReason(bytes); transport?.close(); transport = null }
            Protocol.REJECT -> { status = Status.CLOSED; statusMessage = Protocol.decodeReason(bytes); transport?.close(); transport = null }
        }
    }

    private fun onWelcome(m: Protocol.Welcome) {
        val existing = world
        if (existing == null || welcome?.yourChainId != m.yourChainId) {
            val mode = GameMode.values().getOrElse(m.mode) { GameMode.CLASSIC }
            val diff = AIDifficulty.values().getOrElse(m.difficulty) { AIDifficulty.NORMAL }
            world = World(GameConfig(), GameModeSettings.forMode(mode), diff, m.seed, skin, trail,
                WorldOptions(replica = true, obstacles = m.obstacles, playerName = name))
            tracks.clear()
            hostTimeOffset = Float.NaN
            lastTick = -1
        }
        welcome = m
        status = Status.PLAYING; statusMessage = "Connected"
        sinceState = 0f
        reconnectAttempts = 0
    }

    private fun onRoster(list: List<Protocol.RosterEntry>) {
        val w = world ?: return
        roster.clear()
        for (e in list) {
            roster[e.chainId] = e
            val c = w.replicaChain(e.chainId, e.name, e.isHuman, e.skin, e.trail, e.color)
            c.connected = e.connected
            if (e.chainId == welcome?.yourChainId) w.replicaSetLocalPlayer(c)
        }
        val ids = list.map { it.chainId }.toSet()
        for (c in w.chains.toList()) if (c.id !in ids) { w.replicaRemoveChain(c.id); tracks.remove(c.id) }
    }

    private fun onState(s: Protocol.State) {
        val w = world ?: return
        if (s.tick <= lastTick && lastTick - s.tick < 1000) return
        lastTick = s.tick
        statesReceived++
        sinceState = 0f
        if (status != Status.PLAYING) { status = Status.PLAYING; statusMessage = "Connected" }
        // Map host time to local clock (keeps the smallest observed offset = least delayed packet).
        val offset = clock - s.hostTime
        hostTimeOffset = if (hostTimeOffset.isNaN() || offset < hostTimeOffset) offset else hostTimeOffset + (offset - hostTimeOffset) * 0.02f

        for (cs in s.chains) {
            val c = w.findChain(cs.id) ?: continue
            val wasAlive = c.alive
            w.replicaApplyChain(c, cs.alive, cs.protectedFlag, cs.boosting, cs.energy, cs.levels, cs.score, cs.highest, cs.maxLen, cs.defeats)
            c.respawnCountdown = cs.respawnIn
            val tr = tracks.getOrPut(cs.id) { Track() }
            if (!cs.alive) { tr.samples.clear(); tr.needsReset = true; continue }
            if (!wasAlive) { tr.samples.clear(); tr.needsReset = true }
            tr.samples.addLast(Sample(s.hostTime, cs.x, cs.y, cs.heading, cs.speed))
            while (tr.samples.size > 30) tr.samples.removeFirst()
        }
        for (c in s.spawned) w.replicaSpawnCollectible(c.id, c.level, c.x, c.y, c.isDrop)
        for (c in s.moved) w.replicaMoveCollectible(c.id, c.x, c.y)
        for (r in s.removed) w.replicaRemoveCollectible((r ushr 32).toInt(), (r and 0xffffffffL).toInt())
        for (e in s.events) toWorldEvent(w, e)?.let { pendingEvents.add(it) }
    }

    private fun toWorldEvent(w: World, e: Protocol.NetEvent): WorldEvent? {
        val c = w.findChain(e.chainId) ?: return null
        val o = if (e.otherId != 0) w.findChain(e.otherId) else null
        return when (e.type) {
            Protocol.Ev.COLLECTED -> WorldEvent.Collected(c, e.level, e.x, e.y)
            Protocol.Ev.MERGED -> WorldEvent.Merged(c, e.level, e.x, e.y)
            Protocol.Ev.SCORE -> WorldEvent.ScoreGained(c, e.value, e.x, e.y)
            Protocol.Ev.DEFEATED -> { c.lastEatenByName(o?.name); WorldEvent.Defeated(c, o, e.x, e.y, e.level) }
            Protocol.Ev.SEVERED -> if (o != null) WorldEvent.Severed(c, o, e.x, e.y) else null
            Protocol.Ev.BOUNCED -> if (o != null) WorldEvent.Bounced(c, o, e.x, e.y) else null
            Protocol.Ev.BOOST -> WorldEvent.BoostStarted(c)
            Protocol.Ev.BLOCKED -> WorldEvent.Blocked(c, e.x, e.y)
            Protocol.Ev.RESPAWNED -> WorldEvent.Respawned(c)
            else -> null
        }
    }

    /** Places every chain at its interpolated pose for "now - delay" in host time. */
    private fun interpolate(w: World) {
        if (hostTimeOffset.isNaN()) return
        val renderT = clock - hostTimeOffset - interpolationDelay
        for (c in w.chains) {
            if (!c.alive) continue
            val tr = tracks[c.id] ?: continue
            val sm = tr.samples
            if (sm.isEmpty()) continue
            var x: Float; var y: Float; var h: Float; val sp: Float
            if (renderT <= sm.first().t) { val f = sm.first(); x = f.x; y = f.y; h = f.heading; sp = f.speed }
            else if (renderT >= sm.last().t) {
                // Extrapolate briefly along the last heading (hides jitter / small gaps).
                val l = sm.last()
                val ahead = (renderT - l.t).coerceAtMost(0.25f)
                x = l.x + cos(l.heading) * l.speed * ahead; y = l.y + sin(l.heading) * l.speed * ahead; h = l.heading; sp = l.speed
            } else {
                var i = 0
                while (i < sm.size - 2 && sm[i + 1].t < renderT) i++
                val a = sm[i]; val b = sm[i + 1]
                val k = if (b.t > a.t) (renderT - a.t) / (b.t - a.t) else 1f
                x = a.x + (b.x - a.x) * k; y = a.y + (b.y - a.y) * k
                h = a.heading + MathUtil.wrapAngle(b.heading - a.heading) * k; sp = a.speed + (b.speed - a.speed) * k
            }
            val dx = x - c.headX; val dy = y - c.headY
            val reset = tr.needsReset || sqrt(dx * dx + dy * dy) > 8f
            tr.needsReset = false
            w.replicaMoveHead(c, x, y, h, sp, reset)
        }
    }

    val localChain: Chain? get() = world?.let { w -> welcome?.let { w.findChain(it.yourChainId) } }
}
