using System.Collections.Generic;
using CubeSnake.Core;
using CubeSnake.Logic;
using CubeSnake.Visual;
using UnityEngine;

namespace CubeSnake.World
{
    /// <summary>
    /// Builds the arena procedurally: grid ground, glowing boundary ring with posts, a few obstacles, distant
    /// low-poly decorations, lighting globals and fog. Everything is static and uses shared materials.
    /// </summary>
    public static class ArenaBuilder
    {
        public sealed class Decoration
        {
            public Transform Transform;
            public float Spin;
            public float BobPhase;
            public float BaseY;
        }

        public static void ApplyLighting(ArenaConfig cfg)
        {
            Vector3 lightDir = new Vector3(0.35f, 0.85f, -0.4f).normalized;
            Shader.SetGlobalVector("_CS_LightDir", new Vector4(lightDir.x, lightDir.y, lightDir.z, 0f));
            Shader.SetGlobalColor("_CS_LightColor", new Color(0.82f, 0.8f, 0.74f));
            Shader.SetGlobalColor("_CS_Ambient", new Color(0.5f, 0.53f, 0.62f));
            RenderSettings.fog = true;
            RenderSettings.fogMode = FogMode.Linear;
            RenderSettings.fogColor = cfg.skyColor;
            RenderSettings.fogStartDistance = 70f;
            RenderSettings.fogEndDistance = 230f;
            RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
            RenderSettings.ambientLight = new Color(0.55f, 0.58f, 0.66f);
        }

