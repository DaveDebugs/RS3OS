package com.opennxt.model.world

/**
 * Where the OPEN leaf of a door stands, relative to where the shut one stood.
 */
object DoorSwing {

    /**
     * `rot` -> the outward direction of the wall edge it occupies.
     * 0 = west, 1 = north, 2 = east, 3 = south. A wrong mapping here rotates the
     * whole experiment 90 degrees and would make BOTH tile-move modes look
     * wrong, which is itself a distinguishable outcome.
     */
    private val DIR = arrayOf(-1 to 0, 0 to 1, 1 to 0, 0 to -1)

    enum class Mode(val id: String, val why: String) {
        OFF("off", "no transform - the open id is placed on the shut door's own tile and rotation"),
        LEFT("left", "one tile along DIR[(rot+1)&3], rotation unchanged (the 85.0% vs 15.6% cell)"),
        RIGHT("right", "one tile along DIR[(rot+3)&3], rotation unchanged (the 85.7% vs 15.6% cell)"),
        TURN("turn", "same tile, rotation (rot+1)&3"),
        TURN_BACK("turnback", "same tile, rotation (rot-1)&3 - the earlier analysis's answer"),
    }

    /** The tile and rotation the open leaf should be placed at. */
    data class Placement(val x: Int, val y: Int, val rotation: Int, val mode: Mode)

    val mode: Mode
        get() {
            val want = System.getProperty("opennxt.experiment.doors.swing") ?: return Mode.OFF
            return Mode.values().firstOrNull { it.id.equals(want, ignoreCase = true) } ?: Mode.OFF
        }

    val PROVENANCE: String =
        "door swing: EXPERIMENT, default OFF. a transform exists - open-variant placements " +
            "average 0.293 along-the-wall neighbours sharing their rotation against 1.667 for shut " +
            "ones, with an ordinary-wall control at 1.689 (n=147/1543/1200605, re-derived " +
            "against the complete cache import; the shut count read 1221 previously and 1530 " +
            "until then, and the control 1.677). UNMEASURED: its SIGN. " +
            "The two tile-move candidates score 85.0% and 85.7% against controls of 15.6% and 15.6% " +
            "and are OPPOSITE directions within 0.7 points of each other, so the map cannot separate " +
            "them - and never will, because of the 147 open placements exactly ONE has a shut twin " +
            "within 3 tiles and that one is a different doorway. DISPUTED: an independent audit says " +
            "the answer is a same-tile rotation and that the tile moves land in a wall 84% of the " +
            "time; re-running that here gives 14-18% against an 84-96% control, i.e. the opposite. Both " +
            "signs of the rotation and both tile moves are therefore on offer. AUTHORED the moment a " +
            "mode other than 'off' is set. Set -Dopennxt.experiment.doors.swing=left|right|turn|" +
            "turnback, open a gate, and report which looks right; that settles it and nothing in the " +
            "cache can."

    /**
     * Apply [mode] to a shut placement. Returns it unchanged under [Mode.OFF],
     * which is what makes the default a true no-op rather than a differently
     * shaped guess.
     *
     * Only WALL shapes are transformed. A free-standing "Open" loc placed as type
     * 10 is a chest or a cupboard, not a door in a wall, and moving one a tile
     * sideways would be nonsense rather than a hypothesis.
     */
    fun placementFor(shape: Int, x: Int, y: Int, rotation: Int): Placement {
        val m = mode
        if (m == Mode.OFF || shape !in WALL_SHAPES) return Placement(x, y, rotation, Mode.OFF)
        return when (m) {
            Mode.LEFT -> DIR[(rotation + 1) and 3].let { Placement(x + it.first, y + it.second, rotation, m) }
            Mode.RIGHT -> DIR[(rotation + 3) and 3].let { Placement(x + it.first, y + it.second, rotation, m) }
            Mode.TURN -> Placement(x, y, (rotation + 1) and 3, m)
            Mode.TURN_BACK -> Placement(x, y, (rotation + 3) and 3, m)
            Mode.OFF -> Placement(x, y, rotation, Mode.OFF)
        }
    }

    /** Wall shapes, the same set [com.opennxt.content.impl.Doors.WALL_TYPES] uses. */
    val WALL_SHAPES = setOf(0, 1, 2, 3, 9)
}
