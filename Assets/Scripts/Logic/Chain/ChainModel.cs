using System;
using System.Collections.Generic;

namespace CubeSnake.Logic
{
    /// <summary>One cube of a chain. Identity (Id) is stable for the lifetime of the cube.</summary>
    public sealed class ChainCell
    {
        public int Id { get; internal set; }
        public int Level { get; internal set; }
        public double Value => NumberValue.ValueOf(Level);
        public override string ToString() => $"#{Id}:{NumberFormat.FormatLevel(Level)}";
    }

    /// <summary>Monotonic id source shared by every chain so cube ids are globally unique at runtime.</summary>
    public sealed class IdGenerator
    {
        int _next = 1;
        public int Next() => _next++;
    }

    /// <summary>
    /// Ordered list of cubes in a chain. Index 0 is the leading cube (head). The model keeps cubes in
    /// non-increasing level order from head to tail. All mutations go through this class (normally via
    /// <see cref="MergeSystem"/>), which keeps the four separate concepts — cube values, chain length,
    /// score and highest value — independent.
    /// </summary>
    public sealed class ChainModel
    {
        readonly List<ChainCell> _cells = new List<ChainCell>(32);
        readonly IdGenerator _ids;

        /// <summary>Accumulated value for <see cref="SmallerCubeBehaviour.FeedLeader"/>.</summary>
        public double LeaderProgress { get; internal set; }

        /// <summary>Incremented on every structural or value change; lets views cheaply detect changes.</summary>
        public int Version { get; private set; }

        public ChainModel(IdGenerator ids, int startLevel = 0)
        {
            _ids = ids ?? throw new ArgumentNullException(nameof(ids));
            Reset(startLevel);
        }

        public IReadOnlyList<ChainCell> Cells => _cells;
        public int Length => _cells.Count;
        public bool IsEmpty => _cells.Count == 0;
        public ChainCell Head => _cells.Count > 0 ? _cells[0] : null;
        public int HeadLevel => _cells.Count > 0 ? _cells[0].Level : 0;

        public double TotalValue
        {
            get
            {
                double sum = 0;
                for (int i = 0; i < _cells.Count; i++) sum += _cells[i].Value;
                return sum;
            }
        }

        public void Reset(int startLevel)
        {
            _cells.Clear();
            LeaderProgress = 0;
            if (startLevel < 0) startLevel = 0;
            _cells.Add(new ChainCell { Id = _ids.Next(), Level = startLevel });
            Version++;
        }

        /// <summary>Replaces the chain with the given levels (sorted descending). Used for rival spawning and tests.</summary>
        public void SetLevels(IList<int> levels)
        {
            _cells.Clear();
            LeaderProgress = 0;
            var sorted = new List<int>(levels);
            sorted.Sort((a, b) => b.CompareTo(a));
            foreach (int level in sorted)
                _cells.Add(new ChainCell { Id = _ids.Next(), Level = Math.Max(0, level) });
            Version++;
        }

        public int IndexOf(int cellId)
        {
            for (int i = 0; i < _cells.Count; i++)
                if (_cells[i].Id == cellId) return i;
            return -1;
        }

        /// <summary>Index at which a cube of <paramref name="level"/> is inserted: after every cube with level >= it.</summary>
        public int SortedInsertIndex(int level)
        {
            int i = 0;
            while (i < _cells.Count && _cells[i].Level >= level) i++;
            return i;
        }

        internal ChainCell InsertSorted(int level)
        {
            var cell = new ChainCell { Id = _ids.Next(), Level = level };
            _cells.Insert(SortedInsertIndex(level), cell);
            Version++;
            return cell;
        }

        internal void SetLevel(ChainCell cell, int level)
        {
            cell.Level = Math.Min(level, NumberValue.MaxLevel);
            Version++;
        }

        internal void RemoveAt(int index)
        {
            _cells.RemoveAt(index);
            Version++;
        }

        /// <summary>Removes every cube from <paramref name="index"/> to the tail and returns them (used when severed).</summary>
        public List<ChainCell> RemoveFrom(int index)
        {
            var removed = new List<ChainCell>();
            if (index < 0) index = 0;
            for (int i = index; i < _cells.Count; i++) removed.Add(_cells[i]);
            if (removed.Count > 0)
            {
                _cells.RemoveRange(index, _cells.Count - index);
                if (index == 0) LeaderProgress = 0;
                Version++;
            }
            return removed;
        }

        /// <summary>True when levels are non-increasing from head to tail (the model's invariant).</summary>
        public bool IsSorted()
        {
            for (int i = 1; i < _cells.Count; i++)
                if (_cells[i].Level > _cells[i - 1].Level) return false;
            return true;
        }

        /// <summary>Index of the head-most adjacent equal pair (i, i+1), or -1.</summary>
        public int FirstEqualAdjacentPair(int startIndex = 0)
        {
            for (int i = Math.Max(0, startIndex); i + 1 < _cells.Count; i++)
                if (_cells[i].Level == _cells[i + 1].Level) return i;
            return -1;
        }

        public int[] LevelsToArray()
        {
            var arr = new int[_cells.Count];
            for (int i = 0; i < arr.Length; i++) arr[i] = _cells[i].Level;
            return arr;
        }

        /// <summary>Human readable values, e.g. "[4, 2, 1]".</summary>
        public override string ToString()
        {
            var parts = new string[_cells.Count];
            for (int i = 0; i < parts.Length; i++) parts[i] = NumberFormat.FormatLevel(_cells[i].Level);
            return "[" + string.Join(", ", parts) + "]";
        }
    }
}
