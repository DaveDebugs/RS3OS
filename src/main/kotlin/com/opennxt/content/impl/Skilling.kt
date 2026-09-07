package com.opennxt.content.impl

import com.opennxt.api.stat.Stat
import com.opennxt.content.ActionSlot
import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.entity.rendering.blocks.PlayerFaceDirectionBlock
import com.opennxt.model.map.LocInteraction
import com.opennxt.resources.MiningTable949
import com.opennxt.model.world.LocChanges
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * Chopping a tree and mining a rock: the first gameplay loop this server has
 * that is neither walking nor fighting.
 *
 * The evidence for what a resource loc IS, and what it GIVES, lives in
 * [ResourceNodes] - read that first. This file is what happens when the option
 * is clicked, and almost everything in it is AUTHORED. The provenance of every
 * number is stated at its declaration and repeated in the runtime log line, in
 * the shape [com.opennxt.model.world.WorldNpcs.COMBAT_DEMO_PROVENANCE]
 * established.
 */
object Skilling {

    private val logger = KotlinLogging.logger { }

    /** Master switch. Default ON. */
    val enabled: Boolean = System.getProperty("opennxt.experiment.skilling") != "false"

    // ================================================================
    // THE AUTHORED TABLE
    // ================================================================

    /**
     * Woodcutting level and experience per log, keyed by the CACHE item name
     * the yield rule resolved to.
     *
     * **MIXED PROVENANCE, and the two halves are marked separately below.**
     *
     * The table was originally RECONSTRUCTED in full from the long-published RS3 Woodcutting rates, in
     * the same class of public documentation
     * [com.opennxt.model.world.HeadlessPlayer.COMBAT_XP_TENTHS_PER_DAMAGE] cites for combat.
     */
    val WOODCUTTING_TABLE: Map<String, Requirement> = mapOf(
        // Every LEVEL below is still reconstructed - an observation shows what a rate IS, never what it
        // would have refused at a lower level. The xp is measured wherever SkillingRates has it,
        // including the two rows where the measurement DISAGREED with the published figure; see
        // SkillingRates.WOODCUTTING_XP_TENTHS for why the measurement wins there.
        "Logs" to Requirement(1, xpFor("Logs", 250)),
        "Oak logs" to Requirement(15, xpFor("Oak logs", 375)),
        "Willow logs" to Requirement(30, xpFor("Willow logs", 675)),
        "Teak logs" to Requirement(35, 850),
        "Maple logs" to Requirement(45, 1000),
        "Mahogany logs" to Requirement(50, 1250),
        "Yew logs" to Requirement(60, 1750),
        "Magic logs" to Requirement(75, xpFor("Magic logs", 2500)),
        "Elder logs" to Requirement(90, xpFor("Elder logs", 3250))
    )

    /**
     * The measured rate for [log] if one exists, otherwise the reconstructed [fallback].
     *
     * Written as a lookup rather than as literals so that the two sources cannot drift: adding a row
     * to [SkillingRates.WOODCUTTING_XP_TENTHS] changes this table, and a row that is NOT there keeps
     * its published value and stays honestly labelled as reconstructed.
     */
    private fun xpFor(log: String, fallback: Int): Int =
        SkillingRates.WOODCUTTING_XP_TENTHS[log] ?: fallback

    /**
     * Mining has **no** per-ore table here, deliberately.
     *
     * Build 949 is post-"Mining and Smithing rework": the rock set at
     * 113026..113059 is the reworked one (Luminite, Banite, Orichalcite,
     * Drakolith, Phasmatite, Animica - none of which existed before it), so a
     * pre-rework level/xp table would be a set of confidently-labelled WRONG
     * numbers. The reworked numbers were not recovered from anything here, so
     * none are written down.
     *
     * What mining gets instead: the level from param 23 where the cache states
     * it (all 20 Daemonheim ore locs do), and [DEFAULT_MINING_LEVEL] /
     * [DEFAULT_XP_TENTHS] everywhere else, both INVENTED and both reported as
     * such in the log line for every single gather.
     *
     * ONE ROW IS NOW. A live observation of the reference client 949 client mined tin nine times, and the
     * drops alternated 8/7 - 7.5 xp per ore. That is the reworked rate, off this build's own wire, so
     * it does not carry the risk the paragraph above refuses. It is also the only rock anybody has
     * mined on camera: the other observation's mining drops ranged 41..104 on rocks that were never
     * identified, and an unattached spread is not a rate. So the table has exactly one entry, and the
     * reasoning above still governs everything not in it.
     */
    val MINING_TABLE: Map<String, Requirement> = run {
 //: THE LEVELS ARE NOW GROUNDED, THE XP IS STILL NOT.
        //
        // This used to be one row - Tin ore - at the authored level 1, because
        // the paragraph above is right that no published rate could be checked
        // against this build. It missed that the cache states the ladder
        // itself. dbtable 34 carries 91 named rock classes with
        // `[item, level, weight]` yields, and a mining loc's own name joins to
        // the class name; `tools/949/derive_mining.py` writes that join out and
        // `MiningTable949` reads it. 67 classes join, covering 586 of the 794
        // named 'Mine' locs.
        //
        // The two halves of a Requirement now have DIFFERENT provenance and it
        // matters which is which:
        //
        //   level  GROUNDED - dbtable 34 column 3 field 1. Copper 1, Tin 1,
        //          Iron 10, Coal 20, Mithril 30, Adamantite 40, Runite 50, all
        //          asserted as a control in the derivation tool, 7/7.
        // xp UNCHANGED - SkillingRates for tin (measured off this build's
        //          own wire, 7.5) and DEFAULT_XP_TENTHS (INVENTED) elsewhere.
        //          dbtable 34 states no xp and none is inferred from it.
        //
        // A cache-stated level with an invented xp beside it is still better
        // than both invented, and pretending the xp got better because the
        // level did is exactly the blur this comment exists to prevent.
        val out = LinkedHashMap<String, Requirement>()
        for ((item, level) in MiningTable949.levels()) {
            out[item] = Requirement(level, SkillingRates.MINING_XP_TENTHS[item] ?: DEFAULT_XP_TENTHS)
        }
        // Tin keeps its measured xp whether or not the cache named its level,
        // and any measured row the cache is silent about survives on the
        // authored floor rather than being dropped.
        for ((item, xp) in SkillingRates.MINING_XP_TENTHS) {
            val level = MiningTable949.levelForItem(item) ?: DEFAULT_MINING_LEVEL
            out[item] = Requirement(level, xp)
        }
        out
    }

    /**
     * A level requirement and an experience award, in tenths of an xp point. [levelSource] and
     * [xpSource] name where each half came from: CACHE / MINING-TABLE / WIKI / /
     * AUTHORED / DEFAULT) so a log line never blurs a grounded level with an invented xp again.
     */
    data class Requirement(val level: Int, val xpTenths: Int, val levelSource: String = "AUTHORED", val xpSource: String = "AUTHORED")

    /** INVENTED. A log this server has no published rate for still has to award something. */
    val DEFAULT_XP_TENTHS: Int = System.getProperty("opennxt.skilling.defaultXpTenths")?.toIntOrNull() ?: 250

    /** INVENTED. No cache field states a woodcutting level for a loc without param 23. */
    val DEFAULT_WOODCUTTING_LEVEL: Int =
        System.getProperty("opennxt.skilling.defaultWoodcuttingLevel")?.toIntOrNull() ?: 1

    /** INVENTED, and see [MINING_TABLE] for why it is 1 rather than a made-up tier. */
    val DEFAULT_MINING_LEVEL: Int =
        System.getProperty("opennxt.skilling.defaultMiningLevel")?.toIntOrNull() ?: 1

    /**
     * INVENTED. Ticks a chopped tree stays a stump.
     *
     * No cache field times a respawn - the same hole
     * [com.opennxt.model.world.GroundItems.DESPAWN_TICKS] and
     * [com.opennxt.model.world.WorldNpc.RESPAWN_TICKS] document for their own
     * timers. 60 ticks is 36 s at the 600 ms tick this server runs.
     */
    val RESPAWN_TICKS: Int = System.getProperty("opennxt.skilling.respawnTicks")?.toIntOrNull() ?: 60

    /**
     * The loc id [Stumps] falls back to for EVERY tree, when an operator names one.
     */
    val STUMP_LOC: Int? = System.getProperty("opennxt.skilling.stumpLoc")?.toIntOrNull()

    /**
     * The loc id that means "take this loc out of the scene".
     *
     * This is the value a [Depletion] RECORDS for a tree with no grounded stump. It is not a loc
     * id and never goes on the wire as one.
     */
    const val REMOVE_LOC = -1

    /**
     * INVENTED. The item-name suffix that makes an item a woodcutting tool.
     *
     * Cache strings, authored rule - the same construction as the yield rule.
     * The starter backpack ships 'Bronze hatchet' (1351) and 'Bronze pickaxe'
     * (1265) already ([com.opennxt.model.entity.player.PlayerInventory.starterKit]),
     * so a fresh account passes both gates without anything being handed to it
     * here.
     */
    const val HATCHET_SUFFIX = " hatchet"

    /** INVENTED, as [HATCHET_SUFFIX]. */
    const val PICKAXE_SUFFIX = " pickaxe"