        public static List<Decoration> Build(Transform root, ArenaContext ctx)
        {
            var cfg = ctx.Config.arena;
            ApplyLighting(cfg);
            float r = ctx.ArenaRadius;

            // Ground.
            var ground = new GameObject("Ground");
            ground.transform.SetParent(root, false);
            ground.AddComponent<MeshFilter>().sharedMesh = MeshFactory.GroundQuad;
            var gr = ground.AddComponent<MeshRenderer>();
            var groundMat = new Material(CubeMaterials.FindShader("CubeSnake/Ground")) { name = "CS_Ground" };
            groundMat.SetColor("_Color", cfg.groundColor);
            groundMat.SetColor("_GridColor", cfg.gridColor);
            groundMat.SetColor("_OutsideColor", cfg.outsideColor);
            groundMat.SetColor("_BoundaryColor", cfg.boundaryColor);
            groundMat.SetFloat("_GridSize", 4f);
            groundMat.SetFloat("_LineWidth", 0.03f);
            groundMat.SetFloat("_ArenaRadius", r);
            gr.sharedMaterial = groundMat;
            gr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            float groundSize = (r + 160f) * 2f;
            ground.transform.localScale = new Vector3(groundSize, 1f, groundSize);

            // Boundary ring (translucent band) and posts.
            var ring = new GameObject("BoundaryRing");
            ring.transform.SetParent(root, false);
            ring.AddComponent<MeshFilter>().sharedMesh = MeshFactory.CreateRing(r, r + 3f, 160);
            var rr = ring.AddComponent<MeshRenderer>();
            var ringMat = new Material(CubeMaterials.FindShader("CubeSnake/Transparent")) { name = "CS_BoundaryRing" };
            ringMat.mainTexture = TextureFactory.White;
            ringMat.color = new Color(cfg.boundaryColor.r, cfg.boundaryColor.g, cfg.boundaryColor.b, 0.35f);
            rr.sharedMaterial = ringMat;
            ring.transform.localPosition = new Vector3(0, 0.03f, 0);

            var postMat = CubeMaterials.Lit(cfg.boundaryColor);
            postMat.SetFloat("_Emission", 0.35f);
            postMat.SetColor("_EmissionColor", cfg.boundaryColor);
            int posts = Mathf.CeilToInt(2f * Mathf.PI * r / 9f);
            for (int i = 0; i < posts; i++)
            {
                float a = i / (float)posts * Mathf.PI * 2f;
                var post = new GameObject("Post");
                post.transform.SetParent(root, false);
                post.AddComponent<MeshFilter>().sharedMesh = MeshFactory.RoundedCube;
                var pr = post.AddComponent<MeshRenderer>();
                pr.sharedMaterial = postMat;
                pr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                post.transform.localPosition = new Vector3(Mathf.Cos(a) * (r + 1f), 0.6f, Mathf.Sin(a) * (r + 1f));
                post.transform.localScale = new Vector3(1f, 1.2f, 1f);
                post.transform.localRotation = Quaternion.Euler(0f, -a * Mathf.Rad2Deg, 0f);
            }

            // Obstacles (pillars). Kept out of the centre so the player never spawns inside one.
            var obstacleMat = CubeMaterials.Lit(new Color(0.55f, 0.6f, 0.82f));
            var capMat = CubeMaterials.Lit(new Color(0.98f, 0.98f, 1f));
            for (int i = 0; i < cfg.obstacleCount; i++)
            {
                for (int attempt = 0; attempt < 20; attempt++)
                {
                    Vec2 p = ctx.Rng.InsideDisc(r - 12f);
                    if (p.Length < 18f) continue;
                    float radius = ctx.Rng.Range(cfg.obstacleMinRadius, cfg.obstacleMaxRadius);
                    bool ok = true;
                    foreach (var o in ctx.Obstacles)
                        if (Vec2.Distance(o.Position, p) < o.Radius + radius + 10f) { ok = false; break; }
                    if (!ok) continue;
                    ctx.Obstacles.Add(new Obstacle { Position = p, Radius = radius });
                    var pillar = new GameObject("Obstacle");
                    pillar.transform.SetParent(root, false);
                    pillar.AddComponent<MeshFilter>().sharedMesh = MeshFactory.Prism;
                    var mr = pillar.AddComponent<MeshRenderer>();
                    mr.sharedMaterial = obstacleMat;
                    mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                    float h = ctx.Rng.Range(2.5f, 5f);
                    pillar.transform.localPosition = new Vector3(p.X, 0f, p.Y);
                    pillar.transform.localScale = new Vector3(radius * 2f, h, radius * 2f);
                    var cap = new GameObject("Cap");
                    cap.transform.SetParent(pillar.transform, false);
                    cap.AddComponent<MeshFilter>().sharedMesh = MeshFactory.Octahedron;
                    var cr = cap.AddComponent<MeshRenderer>();
                    cr.sharedMaterial = capMat;
                    cap.transform.localPosition = new Vector3(0f, 1.25f, 0f);
                    cap.transform.localScale = new Vector3(0.45f, 0.45f / h * 2f, 0.45f);
                    var shadow = new GameObject("Shadow");
                    shadow.transform.SetParent(root, false);
                    shadow.AddComponent<MeshFilter>().sharedMesh = MeshFactory.GroundQuad;
                    shadow.AddComponent<MeshRenderer>().sharedMaterial = CubeMaterials.BlobShadow;
                    shadow.transform.localPosition = new Vector3(p.X, 0.01f, p.Y);
                    shadow.transform.localScale = new Vector3(radius * 3.2f, 1f, radius * 3.2f);
                    break;
                }
            }

            // Distant floating decorations: give a sense of scale without affecting gameplay.
            var decorations = new List<Decoration>();
            var decoMats = new[]
            {
                CubeMaterials.Lit(new Color(1f, 0.78f, 0.35f)), CubeMaterials.Lit(new Color(0.55f, 0.85f, 1f)),
                CubeMaterials.Lit(new Color(1f, 0.55f, 0.7f)), CubeMaterials.Lit(new Color(0.65f, 0.95f, 0.65f)),
            };
            for (int i = 0; i < cfg.decorationCount; i++)
            {
                float a = ctx.Rng.NextFloat() * Mathf.PI * 2f;
                float dist = r + ctx.Rng.Range(30f, 110f);
                var deco = new GameObject("Decoration");
                deco.transform.SetParent(root, false);
                bool octa = ctx.Rng.Chance(0.5f);
                deco.AddComponent<MeshFilter>().sharedMesh = octa ? MeshFactory.Octahedron : MeshFactory.RoundedCube;
                var dr = deco.AddComponent<MeshRenderer>();
                dr.sharedMaterial = decoMats[i % decoMats.Length];
                dr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                float size = ctx.Rng.Range(5f, 16f);
                float y = ctx.Rng.Range(4f, 26f);
                deco.transform.localPosition = new Vector3(Mathf.Cos(a) * dist, y, Mathf.Sin(a) * dist);
                deco.transform.localScale = Vector3.one * size;
                deco.transform.localRotation = Random.rotation;
                decorations.Add(new Decoration { Transform = deco.transform, Spin = ctx.Rng.Range(-12f, 12f), BobPhase = ctx.Rng.NextFloat() * 6f, BaseY = y });
            }
            return decorations;
        }

        public static void AnimateDecorations(List<Decoration> decorations, float dt, float time)
        {
            if (decorations == null) return;
            for (int i = 0; i < decorations.Count; i++)
            {
                var d = decorations[i];
                d.Transform.Rotate(0f, d.Spin * dt, 0f, Space.World);
                var p = d.Transform.localPosition;
                p.y = d.BaseY + Mathf.Sin(time * 0.4f + d.BobPhase) * 1.5f;
                d.Transform.localPosition = p;
            }
        }
    }
}
