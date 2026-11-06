package com.oxdh.cubeclash.engine.net

import com.oxdh.cubeclash.engine.Obstacle
import com.oxdh.cubeclash.engine.Vec2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Compact binary messages shared by every transport (LAN TCP and the online relay).
 * The host is authoritative: clients only send input; the host sends the world state ~20 times per second.
 */
object Protocol {
    const val VERSION = 1

    // Client -> host
    const val HELLO: Byte = 1
    const val INPUT: Byte = 2
    const val PING: Byte = 3
    const val LEAVE: Byte = 4
    /** Sent by the relay on a client's behalf when its connection drops (keep its chain for reconnect). */
    const val GONE: Byte = 5
    // Host -> client
    const val WELCOME: Byte = 10
    const val STATE: Byte = 11
    const val FULL: Byte = 12
    const val ROSTER: Byte = 13
    const val PONG: Byte = 14
    const val CLOSED: Byte = 15
    const val REJECT: Byte = 16
    const val ROOM_INFO: Byte = 17

    fun type(bytes: ByteArray): Byte = if (bytes.isEmpty()) 0 else bytes[0]

    private inline fun write(type: Byte, block: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream(256)
        DataOutputStream(bos).use { it.writeByte(type.toInt()); it.block() }
        return bos.toByteArray()
    }

