using System;
using System.Collections.Generic;

namespace CubeSnake.Logic
{
    /// <summary>Score rules. Every score is derived from the actual action performed (see docs/GAME_RULES.md).</summary>
    public static class ScoreCalculator
    {
        public static long ForCollect(int level, RulesConfig rules) => ToScore(NumberValue.ValueOf(level) * rules.scorePerCollectedValue);

        public static long ForMerge(int resultLevel, RulesConfig rules) => ToScore(NumberValue.ValueOf(resultLevel) * rules.scorePerMergedValue);

        public static long ForShed(int level, RulesConfig rules) => ToScore(NumberValue.ValueOf(level) * rules.scoreShedValueFraction);

        public static long ForDefeat(double victimTotalValue, RulesConfig rules) =>
            SaturatingMath.Add(ToScore(victimTotalValue * rules.scoreDefeatValueFraction), rules.scoreDefeatFlatBonus);

        public static long SumEvents(List<ChainEvent> events)
        {
            long total = 0;
            if (events == null) return 0;
            for (int i = 0; i < events.Count; i++) total = SaturatingMath.Add(total, events[i].Score);
            return total;
        }

        /// <summary>Converts a (possibly huge) double into a non-negative saturated long.</summary>
        public static long ToScore(double value)
        {
            if (double.IsNaN(value) || value <= 0) return 0;
            if (value >= long.MaxValue) return long.MaxValue;
            return (long)Math.Round(value);
        }
    }

    public static class SaturatingMath
    {
        public static long Add(long a, long b)
        {
            long r = unchecked(a + b);
            if (((a ^ r) & (b ^ r)) < 0) return a < 0 ? long.MinValue : long.MaxValue;
            return r;
        }
    }

    /// <summary>Ranks competitors by score (descending). Ties keep the lower rank for whoever reached it first (lower index).</summary>
    public static class RankCalculator
    {
        /// <summary>1-based rank of <paramref name="index"/> in <paramref name="scores"/>.</summary>
        public static int RankOf(IReadOnlyList<long> scores, int index)
        {
            if (scores == null || index < 0 || index >= scores.Count) return 0;
            long mine = scores[index];
            int rank = 1;
            for (int i = 0; i < scores.Count; i++)
            {
                if (i == index) continue;
                if (scores[i] > mine || (scores[i] == mine && i < index)) rank++;
            }
            return rank;
        }
    }
}
