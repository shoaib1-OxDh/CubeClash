using System;
using System.Collections.Generic;
using CubeSnake.Core;
using CubeSnake.Logic;
using CubeSnake.Visual;
using UnityEngine;

namespace CubeSnake.Chain
{
    /// <summary>A cube that should become a world collectible (from a defeated or severed chain).</summary>
    public struct CubeDrop
    {
        public int Level;
        public Vector3 Position;
    }

    /// <summary>Something that steers a chain (player input or AI).</summary>
    public interface IChainBrain
    {
        void Think(float dt);
    }

    /// <summary>
    /// One snake-like chain of numbered cubes, used for both the player and rivals. Owns the authoritative
    /// <see cref="ChainModel"/> (values), the <see cref="PathTrail"/> (movement history), the boost meter and the
    /// per-chain stats, and mirrors the model into pooled <see cref="ChainSegment"/> visuals.
    /// Value changes ONLY happen through <see cref="MergeSystem"/>, and every change arrives here as a ChainEvent.
    /// </summary>
    public sealed class CubeChainController : MonoBehaviour
    {
        public int ChainId { get; private set; }
        public string DisplayName { get; private set; }
        public bool IsPlayer { get; private set; }
        public Color TeamColor { get; private set; }
        public string Skin { get; private set; }
        public string TrailId { get; private set; }

        public ChainModel Model { get; private set; }
        public PathTrail Trail { get; private set; }
        public BoostMeter Boost { get; private set; }
        public SessionStats Stats { get; } = new SessionStats();
        public IChainBrain Brain { get; set; }

        public bool Alive { get; private set; }
        public float ProtectionTimer { get; private set; }
        public bool IsProtected => ProtectionTimer > 0f;
        public bool CanBeDefeated { get; set; } = true;
        public bool CanDefeatOthers { get; set; } = true;
        public float SpeedMultiplier { get; set; } = 1f;
        public float TurnSensitivity { get; set; } = 1f;

        /// <summary>Heading in radians (0 = +X, PI/2 = +Z).</summary>
        public float Heading { get; private set; }
        public float Speed { get; private set; }
        public Vec2 HeadPosition => Trail.Head;
        public Vec2 Forward => Vec2.FromAngle(Heading);
        public Vec2 Velocity => Forward * Speed;
        public int HeadLevel => Model.HeadLevel;
        public float HeadRadius => _ctx.Config.movement.CubeSize(Model.HeadLevel) * 0.5f;

        /// <summary>Desired movement direction from the brain; null keeps the current heading.</summary>
        public Vec2? DesiredDirection { get; set; }
        public bool WantsBoost { get; set; }

        public IReadOnlyList<ChainSegment> Segments => _segments;
        public event Action<CubeChainController> HeadLevelChanged;

        ArenaContext _ctx;
        readonly List<ChainEvent> _events = new List<ChainEvent>(16);
        readonly List<ChainSegment> _segments = new List<ChainSegment>(32);
        readonly List<ChainSegment> _absorbing = new List<ChainSegment>(8);
        readonly Dictionary<int, ChainSegment> _byId = new Dictionary<int, ChainSegment>(32);
        readonly List<float> _targetDistances = new List<float>(32);
        float _mergeTimer;
        int _lastHeadLevel = -1;
        bool _wasBoosting;

        Transform _marker;
        TrailRenderer _boostTrail;

        public void Initialize(ArenaContext ctx, int chainId, string displayName, bool isPlayer, string skin, string trailId, Color teamColor)
        {
            _ctx = ctx;
            ChainId = chainId;
            DisplayName = displayName;
            IsPlayer = isPlayer;
            Skin = skin;
            TrailId = trailId;
            TeamColor = teamColor;
            Model = new ChainModel(ctx.Ids, ctx.Rules.startLevel);
            Trail = new PathTrail(0.12f, 40f);
            Boost = new BoostMeter(ctx.Config.boost);
            Boost.RegenMultiplier = ctx.Mode.boostRegenMultiplier;
            BuildMarker();
            BuildBoostTrail();
        }

        void BuildMarker()
        {
            var go = new GameObject("TeamRing");
            go.transform.SetParent(transform, false);
            go.AddComponent<MeshFilter>().sharedMesh = MeshFactory.Ring;
            var mr = go.AddComponent<MeshRenderer>();
            mr.sharedMaterial = CubeMaterials.Transparent;
            mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            var block = new MaterialPropertyBlock();
            block.SetColor("_Color", new Color(TeamColor.r, TeamColor.g, TeamColor.b, 0.85f));
            mr.SetPropertyBlock(block);
            _marker = go.transform;
        }

