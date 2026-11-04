using System;
using System.Collections.Generic;
using CubeSnake.Chain;
using CubeSnake.Core;
using CubeSnake.Logic;
using CubeSnake.Player;
using CubeSnake.Visual;
using UnityEngine;
using UnityEngine.UI;

namespace CubeSnake.UI
{
    /// <summary>
    /// In-game HUD: leading cube value, chain length, score, highest value, rank + mini leaderboard, boost meter,
    /// pause button, minimap, danger / boundary warnings, rival name tags and floating score popups.
    /// Text is only rewritten when the underlying value changes (no per-frame string allocations).
    /// </summary>
    public sealed class HUDController : MonoBehaviour
    {
        ArenaContext _ctx;
        CubeChainController _player;
        Camera _camera;
        Action _onPause;

        public Canvas Canvas { get; private set; }
        public PlayerInputController Input { get; private set; }
        RectTransform _safe;
        Text _score, _rank, _chainInfo, _danger, _edge, _leaderboard;
        Image _leadIcon, _boostFill, _boostButtonImage;
        GameObject _joystickRoot;
        MinimapController _minimap;
        FloatingTextLayer _floating;
        RivalLabelLayer _labels;

        long _shownScore = -1;
        int _shownRank = -1, _shownCount = -1, _shownLead = -1, _shownLen = -1, _shownHigh = -1;
        float _leaderboardTimer;
        readonly List<CubeChainController> _sorted = new List<CubeChainController>(16);
        readonly List<long> _scores = new List<long>(16);
        readonly System.Text.StringBuilder _sb = new System.Text.StringBuilder(256);
        float _dangerPulse;
        float _lastWarningSound;

        public void Initialize(ArenaContext ctx, Camera camera, Action onPause)
        {
            _ctx = ctx;
            _player = ctx.Player;
            _camera = camera;
            _onPause = onPause;
            Build();
            Input.Camera = camera;
            ApplySettings();
        }

        void OnEnable()
        {
            SettingsManager.Changed += ApplySettings;
            GameEvents.ScoreGained += OnScoreGained;
        }

        void OnDisable()
        {
            SettingsManager.Changed -= ApplySettings;
            GameEvents.ScoreGained -= OnScoreGained;
        }

