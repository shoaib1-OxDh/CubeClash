package com.oxdh.cubeclash.engine

import kotlin.math.max
import kotlin.math.min

/** Everything recorded during one game session for one chain. */
class SessionStats {
    var score = 0L; private set
    var highestLevel = 0; private set
    var maxChainLength = 1; private set
    var cubesCollected = 0; private set
    var merges = 0; private set
    var opponentsDefeated = 0; private set
    var durationSeconds = 0.0; private set

    fun reset(startLevel: Int) {
        score = 0; highestLevel = startLevel; maxChainLength = 1; cubesCollected = 0; merges = 0; opponentsDefeated = 0; durationSeconds = 0.0
    }
    fun addScore(points: Long) { score = SaturatingMath.add(score, max(0, points)) }
    fun addTime(seconds: Double) { if (seconds > 0) durationSeconds += seconds }
    fun recordCollect() { cubesCollected++ }
    fun recordMerge() { merges++ }
    fun recordDefeat() { opponentsDefeated++ }
    fun observeChain(headLevel: Int, length: Int) {
        if (headLevel > highestLevel) highestLevel = headLevel
        if (length > maxChainLength) maxChainLength = length
    }
    /** Applies the score and merge counts of [events]. */
    fun apply(events: List<ChainEvent>) {
        for (e in events) {
            addScore(e.score)
            if (e.type == ChainEventType.MERGED || e.type == ChainEventType.UPGRADED) recordMerge()
        }
    }
}

data class ModeBest(var mode: Int = 0, var bestScore: Long = 0, var highestLevel: Int = 0, var longestSurvivalSeconds: Double = 0.0)

/** Persistent progress (stored as JSON by the app). */
data class PlayerProgress(
    var version: Int = CURRENT_VERSION,
    var bestScore: Long = 0,
    var highestLevel: Int = 0,
    var bestChainLength: Int = 0,
    var totalCubesCollected: Long = 0,
    var totalMerges: Long = 0,
    var opponentsDefeated: Long = 0,
    var totalPlayTimeSeconds: Double = 0.0,
    var longestSessionSeconds: Double = 0.0,
    var gamesPlayed: Int = 0,
    var coins: Long = 0,
    var unlockedSkins: MutableList<String> = mutableListOf(),
    var selectedSkin: String = UnlockCatalog.DEFAULT_SKIN,
    var unlockedTrails: MutableList<String> = mutableListOf(),
    var selectedTrail: String = UnlockCatalog.DEFAULT_TRAIL,
    var modeBests: MutableList<ModeBest> = mutableListOf(),
) {
    fun modeBest(mode: GameMode): ModeBest =
        modeBests.firstOrNull { it.mode == mode.ordinal } ?: ModeBest(mode.ordinal).also { modeBests.add(it) }

    companion object { const val CURRENT_VERSION = 1 }
}

data class PersonalBestResult(
    val newBestScore: Boolean = false,
    val newHighestLevel: Boolean = false,
    val previousBestScore: Long = 0,
    val previousHighestLevel: Int = 0,
    val coinsEarned: Long = 0,
    val newUnlocks: List<String> = emptyList(),
)

object ProgressRules {
    fun coinsFor(s: SessionStats): Long = max(0, min(1_000_000, s.score / 100 + s.opponentsDefeated * 5 + s.highestLevel * 2))

    /** Folds a finished session into [p]; personal bests never decrease. */
    fun applySession(p: PlayerProgress, s: SessionStats, mode: GameMode): PersonalBestResult {
        SaveValidator.sanitize(p)
        val prevBest = p.bestScore; val prevHigh = p.highestLevel
        val newBest = s.score > p.bestScore
        val newHigh = s.highestLevel > p.highestLevel
        if (newBest) p.bestScore = s.score
        if (newHigh) p.highestLevel = s.highestLevel
        p.bestChainLength = max(p.bestChainLength, s.maxChainLength)
        p.totalCubesCollected += s.cubesCollected
        p.totalMerges += s.merges
        p.opponentsDefeated += s.opponentsDefeated
        p.totalPlayTimeSeconds += s.durationSeconds
        p.longestSessionSeconds = max(p.longestSessionSeconds, s.durationSeconds)
        p.gamesPlayed++
        val coins = coinsFor(s)
        p.coins = SaturatingMath.add(p.coins, coins)
        val mb = p.modeBest(mode)
        mb.bestScore = max(mb.bestScore, s.score)
        mb.highestLevel = max(mb.highestLevel, s.highestLevel)
        mb.longestSurvivalSeconds = max(mb.longestSurvivalSeconds, s.durationSeconds)
        return PersonalBestResult(newBest, newHigh, prevBest, prevHigh, coins, UnlockCatalog.applyUnlocks(p))
    }
}

/** Repairs missing, invalid or tampered fields so a bad save can never crash or soft-lock the game. */
object SaveValidator {
    private fun bad(d: Double) = d.isNaN() || d.isInfinite() || d < 0

