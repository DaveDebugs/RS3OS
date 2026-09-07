package com.opennxt.content.impl

import com.opennxt.OpenNXT
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.GroundItem
import com.opennxt.model.world.GroundItems
import com.opennxt.model.world.PickupResult
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.InvEntry
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.UpdateInvFull
import com.opennxt.net.game.serverprot.generated.SynthSound
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * RS3's LOOT WINDOW - `toplevel_v2_loot`, interface 1622 at 1477:705.
 */
object LootWindow {
    private val logger = KotlinLogging.logger { }

    // ------------------------------------------------------------- the numbers

    /** `toplevel_v2_loot`. */
    const val LOOT_IFACE = 1622

    /** The gameframe dock the reference client mounts it at: 1477:705. 34/34. */
    const val LOOT_PARENT = 1477
    const val LOOT_DOCK = 705

    /** The item grid. Clicks arrive as IF_BUTTON on this component. 34/34. */
    const val SLOT_LAYER = 11

    /** "Loot All". 34 IF_BUTTON1s, always `mid` 0xffffff / `arg2` 0xffff. */
    const val LOOT_ALL_BUTTON = 21

    /** The window's Close X. 4 IF_BUTTON1s. */
    const val CLOSE_BUTTON = 8

    /** The value/alch readout. The reference client writes it; this server does not - see the class doc. */
    const val VALUE_TEXT_COMPONENT = 3

    /** The loot inventory. Cache: 1622:4's inv listener. Wire: 422 messages. */
    const val LOOT_INV = 773

    /** js5[2, 5:773]'s declared size, and the range the reference client arms. */
    const val LOOT_SLOTS = 28

    /**
     * The mask the reference client arms on the grid: `6291462` = `0x600006`.
     */
    const val SLOT_EVENT_MASK = 6291462

    /** RUNCLIENTSCRIPT ids reference runs on the open, in this order. 21/21 at +1 tick. */
    const val SCRIPT_SHOW = 8862       // args [WINDOW_ID, 1]
    const val SCRIPT_LAYOUT = 2651     // args [WINDOW_ID, 0]
    const val SCRIPT_REFRESH = 11413   // args []

    /**
     * The loot window's own window id, 1028.
     *
     * Not read off the wire alone: it is the seventh argument of 1622:4's own
     * cached chrome script, `interfaces_attr[1622:4].scripts["37"] =
     * {script: 8420, args: [1622:5, 1622:7, 1622:6, 1622:8, "Loot", 21217, 1028]}`.
     * The wire's `8862 [1028, 1]` uses the same number, so cache and wire agree.
     */
    const val WINDOW_ID = 1028

    /** `SYNTH_SOUND` the reference client plays on a loot pickup. 200 of 258. */
    const val PICKUP_SOUND = 9704

    /** Chebyshev radius of the pile around the clicked tile. See the class doc. */
    val LOOT_RADIUS: Int = System.getProperty("opennxt.loot.radius")?.toIntOrNull() ?: 2

    /**
     * How far the player may wander from the pile before the window closes.
     *
     * RECONSTRUCTED. The reference client closes the window (24 `IF_CLOSESUB 1477:705`), but
     * nothing pins the trigger, so this is a chosen number and
     * says so. It is deliberately generous: closing too eagerly is a bug the
     * operator would see, and a window left open over an empty pile closes on
     * the empty-pile rule below anyway.
     */
    val CLOSE_RADIUS: Int = System.getProperty("opennxt.loot.closeRadius")?.toIntOrNull() ?: 16

    /**
     * Master switch. Default ON.
     */
    val enabled: Boolean get() = System.getProperty("opennxt.experiment.lootWindow") != "false"

    // ------------------------------------------------------------- the session

    /**
     * One row of the window: every [GroundItem] of one item id inside the pile.
     *
     * [items] is a list because the pile aggregates across tiles (class doc), and
     * it is ordered nearest-first so a partial take empties the closest stack
     * first. [quantity] is the sum, which is what goes on the wire.
     */
    data class Row(val itemId: Int, val items: List<GroundItem>) {
        val quantity: Int get() = items.sumOf { it.quantity }
        val name: String get() = items.firstOrNull()?.itemName ?: "item $itemId"
    }

    /** What one player's window is showing. Weakly keyed so a logout cannot leak. */
    private class Session(val x: Int, val y: Int, val plane: Int) {
        /** The last row list SENT, as (id, quantity) pairs - the thing a resend is compared against. */
        var sent: List<Pair<Int, Int>> = emptyList()
    }