        void Build()
        {
            Canvas = UIFactory.CreateCanvas("HUD", 10, transform);
            var canvasRt = (RectTransform)Canvas.transform;

            // Full-screen steer area behind everything (tap-to-steer scheme).
            var steer = UIFactory.Panel(canvasRt, new Color(0, 0, 0, 0), 0, "SteerArea");
            UIFactory.Stretch(steer.rectTransform);
            var steerArea = steer.gameObject.AddComponent<SteerArea>();

            _safe = UIFactory.SafeArea(canvasRt);

            // Joystick zone (lower-left).
            var zone = UIFactory.Panel(_safe, new Color(0, 0, 0, 0), 0, "JoystickZone");
            var zrt = zone.rectTransform;
            zrt.anchorMin = new Vector2(0, 0); zrt.anchorMax = new Vector2(0.5f, 0.45f); zrt.offsetMin = zrt.offsetMax = Vector2.zero;
            _joystickRoot = zone.gameObject;
            var baseImg = UIFactory.Panel(zrt, new Color(1, 1, 1, 0.18f), 0, "Base");
            baseImg.sprite = TextureFactory.CircleSprite();
            UIFactory.Place(baseImg.rectTransform, new Vector2(0, 0), new Vector2(320, 320), new Vector2(230, 250));
            baseImg.rectTransform.pivot = new Vector2(0.5f, 0.5f);
            baseImg.raycastTarget = false;
            var ring = UIFactory.Panel(baseImg.transform, new Color(1, 1, 1, 0.35f), 0, "Ring");
            ring.sprite = TextureFactory.RoundedRect(60, 8);
            UIFactory.Stretch(ring.rectTransform);
            ring.type = Image.Type.Sliced;
            ring.raycastTarget = false;
            var knob = UIFactory.Panel(baseImg.transform, new Color(1, 1, 1, 0.85f), 0, "Knob");
            knob.sprite = TextureFactory.CircleSprite();
            UIFactory.Place(knob.rectTransform, new Vector2(0.5f, 0.5f), new Vector2(140, 140), Vector2.zero);
            knob.raycastTarget = false;
            var joystick = zone.gameObject.AddComponent<VirtualJoystick>();
            joystick.Base = baseImg.rectTransform;
            joystick.Knob = knob.rectTransform;
            joystick.Radius = 140f;

            // Boost button (lower-right).
            var boost = UIFactory.Panel(_safe, new Color(0.1f, 0.12f, 0.25f, 0.55f), 0, "Boost");
            boost.sprite = TextureFactory.CircleSprite();
            UIFactory.Place(boost.rectTransform, new Vector2(1, 0), new Vector2(250, 250), new Vector2(-70, 110));
            _boostButtonImage = boost;
            var fill = UIFactory.Panel(boost.transform, UITheme.Accent, 0, "Fill");
            fill.sprite = TextureFactory.CircleSprite();
            fill.type = Image.Type.Filled;
            fill.fillMethod = Image.FillMethod.Radial360;
            fill.fillOrigin = (int)Image.Origin360.Top;
            fill.fillClockwise = false;
            UIFactory.Stretch(fill.rectTransform, 12, 12, 12, 12);
            fill.raycastTarget = false;
            _boostFill = fill;
            var inner = UIFactory.Panel(boost.transform, new Color(0.12f, 0.14f, 0.3f, 0.95f), 0, "Inner");
            inner.sprite = TextureFactory.CircleSprite();
            UIFactory.Stretch(inner.rectTransform, 34, 34, 34, 34);
            inner.raycastTarget = false;
            var boostLabel = UIFactory.Label(boost.transform, "BOOST", 44, Color.white);
            UIFactory.Stretch(boostLabel.rectTransform);
            var hold = boost.gameObject.AddComponent<HoldButton>();

            Input = gameObject.AddComponent<PlayerInputController>();
            Input.Joystick = joystick;
            Input.BoostButton = hold;
            Input.SteerArea = steerArea;

            // Score panel (top-left).
            var scorePanel = UIFactory.Panel(_safe, new Color(0.08f, 0.1f, 0.22f, 0.55f), 28, "ScorePanel");
            UIFactory.Place(scorePanel.rectTransform, new Vector2(0, 1), new Vector2(380, 170), new Vector2(24, -24));
            _score = UIFactory.Label(scorePanel.transform, "0", 64, Color.white, TextAnchor.UpperLeft);
            UIFactory.Stretch(_score.rectTransform, 26, 16, 14, 70);
            _rank = UIFactory.Label(scorePanel.transform, "#1", 40, UITheme.Accent, TextAnchor.LowerLeft);
            UIFactory.Stretch(_rank.rectTransform, 26, 16, 90, 16);

            // Mini leaderboard (left, below score).
            var lb = UIFactory.Panel(_safe, new Color(0.08f, 0.1f, 0.22f, 0.4f), 24, "Leaderboard");
            UIFactory.Place(lb.rectTransform, new Vector2(0, 1), new Vector2(380, 260), new Vector2(24, -210));
            _leaderboard = UIFactory.Label(lb.transform, "", 32, Color.white, TextAnchor.UpperLeft, false);
            UIFactory.Stretch(_leaderboard.rectTransform, 22, 12, 14, 10);

            // Leading cube (top centre).
            var lead = UIFactory.Rect("Lead", _safe);
            UIFactory.Place(lead, new Vector2(0.5f, 1), new Vector2(420, 260), new Vector2(0, -20));
            var icon = UIFactory.Rect("Icon", lead);
            UIFactory.Place(icon, new Vector2(0.5f, 1), new Vector2(150, 150), Vector2.zero);
            _leadIcon = icon.gameObject.AddComponent<Image>();
            _leadIcon.raycastTarget = false;
            _leadIcon.preserveAspect = true;
            _chainInfo = UIFactory.Label(lead, "", 36, Color.white);
            UIFactory.Place(_chainInfo.rectTransform, new Vector2(0.5f, 1), new Vector2(560, 90), new Vector2(0, -150));

            // Pause button (top-right).
            var pause = UIFactory.Button(_safe, "II", new Color(0.08f, 0.1f, 0.22f, 0.6f), () => _onPause?.Invoke(), 56);
            UIFactory.Place((RectTransform)pause.transform, new Vector2(1, 1), new Vector2(130, 130), new Vector2(-24, -24));

            // Minimap (top-right, below pause).
            var mm = UIFactory.Rect("Minimap", _safe);
            UIFactory.Place(mm, new Vector2(1, 1), new Vector2(300, 300), new Vector2(-24, -180));
            _minimap = mm.gameObject.AddComponent<MinimapController>();
            _minimap.Initialize(_ctx, _camera);

            // Warnings.
            _danger = UIFactory.Label(_safe, "DANGER! BIGGER RIVAL NEARBY", 46, UITheme.Danger);
            UIFactory.Place(_danger.rectTransform, new Vector2(0.5f, 1), new Vector2(900, 80), new Vector2(0, -290));
            _danger.gameObject.SetActive(false);
            _edge = UIFactory.Label(_safe, "Edge of the arena", 40, UITheme.Accent);
            UIFactory.Place(_edge.rectTransform, new Vector2(0.5f, 1), new Vector2(800, 70), new Vector2(0, -360));
            _edge.gameObject.SetActive(false);

            // Layers for world-anchored UI (behind controls but above the world).
            var labels = UIFactory.Rect("RivalLabels", canvasRt);
            UIFactory.Stretch(labels);
            labels.SetSiblingIndex(1);
            _labels = labels.gameObject.AddComponent<RivalLabelLayer>();
            _labels.Initialize(_ctx, _camera);

            var floating = UIFactory.Rect("FloatingText", canvasRt);
            UIFactory.Stretch(floating);
            floating.SetSiblingIndex(2);
            _floating = floating.gameObject.AddComponent<FloatingTextLayer>();
            _floating.Initialize(_camera);
        }

