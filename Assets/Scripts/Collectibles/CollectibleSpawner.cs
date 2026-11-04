using System.Collections.Generic;
using CubeSnake.Chain;
using CubeSnake.Core;
using CubeSnake.Logic;
using UnityEngine;

namespace CubeSnake.Collectibles
{
    /// <summary>
    /// Spawns, tracks and recycles world collectibles. Uses a weighted, normalised level distribution (with an
    /// optional progression shift), rejection sampling for valid positions, a spatial hash for queries, and
    /// pooling for zero per-frame allocations.
    /// </summary>
    public sealed class CollectibleSpawner : MonoBehaviour
    {
        ArenaContext _ctx;
        SpawnConfig _cfg;
        ObjectPool<CollectibleCube> _pool;
        WeightedTable _table;
        readonly List<CollectibleCube> _active = new List<CollectibleCube>(512);
        readonly List<CollectibleCube> _collecting = new List<CollectibleCube>(32);
        readonly SpatialHash<CollectibleCube> _hash = new SpatialHash<CollectibleCube>(6f);
        int _targetCount;
        int _lastEligibilityLevel = int.MinValue;
        int _failedPlacementsThisFrame;

        public int ActiveCount => _active.Count;
        public IReadOnlyList<CollectibleCube> Active => _active;
        /// <summary>Number of placement attempts that failed this frame (exposed for diagnostics/tests).</summary>
        public int FailedPlacementsThisFrame => _failedPlacementsThisFrame;

        public void Initialize(ArenaContext ctx)
        {
            _ctx = ctx;
            _cfg = ctx.Config.spawn;
            _table = new WeightedTable(_cfg.distribution);
            _targetCount = Mathf.RoundToInt(_cfg.targetCollectibles * ctx.Mode.collectibleDensityMultiplier);
            _pool = new ObjectPool<CollectibleCube>(() => CollectibleCube.Create(transform), Mathf.Min(_targetCount, 400));
        }

        /// <summary>Fills the arena immediately (used at session start).</summary>
        public void FillInitial()
        {
            int guard = _targetCount * 3;
            while (_active.Count < _targetCount && guard-- > 0) TrySpawnRandom(false);
        }

        public void Tick(float dt, Vector3 cameraFocus)
        {
            _failedPlacementsThisFrame = 0;
            int spawns = 0;
            while (_active.Count < _targetCount && spawns < _cfg.maxSpawnsPerFrame)
            {
                if (!TrySpawnRandom(true)) break;
                spawns++;
            }

            // Animate only what is near the camera; distant cubes stay still (cheap LOD).
            float animR2 = 55f * 55f;
            bool lowFx = SettingsManager.LowEffects;
            for (int i = 0; i < _active.Count; i++)
            {
                var c = _active[i];
                Vector3 p = c.transform.position;
                float dx = p.x - cameraFocus.x, dz = p.z - cameraFocus.z;
                c.Tick(dt, !lowFx && dx * dx + dz * dz < animR2);
            }
            for (int i = _collecting.Count - 1; i >= 0; i--)
            {
                if (_collecting[i].Tick(dt, false))
                {
                    var c = _collecting[i];
                    _collecting.RemoveAt(i);
                    c.Deactivate();
                    _pool.Release(c);
                }
            }

            // Recycle old drops when well over budget.
            if (_active.Count > _cfg.maxCollectibles)
            {
                for (int i = 0; i < _active.Count && _active.Count > _cfg.maxCollectibles; i++)
                {
                    var c = _active[i];
                    if (c.IsDrop && Time.time - c.SpawnTime > _cfg.dropLifetime) { Remove(c, true); i--; }
                }
            }
        }

        int SampleLevel()
        {
            int level = _table.Sample(_ctx.Rng);
            var player = _ctx.Player;
            if (player != null && player.Alive && _ctx.Rng.NextFloat() < _cfg.progressionMix)
            {
                int shift = Mathf.FloorToInt(Mathf.Max(0, player.HeadLevel - _cfg.progressionStartLevel) * _cfg.progressionShiftPerLevel);
                level += shift;
            }
            return Mathf.Clamp(level, 0, NumberValue.MaxLevel);
        }