        void BuildBoostTrail()
        {
            var go = new GameObject("BoostTrail");
            go.transform.SetParent(transform, false);
            _boostTrail = go.AddComponent<TrailRenderer>();
            _boostTrail.sharedMaterial = CubeMaterials.Additive;
            _boostTrail.time = 0.45f;
            _boostTrail.minVertexDistance = 0.25f;
            _boostTrail.widthMultiplier = 0.9f;
            _boostTrail.widthCurve = AnimationCurve.Linear(0, 1, 1, 0);
            _boostTrail.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _boostTrail.receiveShadows = false;
            _boostTrail.alignment = LineAlignment.View;
            var g = new Gradient();
            g.SetKeys(
                new[] { new GradientColorKey(CubePalette.TrailColor(TrailId, 0f), 0f), new GradientColorKey(CubePalette.TrailColor(TrailId, 0.5f), 0.5f), new GradientColorKey(CubePalette.TrailColor(TrailId, 1f), 1f) },
                new[] { new GradientAlphaKey(0.85f, 0f), new GradientAlphaKey(0f, 1f) });
            _boostTrail.colorGradient = g;
            _boostTrail.emitting = false;
        }

        /// <summary>(Re)spawns the chain at a position with the given cube levels (index 0 = head).</summary>
        public void Spawn(Vec2 position, float heading, IList<int> levels)
        {
            ReleaseAllSegments();
            if (levels == null || levels.Count == 0) Model.Reset(_ctx.Rules.startLevel);
            else Model.SetLevels(levels);
            Heading = heading;
            Speed = 0f;
            DesiredDirection = null;
            WantsBoost = false;
            Boost.Refill();
            _mergeTimer = 0f;
            Alive = true;
            ProtectionTimer = _ctx.Rules.spawnProtectionSeconds;
            Trail.Reset(position, Vec2.FromAngle(heading), 30f);
            Stats.Reset(Model.HeadLevel);
            _lastHeadLevel = -1;
            SyncSegments(true);
            if (_boostTrail != null) { _boostTrail.Clear(); _boostTrail.emitting = false; }
            if (_marker != null) _marker.gameObject.SetActive(true);
            gameObject.SetActive(true);
        }

        // ------------------------------------------------------------------ Simulation

        public void Simulate(float dt)
        {
            if (!Alive) return;
            if (ProtectionTimer > 0f) ProtectionTimer -= dt;

            var move = _ctx.Config.movement;
            Boost.Update(dt, WantsBoost);
            if (Boost.IsBoosting && !_wasBoosting) GameEvents.RaiseBoostStarted(this);
            _wasBoosting = Boost.IsBoosting;

            float target = move.baseSpeed * move.SpeedFactor(Model.HeadLevel) * SpeedMultiplier *
                           (Boost.IsBoosting ? _ctx.Config.boost.speedMultiplier : 1f);
            float rate = target > Speed ? move.acceleration : move.deceleration;
            Speed = MathUtil.MoveTowards(Speed, target, rate * dt);

            if (DesiredDirection.HasValue && DesiredDirection.Value.LengthSquared > 1e-6f)
            {
                float maxTurn = move.maxTurnRateDegrees * Mathf.Deg2Rad * move.turnSensitivity * TurnSensitivity * dt;
                Heading = MathUtil.RotateTowards(Heading, DesiredDirection.Value.Angle, maxTurn);
            }

            Vec2 fwd = Vec2.FromAngle(Heading);
            Vec2 pos = Trail.Head + fwd * (Speed * dt);
            float headR = HeadRadius;

            // Arena boundary: slide along the edge, never leave the playable disc.
            float limit = _ctx.ArenaRadius - headR;
            if (pos.Length > limit)
            {
                Vec2 n = pos.Normalized;
                pos = n * limit;
                SlideAlong(n, ref fwd);
            }

            // Obstacles: push out and slide around.
            for (int i = 0; i < _ctx.Obstacles.Count; i++)
            {
                var o = _ctx.Obstacles[i];
                Vec2 d = pos - o.Position;
                float dist = d.Length;
                float min = o.Radius + headR;
                if (dist >= min) continue;
                Vec2 n = dist > 1e-4f ? d / dist : Vec2.UnitX;
                pos = o.Position + n * min;
                SlideAlong(-n, ref fwd);
            }

            Trail.MoveHead(pos);

            // Hybrid merge timer: merges happen one step at a time so they are readable and animated.
            if (_ctx.Merge.HasPendingMerge(Model))
            {
                _mergeTimer += dt;
                if (_mergeTimer >= _ctx.Rules.hybridMergeInterval)
                {
                    _mergeTimer = 0f;
                    _events.Clear();
                    _ctx.Merge.StepMerges(Model, _events);
                    ApplyEvents(_events);
                }
            }
            else _mergeTimer = 0f;
        }

