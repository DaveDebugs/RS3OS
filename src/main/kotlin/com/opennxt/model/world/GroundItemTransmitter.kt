package com.opennxt.model.world

import com.opennxt.OpenNXT
import com.opennxt.net.game.serverprot.ObjAdd
import com.opennxt.net.game.serverprot.ObjDel
import com.opennxt.net.game.serverprot.UpdateZonePartialFollows
import com.opennxt.net.game.serverprot.ZoneCoord
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * Puts [GroundItems] on the wire.
 *
 * [GroundItems] has existed for a while and has never had a network path: it
 * spawned, timed out and could be picked up, and no client was ever told any of
 * it. This is that path, and nothing else - it owns no state about what items
 * exist, only about what each client has been TOLD.
 */
object GroundItemTransmitter {

    private val logger = KotlinLogging.logger { }

    /** Master switch. Default ON. */
    val enabled: Boolean = System.getProperty("opennxt.experiment.groundItems") != "false"

    /**
     * Whether to drop a small demo pile near the login point at boot.
     *
     * **DEFAULT ON**, and that is a deliberate, stated choice rather than an
     * oversight: nothing else on this server currently spawns a ground item
     * (the drop-table path only fires when an NPC dies, and no NPC on this
     * server can be killed), so with this off the whole ground-item path is
     * live but has nothing to carry, and "I see no items" would be
     * indistinguishable between "the wire is broken" and "there are no items".
     *
     * `-Dopennxt.experiment.groundItems.demo=false` removes it. The items it
     * spawns are ordinary [GroundItem]s on the ordinary despawn timer; nothing
     * about them is special-cased downstream.
     */
    val demoEnabled: Boolean = System.getProperty("opennxt.experiment.groundItems.demo") != "false"

    /**
     * What the demo drops, and where.
     *
     * Every id is verified to exist in the served cache, both as a row in
     * `data/rs3.sqlite`'s `items` table and by decoding the raw js5 record -
     * the same four ids and the same two-source verification
     * [com.opennxt.model.entity.player.PlayerInventory.starterKit] already
     * uses, re-run here rather than trusted:
     *
     * ```
     * 995 Coins js5[19, 3:227] stackable
     * 1265 Bronze pickaxe js5[19, 4:241]
     * 1351 Bronze hatchet js5[19, 5:71]
     * 590 Tinderbox js5[19, 2:78]
     * ```
     */
    /**
     * FOUR EXTRA DROPS, OFF BY DEFAULT, for one specific measurement.
     *
     * `-Dopennxt.experiment.iconProbeDrops=true` adds these on the login tile.
     *
     * WHY THEY EXIST. Item icons do not paint, and live instrumentation
     * (protocol/RENDER-PATH-LIVE.md) localised the break exactly:
     */
    private val iconProbeDrops: List<DemoDrop> = listOf(
        DemoDrop(1205, "Bronze dagger", 1, 3222, 3222),
        DemoDrop(1277, "Bronze sword", 1, 3223, 3222),
        DemoDrop(2309, "Bread", 1, 3222, 3223),
        DemoDrop(315, "Shrimps", 1, 3223, 3223)
    )

    val demoDrops: List<DemoDrop> = listOf(
        DemoDrop(995, "Coins", 25, 3224, 3222),
        DemoDrop(1265, "Bronze pickaxe", 1, 3224, 3223),
        DemoDrop(1351, "Bronze hatchet", 1, 3223, 3224),
        DemoDrop(590, "Tinderbox", 1, 3225, 3222)
    ) + if (System.getProperty("opennxt.experiment.iconProbeDrops") == "true") iconProbeDrops
        else emptyList()


    data class DemoDrop(val itemId: Int, val name: String, val quantity: Int, val x: Int, val y: Int)

    /**
     * Ticks a demo drop survives.
     *
     * [GroundItems.DESPAWN_TICKS] is 200 ticks = two minutes, which is correct
     * for a real drop and useless for a demo: the thing the operator logged in
     * to look at would be gone before they walked to it. This is not a claim
     * about RS behaviour - it is a demo lifetime, named separately so it cannot
     * be mistaken for one.
     */
    const val DEMO_DESPAWN_TICKS = Int.MAX_VALUE / 2

    // ------------------------------------------------------------- per-player

