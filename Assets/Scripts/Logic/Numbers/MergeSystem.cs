using System;
using System.Collections.Generic;

namespace CubeSnake.Logic
{
    public enum ChainEventType
    {
        /// <summary>A new cube was added at Index with Level.</summary>
        CubeAdded,
        /// <summary>CellId (survivor) absorbed OtherCellId (removed); survivor now has Level.</summary>
        Merged,
        /// <summary>CellId's value was doubled in place (ChainCollection / FeedLeader); now has Level.</summary>
        Upgraded,
        /// <summary>CellId (level Level) was removed because the chain exceeded its maximum length.</summary>
        Shed,
        /// <summary>The collected cube only produced score.</summary>
        ScoreOnly,
        /// <summary>A FeedLeader collection added value to the leader's progress meter without upgrading.</summary>
        LeaderFed,
    }

    public struct ChainEvent
    {
        public ChainEventType Type;
        public int CellId;
        public int OtherCellId;
        public int Level;
        public int Index;
        public long Score;

        public override string ToString() => $"{Type} cell={CellId} other={OtherCellId} lvl={Level} idx={Index} score={Score}";
    }

    public enum CollectResult
    {
        /// <summary>The cube was not eligible; nothing changed.</summary>
        Rejected,
        /// <summary>The cube was accepted and the chain/score were updated.</summary>
        Accepted,
    }

    /// <summary>
    /// Applies collection and merge rules to a <see cref="ChainModel"/>. The merge system is the ONLY place where
    /// cube values change, and every change is reported as a <see cref="ChainEvent"/> so views, score and stats
    /// react to exactly what happened. Merges are driven by the model (never by physics contacts), so a merge
    /// can happen at most once and overlapping colliders can never cascade.
    ///
    /// Merge eligibility (Standard / Hybrid): only two ADJACENT cubes with EQUAL level merge. The head-most such
    /// pair is merged first; the front cube survives with level+1 and the rear cube is removed. Because the chain
    /// is sorted non-increasing, this preserves ordering. Within one step each cube can take part in only one merge.
    /// </summary>
    public sealed class MergeSystem
    {
        readonly RulesConfig _rules;

        public MergeSystem(RulesConfig rules)
        {
            _rules = rules ?? throw new ArgumentNullException(nameof(rules));
        }

        public RulesConfig Rules => _rules;

        /// <summary>Whether <paramref name="chain"/> may collect a world cube of <paramref name="level"/>.</summary>
        public bool CanCollect(ChainModel chain, int level)
        {
            if (chain == null || chain.IsEmpty || level < 0) return false;
            switch (_rules.eligibility)
            {
                case CollectEligibility.AnyValue:
                    return true;
                case CollectEligibility.MatchingChainValue:
                    for (int i = 0; i < chain.Length; i++)
                        if (chain.Cells[i].Level == level) return true;
                    return false;
                default:
                    return level <= chain.HeadLevel;
            }
        }

        /// <summary>
        /// Collects a world cube. Returns Rejected (and changes nothing) when the cube is not eligible.
        /// All resulting changes are appended to <paramref name="events"/>.
        /// </summary>
        public CollectResult TryCollect(ChainModel chain, int level, List<ChainEvent> events)
        {
            if (!CanCollect(chain, level)) return CollectResult.Rejected;
            if (level > NumberValue.MaxLevel) level = NumberValue.MaxLevel;

            long collectScore = ScoreCalculator.ForCollect(level, _rules);
            bool smaller = level < chain.HeadLevel;

            if (smaller && _rules.smallerCubeBehaviour == SmallerCubeBehaviour.ScoreOnly)
            {
                events?.Add(new ChainEvent { Type = ChainEventType.ScoreOnly, Level = level, Score = collectScore, CellId = chain.Head.Id });
                return CollectResult.Accepted;
            }

            if (smaller && _rules.smallerCubeBehaviour == SmallerCubeBehaviour.FeedLeader)
            {
                FeedLeader(chain, level, collectScore, events);
                return CollectResult.Accepted;
            }

            switch (_rules.mergeMode)
            {
                case MergeMode.ChainCollection:
                    CollectChainMode(chain, level, collectScore, events);
                    break;
                case MergeMode.Standard:
                    AddCube(chain, level, collectScore, events);
                    ResolveAllMerges(chain, events);
                    break;
                default: // Hybrid
                    AddCube(chain, level, collectScore, events);
                    if (_rules.hybridMergeInterval <= 0f) ResolveAllMerges(chain, events);
                    break;
            }
            return CollectResult.Accepted;
        }

        void AddCube(ChainModel chain, int level, long collectScore, List<ChainEvent> events)
        {
            var cell = chain.InsertSorted(level);
            events?.Add(new ChainEvent { Type = ChainEventType.CubeAdded, CellId = cell.Id, Level = level, Index = chain.IndexOf(cell.Id), Score = collectScore });
            EnforceMaxLength(chain, events);
        }

