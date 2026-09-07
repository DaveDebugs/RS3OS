package com.opennxt.model.world

import com.opennxt.model.items.AddResult
import com.opennxt.model.items.ItemContainer

/**
 * Items lying on world tiles: what an NPC death leaves behind, until a player
 * picks it up or the despawn timer eats it.
 *
 * ## What is grounded here and what is not
 *
 * The ITEMS are grounded: every [GroundItem] comes from a [DeathResult] line
 * whose wiki item name resolved to real item ids in the definitions database
 * ([DroppedItem.itemIds]). The TIMING is not: the cache carries no ground-item
 * lifetime anywhere (it is server behaviour, like respawn timers -- see
 * [WorldNpc.RESPAWN_TICKS] for the same situation), so [DESPAWN_TICKS] is a
 * RECONSTRUCTED constant, documented at its declaration.
 *
 * ## Refusals are typed, never silent
 *
 * - A death line whose wiki item name resolved to NO item id cannot become a
 *   ground item -- there is no id to put in a container later, and inventing
 * one is exactly what this server refuses to do. Those lines come back in
 * [SpawnResult.skippedUnresolved], each with its reason, never silently
 *   vanishing.
 * - A death line whose quantity string did not parse ([DroppedItem.quantity]
 *   is null -- "nulls stay nulls") is skipped the same way: spawning "1" of it
 *   would be an invented amount wearing the costume of a drop.
 * - A wiki name that matched SEVERAL ids ([DroppedItem.itemIds].size > 1 --
 *   "Bones" matches 6 rows in this database) spawns under the LOWEST id.
 *   That tie-break is RECONSTRUCTED (the wiki page does not say which variant
 *   drops); it is recorded on the [GroundItem] via [GroundItem.itemIdCandidates]
 *   and [GroundItem.itemIdAmbiguous] so the choice stays visible.
 *
 * [DeathResult.unexpandedTableRolls] never reach this class at all: a
 * sub-table reference ("@nothing", "@herb_table") has no contents here by
 * design, and it stays reported on the DeathResult, not converted to items.
 */
class GroundItems {

    companion object {
        /**
         * Ticks a ground item survives before despawning.
         *
         * RECONSTRUCTED -- NOT IN THE CACHE. Ground-item lifetimes are server
         * behaviour; no param, config or map field in data/rs3.sqlite times
         * them. The publicly documented RS3 behaviour for a player-owned drop
         * is TWO phases: roughly 100 ticks (~60 s) visible only to the owner,
         * then roughly another 100 ticks visible to everyone, then gone.
         */
        const val DESPAWN_TICKS = 200

        /**
         * Ticks a player-owned drop is visible to, and takeable by, its owner only
         *. The two-phase model the KDoc above describes,
         * restored now that [GroundItemTransmitter] IS a per-player visibility layer: the
         * documented RS3 shape is ~60 s owner-only then ~60 s public, so half the total.
         */
        const val OWNER_ONLY_TICKS = 100

        /**
         * Chebyshev tile distance a player may pick up from.
         *
         * RECONSTRUCTED simplification: real RS walks the player ONTO the
         * item's tile before the take happens. This model has no queued
         * walk-to-interact intent, so "standing on or directly beside the
         * tile" (distance <= 1) stands in for "arrived at it". Distance 10 is
         * refused -- typed, not silently walked.
         */
        const val PICKUP_RANGE = 1
    }

    private val items = ArrayList<GroundItem>()

    // ------------------------------------------------------------- spawning

    /**
     * Spawns the resolvable drops of [death] on [tile] (snapshotted -- the
     * caller's TileLocation is mutable and this class must not follow an NPC
     * that respawns elsewhere).
     *
     * Every [DeathResult.dropped] line lands in exactly one of the two result
     * lists: spawned as a [GroundItem], or reported in
 * [SpawnResult.skippedUnresolved] with the reason it could not spawn.
     * Nothing is dropped on the floor of the code instead of the floor of the
     * world.
     */
    fun spawn(death: DeathResult, tile: TileLocation, owner: String?): SpawnResult {
        val spawned = ArrayList<GroundItem>()
        val skipped = ArrayList<SkippedUnresolved>()
        for (line in death.dropped) {
            if (line.itemIds.isEmpty()) {
                skipped += SkippedUnresolved(
                    itemName = line.itemName,
                    reason = "wiki item name resolved to no item id -- there is no id to ground, " +
                        "and no default item",
                    rarity = line.rarity,
                    source = line.source
                )
                continue
            }
            val qty = line.quantity
            if (qty == null) {
                skipped += SkippedUnresolved(
                    itemName = line.itemName,
                    reason = "wiki quantity string did not parse (quantity is null, and nulls stay " +
                        "nulls) -- spawning an invented amount is refused",
                    rarity = line.rarity,
                    source = line.source
                )
                continue
            }
            if (qty <= 0) {
                // audit E-03: a non-positive amount never grounds (the parser refuses inverted
                // ranges, and spawnItem already requires > 0; this is the same rule here).
                skipped += SkippedUnresolved(
                    itemName = line.itemName,
                    reason = "quantity $qty is not positive -- nothing to ground",
                    rarity = line.rarity,
                    source = line.source
                )
                continue
            }
            spawned += GroundItem(
                itemId = line.itemId ?: line.itemIds.min(), // canonical id, else the lowest (DropData.chosenItemId)
                itemIdCandidates = line.itemIds,
                itemName = line.itemName,
                quantity = qty,
                tile = TileLocation(tile.x, tile.y, tile.plane),
                owner = owner,
                source = line.source,
                ticksRemaining = DESPAWN_TICKS
            )
        }
        items += spawned
        return SpawnResult(spawned, skipped)
    }

