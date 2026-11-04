using System;
using System.Collections.Generic;
using CubeSnake.Chain;
using CubeSnake.Collectibles;
using CubeSnake.Core;
using CubeSnake.Logic;

namespace CubeSnake.Combat
{
    /// <summary>
    /// Detects every gameplay interaction once per frame using spatial hashes (no physics callbacks, so there are no
    /// duplicate or re-entrant contacts):
    ///  - chain head vs world collectible  -> collection (rules decide eligibility; ineligible cubes are ignored)
    ///  - chain head vs other chains' cubes -> combat contacts, resolved together by <see cref="CombatResolver.ResolveFrame"/>
    /// Obstacles and the arena boundary are handled in chain movement.
    /// </summary>
    public sealed class CollisionManager
    {
        readonly ArenaContext _ctx;
        readonly SpatialHash<ChainSegment> _segmentHash = new SpatialHash<ChainSegment>(4f);
        readonly List<ChainSegment> _segQuery = new List<ChainSegment>(32);
        readonly List<CollectibleCube> _colQuery = new List<CollectibleCube>(16);
        readonly List<Contact> _contacts = new List<Contact>(16);
        readonly List<ResolvedContact> _resolved = new List<ResolvedContact>(8);
        readonly Func<int, ChainSnapshot> _lookup;

        /// <summary>Raised for each resolved combat (GameManager applies defeats, severs and bounces).</summary>
        public event Action<ResolvedContact> CombatResolved;

        public CollisionManager(ArenaContext ctx)
        {
            _ctx = ctx;
            _lookup = id =>
            {
                var c = _ctx.FindChain(id);
                return c != null ? c.Snapshot() : default;
            };
        }

        public void Step()
        {
            var chains = _ctx.Chains;

            // 1) Collection.
            for (int i = 0; i < chains.Count; i++)
            {
                var chain = chains[i];
                if (!chain.Alive || chain.Segments.Count == 0) continue;
                _colQuery.Clear();
                float reach = chain.HeadRadius + 1.6f;
                _ctx.Spawner.Query(chain.HeadPosition, reach, _colQuery);
                var headTransform = chain.Segments[0].transform;
                for (int k = 0; k < _colQuery.Count; k++)
                {
                    var c = _colQuery[k];
                    if (c.CurrentState == CollectibleCube.State.BeingCollected) continue;
                    float r = chain.HeadRadius + c.Radius * 0.9f;
                    if (Vec2.DistanceSquared(c.PlanePosition, chain.HeadPosition) > r * r) continue;
                    if (!chain.CanCollect(c.Level)) continue;
                    if (chain.TryCollect(c.Level, c.transform.position)) _ctx.Spawner.Collect(c, headTransform);
                }
            }

            // 2) Combat contacts.
            _segmentHash.Clear();
            for (int i = 0; i < chains.Count; i++)
            {
                var chain = chains[i];
                if (!chain.Alive) continue;
                var segs = chain.Segments;
                for (int s = 0; s < segs.Count; s++) _segmentHash.Insert(segs[s], ArenaContext.ToPlane(segs[s].Position));
            }

            _contacts.Clear();
            for (int i = 0; i < chains.Count; i++)
            {
                var attacker = chains[i];
                if (!attacker.Alive || attacker.Segments.Count == 0) continue;
                _segQuery.Clear();
                float headR = attacker.HeadRadius;
                Vec2 head = attacker.HeadPosition;
                _segmentHash.Query(head, headR + 1.5f, _segQuery);
                // Keep only the deepest contact per defender chain.
                for (int k = 0; k < _segQuery.Count; k++)
                {
                    var seg = _segQuery[k];
                    var defender = seg.Chain;
                    if (defender == null || defender == attacker || !defender.Alive) continue;
                    float dist = Vec2.Distance(ArenaContext.ToPlane(seg.Position), head);
                    float pen = headR + seg.Radius * 0.9f - dist;
                    if (pen <= 0f) continue;
                    int existing = _contacts.FindIndex(c => c.AttackerId == attacker.ChainId && c.DefenderId == defender.ChainId);
                    var contact = new Contact { AttackerId = attacker.ChainId, DefenderId = defender.ChainId, DefenderSegmentIndex = seg.Index, Penetration = pen };
                    if (existing < 0) _contacts.Add(contact);
                    else if (_contacts[existing].Penetration < pen) _contacts[existing] = contact;
                }
            }

            if (_contacts.Count == 0) return;
            CombatResolver.ResolveFrame(_contacts, _lookup, _ctx.Rules, _resolved);
            for (int i = 0; i < _resolved.Count; i++) CombatResolved?.Invoke(_resolved[i]);
        }
    }
}
