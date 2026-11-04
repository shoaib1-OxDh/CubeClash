using System.Collections;
using System.Collections.Generic;
using CubeSnake.AI;
using CubeSnake.Audio;
using CubeSnake.CameraSystem;
using CubeSnake.Chain;
using CubeSnake.Collectibles;
using CubeSnake.Combat;
using CubeSnake.Logic;
using CubeSnake.Player;
using CubeSnake.Save;
using CubeSnake.UI;
using CubeSnake.Visual;
using CubeSnake.World;
using UnityEngine;

namespace CubeSnake.Core
{
    /// <summary>
    /// Orchestrates one arena session: builds the world, spawns the player and rivals, runs the fixed per-frame order
    /// (brains → movement → collisions/combat → visuals → spawning), applies combat outcomes, respawns rivals, and
    /// handles pause / game over / restart and saving of personal bests.
    /// </summary>
    public sealed class GameManager : MonoBehaviour
    {
        public static GameManager Current { get; private set; }

        static readonly string[] RivalNames =
        {
            "Blocky", "Cubert", "Quadra", "Hexa", "Pixel", "Tetro", "Voxel", "Brick", "Prism", "Nova", "Bolt", "Zappy",
            "Mochi", "Dicey", "Kubo", "Nimbus", "Sprout", "Rumble", "Echo", "Glint", "Orbit", "Pebble", "Turbo", "Wobble",
        };

        static readonly Color[] TeamColors =
        {
            new Color(1f, 0.45f, 0.45f), new Color(0.45f, 0.85f, 1f), new Color(0.6f, 1f, 0.5f), new Color(1f, 0.8f, 0.35f),
            new Color(0.85f, 0.55f, 1f), new Color(1f, 0.6f, 0.85f), new Color(0.4f, 1f, 0.85f), new Color(1f, 0.65f, 0.3f),
            new Color(0.7f, 0.75f, 1f), new Color(0.9f, 1f, 0.45f), new Color(1f, 0.5f, 0.65f), new Color(0.55f, 0.95f, 0.7f),
        };

        ArenaContext _ctx;
        readonly GameStateManager _state = new GameStateManager();
        CollisionManager _collisions;
        HUDController _hud;
        PauseMenuController _pause;
        GameOverController _gameOver;
        FollowCameraController _camera;
        EffectsManager _effects;
        List<ArenaBuilder.Decoration> _decorations;
        readonly Dictionary<CubeChainController, AIController> _ais = new Dictionary<CubeChainController, AIController>();
        readonly Dictionary<CubeChainController, float> _respawn = new Dictionary<CubeChainController, float>();
        readonly List<CubeChainController> _respawnReady = new List<CubeChainController>();
        AIBehaviourConfig _baseAI;
        float _rampTimer;
        bool _sessionRecorded;
        CubeChainController _player;

        public GameState State => _state.State;
        public ArenaContext Context => _ctx;

        void Awake() => Current = this;

        void OnDestroy()
        {
            if (Current == this) Current = null;
            Time.timeScale = 1f;
        }

        void Start() => Setup();