    private fun reader(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1))

    // ------------------------------------------------------------------ messages

    data class Hello(val clientId: String, val name: String, val skin: String, val trail: String, val version: Int = VERSION)

    fun encode(m: Hello) = write(HELLO) { writeUTF(m.clientId); writeUTF(m.name); writeUTF(m.skin); writeUTF(m.trail); writeInt(m.version) }
    fun decodeHello(b: ByteArray) = reader(b).run { Hello(readUTF(), readUTF(), readUTF(), readUTF(), readInt()) }

    /** Steering: [angle] in radians (when [steering]), plus boost. [seq] lets the host drop stale inputs. */
    data class Input(val seq: Int, val steering: Boolean, val angle: Float, val boost: Boolean)

    fun encode(m: Input) = write(INPUT) {
        writeInt(m.seq); writeByte((if (m.steering) 1 else 0) or (if (m.boost) 2 else 0)); writeFloat(m.angle)
    }
    fun decodeInput(b: ByteArray) = reader(b).run { val seq = readInt(); val f = readByte().toInt(); Input(seq, f and 1 != 0, readFloat(), f and 2 != 0) }

    fun ping(time: Long) = write(PING) { writeLong(time) }
    fun pong(time: Long) = write(PONG) { writeLong(time) }
    fun decodeTime(b: ByteArray) = reader(b).readLong()
    fun leave() = write(LEAVE) {}
    fun gone() = write(GONE) {}

    data class Welcome(
        val yourChainId: Int, val seed: Long, val mode: Int, val difficulty: Int, val arenaRadius: Float,
        val obstacles: List<Obstacle>, val hostName: String, val roomCode: String,
    )

    fun encode(m: Welcome) = write(WELCOME) {
        writeInt(VERSION); writeInt(m.yourChainId); writeLong(m.seed); writeByte(m.mode); writeByte(m.difficulty); writeFloat(m.arenaRadius)
        writeShort(m.obstacles.size)
        for (o in m.obstacles) { writeFloat(o.position.x); writeFloat(o.position.y); writeFloat(o.radius); writeFloat(o.height) }
        writeUTF(m.hostName); writeUTF(m.roomCode)
    }
    fun decodeWelcome(b: ByteArray) = reader(b).run {
        readInt()
        val id = readInt(); val seed = readLong(); val mode = readByte().toInt(); val diff = readByte().toInt(); val r = readFloat()
        val obs = List(readShort().toInt()) { Obstacle(Vec2(readFloat(), readFloat()), readFloat(), readFloat()) }
        Welcome(id, seed, mode, diff, r, obs, readUTF(), readUTF())
    }

    data class RosterEntry(val chainId: Int, val name: String, val isHuman: Boolean, val connected: Boolean, val skin: String, val trail: String, val color: Int)

    fun encodeRoster(list: List<RosterEntry>) = write(ROSTER) {
        writeShort(list.size)
        for (e in list) { writeInt(e.chainId); writeUTF(e.name); writeByte((if (e.isHuman) 1 else 0) or (if (e.connected) 2 else 0)); writeUTF(e.skin); writeUTF(e.trail); writeInt(e.color) }
    }
    fun decodeRoster(b: ByteArray) = reader(b).run {
        List(readShort().toInt()) {
            val id = readInt(); val name = readUTF(); val f = readByte().toInt()
            RosterEntry(id, name, f and 1 != 0, f and 2 != 0, readUTF(), readUTF(), readInt())
        }
    }

    class ChainState(
        val id: Int, val alive: Boolean, val protectedFlag: Boolean, val boosting: Boolean,
        val x: Float, val y: Float, val heading: Float, val speed: Float, val energy: Float,
        val score: Long, val highest: Int, val maxLen: Int, val defeats: Int, val levels: IntArray, val respawnIn: Float,
    )

    class CollectibleInfo(val id: Int, val level: Int, val x: Float, val y: Float, val isDrop: Boolean)

    /** One replicated world event (sound / effects / messages on clients). */
    class NetEvent(val type: Int, val chainId: Int, val otherId: Int, val level: Int, val x: Float, val y: Float, val value: Long)

    object Ev { const val COLLECTED = 1; const val MERGED = 2; const val SCORE = 3; const val DEFEATED = 4; const val SEVERED = 5
        const val BOUNCED = 6; const val BOOST = 7; const val BLOCKED = 8; const val RESPAWNED = 9 }

    class State(
        val tick: Int, val hostTime: Float, val chains: List<ChainState>,
        val spawned: List<CollectibleInfo>, val removed: LongArray, val moved: List<CollectibleInfo>, val events: List<NetEvent>,
    )

    fun encode(m: State) = write(STATE) {
        writeInt(m.tick); writeFloat(m.hostTime)
        writeShort(m.chains.size)
        for (c in m.chains) {
            writeInt(c.id)
            writeByte((if (c.alive) 1 else 0) or (if (c.protectedFlag) 2 else 0) or (if (c.boosting) 4 else 0))
            writeFloat(c.x); writeFloat(c.y); writeFloat(c.heading); writeFloat(c.speed)
            writeByte((c.energy.coerceIn(0f, 1f) * 255).toInt())
            writeLong(c.score); writeShort(c.highest); writeShort(c.maxLen); writeShort(c.defeats)
            writeFloat(c.respawnIn)
            writeByte(c.levels.size.coerceAtMost(255))
            for (i in 0 until c.levels.size.coerceAtMost(255)) writeShort(c.levels[i])
        }
        writeCollectibles(m.spawned)
        writeShort(m.removed.size); for (r in m.removed) writeLong(r)
        writeShort(m.moved.size); for (c in m.moved) { writeInt(c.id); writeFloat(c.x); writeFloat(c.y) }
        writeShort(m.events.size)
        for (e in m.events) { writeByte(e.type); writeInt(e.chainId); writeInt(e.otherId); writeShort(e.level); writeFloat(e.x); writeFloat(e.y); writeLong(e.value) }
    }

    fun decodeState(b: ByteArray): State = reader(b).run {
        val tick = readInt(); val t = readFloat()
        val chains = List(readShort().toInt()) {
            val id = readInt(); val f = readByte().toInt()
            val x = readFloat(); val y = readFloat(); val h = readFloat(); val sp = readFloat()
            val en = (readByte().toInt() and 0xff) / 255f
            val score = readLong(); val hi = readShort().toInt(); val ml = readShort().toInt(); val df = readShort().toInt()
            val rs = readFloat()
            val levels = IntArray(readByte().toInt() and 0xff) { readShort().toInt() }
            ChainState(id, f and 1 != 0, f and 2 != 0, f and 4 != 0, x, y, h, sp, en, score, hi, ml, df, levels, rs)
        }
        val spawned = readCollectibles()
        val removed = LongArray(readShort().toInt()) { readLong() }
        val moved = List(readShort().toInt()) { CollectibleInfo(readInt(), 0, readFloat(), readFloat(), false) }
        val events = List(readShort().toInt()) { NetEvent(readByte().toInt(), readInt(), readInt(), readShort().toInt(), readFloat(), readFloat(), readLong()) }
        State(tick, t, chains, spawned, removed, moved, events)
    }

    /** Full collectible list (on join and periodically, so a missed delta can never cause lasting drift). */
    fun encodeFull(list: List<CollectibleInfo>) = write(FULL) { writeCollectibles(list) }
    fun decodeFull(b: ByteArray) = reader(b).readCollectibles()

    private fun DataOutputStream.writeCollectibles(list: List<CollectibleInfo>) {
        writeShort(list.size)
        for (c in list) { writeInt(c.id); writeShort(c.level); writeFloat(c.x); writeFloat(c.y); writeBoolean(c.isDrop) }
    }
    private fun DataInputStream.readCollectibles() = List(readShort().toInt()) { CollectibleInfo(readInt(), readShort().toInt(), readFloat(), readFloat(), readBoolean()) }

    fun closed(reason: String) = write(CLOSED) { writeUTF(reason) }
    fun reject(reason: String) = write(REJECT) { writeUTF(reason) }
    fun decodeReason(b: ByteArray) = reader(b).readUTF()

    /** Retained room announcement on the online relay. */
    fun roomInfo(hostName: String, players: Int) = write(ROOM_INFO) { writeUTF(hostName); writeShort(players); writeLong(System.currentTimeMillis()) }
    fun decodeRoomInfo(b: ByteArray) = reader(b).run { Triple(readUTF(), readShort().toInt(), readLong()) }
}
