package com.opennxt.model.entity.movement

/**
 * RUN ENERGY - the reserve, the toggle, and the two rates, off the reference client 949.
 */
class RunEnergy {

    /**
     * The reserve, in TENTHS of a displayed point. 0..[MAX_TENTHS].
     *
     * Tenths and not percent because the wire's percent is a projection: the reference client moves the value
     * by 7 and by 8 tenths, which is invisible in a `ubyte` and is exactly what the skip pattern
     * above measures. Tenths and not a `Double` because the whole model is integer arithmetic and
     * a float would put rounding drift into a quantity that has to be reproducible tick for tick.
     */
    @Volatile
    var tenths: Int = MAX_TENTHS
        private set

    /**
     * The run TOGGLE - varp 463's value as a boolean. True = run (varp 1), false = walk (varp 0).
     *
     * Identified from the cache, not guessed. `toplevel_v2_run_energy` is interface **326**
     * (`data/prot949/interface_names_949.sym:327`); its component 1 is the only one with a menu,
     * and row 5 of that menu is literally **"Toggle run mode"** (optmask 192 = ops 6 and 7, the
     * second being "Rest"). The `op37` script on that component is clientscript **1315**, which
     *
     * - READS varp 463 (opcode 523, operand 118528)
     * - WRITES varc 119 (opcode 1280, operand 33584896)
     * - registers an on-transmit listener on **463** (a literal `push 463`) calling script 1316,
     * which performs the same varp-463 -> varc-119 copy and re-runs the redraw
     * - and the redraw, script **1741**, branches on varc 119 against 1, 0, 3 and 4 to choose
     * the orb sprite and stamp the label "Toggle Run".
     *
     * The operand encoding is DERIVED, with a firing control rather than typed in. Sweeping all
     * 21,110 clientscripts gives 13,815 distinct 523/1280 operands, of which **13,813 have a zero
     * low byte** - so the operand is `(domain << 24) | (id << 8) | tag`, not a flat int. Splitting
     * on the domain byte: domain 0 holds 2,731 ids with a maximum of 13,537, every one a defined
     * `vars_player` row (`vars_player` has 13,267 rows, max id 13,539); domain 2 holds 5,750 ids
     * with a maximum of **8,481**, which is exactly `max(vars_client.id)` - and only 98.1% of the
     * domain-2 ids are defined varps, so the two domains are separated by the data rather than by
     * assertion. `118528` is therefore domain 0, id **463**, and `33584896` is domain 2, id 119 -
     * which is also the number sitting in that component's own `listeners` array.
     */
    @Volatile
    var toggled: Boolean = DEFAULT_TOGGLE
        private set

    /** The displayed value the client was last told, or -1 before the first send. */
    var lastSent: Int = -1
        private set

    /** What `UPDATE_RUNENERGY` would carry right now: `floor(tenths / 10)`, 0..100. */
    val displayed: Int get() = tenths / TENTHS_PER_POINT

    /**
     * Can the player afford a run step THIS tick?
     *
     * `toggled` and enough in the reserve to pay for it. [resumeTenths] is the threshold and its
     * default is [DRAIN_TENTHS] - the derived rule, "you cannot spend what you do not have" -
 * rather than a number this server invented; see that property for the alternative.
     */
    fun canRunStep(): Boolean = toggled && tenths >= resumeTenths

    /** Flips the toggle and returns the NEW value. Does not touch the reserve. */
    fun setToggled(on: Boolean): Boolean {
        toggled = on
        return toggled
    }

    /**
     * One tick of the model.
     *
     * @param ranThisTick true when the avatar advanced TWO tiles on this tick - which is what the
     * observation measures the drain against, and is [Movement.currentSpeed] `== MovementSpeed.RUN`.
     *   A run whose last odd tile degraded to a walk is NOT a run tick, and neither is a teleport.
     * @return the reserve in tenths after the tick.
     */
    fun tick(ranThisTick: Boolean): Int {
        tenths += if (ranThisTick) -drainTenths else regenTenths
        if (tenths < 0) tenths = 0
        if (tenths > MAX_TENTHS) tenths = MAX_TENTHS
        return tenths
    }

    /**
     * The value to put on the wire, or null when the client already has it.
     *
     * CONSUMING: it records what it hands back, so a caller that drops the value desynchronises
     * the client. That is the reference behaviour being reproduced - 562 frames over 2,050 ticks, not
     * one per tick - and it is also the reason [lastSent] is a field rather than a local.
     */
    fun takeSend(): Int? {
        val d = displayed
        if (d == lastSent) return null
        lastSent = d
        return d
    }

    /** Records a send made by someone else (the login burst), so [takeSend] does not repeat it. */
    fun markSent(value: Int) {
        lastSent = value
    }

    /** Restores a persisted reserve. Clamped, because a hand-edited save is not a trusted source. */
    fun restore(tenths: Int, toggled: Boolean) {
        this.tenths = tenths.coerceIn(0, MAX_TENTHS)
        this.toggled = toggled
    }

