# Test plan

## Automated (run on every push by `.github/workflows/logic-tests.yml`)

```bash
dotnet test tools/LogicTests
```

The same test files run in Unity: **Window → General → Test Runner → EditMode → Run All**.

| Suite | Covers |
|---|---|
| `MergeSystemTests` | start state `[1]`, spec sequence 1+1=2 → 4, cascades, Standard/ChainCollection/Hybrid, eligibility options, smaller-cube options, max length shedding, value conservation over 5 000 random collections, one merge per cube per step, merge event ids, values up to 2^71, rapid collection |
| `NumberAndScoreTests` | label formatting (1 … 2^50), score per action, saturating maths, ranking, weighted distribution (normalisation and sampled frequencies), boost energy and cooldown modes |
| `ChainAndSpatialTests` | path trail on straight lines and full circles, clamping, growth, teleport; chain severing; unique ids; spatial hash vs brute force |
| `CombatTests` | stronger/weaker/tie outcomes, total-value metric, spawn protection and mode permissions, **multiple contacts in the same frame resolve at most once per chain**, drop counts |
| `AITests` | target selection by value/distance, fleeing, chasing, passive mode, boundary avoidance, presets ordering, finite directions |
| `SaveAndProgressTests` | corrupted save repair, personal bests never decrease, unlocks, survival ramp |
| `ArenaSimulationTests` | 4 simulated minutes × 3 difficulties of 8 AI chains (collect/merge/fight/respawn) asserting sorted chains, length caps, finite positions inside the arena, no double defeats |

## Manual device checklist (Android)

Run on at least one mid-range phone and one emulator (portrait and landscape). Tick each item.

**Core**
- [ ] App installs, launches offline (airplane mode) and shows the main menu.
- [ ] New game starts with a single cube showing 1; HUD shows score 0, chain 1, best 1.
- [ ] Joystick steers in all directions, including full circles and instant reversals; chain stays intact.
- [ ] Releasing the joystick keeps the current heading; the knob re-centres.
- [ ] Tap-to-steer (Settings) steers toward the finger.
- [ ] Collecting a 1 shows `[1,1]` then merges to `[2]`; greyed-out (bigger) cubes are ignored.
- [ ] Boost speeds up, drains the ring, refills after release; cannot boost when empty.
- [ ] Rivals collect cubes, flee bigger chains and chase smaller ones (Normal/Hard).
- [ ] Hitting a smaller rival's head defeats it (burst, drops, "+score", "Defeated X!"); hitting its body cuts its tail.
- [ ] Running into a bigger rival ends the game (Classic/Survival); Endless never ends by collision.
- [ ] Equal heads bounce apart.
- [ ] Danger warning + red name tag when a bigger rival is near; minimap colours match.
- [ ] Arena edge: warning text, chain slides along the boundary, never leaves.

**Flow & persistence**
- [ ] Pause → Resume / Restart / Settings / End Run / Main Menu all work; Android back button pauses/resumes.
- [ ] Home button / incoming call pauses the game; resuming shows the pause menu.
- [ ] Game-over shows score, highest number, longest chain, rivals defeated, time, best comparison.
- [ ] Best score and stats persist after force-closing and relaunching the app.
- [ ] Changing settings (volume, controls, camera, minimap, low effects, 30/60 FPS) takes effect and persists.

**Edge cases**
- [ ] Rotate the device mid-game: UI re-lays out, safe areas respected, controls reachable.
- [ ] Very small and very tall aspect ratios (e.g. 16:9 and 20:9) keep HUD readable.
- [ ] Play 20+ minutes in Endless Growth: frame rate stable, no growing memory (Android Studio profiler / `adb shell dumpsys meminfo com.oxdh.cubeclash`).
- [ ] Low effects + 30 FPS on a low-end device stays smooth.

Record results (device, Android version, FPS observed, issues) in the PR or release notes.
