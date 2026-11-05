package com.oxdh.cubeclash.engine.net

import com.oxdh.cubeclash.engine.AIDifficulty
import com.oxdh.cubeclash.engine.GameConfig
import com.oxdh.cubeclash.engine.GameMode
import com.oxdh.cubeclash.engine.GameModeSettings
import com.oxdh.cubeclash.engine.Obstacle
import com.oxdh.cubeclash.engine.Vec2
import com.oxdh.cubeclash.engine.World
import com.oxdh.cubeclash.engine.WorldOptions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import kotlin.math.abs

class ProtocolTest {
    @Test fun roundTrips() {
        val h = Protocol.decodeHello(Protocol.encode(Protocol.Hello("id-1", "Ana", "neon", "ice")))
        assertEquals("Ana", h.name); assertEquals("neon", h.skin); assertEquals(Protocol.VERSION, h.version)
        val i = Protocol.decodeInput(Protocol.encode(Protocol.Input(7, true, 1.25f, true)))
        assertEquals(7, i.seq); assertTrue(i.steering); assertEquals(1.25f, i.angle); assertTrue(i.boost)
        val w = Protocol.decodeWelcome(Protocol.encode(Protocol.Welcome(42, 99L, 2, 1, 120f, listOf(Obstacle(Vec2(1f, 2f), 3f, 4f)), "Host", "ABC234")))
        assertEquals(42, w.yourChainId); assertEquals(99L, w.seed); assertEquals(2, w.mode); assertEquals(1, w.obstacles.size); assertEquals("ABC234", w.roomCode)
        val s = Protocol.State(5, 1.5f,
            listOf(Protocol.ChainState(3, true, false, true, 1f, 2f, 0.5f, 7f, 0.4f, 1234, 6, 9, 2, intArrayOf(6, 4, 1), 0f)),
            listOf(Protocol.CollectibleInfo(11, 3, 5f, 6f, true)), longArrayOf((11L shl 32) or 3L),
            listOf(Protocol.CollectibleInfo(12, 0, 8f, 9f, false)), listOf(Protocol.NetEvent(Protocol.Ev.MERGED, 3, 0, 6, 1f, 2f, 0)))
        val d = Protocol.decodeState(Protocol.encode(s))
        assertEquals(5, d.tick); assertEquals(1, d.chains.size)
        val c = d.chains[0]
        assertTrue(c.alive); assertFalse(c.protectedFlag); assertTrue(c.boosting); assertEquals(1234L, c.score)
        assertArrayEquals(intArrayOf(6, 4, 1), c.levels); assertEquals(0.4f, c.energy, 0.01f)
        assertEquals(11, d.spawned[0].id); assertTrue(d.spawned[0].isDrop); assertEquals(1, d.removed.size)
        assertEquals(8f, d.moved[0].x); assertEquals(Protocol.Ev.MERGED, d.events[0].type)
        val r = Protocol.decodeRoster(Protocol.encodeRoster(listOf(Protocol.RosterEntry(1, "Me", true, false, "classic", "spark", -1))))
        assertEquals("Me", r[0].name); assertTrue(r[0].isHuman); assertFalse(r[0].connected)
        assertEquals("bye", Protocol.decodeReason(Protocol.closed("bye")))
        assertEquals(1, Protocol.decodeFull(Protocol.encodeFull(listOf(Protocol.CollectibleInfo(1, 2, 3f, 4f, false)))).size)
    }

    @Test fun mqttPacketsAndRoomCodes() {
        val p = MqttConnection.packet(0x30, ByteArray(321))
        val len = MqttConnection.readLength(java.io.DataInputStream(p.inputStream(1, p.size - 1)))
        assertEquals(321, len)
        for (i in Online.RELAYS.indices) repeat(20) {
            val code = Online.newCode(i)
            assertTrue(Online.isValidCode(code))
            assertEquals(Online.RELAYS[i], Online.relayFor(code))
        }
        assertEquals("ABC234", Online.normalize(" abc234 "))
    }
}

/** Host and clients over the in-memory transport: real HostSession / ClientSession / replica worlds. */
class LoopbackTest {
    private fun hostWorld(bots: Int = 2) = World(GameConfig(), GameModeSettings.forMode(GameMode.CLASSIC), AIDifficulty.NORMAL, 11,
        options = WorldOptions(botCount = bots, respawnHumans = true, trackNetChanges = true, playerName = "Host"))

    private fun runBoth(host: HostSession, clients: List<ClientSession>, seconds: Float, each: () -> Unit = {}) {
        repeat((seconds * 60).toInt()) { host.tick(1f / 60f); clients.forEach { it.tick(1f / 60f) }; each() }
    }