    /**
     * Spawns ONE item that did not come from a drop table.
     *
     * [spawn] is the drop-table path and every one of its refusals exists
     * because a wiki line may not resolve. This path has nothing to resolve:
 * the caller already holds an item id, so there is no "unresolved" case
 * and no [SkippedUnresolved] to report - which is why it returns the
     * [GroundItem] directly rather than a [SpawnResult] that could only ever
     * have one element and an empty skip list.
     *
     * [itemIdCandidates] is the single id, so [GroundItem.itemIdAmbiguous] is
     * false: nothing was tie-broken here and the object must not claim it was.
     *
     * Used by the ground-item demo drop
     * ([com.opennxt.model.world.GroundItemTransmitter.spawnDemo]) and by
     * checks. The caller supplies [source] so a spawn can always say where it
     * came from.
     */
    fun spawnItem(
        itemId: Int,
        itemName: String,
        quantity: Int,
        tile: TileLocation,
        owner: String? = null,
        source: String = "server",
        ticksRemaining: Int = DESPAWN_TICKS
    ): GroundItem {
        require(quantity > 0) { "ground item quantity must be positive: $quantity" }
        val item = GroundItem(
            itemId = itemId,
            itemIdCandidates = listOf(itemId),
            itemName = itemName,
            quantity = quantity,
            tile = TileLocation(tile.x, tile.y, tile.plane),
            owner = owner,
            source = source,
            ticksRemaining = ticksRemaining
        )
        items += item
        return item
    }

    /**
     * Removes [item] from the ground with no container involved, returning
     * whether it was actually there.
     *
     * Distinct from [pickup]: that one is the player-facing operation with a
     * range check and a container to accept into. This is the world-facing one
     * - a script despawning a spawn, or a check tearing down. Kept separate so
     * neither can be reached by accident from the other's call site.
     */
    fun remove(item: GroundItem): Boolean = items.remove(item)

    // ------------------------------------------------------------- pickup

    /**
     * Attempts to move [item] from the ground into [into].
     *
     * Typed outcomes, one per way this can go:
     *
     *  - [PickupResult.NotOnGround]: the item is no longer here (picked up
     *    already, or despawned) -- picking up a memory is refused.
     *  - [PickupResult.TooFar]: [playerTile] is more than [PICKUP_RANGE] tiles
     *    (Chebyshev) away, or on another plane. The item stays grounded.
     *  - [PickupResult.ContainerFull]: [into] accepted NONE of it. The item
     *    stays on the ground, untouched -- a full backpack does not delete
     *    loot.
     *  - [PickupResult.Partial]: [into] accepted some units (a non-stackable
     *    item into a nearly-full container). The accepted units left the
     *    ground; the remainder STAYS grounded with its timer intact.
     *  - [PickupResult.PickedUp]: everything went in; the ground item is gone.
     */
    fun pickup(playerTile: TileLocation, item: GroundItem, into: ItemContainer, takerName: String? = null): PickupResult {
        if (item !in items) return PickupResult.NotOnGround(item)
 // (audit E-07, I-12): inside the owner-only window only the owner may take it.
        if (!item.isPublic && item.owner != takerName) return PickupResult.NotYours(item, item.owner!!, item.ownerOnlyTicksRemaining)
        if (playerTile.plane != item.tile.plane ||
            !playerTile.withinDistance(item.tile, PICKUP_RANGE)
        ) {
            val dist = maxOf(
                Math.abs(playerTile.x - item.tile.x),
                Math.abs(playerTile.y - item.tile.y)
            )
            return PickupResult.TooFar(item, distance = dist, allowed = PICKUP_RANGE)
        }
        val add: AddResult = into.add(item.itemId, item.quantity)
        return when {
            add.added == 0 -> PickupResult.ContainerFull(item)
            add.complete -> {
                items.remove(item)
                PickupResult.PickedUp(item, add)
            }
            else -> {
                item.quantity -= add.added
                PickupResult.Partial(item, add)
            }
        }
    }

