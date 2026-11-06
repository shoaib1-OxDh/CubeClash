package com.oxdh.cubeclash.engine

import kotlin.math.max
import kotlin.math.min

/** One cube of a chain. Id is stable for the cube's lifetime. */
class ChainCell internal constructor(val id: Int, level: Int) {
    var level: Int = level
        internal set
    val value: Double get() = NumberValue.valueOf(level)
    override fun toString() = "#$id:${NumberFormat.formatLevel(level)}"
}

/** Monotonic id source shared by every chain so cube ids are globally unique. */
class IdGenerator {
    private var next = 1
    fun next() = next++
}

/**
 * Ordered cubes of a chain; index 0 is the leading cube (head). Cubes are kept in non-increasing level order.
 * Cube values, chain length, score and highest value are separate concepts: only [MergeSystem] mutates this.
 */
class ChainModel(private val ids: IdGenerator, startLevel: Int = 0) {
    private val cellList = ArrayList<ChainCell>(32)

    /** Accumulated value for [SmallerCubeBehaviour.FEED_LEADER]. */
    var leaderProgress = 0.0
        internal set

    /** Incremented on every change so views can cheaply detect changes. */
    var version = 0
        private set

    init { reset(startLevel) }

    val cells: List<ChainCell> get() = cellList
    val length: Int get() = cellList.size
    val isEmpty: Boolean get() = cellList.isEmpty()
    val head: ChainCell? get() = cellList.firstOrNull()
    val headLevel: Int get() = cellList.firstOrNull()?.level ?: 0
    val totalValue: Double get() = cellList.sumOf { it.value }

    fun reset(startLevel: Int) {
        cellList.clear()
        leaderProgress = 0.0
        cellList.add(ChainCell(ids.next(), max(0, startLevel)))
        version++
    }

    /** Replaces the chain with these levels (sorted descending). */
    fun setLevels(levels: List<Int>) {
        cellList.clear()
        leaderProgress = 0.0
        for (l in levels.sortedDescending()) cellList.add(ChainCell(ids.next(), max(0, l)))
        if (cellList.isEmpty()) cellList.add(ChainCell(ids.next(), 0))
        version++
    }

    fun indexOf(cellId: Int): Int = cellList.indexOfFirst { it.id == cellId }

    /** Insert index for [level]: after every cube with level >= it. */
    fun sortedInsertIndex(level: Int): Int {
        var i = 0
        while (i < cellList.size && cellList[i].level >= level) i++
        return i
    }

    internal fun insertSorted(level: Int): ChainCell {
        val cell = ChainCell(ids.next(), level)
        cellList.add(sortedInsertIndex(level), cell)
        version++
        return cell
    }

    internal fun setLevel(cell: ChainCell, level: Int) {
        cell.level = min(level, NumberValue.MAX_LEVEL)
        version++
    }

    internal fun removeAt(index: Int) {
        cellList.removeAt(index)
        version++
    }

    /** Removes the cube at [index] and everything behind it (severing) and returns them. */
    fun removeFrom(index: Int): List<ChainCell> {
        val start = max(0, index)
        if (start >= cellList.size) return emptyList()
        val removed = ArrayList(cellList.subList(start, cellList.size))
        while (cellList.size > start) cellList.removeAt(cellList.size - 1)
        if (start == 0) leaderProgress = 0.0
        version++
        return removed
    }

    fun isSorted(): Boolean = (1 until cellList.size).all { cellList[it].level <= cellList[it - 1].level }

    /** Index of the head-most adjacent equal pair (i, i+1) at or after [start], or -1. */
    fun firstEqualAdjacentPair(start: Int = 0): Int {
        var i = max(0, start)
        while (i + 1 < cellList.size) {
            if (cellList[i].level == cellList[i + 1].level) return i
            i++
        }
        return -1
    }

