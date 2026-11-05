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
| `RulesWorldTest` | bigger cubes are solid (head never enters, < 45 % speed while pushing, speed recovers after turning away), equal/smaller cubes are eaten, eating a smaller snake head-on, equal or bigger never eaten even while boosting, a small snake biting a big snake's tail and then being eaten after the big one turns around, the back of the head never bites, multiplayer respawn |
| `ProtocolTest` | every network message round-trips; MQTT packet framing; room codes map to relays |
| `LoopbackTest` | real host + 2 clients (in-memory transport): remote input steers the host's chains, replicas match the host (chains, numbers, scores, positions, cubes), reconnect re-attaches to the same snake, leaving removes it |
| `LanTest` | the same over real TCP sockets, with UDP room discovery and "room closed" handling |
| `OnlineLiveTest` | (CI only) a real session through each public relay, measuring states/s, ping and inputs |
| `WorldTest` | the real arena simulation: new game state, movement in all directions incl. circles and the boundary, collecting & merging through the world, 3-minute sessions for every mode × difficulty with invariants (sorted chains, segments mirror the model, inside arena, outside obstacles, no double defeats), determinism |

`.github/workflows/emulator-screenshots.yml` installs the APK on an Android 11 emulator and checks: the menu, an
AI-driven match (pause, landscape), real touch input (drag to steer, double-tap-and-hold boost), **a LAN room hosted
by the app that a separate bot client joins and plays in** (via `adb forward`), and **an online room hosted by a bot
that the app joins with the room code through the public relay**. The bots fail the job unless the session really
works (states received, inputs delivered, the remote player collects cubes).

## Manual device checklist

- [ ] Installs and launches offline (airplane mode); main menu shows.
- [ ] New game: single cube "1"; HUD shows score 0, chain 1, best 1.
- [ ] Drag anywhere steers in all directions; circles and U-turns keep the chain intact; lifting the finger keeps the heading.
- [ ] A second finger anywhere boosts while the first steers; double-tap-and-hold boosts one-handed; the bar drains and refills.
- [ ] Point-to-steer (Settings) steers toward the finger.
- [ ] Ramming a bigger cube: the snake stops at its surface, slows strongly, the cube barely moves; turning away restores speed.
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

**Multiplayer (two or more phones)**
- [ ] Local: host creates a room on Wi-Fi; a friend finds it with *Find rooms* and joins; both see each other move smoothly.
- [ ] Local via hotspot: the host turns on the hotspot, a friend connects and joins (Find rooms or the address shown).
- [ ] Online: host creates a room, shares the code (Invite friends), a friend on mobile data joins by code.
- [ ] Eating rules are identical on both phones (bigger eats smaller, equal bounces, tail biting).
- [ ] Turn a friend's Wi-Fi off for 10 s and back on: they reconnect to the same snake; leaving removes them.
- [ ] Host ends the room: friends see "The host ended the room".
