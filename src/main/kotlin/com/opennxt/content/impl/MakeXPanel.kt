package com.opennxt.content.impl

import com.opennxt.model.InterfaceHash
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.UpdateInvStopTransmit
import com.opennxt.net.game.serverprot.ifaces.IfClosesub
import com.opennxt.net.game.serverprot.ifaces.IfOpenSub
import com.opennxt.net.game.serverprot.ifaces.IfSetevents
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import com.opennxt.net.game.serverprot.variables.VarpLarge
import com.opennxt.net.game.serverprot.variables.VarpSmall
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * INTERFACES 1370 / 1371 - THE MAKE-X PANEL. "Clicking craft logs just does my character
 * animation, but doesn't open the craft menu."
 */
object MakeXPanel {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean get() = System.getProperty("opennxt.experiment.makex") != "false"

    // ---- ids, every one decoded from a recorded hash or read off the cache ----

    /** `makex2012`. */
    const val IFACE = 1370

    /** `makex2012_controls`. */
    const val CONTROLS_IFACE = 1371

    /** toplevel_v2. */
    const val TOPLEVEL = 1477

    /** 1477:735, from `IF_OPENSUB` parent 96797407. */
    const val MOUNT = 735

    /** 1370:0, from `IF_OPENSUB` parent 89784320 - the controls mount inside the panel. */
    const val CONTROLS_MOUNT = 0

    /** 1371:22 - the product grid. `listeners [[1169],[93]...]`, so varp 1169 builds it. */
    const val PRODUCT_GRID = 22

    /** 1371:20 - the quantity selector, armed `0..8846`. */
    const val QUANTITY = 20

    /** 1370:30 - the confirm. `optmask 1` = the pausebutton bit, so it replies RESUME_PAUSEBUTTON. */
    const val CONFIRM = 30

    /** 1370:32 - "Close", `optmask 2`, an ordinary IF_BUTTON1. */
    const val CLOSE = 32

    /**
     * 1371:28 - the MATERIAL/CATEGORY dropdown button, `optmask 2`, opt "Select".
     */
    const val CATEGORY_BUTTON = 28

    /**
     * 1477:896 - the gameframe's own dropdown list, where the chosen row arrives.
     *
     * `IF_BUTTON1 1477:896 arg2 = <row index>`. The reference client arms it `0.1000 mask 2` in the LOGIN burst
     * (`WorldPlayer.kt`'s generated 1477 block already sends it), so nothing here has to.
     */
    const val CATEGORY_LIST = 896

    /** mask 2 = bit 1 = op 1, which is what the reference client arms both grids with. */
    const val GRID_MASK = 2

    /** The product grid is armed `0..(STRIDE * productCount)`: 4 products -> 0..16, 6 -> 0..24. */
    const val PRODUCT_SLOT_STRIDE = 4

    // ---- varps, all from the open block ----
    const val VARP_CATEGORY_A = 1168
    const val VARP_CATEGORY_B = 7881
    const val VARP_ZERO = 9409
    const val VARP_RECIPE = 1169
    const val VARP_PRODUCT = 1170
    const val VARP_COUNT = 8846
    const val VARP_COUNT_MIRROR = 8847
    const val VARP_CONFIRMED = 1172
    const val VARP_LAST_MADE = 1175

    // ---- varcs ----
    const val VARC_EXAMINE = 2391
    const val VARC_MAKEABLE = 2223
    val VARC_CLEARED_ON_OPEN = intArrayOf(2689, 2690, 6579, 6580, 7057, 7058)
    const val VARC_SELECTION = 3678

    /** The panel's own reset script, run immediately before the two IF_OPENSUBs. */
    const val SCRIPT_RESET = 8178

    /** `RUNCLIENTSCRIPT 3689 [1370, 96797407, 40]` - the close animation, on every confirm. */
    const val SCRIPT_CLOSE = 3689
    const val SCRIPT_CLOSE_ARG3 = 40

    /** The 884 inventory the reference client refreshes on every open and stops transmitting on every close. */
    const val PRODUCT_INV = 884