    /** True when this is exactly what a brand-new player starts with - the "do not write it" test. */
    fun isDefault(): Boolean = tenths == MAX_TENTHS && toggled == DEFAULT_TOGGLE

    companion object {
        /** A full reserve, in tenths. 100 displayed points. */
        const val MAX_TENTHS = 1000

        /** Tenths per displayed point. The wire's `ubyte` is `tenths / 10`. */
        const val TENTHS_PER_POINT = 10

        /** 7 tenths per tick on which the avatar advanced two tiles. See the class KDoc. */
        val DRAIN_TENTHS: Int =
            System.getProperty("opennxt.runenergy.drainTenths")?.toIntOrNull() ?: 7

        /** 8 tenths per tick on which it did not. See the class KDoc. */
        val REGEN_TENTHS: Int =
            System.getProperty("opennxt.runenergy.regenTenths")?.toIntOrNull() ?: 8

        /**
         * The reserve a run step must have. **Default [DRAIN_TENTHS], which is DERIVED, not invented.**
         *
         * THE HONEST GAP. The reference client's reserve was never observed below 69 in 2,004 UPDATE_RUNENERGY
 * frames across 21 sessions, so what the reference client does at zero is unknown to this server. Two
         * readings are live and the protocol separate neither:
         *
         *   (a) the server simply cannot pay for a run step it has no reserve for. That is what
         * the default does, and it is the only behaviour the two rates imply on
         *       their own. Its consequence is stated rather than hidden: at zero, +8 regen and -7
         *       drain means the player can afford a run step every OTHER tick, so movement settles
         * at 1.5 tiles/tick rather than 1. Nothing contradicts that; nothing
         *       supports it either.
         * (b) the reference client LATCHES: the run stops at zero and does not resume until some fraction of
 * the bar is back. That fraction would be **INVENTED** - this server has no
         *       measurement of it, from the cache, the binary or the wire - so it is not the
         *       default and it is not a number written into this file. An operator who wants that
         *       shape sets `-Dopennxt.runenergy.resumeTenths=<n>` and owns the number;
         *       [provenance] prints it and names it INVENTED in the boot log when it is set.
         *
         * What would settle it: one observation of the reference client account run to zero.
         */
        val RESUME_TENTHS_PROPERTY: Int? =
            System.getProperty("opennxt.runenergy.resumeTenths")?.toIntOrNull()

        /**
         * The fresh-account toggle. **Default ON (run).**
         *
         * NOT the reference client's fresh-account default - that was never observed. What IS measured is that
         * the recorded account ran throughout: across all 21 sessions there is not one stretch of
         * three consecutive ticks in which the avatar advanced exactly one tile, and 1,455 ticks
         * advanced two. ON is also what this server did before this class existed
         * (`Movement.speed` was RUN from construction), so it is the choice that changes no
         * existing behaviour for a player who never touches the orb.
         * `-Dopennxt.runenergy.default=off` starts new players walking.
         */
        val DEFAULT_TOGGLE: Boolean =
            System.getProperty("opennxt.runenergy.default")?.lowercase() != "off"

        /** varp 463 - the run toggle. See [toggled] for how it was identified. */
        const val RUN_VARP = 463

        /** Printed at boot so nobody has to read this file to know which way the model is set. */
        val PROVENANCE: String = provenance()

        private fun provenance(): String {
            val resume = RESUME_TENTHS_PROPERTY
            val zero = if (resume == null)
                "at zero a run step is simply unaffordable (DERIVED from the two rates; the reference client's " +
                    "behaviour at zero was NEVER - the reserve never fell below 69 in " +
                    "2,004 frames over 21 sessions)"
            else
                "at zero the run latches off until the reserve reaches $resume tenths - that " +
                    "threshold is INVENTED, set by -Dopennxt.runenergy.resumeTenths, and is " +
                    "not supported by any observation or cache row"
            return "run energy: reserve 0..$MAX_TENTHS tenths, wire = tenths/$TENTHS_PER_POINT. " +
                "drain $DRAIN_TENTHS tenths per " +
                "two-tile tick, regen $REGEN_TENTHS tenths per other tick, exact over ticks " +
                "1232..1336 (105 ticks, 60 frames, 45 silences, 0 wrong) with five rate controls " +
                "fired. Toggle = varp $RUN_VARP (cache: interface " +
                "326 'toplevel_v2_run_energy' component 1, menu op 6 'Toggle run mode', " +
                "clientscript 1315 -> 1741). Fresh-account default " +
                (if (DEFAULT_TOGGLE) "RUN" else "WALK") + " (NOT measured - see DEFAULT_TOGGLE). " +
                zero + "."
        }
    }

    /** Instance view of [RESUME_TENTHS_PROPERTY], so a check can drive one player at a time. */
    var resumeTenths: Int = RESUME_TENTHS_PROPERTY ?: DRAIN_TENTHS

    /** Instance view of [DRAIN_TENTHS]. */
    var drainTenths: Int = DRAIN_TENTHS

    /** Instance view of [REGEN_TENTHS]. */
    var regenTenths: Int = REGEN_TENTHS
}