    private val sessions: MutableMap<WorldPlayer, Session> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, Session>())

    /** Whether [player] has the loot window open. Observable so a check can assert it. */
    fun isOpen(player: WorldPlayer): Boolean = sessions[player] != null

    /** The tile the open window is centred on, or null. */
    fun centreOf(player: WorldPlayer): Triple<Int, Int, Int>? =
        sessions[player]?.let { Triple(it.x, it.y, it.plane) }

    // ------------------------------------------------------------- the pile

    /**
     * The rows a pile centred on ([x], [y], [plane]) holds for [player].
     *
     * Visibility is the SAME rule [com.opennxt.model.world.GroundItemTransmitter]
     * applies - public, or owned by this player - so the window can never list an
     * item the client was not sent, and a second player's owner-window drop stays
     * invisible instead of appearing and then refusing.
     *
     * Rows are ordered by distance from the centre, then by item id, so the slot
     * numbering is stable between two sends of the same pile. An unstable order
     * would make `arg2` mean a different row than the one the client clicked.
     */
    fun rowsAt(player: WorldPlayer, x: Int, y: Int, plane: Int, radius: Int = LOOT_RADIUS): List<Row> {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return emptyList()
        val visible = world.groundItems.all().filter {
            it.tile.plane == plane &&
                Math.abs(it.tile.x - x) <= radius && Math.abs(it.tile.y - y) <= radius &&
                (it.isPublic || it.owner == player.name)
        }
        fun dist(g: GroundItem) = maxOf(Math.abs(g.tile.x - x), Math.abs(g.tile.y - y))
        return visible.groupBy { it.itemId }
            .map { (id, items) -> Row(id, items.sortedBy { dist(it) }) }
            .sortedWith(compareBy({ dist(it.items.first()) }, { it.itemId }))
    }

    // ------------------------------------------------------------- opening

    /**
     * Opens (or refreshes) the loot window on the pile around ([x], [y], [plane]).
     *
     * Returns false and sends nothing when there is nothing there - an empty
     * window is worse than no window, and it is also what a click on ground the
     * server does not believe in would produce.
     *
     * The packet ORDER is the reference client's and is not cosmetic: the inventory goes out
     * BEFORE the `IF_OPENSUB`, because 1622:4's cached inv-773 listener is what
     * builds the grid, and the arming goes out after the open because
     * `IF_SETEVENTS` for an interface that is not mounted sits unused (this
     * tree's [com.opennxt.model.entity.player.InterfaceManager.events] warns
     * about exactly that).
     */
    fun open(player: WorldPlayer, x: Int, y: Int, plane: Int): Boolean {
        if (!enabled) return false
        val rows = rowsAt(player, x, y, plane)
        if (rows.isEmpty()) return false

        val fresh = sessions[player] == null || centreOf(player) != Triple(x, y, plane)
        val session = Session(x, y, plane)
        sessions[player] = session

        sendRows(player, session, rows)
        if (fresh) {
            // The mount is the one part of this that can throw: InterfaceManager
            // refuses to open a sub-interface on a player with no root, and this
            // runs on the packet-decode path where an escaping exception would
            // take the connection (and, on the tick thread, the tick) with it.
            // A window that failed to mount must not leave a session behind
            // either, or every later refresh would send an inventory for a
            // window that is not on screen.
            try {
                player.interfaces.open(
                    id = LOOT_IFACE, parent = LOOT_PARENT, component = LOOT_DOCK,
                    walkable = true, native949 = true
                )
                player.client.write(RunClientScript(SCRIPT_SHOW, arrayOf(WINDOW_ID, 1)))
                player.client.write(RunClientScript(SCRIPT_LAYOUT, arrayOf(WINDOW_ID, 0)))
                player.interfaces.events(
                    id = LOOT_IFACE, component = SLOT_LAYER, from = 0, to = LOOT_SLOTS - 1,
                    mask = SLOT_EVENT_MASK
                )
                player.client.write(RunClientScript(SCRIPT_REFRESH, emptyArray()))
            } catch (t: Throwable) {
                sessions.remove(player)
                logger.error(t) {
                    "loot ${player.name}: could not mount $LOOT_IFACE at $LOOT_PARENT:$LOOT_DOCK; " +
                        "no window is open and nothing was taken."
                }
                return false
            }
        }
        logger.info {
            "loot ${player.name}: window ${if (fresh) "opened" else "re-centred"} on ($x,$y,p$plane) " +
                "with ${rows.size} row(s): " + rows.joinToString(", ") { "${it.name} x${it.quantity}" }
        }
        return true
    }

    /** Closes the window if one is open. Idempotent. */
    fun close(player: WorldPlayer, reason: String) {
        if (sessions.remove(player) == null) return
        player.interfaces.close(id = LOOT_PARENT, component = LOOT_DOCK, native949 = true)
        logger.info { "loot ${player.name}: window closed ($reason)" }
    }

    // ------------------------------------------------------------- the tick

    /**
     * One tick of an open window: close it if it has gone stale, resend if the
     * pile changed. Cheap and returns immediately for the players - almost all
     * of them - with no window open.
     *
     * Driven from [com.opennxt.model.world.GroundItemTransmitter.sync], which is
     * already the once-per-player-per-tick ground-item pass.
     */
    fun refresh(player: WorldPlayer) {
        val session = sessions[player] ?: return
        val here = player.entity.location
        if (here.plane != session.plane ||
            maxOf(Math.abs(here.x - session.x), Math.abs(here.y - session.y)) > CLOSE_RADIUS
        ) {
            close(player, "the player left the pile")
            return
        }
        val rows = rowsAt(player, session.x, session.y, session.plane)
        if (rows.isEmpty()) {
            close(player, "the pile is empty")
            return
        }
        if (rows.map { it.itemId to it.quantity } != session.sent) sendRows(player, session, rows)
    }

    /**
     * `UPDATE_INV_FULL` for inv 773, sized to the pile.
     *
     * FULL rather than PARTIAL, deliberately, and against the reference client's own habit
     * (it sends 394 PARTIALs to 28 FULLs): a PARTIAL is a per-slot patch whose
     * effect depends on what the client currently believes, and this server's
     * idea of that is reconstructed. A FULL replaces the container
     * unconditionally. It is the same argument
     * [com.opennxt.model.world.GroundItemTransmitter] makes for preferring
     * OBJ_DEL+OBJ_ADD over OBJ_COUNT, and the packet is a few tens of bytes.
     *
     * `flags = 0`: measured on all 422 the reference client inv-773 messages, and 0 is also
     * the namespace the CS2 container commands look up (see [UpdateInvFull]).
     */
    private fun sendRows(player: WorldPlayer, session: Session, rows: List<Row>) {
        val slots: List<InvEntry?> = rows.map { InvEntry(it.itemId, it.quantity) }
        player.client.write(UpdateInvFull(inv = LOOT_INV, slots = slots, flags = 0))
        session.sent = rows.map { it.itemId to it.quantity }
    }

    /** The exact packet a pile send is, as a VALUE, so a check drives the real thing. */
    internal fun invPacketFor(rows: List<Row>): UpdateInvFull =
        UpdateInvFull(inv = LOOT_INV, slots = rows.map { InvEntry(it.itemId, it.quantity) }, flags = 0)

    // ------------------------------------------------------------- the clicks

    /**
     * A click inside the loot window. True when this object has dealt with it.
     *
     * Wire it into `IfButtonNHandler.handle` beside the other content hooks.
     */
    fun handleButton(player: WorldPlayer, packet: IfButtonN): Boolean {
        if (!enabled) return false
        if (packet.interfaceId != LOOT_IFACE) return false
        when (packet.component) {
            CLOSE_BUTTON -> {
                close(player, "the player clicked the window's Close")
                return true
            }
            LOOT_ALL_BUTTON -> {
                lootAll(player)
                return true
            }
            SLOT_LAYER -> {
                takeSlot(player, packet.buttonOp, packet.mid, packet.arg2)
                return true
            }
        }
        return false
    }

    /**
     * Take one row.
     */
    internal fun takeSlot(player: WorldPlayer, op: Int, itemId: Int, slot: Int) {
        val session = sessions[player] ?: return
        val row = ItemOps.rowForOp(player, LOOT_IFACE, SLOT_LAYER, op)
        if (row != 0) {
            logger.info {
                "loot ${player.name}: refused op $op on the grid - it maps to row " +
                    "${row?.plus(1) ?: "nothing"} under the armed mask, and only row 1 (take) is routed."
            }
            return
        }
        val rows = rowsAt(player, session.x, session.y, session.plane)
        // The client's view can lag the pile by a tick, so BOTH of its claims are
        // checked and a disagreement refuses rather than picks the nearest thing.
        val target = rows.getOrNull(slot)
        if (target == null || target.itemId != itemId) {
            logger.info {
                "loot ${player.name}: refused a take - the client says slot $slot holds item $itemId, " +
                    "the server's pile has ${target?.itemId ?: "nothing"} there."
            }
            // refresh(), not sendRows(): the commonest reason for a mismatch is
            // that the pile emptied under the client, and that must CLOSE the
            // window rather than re-send an empty one.
            refresh(player)
            return
        }
        takeRow(player, target)
        refresh(player)
    }

    /**
     * Take every row, nearest first, stopping at the first refusal that is not
     * "this one is gone".
     *
     * A full backpack stops the sweep instead of grinding through 27 more rows
     * that cannot fit; a row that vanished between the click and here is skipped
     * and the rest still go.
     */
    internal fun lootAll(player: WorldPlayer) {
        val session = sessions[player] ?: return
        var taken = 0
        for (row in rowsAt(player, session.x, session.y, session.plane)) {
            // sendInventory = false: ONE backpack re-send at the end rather than
            // one per row. The reference client patches with UPDATE_INV_PARTIAL and can afford
            // per-row; a FULL per row on a 28-row pile is 28 whole containers on
            // the wire for one click.
            val result = takeRow(player, row, sendInventory = false)
            if (result == Outcome.TAKEN) taken++
            if (result == Outcome.BLOCKED) break
        }
        if (taken > 0) PlayerInventory.sendBackpack(player)
        logger.info { "loot ${player.name}: Loot All took $taken row(s)" }
        refresh(player)
    }

    /** What one row's take did, at the granularity Loot All has to branch on. */
    internal enum class Outcome { TAKEN, SKIPPED, BLOCKED }

    /**
     * Move one row into the backpack, stack by stack.
     *
     * [GroundItems.pickup] is the only thing that moves a unit, and its typed
     * refusals are honoured one for one - nothing here retries, re-adds, or
     * decides that a refusal was probably fine. The backpack is re-sent only
     * when something actually moved, for the reason
     * [com.opennxt.net.game.handlers.OpObjHandler.take] gives: a refused take
     * that still sends an inventory looks like a pickup that did nothing.
     */
    internal fun takeRow(player: WorldPlayer, row: Row, sendInventory: Boolean = true): Outcome {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return Outcome.BLOCKED
        val backpack = PlayerInventory.backpackOf(player)
        var moved = false
        var blocked = false
        for (item in row.items) {
            val before = item.quantity
            when (val result = world.groundItems.pickup(player.entity.location, item, backpack, player.name)) {
                is PickupResult.PickedUp -> {
                    moved = true
                    logger.info { "loot ${player.name}: took ${item.itemName} x$before (obj ${item.itemId})" }
                }
                is PickupResult.Partial -> {
                    moved = true
                    blocked = true
                    logger.info {
                        "loot ${player.name}: took ${result.add.added} of ${item.itemName} x$before; " +
                            "${result.add.remaining} left on the ground (no room for the rest)"
                    }
                }
                is PickupResult.ContainerFull -> {
                    blocked = true
                    logger.info { "loot ${player.name}: backpack full; ${item.itemName} stays on the ground" }
                }
                is PickupResult.TooFar -> {
                    blocked = true
                    logger.info {
                        "loot ${player.name}: ${result.distance} tile(s) from ${item.itemName} " +
                            "(max ${result.allowed}); walking to it instead of taking it"
                    }
                    com.opennxt.net.game.handlers.MoveGameClickHandler
                        .walk(player, item.tile.x, item.tile.y, "loot-window")
                }
                is PickupResult.NotOnGround ->
                    logger.info { "loot ${player.name}: ${item.itemName} is no longer on the ground" }
                is PickupResult.NotYours -> {
                    blocked = true
                    logger.info {
                        "loot ${player.name}: ${item.itemName} belongs to ${result.owner} for another " +
                            "${result.ticksUntilPublic} tick(s). Refused."
                    }
                }
            }
        }
        if (moved) {
            if (sendInventory) PlayerInventory.sendBackpack(player)
            // The sound stays per row even under Loot All: the reference client's Loot All
            // ticks carry 191 SYNTH_SOUNDs over 34 clicks, i.e. one per item and
            // not one per click.
            player.client.write(SynthSound(PICKUP_SOUND, 1, 0, 100, 256))
        }
        return when {
            blocked -> Outcome.BLOCKED
            moved -> Outcome.TAKEN
            else -> Outcome.SKIPPED
        }
    }
}