    // ================================================================
    // THE ONE PANEL: `Craft` on Logs (1511)
    // ================================================================
    //
    // All five numbers below are read off ONE tick of ONE observation -
 // t368, the tick after the operator chose an option on the
    // 1179 chooser that a `Craft` click on Logs put up at t363 - and they are deliberately not
    // generalised. varp 1169 IS the product list (the client builds the rows from it), so a
    // material this triple was not measured for gets no panel at all rather than a guessed
    // claim at all about any other material.

    /** varp 1168 on the logs panel. Opaque - replayed, not interpreted. */
    const val LOGS_CATEGORY_A = 6939

    /** varp 7881 on the logs panel. Opaque - replayed, not interpreted. */
    const val LOGS_CATEGORY_B = 6940

    /** varp 1169 on the logs panel: the recipe list the CLIENT then draws. */
    const val LOGS_RECIPE = 6947

    /** `IF_SETEVENTS 1371:22 fromSlot 0 toSlot 24` - 24 = 4 x 6 products. */
    const val LOGS_GRID_TO_SLOT = 24

    /**
     * The one product row whose slot is t371's `IF_BUTTON1 1371:22 slot 5` was answered
     * with `varp 1170 = 52` and `varcstr 2391 = "A wooden shaft. Needs feathers."`.
     */
    const val LOGS_SHAFT_SLOT = 5

    /** The reference client's own examine for item 52, from that same reply. Not invented, and not in this cache. */
    const val LOGS_SHAFT_EXAMINE = "A wooden shaft. Needs feathers."

    /**
     * The grid rows of one dropdown category that this server can actually make.
     */
    fun rowsFor(category: Fletching.PanelCategory): Map<Int, Row> =
        category.products.associate { p ->
            p.gridSlot to Row(p.gridSlot, p.itemId, p.name, examineFor(p.itemId))
        }

    /** Every dropdown row of [panel], in cache order. */
    fun categoriesFor(panel: Fletching.Panel): List<CategoryView> =
        panel.categories.map { c ->
            CategoryView(
                index = c.index,
                name = c.name,
                recipeEnum = c.productEnum,
                // the grid is armed 0.4 x the enum's OWN entry count, not the makeable
                // count - the reference client arms all six of the Logs products and this server can make three.
                gridToSlot = Fletching.GRID_STRIDE * c.entryCount,
                rows = rowsFor(c)
            )
        }

    /**
     * The examine line the reference client sent for a product, or null.
     *
     * `items_attr` in this cache carries no examine field at all (the hole [ToolBeltPanel]
     * documents), so this holds only what the WIRE was heard saying. One entry today.
     */
    fun examineFor(itemId: Int): String? = MEASURED_EXAMINES[itemId]

    /**
     * Product item id -> the `CLIENT_SETVARCSTR 2391` the reference client sent when it was selected.
     * not authored: 09-07T01-06-17 t371 and 09-07T05-17-54 t1443.
     */
    val MEASURED_EXAMINES: Map<Int, String> = mapOf(
        Fletching.SHAFT_ITEM to LOGS_SHAFT_EXAMINE
    )

    /**
     * One selectable row, keyed by the grid slot the reference client puts it at.
     *
     * [maxMakeable] is recomputed at open and at every product click, because the reference client's 8846 does:
     * the same panel showed 7 for a stock and 1 for a log box on the same backpack.
     */
    data class Row(val slot: Int, val itemId: Int, val name: String, val examine: String? = null)

    /**
     * One row of the MATERIAL dropdown: its name, the varp-1169 enum behind it, and the rows this
     * server is willing to honour inside it.
     *
     * [gridToSlot] is `4 * entryCount` of the CACHE enum, not of [rows] - the client draws every
     * entry whether this server can make it or not, and the reference client arms the whole grid.
     */
    data class CategoryView(
        val index: Int,
        val name: String,
        val recipeEnum: Int,
        val gridToSlot: Int,
        val rows: Map<Int, Row>
    )