    /**
     * The single string every log line carries, so a reader never has to come
     * to this file to find out how much of what they just read was made up.
     */
    const val PROVENANCE =
        "PROVENANCE: GROUNDED = the action string ('Chop down'/'Chop'/'Mine'), the loc name, the item " +
            "name, and the level where locs_attr param 23 states one (199 locs). AUTHORED = the loc->item " +
            "name rule (245/345 chop locs, 352/777 mine locs; control on non-resource locs: 0 of 345 and 1 " +
            "of 777 on a deterministic stride). " +
            ".0), Oak logs (37.5), Willow logs " +
            "(67.5), Magic logs (365.0), Elder logs (425.0) and Tin ore (7.5), each from a live " +
            "observation; Logs and Oak agree across two accounts 60 levels apart, and Magic and Elder " +
            "CONTRADICT the published figures this table used to carry. See SkillingRates. " +
            "off the wire = the STUMP a chopped tree becomes, per tree id, from " +
            "LOC_ADD_CHANGE frames (data/seed/tree_stumps_observed.tsv; the " +
            "the single authored 1342 is not used). A tree with no measured " +
            "stump is REMOVED for the window, never given a guessed id, and the removal goes out " +
            "as LOC_DEL because that is the opcode the protocol shows (0 of 2,882 LOC_ADD_CHANGE " +
            "frames carry loc = -1; 77 LOC_DEL frames do). " +
            "off the wire = a chopped tree is TWO locs, and its CANOPY - the " +
            "actionless multi-tile loc one plane up whose footprint covers the trunk - is deleted " +
            "with the stump and restored with the tree, as the reference client does in the same tick. Derived " +
            "per placed loc from map_loc (7,344 of 16,112 choppable placements have one; the " +
            "wrong-plane and wrong-tile controls fire at 0.1-3.4%). See Skilling.Canopy. " +
            "INVENTED = every OTHER xp rate, respawn ticks, the tool requirement, and " +
            "one-click-one-gather. No cache field states any of those; see Skilling, SkillingRates and " +
            "ResourceNodes."

    /**
     * The one-line form, for PER-EVENT log lines.
     *
     * [PROVENANCE] is printed ONCE, by [install] at boot. It was previously
     * appended to every gather and every respawn as well, which meant two
     * chopped trees emitted it three times and the facts that differ per event
     * -- which loc, which item, how much xp -- sat inside a paragraph of
     * identical prose. Short here, in full at boot; still explicit that the
     * yield rule is ours, so no single line reads as a measurement.
     */
    const val PROVENANCE_SHORT =
        "[yield AUTHORED, respawn ticks INVENTED, stump per tree id (or the tree is REMOVED), " +
            "xp for Logs/Oak/Willow/Magic/Elder logs " +
            "and Tin ore, reconstructed elsewhere - full provenance at boot]"

    // ================================================================
    // WHAT A GATHER DID
    // ================================================================

    enum class Outcome {
        /** An item went into the container. */
        GATHERED,

        /** No hatchet/pickaxe held. INVENTED gate - see [HATCHET_SUFFIX]. */
        NO_TOOL,

        /** Level below the requirement. GROUNDED when [Gather.levelFromCache] is true, else AUTHORED. */
        LEVEL_TOO_LOW,

        /** The name rule resolved no item for this loc. Refused rather than substituted. */
        NO_YIELD,

        /** This tile's node is a stump right now and has not respawned. */
        DEPLETED,

        /** Container full. */
        NO_SPACE,

        /** No definitions database. */
        NO_DATABASE,
        /**
         * The player already has this node's action running and its next cycle is not due
         *: a re-click inside the cycle pays nothing.
         * Before this a click was a whole gather, so a client could mint 128 logs a tick.
         */
        REPEATING,
        /** The cycle's success roll failed: nothing paid, the action continues. [successChance]. */
        MISSED,
        /**
         * The click STARTED the action and paid nothing yet: the reference client's first
         * yield comes [firstCycleTicks] after the click (mining +2: face on the click tick, the
         * swing animation on T+1, the ore on T+2, 0 of 20 on the click tick; woodcutting +3 from
         * the "You swing your hatchet" message). The cycle then repeats every [CYCLE_TICKS].
         */
        STARTED,
        /** The held tool's own level requirement (item param 750) is above the player's level (I-48). */
        TOOL_LEVEL_TOO_LOW,

        /**
         * `map_loc` places no loc of this id at (or covering) the clicked tile, so
         * there is nothing to gather from. (CODE-REVIEW-FULL #1b):
         * a forged OPLOC used to reach the payout with `placement == null` and be
         * paid the item and the xp with nothing to deplete.
         */
        NO_PLACEMENT
    }

    /**
     * One gather attempt, fully described.
     *
     * Every refusal carries the numbers behind it, so a log line or a check can
     * assert on a value rather than on the absence of an item.
     */
    data class Gather(
        val outcome: Outcome,
        val locId: Int,
        val locName: String?,
        val kind: ResourceNodes.Kind,
        val itemId: Int? = null,
        val itemName: String? = null,
        val amount: Int = 0,
        val xpTenths: Int = 0,
        val chance: Double? = null,
        val levelSource: String? = null,
        val xpSource: String? = null,
        val stat: Stat? = null,
        val levelRequired: Int = 1,
        val levelFromCache: Boolean = false,
        val playerLevel: Int = 1,
        val toolId: Int? = null,
        val depletedTo: Int? = null,
        val respawnAtTick: Long = -1,
        /** Whether the backpack was pushed to the client. False headless; see [inventoryResend]. */
        val inventorySent: Boolean = false,
        val detail: String = ""
    ) {
        val gathered: Boolean get() = outcome == Outcome.GATHERED
        override fun toString() = buildString {
            append("Gather(").append(outcome).append(" loc ").append(locId).append(" '").append(locName)
            append("' ").append(kind)
            if (itemId != null) append(" -> ").append(amount).append("x ").append(itemId).append(" '").append(itemName).append("'")
            if (xpTenths > 0) append(" +").append(xpTenths / 10.0).append(" ").append(stat).append(" xp")
            append(" level ").append(playerLevel).append("/").append(levelRequired)
            append(if (levelFromCache) " [level GROUNDED: param 23]" else " [level ${levelSource ?: "AUTHORED"}]")
            if (xpSource != null) append(" [xp ").append(xpSource).append("]")
            if (chance != null) append(" [chance %.3f]".format(chance))
            if (depletedTo != null) {
                if (depletedTo == REMOVE_LOC) append(" REMOVED (no grounded stump) until tick ").append(respawnAtTick)
                else append(" depleted->").append(depletedTo).append(" until tick ").append(respawnAtTick)
            }
            if (detail.isNotEmpty()) append(" ").append(detail)
            append(")")
        }
    }

    // ================================================================
    // SEAMS: where the container, the level and the xp really live
    // ================================================================

    /**
     * Where a gathered item goes.
     *
     * Default is the [ContentPlayer]'s own inventory, which is what a headless
     * check drives. The live server overrides it with the real backpack
     * ([com.opennxt.model.entity.player.PlayerInventory.backpackOf]), the same
     * supplier seam [Banks.bankSupplier] uses and for the same reason: this
     * package must not depend on `WorldPlayer`.
     */
    @Volatile
    var containerSupplier: (ContentPlayer) -> ItemContainer = { it.inventory }

    /**
     * The player's WORN container, or null headless , the first live chop: he had
     * WIELDED the bronze hatchet, the scan looked in the backpack only, every click was NO_TOOL).
     * The reference client accepts a wielded hatchet or pickaxe; so does [toolIn] now, scanning both. Bound by
     * SkillingWiring to the live worn container.
     */
    @Volatile
    var wornSupplier: (ContentPlayer) -> ItemContainer? = { null }

    /**
     * THE TOOL BELT'S BASE TIER. :
     */
    val TOOLBELT_BASE: Map<ResourceNodes.Kind, Int> = mapOf(
        ResourceNodes.Kind.WOODCUTTING to 1351,   // Bronze hatchet
        ResourceNodes.Kind.MINING to 1265,        // Bronze pickaxe
    )
    val DEFAULT_TOOLBELT: Boolean = System.getProperty("opennxt.skilling.toolbelt") != "off"
    @Volatile
    var toolbelt: Boolean = DEFAULT_TOOLBELT

    /**
     * WHAT THIS PLAYER PUT ON THEIR TOOL BELT THEMSELVES.
     */
    @Volatile
    var beltSupplier: (ContentPlayer) -> Set<Int> = { emptySet() }

 // ------------------------------------------------------------- the action cycle
    //
    // Skilling-actions review: a gather was ONE CLICK = ONE ITEM, with no cadence, no success
    // roll and no stop condition, so a repeated OPLOC minted an item per click up to the 128
    // per tick InboundDrainLimit allows. It is an ACTION now: the click starts it and pays the
    // [CYCLE_TICKS] while the player stands where they started, the node is not depleted and
    // there is space; a click inside the cycle is [Outcome.REPEATING] and pays nothing.

    /**
     * Ticks between cycles: 4.
     */
    val CYCLE_TICKS: Int = System.getProperty("opennxt.skilling.cycleTicks")?.toIntOrNull() ?: 4

    val DEFAULT_FIRST_CYCLE: (ResourceNodes.Kind) -> Int = { kind ->
        System.getProperty("opennxt.skilling.firstCycle")?.toIntOrNull() ?: when (kind) {
            ResourceNodes.Kind.MINING -> 2
            ResourceNodes.Kind.WOODCUTTING -> 3
            ResourceNodes.Kind.GATHERING -> 0
        }
    }
    var firstCycleTicks: (ResourceNodes.Kind) -> Int = DEFAULT_FIRST_CYCLE

