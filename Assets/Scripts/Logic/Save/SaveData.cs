using System;
using System.Collections.Generic;

namespace CubeSnake.Logic
{
    /// <summary>Persistent player progress (stored as JSON by SaveManager). Field names are stable on purpose.</summary>
    [Serializable]
    public class PlayerProgress
    {
        public const int CurrentVersion = 1;

        public int version = CurrentVersion;
        public long bestScore;
        public int highestLevel;
        public int bestChainLength;
        public long totalCubesCollected;
        public long totalMerges;
        public long opponentsDefeated;
        public double totalPlayTimeSeconds;
        public double longestSessionSeconds;
        public int gamesPlayed;
        public long coins;
        public List<string> unlockedSkins = new List<string>();
        public string selectedSkin = UnlockCatalog.DefaultSkin;
        public List<string> unlockedTrails = new List<string>();
        public string selectedTrail = UnlockCatalog.DefaultTrail;
        public List<ModeBest> modeBests = new List<ModeBest>();

        public ModeBest GetModeBest(GameMode mode)
        {
            foreach (var m in modeBests) if (m.mode == (int)mode) return m;
            var created = new ModeBest { mode = (int)mode };
            modeBests.Add(created);
            return created;
        }
    }

    [Serializable]
    public class ModeBest
    {
        public int mode;
        public long bestScore;
        public int highestLevel;
        public double longestSurvivalSeconds;
    }

    /// <summary>Everything recorded during one game session.</summary>
    public sealed class SessionStats
    {
        public long Score { get; private set; }
        public int HighestLevel { get; private set; }
        public int MaxChainLength { get; private set; }
        public int CubesCollected { get; private set; }
        public int Merges { get; private set; }
        public int OpponentsDefeated { get; private set; }
        public double DurationSeconds { get; private set; }

        public void Reset(int startLevel)
        {
            Score = 0; HighestLevel = startLevel; MaxChainLength = 1; CubesCollected = 0; Merges = 0;
            OpponentsDefeated = 0; DurationSeconds = 0;
        }

        public void AddScore(long points) => Score = SaturatingMath.Add(Score, Math.Max(0, points));
        public void AddTime(double seconds) { if (seconds > 0) DurationSeconds += seconds; }
        public void RecordCollect() => CubesCollected++;
        public void RecordMerge() => Merges++;
        public void RecordDefeat() => OpponentsDefeated++;
        public void ObserveChain(int headLevel, int length)
        {
            if (headLevel > HighestLevel) HighestLevel = headLevel;
            if (length > MaxChainLength) MaxChainLength = length;
        }

        /// <summary>Applies the chain events produced by one MergeSystem call to these stats.</summary>
        public void Apply(List<ChainEvent> events)
        {
            if (events == null) return;
            foreach (var e in events)
            {
                AddScore(e.Score);
                if (e.Type == ChainEventType.Merged || e.Type == ChainEventType.Upgraded) RecordMerge();
            }
        }
    }

    public struct PersonalBestResult
    {
        public bool NewBestScore;
        public bool NewHighestLevel;
        public long PreviousBestScore;
        public int PreviousHighestLevel;
        public long CoinsEarned;
        public List<string> NewUnlocks;
    }

    public static class ProgressRules
    {
        /// <summary>Coins awarded for a session (score-based, no purchases involved).</summary>
        public static long CoinsFor(SessionStats s) =>
            Math.Max(0, Math.Min(1_000_000, s.Score / 100 + s.OpponentsDefeated * 5 + s.HighestLevel * 2));

