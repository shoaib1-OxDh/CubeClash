using System;
using System.Collections.Generic;

namespace CubeSnake.Logic
{
    /// <summary>Immutable view of a chain used by the combat rules.</summary>
    public struct ChainSnapshot
    {
        public int ChainId;
        public int HeadLevel;
        public double TotalValue;
        public int Length;
        public bool Alive;
        /// <summary>Spawn protection: cannot be defeated and cannot defeat others.</summary>
        public bool Protected;
        /// <summary>False when this chain may never be defeated (e.g. the player in Endless Growth).</summary>
        public bool CanBeDefeated;
        /// <summary>False when this chain may never defeat others (e.g. passive rivals in Endless Growth).</summary>
        public bool CanDefeatOthers;
    }

    public enum CombatOutcome
    {
        /// <summary>No interaction (dead chain, protection, or ignored tie).</summary>
        None,
        /// <summary>Attacker is stronger and hit the defender's head (or sever disabled): defender is defeated.</summary>
        DefenderDefeated,
        /// <summary>Attacker is stronger and hit the defender's body: defender loses the hit cube and everything behind it.</summary>
        DefenderSevered,
        /// <summary>Attacker ran into a stronger chain: attacker is defeated.</summary>
        AttackerDefeated,
        /// <summary>Both chains are pushed apart.</summary>
        Bounce,
        /// <summary>Equal strength with TieRule.BothDefeated.</summary>
        BothDefeated,
    }

    /// <summary>A head-to-segment contact detected this frame.</summary>
    public struct Contact
    {
        /// <summary>Chain whose HEAD touched the other chain.</summary>
        public int AttackerId;
        public int DefenderId;
        /// <summary>Index of the defender's cube that was touched (0 = head).</summary>
        public int DefenderSegmentIndex;
        /// <summary>Penetration distance; deeper contacts are resolved first.</summary>
        public float Penetration;
    }

    public struct ResolvedContact
    {
        public Contact Contact;
        public CombatOutcome Outcome;
    }

    /// <summary>
    /// Pure combat rules. A chain's HEAD is its weapon: contacts are always "attacker head touches defender cube".
    ///
    /// Strength comparison (configurable metric, default leading-cube value):
    ///  - attacker stronger: defender head hit => defender defeated; body hit => defender severed at that cube
    ///    (or defeated if severOnBodyHit is false).
    ///  - attacker weaker: attacker defeated (it ran into a bigger chain).
    ///  - equal: TieRule (default Bounce).
    /// Protected chains, dead chains, and permission flags downgrade outcomes to Bounce/None.
    /// </summary>
    public static class CombatResolver
    {
        static readonly HashSet<int> s_involved = new HashSet<int>();

        public static int CompareStrength(in ChainSnapshot a, in ChainSnapshot b, StrengthMetric metric)
        {
            if (metric == StrengthMetric.TotalValue)
            {
                // Values are sums of powers of two (exact in double for any realistic chain); compare directly.
                return a.TotalValue.CompareTo(b.TotalValue);
            }
            return a.HeadLevel.CompareTo(b.HeadLevel);
        }

        public static CombatOutcome Resolve(in ChainSnapshot attacker, in ChainSnapshot defender, int defenderSegmentIndex, RulesConfig rules)
        {
            if (!attacker.Alive || !defender.Alive || attacker.ChainId == defender.ChainId) return CombatOutcome.None;
            if (attacker.Protected || defender.Protected) return CombatOutcome.Bounce;

            int cmp = CompareStrength(attacker, defender, rules.strengthMetric);
            if (cmp > 0)
            {
                if (!attacker.CanDefeatOthers || !defender.CanBeDefeated) return CombatOutcome.Bounce;
                bool headHit = defenderSegmentIndex <= 0;
                if (!headHit && rules.severOnBodyHit) return CombatOutcome.DefenderSevered;
                return CombatOutcome.DefenderDefeated;
            }
            if (cmp < 0)
            {
                if (!defender.CanDefeatOthers || !attacker.CanBeDefeated) return CombatOutcome.Bounce;
                return CombatOutcome.AttackerDefeated;
            }
            switch (rules.tieRule)
            {
                case TieRule.BothDefeated:
                    if (!attacker.CanBeDefeated || !defender.CanBeDefeated) return CombatOutcome.Bounce;
                    return CombatOutcome.BothDefeated;
                case TieRule.Ignore:
                    return CombatOutcome.None;
                default:
                    return CombatOutcome.Bounce;
            }
        }

        /// <summary>
        /// Resolves all contacts detected in one frame. Guarantees:
        ///  - each chain takes part in at most ONE resolved (non-None) contact per frame;
        ///  - a chain can be defeated at most once;
        ///  - the result is deterministic (deepest contact first, then by ids).
        /// </summary>
        public static void ResolveFrame(List<Contact> contacts, Func<int, ChainSnapshot> lookup, RulesConfig rules, List<ResolvedContact> results)
        {
            results.Clear();
            if (contacts == null || contacts.Count == 0) return;
            contacts.Sort((a, b) =>
            {
                int c = b.Penetration.CompareTo(a.Penetration);
                if (c != 0) return c;
                c = a.AttackerId.CompareTo(b.AttackerId);
                if (c != 0) return c;
                c = a.DefenderId.CompareTo(b.DefenderId);
                return c != 0 ? c : a.DefenderSegmentIndex.CompareTo(b.DefenderSegmentIndex);
            });
            var involved = s_involved;
            involved.Clear();
            for (int i = 0; i < contacts.Count; i++)
            {
                var c = contacts[i];
                if (involved.Contains(c.AttackerId) || involved.Contains(c.DefenderId)) continue;
                var a = lookup(c.AttackerId);
                var d = lookup(c.DefenderId);
                var outcome = Resolve(a, d, c.DefenderSegmentIndex, rules);
                if (outcome == CombatOutcome.None) continue;
                involved.Add(c.AttackerId);
                involved.Add(c.DefenderId);
                results.Add(new ResolvedContact { Contact = c, Outcome = outcome });
            }
        }

        /// <summary>How many of <paramref name="cubeCount"/> cubes drop as collectibles for the given fraction.</summary>
        public static int DropCount(int cubeCount, float dropFraction)
        {
            if (cubeCount <= 0) return 0;
            int n = (int)Math.Round(cubeCount * MathUtil.Clamp01(dropFraction), MidpointRounding.AwayFromZero);
            return MathUtil.Clamp(n, 0, cubeCount);
        }
    }
}