        void ApplySettings()
        {
            if (_joystickRoot == null) return;
            bool joystick = SettingsManager.Controls == ControlScheme.Joystick;
            _joystickRoot.SetActive(joystick);
            _minimap.gameObject.SetActive(SettingsManager.ShowMinimap);
        }

        void OnScoreGained(CubeChainController chain, long points, Vector3 worldPos)
        {
            if (chain != _player || _floating == null || points <= 0) return;
            _floating.Spawn("+" + NumberFormat.Abbreviate(points), worldPos, UITheme.Accent);
        }

        public void ShowMessage(string text, Color color)
        {
            if (_floating != null && _player != null) _floating.Spawn(text, ArenaContext.ToWorld(_player.HeadPosition, 2.5f), color, 1.6f, 64);
        }

        void Update()
        {
            if (_player == null) return;
            var model = _player.Model;

            long score = _player.Stats.Score;
            if (score != _shownScore) { _shownScore = score; _score.text = NumberFormat.FormatScore(score); }

            int lead = model.HeadLevel, len = model.Length, high = _player.Stats.HighestLevel;
            if (lead != _shownLead)
            {
                _shownLead = lead;
                _leadIcon.sprite = TextureFactory.CubeFaceSprite(_player.Skin, lead);
            }
            if (len != _shownLen || high != _shownHigh)
            {
                _shownLen = len; _shownHigh = high;
                _chainInfo.text = "Chain " + len + "   •   Best " + NumberFormat.FormatLevel(high);
            }

            _boostFill.fillAmount = _player.Boost.Display01;
            _boostFill.color = _player.Boost.IsBoosting ? Color.white : (_player.Boost.Ready ? UITheme.Accent : new Color(0.6f, 0.6f, 0.7f));

            _leaderboardTimer -= Time.unscaledDeltaTime;
            if (_leaderboardTimer <= 0f)
            {
                _leaderboardTimer = 0.4f;
                UpdateRanking();
            }
            UpdateWarnings();
        }

        void UpdateRanking()
        {
            _sorted.Clear();
            _scores.Clear();
            int playerIndex = 0;
            foreach (var c in _ctx.Chains)
            {
                if (c == _player) playerIndex = _scores.Count;
                if (!c.Alive && c != _player) continue;
                _sorted.Add(c);
                _scores.Add(c.Stats.Score);
            }
            // Recompute player index among the filtered list.
            playerIndex = _sorted.IndexOf(_player);
            int rank = RankCalculator.RankOf(_scores, playerIndex);
            if (rank != _shownRank || _sorted.Count != _shownCount)
            {
                _shownRank = rank; _shownCount = _sorted.Count;
                _rank.text = "Rank #" + rank + " / " + _sorted.Count;
            }
            _sorted.Sort((a, b) => b.Stats.Score.CompareTo(a.Stats.Score));
            _sb.Length = 0;
            int shown = Mathf.Min(5, _sorted.Count);
            bool playerShown = false;
            for (int i = 0; i < shown; i++)
            {
                var c = _sorted[i];
                bool me = c == _player;
                playerShown |= me;
                AppendEntry(i + 1, c, me);
            }
            if (!playerShown && playerIndex >= 0) AppendEntry(rank, _player, true);
            _leaderboard.text = _sb.ToString();
        }

