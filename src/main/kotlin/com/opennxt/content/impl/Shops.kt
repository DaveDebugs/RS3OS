package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.content.LocContext
import com.opennxt.content.NpcContext
import com.opennxt.content.SqliteDefinitions
import com.opennxt.model.shops.ShopData
import mu.KotlinLogging

/**
 * Shops: dispatch validation and a clean seam for shop openers.
 */
object Shops {
    private val logger = KotlinLogging.logger { }

    /**
     * The documented hole, as a value the event carries. Stock absence is a
     * property of the RS3 cache ([ShopData.STOCK_PRESENT_IN_CACHE] = false,
     * established by [ShopData.absenceProbe]), not a TODO in this module.
     */
    const val STOCK_IS_NOT_IN_THE_CACHE =
        "STOCK_IS_NOT_IN_THE_CACHE: the RS3 cache carries no shop stock " +
            "(ShopData.STOCK_PRESENT_IN_CACHE=false, proved by absenceProbe); " +
            "stock=null is absence of data, not an empty shop"

    /** What a shop-opener handler returns on success, the way Banks returns "bank-opened". */
    const val OPENED = "shop-opened"

    /**
     * The module's observable output: a documented shop opener fired.
     *
     * [opener] is the [ShopData.ShopOpener] row itself - literal option text,
     * menu slot, members flag, and the archive-id provenance
     * ([ShopData.ShopOpener.archiveId], derived from the `_group`/`_file`
     * columns) alongside the game id, so the npc id-space trap stays visible
     * on every event.
     *
     * [stock] is **always null today** and [stockAbsence] says why, on the
     * event itself: SHOP STOCK IS NOT IN THE CACHE. See the class comment.
     *
     * Item VALUES are in the cache, and a consumer asks for them through
     * [itemValue] / [itemName] - straight delegations to [ShopData], so the
     * event surfaces exactly what the cache has and nothing it does not.
     */
    data class ShopOpenedEvent(
        val player: String,
        val opener: ShopData.ShopOpener,
        /** Null for every shop, by construction: [ShopData.stockFor]. */
        val stock: List<ShopData.ItemStack>?,
        val stockAbsence: String = STOCK_IS_NOT_IN_THE_CACHE
    ) {
        /** The item definition's value field - the number shop pricing derives from - or null when absent. */
        fun itemValue(itemId: Int): Long? = ShopData.itemValue(itemId)
        fun itemName(itemId: Int): String? = ShopData.itemName(itemId)
        override fun toString() =
            "ShopOpenedEvent($player @ $opener, stock=${stock ?: "null[STOCK_IS_NOT_IN_THE_CACHE]"})"
    }

    private val events = ArrayList<ShopOpenedEvent>()

    fun eventCount(): Int = events.size
    fun lastEvent(): ShopOpenedEvent? = events.lastOrNull()
    fun clear() = events.clear()

    // ---- handlers -------------------------------------------------------

    private fun fire(player: ContentPlayer, opener: ShopData.ShopOpener): Any {
        events.add(
            ShopOpenedEvent(
                player = player.name,
                opener = opener,
                stock = ShopData.stockFor(opener.gameId)  // null, by construction
            )
        )
        return OPENED
    }

    /**
     * The npc seam. Today: record the [ShopOpenedEvent] and answer
     * [OPENED]. Future work: open the shop interface and hand it
     * server-authored stock. Bindings are only ever created from
     * [ShopData.openersForNpc] rows, so the lookup here cannot miss; the
     * fallback return exists so a mismatch would be visible, not silent.
     */
    fun onOpenNpcShop(ctx: NpcContext): Any? {
        val opener = ShopData.openersForNpc(ctx.npcId).firstOrNull { it.option == ctx.action }
            ?: return "not-a-documented-shop-opener"
        return fire(ctx.player, opener)
    }

    /** The loc seam, same contract as [onOpenNpcShop] for the 8 scenery-object shops. */
    fun onOpenLocShop(ctx: LocContext): Any? {
        val opener = ShopData.openersForLoc(ctx.locId).firstOrNull { it.option == ctx.action }
            ?: return "not-a-documented-shop-opener"
        return fire(ctx.player, opener)
    }

    // ---- entry points ---------------------------------------------------

    /**
     * Every option the definitions declare for [gameId]: the codec's merged
     * actions (columns + `npcs_attr` `actions_3`/`actions_4`) plus the attr-row
     * shop options [ShopData] read from `npcs_attr` - which, post-merge, only
     * adds the `members_actions_*` openers the codec has no storage for. This
     * is what a rejection echoes, so the declared list a client sees is the
     * definition's whole answer, not a codec subset.
     */
    fun declaredForNpc(gameId: Int): List<String> {
        val cols = SqliteDefinitions.npc(gameId)?.actions?.filterNotNull() ?: emptyList()
        val attr = ShopData.openersForNpc(gameId).map { it.option }
        return (cols + attr).distinct()
    }

