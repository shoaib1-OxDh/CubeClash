using System;

namespace CubeSnake.Logic
{
    public enum GameMode { Classic = 0, EndlessGrowth = 1, SurvivalChallenge = 2 }

    /// <summary>Per-mode rule set. The main loop is identical for all modes; only these values change.</summary>
    [Serializable]
    public class GameModeSettings
    {
        public GameMode mode;
        public string displayName;
        public string description;
        public int rivalCount = 9;
        public bool playerCanBeDefeated = true;
        public bool rivalsCanDefeatPlayer = true;
        public bool rivalsAggressive = true;
        public float collectibleDensityMultiplier = 1f;
        public float boostRegenMultiplier = 1f;
        /// <summary>Seconds before a defeated rival respawns.</summary>
        public float rivalRespawnDelay = 4f;
        /// <summary>Survival: minutes for the difficulty ramp to reach 100%.</summary>
        public float difficultyRampMinutes = 0f;
        /// <summary>Survival: rivals respawn with head level = base + floor(minutes * this).</summary>
        public float rivalLevelPerMinute = 0f;
        public int rivalBaseMaxLevel = 3;

        public static GameModeSettings For(GameMode mode)
        {
            switch (mode)
            {
                case GameMode.EndlessGrowth:
                    return new GameModeSettings
                    {
                        mode = mode, displayName = "Endless Growth",
                        description = "Relaxed exploration. Rivals never hunt you and you cannot be defeated. Reach the biggest number you can!",
                        rivalCount = 7, playerCanBeDefeated = false, rivalsCanDefeatPlayer = false, rivalsAggressive = false,
                        collectibleDensityMultiplier = 1.35f, boostRegenMultiplier = 1.5f, rivalRespawnDelay = 3f,
                        rivalBaseMaxLevel = 3,
                    };
                case GameMode.SurvivalChallenge:
                    return new GameModeSettings
                    {
                        mode = mode, displayName = "Survival Challenge",
                        description = "Rivals grow stronger and more aggressive every minute and boost energy recovers slowly. How long can you last?",
                        rivalCount = 10, playerCanBeDefeated = true, rivalsCanDefeatPlayer = true, rivalsAggressive = true,
                        collectibleDensityMultiplier = 0.9f, boostRegenMultiplier = 0.5f, rivalRespawnDelay = 3f,
                        difficultyRampMinutes = 6f, rivalLevelPerMinute = 0.8f, rivalBaseMaxLevel = 3,
                    };
                default:
                    return new GameModeSettings
                    {
                        mode = GameMode.Classic, displayName = "Classic Arena",
                        description = "Collect cubes, merge numbers, outgrow your rivals and survive as long as possible.",
                        rivalCount = 9, rivalRespawnDelay = 4f, rivalBaseMaxLevel = 4,
                    };
            }
        }

        /// <summary>0..1 difficulty ramp for the elapsed session time.</summary>
        public float RampAt(double elapsedSeconds)
        {
            if (difficultyRampMinutes <= 0) return 0f;
            return MathUtil.Clamp01((float)(elapsedSeconds / 60.0 / difficultyRampMinutes));
        }

        /// <summary>Maximum head level a rival may respawn with at the given time.</summary>
        public int RivalMaxLevelAt(double elapsedSeconds) =>
            rivalBaseMaxLevel + (int)Math.Floor(elapsedSeconds / 60.0 * rivalLevelPerMinute);
    }
}
