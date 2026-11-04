using System;
using System.Globalization;

namespace CubeSnake.Logic
{
    /// <summary>
    /// A cube's number. Every value in the game is a power of two, so the value is stored as its
    /// exponent ("level"): level 0 = 1, level 1 = 2, level 2 = 4 ... This makes merging exact and
    /// overflow-free for any reachable value (level is an int, so values up to 2^int.MaxValue are representable).
    /// </summary>
    [Serializable]
    public struct NumberValue : IEquatable<NumberValue>, IComparable<NumberValue>
    {
        /// <summary>Highest level whose value still fits exactly in a signed 64-bit integer.</summary>
        public const int MaxExactLevel = 62;
        /// <summary>Hard upper bound to keep display and score maths sane.</summary>
        public const int MaxLevel = 1000;

        public int Level;

        public NumberValue(int level)
        {
            if (level < 0) throw new ArgumentOutOfRangeException(nameof(level), "Level must be >= 0");
            Level = level > MaxLevel ? MaxLevel : level;
        }

        public static NumberValue One => new NumberValue(0);

        /// <summary>Value as a double (exact for every level up to 1023 because it is a power of two).</summary>
        public double Value => ValueOf(Level);

        /// <summary>Exact value when it fits in a long; otherwise long.MaxValue (saturated).</summary>
        public long ExactValueOrSaturated => Level <= MaxExactLevel ? 1L << Level : long.MaxValue;

        public NumberValue Doubled => new NumberValue(Level + 1);

        public static double ValueOf(int level) => Math.Pow(2.0, level);

        /// <summary>Converts a positive power-of-two integer to its level. Returns false for anything else.</summary>
        public static bool TryFromValue(long value, out NumberValue number)
        {
            number = default;
            if (value <= 0 || (value & (value - 1)) != 0) return false;
            int level = 0;
            while ((value >>= 1) != 0) level++;
            number = new NumberValue(level);
            return true;
        }

        public string ToDisplayString() => NumberFormat.FormatLevel(Level);

        public bool Equals(NumberValue other) => Level == other.Level;
        public override bool Equals(object obj) => obj is NumberValue n && Equals(n);
        public override int GetHashCode() => Level;
        public int CompareTo(NumberValue other) => Level.CompareTo(other.Level);
        public override string ToString() => ToDisplayString();

        public static bool operator ==(NumberValue a, NumberValue b) => a.Level == b.Level;
        public static bool operator !=(NumberValue a, NumberValue b) => a.Level != b.Level;
        public static bool operator <(NumberValue a, NumberValue b) => a.Level < b.Level;
        public static bool operator >(NumberValue a, NumberValue b) => a.Level > b.Level;
        public static bool operator <=(NumberValue a, NumberValue b) => a.Level <= b.Level;
        public static bool operator >=(NumberValue a, NumberValue b) => a.Level >= b.Level;
    }

    /// <summary>Formatting helpers for cube labels (short) and HUD/score text (longer).</summary>
    public static class NumberFormat
    {
        static readonly string[] Suffixes = { "", "K", "M", "B", "T" };

        /// <summary>
        /// Short label suitable for a cube face (at most 5 characters).
        /// 1..8192 are shown exactly; larger values use K/M/B/T; beyond that "2^N".
        /// </summary>
        public static string FormatLevel(int level)
        {
            if (level < 0) level = 0;
            if (level <= 13) return (1L << level).ToString(CultureInfo.InvariantCulture);
            if (level >= 50) return "2^" + level.ToString(CultureInfo.InvariantCulture);
            return Abbreviate(Math.Pow(2.0, level));
        }

        /// <summary>Abbreviates a non-negative number: 999 -> "999", 16384 -> "16K", 2097152 -> "2.1M".</summary>
        public static string Abbreviate(double value)
        {
            if (double.IsNaN(value) || value < 0) value = 0;
            if (value < 10000) return Math.Floor(value).ToString("0", CultureInfo.InvariantCulture);
            int tier = 0;
            while (value >= 1000 && tier < Suffixes.Length - 1)
            {
                value /= 1000.0;
                tier++;
            }
            if (value >= 1000) return value.ToString("0.0e0", CultureInfo.InvariantCulture);
            string number = value < 10
                ? (Math.Floor(value * 10) / 10).ToString("0.#", CultureInfo.InvariantCulture)
                : Math.Floor(value).ToString("0", CultureInfo.InvariantCulture);
            return number + Suffixes[tier];
        }

        /// <summary>Score text for HUD: exact with thousands separators up to 10 million, abbreviated after.</summary>
        public static string FormatScore(long score)
        {
            if (score < 0) score = 0;
            if (score < 10_000_000) return score.ToString("#,0", CultureInfo.InvariantCulture);
            return Abbreviate(score);
        }

        public static string FormatDuration(double seconds)
        {
            if (seconds < 0 || double.IsNaN(seconds)) seconds = 0;
            long total = (long)seconds;
            long h = total / 3600, m = (total / 60) % 60, s = total % 60;
            return h > 0
                ? string.Format(CultureInfo.InvariantCulture, "{0}:{1:00}:{2:00}", h, m, s)
                : string.Format(CultureInfo.InvariantCulture, "{0}:{1:00}", m, s);
        }
    }
}