    /**
     * Network replicas: makes the levels equal to [levels] while keeping the identity of cubes at the same index
     * (so views animate smoothly). Returns the indices whose level increased (merges / upgrades).
     */
    fun replaceLevelsInPlace(levels: IntArray): List<Int> {
        val grew = ArrayList<Int>()
        val n = levels.size.coerceAtLeast(1)
        while (cellList.size > n) cellList.removeAt(cellList.size - 1)
        for (i in 0 until n) {
            val l = if (i < levels.size) levels[i].coerceAtLeast(0) else 0
            if (i < cellList.size) {
                if (cellList[i].level != l) { if (l > cellList[i].level) grew += i; cellList[i].level = l }
            } else cellList.add(ChainCell(ids.next(), l))
        }
        version++
        return grew
    }

    fun levelsEqual(levels: IntArray): Boolean {
        if (levels.size != cellList.size) return false
        for (i in levels.indices) if (levels[i] != cellList[i].level) return false
        return true
    }

    override fun toString() = cellList.joinToString(", ", "[", "]") { NumberFormat.formatLevel(it.level) }
}

enum class ChainEventType { CUBE_ADDED, MERGED, UPGRADED, SHED, SCORE_ONLY, LEADER_FED }

/** One change produced by [MergeSystem]. For MERGED: [cellId] survives, [otherCellId] is removed. */
data class ChainEvent(
    val type: ChainEventType,
    val cellId: Int = 0,
    val otherCellId: Int = 0,
    val level: Int = 0,
    val index: Int = 0,
    val score: Long = 0,
)

/**
 * Applies collection and merge rules. Merges are driven by the model, never by contacts, so each merge happens
 * exactly once. Eligibility (Standard/Hybrid): only ADJACENT EQUAL cubes merge; the head-most pair goes first;
 * the front cube survives with level + 1. That keeps the chain sorted. Per step each cube merges at most once.
 */
class MergeSystem(val rules: RulesConfig) {

    fun canCollect(chain: ChainModel, level: Int): Boolean {
        if (chain.isEmpty || level < 0) return false
        return when (rules.eligibility) {
            CollectEligibility.ANY_VALUE -> true
            CollectEligibility.MATCHING_CHAIN_VALUE -> chain.cells.any { it.level == level }
            CollectEligibility.LESS_OR_EQUAL_TO_HEAD -> level <= chain.headLevel
        }
    }

    /** Collects a world cube; returns false (and changes nothing) if it is not eligible. */
    fun tryCollect(chain: ChainModel, levelIn: Int, events: MutableList<ChainEvent>?): Boolean {
        if (!canCollect(chain, levelIn)) return false
        val level = min(levelIn, NumberValue.MAX_LEVEL)
        val collectScore = ScoreCalculator.forCollect(level, rules)
        val smaller = level < chain.headLevel
        val head = chain.head!!

        if (smaller && rules.smallerCubeBehaviour == SmallerCubeBehaviour.SCORE_ONLY) {
            events?.add(ChainEvent(ChainEventType.SCORE_ONLY, cellId = head.id, level = level, score = collectScore))
            return true
        }
        if (smaller && rules.smallerCubeBehaviour == SmallerCubeBehaviour.FEED_LEADER) {
            feedLeader(chain, level, collectScore, events)
            return true
        }
        when (rules.mergeMode) {
            MergeMode.CHAIN_COLLECTION -> collectChainMode(chain, level, collectScore, events)
            MergeMode.STANDARD -> { addCube(chain, level, collectScore, events); resolveAllMerges(chain, events) }
            MergeMode.HYBRID -> {
                addCube(chain, level, collectScore, events)
                if (rules.hybridMergeInterval <= 0f) resolveAllMerges(chain, events)
            }
        }
        return true
    }

    private fun addCube(chain: ChainModel, level: Int, collectScore: Long, events: MutableList<ChainEvent>?) {
        val cell = chain.insertSorted(level)
        events?.add(ChainEvent(ChainEventType.CUBE_ADDED, cellId = cell.id, level = level, index = chain.indexOf(cell.id), score = collectScore))
        enforceMaxLength(chain, events)
    }

