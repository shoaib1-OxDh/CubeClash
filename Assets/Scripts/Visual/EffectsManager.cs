using CubeSnake.CameraSystem;
using CubeSnake.Chain;
using CubeSnake.Core;
using UnityEngine;

namespace CubeSnake.Visual
{
    /// <summary>
    /// Particle feedback for collection, merges, defeats and boosts, plus camera shake for major events.
    /// Uses a few shared particle systems with Emit() (no per-effect objects). Low-effects mode halves particles.
    /// </summary>
    public sealed class EffectsManager : MonoBehaviour
    {
        ParticleSystem _sparks;
        ParticleSystem _shards;
        EffectsConfig _cfg;
        FollowCameraController _camera;
        string _playerSkin = "classic";

        public void Initialize(EffectsConfig cfg, FollowCameraController camera, string playerSkin)
        {
            _cfg = cfg;
            _camera = camera;
            _playerSkin = playerSkin;
            _sparks = CreateSystem("Sparks", CubeMaterials.Additive, 0.45f, new Vector2(3f, 9f), new Vector2(0.25f, 0.6f), 0.2f, 600);
            _shards = CreateSystem("Shards", CubeMaterials.Transparent, 0.9f, new Vector2(4f, 11f), new Vector2(0.18f, 0.42f), 1.6f, 400);
        }

        ParticleSystem CreateSystem(string name, Material mat, float lifetime, Vector2 speed, Vector2 size, float gravity, int max)
        {
            var go = new GameObject(name);
            go.transform.SetParent(transform, false);
            var ps = go.AddComponent<ParticleSystem>();
            ps.Stop(true, ParticleSystemStopBehavior.StopEmittingAndClear);
            var main = ps.main;
            main.loop = false;
            main.playOnAwake = false;
            main.duration = 1f;
            main.startLifetime = new ParticleSystem.MinMaxCurve(lifetime * 0.6f, lifetime);
            main.startSpeed = new ParticleSystem.MinMaxCurve(speed.x, speed.y);
            main.startSize = new ParticleSystem.MinMaxCurve(size.x, size.y);
            main.gravityModifier = gravity;
            main.simulationSpace = ParticleSystemSimulationSpace.World;
            main.maxParticles = max;
            main.scalingMode = ParticleSystemScalingMode.Shape;
            var emission = ps.emission;
            emission.enabled = false;
            var shape = ps.shape;
            shape.enabled = true;
            shape.shapeType = ParticleSystemShapeType.Hemisphere;
            shape.radius = 0.4f;
            shape.rotation = new Vector3(-90f, 0f, 0f);
            var sol = ps.sizeOverLifetime;
            sol.enabled = true;
            sol.size = new ParticleSystem.MinMaxCurve(1f, AnimationCurve.EaseInOut(0f, 1f, 1f, 0f));
            var rot = ps.rotationOverLifetime;
            rot.enabled = true;
            rot.z = new ParticleSystem.MinMaxCurve(-4f, 4f);
            var r = go.GetComponent<ParticleSystemRenderer>();
            r.sharedMaterial = mat;
            r.renderMode = ParticleSystemRenderMode.Billboard;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            r.receiveShadows = false;
            ps.Play();
            return ps;
        }

        void OnEnable()
        {
            GameEvents.CubeCollected += OnCollected;
            GameEvents.CubesMerged += OnMerged;
            GameEvents.BoostStarted += OnBoost;
        }

        void OnDisable()
        {
            GameEvents.CubeCollected -= OnCollected;
            GameEvents.CubesMerged -= OnMerged;
            GameEvents.BoostStarted -= OnBoost;
        }

        int Scaled(int n) => SettingsManager.LowEffects ? Mathf.Max(1, n / 2) : n;

        bool Visible(Vector3 p)
        {
            if (_camera == null || _camera.Camera == null) return true;
            Vector3 v = _camera.Camera.WorldToViewportPoint(p);
            return v.z > 0f && v.x > -0.2f && v.x < 1.2f && v.y > -0.2f && v.y < 1.2f;
        }

        public void Burst(Vector3 position, Color color, int count, bool shards = false)
        {
            if (_sparks == null || count <= 0 || !Visible(position)) return;
            var ps = shards ? _shards : _sparks;
            var p = new ParticleSystem.EmitParams
            {
                position = position,
                applyShapeToPosition = true,
                startColor = color,
            };
            ps.Emit(p, Scaled(count));
        }

        void OnCollected(CubeChainController chain, int level, Vector3 pos)
        {
            Burst(pos + Vector3.up * 0.4f, CubePalette.ColorFor(chain.Skin, level), _cfg.particlesPerCollect);
        }

        void OnMerged(CubeChainController chain, int level, Vector3 pos)
        {
            var c = CubePalette.ColorFor(chain.Skin, level);
            int count = _cfg.particlesPerMerge + Mathf.Min(30, level * 2);
            Burst(pos + Vector3.up * 0.6f, Color.Lerp(c, Color.white, 0.35f), count);
            if (level >= 6) Burst(pos + Vector3.up * 0.6f, c, count / 2, true);
            if (chain.IsPlayer && _camera != null && level >= 5) _camera.AddShake(Mathf.Clamp(0.05f * (level - 4), 0.05f, 0.4f));
        }

        void OnBoost(CubeChainController chain)
        {
            if (chain.Segments.Count == 0) return;
            Burst(chain.Segments[0].Position + Vector3.up * 0.3f, CubePalette.TrailColor(chain.TrailId, 0.2f), 6);
        }

        public void DefeatBurst(CubeChainController chain, Vector3 position)
        {
            Burst(position + Vector3.up * 0.6f, Color.white, _cfg.particlesPerDefeat);
            Burst(position + Vector3.up * 0.6f, CubePalette.ColorFor(chain.Skin, chain.HeadLevel), _cfg.particlesPerDefeat, true);
            if (_camera != null && (chain.IsPlayer || Vector3.Distance(position, _camera.transform.position) < 40f)) _camera.AddShake(chain.IsPlayer ? 0.7f : 0.25f);
        }
    }
}