    /**
     * The player's gathering animation, all FOUR slots: the reference client sends the
     * PLAYER_INFO ANIMATION block with the id in all four ints, delay 0, EVERY cycle, one tick
     * before the yield (mining 17310 x9 read; fishing 32011 x10 - not a skill here yet).
     */
    fun animationFor(kind: ResourceNodes.Kind): IntArray? = when (kind) {
        ResourceNodes.Kind.MINING -> intArrayOf(17310, 17310, 17310, 17310)
        ResourceNodes.Kind.WOODCUTTING -> intArrayOf(21191, 21191, 21191, 21191)
        else -> null
    }

    /** True when the reference client re-sends the block every tick of the action (woodcutting); false = once per cycle, the tick before the yield (mining). */
    fun animationEveryTick(kind: ResourceNodes.Kind): Boolean = kind == ResourceNodes.Kind.WOODCUTTING

    private val STOP_ANIMATION = intArrayOf(-1, -1, -1, -1)

    /**
     * The reference client's line on the tick the action STARTS, once per click (, 4 of 4 trees,
     * on the arrival tick, never repeated for a failed roll): "You swing your hatchet at the tree."
     * Mining: NOT (the 09-05 mining observation was read for yields, not for a start line), so
     * nothing is sent rather than a guess.
     */
    fun startMessage(kind: ResourceNodes.Kind): String? = when (kind) {
        ResourceNodes.Kind.WOODCUTTING -> "You swing your hatchet at the tree."
        else -> null
    }
    var animationSink: (ContentPlayer, IntArray) -> Unit = { _, _ -> }
    var messageSink: (ContentPlayer, String) -> Unit = { _, _ -> }

    /**
     * "Turn to look at the node." The angle only - the seam takes a number so a headless check can
     * assert what was computed without a wire, exactly as [animationSink] does for the four ids.
     */
    var faceSink: (ContentPlayer, Int) -> Unit = { _, _ -> }
    private var animationsSent = 0
    private var messagesSent = 0
    private var facesSent = 0
    fun animationsSent(): Int = animationsSent
    fun messagesSent(): Int = messagesSent
    fun facesSent(): Int = facesSent

    /**
     * Turns [player] toward the loc footprint at ([originX], [originZ]) sized [sizeX] x [sizeZ],
     * and returns the angle it sent - or null when there was nothing to send.
     *
     * Null happens for exactly one reason worth having: a player standing ON the target's centre
     * has no direction, and [PlayerFaceDirectionBlock.towards] would answer 0 (due south) for it.
     * That is a real angle and would spin the player, so it is refused here instead.
     */
    fun faceLoc(player: ContentPlayer, originX: Int, originZ: Int, sizeX: Int, sizeZ: Int): Int? {
        val from = whereIs(player)
        if (2 * from.x + 1 == 2 * originX + sizeX && 2 * from.y + 1 == 2 * originZ + sizeZ) return null
        val angle = PlayerFaceDirectionBlock.towards(from.x, from.y, originX, originZ, sizeX, sizeZ)
        runCatching { faceSink(player, angle) }
        facesSent++
        return angle
    }

    /**
     * Per-player throws contained by [tick]'s own try/finally/catch.
     */
    @Volatile private var containedFailures = 0
    fun containedFailures(): Int = containedFailures

    /** The reference client's per-yield chat line. */
    fun yieldMessage(kind: ResourceNodes.Kind, itemName: String): String? = when (kind) {
        ResourceNodes.Kind.WOODCUTTING -> "You get some ${itemName.lowercase()}."
        else -> null
    }

    /** What one cycle's roll knows. [toolName] is the cache item name of the tool in use. */
    data class SuccessContext(
        val kind: ResourceNodes.Kind, val itemName: String, val level: Int, val requiredLevel: Int,
        val toolId: Int, val toolName: String?, val toolLevel: Int?
    )

    /**
     * The success chance of one cycle, clamped to [0, 1], as a seam a check can replace and
     * [random] is injectable so a check can pin a seeded stream.
     */
    val DEFAULT_SUCCESS_CHANCE: (SuccessContext) -> Double = { c ->
        if (System.getProperty("opennxt.skilling.chance") == "off") 1.0
        else when (c.kind) {
            ResourceNodes.Kind.WOODCUTTING ->
                c.toolName?.let { SkillXpWiki.woodcuttingChance(c.itemName, it, c.level) } ?: 1.0
            else -> 1.0
        }
    }
    var successChance: (SuccessContext) -> Double = DEFAULT_SUCCESS_CHANCE

    /**
     * Chance that a successful chop stumps the tree: the wiki's `fell`/256 per tree
     * ([SkillXpWiki.fellChance]; Tree 96/256, Oak 32/256, Yew 8/256 ...); null = every chop stumps
     * (the rule, kept for trees the wiki does not name and under
     * `-Dopennxt.skilling.fell=off`). A seam so a check can force either.
     */
    val DEFAULT_FELL_CHANCE: (String) -> Double? = { logs ->
        if (System.getProperty("opennxt.skilling.fell") == "off") null else MEASURED_FELL[logs] ?: SkillXpWiki.fellChance(logs)
    }

    val MEASURED_FELL: Map<String, Double> = mapOf("Logs" to 1.0)
    var fellChance: (String) -> Double? = DEFAULT_FELL_CHANCE
    var random: java.util.Random = java.util.Random(0x534b494cL)   // "SKIL"; reseeded by checks