    /**
     * The panel one player has open.
     *
     * [countOf] is a function rather than a number so a confirm cannot pay out a count that was
     * true when the panel opened and false by the time it was pressed - question 7 of the nine.
     */
    data class Session(
        val materialId: Int,
        val materialName: String,
        val categoryA: Int,
        val categoryB: Int,
        val recipe: Int,
        val rows: Map<Int, Row>,
        val gridToSlot: Int,
        var selected: Row,
        val countOf: (WorldPlayer, Row) -> Int,
        val onConfirm: (WorldPlayer, Row, Int) -> Boolean,
        /**
         * The MATERIAL dropdown. EMPTY means the panel has exactly one category
         * and [recipe] / [rows] / [gridToSlot] are it - which is the pre- shape and is
         * still what a caller that passes no categories gets, byte for byte.
         */
        val categories: List<CategoryView> = emptyList(),
        /** Which dropdown row is showing. Starts at the caller's own, moves on [handleCategoryChoice]. */
        var categoryIndex: Int = 0,
        /**
         * Whether [CATEGORY_BUTTON] has been clicked since the last selection. The gate on
         * [CATEGORY_LIST], which is a SHARED gameframe component: without it, any dropdown anywhere
         * in the client would re-key a make-X panel that happened to be open.
         */
        var dropdownOpen: Boolean = false
    ) {
        /** The dropdown row in force, or null when this panel has no dropdown. */
        fun category(): CategoryView? = categories.firstOrNull { it.index == categoryIndex }

        /** varp 1169 for the row in force. */
        fun activeRecipe(): Int = category()?.recipeEnum ?: recipe

        /** The honourable grid rows for the row in force. */
        fun activeRows(): Map<Int, Row> = category()?.rows ?: rows

        /** `IF_SETEVENTS 1371:22 0..this` for the row in force. */
        fun activeGridToSlot(): Int = category()?.gridToSlot ?: gridToSlot
    }

