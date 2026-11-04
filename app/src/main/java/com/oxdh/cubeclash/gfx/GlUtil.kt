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