    private fun waitConnected(c: ClientSession, host: HostSession) {
        val end = System.currentTimeMillis() + 3000
        while (c.status != ClientSession.Status.PLAYING && System.currentTimeMillis() < end) { host.tick(1f / 60f); c.tick(1f / 60f); Thread.sleep(5) }
        assertEquals(ClientSession.Status.PLAYING, c.status)
    }

    @Test fun clientsMirrorTheHostAndControlTheirChain() {
        val hub = InMemoryHub()
        val host = HostSession(hostWorld(), hub.hostTransport, "MEMORY", "Host")
        host.start()
        val a = ClientSession({ hub.client() }, "client-a", "Ana", "neon", "ice")
        val b = ClientSession({ hub.client() }, "client-b", "Ben", "classic", "spark")
        a.start(); b.start()
        waitConnected(a, host); waitConnected(b, host)
        assertEquals(2, host.connectedCount)
        assertEquals("host world has host + 2 bots + 2 people", 5, host.world.chains.size)

        // Ana steers east, Ben north; the host's chains follow the remote input.
        a.input.direction = Vec2(1f, 0f)
        b.input.direction = Vec2(0f, 1f)
        runBoth(host, listOf(a, b), 3f)
        val hostA = host.world.findChain(a.welcome!!.yourChainId)!!
        val hostB = host.world.findChain(b.welcome!!.yourChainId)!!
        // (Headings can be deflected by solid cubes, so check the steering the host received.)
        assertEquals("Ana steers east", 0f, hostA.desiredDirection!!.angle, 0.01f)
        assertEquals("Ben steers north", 1.5708f, hostB.desiredDirection!!.angle, 0.01f)

        // Replicas agree with the host: same chains, same numbers, positions within interpolation distance.
        for (client in listOf(a, b)) {
            val w = client.world!!
            assertEquals(host.world.chains.map { it.id }.toSet(), w.chains.map { it.id }.toSet())
            for (hc in host.world.chains) {
                val rc = w.findChain(hc.id)!!
                assertEquals(hc.alive, rc.alive)
                if (!hc.alive) continue
                assertEquals("levels of ${hc.name}", hc.model.toString(), rc.model.toString())
                assertEquals(hc.stats.score, rc.stats.score)
                assertTrue("position of ${hc.name}", Vec2.distance(hc.headPosition, rc.headPosition) < 3.5f)
            }
            assertEquals("collectibles mirrored", host.world.collectibles.map { it.id }.toSet(), w.collectibles.map { it.id }.toSet())
            assertEquals(client.welcome!!.yourChainId, w.player.id)
        }
    }

    @Test fun reconnectKeepsTheSameChain() {
        val hub = InMemoryHub()
        val host = HostSession(hostWorld(0), hub.hostTransport, "MEMORY", "Host", timeoutSeconds = 1f)
        host.start()
        val a = ClientSession({ hub.client() }, "client-a", "Ana", "classic", "spark", hostTimeout = 1f)
        a.start(); waitConnected(a, host)
        val chainId = a.welcome!!.yourChainId
        runBoth(host, listOf(a), 2f)
        val scoreBefore = host.world.findChain(chainId)!!.stats.score
        // The connection goes silent: the host keeps the chain (AI stand-in), the client starts reconnecting.
        hub.dropAll = true
        runBoth(host, listOf(a), 2f)
        assertFalse(host.peers.first().connected)
        assertNotNull(host.world.findChain(chainId))
        assertEquals(ClientSession.Status.RECONNECTING, a.status)
        hub.dropAll = false
        val end = System.currentTimeMillis() + 5000
        while (a.status != ClientSession.Status.PLAYING && System.currentTimeMillis() < end) { host.tick(1f / 60f); a.tick(1f / 60f); Thread.sleep(2) }
        assertEquals(ClientSession.Status.PLAYING, a.status)
        assertEquals("re-attached to the same chain", chainId, a.welcome!!.yourChainId)
        assertTrue(host.peers.first().connected)
        assertTrue(host.world.findChain(chainId)!!.stats.score >= scoreBefore)
        assertEquals(1, host.peers.size)
    }

    @Test fun leavingRemovesThePlayer() {
        val hub = InMemoryHub()
        val host = HostSession(hostWorld(0), hub.hostTransport, "MEMORY", "Host")
        host.start()
        val a = ClientSession({ hub.client() }, "client-a", "Ana", "classic", "spark")
        a.start(); waitConnected(a, host)
        val id = a.welcome!!.yourChainId
        a.leave()
        runBoth(host, emptyList(), 0.2f)
        assertEquals(null, host.world.findChain(id))
    }
}

