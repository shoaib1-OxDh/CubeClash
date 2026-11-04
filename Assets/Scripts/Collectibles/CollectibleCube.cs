using CubeSnake.Logic;
using CubeSnake.Visual;
using UnityEngine;

namespace CubeSnake.Collectibles
{
    /// <summary>A numbered cube lying in the arena. Pooled; state changes are driven by <see cref="CollectibleSpawner"/>.</summary>
    public sealed class CollectibleCube : MonoBehaviour
    {
        public enum State { Inactive, Spawning, Idle, BeingCollected }

        public int Level { get; private set; }
        public Vec2 PlanePosition { get; private set; }
        public bool IsDrop { get; private set; }
        public float SpawnTime { get; private set; }
        public State CurrentState { get; private set; }
        public bool Dimmed { get; private set; }
        public float Size { get; private set; } = 1f;
        public float Radius => Size * 0.5f;
        /// <summary>Unique id used by AI target memory.</summary>
        public int Id { get; private set; }
        /// <summary>Configurable score reward multiplier (1 = default value-based score).</summary>
        public float RewardMultiplier { get; set; } = 1f;

        static int s_nextId = 1;
        MeshRenderer _renderer;
        Transform _body, _shadow;
        GameObject _halo;
        MeshRenderer _haloRenderer;
        float _t, _phase, _spin;
        Transform _collectTarget;
        Vector3 _collectStart;
        string _skin = "classic";
        static readonly MaterialPropertyBlock s_block = new MaterialPropertyBlock();

        public static CollectibleCube Create(Transform parent)
        {
            var go = new GameObject("Collectible");
            go.transform.SetParent(parent, false);
            var c = go.AddComponent<CollectibleCube>();
            c.Build();
            return c;
        }

        void Build()
        {
            var body = new GameObject("Body");
            body.transform.SetParent(transform, false);
            body.AddComponent<MeshFilter>().sharedMesh = MeshFactory.RoundedCube;
            _renderer = body.AddComponent<MeshRenderer>();
            _renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _renderer.receiveShadows = false;
            _body = body.transform;

            var shadow = new GameObject("Shadow");
            shadow.transform.SetParent(transform, false);
            shadow.AddComponent<MeshFilter>().sharedMesh = MeshFactory.GroundQuad;
            var sr = shadow.AddComponent<MeshRenderer>();
            sr.sharedMaterial = CubeMaterials.BlobShadow;
            sr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _shadow = shadow.transform;

            _halo = new GameObject("Halo");
            _halo.transform.SetParent(transform, false);
            _halo.AddComponent<MeshFilter>().sharedMesh = MeshFactory.GroundQuad;
            _haloRenderer = _halo.AddComponent<MeshRenderer>();
            _haloRenderer.sharedMaterial = CubeMaterials.Halo;
            _haloRenderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _halo.SetActive(false);
        }

        public void Activate(int level, Vec2 position, float size, bool isDrop, bool dimmed, bool lowEffects)
        {
            Id = s_nextId++;
            Level = level;
            PlanePosition = position;
            IsDrop = isDrop;
            Size = size;
            SpawnTime = Time.time;
            CurrentState = State.Spawning;
            _t = 0f;
            _phase = Random.value * Mathf.PI * 2f;
            _spin = Random.Range(-25f, 25f);
            _collectTarget = null;
            transform.position = new Vector3(position.X, 0f, position.Y);
            transform.rotation = Quaternion.Euler(0f, Random.Range(0f, 360f), 0f);
            Dimmed = !dimmed; // force refresh
            SetDimmed(dimmed);
            bool glow = level >= CubeMaterials.GlowLevel && !lowEffects;
            _halo.SetActive(glow);
            if (glow)
            {
                var c = CubePalette.ColorFor(_skin, level);
                s_block.Clear();
                s_block.SetColor("_Color", new Color(c.r, c.g, c.b, 0.5f));
                _haloRenderer.SetPropertyBlock(s_block);
            }
            ApplyScale(0.01f);
        }

        public void SetDimmed(bool dimmed)
        {
            if (dimmed == Dimmed) return;
            Dimmed = dimmed;
            _renderer.sharedMaterial = CubeMaterials.Cube(_skin, Level, dimmed);
        }

        public void BeginCollect(Transform target)
        {
            CurrentState = State.BeingCollected;
            _collectTarget = target;
            _collectStart = transform.position;
            _t = 0f;
            _halo.SetActive(false);
        }

        /// <summary>Per-frame animation. Returns true when a collect animation has finished.</summary>
        public bool Tick(float dt, bool animateIdle)
        {
            switch (CurrentState)
            {
                case State.Spawning:
                    _t += dt / 0.3f;
                    float s = Mathf.Clamp01(_t);
                    // Ease-out-back pop.
                    float pop = 1f + 2.2f * Mathf.Pow(s - 1f, 3) + 1.2f * Mathf.Pow(s - 1f, 2);
                    ApplyScale(Mathf.Max(0.01f, pop));
                    if (s >= 1f) { CurrentState = State.Idle; ApplyScale(1f); }
                    return false;
                case State.Idle:
                    if (!animateIdle) return false;
                    _phase += dt * 2.2f;
                    transform.Rotate(0f, _spin * dt, 0f, Space.World);
                    _body.localPosition = new Vector3(0f, Size * 0.5f + 0.06f + Mathf.Sin(_phase) * 0.08f, 0f);
                    return false;
                case State.BeingCollected:
                    _t += dt / 0.16f;
                    float t = Mathf.Clamp01(_t);
                    Vector3 end = _collectTarget != null ? _collectTarget.position : _collectStart;
                    transform.position = Vector3.Lerp(_collectStart, end, t * t);
                    ApplyScale(Mathf.Lerp(1f, 0.2f, t));
                    return t >= 1f;
            }
            return false;
        }

        void ApplyScale(float k)
        {
            float s = Size * k;
            _body.localScale = new Vector3(s, s, s);
            _body.localPosition = new Vector3(0f, s * 0.5f + 0.06f, 0f);
            _shadow.localScale = new Vector3(s * 1.4f, 1f, s * 1.4f);
            _shadow.localPosition = new Vector3(0f, 0.012f, 0f);
            if (_halo.activeSelf)
            {
                _halo.transform.localScale = new Vector3(s * 2.4f, 1f, s * 2.4f);
                _halo.transform.localPosition = new Vector3(0f, 0.02f, 0f);
            }
        }

        public void Deactivate()
        {
            CurrentState = State.Inactive;
            _collectTarget = null;
        }
    }
}
