using CubeSnake.Visual;
using UnityEngine;

namespace CubeSnake.Chain
{
    /// <summary>
    /// Visual + collision data for one cube of a chain. The authoritative value lives in the chain's ChainModel; this
    /// component mirrors it (CellId, Level) and exposes collision information (Radius) to the CollisionManager.
    /// </summary>
    public sealed class ChainSegment : MonoBehaviour
    {
        static int s_nextRuntimeId = 1;
        static readonly MaterialPropertyBlock s_block = new MaterialPropertyBlock();

        public int RuntimeId { get; private set; }
        public int CellId { get; private set; }
        public int Level { get; private set; } = -1;
        public int Index { get; set; }
        public CubeChainController Chain { get; private set; }
        /// <summary>Collision radius on the ground plane.</summary>
        public float Radius => CurrentSize * 0.5f;
        public float CurrentSize { get; private set; } = 1f;
        public Vector3 Position => transform.position;

        public ChainFollower Follower { get; private set; }

        MeshRenderer _renderer;
        Transform _body;
        Transform _shadow;
        GameObject _halo;
        float _punch;
        float _targetSize = 1f;
        string _skin;

        // Absorb (merge) animation state.
        ChainSegment _absorbTarget;
        Vector3 _absorbStart;
        float _absorbT;
        public bool IsAbsorbing => _absorbTarget != null;

        public static ChainSegment Create(Transform parent)
        {
            var go = new GameObject("ChainCube");
            go.transform.SetParent(parent, false);
            var seg = go.AddComponent<ChainSegment>();
            seg.Build();
            return seg;
        }

        void Build()
        {
            RuntimeId = s_nextRuntimeId++;
            Follower = gameObject.AddComponent<ChainFollower>();

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
            sr.receiveShadows = false;
            _shadow = shadow.transform;

            _halo = new GameObject("Halo");
            _halo.transform.SetParent(transform, false);
            _halo.AddComponent<MeshFilter>().sharedMesh = MeshFactory.GroundQuad;
            var hr = _halo.AddComponent<MeshRenderer>();
            hr.sharedMaterial = CubeMaterials.Halo;
            hr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _halo.SetActive(false);
        }

        public void Bind(CubeChainController chain, int cellId, int level, string skin, float size)
        {
            Chain = chain;
            CellId = cellId;
            _skin = skin;
            Level = -1;
            SetLevel(level, false);
            _targetSize = size;
            CurrentSize = size * 0.35f;
            _punch = 0f;
            _absorbTarget = null;
            ApplyScale();
        }

        public void SetLevel(int level, bool punch)
        {
            if (level == Level) return;
            Level = level;
            _renderer.sharedMaterial = CubeMaterials.Cube(_skin, level);
            bool glow = level >= CubeMaterials.GlowLevel && !Core.SettingsManager.LowEffects;
            _halo.SetActive(glow);
            if (glow)
            {
                var c = CubePalette.ColorFor(_skin, level);
                var hr = _halo.GetComponent<MeshRenderer>();
                s_block.Clear();
                s_block.SetColor("_Color", new Color(c.r, c.g, c.b, 0.55f));
                hr.SetPropertyBlock(s_block);
            }
            if (punch) _punch = 1f;
        }

        public void SetTargetSize(float size) => _targetSize = size;

        public void BeginAbsorb(ChainSegment target)
        {
            _absorbTarget = target;
            _absorbStart = transform.position;
            _absorbT = 0f;
        }

        /// <summary>Advances the merge animation; returns true when finished (caller then releases this cube).</summary>
        public bool TickAbsorb(float dt)
        {
            if (_absorbTarget == null) return true;
            _absorbT += dt / 0.14f;
            float t = Mathf.Clamp01(_absorbT);
            transform.position = Vector3.Lerp(_absorbStart, _absorbTarget.transform.position, t * t);
            CurrentSize = Mathf.Lerp(_targetSize, _targetSize * 0.6f, t);
            ApplyScale();
            if (t >= 1f)
            {
                _absorbTarget = null;
                return true;
            }
            return false;
        }

        public void TickVisual(float dt)
        {
            CurrentSize = Mathf.Lerp(CurrentSize, _targetSize, 1f - Mathf.Exp(-12f * dt));
            if (_punch > 0f) _punch = Mathf.Max(0f, _punch - dt * 4f);
            ApplyScale();
        }

        void ApplyScale()
        {
            float punchScale = 1f + Mathf.Sin(_punch * Mathf.PI) * 0.28f;
            float s = CurrentSize * punchScale;
            _body.localScale = new Vector3(s, s, s);
            _body.localPosition = new Vector3(0f, s * 0.5f + 0.02f, 0f);
            float shadowSize = s * 1.45f;
            _shadow.localScale = new Vector3(shadowSize, 1f, shadowSize);
            _shadow.localPosition = new Vector3(0f, 0.015f, 0f);
            _shadow.rotation = Quaternion.identity;
            if (_halo.activeSelf)
            {
                float hs = s * 2.6f;
                _halo.transform.localScale = new Vector3(hs, 1f, hs);
                _halo.transform.localPosition = new Vector3(0f, 0.03f, 0f);
                _halo.transform.rotation = Quaternion.identity;
            }
        }

        public void Unbind()
        {
            Chain = null;
            _absorbTarget = null;
            CellId = 0;
        }
    }
}
