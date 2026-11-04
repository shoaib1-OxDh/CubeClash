using System;
using System.Collections.Generic;

namespace CubeSnake.Logic
{
    /// <summary>Small, fast, deterministic xorshift RNG (seedable for tests and replays).</summary>
    public sealed class Rng
    {
        ulong _state;

        public Rng(ulong seed)
        {
            _state = seed == 0 ? 0x9E3779B97F4A7C15UL : seed;
        }

        public Rng() : this((ulong)DateTime.UtcNow.Ticks) { }

        public ulong NextULong()
        {
            // xorshift64*
            _state ^= _state >> 12;
            _state ^= _state << 25;
            _state ^= _state >> 27;
            return _state * 0x2545F4914F6CDD1DUL;
        }

        /// <summary>Uniform float in [0, 1).</summary>
        public float NextFloat() => (NextULong() >> 40) * (1f / (1 << 24));

        /// <summary>Uniform float in [min, max).</summary>
        public float Range(float min, float max) => min + (max - min) * NextFloat();

        /// <summary>Uniform int in [min, maxExclusive).</summary>
        public int Range(int min, int maxExclusive)
        {
            if (maxExclusive <= min) return min;
            return min + (int)(NextULong() % (ulong)(maxExclusive - min));
        }

        public bool Chance(float probability) => NextFloat() < probability;

        /// <summary>Uniformly distributed point inside a disc.</summary>
        public Vec2 InsideDisc(float radius)
        {
            float r = radius * (float)Math.Sqrt(NextFloat());
            float a = NextFloat() * MathUtil.TwoPi;
            return Vec2.FromAngle(a) * r;
        }

        public T Pick<T>(IReadOnlyList<T> list) => list[Range(0, list.Count)];
    }

    [Serializable]
    public struct WeightedLevel
    {
        public int level;
        public float weight;
        public WeightedLevel(int level, float weight) { this.level = level; this.weight = weight; }
    }

    /// <summary>
    /// Normalised weighted distribution over cube levels. Negative / NaN weights are treated as 0; if every
    /// weight is 0 the table falls back to always returning the lowest level.
    /// </summary>
    public sealed class WeightedTable
    {
        readonly int[] _levels;
        readonly float[] _cumulative;
        readonly float _total;

        public WeightedTable(IList<WeightedLevel> entries)
        {
            if (entries == null || entries.Count == 0)
            {
                _levels = new[] { 0 };
                _cumulative = new[] { 1f };
                _total = 1f;
                return;
            }
            _levels = new int[entries.Count];
            _cumulative = new float[entries.Count];
            float sum = 0;
            for (int i = 0; i < entries.Count; i++)
            {
                float w = entries[i].weight;
                if (float.IsNaN(w) || w < 0) w = 0;
                sum += w;
                _levels[i] = Math.Max(0, entries[i].level);
                _cumulative[i] = sum;
            }
            _total = sum;
        }

        public int Count => _levels.Length;

        /// <summary>Normalised probability of entry i.</summary>
        public float Probability(int i)
        {
            if (_total <= 0) return i == 0 ? 1f : 0f;
            float prev = i == 0 ? 0 : _cumulative[i - 1];
            return (_cumulative[i] - prev) / _total;
        }

        public int LevelAt(int i) => _levels[i];

        public int Sample(Rng rng)
        {
            if (_total <= 0)
            {
                int min = _levels[0];
                foreach (var l in _levels) min = Math.Min(min, l);
                return min;
            }
            float r = rng.NextFloat() * _total;
            for (int i = 0; i < _cumulative.Length; i++)
                if (r < _cumulative[i]) return _levels[i];
            return _levels[_levels.Length - 1];
        }

        /// <summary>The design document's initial distribution: 1:40, 2:25, 4:16, 8:10, 16:5, 32:3, 64+:1.</summary>
        public static WeightedLevel[] DefaultDistribution() => new[]
        {
            new WeightedLevel(0, 40), new WeightedLevel(1, 25), new WeightedLevel(2, 16), new WeightedLevel(3, 10),
            new WeightedLevel(4, 5), new WeightedLevel(5, 3), new WeightedLevel(6, 0.7f), new WeightedLevel(7, 0.3f),
        };
    }
}
