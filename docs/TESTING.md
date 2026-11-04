# Test plan

## Automated

```bash
./gradlew :engine:test
```

Runs on every push in `.github/workflows/build-apk.yml`.

| Suite | Covers |
|---|---|
| `MergeSystemTest` | start state `[1]`, spec sequence 1+1=2 → 4, cascades, Standard / ChainCollection / Hybrid, eligibility options, smaller-cube options, max-length shedding, value conservation over 5 000 random collections, one merge per cube per step, merge event ids, values up to 2^71, rapid collection |
| `CoreTest` | label formatting (1 … 2^50), score per action, saturating maths, ranking, weighted distribution, boost (energy & cooldown), path trail (straight, full circles, clamp, growth, teleport), severing, unique ids, spatial hash vs brute force, RNG, angle helpers |
| `CombatTest` | stronger / weaker / tie outcomes, total-value metric, spawn protection and mode permissions, multiple contacts in the same frame resolve once per chain, drop counts |
| `AITest` | target choice by value/distance, fleeing, chasing vs passive, boundary avoidance, preset ordering, finite directions |
| `SaveTest` | corrupted save repair, personal bests never decrease, unlocks, mode ramps |
| `WorldTest` | the real arena simulation: new game state, movement in all directions incl. circles and the boundary, collecting & merging through the world, 3-minute sessions for every mode × difficulty with invariants (sorted chains, segments mirror the model, inside arena, outside obstacles, no double defeats), determinism |

`.github/workflows/emulator-screenshots.yml` installs the APK on an Android 11 emulator, opens the menu and an
AI-driven match (including pause and landscape), captures screenshots and a logcat summary.

## Manual device checklist

- [ ] Installs and launches offline (airplane mode); main menu shows.
- [ ] New game: single cube "1"; HUD shows score 0, chain 1, best 1.
- [ ] Joystick steers in all directions, circles and reversals keep the chain intact; release keeps heading.
- [ ] Tap-to-steer (Settings) steers toward the finger.
- [ ] Collect a 1 → `[1,1]` → merges to `[2]`; greyed (bigger) cubes are ignored.
- [ ] Boost speeds up, drains the ring, refills; can't boost on empty.
- [ ] Rivals collect, flee bigger chains, chase smaller ones (Normal/Hard).
- [ ] Smaller rival's head → defeated (burst, drops, "+score"); body → tail cut. Bigger rival → game over (Classic/Survival). Endless never ends by collision. Equal heads bounce.
- [ ] Danger warning + red name tags near bigger rivals; minimap colours match.
- [ ] Arena edge warning; chain slides along the boundary.
- [ ] Pause → Resume / Restart / Settings / End Run / Main Menu; Back button pauses/resumes; Home pauses.
- [ ] Game over shows score, highest number, longest chain, rivals defeated, time, best comparison; stats persist after relaunch.
- [ ] Settings (volumes, controls, camera, minimap, low effects, 30/60 FPS) apply and persist.
- [ ] Rotation mid-game works; notch / gesture areas respected.
- [ ] 20+ minutes in Endless: stable frame rate and memory.