        void AppendEntry(int rank, CubeChainController c, bool me)
        {
            if (me) _sb.Append("<color=#FFC23D>");
            _sb.Append(rank).Append(". ").Append(c.DisplayName).Append("  ").Append(NumberFormat.Abbreviate(c.Stats.Score));
            if (me) _sb.Append("</color>");
            _sb.Append('\n');
        }

        void UpdateWarnings()
        {
            bool danger = false;
            if (_player.Alive && _player.CanBeDefeated)
            {
                var me = _player.Snapshot();
                foreach (var c in _ctx.Chains)
                {
                    if (c == _player || !c.Alive || !c.CanDefeatOthers) continue;
                    if (Vec2.DistanceSquared(c.HeadPosition, _player.HeadPosition) > 12f * 12f) continue;
                    if (CombatResolver.CompareStrength(c.Snapshot(), me, _ctx.Rules.strengthMetric) > 0) { danger = true; break; }
                }
            }
            if (danger != _danger.gameObject.activeSelf)
            {
                _danger.gameObject.SetActive(danger);
                if (danger && Time.unscaledTime - _lastWarningSound > 3f)
                {
                    _lastWarningSound = Time.unscaledTime;
                    Services.Audio?.Play(Audio.Sfx.Warning, 1f, 0.6f);
                }
            }
            if (danger)
            {
                _dangerPulse += Time.unscaledDeltaTime * 6f;
                float a = 0.65f + 0.35f * Mathf.Sin(_dangerPulse);
                _danger.color = new Color(UITheme.Danger.r, UITheme.Danger.g, UITheme.Danger.b, a);
            }
            bool nearEdge = _player.Alive && _player.HeadPosition.Length > _ctx.ArenaRadius - _ctx.Config.arena.boundaryWarningDistance;
            if (nearEdge != _edge.gameObject.activeSelf) _edge.gameObject.SetActive(nearEdge);
        }

        public void SetVisible(bool visible)
        {
            if (Canvas != null) Canvas.enabled = visible;
        }
    }

    /// <summary>Radar-style minimap centred on the player: eligible / blocked cubes, rivals (red = stronger), arena edge.</summary>
    public sealed class MinimapController : MonoBehaviour
    {
        const float Range = 48f;
        const int MaxCubeDots = 70;

        ArenaContext _ctx;
        Camera _camera;
        RectTransform _dotsRoot;
        RectTransform _edgeMarker;
        readonly List<Image> _cubeDots = new List<Image>();
        readonly List<Image> _rivalDots = new List<Image>();
        readonly List<Collectibles.CollectibleCube> _query = new List<Collectibles.CollectibleCube>(128);
        float _timer;
        float _radiusPx;

        public void Initialize(ArenaContext ctx, Camera camera)
        {
            _ctx = ctx;
            _camera = camera;
            var bg = gameObject.AddComponent<Image>();
            bg.sprite = TextureFactory.CircleSprite();
            bg.color = new Color(0.06f, 0.08f, 0.2f, 0.55f);
            bg.raycastTarget = false;
            var rim = UIFactory.Panel(transform, new Color(1, 1, 1, 0.3f), 0, "Rim");
            rim.sprite = TextureFactory.RoundedRect(60, 6);
            rim.type = Image.Type.Sliced;
            UIFactory.Stretch(rim.rectTransform);
            rim.raycastTarget = false;
            _dotsRoot = UIFactory.Stretch(UIFactory.Rect("Dots", transform));
            var edge = UIFactory.Panel(_dotsRoot, new Color(1f, 0.4f, 0.45f, 0.9f), 0, "Edge");
            edge.sprite = TextureFactory.CircleSprite();
            edge.rectTransform.sizeDelta = new Vector2(16, 16);
            edge.raycastTarget = false;
            _edgeMarker = edge.rectTransform;
            var me = UIFactory.Panel(transform, Color.white, 0, "Me");
            me.sprite = TextureFactory.CircleSprite();
            UIFactory.Place(me.rectTransform, new Vector2(0.5f, 0.5f), new Vector2(22, 22), Vector2.zero);
            me.raycastTarget = false;
        }