    data class Active(
        val ctx: LocContext,
        val kind: ResourceNodes.Kind,
        val startTile: TileLocation,
        var nextTick: Long,
        var cycles: Int = 0
    )
    private val active = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<ContentPlayer, Active>())

    private val lastCycle: MutableMap<ContentPlayer, Long> =
        java.util.Collections.synchronizedMap(WeakHashMap<ContentPlayer, Long>())
    fun lastCycleOf(player: ContentPlayer): Long? = lastCycle[player]

    fun stopFor(player: ContentPlayer, why: String) = stop(player, why)
    fun activeCount(): Int = active.size
    fun activeFor(player: ContentPlayer): Active? = active[player]
    internal fun clearActions() { active.clear(); lastCycle.clear() }
    private var cyclesPaid = 0
    private var cyclesMissed = 0
    private var actionsStopped = 0
    fun cyclesPaid(): Int = cyclesPaid
    fun cyclesMissed(): Int = cyclesMissed
    fun actionsStopped(): Int = actionsStopped

    /** Where the player is now: the bound WorldPlayer's entity when there is one, else the content location. */
    private fun whereIs(player: ContentPlayer): TileLocation =
        SkillingWiring.ownerOf(player)?.entity?.location?.let { TileLocation(it.x, it.y, it.plane) } ?: player.location

    /**
     * This module's seat in the one-action-per-player slot.
     */
    internal object SLOT : ActionSlot.Owner {
        override val actionName = "skilling"
        override fun cancelSlot(player: ContentPlayer, why: String) = stop(player, why)
    }

    /**
     * Registers a live action AND takes the player's action slot.
     */
    private fun arm(player: ContentPlayer, a: Active) {
        active[player] = a
        ActionSlot.claim(player, SLOT)
    }

    private fun stop(player: ContentPlayer, why: String) {
        val was = active.remove(player)
        // Released whether or not an action was running: the identity test inside makes a release
        // by a module that does not hold the slot free, and this way no stop path can leak a slot.
        ActionSlot.release(player, SLOT)
        if (was != null) {
            actionsStopped++
            logger.info { "skilling: ${player.name}'s action stopped - $why" }
 // an every-tick animation ends with the four-slot -1 block
            if (animationEveryTick(was.kind)) { runCatching { animationSink(player, STOP_ANIMATION) }; animationsSent++ }
        }
    }

    /** The acting player's level in a skill. Default 1; the live server supplies the real one. */
    @Volatile
    var levelSupplier: (ContentPlayer, Stat) -> Int = { _, _ -> 1 }

    /**
     * Where xp goes on top of [xpAwarded].
     *
     * Default is a no-op. The live server points it at
     * `PlayerStatContainer.addExperience`, whose UPDATE_STAT write is already
     * behind the crash gate - see the class doc. Nothing here sends a packet.
     */
    @Volatile
    var xpSink: (ContentPlayer, Stat, Double) -> Unit = { _, _, _ -> }

    /**
     * Tells the client its backpack changed. Returns whether anything was sent.
     *
     * Default false - a headless check has no channel. The live server points
     * this at [com.opennxt.model.entity.player.PlayerInventory.sendBackpack],
     * which is the same UPDATE_INV_FULL the login path sends and which owns its
     * own "no opcode on this build" and "experiment off" degradations. A log
     * that lands in the container but is never re-sent is a log the player
     * cannot see, so this fires on every successful gather - and ONLY on a
     * successful one, so a refusal costs no bandwidth.
     */
    @Volatile
    var inventoryResend: (ContentPlayer) -> Boolean = { false }

    /**
     * Every tenth of xp this module has awarded, per player and stat.
     *
     * Kept regardless of [xpSink] so a headless check has something to assert
     * on, and weakly keyed so a logout cannot leak one.
     */
    private val awarded: MutableMap<ContentPlayer, MutableMap<Stat, Int>> =
        Collections.synchronizedMap(WeakHashMap())

    fun xpAwarded(player: ContentPlayer, stat: Stat): Int =
        synchronized(awarded) { awarded[player]?.get(stat) ?: 0 }

    private fun record(player: ContentPlayer, stat: Stat, tenths: Int) {
        synchronized(awarded) {
            val map = awarded.getOrPut(player) { java.util.EnumMap(Stat::class.java) }
            map[stat] = (map[stat] ?: 0) + tenths
        }
    }

    /** Test seam: forgets every accrued award. */
    internal fun clearAwards() = synchronized(awarded) { awarded.clear() }

    // ================================================================
    // THE STUMP TABLE
    // ================================================================

    /**
     * Which loc a chopped tree becomes:, with one derived
     * step, and no invented id anywhere.
     */
    object Stumps {

        /** The name every stump in this cache carries; there is no 'Oak stump' or 'Willow stump'. */
        const val STUMP_NAME = "Tree stump"

        /** How a tree's stump was arrived at. */
        enum class Source {
            /** A row of `data/seed/tree_stumps_observed.tsv`: seen. */
            MEASURED,

            /** The measured offset of this repository's own contiguous same-name chop-run. */
            RUN,

            /** `-Dopennxt.skilling.stumpLoc` named one id for everything. */
            FORCED
        }

        data class Stump(val locId: Int, val source: Source, val observations: Int)

        /** Read per call so a check can flip it. */
        val enabled: Boolean get() = System.getProperty("opennxt.skilling.stumps") != "off"

        private val seedPath = com.opennxt.Constants.DATA_PATH.resolve("seed").resolve("tree_stumps_observed.tsv")

        /** tree loc id -> (stump loc id, observation count), straight off the TSV. */
        val measured: Map<Int, Pair<Int, Int>> by lazy { loadSeed() }

        private fun loadSeed(): Map<Int, Pair<Int, Int>> {
            if (!java.nio.file.Files.isRegularFile(seedPath)) {
                logger.warn {
                    "skilling: no stump seed at $seedPath - EVERY chopped tree will be REMOVED for its " +
                        "respawn window instead of becoming a stump."
                }
                return emptyMap()
            }
            val out = LinkedHashMap<Int, Pair<Int, Int>>()
            java.nio.file.Files.newBufferedReader(seedPath).useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#") || line.startsWith("tree_loc")) continue
                    val p = line.split('\t')
                    if (p.size < 5) continue
                    val tree = p[0].trim().toIntOrNull() ?: continue
                    val stump = p[2].trim().toIntOrNull() ?: continue
                    out[tree] = stump to (p[4].trim().toIntOrNull() ?: 0)
                }
            }
            logger.info {
                "skilling: stump seed $seedPath -> ${out.size} tree->stump pair(s) " +
                    "(${out.entries.joinToString { "${it.key}->${it.value.first}x${it.value.second}" }}). " +
                    "Every other tree is REMOVED while depleted; nothing is guessed."
            }
            return out
        }

        private val memo = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<Stump>>()

        /** Test seam: forget the resolved table so a property flip is visible. */
        internal fun clearMemo() = memo.clear()

        /**
         * The maximal contiguous run of ids around [locId] that share its name AND declare a chop
         * action. Derived from the cache every time it is asked for, never typed.
         */
        fun chopRun(locId: Int): List<Int> {
            val name = nameOf(locId) ?: return listOf(locId)
            var lo = locId
            while (isChoppableNamed(lo - 1, name)) lo--
            var hi = locId
            while (isChoppableNamed(hi + 1, name)) hi++
            return (lo..hi).toList()
        }

        private fun nameOf(locId: Int): String? =
            com.opennxt.resources.sqlite.SqliteLocCodec.load(locId)?.name?.takeIf { it.isNotEmpty() }

        private fun isChoppableNamed(locId: Int, name: String): Boolean {
            val def = com.opennxt.resources.sqlite.SqliteLocCodec.load(locId) ?: return false
            if (def.name != name) return false
            return def.actions.any { it != null && ResourceNodes.isChopAction(it) }
        }

        /** The stump [locId] becomes, or null when nothing grounded names one. */
        fun stumpFor(locId: Int): Stump? = memo.computeIfAbsent(locId) {
            java.util.Optional.ofNullable(resolve(locId))
        }.orElse(null)

        private fun resolve(locId: Int): Stump? {
            STUMP_LOC?.let { return Stump(it, Source.FORCED, 0) }
            if (!enabled) return null
            measured[locId]?.let { (stump, n) -> return Stump(stump, Source.MEASURED, n) }
            if (!RsDatabase.available) return null
            val run = chopRun(locId)
            if (run.size < 2) return null
            val offsets = run.mapNotNull { measured[it]?.first?.minus(it) }.toSet()
            if (offsets.size != 1) return null
            val candidate = locId + offsets.first()
            if (nameOf(candidate) != STUMP_NAME) return null
            return Stump(candidate, Source.RUN, 0)
        }

        /**
         * Every choppable loc that resolves, derived live. For the install log and for
         * - never an authored list.
         */
        fun coverage(): Map<Int, Stump> {
            val out = LinkedHashMap<Int, Stump>()
            for (action in listOf(ResourceNodes.CHOP_DOWN, ResourceNodes.CHOP_DOWN_HYPHEN, ResourceNodes.CHOP)) {
                for (id in ResourceNodes.locsDeclaring(action)) {
                    if (out.containsKey(id)) continue
                    stumpFor(id)?.let { out[id] = it }
                }
            }
            return out
        }
    }

    // ================================================================
    // THE CANOPY - the half of a tree this server used to leave standing
    // ================================================================

    /**
     * The loc ABOVE a tree: its leaves.
     */
    object Canopy {

        /** Off restores the old behaviour: the trunk changes, the leaves stay. */
        val enabled: Boolean get() = System.getProperty("opennxt.skilling.canopy") != "off"

        /**
         * The canopy above [trunk], or null when this placement has none.
         *
         * One bounded `map_loc` query through [LocInteraction.placementsCovering], paid once per
         * fell - the same per-click cost the click itself already pays, never a per-tick one.
         */
        fun above(trunk: LocInteraction.Placed, plane: Int): LocInteraction.Placed? {
            if (!enabled) return null
            if (!RsDatabase.available) return null
            if (plane >= 3) return null
            val candidates = LocInteraction.placementsCovering(trunk.originX, trunk.originZ, plane + 1)
                .filter { it.locId != trunk.locId && it.tiles > 1 && it.rot == trunk.rot && !interactable(it.locId) }
            if (candidates.isEmpty()) return null
            // 17 of 7,344 placements offer more than one. The order is fixed rather than
            // arbitrary so that two runs of the same fell delete the same loc: the trunk's own
            // name first (95.6% of canopies share it), then the smallest footprint, then the
            // lowest id - the same tie-break LocInteraction.placementsCovering already uses.
            val trunkName = nameOf(trunk.locId)
            return candidates.sortedWith(
                compareBy(
                    { if (trunkName != null && nameOf(it.locId) == trunkName) 0 else 1 },
                    { it.tiles },
                    { it.locId }
                )
            ).first()
        }

        /** Whether the loc declares ANY option. A canopy declares none; that is what makes it scenery. */
        private fun interactable(locId: Int): Boolean =
            com.opennxt.resources.sqlite.SqliteLocCodec.load(locId)
                ?.actions?.any { !it.isNullOrEmpty() } ?: false

        /** The loc's name, or null. Internal so the fell's log line can say what it took down. */
        internal fun nameOf(locId: Int): String? =
            com.opennxt.resources.sqlite.SqliteLocCodec.load(locId)?.name?.takeIf { it.isNotEmpty() }
    }

    /** What was removed above a felled tree, so the respawn can put it back and a check can pin it. */
    data class RemovedCanopy(
        val key: LocChanges.Key,
        val locId: Int,
        val rotation: Int
    )

    // ================================================================
    // DEPLETION STATE
    // ================================================================

    /**
     * A node that is currently a stump, and when it stops being one.
     *
     * [depletedLocId] is [REMOVE_LOC] when [Stumps] could not ground a stump for this repository - the
     * loc is taken out of the scene for the window instead. [stumpSource] records which layer
     * answered, so a log line and a check can tell a measured swap from a removal.
     */
    data class Depletion(
        val key: LocChanges.Key,
        val originalLocId: Int,
        val depletedLocId: Int,
        val rotation: Int,
        val respawnAtTick: Long,
        val stumpSource: Stumps.Source? = null,
        /**
         * The leaves that came down with the trunk, or null when this repository has none in `map_loc`.
         * Held so [respawn] can put them back and so a check can pin the arguments of the
         * `LOC_DEL` without a running protocol table. See [Canopy].
         */
        val canopy: RemovedCanopy? = null
    ) {
        /** True when nothing grounded a stump and the tree was removed from the scene instead. */
        val removed: Boolean get() = depletedLocId == REMOVE_LOC
    }

    private val depleted = LinkedHashMap<LocChanges.Key, Depletion>()

    /**
     * This module's own tick counter, advanced by [tick].
     *
     * Not `World.ticks()`: a headless check has no World, and a respawn model
     * whose clock only exists inside a running server is a model no check can
     * exercise. The live server calls [tick] once per world tick, so the two
     * clocks are the same clock.
     */
    @Volatile
    private var tickCount: Long = 0

    fun ticks(): Long = tickCount

    fun depletionAt(plane: Int, x: Int, z: Int, shape: Int): Depletion? =
        synchronized(depleted) { depleted[LocChanges.Key(plane, x, z, shape)] }

    fun depletedCount(): Int = synchronized(depleted) { depleted.size }

    /** Test seam: forget every depletion and reset the clock. */
    internal fun clearDepletions() {
        synchronized(depleted) { depleted.clear() }
        tickCount = 0
    }

    /**
     * One world tick: respawn everything whose timer has run out.
     *
     * Returns how many nodes respawned, so the caller - and a check - can
     * assert on a number rather than on a side effect. Restoring the loc is
     * `LocChanges.change(..., originalId, originalId)`: [LocChanges] keeps the
     * original id on the [LocChanges.Change] it already holds, so writing the
     * original id back both re-states the scenery to every client in range and
     * leaves the recorded change self-consistent. No new API was added to
     * [LocChanges] for this.
     */
    fun tick(): Int {
        tickCount++
        // the running actions: cancel on movement, pay a cycle when due
        if (active.isNotEmpty()) {
            for ((player, a) in ArrayList(active.entries).map { it.key to it.value }) {
              try {
                val here = whereIs(player)
                if (here.x != a.startTile.x || here.y != a.startTile.y || here.plane != a.startTile.plane) {
                    stop(player, "moved from (${a.startTile.x},${a.startTile.y}) to (${here.x},${here.y})"); continue
                }
                val everyTick = animationEveryTick(a.kind)
                if (!everyTick && tickCount == a.nextTick - 1) {
                    animationFor(a.kind)?.let { ids -> runCatching { animationSink(player, ids) }; animationsSent++ }
                }
                if (tickCount < a.nextTick) {
 //woodcutting's 21191 rides every tick between yields
                    if (everyTick) animationFor(a.kind)?.let { ids -> runCatching { animationSink(player, ids) }; animationsSent++ }
                    continue
                }
                val g = gather(a.ctx, a.kind, fromCycle = true)
                when (g.outcome) {
                    Outcome.GATHERED, Outcome.MISSED -> {
                        a.nextTick = tickCount + CYCLE_TICKS; a.cycles++
                        // still running after the roll (the tree stands): the block again on the yield tick;
                        // a fell stopped the action inside gather() and sent the stop block instead
                        if (everyTick && active.containsKey(player)) animationFor(a.kind)?.let { ids -> runCatching { animationSink(player, ids) }; animationsSent++ }
                    }
                    else -> stop(player, "cycle refused: ${g.outcome} ${g.detail}")
                }
              } catch (t: Throwable) {
                // Contained per PLAYER, so one bad action cannot stop the players behind it in the
                // iteration order. The action is stopped rather than left armed: it threw once and
                // nothing here knows whether its state is still coherent, and leaving it would
                // re-enter the same throw every cadence. `stop` is itself wrapped because it sends.
                containedFailures++
                runCatching { stop(player, "contained a throw: ${t.javaClass.simpleName}") }
                logger.error(t) {
                    "skilling: ${player.name}'s ${a.kind} action threw and was CONTAINED and stopped - " +
                        "the rest of this tick's actions continue (contained so far: $containedFailures)"
                }
              }
            }
        }
        val due = synchronized(depleted) {
            depleted.values.filter { it.respawnAtTick <= tickCount }.also { list ->
                list.forEach { depleted.remove(it.key) }
            }
        }
        for (d in due) {
            respawn(d)
            logger.info {
                "skilling: loc ${d.originalLocId} respawned at (${d.key.x},${d.key.y},plane ${d.key.plane}) " +
                    "after $RESPAWN_TICKS ticks (INVENTED timer); it had been " +
                    (if (d.removed) "REMOVED from the scene (no grounded stump)" else "stump ${d.depletedLocId} [${d.stumpSource}]") +
                    ". $PROVENANCE_SHORT"
            }
        }
        return due.size
    }

    /**
     * Puts one depleted node back.
     *
     * [LocChanges.revert] rather than `change(..., originalId, originalId)`: the latter leaves a
     * no-op entry in `LocChanges.changes` forever, and that map is replayed in full to every player
     * whose scene rebuilds, so an hour of chopping turned into several hundred dead frames on every
     * scene boundary crossing. See that function's KDoc. `revert` returns null when there was no
     * recorded change - which is what a HEADLESS check sees, because `change` never got as far as
     * recording anything - so this falls back to the old call in that case and the counters keep
     * counting exactly what they counted before.
     */
    private fun respawn(d: Depletion) {
        d.canopy?.let { c ->
            // The reference client puts the leaves back with a plain LOC_ADD_CHANGE in the same tick as the
            // trunk (Skilling.Canopy), which is exactly what LocChanges.revert sends. Counted
            // separately from the trunk so the trunk's own numbers keep meaning what they meant.
            canopyAttempts++
            runCatching { LocChanges.revert(c.key.plane, c.key.x, c.key.y, c.key.shape) }
                .onFailure {
                    canopyFailures++
                    if (!canopyWarned) {
                        canopyWarned = true
                        logger.warn {
                            "skilling: the canopy ${c.locId} at ${c.key} could not be put back - " +
                                "${it::class.simpleName}: ${it.message}. Server-side state is still correct; " +
                                "only the client was not told. EXPECTED headless. (Warned once.)"
                        }
                    }
                }
                .getOrNull()?.let { canopyApplied++ }
        }
        locChangeAttempts++
        val reverted = runCatching {
            LocChanges.revert(d.key.plane, d.key.x, d.key.y, d.key.shape)
        }.onFailure {
            locChangeFailures++
            if (!locChangeWarned) {
                locChangeWarned = true
                logger.warn {
                    "skilling: the respawn of loc ${d.originalLocId} at ${d.key} could not be built - " +
                        "${it::class.simpleName}: ${it.message}. The server-side depletion state is still " +
                        "correct; only the client was not told. EXPECTED with no running protocol table. (Warned once.)"
                }
            }
        }.getOrNull()
        if (reverted != null) locChangeApplied++
    }

    /**
     * Asks [LocChanges] to restate a loc, and COUNTS what happened.
     *
     * The counters exist because of a measured property of this repository, not for
     * decoration: [LocChanges.change] consults `OpenNXT.protocol` for the
     * `LOC_ADD_CHANGE` opcode, and `protocol` is a `lateinit` that only a
     * running server initialises. So in a headless check the call throws
     * `UninitializedPropertyAccessException` before it can send anything - which
     * means "the tree turned into a stump on the client" is NOT assertable from
     * a check, and pretending otherwise would be a control that cannot fire.
     *
     * What IS assertable, and what these three numbers make assertable:
     *
     *  - [locChangeAttempts] - the swap path was REACHED with real arguments;
     *  - [locChangeApplied]  - [LocChanges] accepted and recorded the change
     *    (non-null return), i.e. it would go on the wire. Zero headless, and the
     *    check asserts zero headless rather than hiding it;
     *  - [locChangeFailures] - the call threw. Also asserted.
     *
     * The ARGUMENTS are separately assertable off [Depletion] (shape, rotation,
     * both loc ids and the tile), which are exactly the fields
     * `LOC_ADD_CHANGE` carries, so the packet's contents are pinned even where
     * the packet cannot be built.
     */
    private fun sendLocChange(key: LocChanges.Key, rotation: Int, originalId: Int, newId: Int, why: String) {
        locChangeAttempts++
        val outcome = runCatching {
 //a REMOVAL goes out as LOC_DEL, the mechanism the protocol actually
            // shows (0 of 2,882 LOC_ADD_CHANGE frames carry loc = -1; 77 LOC_DEL frames do the
            // job). A STUMP is still a plain LOC_ADD_CHANGE, unchanged. See REMOVE_LOC.
            if (newId == REMOVE_LOC) LocChanges.remove(
                plane = key.plane, x = key.x, y = key.y, shape = key.shape,
                rotation = rotation, originalId = originalId
            ) else LocChanges.change(
                plane = key.plane, x = key.x, y = key.y, shape = key.shape,
                rotation = rotation, originalId = originalId, newId = newId
            )
        }
        outcome.onFailure {
            locChangeFailures++
            if (!locChangeWarned) {
                locChangeWarned = true
                logger.warn {
                    "skilling: LOC_ADD_CHANGE ($why) could not be built for loc $originalId at $key - " +
                        "${it::class.simpleName}: ${it.message}. The server-side depletion state is still " +
                        "correct; only the client was not told. This is EXPECTED with no running protocol " +
                        "table (a headless check). (Warned once.)"
                }
            }
        }.onSuccess { change ->
            if (change != null) locChangeApplied++
            else logger.warn {
                "skilling: LocChanges refused the $why of loc $originalId at $key (experiment off, or no " +
                    "LOC_ADD_CHANGE opcode on this build). Server-side state is unchanged by that."
            }
        }
    }

    /**
     * Takes the leaves out of the scene with `LOC_DEL`, and COUNTS what happened.
     *
     * Its own three counters, deliberately NOT the trunk's: every existing assertion about
     * [locChangeAttempts] / [locChangeApplied] / [locChangeFailures] describes the trunk swap and
     * must go on describing exactly that. A canopy that quietly incremented them would move
     * numbers a check has pinned without any check being able to say why - which is the shape of
     * a silent regression, not of a fix.
     *
     * Headless the call throws (`OpenNXT.protocol` is a `lateinit` only a running server sets),
     * which is why [canopyFailures] exists and is asserted rather than swallowed. See
     * [sendLocChange] for the same argument in full.
     */
    private fun sendCanopyRemoval(c: RemovedCanopy) {
        canopyAttempts++
        runCatching {
            LocChanges.remove(
                plane = c.key.plane, x = c.key.x, y = c.key.y, shape = c.key.shape,
                rotation = c.rotation, originalId = c.locId,
                // The player is on the trunk's plane, the canopy is one up. The reference client sends it
                // anyway; see LocChanges.Change.crossPlane.
                crossPlane = true
            )
        }.onFailure {
            canopyFailures++
            if (!canopyWarned) {
                canopyWarned = true
                logger.warn {
                    "skilling: LOC_DEL of the canopy ${c.locId} at ${c.key} could not be built - " +
                        "${it::class.simpleName}: ${it.message}. The server-side depletion state is still " +
                        "correct; only the client was not told, so the leaves stay drawn. EXPECTED with no " +
                        "running protocol table (a headless check). (Warned once.)"
                }
            }
        }.onSuccess { change ->
            if (change != null) canopyApplied++
            else logger.warn {
                "skilling: LocChanges refused the canopy removal of ${c.locId} at ${c.key} (experiment " +
                    "off, or no LOC_DEL opcode on this build). The leaves will stay drawn."
            }
        }
    }

    @Volatile
    private var canopyAttempts = 0

    @Volatile
    private var canopyApplied = 0

    @Volatile
    private var canopyFailures = 0

    @Volatile
    private var canopyWarned = false

    /** How many times a canopy removal or restore was reached with real arguments. */
    fun canopyAttempts(): Int = canopyAttempts

    /** How many of those [LocChanges] accepted, i.e. would have gone on the wire. Zero headless. */
    fun canopyApplied(): Int = canopyApplied

    /** How many threw. Non-zero headless; see [sendCanopyRemoval]. */
    fun canopyFailures(): Int = canopyFailures

    @Volatile
    private var locChangeAttempts = 0

    @Volatile
    private var locChangeApplied = 0

    @Volatile
    private var locChangeFailures = 0

    @Volatile
    private var locChangeWarned = false

    /** How many times the stump/respawn swap path was reached with real arguments. */
    fun locChangeAttempts(): Int = locChangeAttempts

    /** How many of those [LocChanges] accepted, i.e. would have gone on the wire. */
    fun locChangeApplied(): Int = locChangeApplied

    /** How many threw. Non-zero headless; see [sendLocChange]. */
    fun locChangeFailures(): Int = locChangeFailures

    internal fun resetLocChangeCounters() {
        locChangeAttempts = 0; locChangeApplied = 0; locChangeFailures = 0; locChangeWarned = false
        canopyAttempts = 0; canopyApplied = 0; canopyFailures = 0; canopyWarned = false
    }

    // ================================================================
    // TOOLS
    // ================================================================

    /** Item name out of the definitions, or null. */
    private val itemNameMemo = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<String>>()
    fun itemNameOf(id: Int): String? =
        if (!RsDatabase.available) null
        else itemNameMemo.computeIfAbsent(id) {
            java.util.Optional.ofNullable(RsDatabase.queryOne("SELECT name FROM items WHERE id = ?", id) { it.getString(1) })
        }.orElse(null)

    /**
     * The id of a held tool for [kind], or null.
     *
     * INVENTED rule, cache strings: an item whose name ends in " hatchet" is a
     * hatchet, one ending in " pickaxe" is a pickaxe. Real RS gates on a tool
     * LEVEL as well and picks the best one held; neither is modelled, and this
     * refuses only the case of holding nothing at all.
     */
    fun toolIn(container: ItemContainer, kind: ResourceNodes.Kind): Int? = toolIn(container, kind, Int.MAX_VALUE)

    /**
     * The BEST tool the player can use at [level]: among the hatchets / pickaxes held,
     * the highest-power one (wiki HatchetData power, else the tool's own level requirement) whose
     * requirement the level meets; when none is usable, the one with the LOWEST requirement, so the
     * refusal names the nearest level. The reference client picks the best usable hatchet the same way.
     */
    fun toolIn(
        container: ItemContainer,
        kind: ResourceNodes.Kind,
        level: Int,
        worn: ItemContainer? = null,
        belt: Set<Int> = emptySet()
    ): Int? {
        val suffix = when (kind) {
            ResourceNodes.Kind.WOODCUTTING -> HATCHET_SUFFIX
            ResourceNodes.Kind.MINING -> PICKAXE_SUFFIX
            ResourceNodes.Kind.GATHERING -> return null
        }
        data class Held(val id: Int, val requirement: Int, val power: Int)
        val held = ArrayList<Held>()
        val ids = ArrayList<Int>()
        for (item in container.items() + (worn?.items() ?: emptyList())) ids += item.id   // the backpack AND the worn slots
        if (toolbelt) TOOLBELT_BASE[kind]?.let { ids += it }                              // ...and the tool belt's base tier
        ids += belt                                                                       // ...and what THIS player added to the belt
        for (id in ids) {
            val name = itemNameOf(id) ?: continue
            if (!name.endsWith(suffix)) continue
            val requirement = toolRequirement(id) ?: 1
            val power = SkillXpWiki.hatchets[name]?.power ?: requirement
            if (held.none { it.id == id }) held += Held(id, requirement, power)
        }
        if (held.isEmpty()) return null
        return (held.filter { it.requirement <= level }.maxByOrNull { it.power } ?: held.minByOrNull { it.requirement })?.id
    }

    // ================================================================
    // THE GATHER
    // ================================================================

    /** Which stat a kind trains. Not a guess: [Stat] names them. */
    fun statFor(kind: ResourceNodes.Kind): Stat = when (kind) {
        ResourceNodes.Kind.WOODCUTTING -> Stat.WOODCUTTING
        ResourceNodes.Kind.MINING -> Stat.MINING
        ResourceNodes.Kind.GATHERING -> Stat.FARMING
    }

    /**
     * The requirement for a resolved yield: the cache's level if it has one,
 * this server's table otherwise.
     */
    fun requirementFor(locId: Int, kind: ResourceNodes.Kind, itemName: String): Pair<Requirement, Boolean> {
        val cacheLevel = ResourceNodes.levelFromCache(locId)
        val table = when (kind) {
            ResourceNodes.Kind.WOODCUTTING -> WOODCUTTING_TABLE
            ResourceNodes.Kind.MINING -> MINING_TABLE
            ResourceNodes.Kind.GATHERING -> emptyMap()
        }
        val authored = table[itemName] ?: Requirement(
            when (kind) {
                ResourceNodes.Kind.WOODCUTTING -> DEFAULT_WOODCUTTING_LEVEL
                ResourceNodes.Kind.MINING -> DEFAULT_MINING_LEVEL
                ResourceNodes.Kind.GATHERING -> 1
            },
            DEFAULT_XP_TENTHS, "AUTHORED-DEFAULT", "DEFAULT"
        )
 //, the WIKI layer (SkillXpWiki): each half of the requirement resolves separately.
        //   level: cache param 23 > dbtable 34 mining ladder > wiki > authored
        // xp:
        val measuredXp = when (kind) {
            ResourceNodes.Kind.WOODCUTTING -> SkillingRates.WOODCUTTING_XP_TENTHS[itemName]
            ResourceNodes.Kind.MINING -> SkillingRates.MINING_XP_TENTHS[itemName]
            ResourceNodes.Kind.GATHERING -> null
        }
        val wiki = if (SkillXpWiki.enabled) SkillXpWiki.requirementFor(kind, itemName) else null
        val miningLadder = if (kind == ResourceNodes.Kind.MINING) MiningTable949.levelForItem(itemName) else null
        val (level, levelSource) = when {
            cacheLevel != null -> cacheLevel to "CACHE"
            miningLadder != null -> miningLadder to "MINING-TABLE"
            wiki?.level != null -> wiki.level to "WIKI"
            else -> authored.level to authored.levelSource
        }
        val (xp, xpSource) = when {
            measuredXp != null -> measuredXp to ""
            wiki != null -> wiki.xpTenths to "WIKI"
            else -> authored.xpTenths to authored.xpSource
        }
        return Requirement(level, xp, levelSource, xpSource) to (cacheLevel != null)
    }

    /**
     * Whether this kind of node visibly depletes. Woodcutting yes, mining no -
     * see the class doc for the measurement behind the "no".
     */
    fun depletes(kind: ResourceNodes.Kind): Boolean = kind == ResourceNodes.Kind.WOODCUTTING

    /**
     * One click on a resource loc.
     *
     * The order of the gates is deliberate: DEPLETED
     * before NO_TOOL before LEVEL_TOO_LOW before NO_YIELD before NO_SPACE.
     * A stump must refuse even to a player who could chop it, and a player with
     * no hatchet must be told that rather than being told their level is too
     * low.
     */
    fun gather(ctx: LocContext, kind: ResourceNodes.Kind, fromCycle: Boolean = false): Gather {
        val locId = ctx.locId
        val locName = ctx.definition.name
        val stat = statFor(kind)

        if (!RsDatabase.available) {
            return Gather(Outcome.NO_DATABASE, locId, locName, kind, detail = "no rs3.sqlite")
        }
        // ---- 0. a click inside a running cycle on the SAME node pays nothing ----------
        if (!fromCycle) {
            val running = active[ctx.player]
            if (running != null && running.ctx.locId == locId && running.ctx.x == ctx.x && running.ctx.z == ctx.z &&
                running.ctx.plane == ctx.plane && tickCount < running.nextTick
            ) {
                return Gather(
                    Outcome.REPEATING, locId, locName, kind,
                    detail = "already gathering here; next cycle in ${running.nextTick - tickCount} tick(s)"
                )
            }
            if (running != null) stop(ctx.player, "re-clicked (${if (running.ctx.locId == locId) "same node, cycle due" else "a different node"})")
        }

        // ---- 1. is this node already a stump? --------------------------
        val placement = LocInteraction.placementOf(locId, ctx.x, ctx.z, ctx.plane)
        val shape = placement?.type ?: -1
        val rotation = placement?.rot ?: 0
        val originX = placement?.originX ?: ctx.x
        val originZ = placement?.originZ ?: ctx.z
        val existing = depletionAt(ctx.plane, originX, originZ, shape)
        if (existing != null) {
            return Gather(
                Outcome.DEPLETED, locId, locName, kind,
                depletedTo = existing.depletedLocId, respawnAtTick = existing.respawnAtTick,
                detail = "still a stump for ${existing.respawnAtTick - tickCount} more tick(s)"
            )
        }

        // ---- 2. tool: the best one the player can use --------------------
        val container = containerSupplier(ctx.player)
        val playerLevelOnce = levelSupplier(ctx.player, stat)
        val tool = toolIn(container, kind, playerLevelOnce, wornSupplier(ctx.player), beltSupplier(ctx.player))
        if (tool == null) {
            return Gather(
                Outcome.NO_TOOL, locId, locName, kind,
                detail = "no item named '*${if (kind == ResourceNodes.Kind.WOODCUTTING) HATCHET_SUFFIX else PICKAXE_SUFFIX}' in the backpack or worn" +
                    (if (toolbelt) " (and the tool belt has no base tool for this kind)" else " (tool belt OFF)")
            )
        }

 // ---- 2b. the tool's OWN level ------------------
        // Hatchets carry item param 750 = 1/10/20/30/40/50 (bronze..rune) with 749 = Attack,
        // the wield requirement; the wiki's Woodcutting requirement for the same hatchets is the
        // those two). A tool with no 750 has no requirement - absent is not zero.
        val toolLevel = toolRequirement(tool)
        val playerLevel = playerLevelOnce   // read ONCE above, for the tool choice; the gates reuse it
        if (toolLevel != null && playerLevel < toolLevel) {
            return Gather(
                Outcome.TOOL_LEVEL_TOO_LOW, locId, locName, kind, toolId = tool,
                playerLevel = playerLevel, levelRequired = toolLevel,
                detail = "tool ${itemNameOf(tool) ?: tool} needs level $toolLevel (item param 750)"
            )
        }
        // ---- 3. yield ----------------------------------------------------
        val resolved = ResourceNodes.resolve(locId, kind)
        if (resolved !is ResourceNodes.Resolved) {
            return Gather(
                Outcome.NO_YIELD, locId, locName, kind, toolId = tool,
                detail = "the name rule resolved nothing: $resolved"
            )
        }

        // ---- 4. level ----------------------------------------------------
        val (requirement, grounded) = requirementFor(locId, kind, resolved.itemName)
        if (playerLevel < requirement.level) {
            return Gather(
                Outcome.LEVEL_TOO_LOW, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = if (grounded) "requirement is the cache's param 23" else "requirement is AUTHORED"
            )
        }

        // ---- 4b. is there a node here at all? ---------------------------
 // (CODE-REVIEW-FULL #1b, CRITICAL). `placement` is null when
        // map_loc places no loc of this id at or covering the tile. Every step
        // below used to run regardless: the item and the xp were paid, and step 7
        // could only warn that it had nothing to deplete. A client that forged an
        // OPLOC naming a resource id at any tile minted items and xp, 128 per
        // tick. Placed AFTER the informational gates (tool, yield, level - their
        // right reason) and BEFORE the first step that pays anything.
        if (placement == null) {
            return Gather(
                Outcome.NO_PLACEMENT, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = "map_loc places no loc $locId at or covering (${ctx.x},${ctx.z},plane ${ctx.plane}) - nothing to gather"
            )
        }

 // ---- 4c. space BEFORE the roll ----------------
        // A missed cycle on a full backpack would otherwise keep the action alive for ever
        // (MISSED never reached step 5): the refusal has to come first.
        if (container.isFull() && !(container.stacks(resolved.itemId) && container.count(resolved.itemId) > 0)) {
            return Gather(
                Outcome.NO_SPACE, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }
 // ---- 4c1. turn to face the node --------------------
        // On the CLICK, never from the cycle: the reference client's angle changes once per interaction, on the
        // arrival tick, and this server's arrival tick IS the click tick because the gather is
        // dispatched from the walk's onArrival. Placed after every gate that can refuse - a player
        // with no hatchet is told so and does not turn - and before the three arming branches
        // below, so STARTED, the in-cycle node switch and the immediate yield all face the same
        // way from one call site. The footprint is [placement]'s ROTATED dx/dz, already resolved
        // at step 1; a loc with no map_loc row falls back to 1x1 at the clicked tile, which is the
        // same fallback `OpLocHandler.adjacentToFootprint` takes.
        if (!fromCycle) {
            faceLoc(
                ctx.player, originX, originZ,
                placement?.dx ?: 1, placement?.dz ?: 1
            )
        }

 // ---- 4c2. the FIRST cycle waits --------------------
        // A valid click registers the action and pays nothing; the first yield comes firstCycleTicks
        // later from tick(), the swing animation the tick before it. The per-player cadence below
        // still governs a switch made inside a running cycle.
        if (!fromCycle) {
            val first = firstCycleTicks(kind)
            if (first > 0) {
                val last = lastCycle[ctx.player]
                val due = maxOf(tickCount + first, (last ?: Long.MIN_VALUE) + CYCLE_TICKS)
                arm(ctx.player, Active(ctx, kind, whereIs(ctx.player), due))
                if (due - 1 == tickCount) animationFor(kind)?.let { ids -> runCatching { animationSink(ctx.player, ids) }; animationsSent++ }
 // the start line rides the start tick, once per click
                startMessage(kind)?.let { msg -> runCatching { messageSink(ctx.player, msg) }; messagesSent++ }
                return Gather(
                    Outcome.STARTED, locId, locName, kind,
                    itemId = resolved.itemId, itemName = resolved.itemName,
                    levelRequired = requirement.level, levelFromCache = grounded,
                    playerLevel = playerLevel, toolId = tool,
                    detail = "first cycle in ${due - tickCount} tick(s)"
                )
            }
        }
 // ---- 4d. the per-player cadence ------
        // A valid click inside the player's cycle - on ANY node - registers the action here
        // and pays when the running cycle falls due. Only a click after the cycle pays now.
        if (!fromCycle) {
            val last = lastCycle[ctx.player]
            if (last != null && tickCount - last < CYCLE_TICKS) {
                arm(ctx.player, Active(ctx, kind, whereIs(ctx.player), last + CYCLE_TICKS))
                return Gather(
                    Outcome.REPEATING, locId, locName, kind,
                    itemId = resolved.itemId, itemName = resolved.itemName,
                    levelRequired = requirement.level, levelFromCache = grounded,
                    playerLevel = playerLevel, toolId = tool,
                    detail = "switched to this node inside the cycle; next cycle in ${last + CYCLE_TICKS - tickCount} tick(s)"
                )
            }
        }
        lastCycle[ctx.player] = tickCount
 // ---- 4e. the cycle's roll --------------------------------
        val chance = successChance(
            SuccessContext(kind, resolved.itemName, playerLevel, requirement.level, tool, itemNameOf(tool), toolLevel)
        ).coerceIn(0.0, 1.0)
        if (random.nextDouble() >= chance) {
            cyclesMissed++
            val miss = Gather(
                Outcome.MISSED, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = "cycle missed at chance %.3f".format(chance)
            )
            if (!fromCycle) arm(ctx.player, Active(ctx, kind, whereIs(ctx.player), tickCount + CYCLE_TICKS))
            return miss
        }
        // ---- 5. space ----------------------------------------------------
        val add = container.add(resolved.itemId, 1)
        if (add.added == 0) {
            return Gather(
                Outcome.NO_SPACE, locId, locName, kind,
                itemId = resolved.itemId, itemName = resolved.itemName,
                levelRequired = requirement.level, levelFromCache = grounded,
                playerLevel = playerLevel, toolId = tool,
                detail = "container full (${container.usedSlots()}/${container.size})"
            )
        }

        // ---- 6. xp -------------------------------------------------------
        record(ctx.player, stat, requirement.xpTenths)
        // Straight to the container's own addExperience, whose refresh() is
        // gated behind the UPDATE_STAT crash switch. Nothing is sent.
        runCatching { xpSink(ctx.player, stat, requirement.xpTenths / 10.0) }
            .onFailure { logger.warn(it) { "skilling: xp sink threw; the award was still recorded" } }

        // ---- 7. deplete ---------------------------------------------------
        var depletedTo: Int? = null
        var respawnAt = -1L
 //: a successful chop stumps the tree with the wiki's fell/256, not always
        val fell = fellChance(resolved.itemName)
        val fellsNow = fell == null || random.nextDouble() < fell
        if (depletes(kind) && placement != null && fellsNow) {
 //the id is per tree, and where nothing
            // grounds one the tree is REMOVED rather than turned into a guessed stump. See
            val stump = Stumps.stumpFor(locId)
            val newId = stump?.locId ?: REMOVE_LOC
            depletedTo = newId
            respawnAt = tickCount + RESPAWN_TICKS
            val key = LocChanges.Key(ctx.plane, originX, originZ, shape)
            // Question 9 (two players felling the same tree on the same tick). The tick engine is
            // one thread and `gather` runs on it, so this cannot be reached twice concurrently
            // today - but the check-then-act between step 1's `depletionAt` and this write is the
            // shape that stops being safe the moment anything else calls `gather`. putIfAbsent
            // makes the LOSER a no-op instead of a second LOC_ADD_CHANGE and a second respawn
            // timer overwriting the first. The winner's Depletion is what gets sent.
            // The OTHER half of the tree: its leaves, one plane up. Resolved from map_loc per
            // PLACED loc - the offset is not constant and must never be typed - and removed with
            // LOC_DEL, which is what the reference client sends. See [Canopy] for the derivation and controls.
            val leaves = Canopy.above(placement, ctx.plane)
            val canopyRef = leaves?.let {
                RemovedCanopy(
                    LocChanges.Key(ctx.plane + 1, it.originX, it.originZ, it.type), it.locId, it.rot
                )
            }
            val record = Depletion(key, locId, newId, rotation, respawnAt, stump?.source, canopyRef)
            val already = synchronized(depleted) { depleted.putIfAbsent(key, record) }
            if (already != null) {
                logger.warn {
                    "skilling: loc $locId at $key was felled twice in one tick - the second fell is a " +
                        "no-op and the first depletion (respawn at ${already.respawnAtTick}) stands."
                }
                depletedTo = already.depletedLocId
                respawnAt = already.respawnAtTick
            } else {
                sendLocChange(key, rotation, locId, newId, if (stump == null) "removed (no grounded stump)" else "stump")
                if (canopyRef != null) {
                    sendCanopyRemoval(canopyRef)
                    logger.info {
                        "skilling: ...and its canopy, loc ${canopyRef.locId} " +
                            "'${Canopy.nameOf(canopyRef.locId) ?: "?"}' at (${canopyRef.key.x}," +
                            "${canopyRef.key.y},plane ${canopyRef.key.plane}) shape ${canopyRef.key.shape} " +
                            "rot ${canopyRef.rotation}, was REMOVED with LOC_DEL. the reference client deletes " +
                            "the leaves in the same tick as the stump (Skilling.Canopy)."
                    }
                } else logger.info {
                    "skilling: loc $locId at $key has NO canopy above it in map_loc, so nothing was " +
                        "removed on plane ${ctx.plane + 1}. 54.4% of the 16,112 choppable placements are " +
                        "like this; see Skilling.Canopy."
                }
                if (stump == null) logger.info {
                    "skilling: loc $locId '$locName' has no grounded stump in " +
                        "data/seed/tree_stumps_observed.tsv and its chop-run names none either, so it was " +
                        "REMOVED from the scene for $RESPAWN_TICKS tick(s) instead of becoming a guessed id. " +
                        "This is a known DIFF from the reference client, which shows a stump; see Skilling.Stumps."
                } else logger.info {
                    "skilling: loc $locId '$locName' -> stump ${stump.locId} [${stump.source}" +
                        (if (stump.observations > 0) ", seen x${stump.observations}" else "") + "]"
                }
            }
 //the action ends on the fell tick - the (-1 x4) block rode the yield
            // tick and nothing followed; before this the running action lived on to be refused DEPLETED
            // four ticks later (a ghost cycle, invisible except in the log)
            stop(ctx.player, "the tree fell (stump)")
        } else if (depletes(kind) && fellsNow) {
            // No map_loc row: the shape and rotation LOC_ADD_CHANGE needs do
            // not exist, and inventing them would put a stump on the wrong
            // edge. Same refusal OpLocHandler makes for a door.
            logger.warn {
                "skilling: chopped loc $locId at (${ctx.x},${ctx.z},plane ${ctx.plane}) but map_loc places " +
                    "no such loc there, so there is no shape/rotation to send. The item and xp still happened; " +
                    "the tree did not change."
            }
        }

        // ---- 8. show it -----------------------------------------------------
        // The log is in the container; the client has to be told, or the player
        // chops a tree and sees nothing happen - which is the exact bug this
        // whole change exists to fix.
        val sent = runCatching { inventoryResend(ctx.player) }.getOrDefault(false)

        val result = Gather(
            Outcome.GATHERED, locId, locName, kind,
            itemId = resolved.itemId, itemName = resolved.itemName, amount = add.added,
            xpTenths = requirement.xpTenths, stat = stat, chance = chance,
            levelSource = requirement.levelSource, xpSource = requirement.xpSource,
            levelRequired = requirement.level, levelFromCache = grounded,
            playerLevel = playerLevel, toolId = tool,
            depletedTo = depletedTo, respawnAtTick = respawnAt,
            inventorySent = sent,
            detail = if (resolved.ambiguous) "AMBIGUOUS item name, ${resolved.candidates.size} ids ${resolved.candidates}, lowest wins" else ""
        )
        logger.info { "skilling: $result. $PROVENANCE_SHORT" }
        cyclesPaid++
        yieldMessage(kind, resolved.itemName)?.let { msg -> runCatching { messageSink(ctx.player, msg) }; messagesSent++ }
        // the action: a click starts it (first cycle paid above), a cycle refreshes it
        if (!fromCycle) {
            arm(ctx.player, Active(ctx, kind, whereIs(ctx.player), tickCount + CYCLE_TICKS))
        }
        // a stump has nothing more to give until it respawns; the action ends with the pay
        if (depletedTo != null) stop(ctx.player, "the node depleted")
        return result
    }

    /** The tool's own level requirement for its skill, from item params 749/750; null when the item states none. */
    private val toolRequirementMemo = java.util.concurrent.ConcurrentHashMap<Int, java.util.Optional<Int>>()
    fun toolRequirement(toolId: Int): Int? = toolRequirementMemo.computeIfAbsent(toolId) {
        java.util.Optional.ofNullable(toolRequirementUncached(toolId))
    }.orElse(null)

    private fun toolRequirementUncached(toolId: Int): Int? {
        val json = RsDatabase.queryOne("SELECT value FROM items_attr WHERE id = ? AND field = 'extra'", toolId) { it.getString(1) } ?: return null
        val params = com.opennxt.model.combat.NpcCombat.parseParams(json)
        val level = params[750] ?: return null
        val skill = params[749]
        // 749 = 0 (Attack: the hatchet's wield requirement, equal to its Woodcutting one), or the
        // tool's own skill: 14 = Mining on the two reworked pickaxes, 8 = Woodcutting on the sacred /
        // rejecting 8 gave those four NO requirement). Anything else is not read.
        return if (skill == null || skill == 0 || skill == Stat.MINING.id || skill == Stat.WOODCUTTING.id) level else null
    }

    /** Handler for "Chop down" / "Chop". */
    fun onChop(ctx: LocContext): Any = gather(ctx, ResourceNodes.Kind.WOODCUTTING)

    /** Handler for "Mine". */
    fun onMine(ctx: LocContext): Any = gather(ctx, ResourceNodes.Kind.MINING)

    // ================================================================
    // REGISTRATION
    // ================================================================

    data class Installed(val chopDown: Int, val chop: Int, val mine: Int) {
        val total: Int get() = chopDown + chop + mine
    }

    /**
     * Binds the three options across every loc that declares them. Returns the
     * loc-id counts, which are the numbers the class doc quotes.
     */
    fun install(): Installed {
        if (!enabled) {
            logger.warn { "skilling is DISABLED (-Dopennxt.experiment.skilling=false) - no resource loc will act" }
            return Installed(0, 0, 0)
        }
        val chopDownSpaced = ContentRegistry.onLocAction(ResourceNodes.CHOP_DOWN, ::onChop)
        // The hyphenated spelling. 39 loc ids declare it and none of them acted
 // before. Guarded rather than assumed: a cache that does not
        // declare it must not cost us the two spellings that do.
        val chopDownHyphen = try {
            ContentRegistry.onLocAction(ResourceNodes.CHOP_DOWN_HYPHEN, ::onChop)
        } catch (e: IllegalArgumentException) {
            logger.info { "skilling: no loc declares '${ResourceNodes.CHOP_DOWN_HYPHEN}' in this cache" }
            0
        }
        val chopDown = chopDownSpaced + chopDownHyphen
        val chop = ContentRegistry.onLocAction(ResourceNodes.CHOP, ::onChop)
        val mine = ContentRegistry.onLocAction(ResourceNodes.MINE, ::onMine)
        logger.info {
            "skilling: bound '${ResourceNodes.CHOP_DOWN}' across $chopDownSpaced locs, " +
                "'${ResourceNodes.CHOP_DOWN_HYPHEN}' across $chopDownHyphen, " +
                "'${ResourceNodes.CHOP}' across $chop, '${ResourceNodes.MINE}' across $mine. $PROVENANCE"
        }
        // Said out loud at boot, once, and DERIVED here rather than typed: an operator can see how
        // many of the trees on their world turn into a stump and how many simply vanish.
        runCatching {
            val cov = Stumps.coverage()
            val measured = cov.count { it.value.source == Stumps.Source.MEASURED }
            val run = cov.count { it.value.source == Stumps.Source.RUN }
            val forced = cov.count { it.value.source == Stumps.Source.FORCED }
            val choppable = chopDown + chop
            logger.info {
                "skilling stumps: $measured + $run RUN + $forced FORCED = ${cov.size} of " +
                    "$choppable choppable locs resolve to a grounded stump; the rest are REMOVED from " +
                    "the scene for $RESPAWN_TICKS tick(s) and put back. Nothing is guessed - the single " +
                    "1342 is not used (the stumps are 40350/40351/40352/40354/40357, and those " +
                    "ARE placed by map_loc, so 'never placed' was the wrong discriminator). " +
                    "-Dopennxt.skilling.stumpLoc=1342 restores the old behaviour."
            }
        }.onFailure { logger.warn(it) { "skilling stumps: the coverage line could not be derived" } }
        return Installed(chopDown, chop, mine)
    }
}