    fun sanitize(p: PlayerProgress): PlayerProgress {
        if (p.version <= 0 || p.version > PlayerProgress.CURRENT_VERSION) p.version = PlayerProgress.CURRENT_VERSION
        if (p.bestScore < 0) p.bestScore = 0
        p.highestLevel = MathUtil.clamp(p.highestLevel, 0, NumberValue.MAX_LEVEL)
        if (p.bestChainLength < 0) p.bestChainLength = 0
        if (p.totalCubesCollected < 0) p.totalCubesCollected = 0
        if (p.totalMerges < 0) p.totalMerges = 0
        if (p.opponentsDefeated < 0) p.opponentsDefeated = 0
        if (bad(p.totalPlayTimeSeconds)) p.totalPlayTimeSeconds = 0.0
        if (bad(p.longestSessionSeconds)) p.longestSessionSeconds = 0.0
        if (p.gamesPlayed < 0) p.gamesPlayed = 0
        if (p.coins < 0) p.coins = 0
        p.unlockedSkins = clean(p.unlockedSkins, UnlockCatalog::isSkin)
        p.unlockedTrails = clean(p.unlockedTrails, UnlockCatalog::isTrail)
        if (UnlockCatalog.DEFAULT_SKIN !in p.unlockedSkins) p.unlockedSkins.add(0, UnlockCatalog.DEFAULT_SKIN)
        if (UnlockCatalog.DEFAULT_TRAIL !in p.unlockedTrails) p.unlockedTrails.add(0, UnlockCatalog.DEFAULT_TRAIL)
        if (p.selectedSkin !in p.unlockedSkins) p.selectedSkin = UnlockCatalog.DEFAULT_SKIN
        if (p.selectedTrail !in p.unlockedTrails) p.selectedTrail = UnlockCatalog.DEFAULT_TRAIL
        p.modeBests = p.modeBests.filter { it.mode in 0..2 }.distinctBy { it.mode }.toMutableList()
        for (m in p.modeBests) {
            if (m.bestScore < 0) m.bestScore = 0
            m.highestLevel = MathUtil.clamp(m.highestLevel, 0, NumberValue.MAX_LEVEL)
            if (bad(m.longestSurvivalSeconds)) m.longestSurvivalSeconds = 0.0
        }
        return p
    }

    private fun clean(list: List<String?>?, valid: (String) -> Boolean): MutableList<String> {
        val out = mutableListOf<String>()
        list?.forEach { s -> if (!s.isNullOrEmpty() && valid(s) && s !in out) out.add(s) }
        return out
    }
}

enum class UnlockKind { SKIN, TRAIL }

data class UnlockItem(
    val id: String,
    val displayName: String,
    val kind: UnlockKind,
    val requirementText: String,
    val requiredBestScore: Long = 0,
    val requiredHighestLevel: Int = 0,
    val requiredOpponentsDefeated: Long = 0,
)

/** Cosmetics earned purely through play (no purchases). */
object UnlockCatalog {
    const val DEFAULT_SKIN = "classic"
    const val DEFAULT_TRAIL = "spark"

    val items = listOf(
        UnlockItem("classic", "Classic", UnlockKind.SKIN, "Unlocked"),
        UnlockItem("pastel", "Pastel", UnlockKind.SKIN, "Score 1,500 in one game", requiredBestScore = 1500),
        UnlockItem("neon", "Neon", UnlockKind.SKIN, "Reach 256", requiredHighestLevel = 8),
        UnlockItem("sunset", "Sunset", UnlockKind.SKIN, "Defeat 25 rivals (total)", requiredOpponentsDefeated = 25),
        UnlockItem("mono", "Monochrome", UnlockKind.SKIN, "Reach 2048", requiredHighestLevel = 11),
        UnlockItem("spark", "Spark", UnlockKind.TRAIL, "Unlocked"),
        UnlockItem("flame", "Flame", UnlockKind.TRAIL, "Score 4,000 in one game", requiredBestScore = 4000),
        UnlockItem("rainbow", "Rainbow", UnlockKind.TRAIL, "Reach 1024", requiredHighestLevel = 10),
        UnlockItem("ice", "Ice", UnlockKind.TRAIL, "Defeat 10 rivals (total)", requiredOpponentsDefeated = 10),
    )

    fun isSkin(id: String) = items.any { it.kind == UnlockKind.SKIN && it.id == id }
    fun isTrail(id: String) = items.any { it.kind == UnlockKind.TRAIL && it.id == id }
    fun get(id: String) = items.firstOrNull { it.id == id }

    fun meetsRequirement(item: UnlockItem, p: PlayerProgress) =
        p.bestScore >= item.requiredBestScore && p.highestLevel >= item.requiredHighestLevel && p.opponentsDefeated >= item.requiredOpponentsDefeated

    fun isUnlocked(item: UnlockItem, p: PlayerProgress) =
        if (item.kind == UnlockKind.SKIN) item.id in p.unlockedSkins else item.id in p.unlockedTrails

    /** Unlocks every item whose requirement is met; returns the newly unlocked ids. */
    fun applyUnlocks(p: PlayerProgress): List<String> {
        val newly = mutableListOf<String>()
        for (item in items) {
            if (isUnlocked(item, p) || !meetsRequirement(item, p)) continue
            if (item.kind == UnlockKind.SKIN) p.unlockedSkins.add(item.id) else p.unlockedTrails.add(item.id)
            newly.add(item.id)
        }
        return newly
    }
}
