package com.opennxt.content

import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.NpcDefinition
import com.opennxt.resources.sqlite.SqliteLocCodec
import com.opennxt.resources.sqlite.SqliteNpcCodec
import mu.KotlinLogging

typealias LocHandler = (LocContext) -> Any?
typealias NpcHandler = (NpcContext) -> Any?

/**
 * What an item-on-loc interaction (ClientProt 116 OPLOCT) tells a handler. It is declared HERE
 * rather than in `ContentContext.kt` deliberately: that file is a shared aggregate three passes
 * were editing the same day, and this type is needed by exactly one dispatcher, in this file.
 * It is NOT a [ContentContext] subclass for the same reason - the sealed hierarchy lives there.
 */
data class ItemOnLocContext(
    val player: ContentPlayer,
    val definition: LocDefinition,
    val itemId: Int,
    val slot: Int,
    val action: String,
    val actionSlot: Int,
    /** Absolute world tile of the loc, not a local/region coordinate. */
    val x: Int,
    val z: Int,
    val plane: Int
) {
    val locId: Int get() = definition.id
    override fun toString() =
        "ItemOnLocContext(item $itemId slot $slot on ${definition.id} '${definition.name}' " +
            "$action@$actionSlot at $x,$z,$plane)"
}

typealias ItemOnLocHandler = (ItemOnLocContext) -> Any?

/**
 * What a dispatch did, as a value rather than a boolean.
 *
 * Three of these four are refusals and they are deliberately distinct. "No
 * handler is written for this yet" and "the client asked for something this
 * object cannot do" look identical to a caller that only gets `false`, and only
 * the second one is worth logging as suspicious.
 */
sealed class DispatchResult {
    /** A handler ran. [value] is whatever it returned; handlers may return null. */
    data class Handled(val value: Any?) : DispatchResult()

    /** The action is declared by the definition, but nothing is bound to it. Content gap, not abuse. */
    data class NoHandler(val id: Int, val action: String) : DispatchResult() {
        override fun toString() = "NoHandler(no content bound to $id/'$action')"
    }

    /**
     * The definition does not declare this action. **Rejected.** This is the
     * anti-client-trust gate: a client is free to send any (id, op) pair it
     * likes and the only thing that makes one legitimate is the cache saying the
     * object carries it.
     */
    data class Rejected(val id: Int, val action: String, val declared: List<String>) : DispatchResult() {
        override fun toString() =
            "Rejected($id does not declare '$action'; it declares ${if (declared.isEmpty()) "nothing" else declared})"
    }

    /** No such loc/npc id in the definitions at all. Also a rejection. */
    data class UnknownTarget(val id: Int) : DispatchResult()

    val handled: Boolean get() = this is Handled
}

/**
 * Where the registry learns what an object is allowed to do.
 *
 * Split out from the registry so the validation rule can be exercised against a
 * fixed table instead of 139587 rows of sqlite, and so nothing in this package
 * hard-depends on rs3.sqlite existing.
 */
interface DefinitionSource {
    fun loc(id: Int): LocDefinition?
    fun npc(id: Int): NpcDefinition?

    /** Every loc id whose `actions[]` contains [action], exactly. */
    fun locsDeclaring(action: String): Set<Int>

    /** Every npc id whose `actions[]` contains [action], exactly. */
    fun npcsDeclaring(action: String): Set<Int>

    val locCount: Int
    val npcCount: Int
}

/**
 * The definitions out of `data/rs3.sqlite`, with a reverse index from action
 * name to the ids that declare it.
 *
 * Both tables are pulled in full on first use. That is 139587 locs and 32687
 * npcs, and it is done once because the reverse index is the point: binding a
 * handler to "every loc that declares Open" cannot be answered by a per-id
 * lookup, and doing it as a `LIKE` query per registration would put SQL in the
 * middle of content registration.
 *
 * Action matching is **exact and case-sensitive**. The corpus contains both
 * `Open` (2153 locs) and `open` (3), and they are different options on different
 * objects; folding case would silently widen every binding.
 */
object SqliteDefinitions : DefinitionSource {
    private val logger = KotlinLogging.logger { }

    private val locs: Map<Int, LocDefinition> by lazy {
        SqliteLocCodec.listAll().also { logger.info { "content: indexed ${it.size} locs" } }
    }
    private val npcs: Map<Int, NpcDefinition> by lazy {
        SqliteNpcCodec.listAll().also { logger.info { "content: indexed ${it.size} npcs" } }
    }

    private val locsByAction: Map<String, Set<Int>> by lazy { index(locs.values.map { it.id to it.actions }) }
    private val npcsByAction: Map<String, Set<Int>> by lazy { index(npcs.values.map { it.id to it.actions }) }

    private fun index(rows: List<Pair<Int, Array<String?>>>): Map<String, Set<Int>> {
        val out = HashMap<String, MutableSet<Int>>()
        for ((id, actions) in rows) {
            for (a in actions) {
                if (a == null) continue
                out.getOrPut(a) { HashSet() }.add(id)
            }
        }
        return out
    }