        Image Dot(List<Image> list, int index, float size)
        {
            while (list.Count <= index)
            {
                var img = UIFactory.Panel(_dotsRoot, Color.white, 0, "Dot");
                img.sprite = TextureFactory.CircleSprite();
                img.raycastTarget = false;
                img.rectTransform.anchorMin = img.rectTransform.anchorMax = new Vector2(0.5f, 0.5f);
                list.Add(img);
            }
            var d = list[index];
            d.rectTransform.sizeDelta = new Vector2(size, size);
            if (!d.gameObject.activeSelf) d.gameObject.SetActive(true);
            return d;
        }

        void Update()
        {
            if (_ctx?.Player == null) return;
            _timer -= Time.unscaledDeltaTime;
            if (_timer > 0f) return;
            _timer = 0.1f;
            _radiusPx = ((RectTransform)transform).rect.width * 0.5f - 10f;
            var player = _ctx.Player;
            Vec2 center = player.HeadPosition;
            float yaw = _camera != null ? _camera.transform.eulerAngles.y * Mathf.Deg2Rad : 0f;

            _query.Clear();
            _ctx.Spawner.Query(center, Range, _query);
            int n = 0;
            for (int i = 0; i < _query.Count && n < MaxCubeDots; i++)
            {
                var c = _query[i];
                var dot = Dot(_cubeDots, n++, c.Dimmed ? 8 : 11);
                dot.rectTransform.anchoredPosition = ToMap(c.PlanePosition - center, yaw);
                var col = CubePalette.ColorFor("classic", c.Level);
                dot.color = c.Dimmed ? new Color(0.6f, 0.6f, 0.7f, 0.45f) : col;
            }
            for (int i = n; i < _cubeDots.Count; i++) if (_cubeDots[i].gameObject.activeSelf) _cubeDots[i].gameObject.SetActive(false);

            int r = 0;
            var me = player.Snapshot();
            foreach (var chain in _ctx.Chains)
            {
                if (chain == player || !chain.Alive) continue;
                Vec2 rel = chain.HeadPosition - center;
                bool inRange = rel.Length < Range;
                // Rivals outside the radar are pinned to the rim as directional indicators.
                if (!inRange) rel = rel.Normalized * Range * 0.98f;
                var dot = Dot(_rivalDots, r++, inRange ? 20 : 14);
                dot.rectTransform.anchoredPosition = ToMap(rel, yaw);
                int cmp = CombatResolver.CompareStrength(chain.Snapshot(), me, _ctx.Rules.strengthMetric);
                dot.color = cmp > 0 ? UITheme.Danger : (cmp < 0 ? UITheme.Good : UITheme.Accent);
                if (!inRange) dot.color = new Color(dot.color.r, dot.color.g, dot.color.b, 0.6f);
            }
            for (int i = r; i < _rivalDots.Count; i++) if (_rivalDots[i].gameObject.activeSelf) _rivalDots[i].gameObject.SetActive(false);

            // Arena edge indicator: closest boundary point when the edge is within range.
            float distToEdge = _ctx.ArenaRadius - center.Length;
            bool showEdge = distToEdge < Range;
            _edgeMarker.gameObject.SetActive(showEdge);
            if (showEdge) _edgeMarker.anchoredPosition = ToMap(center.Normalized * distToEdge, yaw);
        }

        Vector2 ToMap(Vec2 rel, float yaw)
        {
            // Rotate into camera space so "up" on the map is "up" on screen.
            float cos = Mathf.Cos(yaw), sin = Mathf.Sin(yaw);
            float x = rel.X * cos - rel.Y * sin;
            float y = rel.X * sin + rel.Y * cos;
            float k = _radiusPx / Range;
            return new Vector2(x * k, y * k);
        }
    }

    /// <summary>Pooled "+score" popups that rise and fade above the world position where points were earned.</summary>
    public sealed class FloatingTextLayer : MonoBehaviour
    {
        sealed class Entry
        {
            public Text Text;
            public Vector3 World;
            public float Age, Life;
            public Color Color;
        }

        Camera _camera;
        readonly List<Entry> _entries = new List<Entry>();
        RectTransform _rt;

        public void Initialize(Camera camera)
        {
            _camera = camera;
            _rt = (RectTransform)transform;
        }