        /// <summary>Removes the velocity component going into a wall whose outward normal is <paramref name="wallNormal"/>.</summary>
        void SlideAlong(Vec2 wallNormal, ref Vec2 fwd)
        {
            float into = Vec2.Dot(fwd, wallNormal);
            if (into <= 0f) return;
            Vec2 tangent = fwd - wallNormal * into;
            if (tangent.LengthSquared < 1e-4f) tangent = wallNormal.Perpendicular;
            fwd = (tangent.Normalized - wallNormal * 0.2f).Normalized;
            Heading = fwd.Angle;
        }

        public void Bounce(Vec2 away)
        {
            if (!Alive || away.LengthSquared < 1e-6f) return;
            away = away.Normalized;
            Vec2 pushed = Trail.Head + away * 0.8f;
            if (pushed.Length > _ctx.ArenaRadius - HeadRadius) pushed = pushed.Normalized * (_ctx.ArenaRadius - HeadRadius);
            Trail.MoveHead(pushed);
            Heading = away.Angle;
            Speed *= 0.4f;
        }

        // ------------------------------------------------------------------ Collection & merging

        public bool CanCollect(int level) => Alive && _ctx.Merge.CanCollect(Model, level);

        /// <summary>Collects a world cube. Returns false (no change) if the rules reject it.</summary>
        public bool TryCollect(int level, Vector3 worldPosition)
        {
            if (!Alive) return false;
            _events.Clear();
            if (_ctx.Merge.TryCollect(Model, level, _events) != CollectResult.Accepted) return false;
            Stats.RecordCollect();
            GameEvents.RaiseCubeCollected(this, level, worldPosition);
            ApplyEvents(_events);
            return true;
        }

        void ApplyEvents(List<ChainEvent> events)
        {
            long gained = 0;
            for (int i = 0; i < events.Count; i++)
            {
                var e = events[i];
                gained = SaturatingMath.Add(gained, e.Score);
                switch (e.Type)
                {
                    case ChainEventType.Merged:
                    {
                        Stats.RecordMerge();
                        if (_byId.TryGetValue(e.OtherCellId, out var removed))
                        {
                            _byId.Remove(e.OtherCellId);
                            _segments.Remove(removed);
                            if (_byId.TryGetValue(e.CellId, out var into)) { removed.BeginAbsorb(into); _absorbing.Add(removed); }
                            else ReleaseSegment(removed);
                        }
                        if (_byId.TryGetValue(e.CellId, out var survivor))
                        {
                            survivor.SetLevel(e.Level, true);
                            GameEvents.RaiseCubesMerged(this, e.Level, survivor.Position);
                        }
                        break;
                    }
                    case ChainEventType.Upgraded:
                        Stats.RecordMerge();
                        if (_byId.TryGetValue(e.CellId, out var up))
                        {
                            up.SetLevel(e.Level, true);
                            GameEvents.RaiseCubesMerged(this, e.Level, up.Position);
                        }
                        break;
                    case ChainEventType.Shed:
                        if (_byId.TryGetValue(e.CellId, out var shed))
                        {
                            _byId.Remove(e.CellId);
                            _segments.Remove(shed);
                            ReleaseSegment(shed);
                        }
                        break;
                }
            }
            if (gained > 0)
            {
                Stats.AddScore(gained);
                GameEvents.RaiseScoreGained(this, gained, ArenaContext.ToWorld(HeadPosition, 1.2f));
            }
            SyncSegments(false);
        }

        /// <summary>Makes the visual segment list mirror the model exactly (creates/removes/reorders as needed).</summary>
        void SyncSegments(bool snap)
        {
            var cells = Model.Cells;
            var move = _ctx.Config.movement;
            // Release visuals whose cells no longer exist (safety net; events normally handle this).
            for (int i = _segments.Count - 1; i >= 0; i--)
            {
                var s = _segments[i];
                if (Model.IndexOf(s.CellId) < 0)
                {
                    _byId.Remove(s.CellId);
                    _segments.RemoveAt(i);
                    ReleaseSegment(s);
                }
            }
            _segments.Clear();
            _targetDistances.Clear();
            float d = 0f;
            for (int i = 0; i < cells.Count; i++)
            {
                var cell = cells[i];
                float size = move.CubeSize(cell.Level);
                if (i > 0) d += (move.CubeSize(cells[i - 1].Level) + size) * 0.5f * move.chainGapFactor;
                _targetDistances.Add(d);
                bool created = false;
                if (!_byId.TryGetValue(cell.Id, out var seg))
                {
                    seg = _ctx.SegmentPool.Get();
                    seg.transform.SetParent(_ctx.SegmentRoot, false);
                    seg.Bind(this, cell.Id, cell.Level, Skin, size);
                    _byId[cell.Id] = seg;
                    created = true;
                }
                seg.SetLevel(cell.Level, false);
                seg.SetTargetSize(size);
                seg.Index = i;
                if (created || snap)
                {
                    seg.Follower.Snap(d);
                    seg.Follower.Follow(Trail, 100f, 1f, 0f);
                }
                else seg.Follower.TargetDistance = d;
                _segments.Add(seg);
            }
            Trail.EnsureLength(d + 6f);
            Stats.ObserveChain(Model.HeadLevel, Model.Length);
            if (Model.HeadLevel != _lastHeadLevel)
            {
                _lastHeadLevel = Model.HeadLevel;
                HeadLevelChanged?.Invoke(this);
            }
            GameEvents.RaiseChainChanged(this);
        }