    override fun loc(id: Int): LocDefinition? = locs[id]
    override fun npc(id: Int): NpcDefinition? = npcs[id]
    override fun locsDeclaring(action: String): Set<Int> = locsByAction[action] ?: emptySet()
    override fun npcsDeclaring(action: String): Set<Int> = npcsByAction[action] ?: emptySet()
    override val locCount: Int get() = locs.size
    override val npcCount: Int get() = npcs.size

    /** Every distinct loc action in the corpus, with how many loc ids declare each. */
    fun locActionHistogram(): Map<String, Int> = locsByAction.mapValues { it.value.size }

    /** Every distinct npc action in the corpus, with how many npc ids declare each. */
    fun npcActionHistogram(): Map<String, Int> = npcsByAction.mapValues { it.value.size }
}

/**
 * (target, action) -> handler, and the dispatch that enforces the cache's answer
 * about which actions a target has.
 *
 * ## Two ways to bind, because ids do not scale
 *
 * [onLoc] binds one id, for the handful of objects with genuinely unique
 * behaviour. [onLocAction] binds a *category*: every loc whose `actions[]`
 * contains that name, which for "Open" is 2170 loc ids. Doors are not a list -
 * writing one out would be 2170 lines that go stale the next time the cache
 * moves, and the cache already knows which locs are openable.
 *
 * An explicit id binding wins over a category binding for the same action, so a
 * single quest door can override the generic behaviour without unbinding it.
 *
 * ## Dispatch validates first
 *
 * `dispatchLoc(player, 1530, "Attack", ...)` is rejected, because loc 1530
 * ("Door") declares "Open" and nothing else. The client chooses which op number
 * it sends; it does not get to choose which options an object has. Every
 * interaction packet that ever reaches this class arrives from a client, so the
 * check is not defence in depth, it is the only check there is.
 *
 * The registry itself is content-free: it knows nothing about doors, NPCs or
 * items, only about names that came out of the definitions.
 */
object ContentRegistry {
    private val logger = KotlinLogging.logger { }

    private data class Key(val id: Int, val action: String)

    private val locById = HashMap<Key, LocHandler>()
    private val locByAction = HashMap<String, LocHandler>()
    private val npcById = HashMap<Key, NpcHandler>()
    private val npcByAction = HashMap<String, NpcHandler>()

    var source: DefinitionSource = SqliteDefinitions

    // ---- registration --------------------------------------------------

    /**
     * Bind one loc id. Refuses if the definitions say that loc has no such
     * action - a binding that can never fire is a typo, and failing at
     * registration is far cheaper than wondering later why a door is inert.
     */
    fun onLoc(id: Int, action: String, handler: LocHandler) {
        val def = source.loc(id) ?: throw IllegalArgumentException("no loc $id in the definitions")
        require(def.actions.any { it == action }) {
            "loc $id ('${def.name}') does not declare '$action'; it declares ${def.actions.filterNotNull()}"
        }
        locById[Key(id, action)] = handler
    }

    /** Bind every loc that declares [action]. Returns how many loc ids that is. */
    fun onLocAction(action: String, handler: LocHandler): Int {
        val n = source.locsDeclaring(action).size
        require(n > 0) { "no loc in the definitions declares '$action'" }
        locByAction[action] = handler
        logger.info { "content: bound loc action '$action' across $n loc ids" }
        return n
    }

    fun onNpc(id: Int, action: String, handler: NpcHandler) {
        val def = source.npc(id) ?: throw IllegalArgumentException("no npc $id in the definitions")
        require(def.actions.any { it == action }) {
            "npc $id ('${def.name}') does not declare '$action'; it declares ${def.actions.filterNotNull()}"
        }
        npcById[Key(id, action)] = handler
    }

    /** Bind every npc that declares [action]. Returns how many npc ids that is. */
    fun onNpcAction(action: String, handler: NpcHandler): Int {
        val n = source.npcsDeclaring(action).size
        require(n > 0) { "no npc in the definitions declares '$action'" }
        npcByAction[action] = handler
        logger.info { "content: bound npc action '$action' across $n npc ids" }
        return n
    }

    // ---- dispatch ------------------------------------------------------

    fun dispatchLoc(
        player: ContentPlayer,
        locId: Int,
        action: String,
        x: Int,
        z: Int,
        plane: Int = 0
    ): DispatchResult {
        val def = source.loc(locId) ?: return DispatchResult.UnknownTarget(locId)
        val slot = def.actions.indexOfFirst { it == action }
        if (slot < 0) return DispatchResult.Rejected(locId, action, def.actions.filterNotNull())

        val handler = locById[Key(locId, action)] ?: locByAction[action]
        ?: return DispatchResult.NoHandler(locId, action)

        return DispatchResult.Handled(handler(LocContext(player, def, action, slot, x, z, plane)))
    }

    fun dispatchNpc(
        player: ContentPlayer,
        npcId: Int,
        action: String,
        npcIndex: Int = -1,
        x: Int = 0,
        z: Int = 0,
        plane: Int = 0
    ): DispatchResult {
        val def = source.npc(npcId) ?: return DispatchResult.UnknownTarget(npcId)
        val slot = def.actions.indexOfFirst { it == action }
        if (slot < 0) return DispatchResult.Rejected(npcId, action, def.actions.filterNotNull())

        val handler = npcById[Key(npcId, action)] ?: npcByAction[action]
        ?: return DispatchResult.NoHandler(npcId, action)

        return DispatchResult.Handled(handler(NpcContext(player, def, action, slot, npcIndex, x, z, plane)))
    }