        void Setup()
        {
            var config = GameConfig.Instance;
            var mode = GameModeSettings.For(GameSession.Mode);
            var progress = SaveManager.Progress;
            ulong seed = GameSession.Seed != 0 ? GameSession.Seed : (ulong)System.DateTime.UtcNow.Ticks;

            _ctx = new ArenaContext
            {
                Config = config,
                Mode = mode,
                Rules = config.rules.Clone(),
                Rng = new Rng(seed),
                ArenaRadius = config.arena.radius,
            };
            _ctx.Rules.Sanitize();
            _ctx.Merge = new MergeSystem(_ctx.Rules);
            CubeMaterials.GlowLevel = config.effects.glowLevel;

            var world = new GameObject("World");
            world.transform.SetParent(transform, false);
            _decorations = ArenaBuilder.Build(world.transform, _ctx);

            var segRoot = new GameObject("ChainCubes").transform;
            segRoot.SetParent(transform, false);
            _ctx.SegmentRoot = segRoot;
            _ctx.SegmentPool = new ObjectPool<ChainSegment>(() => ChainSegment.Create(segRoot), 120);

            // Camera.
            var camGo = new GameObject("Main Camera");
            camGo.transform.SetParent(transform, false);
            camGo.tag = "MainCamera";
            var cam = camGo.AddComponent<UnityEngine.Camera>();
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = config.arena.skyColor;
            cam.nearClipPlane = 0.3f;
            cam.farClipPlane = 420f;
            cam.allowHDR = false;
            cam.allowMSAA = !SettingsManager.LowEffects;
            _camera = camGo.AddComponent<FollowCameraController>();
            _camera.Settings = config.cameraSettings;

            var fxGo = new GameObject("Effects");
            fxGo.transform.SetParent(transform, false);
            _effects = fxGo.AddComponent<EffectsManager>();
            _effects.Initialize(config.effects, _camera, progress.selectedSkin);
            _ctx.Effects = _effects;

            var spawnerGo = new GameObject("Collectibles");
            spawnerGo.transform.SetParent(transform, false);
            _ctx.Spawner = spawnerGo.AddComponent<CollectibleSpawner>();
            _ctx.Spawner.Initialize(_ctx);

            CubeMaterials.Prewarm(progress.selectedSkin, 8);
            CubeMaterials.Prewarm("classic", 8);

            // Player.
            _player = CreateChain(1, "You", true, progress.selectedSkin, progress.selectedTrail, Color.white);
            _player.CanBeDefeated = mode.playerCanBeDefeated;
            _player.Spawn(Vec2.Zero, MathUtil.Pi * 0.5f, null);
            _ctx.Player = _player;

            // Rivals.
            _baseAI = config.AIFor(GameSession.Difficulty).Clone();
            if (!mode.rivalsAggressive) _baseAI = _baseAI.Passive();
            var names = new List<string>(RivalNames);
            for (int i = 0; i < mode.rivalCount; i++)
            {
                int pick = _ctx.Rng.Range(0, names.Count);
                string name = names[pick];
                names.RemoveAt(pick);
                var rival = CreateChain(i + 2, name, false, "classic", "spark", TeamColors[i % TeamColors.Length]);
                rival.CanBeDefeated = true;
                rival.CanDefeatOthers = true;
                var ai = new AIController(rival, _ctx, _baseAI.Clone(), new Rng(seed + (ulong)(i * 7919 + 13)));
                rival.Brain = ai;
                _ais[rival] = ai;
                SpawnRival(rival, true);
            }

            var hudGo = new GameObject("HUD");
            hudGo.transform.SetParent(transform, false);
            _hud = hudGo.AddComponent<HUDController>();
            _hud.Initialize(_ctx, cam, Pause);
            _player.Brain = new PlayerController(_player, _hud.Input);

            _pause = hudGo.AddComponent<PauseMenuController>();
            _pause.Initialize(_hud.Canvas.transform, Resume, Restart, QuitToMenu, () => EndSession(false));
            _gameOver = hudGo.AddComponent<GameOverController>();

            _ctx.Spawner.FillInitial();
            _ctx.Spawner.RefreshEligibility(_player, true);
            _player.HeadLevelChanged += OnPlayerHeadLevelChanged;

            _collisions = new CollisionManager(_ctx);
            _collisions.CombatResolved += OnCombat;

            _camera.Target = _player;
            _camera.Snap();
            _state.Set(GameState.Playing);
            _hud.ShowMessage(mode.displayName, Color.white);
        }

        CubeChainController CreateChain(int id, string name, bool isPlayer, string skin, string trail, Color team)
        {
            var go = new GameObject(isPlayer ? "PlayerChain" : "Rival_" + name);
            go.transform.SetParent(transform, false);
            var chain = go.AddComponent<CubeChainController>();
            chain.Initialize(_ctx, id, name, isPlayer, skin, trail, team);
            _ctx.Chains.Add(chain);
            return chain;
        }

        void OnPlayerHeadLevelChanged(CubeChainController player) => _ctx.Spawner.RefreshEligibility(player);

        // ------------------------------------------------------------------ Rival spawning

        void SpawnRival(CubeChainController rival, bool initial)
        {
            var mode = _ctx.Mode;
            int baseMax = mode.RivalMaxLevelAt(_ctx.ElapsedSeconds);
            int playerLevel = _player != null ? _player.HeadLevel : 0;
            int hi = Mathf.Max(baseMax, playerLevel + 1);
            int lo = Mathf.Max(0, Mathf.Min(baseMax, playerLevel - 3));
            if (initial) { lo = 0; hi = baseMax; }
            int head = _ctx.Rng.Range(lo, hi + 1);
            var levels = new List<int> { head };
            for (int l = head - 1; l >= 0; l--) if (_ctx.Rng.Chance(0.55f)) levels.Add(l); // distinct values: no pending merges

            Vec2 pos = FindRivalSpot(initial);
            rival.Spawn(pos, _ctx.Rng.NextFloat() * MathUtil.TwoPi, levels);
            if (_ais.TryGetValue(rival, out var ai)) ai.ResetBrain();
        }

