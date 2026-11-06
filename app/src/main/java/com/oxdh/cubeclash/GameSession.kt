package com.oxdh.cubeclash

import com.oxdh.cubeclash.engine.AIDifficulty
import com.oxdh.cubeclash.engine.GameConfig
import com.oxdh.cubeclash.engine.GameMode
import com.oxdh.cubeclash.engine.GameModeSettings
import com.oxdh.cubeclash.engine.InputBrain
import com.oxdh.cubeclash.engine.PersonalBestResult
import com.oxdh.cubeclash.engine.Rng
import com.oxdh.cubeclash.engine.SessionStats
import com.oxdh.cubeclash.engine.World
import com.oxdh.cubeclash.engine.WorldOptions
import com.oxdh.cubeclash.engine.net.ClientSession
import com.oxdh.cubeclash.engine.net.HostSession
import com.oxdh.cubeclash.engine.net.HostTransport

/**
 * One run: solo, hosting a multiplayer room, or playing in someone else's room. The world is only touched on the
 * GL thread (advanced by [tick]); the UI thread talks to it through the volatile [input] brain and flags.
 */
class GameSession private constructor(
    val kind: Kind,
    val mode: GameMode,
    val difficulty: AIDifficulty,
    private val soloWorld: World?,
    val host: HostSession?,
    val client: ClientSession?,
    /** Debug/CI: the local chain is driven by the rival AI. */
    val autoplay: Boolean,
    /** Shown in the HUD, e.g. "Online room K7Q2PX" or "Local room 192.168.1.20". */
    val roomLabel: String?,
    /** Online room code to share, if any. */
    val inviteCode: String?,
) {
    enum class Kind { SOLO, HOST, CLIENT }

    val modeSettings: GameModeSettings = GameModeSettings.forMode(mode)
    val hud = com.oxdh.cubeclash.ui.HudState()
    val isMultiplayer: Boolean get() = kind != Kind.SOLO

    /** Player input (touch). For clients this is the brain whose values are sent to the host. */
    val input: InputBrain = client?.input ?: InputBrain()

    private val placeholder by lazy { World(GameConfig(), modeSettings, difficulty, 1, options = WorldOptions(replica = true)) }

    /** The world to render: the real one (solo / host) or the replica (client). */
    val world: World get() = soloWorld ?: host?.world ?: client?.world ?: placeholder

    /** The local player's cube skin (for the renderer's number atlas). */
    var localSkin = "classic"

    @Volatile var paused = false
    @Volatile var endRequested = false
    @Volatile var ended = false
    @Volatile var summary: Summary? = null
    class Summary(val title: String, val stats: SessionStats, val result: PersonalBestResult, val skin: String)

    private var autoBrain: World.AIBrain? = null

    init {
        val w = soloWorld ?: host?.world
        if (w != null) w.player.brain = if (autoplay) World.AIBrain(GameConfig().aiFor(AIDifficulty.HARD), Rng(7)) else input
        if (client != null && autoplay) autoBrain = World.AIBrain(GameConfig().aiFor(AIDifficulty.NORMAL), Rng(9))
    }

    /** Advances the game by [dt] (GL thread). Multiplayer rooms never pause: other people keep playing. */
    fun tick(dt: Float) {
        when (kind) {
            Kind.SOLO -> soloWorld!!.step(dt)
            Kind.HOST -> host!!.tick(dt)
            Kind.CLIENT -> {
                val c = client!!
                autoBrain?.let { b ->
                    val w = c.world; val me = c.localChain
                    if (w != null && me != null && me.alive) { b.think(w, me, dt); c.input.direction = me.desiredDirection; c.input.boost = me.wantsBoost }
                }
                c.tick(dt)
            }
        }
    }

    /** Leaves / closes the room (call from any thread). */
    fun shutdownNetwork() {
        host?.let { h -> Thread { try { h.close() } catch (_: Exception) {} }.start() }
        client?.let { c -> Thread { try { c.leave() } catch (_: Exception) {} }.start() }
    }

    companion object {
        fun solo(mode: GameMode, difficulty: AIDifficulty, skin: String, trail: String, autoplay: Boolean = false, seed: Long = System.nanoTime()) =
            GameSession(Kind.SOLO, mode, difficulty, World(GameConfig(), GameModeSettings.forMode(mode), difficulty, seed, skin, trail), null, null, autoplay, null, null)

        /** Hosts a multiplayer room on [transport] (already opened). Defeated players respawn; bots fill the arena. */
        fun host(transport: HostTransport, roomCode: String, label: String, invite: String?, hostName: String, bots: Int,
                 skin: String, trail: String, autoplay: Boolean = false): GameSession {
            val mode = GameMode.CLASSIC
            val world = World(GameConfig(), GameModeSettings.forMode(mode), AIDifficulty.NORMAL, System.nanoTime(), skin, trail,
                WorldOptions(botCount = bots, respawnHumans = true, trackNetChanges = true, playerName = hostName))
            val online = transport is com.oxdh.cubeclash.engine.net.Online.HostTransportImpl
            val host = HostSession(world, transport, roomCode, hostName, stateRate = if (online) com.oxdh.cubeclash.engine.net.Online.STATE_RATE else 20f,
                fullResyncSeconds = if (online) 5f else 10f)
            host.start()
            return GameSession(Kind.HOST, mode, AIDifficulty.NORMAL, null, host, null, autoplay, label, invite)
        }

        fun join(client: ClientSession, label: String, autoplay: Boolean = false): GameSession {
            client.start()
            return GameSession(Kind.CLIENT, GameMode.CLASSIC, AIDifficulty.NORMAL, null, null, client, autoplay, label, null)
        }
    }
}
