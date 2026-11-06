# Cube Clash — Game Rules (reference)

This document is the specification the code implements. Every rule below is configurable in
`engine/src/main/kotlin/com/oxdh/cubeclash/engine/` (`RulesConfig` in `Numbers.kt`, the rest in `Config.kt`)
and covered by automated tests in `engine/src/test/`.

## 1. Four independent quantities

| Quantity | Where it lives | Changes when |
|---|---|---|
| **Cube value** | `ChainCell.level` (value = 2^level) | merges, upgrades |
| **Chain length** | `ChainModel.length` | a cube is added, merged away, shed, or severed |
| **Score** | `SessionStats.score` (saturating `long`) | collect, merge, defeat, sever, shed |
| **Highest value** | `SessionStats.highestLevel` | the leading cube reaches a new maximum |

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

Ineligible (bigger) cubes are greyed out and **solid**: a head cannot enter them. Ramming one pushes it only
slightly (less the bigger it is) and holds the snake back to ~30 % speed; speed returns smoothly once you turn away.

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

## 7. Eating snakes (number-based)

A chain's **head is its mouth**; its number is the leading cube's value. Only the **front** of the head bites
(contacts behind the head's mid-line are ignored), and only real contact counts (penetration of the cube's radius).

| Situation | Outcome |
|---|---|
| Head touches another snake's cube with a **smaller** number — that cube is the head | The whole snake is eaten: all its cubes are added to the eater (then merge), plus defeat score |
| … that cube is a body cube | That cube and everything behind it are eaten (`severOnBodyHit`, default on) |
| The touched cube is **equal or bigger** | Solid: heads **bounce** apart; a body **blocks** the head (pushed back out, strong slow-down) |
| Either snake spawn-protected (3 s, ring blinks) or the mode forbids it | Solid contact |

Example: with an 8 you eat 1, 2, 4 — never 8 or more, whether anyone is moving, standing or boosting. A small snake
with a 4 can eat a 2 or a 1 at the end of a big snake's tail; the big snake can turn around and eat it.
Per frame each chain takes part in at most one eat (so it is eaten at most once); eats are resolved before solid
contacts. Physics runs in sub-steps of ≤ 1/60 s so fast snakes cannot tunnel through anything.
In multiplayer the host resolves all of this, so the rules are identical for every player.

## 8. Movement and boost

* Chains always move forward; input steers (max 360°/s × turn sensitivity, so U-turns take about half a second).
  Bigger heads are slightly slower.
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
