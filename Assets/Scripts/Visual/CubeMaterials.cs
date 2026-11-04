using System.Collections.Generic;
using UnityEngine;

namespace CubeSnake.Visual
{
    /// <summary>
    /// Shader lookup and shared material caches. All shaders live in Assets/Resources/Shaders, which guarantees they
    /// are included in player builds, so Shader.Find works on device. Materials are shared per (skin, level, state)
    /// so hundreds of cubes batch/instance together.
    /// </summary>
    public static class CubeMaterials
    {
        static readonly Dictionary<long, Material> s_cubeMats = new Dictionary<long, Material>();
        static readonly Dictionary<long, Texture2D> s_faces = new Dictionary<long, Texture2D>();
        static readonly Dictionary<string, int> s_skinIds = new Dictionary<string, int>();
        static Material s_shadow, s_halo, s_additive, s_transparent;

        public static int GlowLevel = 6;

        public static Shader FindShader(string name)
        {
            var shader = Shader.Find(name);
            if (shader != null) return shader;
            // Last-resort fallbacks so something visible is always rendered.
            shader = Shader.Find("Unlit/Texture") ?? Shader.Find("Universal Render Pipeline/Unlit") ?? Shader.Find("Sprites/Default");
            Debug.LogWarning($"[CubeSnake] Shader '{name}' not found, using fallback '{(shader != null ? shader.name : "none")}'.");
            return shader;
        }

        static int SkinId(string skin)
        {
            skin = string.IsNullOrEmpty(skin) ? "classic" : skin;
            if (!s_skinIds.TryGetValue(skin, out int id))
            {
                id = s_skinIds.Count + 1;
                s_skinIds[skin] = id;
            }
            return id;
        }

        public static Texture2D FaceTexture(string skin, int level)
        {
            long key = ((long)SkinId(skin) << 32) | (uint)level;
            if (s_faces.TryGetValue(key, out var tex) && tex != null) return tex;
            tex = TextureFactory.CubeFace(level, CubePalette.ColorFor(skin, level));
            s_faces[key] = tex;
            return tex;
        }

        /// <summary>Material for a cube of <paramref name="level"/>; <paramref name="dimmed"/> for collectibles the player cannot take yet.</summary>
        public static Material Cube(string skin, int level, bool dimmed = false)
        {
            long key = ((long)SkinId(skin) << 33) | ((long)(dimmed ? 1 : 0) << 32) | (uint)level;
            if (s_cubeMats.TryGetValue(key, out var mat) && mat != null) return mat;
            mat = new Material(FindShader("CubeSnake/Cube")) { name = $"CS_Cube_{skin}_{level}{(dimmed ? "_dim" : "")}", enableInstancing = true };
            mat.mainTexture = FaceTexture(skin, level);
            Color face = CubePalette.ColorFor(skin, level);
            if (dimmed)
            {
                mat.color = new Color(0.62f, 0.62f, 0.68f, 1f);
                mat.SetFloat("_Rim", 0.05f);
            }
            else
            {
                mat.color = Color.white;
                mat.SetFloat("_Rim", 0.22f);
            }
            bool glow = level >= GlowLevel && !dimmed;
            mat.SetFloat("_Emission", glow ? Mathf.Clamp(0.18f + 0.05f * (level - GlowLevel), 0.18f, 0.55f) : 0f);
            mat.SetColor("_EmissionColor", face);
            s_cubeMats[key] = mat;
            return mat;
        }

        public static Material BlobShadow
        {
            get
            {
                if (s_shadow != null) return s_shadow;
                s_shadow = new Material(FindShader("CubeSnake/Transparent")) { name = "CS_BlobShadow", enableInstancing = true };
                s_shadow.mainTexture = TextureFactory.SoftCircle;
                s_shadow.color = new Color(0.1f, 0.12f, 0.25f, 0.38f);
                return s_shadow;
            }
        }

        public static Material Halo
        {
            get
            {
                if (s_halo != null) return s_halo;
                s_halo = new Material(FindShader("CubeSnake/Additive")) { name = "CS_Halo" };
                s_halo.mainTexture = TextureFactory.SoftCircle;
                s_halo.color = new Color(1f, 1f, 1f, 0.55f);
                return s_halo;
            }
        }

        /// <summary>Additive vertex-coloured material for particles and trails.</summary>
        public static Material Additive
        {
            get
            {
                if (s_additive != null) return s_additive;
                s_additive = new Material(FindShader("CubeSnake/Additive")) { name = "CS_Additive" };
                s_additive.mainTexture = TextureFactory.SoftCircle;
                return s_additive;
            }
        }

        /// <summary>Alpha-blended vertex-coloured material (rings, markers, cube-shard particles).</summary>
        public static Material Transparent
        {
            get
            {
                if (s_transparent != null) return s_transparent;
                s_transparent = new Material(FindShader("CubeSnake/Transparent")) { name = "CS_Transparent" };
                s_transparent.mainTexture = TextureFactory.White;
                return s_transparent;
            }
        }

        public static Material Unlit(Color color, Texture tex = null)
        {
            var m = new Material(FindShader("CubeSnake/Unlit")) { name = "CS_Unlit", enableInstancing = true };
            m.mainTexture = tex != null ? tex : TextureFactory.White;
            m.color = color;
            return m;
        }

        public static Material Lit(Color color)
        {
            var m = new Material(FindShader("CubeSnake/Cube")) { name = "CS_Lit", enableInstancing = true };
            m.mainTexture = TextureFactory.White;
            m.color = color;
            m.SetFloat("_Rim", 0.15f);
            return m;
        }

        /// <summary>Pre-generates the most common face textures during loading to avoid hitches mid-game.</summary>
        public static void Prewarm(string skin, int upToLevel)
        {
            for (int l = 0; l <= upToLevel; l++)
            {
                Cube(skin, l);
                Cube(skin, l, true);
            }
        }
    }
}