    /**
     * Dispatch a shop-opener option at an npc game id. **This is the module's
     * entry point**, carrying the residual seam described in the class comment:
     *
     *  1. [ContentRegistry.dispatchNpc] runs first. Anything but a rejection
     *     is final - Handled, NoHandler and UnknownTarget pass through. Since
     *     the codec merges `npcs_attr` `actions_3`/`actions_4`, this is where
     *     749 of the 756 documented opener pairs resolve.
     *  2. A registry rejection is re-checked against [ShopData.openersForNpc],
     *     but ONLY for a `members_actions_*`-sourced row
     * ([ShopData.ShopOpener.membersOnly] = true) - the measured 7-pair
     *     remainder [NpcDefinition][com.opennxt.resources.sqlite.NpcDefinition]
     *     has no storage for. A match means the definition IS declaring the
     *     option (in a members slot), so the opener fires. A non-members
     *     ShopData row can never reach this branch: post-merge the registry
     *     sees every one of those itself, so a rejection is authoritative.
     *  3. No match means genuinely undeclared: [DispatchResult.Rejected] in
     *     the registry's own shape, echoing [declaredForNpc].
     *
     * The seam validates against the definitions exactly as the registry does;
     * it only reads the members slots through [ShopData]'s wider window.
     */
    fun openForNpc(
        player: ContentPlayer,
        gameId: Int,
        option: String,
        npcIndex: Int = -1,
        x: Int = 0,
        z: Int = 0,
        plane: Int = 0
    ): DispatchResult {
        val viaRegistry = ContentRegistry.dispatchNpc(player, gameId, option, npcIndex, x, z, plane)
        if (viaRegistry !is DispatchResult.Rejected) return viaRegistry

        // Members-only remainder: the one shape of opener the codec cannot store.
        val opener = ShopData.openersForNpc(gameId).firstOrNull { it.option == option && it.membersOnly }
            ?: return DispatchResult.Rejected(gameId, option, declaredForNpc(gameId))
        return DispatchResult.Handled(fire(player, opener))
    }

    /**
     * The loc counterpart, for symmetry. The loc codec merges `locs_attr`, so
     * every loc opener is registry-visible and step 2 should never fire; it is
     * kept so both kinds carry the same contract, and so a future codec
     * regression degrades to the seam instead of to rejections.
     */
    fun openForLoc(
        player: ContentPlayer,
        locId: Int,
        option: String,
        x: Int,
        z: Int,
        plane: Int = 0
    ): DispatchResult {
        val viaRegistry = ContentRegistry.dispatchLoc(player, locId, option, x, z, plane)
        if (viaRegistry !is DispatchResult.Rejected) return viaRegistry

        val opener = ShopData.openersForLoc(locId).firstOrNull { it.option == option }
            ?: return DispatchResult.Rejected(
                locId, option,
                ((SqliteDefinitions.loc(locId)?.actions?.filterNotNull() ?: emptyList()) +
                    ShopData.openersForLoc(locId).map { it.option }).distinct()
            )
        return DispatchResult.Handled(fire(player, opener))
    }

    // ---- registration ---------------------------------------------------

    /** How install() went: registry bindings made, plus the openers only the [openForNpc]/[openForLoc] seam can reach. */
    data class Installed(val npcBound: Int, val npcSeamOnly: Int, val locBound: Int, val locSeamOnly: Int) {
        override fun toString() =
            "Installed(npc: $npcBound bound through the registry + $npcSeamOnly members_actions-only (seam), " +
                "loc: $locBound bound + $locSeamOnly seam-only)"
    }

    /**
     * Binds every [ShopData] opener the registry's definition view can see,
     * per-id: [ContentRegistry.onNpc] re-validates each (id, option) pair
     * against the npc definition exactly as [Banks] relies on
     * [ContentRegistry.onLoc], so a ShopData row the codec disagreed with
     * would refuse to bind rather than bind wrongly. With the codec merging
     * `npcs_attr` `actions_3`/`actions_4`, the only openers this view cannot
     * see are the `members_actions_*` ones (measured: 7 of 756 pairs, was
     * 210 of 756 pre-merge); they are counted, logged, and served by
     * [openForNpc]'s members seam - a documented split, not a silent one.
     */
    fun install(): Installed {
        var npcBound = 0
        var npcSeamOnly = 0
        val seenNpc = HashSet<Pair<Int, String>>()
        for (o in ShopData.openers().filter { it.kind == ShopData.OpenerKind.NPC }) {
            if (!seenNpc.add(o.gameId to o.option)) continue
            val def = SqliteDefinitions.npc(o.gameId)
            if (def != null && def.actions.any { it == o.option }) {
                ContentRegistry.onNpc(o.gameId, o.option, ::onOpenNpcShop)
                npcBound++
            } else {
                npcSeamOnly++  // declared only in members_actions_* (no NpcDefinition storage); openForNpc's seam serves it
            }
        }
        var locBound = 0
        var locSeamOnly = 0
        val seenLoc = HashSet<Pair<Int, String>>()
        for (o in ShopData.openers().filter { it.kind == ShopData.OpenerKind.LOC }) {
            if (!seenLoc.add(o.gameId to o.option)) continue
            val def = SqliteDefinitions.loc(o.gameId)
            if (def != null && def.actions.any { it == o.option }) {
                ContentRegistry.onLoc(o.gameId, o.option, ::onOpenLocShop)
                locBound++
            } else {
                locSeamOnly++  // measured 0 today: the loc codec merges locs_attr
            }
        }
        val result = Installed(npcBound, npcSeamOnly, locBound, locSeamOnly)
        logger.info {
            "shops: $result - stock is NOT in the cache " +
                "(STOCK_PRESENT_IN_CACHE=${ShopData.STOCK_PRESENT_IN_CACHE}); events carry stock=null with the marker"
        }
        return result
    }
}
