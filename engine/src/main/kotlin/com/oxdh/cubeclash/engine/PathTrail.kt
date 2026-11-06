package com.oxdh.cubeclash.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records the path travelled by a chain's head as evenly spaced points and samples positions at an arc
 * distance behind the head in O(1). Following cubes sit at fixed distances along this path, so the chain
 * stays intact through sharp turns, full circles and reversals.
 */
class PathTrail(val spacing: Float, initialLength: Float) {
    private var xs: FloatArray
    private var ys: FloatArray
    private var newest = -1
    var count = 0
        private set
    var headX = 0f
        private set
    var headY = 0f
        private set

    val head: Vec2 get() = Vec2(headX, headY)
    val capacity: Int get() = xs.size

    init {
        require(spacing > 0f)
        val cap = max(8, ceil(initialLength / spacing).toInt() + 4)
        xs = FloatArray(cap); ys = FloatArray(cap)
    }

    /** Straight trail behind [head] pointing along [forward]. */
    fun reset(head: Vec2, forward: Vec2, length: Float) {
        ensureLength(length)
        val f = if (forward.lengthSquared > 1e-8f) forward.normalized else Vec2.UNIT_Y
        headX = head.x; headY = head.y
        count = 0; newest = -1
        val n = min(xs.size, ceil(length / spacing).toInt() + 2)
        for (i in n - 1 downTo 0) push(head.x - f.x * i * spacing, head.y - f.y * i * spacing)
    }

    /** Grows the buffer (keeping history) so at least [length] of path can be stored. */
    fun ensureLength(length: Float) {
        val needed = ceil(length / spacing).toInt() + 4
        if (needed <= xs.size) return
        val cap = max(needed, xs.size * 2)
        val nx = FloatArray(cap); val ny = FloatArray(cap)
        var j = 0
        for (k in count - 1 downTo 0) { val idx = index(k); nx[j] = xs[idx]; ny[j] = ys[idx]; j++ }
        xs = nx; ys = ny
        newest = count - 1
    }

    private fun index(k: Int): Int { var i = newest - k; if (i < 0) i += xs.size; return i }

    private fun push(x: Float, y: Float) {
        newest = (newest + 1) % xs.size
        xs[newest] = x; ys[newest] = y
        if (count < xs.size) count++
    }

    /** k = 0 is the newest recorded point. */
    fun pointAt(k: Int): Vec2 { val i = index(k); return Vec2(xs[i], ys[i]) }

    fun moveHead(x: Float, y: Float) {
        headX = x; headY = y
        if (count == 0) { push(x, y); return }
        var lx = xs[newest]; var ly = ys[newest]
        var dx = x - lx; var dy = y - ly
        var dist = sqrt(dx * dx + dy * dy)
        if (dist > xs.size * spacing) { // teleport: re-seed
            reset(Vec2(x, y), Vec2(dx, dy), (count - 1) * spacing)
            return
        }
        while (dist >= spacing) {
            lx += dx / dist * spacing; ly += dy / dist * spacing
            push(lx, ly)
            dx = x - lx; dy = y - ly
            dist = sqrt(dx * dx + dy * dy)
        }
    }

    fun moveHead(p: Vec2) = moveHead(p.x, p.y)

    /** Writes the position at arc [distance] behind the head into [out] (x at 0, y at 1). */
    fun sampleInto(distance: Float, out: FloatArray) {
        if (count == 0 || distance <= 0f) { out[0] = headX; out[1] = headY; return }
        val nx = xs[newest]; val ny = ys[newest]
        val ddx = headX - nx; val ddy = headY - ny
        val d0 = sqrt(ddx * ddx + ddy * ddy)
        if (distance <= d0) {
            val t = if (d0 > 1e-6f) distance / d0 else 1f
            out[0] = headX + (nx - headX) * t; out[1] = headY + (ny - headY) * t
            return
        }
        val rem = (distance - d0) / spacing
        val k = rem.toInt()
        if (k >= count - 1) { val i = index(count - 1); out[0] = xs[i]; out[1] = ys[i]; return }
        val t = rem - k
        val a = index(k); val b = index(k + 1)
        out[0] = xs[a] + (xs[b] - xs[a]) * t; out[1] = ys[a] + (ys[b] - ys[a]) * t
    }