    /**
     * Weakly keyed on the [WorldPlayer], the shape [Fletching.active] uses and for the same
     * reason: a player who logs out with the panel open must be collectable even if nothing ever
     * calls [close].
     */
    private val sessions: MutableMap<WorldPlayer, Session> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, Session>())

    @Volatile var opens: Int = 0; private set
    @Volatile var productClicks: Int = 0; private set
    @Volatile var quantityClicks: Int = 0; private set
    @Volatile var confirms: Int = 0; private set
    @Volatile var closes: Int = 0; private set

    /** `IF_BUTTON1 1371:28` frames taken - the material dropdown being opened. */
    @Volatile var categoryButtons: Int = 0; private set

    /** `IF_BUTTON1 1477:896` frames taken - the material actually switched. */
    @Volatile var categoryChoices: Int = 0; private set

    internal fun resetCounters() {
        opens = 0; productClicks = 0; quantityClicks = 0; confirms = 0; closes = 0
        categoryButtons = 0; categoryChoices = 0
    }

    fun sessionOf(player: WorldPlayer): Session? = sessions[player]
    fun openCount(): Int = sessions.size

    /**
     * Open the panel, in the reference client's order.
     *
     * ## The nine questions
     *  1. this tick - the whole block goes out now.
     *  2. next tick - nothing. The client builds the grid from varp 1169.
     * 3. the actor moves - nothing here closes the panel. Neither does the reference client: the 09-07 observation
     *     walks between t23 and t25 with the panel opening on arrival, and no movement frame in
     *     the corpus is followed by an `IF_CLOSESUB` of 1370.
     *  4. the target disappears - the material is re-counted by [Session.countOf] at CONFIRM, not
     *     trusted from the open, so a bank deposit between the two makes fewer items, not a debt.
     *  5. the actor dies - not modelled here; the action the confirm starts owns that.
     *  6. logout / disconnect - guarded on `channel.isActive`; the session map is weakly keyed.
     *  7. the request repeats - a second open REPLACES the session and re-sends the block, which
     * is what the reference client does (09-07 t25 then t66 on the same player, no close in between beyond
     *     the panel's own).
     *  8. persistence fails - nothing is persisted by an open.
     *  9. two actors - the session map is per-player; there is no shared object.
     */
    fun open(player: WorldPlayer, session: Session): Boolean {
        if (!enabled) return false
        if (!player.client.channel.isActive) return false
        val w = player.client
        val count = session.countOf(player, session.selected).coerceAtLeast(0)

        w.write(VarpLarge(VARP_CATEGORY_A, session.categoryA))
        w.write(VarpLarge(VARP_CATEGORY_B, session.categoryB))
        w.write(VarpSmall(VARP_ZERO, 0))
        w.write(VarpLarge(VARP_RECIPE, session.activeRecipe()))
        w.write(VarpLarge(VARP_PRODUCT, session.selected.itemId))
        w.write(VarpSmall(VARP_COUNT, count))
        w.write(VarpSmall(VARP_COUNT_MIRROR, count))
        for (id in VARC_CLEARED_ON_OPEN) w.write(ClientSetvarcSmall(id, 0))
        session.selected.examine?.let { w.write(ClientSetvarcstrSmall(VARC_EXAMINE, it)) }
        w.write(ClientSetvarcSmall(VARC_MAKEABLE, if (count > 0) 1 else 0))
        w.write(ClientSetvarcSmall(VARC_SELECTION, -1))
        w.write(RunClientScript(SCRIPT_RESET, emptyArray()))
        w.write(IfOpenSub(IFACE, false, InterfaceHash(TOPLEVEL, MOUNT)))
        w.write(IfOpenSub(CONTROLS_IFACE, true, InterfaceHash(IFACE, CONTROLS_MOUNT)))
        w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, PRODUCT_GRID), 0, session.activeGridToSlot(), GRID_MASK))
        w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, QUANTITY), 0, count, GRID_MASK))

        session.dropdownOpen = false
        sessions[player] = session
        opens++
        logger.info {
            "make-X: opened $IFACE/$CONTROLS_IFACE at $TOPLEVEL:$MOUNT for ${player.name} - " +
                "${session.materialName} -> ${session.selected.name} (item ${session.selected.itemId}), " +
                "varp $VARP_RECIPE=${session.recipe} builds the rows CLIENT-SIDE, grid armed 0..${session.gridToSlot}, " +
                "quantity armed 0.$count. The COUNT ($count) is this server's, exactly as the reference client's 8846 is " +
                "the reference client's; the player-chosen count has no observed wire form."
        }
        return true
    }

    /**
     * A click on the product grid. Only the slots this module has are honoured; the
     * stride is known and deliberately not used to synthesise the rest (see the KDoc).
     */
    fun handleProductClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        val row = session.activeRows()[slot] ?: run {
            logger.info {
                "make-X: ${player.name} clicked product slot $slot on $CONTROLS_IFACE:$PRODUCT_GRID; in category " +
                    "${session.categoryIndex} (${session.category()?.name ?: session.materialName}) this server can " +
                    "make ${session.activeRows().keys.sorted()} and refuses the rest - the grid is the CLIENT's " +
                    "(varp $VARP_RECIPE builds it from the cache enum) and it draws rows this server has no recipe " +
                    "for. Nothing sent. Grid slot $slot is product index ${Fletching.productIndexOf(slot)}."
            }
            return false
        }
        if (!player.client.channel.isActive) return false
        val count = session.countOf(player, row).coerceAtLeast(0)
        session.selected = row
        val w = player.client
        w.write(VarpLarge(VARP_PRODUCT, row.itemId))
        w.write(VarpSmall(VARP_COUNT, count))
        w.write(VarpSmall(VARP_COUNT_MIRROR, count))
        row.examine?.let { w.write(ClientSetvarcstrSmall(VARC_EXAMINE, it)) }
        w.write(ClientSetvarcSmall(VARC_MAKEABLE, if (count > 0) 1 else 0))
        w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, QUANTITY), 0, count, GRID_MASK))
        productClicks++
        logger.info {
            "make-X: ${player.name} selected slot $slot -> ${row.name} (item ${row.itemId}), count $count. " +
                "The five-frame reply is the reference client's (09-07T01-06-17 t371/t374/t44/t56)."
        }
        return true
    }

    /**
     * `IF_BUTTON1 1371:28` - the player opened the MATERIAL dropdown.
     */
    fun handleCategoryButton(player: WorldPlayer): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        if (session.categories.isEmpty()) {
            logger.info {
                "make-X: ${player.name} opened the material dropdown on $CONTROLS_IFACE:$CATEGORY_BUTTON but this " +
                    "panel has ONE category, so there is nothing to switch to. Nothing sent, exactly as the reference client sends " +
                    "nothing for this frame."
            }
            return true
        }
        session.dropdownOpen = true
        categoryButtons++
        logger.info {
            "make-X: ${player.name} opened the material dropdown (${session.categories.size} rows, currently " +
                "${session.categoryIndex} '${session.category()?.name}'). The reference client sends NOTHING for this frame " +
                "(4 of 4, 09-07T01-06-17 t29/t32/t37/t41) and neither does this; the choice arrives on " +
                "$TOPLEVEL:$CATEGORY_LIST."
        }
        return true
    }

    /**
     * `IF_BUTTON1 1477:896 arg2 = <row>` - the material the player picked out of the dropdown.
     *
     * THE DEFECT THIS CLOSES: "the multiple types of wood don't populate when you go to them, it
     * just stays as normal wood". The dropdown label moved because the CLIENT moves it; the grid
     * did not, because the grid is built from varp 1169 and this server never rewrote it.
     *
     * The reference client's reply, 4 of 4 (09-07T01-06-17 t30/t34/t39/t42) and reproduced here in the
     * same order:
     *
     * ```
     *   VARP_LARGE 1169 = enum(varp 1168)[row]      <- the new product list; the client rebuilds the grid
     *   VARP_SMALL 1170 = -1                        <- forced change, so the detail pane's listener fires
     *   VARP_LARGE 1170 = <the newly selected product item id>
     *   VARP_SMALL 8846 = <count>   VARP_SMALL 8847 = <count>
     *   CLIENT_SETVARCSTR 2391 = <the product's examine>       (only when there is one)
     *   IF_SETEVENTS 1371:22 0..(4 x entryCount) mask 2
     *   IF_SETEVENTS 1371:20 0..<count>          mask 2        (only when count > 0 - t42 sent it
     *                                                           at count 2, t30/t34/t39 did not at 0)
     * ```
     *
     * ## The nine questions
     *  1. this tick - the whole reply goes out now; nothing is scheduled.
     *  2. next tick - nothing. The client rebuilds the grid off varp 1169.
     * 3. the actor moves - irrelevant; no packet here depends on position, and the reference client closes no
     *     panel on movement (§2 of the class doc).
     *  4. the target disappears - the material is re-counted HERE and again at the confirm through
     *     [Session.countOf], so a bank deposit between the two makes fewer, never a debt.
     *  5. the actor dies - the panel is a UI; the action it may later start owns death.
     *  6. logout / disconnect - `channel.isActive` is checked before the first write and the session
     *     map is weakly keyed, so a dropped player leaks nothing.
     *  7. the request repeats - a second 1477:896 without a fresh [CATEGORY_BUTTON] is REFUSED by
     *     [Session.dropdownOpen], and a repeat of the SAME row is idempotent: it re-sends the same
     *     varps, which is a value-change-free write the client ignores.
     *  8. persistence fails - nothing is persisted.
     *  9. two actors - the session map is per-player; there is no shared object.
     */
    fun handleCategoryChoice(player: WorldPlayer, row: Int): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        if (session.categories.isEmpty()) return false
        // THE GATE. 1477:896 is shared by every dropdown in the gameframe, so a frame on it is only
        // this panel's while this panel opened the list. Without this, a make-X panel left open
        // behind another window would re-key itself on somebody else's dropdown.
        if (!session.dropdownOpen) {
            logger.info {
                "make-X: ${player.name} sent $TOPLEVEL:$CATEGORY_LIST row $row with no open material dropdown - " +
                    "not this panel's frame, ignored."
            }
            return false
        }
        session.dropdownOpen = false
        val category = session.categories.firstOrNull { it.index == row } ?: run {
            logger.info {
                "make-X: ${player.name} chose material row $row, which is not one of this panel's " +
                    "${session.categories.map { it.index }} - refused, and varp $VARP_RECIPE is left alone."
            }
            return true
        }
        if (!player.client.channel.isActive) return true
        // The row the new category opens on: the lowest grid slot this server can actually make.
        // A category with nothing makeable keeps the selection but reports a count of 0, which is
        // the state the reference client was in at t30/t34/t39 (varp 8846 = 0, no quantity arming).
        val selected = category.rows.entries.minByOrNull { it.key }?.value ?: session.selected
        session.categoryIndex = row
        session.selected = selected
        val count = session.countOf(player, selected).coerceAtLeast(0)

        val w = player.client
        w.write(VarpLarge(VARP_RECIPE, category.recipeEnum))
        // -1 FIRST, then the value. The reference client does this and the reason is in the cache: varp listeners
        // fire on VALUE CHANGE ONLY, so re-selecting the same product across a category switch would
        // rebuild nothing without the forced change. on all four switches.
        w.write(VarpSmall(VARP_PRODUCT, -1))
        w.write(VarpLarge(VARP_PRODUCT, selected.itemId))
        w.write(VarpSmall(VARP_COUNT, count))
        w.write(VarpSmall(VARP_COUNT_MIRROR, count))
        selected.examine?.let { w.write(ClientSetvarcstrSmall(VARC_EXAMINE, it)) }
        w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, PRODUCT_GRID), 0, category.gridToSlot, GRID_MASK))
        if (count > 0) w.write(IfSetevents(InterfaceHash(CONTROLS_IFACE, QUANTITY), 0, count, GRID_MASK))
        categoryChoices++
        logger.info {
            "make-X: ${player.name} switched the material to row $row '${category.name}' - varp $VARP_RECIPE = " +
                "${category.recipeEnum}, which is the enum the CLIENT rebuilds the grid from; grid armed " +
                "0..${category.gridToSlot}, ${category.rows.size} of its rows are makeable here, selection " +
                "${selected.name} (item ${selected.itemId}), count $count."
        }
        return true
    }

    /**
     * A click on the quantity selector. **Consumed and deliberately not acted on**: the reference client arms
     * `0..8846` for it and the corpus contains no click, so `slot -> count` is unmeasured and
     * `count = slot` and `count = slot + 1` are equally consistent with every sample. Acting on a
     * guess here would make the panel silently produce the wrong number of items.
     *
     * Returns true so the frame does not fall through to the generic log as an unknown button;
     * the line below IS the record, and it carries everything the next observation needs.
     */
    fun handleQuantityClick(player: WorldPlayer, slot: Int): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        quantityClicks++
        val count = session.countOf(player, session.selected)
        logger.warn {
            "make-X: ${player.name} clicked the QUANTITY selector $CONTROLS_IFACE:$QUANTITY slot $slot while " +
                "varp $VARP_COUNT = $count. Not yet settled: the reference client arms this component 0.$VARP_COUNT " +
                "and nothing observed contains a single click on it, so slot -> count is unmeasured " +
                "and this server will still make $count. THIS LINE IS THE MEASUREMENT the next observation needs."
        }
        return true
    }

    /**
     * `RESUME_PAUSEBUTTON 1370:30` - the confirm. Closes the panel exactly as the reference client does and
     * hands the count to [Session.onConfirm].
     *
     * A SECOND confirm finds no session and is refused, which is why [close] runs before
     * [Session.onConfirm] rather than after: the callback may itself open something.
     */
    fun handleConfirm(player: WorldPlayer): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        val row = session.selected
        val count = session.countOf(player, row).coerceAtLeast(0)
        closePanel(player, session, row)
        if (count <= 0) {
            logger.info {
                "make-X: ${player.name} confirmed ${row.name} with nothing to make it from - the panel is closed " +
                    "and no action starts. The reference client's varc 2223 was already 0 in that state."
            }
            return true
        }
        confirms++
        val started = runCatching { session.onConfirm(player, row, count) }
            .onFailure { logger.error(it) { "make-X: the confirm callback threw for ${player.name}; the panel is closed." } }
            .getOrDefault(false)
        logger.info {
            "make-X: ${player.name} confirmed ${count} x ${row.name} (item ${row.itemId}) from " +
                "${session.materialName}; the action ${if (started) "started" else "was REFUSED by the module"}. " +
                "The count is varp $VARP_COUNT, which is this server's own number - as the reference client's rule " +
                "(varc 2228 = 8846 on every confirm), not as a player choice."
        }
        return true
    }

    /** `IF_BUTTON1 1370:32` - the Close button, and anything else that must drop the panel. */
    fun handleClose(player: WorldPlayer): Boolean {
        if (!enabled) return false
        val session = sessions[player] ?: return false
        closePanel(player, session, session.selected)
        logger.info { "make-X: ${player.name} closed the panel; no action started." }
        return true
    }

    /** Drops the session without writing anything - for a logout or a forced teardown. */
    fun forget(player: WorldPlayer) { sessions.remove(player) }

    private fun closePanel(player: WorldPlayer, session: Session, row: Row) {
        sessions.remove(player)
        closes++
        if (!player.client.channel.isActive) return
        val w = player.client
        // The reference client's close block, in the reference client's order (09-07T01-06-17 t378, 09-04T06-41-52 t423).
        w.write(VarpLarge(VARP_CONFIRMED, row.itemId))
        w.write(VarpSmall(VARP_COUNT, 0))
        w.write(VarpSmall(VARP_COUNT_MIRROR, 0))
        w.write(VarpSmall(VARP_CATEGORY_A, -1))
        w.write(VarpSmall(VARP_RECIPE, -1))
        w.write(VarpSmall(VARP_PRODUCT, -1))
        w.write(VarpSmall(VARP_ZERO, 0))
        w.write(VarpSmall(VARP_CATEGORY_B, -1))
        w.write(UpdateInvStopTransmit(PRODUCT_INV))
        w.write(VarpLarge(VARP_LAST_MADE, row.itemId))
        w.write(IfClosesub(InterfaceHash(IFACE, CONTROLS_MOUNT)))
        w.write(IfClosesub(InterfaceHash(TOPLEVEL, MOUNT)))
        w.write(
            RunClientScript(
                SCRIPT_CLOSE,
                arrayOf(IFACE, (TOPLEVEL shl 16) or MOUNT, SCRIPT_CLOSE_ARG3)
            )
        )
    }

    fun describe(): String =
        "make-X panel: $IFACE mounts at $TOPLEVEL:$MOUNT with $CONTROLS_IFACE at $IFACE:$CONTROLS_MOUNT; the " +
            "product rows are CLIENT-BUILT from varp $VARP_RECIPE (cache listener on $CONTROLS_IFACE:$PRODUCT_GRID), " +
            "the confirm is RESUME_PAUSEBUTTON on $IFACE:$CONFIRM (optmask 1 = the pausebutton bit), and the count " +
            "is varp $VARP_COUNT, the server's own. The MATERIAL dropdown is two frames: " +
            "$CONTROLS_IFACE:$CATEGORY_BUTTON opens it and reference answers NOTHING (4/4), then the row arrives as " +
            "$TOPLEVEL:$CATEGORY_LIST arg2=<index> and the reply is varp $VARP_RECIPE = enum(varp " +
            "$VARP_CATEGORY_A)[index]. Grid slot = ${Fletching.GRID_STRIDE} x index + 1 " +
            ". The player-chosen count has NO observed wire form (F29): " +
            "$CONTROLS_IFACE:$QUANTITY is armed for it and no observation clicks it."
}
