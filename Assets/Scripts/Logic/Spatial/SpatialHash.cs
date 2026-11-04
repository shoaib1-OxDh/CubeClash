using System;
using System.Collections.Generic;

namespace CubeSnake.Logic
{
    /// <summary>
    /// Uniform-grid spatial hash for fast radius queries (collectibles near a head, segments near a head, etc.).
    /// Avoids all-pairs checks. Supports incremental insert/move/remove and cheap per-frame Clear().
    /// Allocation-free in steady state: cell lists are pooled and reused.
    /// </summary>
    public sealed class SpatialHash<T> where T : class
    {
        struct Entry
        {
            public T Item;
            public Vec2 Position;
        }

        readonly float _cellSize;
        readonly float _inv;
        readonly Dictionary<long, List<Entry>> _cells = new Dictionary<long, List<Entry>>(256);
        readonly Dictionary<T, long> _itemCell = new Dictionary<T, long>(256);
        readonly Stack<List<Entry>> _pool = new Stack<List<Entry>>();
        readonly List<long> _tmpKeys = new List<long>(256);

        public SpatialHash(float cellSize)
        {
            if (cellSize <= 0) throw new ArgumentOutOfRangeException(nameof(cellSize));
            _cellSize = cellSize;
            _inv = 1f / cellSize;
        }

        public int Count => _itemCell.Count;
        public float CellSize => _cellSize;

        static long Key(int cx, int cy) => ((long)cx << 32) ^ (uint)cy;
        int CellCoord(float v) => (int)Math.Floor(v * _inv);

        public bool Contains(T item) => _itemCell.ContainsKey(item);

        public void Insert(T item, Vec2 position)
        {
            if (item == null) throw new ArgumentNullException(nameof(item));
            if (_itemCell.ContainsKey(item)) { Move(item, position); return; }
            long key = Key(CellCoord(position.X), CellCoord(position.Y));
            if (!_cells.TryGetValue(key, out var list))
            {
                list = _pool.Count > 0 ? _pool.Pop() : new List<Entry>(8);
                _cells[key] = list;
            }
            list.Add(new Entry { Item = item, Position = position });
            _itemCell[item] = key;
        }

        public bool Remove(T item)
        {
            if (item == null || !_itemCell.TryGetValue(item, out long key)) return false;
            _itemCell.Remove(item);
            if (_cells.TryGetValue(key, out var list))
            {
                for (int i = 0; i < list.Count; i++)
                {
                    if (!ReferenceEquals(list[i].Item, item)) continue;
                    list[i] = list[list.Count - 1];
                    list.RemoveAt(list.Count - 1);
                    break;
                }
                if (list.Count == 0)
                {
                    _cells.Remove(key);
                    _pool.Push(list);
                }
            }
            return true;
        }

        public void Move(T item, Vec2 position)
        {
            if (!_itemCell.TryGetValue(item, out long oldKey)) { Insert(item, position); return; }
            long newKey = Key(CellCoord(position.X), CellCoord(position.Y));
            if (newKey == oldKey)
            {
                var list = _cells[oldKey];
                for (int i = 0; i < list.Count; i++)
                    if (ReferenceEquals(list[i].Item, item)) { list[i] = new Entry { Item = item, Position = position }; break; }
                return;
            }
            Remove(item);
            Insert(item, position);
        }

        public void Clear()
        {
            _tmpKeys.Clear();
            foreach (var kv in _cells) _tmpKeys.Add(kv.Key);
            foreach (long k in _tmpKeys)
            {
                var list = _cells[k];
                list.Clear();
                _pool.Push(list);
            }
            _cells.Clear();
            _itemCell.Clear();
        }

        /// <summary>Appends every item within <paramref name="radius"/> of <paramref name="center"/> to <paramref name="results"/>.</summary>
        public int Query(Vec2 center, float radius, List<T> results)
        {
            int found = 0;
            float r2 = radius * radius;
            int minX = CellCoord(center.X - radius), maxX = CellCoord(center.X + radius);
            int minY = CellCoord(center.Y - radius), maxY = CellCoord(center.Y + radius);
            for (int cx = minX; cx <= maxX; cx++)
            {
                for (int cy = minY; cy <= maxY; cy++)
                {
                    if (!_cells.TryGetValue(Key(cx, cy), out var list)) continue;
                    for (int i = 0; i < list.Count; i++)
                    {
                        if (Vec2.DistanceSquared(list[i].Position, center) > r2) continue;
                        results.Add(list[i].Item);
                        found++;
                    }
                }
            }
            return found;
        }

        /// <summary>True if any item lies within <paramref name="radius"/> of <paramref name="center"/>.</summary>
        public bool AnyWithin(Vec2 center, float radius)
        {
            float r2 = radius * radius;
            int minX = CellCoord(center.X - radius), maxX = CellCoord(center.X + radius);
            int minY = CellCoord(center.Y - radius), maxY = CellCoord(center.Y + radius);
            for (int cx = minX; cx <= maxX; cx++)
                for (int cy = minY; cy <= maxY; cy++)
                {
                    if (!_cells.TryGetValue(Key(cx, cy), out var list)) continue;
                    for (int i = 0; i < list.Count; i++)
                        if (Vec2.DistanceSquared(list[i].Position, center) <= r2) return true;
                }
            return false;
        }
    }
}