        public void Spawn(string text, Vector3 world, Color color, float life = 0.9f, int size = 46)
        {
            Entry e = null;
            foreach (var x in _entries) if (x.Age >= x.Life) { e = x; break; }
            if (e == null)
            {
                if (_entries.Count >= 24) return;
                e = new Entry { Text = UIFactory.Label(transform, "", size, color) };
                e.Text.rectTransform.sizeDelta = new Vector2(500, 90);
                e.Text.horizontalOverflow = HorizontalWrapMode.Overflow;
                _entries.Add(e);
            }
            e.Text.text = text;
            e.Text.fontSize = size;
            e.World = world;
            e.Age = 0f;
            e.Life = life;
            e.Color = color;
            e.Text.gameObject.SetActive(true);
            Position(e);
        }

        void Position(Entry e)
        {
            if (_camera == null) return;
            Vector3 sp = _camera.WorldToScreenPoint(e.World + Vector3.up * (e.Age * 2.2f));
            if (sp.z < 0) { e.Text.gameObject.SetActive(false); return; }
            if (RectTransformUtility.ScreenPointToLocalPointInRectangle(_rt, sp, null, out var local)) e.Text.rectTransform.anchoredPosition = local;
        }

        void Update()
        {
            float dt = Time.unscaledDeltaTime;
            foreach (var e in _entries)
            {
                if (e.Age >= e.Life) continue;
                e.Age += dt;
                if (e.Age >= e.Life) { e.Text.gameObject.SetActive(false); continue; }
                float t = e.Age / e.Life;
                e.Text.color = new Color(e.Color.r, e.Color.g, e.Color.b, 1f - t * t);
                float s = 1f + 0.25f * Mathf.Sin(Mathf.Min(1f, t * 4f) * Mathf.PI);
                e.Text.rectTransform.localScale = new Vector3(s, s, 1f);
                Position(e);
            }
        }
    }

    /// <summary>Name + leading value tags above rival heads; red when the rival is stronger than the player.</summary>
    public sealed class RivalLabelLayer : MonoBehaviour
    {
        ArenaContext _ctx;
        Camera _camera;
        RectTransform _rt;
        readonly Dictionary<CubeChainController, Text> _labels = new Dictionary<CubeChainController, Text>();
        readonly Dictionary<CubeChainController, int> _shownLevel = new Dictionary<CubeChainController, int>();

        public void Initialize(ArenaContext ctx, Camera camera)
        {
            _ctx = ctx;
            _camera = camera;
            _rt = (RectTransform)transform;
        }

        void LateUpdate()
        {
            if (_ctx == null || _camera == null || _ctx.Player == null) return;
            var me = _ctx.Player.Snapshot();
            foreach (var chain in _ctx.Chains)
            {
                if (chain.IsPlayer) continue;
                if (!_labels.TryGetValue(chain, out var label))
                {
                    label = UIFactory.Label(transform, "", 34, Color.white);
                    label.rectTransform.sizeDelta = new Vector2(420, 60);
                    label.horizontalOverflow = HorizontalWrapMode.Overflow;
                    _labels[chain] = label;
                    _shownLevel[chain] = -1;
                }
                bool visible = chain.Alive && chain.Segments.Count > 0;
                Vector3 sp = Vector3.zero;
                if (visible)
                {
                    var head = chain.Segments[0].transform.position;
                    sp = _camera.WorldToScreenPoint(head + Vector3.up * (chain.Segments[0].CurrentSize + 1.2f));
                    visible = sp.z > 0 && sp.x > -50 && sp.x < Screen.width + 50 && sp.y > -50 && sp.y < Screen.height + 50;
                }
                if (label.gameObject.activeSelf != visible) label.gameObject.SetActive(visible);
                if (!visible) continue;
                if (RectTransformUtility.ScreenPointToLocalPointInRectangle(_rt, sp, null, out var local)) label.rectTransform.anchoredPosition = local;
                if (_shownLevel[chain] != chain.HeadLevel)
                {
                    _shownLevel[chain] = chain.HeadLevel;
                    label.text = chain.DisplayName + "  " + NumberFormat.FormatLevel(chain.HeadLevel);
                }
                int cmp = CombatResolver.CompareStrength(chain.Snapshot(), me, _ctx.Rules.strengthMetric);
                label.color = cmp > 0 ? new Color(1f, 0.55f, 0.6f) : (cmp < 0 ? new Color(0.6f, 1f, 0.7f) : Color.white);
            }
        }
    }
}
