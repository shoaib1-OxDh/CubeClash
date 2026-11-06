package com.oxdh.cubeclash.gfx

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

object GlUtil {
    fun program(vs: String, fs: String): Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            Log.e("CubeClash", "Program link failed: $log")
            throw RuntimeException("GL link failed: $log")
        }
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            Log.e("CubeClash", "Shader compile failed: $log\n$src")
            throw RuntimeException("GL shader compile failed: $log")
        }
        return s
    }

    fun floatBuffer(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun floatBuffer(a: FloatArray): FloatBuffer = floatBuffer(a.size).apply { put(a); position(0) }
    fun shortBuffer(a: ShortArray): ShortBuffer = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); position(0) }

    fun genBuffer(): Int { val b = IntArray(1); GLES30.glGenBuffers(1, b, 0); return b[0] }
    fun genVao(): Int { val b = IntArray(1); GLES30.glGenVertexArrays(1, b, 0); return b[0] }
}

/** Rounded unit cube (edge 1, centred) with per-face 0..1 UVs oriented so numbers read upright from outside. */
object CubeMesh {
    /** Interleaved: px,py,pz, nx,ny,nz, u,v. */
    val vertices: FloatArray
    val indices: ShortArray

    init {
        val n = 5
        val radius = 0.16f
        val inner = 0.5f - radius
        val v = ArrayList<Float>()
        val idx = ArrayList<Short>()
        // (normal, up) per face. Local forward is -Z; with this choice text reads upright and unmirrored.
        val faces = arrayOf(
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f), floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f, 0f, -1f), floatArrayOf(0f, -1f, 0f, 0f, 0f, 1f))
        for (f in faces) {
            val nx = f[0]; val ny = f[1]; val nz = f[2]; val ux = f[3]; val uy = f[4]; val uz = f[5]
            // right = up x normal (viewer-right when looking at the face from outside, right-handed GL)
            val rx = uy * nz - uz * ny; val ry = uz * nx - ux * nz; val rz = ux * ny - uy * nx
            val start = v.size / 8
            for (j in 0..n) for (i in 0..n) {
                val a = i / n.toFloat(); val b = j / n.toFloat()
                val px = nx * 0.5f + rx * (a - 0.5f) + ux * (b - 0.5f)
                val py = ny * 0.5f + ry * (a - 0.5f) + uy * (b - 0.5f)
                val pz = nz * 0.5f + rz * (a - 0.5f) + uz * (b - 0.5f)
                val cx = px.coerceIn(-inner, inner); val cy = py.coerceIn(-inner, inner); val cz = pz.coerceIn(-inner, inner)
                var dx = px - cx; var dy = py - cy; var dz = pz - cz
                val l = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
                dx /= l; dy /= l; dz /= l
                v.add(cx + dx * radius); v.add(cy + dy * radius); v.add(cz + dz * radius)
                v.add(dx); v.add(dy); v.add(dz)
                v.add(a); v.add(1f - b) // bitmap row 0 is the top of the face
            }
            for (j in 0 until n) for (i in 0 until n) {
                val i0 = start + j * (n + 1) + i
                val i1 = i0 + 1; val i2 = i0 + n + 1; val i3 = i2 + 1
                // Counter-clockwise when seen from outside: (i0, i1, i3), (i0, i3, i2).
                idx.add(i0.toShort()); idx.add(i1.toShort()); idx.add(i3.toShort())
                idx.add(i0.toShort()); idx.add(i3.toShort()); idx.add(i2.toShort())
            }
        }
        vertices = v.toFloatArray()
        indices = idx.toShortArray()
    }
}

/** A mesh in the same interleaved layout as [CubeMesh] (px,py,pz, nx,ny,nz, u,v). */
class Mesh(val vertices: FloatArray, val indices: ShortArray)

/**
 * Builds round obstacle and arena meshes by spinning a (radius, height) profile around the Y axis. Profiles go from
 * the bottom up and end at the top centre. Unit shapes fit the same -0.5..0.5 box as the cube, so the cube shader's
 * instance size / height multiplier place them; UVs all point at the centre of the plain atlas cell.
 */
