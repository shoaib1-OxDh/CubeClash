package com.oxdh.cubeclash.engine

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable 2D vector on the ground plane (x = world X, y = world Z). */
data class Vec2(val x: Float, val y: Float) {
    val lengthSquared: Float get() = x * x + y * y
    val length: Float get() = sqrt(x * x + y * y)
    val normalized: Vec2 get() { val l = length; return if (l > 1e-6f) Vec2(x / l, y / l) else ZERO }
    /** Angle in radians from +X towards +Y. */
    val angle: Float get() = atan2(y, x)
    val perpendicular: Vec2 get() = Vec2(-y, x)

    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun unaryMinus() = Vec2(-x, -y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)

    override fun toString() = "(%.3f, %.3f)".format(x, y)

    companion object {
        val ZERO = Vec2(0f, 0f)
        val UNIT_X = Vec2(1f, 0f)
        val UNIT_Y = Vec2(0f, 1f)
        fun fromAngle(r: Float) = Vec2(cos(r), sin(r))
        fun dot(a: Vec2, b: Vec2) = a.x * b.x + a.y * b.y
        fun distance(a: Vec2, b: Vec2) = (a - b).length
        fun distanceSquared(a: Vec2, b: Vec2): Float { val dx = a.x - b.x; val dy = a.y - b.y; return dx * dx + dy * dy }
        fun lerp(a: Vec2, b: Vec2, t: Float) = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
}

object MathUtil {
    const val PI = Math.PI.toFloat()
    const val TWO_PI = (Math.PI * 2.0).toFloat()
    const val DEG2RAD = PI / 180f

    fun clamp(v: Float, min: Float, max: Float) = if (v < min) min else if (v > max) max else v
    fun clamp(v: Int, min: Int, max: Int) = if (v < min) min else if (v > max) max else v
    fun clamp01(v: Float) = clamp(v, 0f, 1f)
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Wraps an angle into (-PI, PI]. */
    fun wrapAngle(radians: Float): Float {
        var r = radians % TWO_PI
        if (r <= -PI) r += TWO_PI else if (r > PI) r -= TWO_PI
        return r
    }

    /** Rotates [current] towards [target] by at most [maxDelta] radians along the shortest way. */
    fun rotateTowards(current: Float, target: Float, maxDelta: Float): Float {
        val delta = wrapAngle(target - current)
        if (abs(delta) <= maxDelta) return wrapAngle(target)
        return wrapAngle(current + sign(delta) * maxDelta)
    }

    fun moveTowards(current: Float, target: Float, maxDelta: Float): Float {
        if (abs(target - current) <= maxDelta) return target
        return current + sign(target - current) * maxDelta
    }

    /** Frame-rate independent exponential smoothing factor. */
    fun smoothFactor(sharpness: Float, dt: Float) = 1f - exp(-sharpness * dt)
}