        bool TrySpawnRandom(bool preferNearPlayer)
        {
            int level = SampleLevel();
            for (int attempt = 0; attempt < _cfg.maxPlacementAttempts; attempt++)
            {
                Vec2 p;
                var player = _ctx.Player;
                if (preferNearPlayer && player != null && player.Alive && _ctx.Rng.NextFloat() < 0.5f)
                {
                    // Spawn in a ring around the player, mostly just outside the view, to keep the action dense.
                    float a = _ctx.Rng.NextFloat() * MathUtil.TwoPi;
                    float r = _ctx.Rng.Range(18f, 50f);
                    p = player.HeadPosition + Vec2.FromAngle(a) * r;
                }
                else p = _ctx.Rng.InsideDisc(_ctx.ArenaRadius - 3f);

                if (!IsValidSpot(p)) continue;
                Spawn(level, p, false);
                return true;
            }
            _failedPlacementsThisFrame++;
            return false; // no valid location this frame; try again next frame
        }

        bool IsValidSpot(Vec2 p)
        {
            if (!_ctx.IsFreeSpot(p, 1.5f)) return false;
            if (_hash.AnyWithin(p, _cfg.minSpacing)) return false;
            float minHead = _cfg.minDistanceFromHeads;
            for (int i = 0; i < _ctx.Chains.Count; i++)
            {
                var chain = _ctx.Chains[i];
                if (!chain.Alive) continue;
                if (Vec2.DistanceSquared(chain.HeadPosition, p) < minHead * minHead) return false;
                // Not inside any chain body either.
                var segs = chain.Segments;
                for (int s = 1; s < segs.Count; s++)
                {
                    var sp = segs[s].Position;
                    float dx = sp.x - p.X, dz = sp.z - p.Y;
                    float r = segs[s].Radius + 1f;
                    if (dx * dx + dz * dz < r * r) return false;
                }
            }
            return true;
        }

        public CollectibleCube Spawn(int level, Vec2 position, bool isDrop)
        {
            var c = _pool.Get();
            float size = _ctx.Config.movement.CubeSize(level) * 0.92f;
            bool dim = _ctx.Player != null && !_ctx.Player.CanCollect(level);
            c.Activate(level, position, size, isDrop, dim, SettingsManager.LowEffects);
            _active.Add(c);
            _hash.Insert(c, position);
            return c;
        }

        /// <summary>Scatters drops from a defeated/severed chain into the arena as collectibles.</summary>
        public void SpawnDrops(List<CubeDrop> drops, float dropFraction)
        {
            int count = CombatResolver.DropCount(drops.Count, dropFraction);
            for (int i = 0; i < count; i++)
            {
                var d = drops[i];
                Vec2 p = ArenaContext.ToPlane(d.Position) + _ctx.Rng.InsideDisc(0.8f);
                if (p.Length > _ctx.ArenaRadius - 2f) p = p.Normalized * (_ctx.ArenaRadius - 2f);
                // Avoid spawning inside obstacles: push to the nearest free spot along the radius.
                for (int o = 0; o < _ctx.Obstacles.Count; o++)
                {
                    var ob = _ctx.Obstacles[o];
                    Vec2 dv = p - ob.Position;
                    float min = ob.Radius + 1.2f;
                    if (dv.Length < min) p = ob.Position + (dv.LengthSquared > 1e-4f ? dv.Normalized : Vec2.UnitX) * min;
                }
                Spawn(d.Level, p, true);
            }
        }

        public int Query(Vec2 center, float radius, List<CollectibleCube> results) => _hash.Query(center, radius, results);

        /// <summary>Removes a collectible. If <paramref name="animateTo"/> is set it flies into that transform first.</summary>
        public void Collect(CollectibleCube c, Transform animateTo)
        {
            if (c == null || c.CurrentState == CollectibleCube.State.BeingCollected || c.CurrentState == CollectibleCube.State.Inactive) return;
            _hash.Remove(c);
            _active.Remove(c);
            c.BeginCollect(animateTo);
            _collecting.Add(c);
        }

        void Remove(CollectibleCube c, bool immediate)
        {
            _hash.Remove(c);
            _active.Remove(c);
            c.Deactivate();
            _pool.Release(c);
        }

        /// <summary>Dims every collectible the player cannot take yet. Cheap: only runs when the player's head level changes.</summary>
        public void RefreshEligibility(CubeChainController player, bool force = false)
        {
            if (player == null) return;
            int level = player.HeadLevel;
            if (!force && level == _lastEligibilityLevel) return;
            _lastEligibilityLevel = level;
            for (int i = 0; i < _active.Count; i++) _active[i].SetDimmed(!player.CanCollect(_active[i].Level));
        }
    }
}
