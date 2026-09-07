package com.opennxt.content

import com.opennxt.model.items.Equipment
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.vars.VarPlayerState
import com.opennxt.model.world.TileLocation
import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.NpcDefinition

/**
 * The player state a content handler is allowed to see and change.
 *
 * This is a content-side aggregate, not a rename of [com.opennxt.model.world.WorldPlayer].
 * `WorldPlayer` is a network object - it owns a `ConnectedClient`, a packet
 * handler table and a viewport - and none of that is any of a door's business.
 */
class ContentPlayer(
    val name: String = "content",
    var location: TileLocation = TileLocation(0, 0, 0),
    var onTeleport: ((TileLocation) -> Unit)? = null,
    val inventory: ItemContainer = ItemContainer.inventory(),
    val equipment: Equipment = Equipment(),
    val vars: VarPlayerState = VarPlayerState(),
    var onAnimate: ((Int) -> Unit)? = null
) {
    /** Chebyshev distance to a tile, which is the metric RS uses for interaction range. */
    fun distanceTo(x: Int, z: Int): Int =
        maxOf(Math.abs(location.x - x), Math.abs(location.y - z))

    override fun toString() = "ContentPlayer($name at ${location.x},${location.y},${location.plane})"
}

/**
 * What a handler is told about the interaction it is handling.
 *
 * [definition] is the *definition the dispatcher validated against*, not one the
 * handler is expected to look up again. A handler that re-read the definition
 * could disagree with the dispatcher about which actions exist, and then the
 * validation in [ContentRegistry] would be advisory rather than binding.
 *
 * [actionSlot] is the index in `actions[]` the name was found at - the "op1..op5"
 * the client sends. It is reported rather than trusted: dispatch keys on the
 * action *name*, because slot numbers are not stable across definitions and a
 * client that sends op3 for a loc whose slot 3 is empty must not reach a handler.
 */
sealed class ContentContext {
    abstract val player: ContentPlayer
    abstract val action: String
    abstract val actionSlot: Int
}

class LocContext(
    override val player: ContentPlayer,
    val definition: LocDefinition,
    override val action: String,
    override val actionSlot: Int,
    /** Absolute world tile of the loc, not a local/region coordinate. */
    val x: Int,
    val z: Int,
    val plane: Int
) : ContentContext() {
    val locId: Int get() = definition.id
    override fun toString() =
        "LocContext(${definition.id} '${definition.name}' $action@$actionSlot at $x,$z,$plane)"
}

class NpcContext(
    override val player: ContentPlayer,
    val definition: NpcDefinition,
    override val action: String,
    override val actionSlot: Int,
    /** The world's index for this NPC instance; -1 when the caller has only an id. */
    val npcIndex: Int = -1,
    val x: Int = 0,
    val z: Int = 0,
    val plane: Int = 0
) : ContentContext() {
    val npcId: Int get() = definition.id
    override fun toString() =
        "NpcContext(${definition.id} '${definition.name}' $action@$actionSlot index=$npcIndex)"
}