        Vec2 FindRivalSpot(bool initial)
        {
            float minFromPlayer = initial ? 25f : 38f;
            Vec2 best = Vec2.Zero;
            float bestScore = -1f;
            for (int attempt = 0; attempt < 30; attempt++)
            {
                Vec2 p = _ctx.Rng.InsideDisc(_ctx.ArenaRadius - 10f);
                if (!_ctx.IsFreeSpot(p, 3f)) continue;
                float nearest = float.MaxValue;
                foreach (var c in _ctx.Chains)
                {
                    if (!c.Alive) continue;
                    float d = Vec2.Distance(c.HeadPosition, p) - (c.IsPlayer ? minFromPlayer : 12f);
                    nearest = Mathf.Min(nearest, d);
                }
                if (nearest >= 0f) return p;
                if (nearest > bestScore) { bestScore = nearest; best = p; }
            }
            // Fallback: the opposite side of the arena from the player.
            if (bestScore < 0 && _player != null)
            {
                Vec2 dir = _player.HeadPosition.LengthSquared > 1f ? -_player.HeadPosition.Normalized : Vec2.FromAngle(_ctx.Rng.NextFloat() * MathUtil.TwoPi);
                best = dir * (_ctx.ArenaRadius * 0.7f);
            }
            return best;
        }

        // ------------------------------------------------------------------ Main loop

        void Update()
        {
            if (KeyboardInput.BackPressed())
            {
                if (_state.State == GameState.Playing) Pause();
                else if (_state.State == GameState.Paused) _pause.HandleBack();
                else if (_state.State == GameState.GameOver) QuitToMenu();
            }

            float realDt = Time.unscaledDeltaTime;
            ArenaBuilder.AnimateDecorations(_decorations, realDt, Time.unscaledTime);
            if (_state.State == GameState.Paused || _state.State == GameState.Loading) return;

            float dt = Mathf.Min(Time.deltaTime, 1f / 20f);
            if (dt <= 0f) return;
            bool playing = _state.State == GameState.Playing;
            if (playing)
            {
                _ctx.ElapsedSeconds += dt;
                if (_player.Alive) _player.Stats.AddTime(dt);
            }

            var chains = _ctx.Chains;
            for (int i = 0; i < chains.Count; i++) if (chains[i].Alive) chains[i].Brain?.Think(dt);
            for (int i = 0; i < chains.Count; i++) chains[i].Simulate(dt);
            _collisions.Step();
            for (int i = 0; i < chains.Count; i++) chains[i].UpdateVisuals(dt);
            _ctx.Spawner.Tick(dt, _player.Segments.Count > 0 ? _player.Segments[0].Position : Vector3.zero);

            UpdateRespawns(dt);
            UpdateDifficultyRamp(dt);
        }

        void UpdateRespawns(float dt)
        {
            if (_respawn.Count == 0) return;
            _respawnReady.Clear();
            var keys = new List<CubeChainController>(_respawn.Keys);
            foreach (var chain in keys)
            {
                float t = _respawn[chain] - dt;
                if (t <= 0f) _respawnReady.Add(chain);
                else _respawn[chain] = t;
            }
            foreach (var chain in _respawnReady)
            {
                _respawn.Remove(chain);
                SpawnRival(chain, false);
            }
        }

        void UpdateDifficultyRamp(float dt)
        {
            if (_ctx.Mode.difficultyRampMinutes <= 0f) return;
            _rampTimer -= dt;
            if (_rampTimer > 0f) return;
            _rampTimer = 5f;
            float ramp = _ctx.Mode.RampAt(_ctx.ElapsedSeconds);
            foreach (var kv in _ais) kv.Value.Config = _baseAI.Ramped(ramp);
        }

        // ------------------------------------------------------------------ Combat outcomes

        void OnCombat(ResolvedContact rc)
        {
            var attacker = _ctx.FindChain(rc.Contact.AttackerId);
            var defender = _ctx.FindChain(rc.Contact.DefenderId);
            if (attacker == null || defender == null) return;
            switch (rc.Outcome)
            {
                case CombatOutcome.DefenderDefeated:
                    DefeatChain(defender, attacker);
                    break;
                case CombatOutcome.AttackerDefeated:
                    DefeatChain(attacker, defender);
                    break;
                case CombatOutcome.BothDefeated:
                    DefeatChain(attacker, null);
                    DefeatChain(defender, null);
                    break;
                case CombatOutcome.DefenderSevered:
                    SeverChain(defender, attacker, rc.Contact.DefenderSegmentIndex);
                    break;
                case CombatOutcome.Bounce:
                    Vec2 away = attacker.HeadPosition - defender.HeadPosition;
                    if (away.LengthSquared < 1e-4f) away = -attacker.Forward;
                    attacker.Bounce(away);
                    if (rc.Contact.DefenderSegmentIndex == 0) defender.Bounce(-away);
                    if (attacker.IsPlayer || defender.IsPlayer) Services.Audio?.Play(Sfx.Click, 0.6f, 0.7f);
                    break;
            }
        }