    /**
     * What each player has been told, keyed weakly so a logout cannot leak.
     * Same shape as [com.opennxt.model.entity.player.PlayerInventory]'s
     * backpack map, and for the same reason: [WorldPlayer] is not this
     * change's to add fields to.
     */
    private val sent: MutableMap<WorldPlayer, SentState> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, SentState>())

    /**
     * One player's view of the ground.
     *
     * [quantities] is keyed by IDENTITY, not by (id, tile): two stacks of the
     * same item on the same tile are two [GroundItem]s in the model and must
     * stay two entries here, or picking one up would look like picking up both.
     */
    private class SentState {
        var epoch: Int = -1
        val quantities: MutableMap<GroundItem, Int> = java.util.IdentityHashMap()
    }

    // ------------------------------------------------------------- demo spawn

    /** One-shot latch: the demo pile is world state, not per-player state. */
    @Volatile
    private var demoSpawned = false

    /**
     * Drops the demo pile into [items], once per process.
     *
     * Called from [sync] rather than from world boot because the world is
     * constructed in several contexts (tools, checks, the live server) and only
     * the live one should acquire scenery. A player ticking is the earliest
     * moment that is unambiguously the live server.
     */
    @Synchronized
    fun spawnDemo(items: GroundItems): List<GroundItem> {
        if (demoSpawned || !demoEnabled) return emptyList()
        demoSpawned = true
        val spawned = demoDrops.map {
            items.spawnItem(
                itemId = it.itemId,
                itemName = it.name,
                quantity = it.quantity,
                tile = TileLocation(it.x, it.y, 0),
                owner = null,
                source = "GroundItemTransmitter.demoDrops (-Dopennxt.experiment.groundItems.demo=false to remove)",
                ticksRemaining = DEMO_DESPAWN_TICKS
            )
        }
        logger.warn {
            "DEMO: dropped ${spawned.size} ground item(s) near the login point - " +
                spawned.joinToString(", ") { "${it.itemName} x${it.quantity} @(${it.tile.x},${it.tile.y})" } +
                ". Turn off with -Dopennxt.experiment.groundItems.demo=false"
        }
        return spawned
    }

    /** Test seam: lets a check drive [spawnDemo] more than once per process. */
    internal fun resetDemoLatch() {
        demoSpawned = false
    }

    // ------------------------------------------------------------- the tick

    /**
     * Brings [player]'s client up to date with the ground.
     *
     * Called once per tick from [WorldPlayer.tick]. Degrades - once, with a
     * warning - when this build's table has no opcode for the two packets it
     * needs, exactly as PLAYER_INFO and UPDATE_INV_FULL already do. An unmapped
     * packet must never take a tick down.
     */
    fun sync(player: WorldPlayer) {
        if (!enabled) return

        // ------------------------------------------------------------------
 // THE LOOT WINDOW'S TICK.
        //
        // [com.opennxt.content.impl.LootWindow] needs one call per player per
        // tick to close a window whose pile has emptied or whose player has
        // walked away, and to re-send a pile that changed. This is that call,
        // and it is HERE rather than in WorldPlayer.tick for two reasons: this
        // function is already the once-per-player-per-tick ground-item pass, and
        // WorldPlayer.kt was owned by other work on the day this landed.
        //
        // ABOVE opcodesAvailable() on purpose. That guard is about OBJ_ADD /
        // OBJ_DEL / UPDATE_ZONE_PARTIAL_FOLLOWS; the loot window needs none of
        // the three (UPDATE_INV_FULL, IF_OPENSUB, IF_SETEVENTS), so a build
        // missing the zone opcodes must not silently freeze an open window.
        // Below `enabled`, also on purpose: with the ground-item wire off there
        // is nothing on the client's ground to loot.
        //
        // Its own try/catch: a content bug must not take a tick down, which is
        // the same rule the syncUnguarded call below already follows.
        try {
            com.opennxt.content.impl.LootWindow.refresh(player)
        } catch (t: Throwable) {
            if (!lootFailureWarned) {
                lootFailureWarned = true
                logger.error(t) {
                    "Loot-window refresh failed for ${player.name}; it will keep being attempted. " +
                        "Run with -Dopennxt.experiment.lootWindow=false to take it out of the picture. (Warned once.)"
                }
            }
        }

        if (!opcodesAvailable()) return

        val world = runCatching { OpenNXT.world }.getOrNull() ?: return
        spawnDemo(world.groundItems)

        try {
            syncUnguarded(player, world.groundItems)
        } catch (t: Throwable) {
            if (!failureWarned) {
                failureWarned = true
                logger.error(t) {
                    "Ground-item transmit failed for ${player.name}; it will keep being attempted. " +
                        "Run with -Dopennxt.experiment.groundItems=false to take it out of the picture. (Warned once.)"
                }
            }
        }
    }

    private fun syncUnguarded(player: WorldPlayer, items: GroundItems) {
        val viewport = player.viewport
        val state = sent.getOrPut(player) { SentState() }

        // The scene moved, so the client threw away every zone's contents.
        // Forget what we think it knows rather than trying to work out what
        // survived - nothing did.
        if (state.epoch != viewport.sceneEpoch) {
            if (state.epoch != -1 && state.quantities.isNotEmpty()) {
                logger.info {
                    "Scene rebuilt for ${player.name} (epoch ${state.epoch} -> ${viewport.sceneEpoch}); " +
                        "re-sending ${state.quantities.size} ground item(s) the client just discarded"
                }
            }
            state.epoch = viewport.sceneEpoch
            state.quantities.clear()
        }

        val plane = player.entity.location.plane
 // (audit E-07, I-12/I-45): inside its owner-only window a drop is sent to
        // its owner only; it appears to everyone else when the window closes.
        val visible = items.all().filter {
            it.tile.plane == plane && viewport.containsTile(it.tile.x, it.tile.y) &&
                (it.isPublic || it.owner == player.name)
        }
        val visibleSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<GroundItem, Boolean>())
        visibleSet.addAll(visible)

        // Removals first. An item that left the model (despawned, taken) or
        // fell out of the scene must be deleted before anything is added, so a
        // delete-then-add for a quantity change cannot be reordered into an
        // add-then-delete by a later loop.
        val gone = state.quantities.keys.filter { it !in visibleSet }
        for (item in gone) {
            sendDel(player, item)
            state.quantities.remove(item)
        }

        for (item in visible) {
            val known = state.quantities[item]
            if (known == item.quantity) continue
            if (known != null) {
                // A stack that shrank (partial pickup). Delete-then-add; see
                // the class doc for why not OBJ_COUNT.
                sendDel(player, item)
            }
            sendAdd(player, item)
            state.quantities[item] = item.quantity
        }
    }

    // ------------------------------------------------------------- the sends

    /**
     * Cursor + `OBJ_ADD`.
     *
     * The id goes out RAW. This is the one line in this file where copying the
     * inventory convention would be silent: `UPDATE_INV_FULL` puts `objId + 1`
     */
    private fun sendAdd(player: WorldPlayer, item: GroundItem) {
        val v = player.viewport
        for (packet in addPackets(v, item)) player.client.write(packet)
        addsSent++
        if (adds < 8) {
            adds++
            logger.info {
                "OBJ_ADD #$adds to ${player.name}: ${item.itemName} (obj ${item.itemId}) x${item.quantity} at " +
                    "(${item.tile.x},${item.tile.y},p${item.tile.plane}) -> zone " +
                    "(${v.zoneX(item.tile.x)},${v.zoneY(item.tile.y)}) coord " +
                    "0x%02x, scene chunk (${v.chunkX},${v.chunkY})".format(
                        ZoneCoord.coord(item.tile.x, item.tile.y)
                    )
            }
        }
    }

    /** Cursor + `OBJ_DEL`. */
    private fun sendDel(player: WorldPlayer, item: GroundItem) {
        val v = player.viewport
        // The tile may now be outside the scene (the item left because the
        // player walked away without a rebuild). The client's own sink
        // bounds-checks and drops it, but the signed-byte zone fields would
        // WRAP before it got that far, so refuse here instead.
        if (!v.containsTile(item.tile.x, item.tile.y)) return
        for (packet in delPackets(v, item)) player.client.write(packet)
        delsSent++
    }

    // -------------------------------------------------- the seam, and why

    /**
     * The exact packets an add is, as VALUES.
     */
    internal fun addPackets(v: com.opennxt.model.entity.player.Viewport, item: GroundItem): List<com.opennxt.net.game.GamePacket> = listOf(
        UpdateZonePartialFollows(item.tile.plane, v.zoneX(item.tile.x), v.zoneY(item.tile.y)),
        ObjAdd(
            coord = ZoneCoord.coord(item.tile.x, item.tile.y),
            count = item.quantity.coerceAtMost(0xFFFF),
            id = item.itemId
        )
    )

    /** The exact packets a delete is. See [addPackets]. */
    internal fun delPackets(v: com.opennxt.model.entity.player.Viewport, item: GroundItem): List<com.opennxt.net.game.GamePacket> = listOf(
        UpdateZonePartialFollows(item.tile.plane, v.zoneX(item.tile.x), v.zoneY(item.tile.y)),
        ObjDel(ZoneCoord.coord(item.tile.x, item.tile.y), item.itemId)
    )

    /** OBJ_ADD / OBJ_DEL sequences written since boot. Observable so a check can assert. */
    fun addsSent(): Int = addsSent

    fun delsSent(): Int = delsSent

    fun resetSendCounters() {
        addsSent = 0
        delsSent = 0
    }

    @Volatile
    private var addsSent = 0

    @Volatile
    private var delsSent = 0

    // ------------------------------------------------------------- degradation

    private fun opcodesAvailable(): Boolean {
        val names = OpenNXT.protocol.serverProtNames.values
        val missing = listOf("UPDATE_ZONE_PARTIAL_FOLLOWS", "OBJ_ADD", "OBJ_DEL").filter { names[it] == null }
        if (missing.isEmpty()) return true
        if (!unmappedWarned) {
            unmappedWarned = true
            logger.warn {
                "Build ${OpenNXT.protocol.effectiveBuild} has no opcode for ${missing.joinToString(", ")} - " +
                    "no ground item can be transmitted. Recover them into " +
                    "data/prot/<build>/serverProtNames.toml. (Warned once.)"
            }
        }
        return false
    }

    /**
     * Drops a player's sent-set.
     */
    fun forget(player: WorldPlayer) {
        sent.remove(player)
    }

    @Volatile
    private var unmappedWarned = false

    @Volatile
    private var failureWarned = false

    /** Warn-once latch for the loot-window refresh, kept separate so one cannot mask the other. */
    @Volatile
    private var lootFailureWarned = false

    @Volatile
    private var adds = 0
}