 // ---- item-on-loc -----------------
    //
    // OPLOCT is "use the item I have selected on that scenery object". It has NO menu row and no
    // option number, so it cannot be validated the way dispatchLoc validates one - but the cache
    // still has an answer, and it is the same KIND of answer: an object that accepts an item
    // declares the action "Use" in one of its five slots. 278 loc ids do, and all seventeen
    // Firemaking fires (70755..70771) are among them, at slot 4. So the anti-client-trust gate
    // here is identical in shape to dispatchLoc's: the client picks the loc, the cache decides
    // whether that loc accepts a "Use" at all, and a loc that does not is Rejected.
    //
    // Three binding forms, resolved most-specific first: (item, loc), then (any item, loc), then
    // (item, any loc). Cooking binds the middle one, once per fire.

    private val itemOnLocByPair = HashMap<Pair<Int, Int>, ItemOnLocHandler>()
    private val itemOnLocByLoc = HashMap<Int, ItemOnLocHandler>()
    private val itemOnLocByItem = HashMap<Int, ItemOnLocHandler>()

    /**
     * Binds an item-on-loc handler. [itemId] null means "any item", [locId] null means "any loc";
     * both null is refused, because a binding that catches every OPLOCT in the world is not a
     * content binding, it is a global hook.
     *
     * Refuses a [locId] the definitions do not carry, or one that does not declare [action] -
     * the same "a binding that can never fire is a typo" rule [onLoc] enforces.
     */
    fun onItemOnLoc(itemId: Int?, locId: Int?, action: String = "Use", handler: ItemOnLocHandler) {
        require(itemId != null || locId != null) { "onItemOnLoc needs an item id, a loc id, or both" }
        if (locId != null) {
            val def = source.loc(locId) ?: throw IllegalArgumentException("no loc $locId in the definitions")
            require(def.actions.any { it == action }) {
                "loc $locId ('${def.name}') does not declare '$action'; it declares ${def.actions.filterNotNull()}"
            }
        }
        when {
            itemId != null && locId != null -> itemOnLocByPair[itemId to locId] = handler
            locId != null -> itemOnLocByLoc[locId] = handler
            else -> itemOnLocByItem[itemId!!] = handler
        }
    }

    /**
     * Routes one OPLOCT. [itemId] is the client's `selobj`; 0xffffff is the client's own "nothing
     * selected" and every negative or absent value is a forged one, so both are refused before a
     * handler can see them.
     */
    fun dispatchItemOnLoc(
        player: ContentPlayer,
        itemId: Int,
        locId: Int,
        slot: Int,
        x: Int,
        z: Int,
        plane: Int = 0,
        action: String = "Use"
    ): DispatchResult {
        val def = source.loc(locId) ?: return DispatchResult.UnknownTarget(locId)
        if (itemId <= 0 || itemId == 0xffffff)
            return DispatchResult.Rejected(locId, "$action item $itemId", def.actions.filterNotNull())
        val actionSlot = def.actions.indexOfFirst { it == action }
        if (actionSlot < 0) return DispatchResult.Rejected(locId, action, def.actions.filterNotNull())

        val handler = itemOnLocByPair[itemId to locId] ?: itemOnLocByLoc[locId] ?: itemOnLocByItem[itemId]
        ?: return DispatchResult.NoHandler(locId, "$action item $itemId")

        return DispatchResult.Handled(
            handler(ItemOnLocContext(player, def, itemId, slot, action, actionSlot, x, z, plane))
        )
    }

    /** How many item-on-loc bindings exist, in all three forms. Observable so a check can pin it. */
    fun boundItemOnLoc(): Int = itemOnLocByPair.size + itemOnLocByLoc.size + itemOnLocByItem.size

    // ---- introspection -------------------------------------------------

    /** How many loc ids a category binding on [action] actually covers. 0 if not bound. */
    fun locCoverage(action: String): Int =
        if (action in locByAction) source.locsDeclaring(action).size else 0

    fun npcCoverage(action: String): Int =
        if (action in npcByAction) source.npcsDeclaring(action).size else 0

    fun boundLocActions(): Set<String> = locByAction.keys.toSet()
    fun boundNpcActions(): Set<String> = npcByAction.keys.toSet()
    fun boundLocIds(): Int = locById.size
    fun boundNpcIds(): Int = npcById.size

    /** Drops every binding. For tests and for a content reload. */
    fun clear() {
        locById.clear(); locByAction.clear(); npcById.clear(); npcByAction.clear()
 // ADDITIVE: the item-on-loc maps clear with the rest, or a check that calls
        // clear() would leave a stale Cooking binding behind and the next section would dispatch
        // into it.
        itemOnLocByPair.clear(); itemOnLocByLoc.clear(); itemOnLocByItem.clear()
    }
}