    private fun collectChainMode(chain: ChainModel, level: Int, collectScore: Long, events: MutableList<ChainEvent>?) {
        val i = chain.cells.indexOfFirst { it.level == level }
        if (i >= 0) {
            val cell = chain.cells[i]
            chain.setLevel(cell, level + 1)
            events?.add(ChainEvent(ChainEventType.UPGRADED, cellId = cell.id, level = cell.level, index = i,
                score = collectScore + ScoreCalculator.forMerge(level + 1, rules)))
            return
        }
        addCube(chain, level, collectScore, events)
    }

    private fun feedLeader(chain: ChainModel, level: Int, collectScore: Long, events: MutableList<ChainEvent>?) {
        chain.leaderProgress += NumberValue.valueOf(level)
        var upgraded = false
        val head = chain.head!!
        while (chain.leaderProgress >= head.value && head.level < NumberValue.MAX_LEVEL) {
            chain.leaderProgress -= head.value
            chain.setLevel(head, head.level + 1)
            events?.add(ChainEvent(ChainEventType.UPGRADED, cellId = head.id, level = head.level, index = 0,
                score = (if (upgraded) 0 else collectScore) + ScoreCalculator.forMerge(head.level, rules)))
            upgraded = true
        }
        if (!upgraded) events?.add(ChainEvent(ChainEventType.LEADER_FED, cellId = head.id, level = level, score = collectScore))
        if (rules.mergeMode == MergeMode.STANDARD || (rules.mergeMode == MergeMode.HYBRID && rules.hybridMergeInterval <= 0f))
            resolveAllMerges(chain, events)
    }

    private fun enforceMaxLength(chain: ChainModel, events: MutableList<ChainEvent>?) {
        while (chain.length > rules.maxChainLength) {
            val tail = chain.length - 1
            val cell = chain.cells[tail]
            chain.removeAt(tail)
            events?.add(ChainEvent(ChainEventType.SHED, cellId = cell.id, level = cell.level, index = tail, score = ScoreCalculator.forShed(cell.level, rules)))
        }
    }

    fun hasPendingMerge(chain: ChainModel): Boolean =
        rules.mergeMode != MergeMode.CHAIN_COLLECTION && chain.firstEqualAdjacentPair() >= 0

    /** One Hybrid merge step; returns the number of merges performed. */
    fun stepMerges(chain: ChainModel, events: MutableList<ChainEvent>?): Int =
        if (rules.mergeMode == MergeMode.CHAIN_COLLECTION) 0 else mergePass(chain, rules.hybridMergesPerStep, events)

    /** Merges until no adjacent equal pair remains. */
    fun resolveAllMerges(chain: ChainModel, events: MutableList<ChainEvent>?): Int {
        var total = 0
        var guard = chain.length + 1
        while (guard-- > 0) {
            val merged = mergePass(chain, Int.MAX_VALUE, events)
            if (merged == 0) break
            total += merged
        }
        return total
    }

    private fun mergePass(chain: ChainModel, maxMerges: Int, events: MutableList<ChainEvent>?): Int {
        var merges = 0
        var start = 0
        while (merges < maxMerges) {
            val i = chain.firstEqualAdjacentPair(start)
            if (i < 0) break
            val survivor = chain.cells[i]
            val removed = chain.cells[i + 1]
            val newLevel = min(survivor.level + 1, NumberValue.MAX_LEVEL)
            chain.removeAt(i + 1)
            chain.setLevel(survivor, newLevel)
            events?.add(ChainEvent(ChainEventType.MERGED, cellId = survivor.id, otherCellId = removed.id, level = newLevel, index = i,
                score = ScoreCalculator.forMerge(newLevel, rules)))
            merges++
            start = i + 1
        }
        return merges
    }
}
