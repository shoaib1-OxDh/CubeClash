using CubeSnake.Logic;

namespace CubeSnake.Core
{
    /// <summary>Parameters chosen in the main menu and handed to the arena scene.</summary>
    public static class GameSession
    {
        public static GameMode Mode = GameMode.Classic;
        public static AIDifficulty Difficulty = AIDifficulty.Normal;
        /// <summary>Optional fixed seed (0 = random) for reproducible test sessions.</summary>
        public static ulong Seed;
    }
}