        /// <summary>Folds a finished session into persistent progress and reports personal bests.</summary>
        public static PersonalBestResult ApplySession(PlayerProgress p, SessionStats s, GameMode mode)
        {
            SaveValidator.Sanitize(p);
            var result = new PersonalBestResult
            {
                PreviousBestScore = p.bestScore,
                PreviousHighestLevel = p.highestLevel,
                NewUnlocks = new List<string>(),
            };
            result.NewBestScore = s.Score > p.bestScore;
            result.NewHighestLevel = s.HighestLevel > p.highestLevel;
            if (result.NewBestScore) p.bestScore = s.Score;
            if (result.NewHighestLevel) p.highestLevel = s.HighestLevel;
            p.bestChainLength = Math.Max(p.bestChainLength, s.MaxChainLength);
            p.totalCubesCollected += s.CubesCollected;
            p.totalMerges += s.Merges;
            p.opponentsDefeated += s.OpponentsDefeated;
            p.totalPlayTimeSeconds += s.DurationSeconds;
            p.longestSessionSeconds = Math.Max(p.longestSessionSeconds, s.DurationSeconds);
            p.gamesPlayed++;
            result.CoinsEarned = CoinsFor(s);
            p.coins = SaturatingMath.Add(p.coins, result.CoinsEarned);

            var mb = p.GetModeBest(mode);
            mb.bestScore = Math.Max(mb.bestScore, s.Score);
            mb.highestLevel = Math.Max(mb.highestLevel, s.HighestLevel);
            mb.longestSurvivalSeconds = Math.Max(mb.longestSurvivalSeconds, s.DurationSeconds);

            result.NewUnlocks.AddRange(UnlockCatalog.ApplyUnlocks(p));
            return result;
        }
    }

    /// <summary>Repairs missing, invalid or tampered fields so a bad save can never crash or soft-lock the game.</summary>
    public static class SaveValidator
    {
        public static PlayerProgress Sanitize(PlayerProgress p)
        {
            if (p == null) p = new PlayerProgress();
            if (p.version <= 0 || p.version > PlayerProgress.CurrentVersion) p.version = PlayerProgress.CurrentVersion;
            if (p.bestScore < 0) p.bestScore = 0;
            p.highestLevel = MathUtil.Clamp(p.highestLevel, 0, NumberValue.MaxLevel);
            if (p.bestChainLength < 0) p.bestChainLength = 0;
            if (p.totalCubesCollected < 0) p.totalCubesCollected = 0;
            if (p.totalMerges < 0) p.totalMerges = 0;
            if (p.opponentsDefeated < 0) p.opponentsDefeated = 0;
            if (double.IsNaN(p.totalPlayTimeSeconds) || double.IsInfinity(p.totalPlayTimeSeconds) || p.totalPlayTimeSeconds < 0) p.totalPlayTimeSeconds = 0;
            if (double.IsNaN(p.longestSessionSeconds) || double.IsInfinity(p.longestSessionSeconds) || p.longestSessionSeconds < 0) p.longestSessionSeconds = 0;
            if (p.gamesPlayed < 0) p.gamesPlayed = 0;
            if (p.coins < 0) p.coins = 0;
            p.unlockedSkins = Clean(p.unlockedSkins, UnlockCatalog.IsSkin);
            p.unlockedTrails = Clean(p.unlockedTrails, UnlockCatalog.IsTrail);
            if (!p.unlockedSkins.Contains(UnlockCatalog.DefaultSkin)) p.unlockedSkins.Insert(0, UnlockCatalog.DefaultSkin);
            if (!p.unlockedTrails.Contains(UnlockCatalog.DefaultTrail)) p.unlockedTrails.Insert(0, UnlockCatalog.DefaultTrail);
            if (string.IsNullOrEmpty(p.selectedSkin) || !p.unlockedSkins.Contains(p.selectedSkin)) p.selectedSkin = UnlockCatalog.DefaultSkin;
            if (string.IsNullOrEmpty(p.selectedTrail) || !p.unlockedTrails.Contains(p.selectedTrail)) p.selectedTrail = UnlockCatalog.DefaultTrail;
            if (p.modeBests == null) p.modeBests = new List<ModeBest>();
            p.modeBests.RemoveAll(m => m == null || m.mode < 0 || m.mode > 2);
            foreach (var m in p.modeBests)
            {
                if (m.bestScore < 0) m.bestScore = 0;
                m.highestLevel = MathUtil.Clamp(m.highestLevel, 0, NumberValue.MaxLevel);
                if (double.IsNaN(m.longestSurvivalSeconds) || m.longestSurvivalSeconds < 0) m.longestSurvivalSeconds = 0;
            }
            return p;
        }

