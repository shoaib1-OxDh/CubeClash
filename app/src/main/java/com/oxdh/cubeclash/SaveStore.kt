package com.oxdh.cubeclash

import android.content.Context
import android.util.Log
import com.oxdh.cubeclash.engine.ModeBest
import com.oxdh.cubeclash.engine.PlayerProgress
import com.oxdh.cubeclash.engine.SaveValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Offline persistence of [PlayerProgress] as JSON in the app's private files. Writes go to a temp file and the
 * previous save is kept as a backup; a missing or corrupt file falls back to the backup, then to defaults.
 * Everything is sanitised before use.
 */
class SaveStore(context: Context) {
    private val dir = context.filesDir
    private val main = File(dir, "progress.json")
    private val backup = File(dir, "progress.json.bak")
    private val temp = File(dir, "progress.json.tmp")

    var progress: PlayerProgress = load()
        private set

    private fun load(): PlayerProgress = SaveValidator.sanitize(read(main) ?: read(backup) ?: PlayerProgress())

    private fun read(f: File): PlayerProgress? = try {
        if (!f.exists()) null else fromJson(JSONObject(f.readText()))
    } catch (e: Exception) {
        Log.w("CubeClash", "Could not read ${f.name}: ${e.message}")
        null
    }

    @Synchronized
    fun save() {
        try {
            SaveValidator.sanitize(progress)
            temp.writeText(toJson(progress).toString(2))
            if (main.exists()) main.copyTo(backup, overwrite = true)
            temp.copyTo(main, overwrite = true)
            temp.delete()
        } catch (e: Exception) {
            Log.w("CubeClash", "Could not write save: ${e.message}")
        }
    }

    fun reset() {
        progress = SaveValidator.sanitize(PlayerProgress())
        save()
    }

    companion object {
        fun toJson(p: PlayerProgress) = JSONObject().apply {
            put("version", p.version); put("bestScore", p.bestScore); put("highestLevel", p.highestLevel)
            put("bestChainLength", p.bestChainLength); put("totalCubesCollected", p.totalCubesCollected)
            put("totalMerges", p.totalMerges); put("opponentsDefeated", p.opponentsDefeated)
            put("totalPlayTimeSeconds", p.totalPlayTimeSeconds); put("longestSessionSeconds", p.longestSessionSeconds)
            put("gamesPlayed", p.gamesPlayed); put("coins", p.coins)
            put("unlockedSkins", JSONArray(p.unlockedSkins)); put("selectedSkin", p.selectedSkin)
            put("unlockedTrails", JSONArray(p.unlockedTrails)); put("selectedTrail", p.selectedTrail)
            put("modeBests", JSONArray().apply {
                p.modeBests.forEach { m -> put(JSONObject().put("mode", m.mode).put("bestScore", m.bestScore).put("highestLevel", m.highestLevel).put("longestSurvivalSeconds", m.longestSurvivalSeconds)) }
            })
        }

        fun fromJson(o: JSONObject): PlayerProgress {
            fun strings(a: JSONArray?) = mutableListOf<String>().also { l -> if (a != null) for (i in 0 until a.length()) l.add(a.optString(i)) }
            val bests = mutableListOf<ModeBest>()
            o.optJSONArray("modeBests")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { m ->
                    bests.add(ModeBest(m.optInt("mode", -1), m.optLong("bestScore"), m.optInt("highestLevel"), m.optDouble("longestSurvivalSeconds", 0.0)))
                }
            }
            return PlayerProgress(
                version = o.optInt("version", PlayerProgress.CURRENT_VERSION),
                bestScore = o.optLong("bestScore"), highestLevel = o.optInt("highestLevel"),
                bestChainLength = o.optInt("bestChainLength"), totalCubesCollected = o.optLong("totalCubesCollected"),
                totalMerges = o.optLong("totalMerges"), opponentsDefeated = o.optLong("opponentsDefeated"),
                totalPlayTimeSeconds = o.optDouble("totalPlayTimeSeconds", 0.0), longestSessionSeconds = o.optDouble("longestSessionSeconds", 0.0),
                gamesPlayed = o.optInt("gamesPlayed"), coins = o.optLong("coins"),
                unlockedSkins = strings(o.optJSONArray("unlockedSkins")), selectedSkin = o.optString("selectedSkin", "classic"),
                unlockedTrails = strings(o.optJSONArray("unlockedTrails")), selectedTrail = o.optString("selectedTrail", "spark"),
                modeBests = bests,
            )
        }
    }
}