    // ------------------------------------------------------------- ticking

    /**
     * One world tick: every ground item's despawn countdown advances, and the
     * items that reached zero leave the world. Returns what despawned this
     * tick, so a caller (or a check tool) can observe the loss instead of
     * inferring it. Driven from [World.tick].
     */
    fun tick(): List<GroundItem> {
        val despawned = ArrayList<GroundItem>()
        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            item.ticksRemaining--
            if (item.ownerOnlyTicksRemaining > 0) item.ownerOnlyTicksRemaining--
            if (item.ticksRemaining <= 0) {
                it.remove()
                despawned += item
            }
        }
        return despawned
    }

    // ------------------------------------------------------------- queries

    fun all(): List<GroundItem> = items

    fun count(): Int = items.size

    fun itemsAt(x: Int, y: Int, plane: Int = 0): List<GroundItem> =
        items.filter { it.tile.x == x && it.tile.y == y && it.tile.plane == plane }

    fun isOnGround(item: GroundItem): Boolean = item in items
}

/**
 * One stack of items on one tile.
 *
 * [itemId] is the id that will enter a container on pickup; when the wiki name
 * matched several ids, it is the lowest of [itemIdCandidates] (RECONSTRUCTED
 * tie-break, see [GroundItems]) and [itemIdAmbiguous] is true so the choice is
 * auditable. [quantity] shrinks on a [PickupResult.Partial]. [ticksRemaining]
 * counts down to despawn from [GroundItems.DESPAWN_TICKS].
 */
class GroundItem(
    val itemId: Int,
    /** Every id the wiki item name matched. Size > 1 means [itemId] is a tie-break. */
    val itemIdCandidates: List<Int>,
    val itemName: String,
    var quantity: Int,
    /** Private snapshot -- not shared with the entity whose death made it. */
    val tile: TileLocation,
    /** Name of the player whose kill produced this, or null for ownerless spawns. */
    val owner: String?,
    /** Wiki article URL of the drop line this came from. */
    val source: String,
    var ticksRemaining: Int,
    var ownerOnlyTicksRemaining: Int = if (owner == null) 0 else GroundItems.OWNER_ONLY_TICKS
) {
    val itemIdAmbiguous: Boolean get() = itemIdCandidates.size > 1

    /** Visible to, and takeable by, everyone. */
    val isPublic: Boolean get() = ownerOnlyTicksRemaining <= 0

    override fun toString() =
        "GroundItem($itemName id=$itemId${if (itemIdAmbiguous) " of $itemIdCandidates" else ""} " +
            "x$quantity @ (${tile.x},${tile.y},${tile.plane}) owner=${owner ?: "none"} " +
            "despawn=${ticksRemaining}t)"
}

/**
 * One death line that could NOT become a ground item, with its reason. These
 * are surfaced by [GroundItems.spawn] -- a drop the data cannot ground is a
 * reported fact, never a silent disappearance.
 */
data class SkippedUnresolved(
    val itemName: String,
    val reason: String,
    val rarity: String,
    val source: String
) {
    override fun toString() = "SkippedUnresolved($itemName @ $rarity: $reason)"
}

/**
 * What [GroundItems.spawn] did with one [DeathResult]: every dropped line is
 * in exactly one of the two lists.
 */
data class SpawnResult(
    val spawned: List<GroundItem>,
    val skippedUnresolved: List<SkippedUnresolved>
) {
    override fun toString() =
        "SpawnResult(spawned=${spawned.map { "${it.itemName} x${it.quantity}" }}, " +
            "skippedUnresolved=$skippedUnresolved)"
}

/** Typed outcome of one pickup attempt. See [GroundItems.pickup]. */
sealed class PickupResult {
    /** The item was not on the ground (already taken, or despawned). */
    data class NotOnGround(val item: GroundItem) : PickupResult()

    /** Player too far away ([distance] > [allowed] Chebyshev tiles, or wrong plane). Item stays. */
    data class TooFar(val item: GroundItem, val distance: Int, val allowed: Int) : PickupResult()

    /** The container took nothing. The item is STILL ON THE GROUND, untouched. */
    data class ContainerFull(val item: GroundItem) : PickupResult()

    data class NotYours(val item: GroundItem, val owner: String, val ticksUntilPublic: Int) : PickupResult()

    /** The container took [add].added units; the rest stays grounded on its timer. */
    data class Partial(val item: GroundItem, val add: AddResult) : PickupResult()

    /** Everything went into the container; the ground item is gone. */
    data class PickedUp(val item: GroundItem, val add: AddResult) : PickupResult()
}