        static List<string> Clean(List<string> list, Func<string, bool> valid)
        {
            var result = new List<string>();
            if (list == null) return result;
            foreach (var s in list)
                if (!string.IsNullOrEmpty(s) && valid(s) && !result.Contains(s)) result.Add(s);
            return result;
        }
    }

    public enum UnlockKind { Skin, Trail }

    public sealed class UnlockItem
    {
        public string Id;
        public string DisplayName;
        public UnlockKind Kind;
        public long RequiredBestScore;
        public int RequiredHighestLevel;
        public long RequiredOpponentsDefeated;
        public string RequirementText;
    }

    /// <summary>Cosmetic unlocks earned purely through play (no purchases).</summary>
    public static class UnlockCatalog
    {
        public const string DefaultSkin = "classic";
        public const string DefaultTrail = "spark";

        public static readonly UnlockItem[] Items =
        {
            new UnlockItem { Id = "classic", DisplayName = "Classic", Kind = UnlockKind.Skin, RequirementText = "Unlocked" },
            new UnlockItem { Id = "pastel", DisplayName = "Pastel", Kind = UnlockKind.Skin, RequiredBestScore = 1500, RequirementText = "Score 1,500 in one game" },
            new UnlockItem { Id = "neon", DisplayName = "Neon", Kind = UnlockKind.Skin, RequiredHighestLevel = 8, RequirementText = "Reach 256" },
            new UnlockItem { Id = "sunset", DisplayName = "Sunset", Kind = UnlockKind.Skin, RequiredOpponentsDefeated = 25, RequirementText = "Defeat 25 rivals (total)" },
            new UnlockItem { Id = "mono", DisplayName = "Monochrome", Kind = UnlockKind.Skin, RequiredHighestLevel = 11, RequirementText = "Reach 2048" },
            new UnlockItem { Id = "spark", DisplayName = "Spark", Kind = UnlockKind.Trail, RequirementText = "Unlocked" },
            new UnlockItem { Id = "flame", DisplayName = "Flame", Kind = UnlockKind.Trail, RequiredBestScore = 4000, RequirementText = "Score 4,000 in one game" },
            new UnlockItem { Id = "rainbow", DisplayName = "Rainbow", Kind = UnlockKind.Trail, RequiredHighestLevel = 10, RequirementText = "Reach 1024" },
            new UnlockItem { Id = "ice", DisplayName = "Ice", Kind = UnlockKind.Trail, RequiredOpponentsDefeated = 10, RequirementText = "Defeat 10 rivals (total)" },
        };

        public static bool IsSkin(string id) { foreach (var i in Items) if (i.Kind == UnlockKind.Skin && i.Id == id) return true; return false; }
        public static bool IsTrail(string id) { foreach (var i in Items) if (i.Kind == UnlockKind.Trail && i.Id == id) return true; return false; }

        public static UnlockItem Get(string id)
        {
            foreach (var i in Items) if (i.Id == id) return i;
            return null;
        }

        public static bool MeetsRequirement(UnlockItem item, PlayerProgress p) =>
            p.bestScore >= item.RequiredBestScore && p.highestLevel >= item.RequiredHighestLevel &&
            p.opponentsDefeated >= item.RequiredOpponentsDefeated;

        public static bool IsUnlocked(UnlockItem item, PlayerProgress p) =>
            item.Kind == UnlockKind.Skin ? p.unlockedSkins.Contains(item.Id) : p.unlockedTrails.Contains(item.Id);

        /// <summary>Unlocks every item whose requirement is met; returns newly unlocked ids.</summary>
        public static List<string> ApplyUnlocks(PlayerProgress p)
        {
            var newly = new List<string>();
            foreach (var item in Items)
            {
                if (IsUnlocked(item, p) || !MeetsRequirement(item, p)) continue;
                if (item.Kind == UnlockKind.Skin) p.unlockedSkins.Add(item.Id);
                else p.unlockedTrails.Add(item.Id);
                newly.Add(item.Id);
            }
            return newly;
        }
    }
}
