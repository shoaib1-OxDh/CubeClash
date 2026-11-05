package com.oxdh.cubeclash.ui

/**
 * Snapshot of everything the HUD draws. Written by the GL thread once per frame and read by [HudView] on the UI
 * thread; both sides hold [lock] while touching it. Arrays are preallocated so a frame allocates nothing.
 */
class HudState {
    val lock = Any()

    var score = 0L
    var rank = 1
    var competitors = 1
    var leadLevel = 0
    var chainLength = 1
    var highestLevel = 0
    var boost01 = 1f
    var boosting = false
    var boostReady = true
    var danger = false
    var nearEdge = false
    var skin = "classic"
    var alive = true
    /** Multiplayer: who ate the local player, and seconds until respawn. */
    var eatenBy: String? = null
    var respawnIn = 0f
    var peopleCount = 1

    /** Player head on screen (px), for tap-to-steer. */
    @Volatile var headScreenX = 0f
    @Volatile var headScreenY = 0f
    /** Camera yaw (radians) used to rotate screen input into world directions. */
    @Volatile var cameraYaw = 0f

    // Leaderboard (top 5 + player if outside).
    val lbNames = arrayOfNulls<String>(6)
    val lbScores = LongArray(6)
    val lbRanks = IntArray(6)
    val lbIsPlayer = BooleanArray(6)
    val lbIsHuman = BooleanArray(6)
    var lbCount = 0

    // Rival name tags.
    val labelX = FloatArray(MAX_LABELS); val labelY = FloatArray(MAX_LABELS)
    val labelText = arrayOfNulls<String>(MAX_LABELS); val labelColor = IntArray(MAX_LABELS)
    var labelCount = 0

    // Minimap dots in -1..1 radar space.
    val dotX = FloatArray(MAX_DOTS); val dotY = FloatArray(MAX_DOTS); val dotColor = IntArray(MAX_DOTS); val dotSize = FloatArray(MAX_DOTS)
    var dotCount = 0
    var edgeVisible = false
    var edgeX = 0f; var edgeY = 0f

    // Floating texts ("+8", "Defeated Nova!").
    val ftX = FloatArray(MAX_FLOAT); val ftY = FloatArray(MAX_FLOAT); val ftText = arrayOfNulls<String>(MAX_FLOAT)
    val ftColor = IntArray(MAX_FLOAT); val ftAlpha = FloatArray(MAX_FLOAT); val ftSize = FloatArray(MAX_FLOAT)
    var ftCount = 0

    companion object {
        const val MAX_LABELS = 16
        const val MAX_DOTS = 96
        const val MAX_FLOAT = 24
    }
}