object Shapes {
    /**
     * @param smoothAround smooth normals around the axis (cylinder) or flat facets (hexagon, rock).
     * @param noise optional radial jitter per (segment, profile point) for rocky looks.
     */
    fun lathe(profile: FloatArray, segments: Int, smoothAround: Boolean, noise: ((Int, Int) -> Float)? = null): Mesh {
        val pts = profile.size / 2
        val v = ArrayList<Float>(segments * pts * 32)
        val idx = ArrayList<Short>(segments * pts * 6)
        fun pos(i: Int, j: Int, out: FloatArray) {
            val a = (i % segments) / segments.toFloat() * (Math.PI * 2).toFloat()
            val n = noise?.invoke(i % segments, j) ?: 1f
            val r = profile[j * 2] * n
            out[0] = r * kotlin.math.cos(a); out[1] = profile[j * 2 + 1]; out[2] = r * kotlin.math.sin(a)
        }
        // Outward profile normal of edge j (from point j to j+1) in (r, y).
        fun edgeN(j: Int): FloatArray {
            val dr = profile[j * 2 + 2] - profile[j * 2]; val dy = profile[j * 2 + 3] - profile[j * 2 + 1]
            val l = kotlin.math.sqrt(dr * dr + dy * dy).coerceAtLeast(1e-6f)
            return floatArrayOf(dy / l, -dr / l)
        }
        val p = Array(4) { FloatArray(3) }
        for (i in 0 until segments) for (j in 0 until pts - 1) {
            pos(i, j, p[0]); pos(i + 1, j, p[1]); pos(i + 1, j + 1, p[2]); pos(i, j + 1, p[3])
            val en = edgeN(j)
            val start = v.size / 8
            if (smoothAround && noise == null) {
                for ((k, ii) in intArrayOf(i, i + 1, i + 1, i).withIndex()) {
                    val a = (ii % segments) / segments.toFloat() * (Math.PI * 2).toFloat()
                    v.add(p[k][0]); v.add(p[k][1]); v.add(p[k][2])
                    v.add(en[0] * kotlin.math.cos(a)); v.add(en[1]); v.add(en[0] * kotlin.math.sin(a))
                    v.add(0.5f); v.add(0.5f)
                }
            } else {
                // Flat facet: cross product of the diagonals, oriented like the analytic outward normal.
                val ax = p[2][0] - p[0][0]; val ay = p[2][1] - p[0][1]; val az = p[2][2] - p[0][2]
                val bx = p[3][0] - p[1][0]; val by = p[3][1] - p[1][1]; val bz = p[3][2] - p[1][2]
                var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
                val am = (i + 0.5f) / segments * (Math.PI * 2).toFloat()
                val ex = en[0] * kotlin.math.cos(am); val ez = en[0] * kotlin.math.sin(am)
                if (nx * ex + ny * en[1] + nz * ez < 0f) { nx = -nx; ny = -ny; nz = -nz }
                val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                for (k in 0..3) { v.add(p[k][0]); v.add(p[k][1]); v.add(p[k][2]); v.add(nx / l); v.add(ny / l); v.add(nz / l); v.add(0.5f); v.add(0.5f) }
            }
            for (t in intArrayOf(0, 1, 2, 0, 2, 3)) idx.add((start + t).toShort())
        }
        return Mesh(v.toFloatArray(), idx.toShortArray())
    }

    private fun dome(): FloatArray {
        val out = ArrayList<Float>()
        out.add(0.5f); out.add(-0.5f)
        val steps = 8
        for (k in 1..steps) {
            val a = k / steps.toFloat() * (Math.PI / 2).toFloat()
            out.add(0.5f * kotlin.math.cos(a)); out.add(-0.5f + kotlin.math.sin(a))
        }
        return out.toFloatArray()
    }

    val pillar by lazy { lathe(floatArrayOf(0.5f, -0.5f, 0.5f, 0.38f, 0.46f, 0.47f, 0.38f, 0.5f, 0f, 0.5f), 24, true) }
    val hex by lazy { lathe(floatArrayOf(0.5f, -0.5f, 0.5f, 0.4f, 0.42f, 0.5f, 0f, 0.5f), 6, false) }
    val dome by lazy { lathe(dome(), 20, true) }
    val cone by lazy { lathe(floatArrayOf(0.5f, -0.5f, 0.42f, -0.3f, 0.16f, 0.45f, 0.1f, 0.5f, 0f, 0.5f), 18, true) }
    val rock by lazy {
        lathe(floatArrayOf(0.5f, -0.5f, 0.52f, -0.1f, 0.42f, 0.28f, 0.22f, 0.5f, 0f, 0.5f), 7, false) { i, j ->
            if (j == 4) 1f else 0.82f + 0.3f * ((((i * 73 + j * 151) * 2654435761L).toInt() ushr 24) and 0xff).toFloat() / 255f
        }
    }

    /** Low rounded wall around the arena edge, in world units (inner face at [radius]). */
    fun ringWall(radius: Float, thickness: Float, height: Float): Mesh {
        val r0 = radius; val r1 = radius + thickness
        // Inner face is walked top-down so its normals point towards the arena.
        val inner = floatArrayOf(r0, 0f, r0, height * 0.78f, r0 + thickness * 0.18f, height)
        val outer = floatArrayOf(r1 - thickness * 0.18f, height, r1, height * 0.78f, r1, 0f)
        val segs = (radius * 1.1f).toInt().coerceIn(96, 400)
        // One profile from the inner foot over the top to the outer foot; edge normals of that path point "left",
        // so walk it reversed (outer foot -> inner foot) to keep them outward-facing (up / away from the wall core).
        val path = (inner.toList() + outer.toList()).chunked(2).reversed().flatten().toFloatArray()
        return lathe(path, segs, true)
    }
}
