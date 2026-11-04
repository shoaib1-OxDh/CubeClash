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

/**
 * One arena run. The [world] is only touched on the GL thread (stepped by the renderer); the UI thread talks to
 * it through the volatile [input] brain and the [paused] / [ended] flags.
 */
class GameSession(
    val mode: GameMode,
    val difficulty: AIDifficulty,
    playerSkin: String,
    playerTrail: String,
    /** Debug/CI: the player chain is driven by the rival AI. */
    val autoplay: Boolean = false,
    seed: Long = System.nanoTime(),
) {
    val modeSettings = GameModeSettings.forMode(mode)
    val world = World(GameConfig(), modeSettings, difficulty, seed, playerSkin, playerTrail)
    val input = InputBrain()
    val hud = com.oxdh.cubeclash.ui.HudState()

    @Volatile var paused = false
    /** UI asks to end the run voluntarily ("End run" in the pause menu); handled on the GL thread. */
    @Volatile var endRequested = false
    /** Set once the run is over (defeat or "End run"); the summary is shown once. */
    @Volatile var ended = false
    @Volatile var summary: Summary? = null

    class Summary(val title: String, val stats: SessionStats, val result: PersonalBestResult, val skin: String)

    init {
        world.player.brain = if (autoplay) World.AIBrain(GameConfig().aiFor(AIDifficulty.HARD), Rng(seed xor 0x5555)) else input
    }
}
