using System;

namespace CubeSnake.Logic
{
    /// <summary>How collected cubes interact with the chain. See docs/GAME_RULES.md for the exact definitions.</summary>
    public enum MergeMode
    {
        /// <summary>Mode A: the collected cube is inserted and every equal adjacent pair merges immediately in the same step.</summary>
        Standard = 0,
        /// <summary>Mode B: collecting a cube equal to a chain cube doubles that cube; otherwise the cube is added. No cascades.</summary>
        ChainCollection = 1,
        /// <summary>Mode C (default): the collected cube is added to the chain; adjacent equal pairs then merge one at a time on a merge timer.</summary>
        Hybrid = 2,
    }

    /// <summary>Which world cubes a chain may collect.</summary>
    public enum CollectEligibility
    {
        /// <summary>Default: values less than or equal to the leading cube.</summary>
        LessOrEqualToHead = 0,
        /// <summary>Any value can be collected (more forgiving, mostly for testing / casual modes).</summary>
        AnyValue = 1,
        /// <summary>Only values exactly equal to the leading cube or any cube already in the chain.</summary>
        MatchingChainValue = 2,
    }

    /// <summary>What happens when an eligible collected cube is strictly smaller than the leading cube.</summary>
    public enum SmallerCubeBehaviour
    {
        /// <summary>Default: the cube is added to the chain (and then follows the merge mode).</summary>
        AddBodyCube = 0,
        /// <summary>The cube's value is fed into the leading cube's progress meter; when the meter reaches the
        /// leader's value the leader doubles. Chain length never changes.</summary>
        FeedLeader = 1,
        /// <summary>The cube only awards score. Chain is unchanged.</summary>
        ScoreOnly = 2,
    }

    /// <summary>How chain strength is measured for combat.</summary>
    public enum StrengthMetric
    {
        /// <summary>Default: the value of the leading cube.</summary>
        HeadValue = 0,
        /// <summary>The sum of every cube value in the chain.</summary>
        TotalValue = 1,
    }

    /// <summary>What happens when two chains of equal strength collide head-first.</summary>
    public enum TieRule
    {
        /// <summary>Default: both chains are pushed apart, nobody is defeated.</summary>
        Bounce = 0,
        /// <summary>Both chains are defeated.</summary>
        BothDefeated = 1,
        /// <summary>Nothing happens; the chains pass through each other.</summary>
        Ignore = 2,
    }

    /// <summary>
    /// All tunable progression, merging, scoring and combat rules. Pure data with safe defaults so it can be
    /// serialised by Unity (inside GameConfig) and constructed directly in unit tests.
    /// </summary>
    [Serializable]
    public class RulesConfig
    {
        public MergeMode mergeMode = MergeMode.Hybrid;
        public CollectEligibility eligibility = CollectEligibility.LessOrEqualToHead;
        public SmallerCubeBehaviour smallerCubeBehaviour = SmallerCubeBehaviour.AddBodyCube;

        /// <summary>Seconds between automatic merge steps in Hybrid mode. 0 or less resolves all merges instantly.</summary>
        public float hybridMergeInterval = 0.18f;
        /// <summary>Maximum number of merges applied per Hybrid merge step (each cube participates at most once per step).</summary>
        public int hybridMergesPerStep = 1;

        /// <summary>Hard cap on chain length. When exceeded the smallest (tail) cube is shed and converted to score.</summary>
        public int maxChainLength = 40;
        /// <summary>Starting leading cube level (0 = value 1).</summary>
        public int startLevel = 0;

        // ---- Score ----
        public float scorePerCollectedValue = 1f;
        public float scorePerMergedValue = 1f;
        public float scoreDefeatValueFraction = 0.5f;
        public long scoreDefeatFlatBonus = 50;
        public float scoreShedValueFraction = 0.5f;

        // ---- Combat ----
        public StrengthMetric strengthMetric = StrengthMetric.HeadValue;
        public TieRule tieRule = TieRule.Bounce;
        /// <summary>When a stronger head hits a weaker chain's body, sever the chain there instead of defeating it outright.</summary>
        public bool severOnBodyHit = true;
        /// <summary>Fraction (0..1) of a defeated / severed chain's cubes that are dropped as collectibles.</summary>
        public float dropFraction = 1f;
        /// <summary>Seconds of spawn protection for newly (re)spawned chains.</summary>
        public float spawnProtectionSeconds = 3f;

        public RulesConfig Clone() => (RulesConfig)MemberwiseClone();

        /// <summary>Clamps every field into a valid range. Called after loading or editing configuration.</summary>
        public void Sanitize()
        {
            if (hybridMergesPerStep < 1) hybridMergesPerStep = 1;
            if (maxChainLength < 1) maxChainLength = 1;
            if (startLevel < 0) startLevel = 0;
            if (startLevel > NumberValue.MaxLevel) startLevel = NumberValue.MaxLevel;
            if (scorePerCollectedValue < 0) scorePerCollectedValue = 0;
            if (scorePerMergedValue < 0) scorePerMergedValue = 0;
            if (scoreDefeatValueFraction < 0) scoreDefeatValueFraction = 0;
            if (scoreDefeatFlatBonus < 0) scoreDefeatFlatBonus = 0;
            if (scoreShedValueFraction < 0) scoreShedValueFraction = 0;
            dropFraction = MathUtil.Clamp01(dropFraction);
            if (spawnProtectionSeconds < 0) spawnProtectionSeconds = 0;
        }
    }
}
