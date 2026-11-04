using System.Collections.Generic;
using UnityEngine;

namespace CubeSnake.Visual
{
    /// <summary>Procedural meshes (rounded cube, quads, rings, low-poly decorations). Created once and shared.</summary>
    public static class MeshFactory
    {
        static Mesh s_roundedCube, s_quad, s_ring, s_prism, s_octa;

        /// <summary>Unit cube (edge 1) with rounded edges. Each face has its own 0..1 UVs so the number shows on every face.</summary>
        public static Mesh RoundedCube
        {
            get
            {
                if (s_roundedCube == null) s_roundedCube = BuildRoundedCube(6, 0.16f);
                return s_roundedCube;
            }
        }

        /// <summary>Horizontal quad (XZ plane) of size 1 facing up.</summary>
        public static Mesh GroundQuad
        {
            get
            {
                if (s_quad != null) return s_quad;
                s_quad = new Mesh { name = "CS_GroundQuad" };
                s_quad.vertices = new[] { new Vector3(-0.5f, 0, -0.5f), new Vector3(-0.5f, 0, 0.5f), new Vector3(0.5f, 0, 0.5f), new Vector3(0.5f, 0, -0.5f) };
                s_quad.uv = new[] { new Vector2(0, 0), new Vector2(0, 1), new Vector2(1, 1), new Vector2(1, 0) };
                s_quad.normals = new[] { Vector3.up, Vector3.up, Vector3.up, Vector3.up };
                s_quad.triangles = new[] { 0, 1, 2, 0, 2, 3 };
                s_quad.RecalculateBounds();
                return s_quad;
            }
        }

        /// <summary>Flat ring (XZ) with inner radius 0.82 and outer radius 1.</summary>
        public static Mesh Ring
        {
            get
            {
                if (s_ring == null) s_ring = BuildRing(0.82f, 1f, 64);
                return s_ring;
            }
        }

        public static Mesh Prism
        {
            get
            {
                if (s_prism != null) return s_prism;
                s_prism = BuildLathe(new[] { new Vector2(0.5f, 0), new Vector2(0.5f, 1), new Vector2(0f, 1) }, 6, "CS_Prism");
                return s_prism;
            }
        }

        public static Mesh Octahedron
        {
            get
            {
                if (s_octa != null) return s_octa;
                s_octa = BuildLathe(new[] { new Vector2(0f, -0.5f), new Vector2(0.5f, 0f), new Vector2(0f, 0.5f) }, 4, "CS_Octa");
                return s_octa;
            }
        }

        static Mesh BuildRoundedCube(int n, float radius)
        {
            var verts = new List<Vector3>();
            var normals = new List<Vector3>();
            var uvs = new List<Vector2>();
            var tris = new List<int>();
            Vector3[] faceNormals = { Vector3.forward, Vector3.back, Vector3.up, Vector3.down, Vector3.right, Vector3.left };
            Vector3[] faceUps = { Vector3.up, Vector3.up, Vector3.forward, Vector3.back, Vector3.up, Vector3.up };
            float inner = 0.5f - radius;
            foreach (var (normal, up, idx) in Enumerate(faceNormals, faceUps))
            {
                Vector3 right = Vector3.Cross(normal, up); // viewer-right when looking at the face from outside
                int start = verts.Count;
                for (int y = 0; y <= n; y++)
                {
                    for (int x = 0; x <= n; x++)
                    {
                        float u = (float)x / n, v = (float)y / n;
                        Vector3 p = normal * 0.5f + right * (u - 0.5f) + up * (v - 0.5f);
                        Vector3 clamped = new Vector3(Mathf.Clamp(p.x, -inner, inner), Mathf.Clamp(p.y, -inner, inner), Mathf.Clamp(p.z, -inner, inner));
                        Vector3 nrm = (p - clamped).normalized;
                        verts.Add(clamped + nrm * radius);
                        normals.Add(nrm);
                        uvs.Add(new Vector2(u, v));
                    }
                }
                for (int y = 0; y < n; y++)
                {
                    for (int x = 0; x < n; x++)
                    {
                        int i0 = start + y * (n + 1) + x;
                        int i1 = i0 + 1, i2 = i0 + n + 1, i3 = i2 + 1;
                        tris.Add(i0); tris.Add(i2); tris.Add(i1);
                        tris.Add(i1); tris.Add(i2); tris.Add(i3);
                    }
                }
            }
            var mesh = new Mesh { name = "CS_RoundedCube" };
            mesh.SetVertices(verts);
            mesh.SetNormals(normals);
            mesh.SetUVs(0, uvs);
            mesh.SetTriangles(tris, 0);
            // Ensure outward winding: flip triangles whose normal points inward.
            FixWinding(mesh);
            mesh.RecalculateBounds();
            mesh.UploadMeshData(false);
            return mesh;
        }