        void SeverChain(CubeChainController victim, CubeChainController attacker, int index)
        {
            if (index <= 0 || index >= victim.Model.Length) return;
            Vector3 where = victim.Segments[index].Position;
            var drops = victim.Sever(index);
            double value = 0;
            foreach (var d in drops) value += NumberValue.ValueOf(d.Level);
            _ctx.Spawner.SpawnDrops(drops, _ctx.Rules.dropFraction);
            long pts = ScoreCalculator.ToScore(value * _ctx.Rules.scoreDefeatValueFraction);
            if (pts > 0)
            {
                attacker.Stats.AddScore(pts);
                GameEvents.RaiseScoreGained(attacker, pts, where + Vector3.up);
            }
            _effects.Burst(where + Vector3.up * 0.5f, Color.white, 24);
            if (victim.IsPlayer || attacker.IsPlayer) Services.Audio?.Play(Sfx.OpponentDefeat, victim.IsPlayer ? 0.8f : 1.2f, 0.7f);
            if (victim.IsPlayer) _hud.ShowMessage("Tail lost!", UITheme.Danger);
            GameEvents.RaiseChainSevered(victim);
        }

        void DefeatChain(CubeChainController victim, CubeChainController killer)
        {
            if (!victim.Alive) return;
            Vector3 pos = victim.Segments.Count > 0 ? victim.Segments[0].Position : ArenaContext.ToWorld(victim.HeadPosition);
            double total = victim.Model.TotalValue;
            _effects.DefeatBurst(victim, pos);
            var drops = victim.Defeat();
            _ctx.Spawner.SpawnDrops(drops, _ctx.Rules.dropFraction);

            if (killer != null && killer.Alive)
            {
                long pts = ScoreCalculator.ForDefeat(total, _ctx.Rules);
                killer.Stats.AddScore(pts);
                killer.Stats.RecordDefeat();
                GameEvents.RaiseScoreGained(killer, pts, pos + Vector3.up * 1.5f);
                if (killer.IsPlayer) _hud.ShowMessage("Defeated " + victim.DisplayName + "!", UITheme.Good);
            }

            if (victim.IsPlayer) Services.Audio?.Play(Sfx.PlayerDefeat);
            else if (killer != null && killer.IsPlayer) Services.Audio?.Play(Sfx.OpponentDefeat);
            GameEvents.RaiseChainDefeated(victim, killer);

            if (victim.IsPlayer) EndSession(true);
            else _respawn[victim] = _ctx.Mode.rivalRespawnDelay;
        }

        // ------------------------------------------------------------------ Flow

        public void Pause()
        {
            if (_state.State != GameState.Playing) return;
            _state.Set(GameState.Paused);
            _pause.Show();
        }

        public void PauseFromSystem() => Pause();

        public void Resume()
        {
            if (_state.State != GameState.Paused) return;
            _pause.Hide();
            _state.Set(GameState.Playing);
        }

        public void Restart()
        {
            RecordSession();
            SceneFlow.LoadGame();
        }

        public void QuitToMenu()
        {
            RecordSession();
            SceneFlow.LoadMenu();
        }

        /// <summary>Folds the session into saved progress exactly once.</summary>
        PersonalBestResult RecordSession()
        {
            if (_sessionRecorded || _player == null) return default;
            _sessionRecorded = true;
            var result = ProgressRules.ApplySession(SaveManager.Progress, _player.Stats, _ctx.Mode.mode);
            SaveManager.Save();
            return result;
        }

        void EndSession(bool defeated)
        {
            if (_state.State == GameState.GameOver) return;
            _pause.Hide();
            _state.Set(GameState.GameOver);
            // Freeze the player's chain behind the summary (ended run or defeat).
            _player.Brain = null;
            _player.DesiredDirection = null;
            _player.WantsBoost = false;
            _player.SpeedMultiplier = 0f;
            _player.CanBeDefeated = false;
            StartCoroutine(GameOverSequence(defeated));
        }

        IEnumerator GameOverSequence(bool defeated)
        {
            var result = RecordSession();
            // Brief slow motion so the defeat reads clearly.
            Time.timeScale = defeated ? 0.35f : 1f;
            yield return new WaitForSecondsRealtime(defeated ? 1.4f : 0.2f);
            Time.timeScale = 1f;
            if (result.NewBestScore || result.NewHighestLevel) Services.Audio?.Play(Sfx.HighScore);
            string title = defeated ? "Game Over" : "Run Complete";
            _gameOver.Show(_hud.Canvas.transform, title, _player.Stats, result, _player.Skin, Restart, QuitToMenu);
        }
    }
}
