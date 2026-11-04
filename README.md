# Cube Clash

An original Android arcade game that combines 2048-style number merging with snake-style movement in an open 3D
arena. You start as a single cube showing **1**, collect numbered cubes, merge equal neighbours
(1+1=2, 2+2=4, …), and outgrow AI rival chains — all offline.

> Built with Unity 2022.3 LTS (C#), Universal Render Pipeline, Unity Input System and uGUI.
> All art (meshes, cube textures, the number font), all sound effects and the music are generated procedurally
> by the game's own code. There are no third-party assets.

---

## Gameplay at a glance

* **Move** with the virtual joystick (lower-left) or tap-to-steer. The chain always moves; you steer.
* **Collect** cubes whose number is ≤ your leading cube. Bigger ones are greyed out until you grow.
* **Merge**: equal neighbours merge into double, one step at a time, so cascades are readable
  (`[8,4,2,1] + 1 → [8,4,2,2] → [8,4,4] → [8,8] → [16]`).
* **Boost** (lower-right) drains an energy ring that refills when released.
* **Fight**: your leading cube is your strength. Hit a weaker rival's head to defeat it, its body to cut off its
  tail; run into a stronger rival and you're out; equal strength bounces. Defeated chains burst into cubes.
* **Modes**: Classic Arena, Endless Growth (you can't be defeated, rivals never hunt), Survival Challenge (rivals
  ramp up every minute, slower boost recovery). **Rival difficulty**: Easy / Normal / Hard.

Exact, configurable rules: [`docs/GAME_RULES.md`](docs/GAME_RULES.md).

## Features

| Area | Implementation |
|---|---|
| Arena | Large disc (radius 120) with procedural anti-aliased grid ground, glowing boundary + posts, pillars, distant floating decorations, fog |
| Chain | Path-recording trail (`PathTrail`) — cubes retrace the exact head path; smooth slide on insert/merge; scale punch on merge |
| Numbers | Power-of-two exponent storage (no overflow), K/M/B/T labels, colour palette per value + number on every face |
| Merging | `MergeSystem` with Standard / ChainCollection / Hybrid modes, eligibility and smaller-cube options |
| Collectibles | Weighted normalised spawn table, progression shift, rejection sampling, spatial hash, object pooling, drops |
| AI | One configurable AI (`AITargetSelector`) with Easy/Normal/Hard presets: value/distance/risk scoring, flee, chase with lead, wander, obstacle & boundary avoidance, reaction time, deliberate mistakes |
| Combat | `CombatResolver` (head-vs-head/body rules, sever, ties, spawn protection) + per-frame dedupe |
| Camera | Elevated follow camera with look-ahead, chain-length zoom, portrait/landscape compensation, optional heading-follow, shake |
| UI | Main menu, mode/difficulty select, settings, how to play, high score & stats, customize (skins/trails), privacy & credits, HUD (lead cube, chain, score, best, rank, mini leaderboard, boost ring, pause, minimap, danger/edge warnings, rival name tags, floating score), pause, game over |
| Audio | Synthesised collect / merge (5 tiers, higher = more distinctive) / boost / defeat / click / high-score / warning, looping music, separate volumes + mute |
| Save | JSON in `persistentDataPath` with atomic write + backup + sanitising; settings in PlayerPrefs |
| Progression | Best score, highest number, totals, per-mode bests, coins, 5 skins + 4 trails unlocked by play (no purchases) |
| Performance | Pooling everywhere, spatial hashes, shared instanced materials, blob shadows instead of shadow maps, change-only UI updates, idle-animation LOD, low-effects mode, 30/60 FPS setting |

## Project structure

```
Assets/
  Resources/Shaders/        Pipeline-agnostic shaders (work in URP and built-in): Cube, Ground, Unlit, Transparent, Additive
  Scripts/
    Logic/                  Engine-independent rules (no UnityEngine): numbers, merging, chain model, path trail,
                            spatial hash, RNG/weighted table, combat, AI decisions, boost, modes, save data
    Core/                   GameBootstrap, SceneFlow, Services, GameManager, GameStateManager, GameConfig,
                            GameEvents, GameSession, SettingsManager, ArenaContext
    Chain/                  CubeChainController, ChainSegment, ChainFollower
    Collectibles/           CollectibleCube, CollectibleSpawner, ObjectPool
    Player/                 PlayerController, PlayerInputController, KeyboardInput, PlayerStats
    AI/                     AIController
    Combat/                 CollisionManager
    Camera/                 FollowCameraController
    World/                  ArenaBuilder
    Visual/                 MeshFactory, TextureFactory, GlyphFont, CubePalette, CubeMaterials, EffectsManager
    UI/                     UIFactory, UIComponents (joystick, hold button, safe area…), HUDController,
                            MainMenuController, PauseMenuController, GameOverController, SharedPanels
    Audio/                  AudioManager, ProceduralAudio
    Save/                   SaveManager
    Editor/                 ProjectSetup (auto setup), BuildScript (APK/AAB)
  Tests/EditMode/           NUnit tests (run in Unity Test Runner and via dotnet)
docs/                       GAME_RULES.md, TESTING.md
tools/LogicTests/           dotnet test project for the logic + tests (no Unity needed)
tools/generate_meta.py      Creates deterministic .meta files for new assets
```

Assemblies: `CubeSnake.Logic` (no engine references) ← `CubeSnake.Runtime` ← `CubeSnake.Editor`;
`CubeSnake.Tests.EditMode` references only the logic assembly.

**Scenes and prefabs.** `Assets/Scenes/MainMenu.unity` and `GameArena.unity` are created automatically by
`Cube Clash → Setup Project` (it also runs on first open). They are intentionally empty: `GameBootstrap` builds the
menu or the arena at runtime from the scene name. Pooled game objects (chain cubes, collectibles, UI elements) are
built by factory methods (`ChainSegment.Create`, `CollectibleCube.Create`, `UIFactory`) instead of prefabs, so a
fresh clone has no broken references and all tuning lives in `GameConfig`.

Communication uses `GameEvents` (collect, merge, score, defeat, sever, chain changed, boost, state).

## Configuration

Select `Assets/Resources/GameConfig.asset` (created by setup) and edit in the Inspector:

* **Rules** — merge mode, eligibility, smaller-cube behaviour, merge interval, max chain length, score factors,
  strength metric, tie rule, sever on body hit, drop fraction, spawn protection.
* **Movement** — base speed, acceleration, deceleration, turn sensitivity, max turn rate, chain gap, follow
  sharpness, cube sizes, speed loss per level.
* **Boost** — energy or cooldown mode and all rates.
* **Spawn** — counts, spacing, attempts, distribution table, progression shift.
* **Arena**, **Camera** (height, distance, FOV, smoothing, look-ahead, zoom), **Effects**, **AI Easy/Normal/Hard**.

Player-facing settings (in game): volumes, mute, joystick / tap steering, turn speed, joystick size, camera mode,
zoom, follow speed, minimap, low effects, 30/60 FPS, reset progress.

## Getting started (editor)

1. Install **Unity 2022.3 LTS** (2022.3.50f1 or newer 2022.3 patch) with **Android Build Support** (OpenJDK,
   Android SDK & NDK tools) via Unity Hub.
2. Open this folder with Unity Hub. Packages resolve automatically (URP 14, Input System 1.7, uGUI, Test Framework).
3. If Unity asks to enable the new Input System backends / restart, accept.
4. `Cube Clash → Setup Project` runs automatically on first open (scenes, Build Settings, GameConfig, URP asset,
   Android player settings, input handling = Both). Run it again any time from the menu.
5. Open `Assets/Scenes/MainMenu.unity` and press **Play**. Keyboard in the editor: WASD/arrows steer, Space/Shift
   boost, Esc pauses. Use the Game view's device simulator to try touch controls.

## Tests

```bash
dotnet test tools/LogicTests
```

or in Unity: **Window → General → Test Runner → EditMode → Run All**. See [`docs/TESTING.md`](docs/TESTING.md)
for the suites and the manual Android checklist.

## Building for Android

Package id `com.oxdh.cubeclash`, min API 24 (Android 7.0), target API = highest installed, IL2CPP,
ARM64 + ARMv7, no extra permissions (internet permission is not forced; the game is fully offline).

**From the editor:** `Cube Clash → Build → Android Debug APK | Android Release APK | Android Release AAB`.
Outputs go to `Builds/Android/`.

**From the command line** (replace the Unity path for your OS/version):

```bash
UNITY="/Applications/Unity/Hub/Editor/2022.3.50f1/Unity.app/Contents/MacOS/Unity"
```

```bash
"$UNITY" -batchmode -quit -projectPath . -buildTarget Android -logFile build-debug.log -executeMethod CubeSnake.EditorTools.BuildScript.BuildDebugApk
```

```bash
"$UNITY" -batchmode -quit -projectPath . -buildTarget Android -logFile build-release.log -executeMethod CubeSnake.EditorTools.BuildScript.BuildReleaseApk
```

```bash
"$UNITY" -batchmode -quit -projectPath . -buildTarget Android -logFile build-aab.log -executeMethod CubeSnake.EditorTools.BuildScript.BuildReleaseAab
```

Unity EditMode tests from the command line:

```bash
"$UNITY" -batchmode -projectPath . -runTests -testPlatform EditMode -testResults test-results.xml
```

**Release signing.** Create an upload keystore once (keep it and its passwords safe and out of git):

```bash
keytool -genkeypair -v -keystore cubeclash-upload.keystore -alias cubeclash -keyalg RSA -keysize 2048 -validity 10000
```

Then export before building release APK/AAB:

```bash
export CUBECLASH_KEYSTORE_PATH=/secure/path/cubeclash-upload.keystore CUBECLASH_KEYSTORE_PASS='...' CUBECLASH_KEY_ALIAS=cubeclash CUBECLASH_KEY_PASS='...'
```

Optional: `CUBECLASH_VERSION_NAME=1.0.1`, `CUBECLASH_VERSION_CODE=2`, `CUBECLASH_BUILD_DIR=out`.
Without the keystore variables, release builds are signed with the debug keystore (fine for testing, not
accepted by Google Play) and the build log says so.

**Install on a device:**

```bash
adb install -r Builds/Android/CubeClash-1.0.0-debug.apk
```

**CI.** `.github/workflows/logic-tests.yml` runs the logic tests on every push. `.github/workflows/unity-android.yml`
is a manually triggered GameCI workflow (EditMode tests + Android build) that needs `UNITY_LICENSE`,
`UNITY_EMAIL`, `UNITY_PASSWORD` (and optional keystore) repository secrets.

## Project status

What has been verified so far:

* `dotnet test tools/LogicTests`: **73/73 tests pass** (merging, scoring, path following, spatial queries,
  combat and same-frame dedupe, AI, saves, and a four-minute multi-chain soak test at each difficulty).
* All runtime and editor C# compiles without errors against UnityEngine/UnityEditor reference assemblies
  (UnityEngine 2021.3 module set, UnityEditor 2021.1, uGUI).

What has **not** been verified yet:

* The project has **not been opened in the Unity editor, played, or built** to an APK/AAB, and has not run on a
  device. Shaders, scene generation, URP auto-setup and the Input System code paths have not been compiled by Unity.
  Expect the first editor open to surface small fixes. Run through `docs/TESTING.md` before release.

## Known limitations

* The URP pipeline asset is created by reflection on URP's internal factory method. If that fails on a given URP
  version, the setup falls back to the built-in pipeline (the game's shaders work in both) and logs a warning.
  `Cube Clash → Rendering → Use Built-in Pipeline` switches manually.
* Pooled objects are built in code rather than from prefab assets (by design, see above).
* Music is a single procedurally generated loop.
* Rival AI does not use pathfinding; it steers around pillars locally.
* Haptics are not included (they would need the VIBRATE permission).

## Privacy

Fully offline: no account, no ads, no analytics, no network access, no personal data. Progress and settings are
stored only on the device.