        static IEnumerable<(Vector3, Vector3, int)> Enumerate(Vector3[] a, Vector3[] b)
        {
            for (int i = 0; i < a.Length; i++) yield return (a[i], b[i], i);
        }

        static void FixWinding(Mesh mesh)
        {
            var v = mesh.vertices;
            var t = mesh.triangles;
            for (int i = 0; i < t.Length; i += 3)
            {
                Vector3 a = v[t[i]], b = v[t[i + 1]], c = v[t[i + 2]];
                Vector3 n = Vector3.Cross(b - a, c - a);
                Vector3 center = (a + b + c) / 3f;
                if (Vector3.Dot(n, center) < 0)
                {
                    int tmp = t[i + 1];
                    t[i + 1] = t[i + 2];
                    t[i + 2] = tmp;
                }
            }
            mesh.triangles = t;
        }

        /// <summary>Creates a new flat ring mesh with absolute radii (caller owns it).</summary>
        public static Mesh CreateRing(float inner, float outer, int segments) => BuildRing(inner, outer, segments);

        static Mesh BuildRing(float inner, float outer, int segments)
        {
            var verts = new Vector3[(segments + 1) * 2];
            var uvs = new Vector2[verts.Length];
            var normals = new Vector3[verts.Length];
            var tris = new int[segments * 6];
            for (int i = 0; i <= segments; i++)
            {
                float a = i / (float)segments * Mathf.PI * 2f;
                Vector3 d = new Vector3(Mathf.Cos(a), 0, Mathf.Sin(a));
                verts[i * 2] = d * inner;
                verts[i * 2 + 1] = d * outer;
                uvs[i * 2] = new Vector2(i / (float)segments, 0);
                uvs[i * 2 + 1] = new Vector2(i / (float)segments, 1);
                normals[i * 2] = normals[i * 2 + 1] = Vector3.up;
            }
            for (int i = 0; i < segments; i++)
            {
                int b = i * 2;
                tris[i * 6 + 0] = b; tris[i * 6 + 1] = b + 1; tris[i * 6 + 2] = b + 3;
                tris[i * 6 + 3] = b; tris[i * 6 + 4] = b + 3; tris[i * 6 + 5] = b + 2;
            }
            var mesh = new Mesh { name = "CS_Ring", vertices = verts, uv = uvs, normals = normals, triangles = tris };
            mesh.RecalculateBounds();
            return mesh;
        }

        /// <summary>Flat-shaded lathe (profile revolved around Y), used for low-poly decorations.</summary>
        static Mesh BuildLathe(Vector2[] profile, int sides, string name)
        {
            var verts = new List<Vector3>();
            var tris = new List<int>();
            for (int s = 0; s < sides; s++)
            {
                float a0 = s / (float)sides * Mathf.PI * 2f, a1 = (s + 1) / (float)sides * Mathf.PI * 2f;
                for (int p = 0; p + 1 < profile.Length; p++)
                {
                    Vector3 v00 = new Vector3(Mathf.Cos(a0) * profile[p].x, profile[p].y, Mathf.Sin(a0) * profile[p].x);
                    Vector3 v01 = new Vector3(Mathf.Cos(a1) * profile[p].x, profile[p].y, Mathf.Sin(a1) * profile[p].x);
                    Vector3 v10 = new Vector3(Mathf.Cos(a0) * profile[p + 1].x, profile[p + 1].y, Mathf.Sin(a0) * profile[p + 1].x);
                    Vector3 v11 = new Vector3(Mathf.Cos(a1) * profile[p + 1].x, profile[p + 1].y, Mathf.Sin(a1) * profile[p + 1].x);
                    int b = verts.Count;
                    verts.Add(v00); verts.Add(v01); verts.Add(v11); verts.Add(v10);
                    tris.Add(b); tris.Add(b + 2); tris.Add(b + 1);
                    tris.Add(b); tris.Add(b + 3); tris.Add(b + 2);
                }
            }
            // Un-share vertices for flat shading.
            var flatVerts = new List<Vector3>();
            var flatTris = new List<int>();
            for (int i = 0; i < tris.Count; i++) { flatVerts.Add(verts[tris[i]]); flatTris.Add(i); }
            var mesh = new Mesh { name = name };
            mesh.SetVertices(flatVerts);
            mesh.SetTriangles(flatTris, 0);
            FixWinding(mesh);
            mesh.RecalculateNormals();
            mesh.RecalculateBounds();
            return mesh;
        }
    }
}