    private val tmp = FloatArray(2)
    fun sample(distance: Float): Vec2 { sampleInto(distance, tmp); return Vec2(tmp[0], tmp[1]) }
}

/**
 * Uniform-grid spatial hash for fast radius queries (collectibles near a head, cubes near a head).
 * Avoids all-pairs checks; allocation-free in steady state.
 */
class SpatialHash<T : Any>(val cellSize: Float) {
    private class Bucket<T> { val items = ArrayList<T>(8); val xs = FloatArrayList(); val ys = FloatArrayList() }
    private val cells = HashMap<Long, Bucket<T>>(256)
    private val itemCell = HashMap<T, Long>(256)
    private val pool = ArrayDeque<Bucket<T>>()
    private val inv = 1f / cellSize

    val size: Int get() = itemCell.size

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    private fun coord(v: Float) = kotlin.math.floor(v * inv).toInt()

    operator fun contains(item: T) = itemCell.containsKey(item)

    fun insert(item: T, x: Float, y: Float) {
        if (itemCell.containsKey(item)) { move(item, x, y); return }
        val k = key(coord(x), coord(y))
        val b = cells.getOrPut(k) { pool.removeLastOrNull() ?: Bucket() }
        b.items.add(item); b.xs.add(x); b.ys.add(y)
        itemCell[item] = k
    }

    fun remove(item: T): Boolean {
        val k = itemCell.remove(item) ?: return false
        val b = cells[k] ?: return true
        val i = b.items.indexOf(item)
        if (i >= 0) {
            val last = b.items.size - 1
            b.items[i] = b.items[last]; b.xs[i] = b.xs[last]; b.ys[i] = b.ys[last]
            b.items.removeAt(last); b.xs.removeLast(); b.ys.removeLast()
        }
        if (b.items.isEmpty()) { cells.remove(k); pool.addLast(b) }
        return true
    }

    fun move(item: T, x: Float, y: Float) {
        val old = itemCell[item]
        if (old == null) { insert(item, x, y); return }
        val nk = key(coord(x), coord(y))
        if (nk == old) {
            val b = cells[old]!!
            val i = b.items.indexOf(item)
            b.xs[i] = x; b.ys[i] = y
            return
        }
        remove(item); insert(item, x, y)
    }

    fun clear() {
        for (b in cells.values) { b.items.clear(); b.xs.clear(); b.ys.clear(); pool.addLast(b) }
        cells.clear(); itemCell.clear()
    }

    /** Appends every item within [radius] of (x, y) to [out]; returns how many were added. */
    fun query(x: Float, y: Float, radius: Float, out: MutableList<T>): Int {
        var found = 0
        val r2 = radius * radius
        for (cx in coord(x - radius)..coord(x + radius)) for (cy in coord(y - radius)..coord(y + radius)) {
            val b = cells[key(cx, cy)] ?: continue
            for (i in b.items.indices) {
                val dx = b.xs[i] - x; val dy = b.ys[i] - y
                if (dx * dx + dy * dy <= r2) { out.add(b.items[i]); found++ }
            }
        }
        return found
    }

    fun anyWithin(x: Float, y: Float, radius: Float): Boolean {
        val r2 = radius * radius
        for (cx in coord(x - radius)..coord(x + radius)) for (cy in coord(y - radius)..coord(y + radius)) {
            val b = cells[key(cx, cy)] ?: continue
            for (i in b.items.indices) {
                val dx = b.xs[i] - x; val dy = b.ys[i] - y
                if (dx * dx + dy * dy <= r2) return true
            }
        }
        return false
    }
}

/** Minimal growable float list (avoids boxing). */
class FloatArrayList {
    private var data = FloatArray(8)
    var size = 0
        private set
    fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    operator fun get(i: Int) = data[i]
    operator fun set(i: Int, v: Float) { data[i] = v }
    fun removeLast() { if (size > 0) size-- }
    fun clear() { size = 0 }
}