/** Real TCP sockets on this machine (the same code the phones use on Wi-Fi / hotspot). */
class LanTest {
    @Test fun hostAndClientOverTcpWithDiscovery() {
        val world = World(GameConfig(), GameModeSettings.forMode(GameMode.CLASSIC), AIDifficulty.NORMAL, 5,
            options = WorldOptions(botCount = 1, respawnHumans = true, trackNetChanges = true))
        val discoveryPort = 47900 + (System.nanoTime() % 50).toInt()
        val transport = Lan.HostTransportImpl({ "Test room" }, "LAN", { 1 }, port = 0, discoveryPort = discoveryPort)
        val host = HostSession(world, transport, "LAN", "Host")
        host.start()
        Thread.sleep(200)
        val rooms = Lan.discover(800, discoveryPort, listOf("127.0.0.1"))
        assertTrue("room discovered: $rooms", rooms.any { it.port == transport.port && it.name == "Test room" })

        val client = ClientSession({ Lan.ClientTransportImpl("127.0.0.1", transport.port) }, "tcp-client", "Tess", "classic", "spark")
        client.start()
        client.input.direction = Vec2(-1f, 0f)
        val end = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < end && client.statesReceived < 40) {
            host.tick(1f / 60f); client.tick(1f / 60f); Thread.sleep(16)
        }
        assertEquals(ClientSession.Status.PLAYING, client.status)
        assertTrue("received states: ${client.statesReceived}", client.statesReceived >= 40)
        val mine = host.world.findChain(client.welcome!!.yourChainId)!!
        assertEquals("host applied the remote input", Math.PI.toFloat(), abs(mine.desiredDirection!!.angle), 0.01f)
        assertEquals(host.world.collectibles.size, client.world!!.collectibles.size)
        host.close()
        val t = System.currentTimeMillis() + 2000
        while (client.status != ClientSession.Status.CLOSED && System.currentTimeMillis() < t) { client.tick(1f / 60f); Thread.sleep(10) }
        assertEquals(ClientSession.Status.CLOSED, client.status)
    }
}

/**
 * Online play through the real public relays. Runs only when CUBECLASH_ONLINE_TESTS=1 (CI). Measures every relay
 * (states per second, ping, inputs delivered) and requires at least one to be good enough to play on.
 */
class OnlineLiveTest {
    private fun measure(relayIndex: Int, seconds: Float): String {
        val world = World(GameConfig(), GameModeSettings.forMode(GameMode.CLASSIC), AIDifficulty.NORMAL, 9,
            options = WorldOptions(botCount = 2, respawnHumans = true, trackNetChanges = true))
        val transport = Online.HostTransportImpl("CI host") { 1 }
        val code = try { transport.openOn(relayIndex) } catch (e: Exception) { return "unreachable (${e.message})" }
        val host = HostSession(world, transport, code, "CI host", stateRate = Online.STATE_RATE, fullResyncSeconds = 5f)
        host.start()
        val cid = Online.randomId("cid-")
        val client = ClientSession({ Online.ClientTransportImpl(code, cid) }, cid, "Remote", "classic", "spark")
        client.start()
        var firstState = -1L
        var turn = 0f
        val start = System.currentTimeMillis()
        val end = start + (seconds * 1000).toLong()
        while (System.currentTimeMillis() < end) {
            turn += 0.02f
            client.input.direction = Vec2.fromAngle(turn) // a player steering continuously
            host.tick(1f / 60f); client.tick(1f / 60f)
            if (firstState < 0 && client.statesReceived > 0) firstState = System.currentTimeMillis()
            Thread.sleep(16)
        }
        val playing = if (firstState > 0) (System.currentTimeMillis() - firstState) / 1000f else 0f
        val rate = if (playing > 0) client.statesReceived / playing else 0f
        val inputs = host.peers.firstOrNull()?.inputs ?: 0
        val status = client.status
        client.leave(); host.close()
        return "status=$status join=${if (firstState > 0) (firstState - start) else -1}ms states/s=${"%.1f".format(rate)} rtt=${client.lastRttMs}ms inputs=$inputs"
    }

    @Test fun relaysCarryARealSession() {
        Assume.assumeTrue(System.getenv("CUBECLASH_ONLINE_TESTS") == "1")
        val results = Online.RELAYS.indices.map { i -> Online.RELAYS[i].host to measure(i, 12f) }
        results.forEach { println("[online] ${it.first}: ${it.second}") }
        val good = results.count { (_, r) ->
            val rate = Regex("states/s=([0-9.]+)").find(r)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            r.startsWith("status=PLAYING") && rate >= 5f
        }
        assertTrue("at least one relay must carry a playable session: $results", good >= 1)
    }
}