        void CollectChainMode(ChainModel chain, int level, long collectScore, List<ChainEvent> events)
        {
            // Head-most cube with the same level doubles in place; this keeps the sort order (see class docs).
            for (int i = 0; i < chain.Length; i++)
            {
                var cell = chain.Cells[i];
                if (cell.Level != level) continue;
                chain.SetLevel(cell, level + 1);
                long mergeScore = ScoreCalculator.ForMerge(level + 1, _rules);
                events?.Add(new ChainEvent { Type = ChainEventType.Upgraded, CellId = cell.Id, Level = cell.Level, Index = i, Score = collectScore + mergeScore });
                return;
            }
            AddCube(chain, level, collectScore, events);
        }

        void FeedLeader(ChainModel chain, int level, long collectScore, List<ChainEvent> events)
        {
            chain.LeaderProgress += NumberValue.ValueOf(level);
            bool upgraded = false;
            // A leader of value V upgrades once it has been fed V worth of cubes. Loop in case a large cube overfills.
            while (chain.LeaderProgress >= chain.Head.Value && chain.HeadLevel < NumberValue.MaxLevel)
            {
                chain.LeaderProgress -= chain.Head.Value;
                chain.SetLevel(chain.Head, chain.HeadLevel + 1);
                long mergeScore = ScoreCalculator.ForMerge(chain.HeadLevel, _rules);
                events?.Add(new ChainEvent { Type = ChainEventType.Upgraded, CellId = chain.Head.Id, Level = chain.HeadLevel, Index = 0, Score = (upgraded ? 0 : collectScore) + mergeScore });
                upgraded = true;
            }
            if (!upgraded)
                events?.Add(new ChainEvent { Type = ChainEventType.LeaderFed, CellId = chain.Head.Id, Level = level, Index = 0, Score = collectScore });
            // The leader may now equal the second cube; let the configured merge mode deal with it.
            if (_rules.mergeMode == MergeMode.Standard || (_rules.mergeMode == MergeMode.Hybrid && _rules.hybridMergeInterval <= 0f))
                ResolveAllMerges(chain, events);
        }

        void EnforceMaxLength(ChainModel chain, List<ChainEvent> events)
        {
            while (chain.Length > _rules.maxChainLength)
            {
                int tail = chain.Length - 1;
                var cell = chain.Cells[tail];
                chain.RemoveAt(tail);
                events?.Add(new ChainEvent { Type = ChainEventType.Shed, CellId = cell.Id, Level = cell.Level, Index = tail, Score = ScoreCalculator.ForShed(cell.Level, _rules) });
            }
        }

        /// <summary>True when the chain currently contains at least one mergeable (adjacent, equal) pair.</summary>
        public bool HasPendingMerge(ChainModel chain)
        {
            if (_rules.mergeMode == MergeMode.ChainCollection) return false;
            return chain.FirstEqualAdjacentPair() >= 0;
        }

        /// <summary>
        /// Performs up to <see cref="RulesConfig.hybridMergesPerStep"/> merges (Hybrid timer tick).
        /// Each cube participates in at most one merge per step. Returns the number of merges performed.
        /// </summary>
        public int StepMerges(ChainModel chain, List<ChainEvent> events)
        {
            if (_rules.mergeMode == MergeMode.ChainCollection) return 0;
            return MergePass(chain, _rules.hybridMergesPerStep, events);
        }

        /// <summary>Merges repeatedly until no adjacent equal pair remains. Returns number of merges.</summary>
        public int ResolveAllMerges(ChainModel chain, List<ChainEvent> events)
        {
            int total = 0;
            // Each merge removes one cube, so the loop is bounded by the chain length.
            int guard = chain.Length + 1;
            while (guard-- > 0)
            {
                int merged = MergePass(chain, int.MaxValue, events);
                if (merged == 0) break;
                total += merged;
            }
            return total;
        }

        int MergePass(ChainModel chain, int maxMerges, List<ChainEvent> events)
        {
            int merges = 0;
            int start = 0;
            while (merges < maxMerges)
            {
                int i = chain.FirstEqualAdjacentPair(start);
                if (i < 0) break;
                var survivor = chain.Cells[i];
                var removed = chain.Cells[i + 1];
                int newLevel = Math.Min(survivor.Level + 1, NumberValue.MaxLevel);
                chain.RemoveAt(i + 1);
                chain.SetLevel(survivor, newLevel);
                events?.Add(new ChainEvent
                {
                    Type = ChainEventType.Merged,
                    CellId = survivor.Id,
                    OtherCellId = removed.Id,
                    Level = newLevel,
                    Index = i,
                    Score = ScoreCalculator.ForMerge(newLevel, _rules),
                });
                merges++;
                // The survivor already merged this pass; continue scanning behind it.
                start = i + 1;
            }
            return merges;
        }
    }
}
