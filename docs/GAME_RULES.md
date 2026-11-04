# Cube Clash — Game Rules (reference)

This document is the specification the code implements. Every rule below is configurable in
`Assets/Resources/GameConfig.asset` (class `RulesConfig` in `Assets/Scripts/Logic/Numbers/ProgressionRules.cs`)
and covered by automated tests in `Assets/Tests/EditMode/`.

## 1. Four independent quantities

| Quantity | Where it lives | Changes when |
|---|---|---|
| **Cube value** | `ChainCell.Level` (value = 2^level) | merges, upgrades |
| **Chain length** | `ChainModel.Length` | a cube is added, merged away, shed, or severed |
| **Score** | `SessionStats.Score` (saturating `long`) | collect, merge, defeat, sever, shed |
| **Highest value** | `SessionStats.HighestLevel` | the leading cube reaches a new maximum |

They are never derived from each other.

## 2. Numbers

* Every value is a power of two, stored as its exponent (`level`): 1 = level 0, 2 = level 1, 4 = level 2 …
* Merging is `level + 1`, so it is exact and cannot overflow (levels are capped at 1000 = 2^1000).
* Labels: exact up to 8192, then `16K`, `524K`, `2.1M`, `1B`, `562T`, and `2^50`+ beyond that.

## 3. The chain

* Index 0 is the **leading cube (head)**. The chain is always sorted **non-increasing** from head to tail.
* A new game starts with exactly one cube of value **1**, score 0.
* Body cubes follow the exact path recorded behind the head (`PathTrail`), spaced by
  `(size(a) + size(b)) / 2 × chainGapFactor`, so the chain survives sharp turns, circles and reversals.
* `maxChainLength` (default 40): if exceeded, the smallest (tail) cube is shed and converted to score
  (`value × scoreShedValueFraction`).

## 4. Collecting world cubes

**Eligibility** (`eligibility`):

| Option | A world cube of value V can be collected if … |
|---|---|
| `LessOrEqualToHead` (default) | V ≤ leading cube |
| `AnyValue` | always (a bigger cube becomes the new head) |
| `MatchingChainValue` | V equals some cube already in the chain |

Ineligible cubes are shown greyed out and are simply ignored on contact (no damage, no collection).

**Smaller cubes** (`smallerCubeBehaviour`, for eligible cubes strictly smaller than the head):

| Option | Effect |
|---|---|
| `AddBodyCube` (default) | The cube is added to the chain and the merge mode applies. |
| `FeedLeader` | The cube's value goes into the leader's progress meter. When the meter reaches the leader's value, the leader doubles (meter − leader value). Chain length never changes. |
| `ScoreOnly` | Only score is awarded; the chain does not change. |

Cubes equal to the head always use the normal (merge mode) path.

## 5. Merge modes (`mergeMode`)

Merge eligibility is identical in Standard and Hybrid: **only two adjacent cubes with equal value merge**.
The **head-most** equal pair merges first; the front cube survives with double the value, the rear cube is
removed. Because the chain is sorted, this keeps it sorted. Within one merge step each cube can take part in at
most one merge. Merges are driven by the chain model, never by physics contacts, so they happen exactly once.

| Mode | Behaviour |
|---|---|
| **A — Standard** | The collected cube is inserted in sorted position and all merges resolve immediately (same frame). |
| **B — ChainCollection** | If a cube of the same value exists, the head-most one doubles in place; otherwise the cube is added. No automatic cascades. |
| **C — Hybrid** (default) | The collected cube is inserted in sorted position (the chain visibly grows), then one merge happens every `hybridMergeInterval` seconds (default 0.18 s, `hybridMergesPerStep` = 1) until no equal neighbours remain. `hybridMergeInterval ≤ 0` resolves instantly. |

Example (Hybrid): `[1]` → collect 1 → `[1, 1]` → merge → `[2]` → collect 2 → `[2, 2]` → `[4]`.
Cascade: `[8, 4, 2, 1]` + 1 → `[8,4,2,1,1]` → `[8,4,2,2]` → `[8,4,4]` → `[8,8]` → `[16]`.

## 6. Score

| Action | Points |
|---|---|
| Collect a cube of value V | V × `scorePerCollectedValue` (1) |
| Merge producing value V | V × `scorePerMergedValue` (1) |
| Defeat a chain with total value T | T × `scoreDefeatValueFraction` (0.5) + `scoreDefeatFlatBonus` (50) |
| Sever cubes with total value T | T × `scoreDefeatValueFraction` |
| Shed a cube of value V (length cap) | V × `scoreShedValueFraction` (0.5) |

## 7. Combat

A chain's **head is its weapon**. Every contact is "attacker head touches a defender cube".
Strength (`strengthMetric`): leading-cube value (default) or total chain value.

| Situation | Outcome |
|---|---|
| Attacker stronger, hits defender's **head** | Defender defeated |
| Attacker stronger, hits defender's **body** | Defender **severed** at that cube (it and everything behind it drop as collectibles). With `severOnBodyHit = false` the defender is defeated instead. |
| Attacker weaker (ran into a bigger chain) | Attacker defeated |
| Equal strength | `tieRule`: `Bounce` (default, both pushed apart), `BothDefeated`, or `Ignore` |
| Either chain spawn-protected (`spawnProtectionSeconds`, 3 s, ring blinks) | Bounce |
| Defeat not permitted by the mode (e.g. Endless Growth player) | Bounce |

Per frame, each chain takes part in **at most one** resolved contact (deepest contact first, deterministic order),
so one collision can never cause multiple defeats. Chains do not collide with themselves.

On defeat: burst effect → `dropFraction` (default 100 %) of its cubes become world collectibles at their positions
→ the winner gets defeat score → rivals respawn after `rivalRespawnDelay`; the player's defeat ends the game.

Ordinary collectibles can never hurt you.

## 8. Movement and boost

* Chains always move forward; input steers (max turn rate × turn sensitivity). Bigger heads are slightly slower.
* Boost (`BoostConfig`): ×1.75 speed, drains energy (0.38/s), regenerates (0.16/s) after a 0.6 s delay, needs
  12 % to start. A cooldown mode is also available.
* The arena is a disc of radius 120 with a visible glowing boundary: chains slide along it and can never leave.
* Obstacles (pillars) push chains around them.

## 9. Modes

| | Classic Arena | Endless Growth | Survival Challenge |
|---|---|---|---|
| Rivals | 9 | 7, never hunt | 10 |
| You can be defeated | yes | no | yes |
| Boost regen | ×1 | ×1.5 | ×0.5 |
| Difficulty ramp | — | — | rivals' speed/aggression/reaction ramp to max over 6 min; respawn sizes grow 0.8 levels/min |

## 10. Spawning

* Weighted, normalised distribution (default 1:40, 2:25, 4:16, 8:10, 16:5, 32:3, 64:0.7, 128:0.3).
* `progressionMix` (40 %) of spawns are shifted up by `(playerHead − 3) × 0.5` levels so the arena keeps pace.
* Rejection sampling: never inside obstacles, outside the arena, within `minSpacing` of another cube, within
  `minDistanceFromHeads` of any head, or inside any chain body. If no valid spot is found in
  `maxPlacementAttempts`, the spawn is retried next frame (no infinite loops).
* Half of the replenishment spawns land in a ring 18–50 units around the player to keep the action dense.
