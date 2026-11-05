package com.oxdh.cubeclash.engine.net

import com.oxdh.cubeclash.engine.AIBehaviourConfig
import com.oxdh.cubeclash.engine.AIDifficulty
import com.oxdh.cubeclash.engine.GameConfig
import com.oxdh.cubeclash.engine.GameMode
import com.oxdh.cubeclash.engine.GameModeSettings
import com.oxdh.cubeclash.engine.Rng
import com.oxdh.cubeclash.engine.World
import com.oxdh.cubeclash.engine.WorldOptions
import java.io.File
import kotlin.system.exitProcess

/**
 * Headless multiplayer peer used by CI to play against the real app on an emulator:
 *   lan-join <host> <port> <seconds>        join a LAN room hosted by the app and play with an AI brain
 *   online-host <seconds> <codeFile>       host an online room (writes the code to codeFile) and wait for players
 * Prints a summary and exits non-zero if the session did not work.
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "help"
    val ok = when (mode) {
        "lan-join" -> join({ Lan.ClientTransportImpl(args[1], args[2].toInt()) }, args[3].toFloat())
        "online-join" -> join({ Online.ClientTransportImpl(args[1], Online.randomId("bot-")) }, args[2].toFloat())
        "online-host" -> onlineHost(args[1].toFloat(), File(args[2]))
        else -> { println("usage: lan-join <host> <port> <s> | online-join <code> <s> | online-host <s> <codeFile>"); false }
    }
    exitProcess(if (ok) 0 else 1)
}

private fun join(factory: ClientTransportFactory, seconds: Float): Boolean {
    val client = ClientSession(factory, Online.randomId("netbot-"), "CI Bot", "neon", "flame")
    client.start()
    val brain = World.AIBrain(AIBehaviourConfig.forDifficulty(AIDifficulty.NORMAL), Rng(7))
    var moved = 0f
    var lastX = Float.NaN; var lastY = Float.NaN
    val frames = (seconds * 60).toInt()
    repeat(frames) { i ->
        client.tick(1f / 60f)
        val w = client.world
        val me = client.localChain
        if (w != null && me != null && me.alive) {
            // Let an AI decide where to go from the replica, and send that as our touch input.
            brain.think(w, me, 1f / 60f)
            client.input.direction = me.desiredDirection
            client.input.boost = i % 300 < 60
            if (!lastX.isNaN()) moved += kotlin.math.hypot(me.headX - lastX, me.headY - lastY)
            lastX = me.headX; lastY = me.headY
        }
        if (i % 300 == 0) println("[netbot] t=${i / 60}s status=${client.status} states=${client.statesReceived} rtt=${client.lastRttMs}ms players=${client.roster.values.count { it.isHuman }} chain=${me?.model} score=${me?.stats?.score}")
        Thread.sleep(16)
    }
    // A software-rendered emulator host sends at most one state per rendered frame, so require a modest rate.
    val ok = client.welcome != null && client.statesReceived > seconds * 2 && moved > 10f
    println("[netbot] RESULT ${if (ok) "OK" else "FAIL"} status=${client.status} (${client.statusMessage}) states=${client.statesReceived} moved=${"%.1f".format(moved)} roster=${client.roster.values.map { it.name }}")
    client.leave()
    return ok
}

private fun onlineHost(seconds: Float, codeFile: File): Boolean {
    val world = World(GameConfig(), GameModeSettings.forMode(GameMode.CLASSIC), AIDifficulty.NORMAL, System.nanoTime(),
        options = WorldOptions(botCount = 3, respawnHumans = true, trackNetChanges = true, playerName = "CI Host"))
    world.player.brain = World.AIBrain(AIBehaviourConfig.forDifficulty(AIDifficulty.EASY), Rng(3))
    val transport = Online.HostTransportImpl("CI Host") { 1 }
    val code = transport.open()
    codeFile.writeText(code)
    println("[netbot] online room $code via ${Online.relayFor(code)}")
    val host = HostSession(world, transport, code, "CI Host", stateRate = Online.STATE_RATE, fullResyncSeconds = 5f)
    host.start()
    var maxPeers = 0; var inputs = 0
    repeat((seconds * 60).toInt()) { i ->
        host.tick(1f / 60f)
        maxPeers = maxOf(maxPeers, host.connectedCount)
        inputs = host.peers.sumOf { it.inputs }
        if (i % 300 == 0) println("[netbot] t=${i / 60}s peers=${host.peers.map { "${it.chain.name}:${if (it.connected) "on" else "off"}:${it.chain.model}" }} inputs=$inputs")
        Thread.sleep(16)
    }
    val best = host.peers.maxOfOrNull { it.chain.stats.highestLevel } ?: 0
    val collected = host.peers.sumOf { it.chain.stats.cubesCollected }
    val ok = maxPeers >= 1 && inputs > 30 && collected > 0
    println("[netbot] RESULT ${if (ok) "OK" else "FAIL"} maxPeers=$maxPeers inputs=$inputs collectedByPlayers=$collected bestLevel=$best")
    host.close()
    return ok
}