        // ------------------------------------------------------------------ Visuals

        public void UpdateVisuals(float dt)
        {
            float sharp = _ctx.Config.movement.followSharpness;
            for (int i = 0; i < _segments.Count; i++)
            {
                var seg = _segments[i];
                if (i == 0) seg.Follower.Snap(0f);
                seg.Follower.Follow(Trail, sharp, dt, 0f);
                seg.TickVisual(dt);
            }
            for (int i = _absorbing.Count - 1; i >= 0; i--)
            {
                if (_absorbing[i].TickAbsorb(dt))
                {
                    ReleaseSegment(_absorbing[i]);
                    _absorbing.RemoveAt(i);
                }
            }
            if (Alive && _segments.Count > 0)
            {
                var head = _segments[0].transform;
                float r = _segments[0].CurrentSize * 0.95f;
                _marker.position = new Vector3(head.position.x, 0.025f, head.position.z);
                _marker.localScale = new Vector3(r, 1f, r);
                // Blink while spawn-protected.
                _marker.gameObject.SetActive(!IsProtected || Mathf.Repeat(Time.time * 4f, 1f) > 0.35f);
                _boostTrail.transform.position = head.position + Vector3.up * 0.2f;
                _boostTrail.emitting = Boost.IsBoosting && !SettingsManager.LowEffects;
            }
        }

        // ------------------------------------------------------------------ Combat

        public ChainSnapshot Snapshot() => new ChainSnapshot
        {
            ChainId = ChainId,
            HeadLevel = Model.HeadLevel,
            TotalValue = Model.TotalValue,
            Length = Model.Length,
            Alive = Alive,
            Protected = IsProtected,
            CanBeDefeated = CanBeDefeated,
            CanDefeatOthers = CanDefeatOthers,
        };

        /// <summary>Removes the cube at <paramref name="index"/> and everything behind it; returns them as drops.</summary>
        public List<CubeDrop> Sever(int index)
        {
            var drops = new List<CubeDrop>();
            if (!Alive || index <= 0 || index >= Model.Length) return drops;
            var removed = Model.RemoveFrom(index);
            foreach (var cell in removed)
            {
                if (_byId.TryGetValue(cell.Id, out var seg))
                {
                    drops.Add(new CubeDrop { Level = cell.Level, Position = seg.Position });
                    _byId.Remove(cell.Id);
                    _segments.Remove(seg);
                    ReleaseSegment(seg);
                }
                else drops.Add(new CubeDrop { Level = cell.Level, Position = ArenaContext.ToWorld(HeadPosition) });
            }
            SyncSegments(false);
            return drops;
        }

        /// <summary>Defeats the chain: every cube becomes a drop, visuals are released, simulation stops.</summary>
        public List<CubeDrop> Defeat()
        {
            var drops = new List<CubeDrop>();
            if (!Alive) return drops;
            Alive = false;
            foreach (var seg in _segments) drops.Add(new CubeDrop { Level = seg.Level, Position = seg.Position });
            ReleaseAllSegments();
            Speed = 0f;
            WantsBoost = false;
            if (_marker != null) _marker.gameObject.SetActive(false);
            if (_boostTrail != null) { _boostTrail.emitting = false; _boostTrail.Clear(); }
            return drops;
        }

        void ReleaseSegment(ChainSegment seg)
        {
            seg.Unbind();
            _ctx.SegmentPool.Release(seg);
        }

        void ReleaseAllSegments()
        {
            foreach (var s in _segments) ReleaseSegment(s);
            foreach (var s in _absorbing) ReleaseSegment(s);
            _segments.Clear();
            _absorbing.Clear();
            _byId.Clear();
        }

        void OnDestroy()
        {
            HeadLevelChanged = null;
        }
    }
}
