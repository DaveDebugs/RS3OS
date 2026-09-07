package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.content.impl.Doors
import com.opennxt.content.impl.ResourceNodes
import com.opennxt.content.impl.SkillingWiring
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.map.LocClipping
import com.opennxt.model.map.LocInteraction
import com.opennxt.model.world.LocChanges
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.OpLoc
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteLocCodec
import mu.KotlinLogging

/**
 * A click on a scenery object: OPLOC1..6 (949 opcodes 41, 70, 33, 91, 1, 88).
 *
 * ONE handler for six opcodes - they differ only in which menu row was chosen,
 * and [OpLoc.option] already carries that. Six packet CLASSES, one handler; see
 * [OpLoc]'s doc for why the classes cannot be merged.
 *
 * ## What it does, and what it deliberately does not
 *
 * It does two things:
 *
 *  1. **Says what was clicked, by name.** The loc id is resolved through
 *     [SqliteLocCodec] (`data/rs3.sqlite`, the same definitions
 *     `ContentRegistry` validates against), and the tile is cross-checked
 *     against `map_loc` - the cache's own record of where locs are placed. So
 *     the log says `9366 'Swamp tree' option 1 'Chop down'` instead of a number,
 *     which is the whole point of decoding this packet at all.
 *  2. **Walks the player to it**, through [MoveGameClickHandler.walk] - the
 *     exact pathing MOVE_GAMECLICK uses, not a second copy of it.
 *
 * It does **NOT** invent gameplay. No chopping, no mining, no loot, no
 * animation, no varp, no XP.
 *
 * It DOES now call `ContentRegistry.dispatchLoc`, but only for one action and
 * only after reading it out of the definition. The old note here said dispatch
 * was refused because "the client clicked row 3" -> "row 3 is action slot 3" is
 * an inference with no measurement behind it. That reasoning still holds and is
 * still respected: [openIfDoor] does not trust the row number at all - it asks
 * the loc's own definition what the clicked row SAYS and acts only when the
 * answer is literally "Open". Dispatch then validates the same string against
 * the same definition a second time. What the old note got wrong was the
 * conclusion, not the premise: refusing to dispatch did not make the server
 * cautious, it made `Doors.onOpen` - the only code in the tree that changes
 * passability - unreachable from any packet, so no door in the world could ever
 * be walked through. Nothing else is dispatched from here.
 *
 * ## The tile check is anti-client-trust, and it is REPORTED rather than enforced
 *
 * A client can send any (loc id, tile) pair it likes. `map_loc` says which pairs
 * are real. A mismatch is logged at WARN and the walk still happens, because the
 * walk is harmless either way and because a mismatch is at least as likely to
 * mean this server's understanding of the coordinate system is wrong as it is to
 * mean the client lied. When something is eventually wired to actually DO the
 * action, that is the place to refuse - and this line is the evidence it will
 * need.
 */
object OpLocHandler : GamePacketHandler<WorldPlayer, OpLoc> {
    private val logger = KotlinLogging.logger { }

    /** A row of `map_loc` at the clicked tile. */
    data class Placement(val locId: Int, val type: Int, val rot: Int)

    /**
     * Every loc `map_loc` places on ([x], [y], [plane]).
     *
     * The packing is `square_id = (squareY << 7) | squareX` with 64-tile
     * squares and `x`/`y` local to the square, which is what
     * [com.opennxt.content.impl.Doors.placements] unpacks the other way round.
     * Coordinates are bound as literals rather than parameters because
     * [RsDatabase] only exposes a single-int bind; all three are Ints straight
     * off a decoded packet field, so there is nothing here to inject.
     */
    internal fun placementsAt(x: Int, y: Int, plane: Int): List<Placement> {
        if (!RsDatabase.available) return emptyList()
        val squareId = ((y / 64) shl 7) or (x / 64)
        val sql = "SELECT loc_id, type, rot FROM map_loc WHERE square_id = $squareId " +
            "AND x = ${x % 64} AND y = ${y % 64} AND plane = $plane"
        return RsDatabase.queryAll(sql) { Placement(it.getInt("loc_id"), it.getInt("type"), it.getInt("rot")) }
    }

    /** `actions_0..4` slot [option] - 1, or null when the definition leaves it empty. */
    private fun optionName(def: LocDefinition?, option: Int): String? =
        def?.actions?.getOrNull(option - 1)

    /**
     * Whether [option] can correspond to a config action slot at all.
     *
     * The loc format has exactly FIVE action slots: `actions_0` is a column and
     * `actions_1..4` live in `locs_attr`; the largest slot index anywhere in the
     * database is 4. So options 1..5 can map to a slot and **option 6 never can**.
     */
    private fun optionCanBeConfigSlot(option: Int): Boolean = option in 1..5

    override fun handle(context: WorldPlayer, packet: OpLoc) {
        val plane = context.entity.location.plane
        val def = if (RsDatabase.available) SqliteLocCodec.load(packet.id) else null
        val name = def?.name ?: "unknown loc"
        val action = optionName(def, packet.option)

        val placed = placementsAt(packet.x, packet.y, plane)
        val confirmed = placed.any { it.locId == packet.id }

        logger.info {
            "OPLOC${packet.option} ${context.name} clicked $name (loc ${packet.id}) at " +
                "(${packet.x},${packet.y},plane $plane) option ${packet.option}" +
                (if (action != null) " = '$action'"
                else if (!optionCanBeConfigSlot(packet.option))
                    " = <option $packet.option is client menu enum 1002, not a config slot - the loc " +
                        "format has only 5 (actions_0..4), so this is EXPECTED>"
                else " = <no action in that slot>") +
                (if (packet.ctrlHeld) " ctrl-held" else "") +
                (if (RsDatabase.available) {
                    if (confirmed) " [map_loc confirms this loc is on that tile]"
                    else " [map_loc has ${placed.size} loc(s) there: " +
                        "${placed.joinToString(", ") { it.locId.toString() }}]"
                } else " [no rs3.sqlite - name and tile unverified]")
        }

        // The client can name any (id, tile) pair; only the cache can say
        // whether it is real. Loud, and separate from the line above, so it is
        // not buried in a line that otherwise reads as success.
        if (RsDatabase.available && !confirmed) {
            // Before crying client-lie, check the FOOTPRINT. map_loc holds one row
            // per placement, at its origin, so a 2x2 loc leaves three of its four
            // tiles with no row - 99,153 clickable tiles across the cache. A click
            // on one of those used to read as "no map_loc row places loc X here".
            val covering = LocInteraction.placementsCovering(packet.x, packet.y, plane)
            val mine = covering.firstOrNull { it.locId == packet.id }
            if (mine != null) {
                logger.info {
                    "OPLOC${packet.option}: (${packet.x},${packet.y}) is not loc ${packet.id}'s map_loc " +
                        "origin, but it IS inside its footprint - origin (${mine.originX},${mine.originZ}), " +
                        "${mine.dx}x${mine.dz}, shape ${mine.type} rot ${mine.rot}. Not a client lie."
                }
            } else {
                logger.warn {
                    "OPLOC${packet.option}: no map_loc row places loc ${packet.id} at " +
                        "(${packet.x},${packet.y},plane $plane), and no loc footprint within " +
                        "${LocInteraction.radius} tiles covers it either (${covering.size} other loc(s) do). " +
                        "Either the client sent a loc that is not there, or this server's tile/plane model " +
                        "disagrees with the cache. REFUSED: no walk, no action."
                }
 // (CODE-REVIEW-FULL #1b, CRITICAL). This line used to say
                // "Walking anyway; nothing acts on the loc" and then fall through
                // to the walk below, which installed executeAction as the arrival
                // callback, which Movement fires on an empty queue next tick - so
                // "nothing acts" was false: Skilling.gather ran with no placement
                // and paid the item and the xp with nothing to deplete. A loc the
                // cache does not place at that tile is refused here, whole.
                return
            }
        }

        // The same walk MOVE_GAMECLICK performs, called rather than copied.
        val walkResult = MoveGameClickHandler.walkToLoc(context, packet.x, packet.y, packet.id, "OPLOC${packet.option}")

        val executeAction = {
            openIfDoor(context, packet, def, placed)
            gatherIfResource(context, packet, def)

 // THE LODESTONE MORPH. The client sends the morph PARENT loc id (
 // 69839, t1269), and a parent carries no actions at all,
            // so the action routing below finds nothing and the click dies here. Lodestones resolves
            // the morph itself against the varbit. See its KDoc.
            com.opennxt.content.impl.Lodestones.handleLocClick(
                context, packet.id, packet.option, packet.x, packet.y, context.entity.location.plane
            )
            val routeAction = def?.actions?.getOrNull(packet.option - 1)
            if (routeAction != null) com.opennxt.content.impl.BanksWiring.handleLocClick(context, packet.id, routeAction, packet.x, packet.y, context.entity.location.plane)
        }

        when (walkResult) {
            is MoveGameClickHandler.LocWalk.AlreadyInRange -> executeAction()
            // The player is moving toward the loc: act when the walk ends.
            // ...and re-checks the distance WHEN the walk ends: `Approached` stops
            // "as close as the map allows", which across a river or a fence is
 // not close at all.
            is MoveGameClickHandler.LocWalk.Walked,
            is MoveGameClickHandler.LocWalk.Approached -> context.entity.movement.onArrival = {
                if (adjacentToFootprint(context, packet, plane)) executeAction()
                else logger.info {
                    "OPLOC${packet.option}: walk to loc ${packet.id} at (${packet.x},${packet.y}) ended at " +
                        "(${context.entity.location.x},${context.entity.location.y}), not beside it - nothing done"
                }
            }
 // (CODE-REVIEW-FULL #2, HIGH): these two never move the
            // player toward the loc, yet the callback used to be installed for
            // them too, and Movement.process() fires onArrival on the next tick
            // whenever the queue is empty - so a door, a bank or a resource on
            // the far side of a wall acted at any distance.
            //
            // But "no path" is not "far away": a player standing ON a multi-tile
            // loc's origin, or beside a wall-shaped one, is not "in range" by the
            // walk's rule and often has no path to a candidate tile either
            // the player on the loc's own tile). So the decision is DISTANCE, not
            // the walk result: adjacent to the loc's footprint -> act now;
            // anywhere else -> nothing armed, nothing fires.
            is MoveGameClickHandler.LocWalk.Unreachable,
            is MoveGameClickHandler.LocWalk.NoPlacement -> {
                if (adjacentToFootprint(context, packet, plane)) {
                    executeAction()
                } else {
                    context.entity.movement.onArrival = null
                    logger.info {
                        "OPLOC${packet.option}: no route to loc ${packet.id} at (${packet.x},${packet.y}) - " +
                            "$walkResult - and ${context.name} is not beside it; nothing armed"
                    }
                }
            }
        }
    }

    /**
     * Is the player within one tile of the footprint map_loc gives loc [packet.id]
     * at or covering the clicked tile? With no database there is no footprint to
     * ask, and the answer falls back to the clicked tile itself being within one
     * tile - the most that can be known.
     */
    private fun adjacentToFootprint(context: WorldPlayer, packet: OpLoc, plane: Int): Boolean {
        val px = context.entity.location.x
        val pz = context.entity.location.y
        val placement = if (RsDatabase.available) LocInteraction.placementOf(packet.id, packet.x, packet.y, plane) else null
        val (x0, z0, x1, z1) = if (placement != null)
            listOf(placement.originX, placement.originZ, placement.originX + placement.dx - 1, placement.originZ + placement.dz - 1)
        else listOf(packet.x, packet.y, packet.x, packet.y)
        return px in (x0 - 1)..(x1 + 1) && pz in (z0 - 1)..(z1 + 1)
    }

    // ================================================================
    // WOODCUTTING AND MINING
    // ================================================================

    /**
     * Dispatches a resource-gathering option to content, on the same terms
     * [openIfDoor] dispatches "Open".
     */
    private fun gatherIfResource(context: WorldPlayer, packet: OpLoc, def: LocDefinition?) {
        if (def == null) return
        val action = optionName(def, packet.option) ?: return
 // ONE ACTION STRING, ONE DISPATCH. NARROWED BACK.
        //
        // These four lines used to read
        //     if (action == LocChanges.OPEN || action == LocChanges.CLOSE) return
        //     // Generic dispatch: allow ANY valid action to flow to ContentRegistry
        // and that comment is the whole defect. `handle` calls THREE dispatchers
        // in a row - openIfDoor, this, and BanksWiring.handleLocClick (which
        // passes everything but Bank/Use to LocWiring.routeOther) - and the split
        // between them is [LocWiring.alreadyRoutedByHandler], which names exactly
        // the six strings the handler used to own. Widening this function without
        // widening that list meant ONE click ran the bound content handler TWICE
 // (2c) on printed fourteen doubled categories against five
        // singles, and the doubling is not cosmetic - `Gatherables.onGather` does
        // `inv.add(yield.itemId, 1)`, so one click on a flax patch banked TWO
        // flax, and `Searchables.onSearch` sent its message twice. It was
        // invisible for Ladders/Stairs only by accident: `contentPlayerAt()`
        // re-copies the world player's tile at the top of `routeOther`, so the
        // first climb was silently thrown away and only the second was written
        // back by `routeOther`'s teleport.
        //
        // The Open/Close carve-out this replaces was the same finding, found once
        // and stopped at doors: its comment said "dispatching them again here
        // would toggle the door a SECOND time (open -> close), which is why gates
        // appeared to open but immediately closed and stayed impassable".
        //
        // So this function is what its name says again - the resource path, and
        // nothing else. The three strings come from [ResourceNodes.isGatherAction],
        // which `LocWiring.alreadyRoutedByHandler` also calls, so the two halves
        // of the split are ONE list in ONE place and cannot drift apart a second
        // time. Every other action string keeps exactly one owner:
        //     'Open'                    openIfDoor (or the router, when
        //                               LocChanges.enabled is false)
        //     'Chop down' 'Chop' 'Mine' here
        //     'Bank' 'Use'              BanksWiring.handleLocClick's own dispatch
        //     everything else           LocWiring.routeOther
        if (!ResourceNodes.isGatherAction(action)) return

        val plane = context.entity.location.plane
        val content = context.contentPlayerAt()
        SkillingWiring.bind(content, context)

        val dispatched = ContentRegistry.dispatchLoc(content, packet.id, action, packet.x, packet.y, plane)
        when (dispatched) {
            is DispatchResult.Handled -> logger.info {
                "OPLOC${packet.option} ${context.name}: content handled '$action' on loc ${packet.id} " +
                    "('${def.name}') at (${packet.x},${packet.y},plane $plane) -> ${dispatched.value}"
            }
            // NoHandler is the ordinary state of affairs when the skilling
            // experiment is switched off; it is a content gap, not abuse, and
            // ContentRegistry's own doc says so. Logged at info for that reason.
            is DispatchResult.NoHandler -> logger.info {
                "OPLOC${packet.option}: nothing is bound to '$action' on loc ${packet.id} ('${def.name}') " +
                    "- skilling is ${if (com.opennxt.content.impl.Skilling.enabled) "enabled but unbound" else "DISABLED"}"
            }
            else -> logger.warn {
                "OPLOC${packet.option}: '$action' on loc ${packet.id} was refused by the registry " +
                    "($dispatched). The definition said the row carried that action, so this is a " +
                    "disagreement between optionName and the registry and should not be possible."
            }
        }
    }

    // ================================================================
    // DOORS
    // ================================================================

    /**
     * Opens the clicked loc, if it is an openable one whose opened variant the
     * cache can name.
     */
    private fun openIfDoor(
        context: WorldPlayer,
        packet: OpLoc,
        def: LocDefinition?,
        placed: List<Placement>
    ) {
        if (!LocChanges.enabled) return
        if (def == null) return
        if (optionName(def, packet.option) != LocChanges.OPEN) return

        val plane = context.entity.location.plane
        // Origin-exact first (that is what map_loc literally says), then the
        // footprint-covering lookup, so a click on a non-origin tile of a
        // multi-tile door still yields the shape and rotation LOC_ADD_CHANGE needs.
        val placement = placed.firstOrNull { it.locId == packet.id }
            ?: LocInteraction.placementOf(packet.id, packet.x, packet.y, plane)
                ?.let { Placement(it.locId, it.type, it.rot) }
        if (placement == null) {
            logger.warn {
                "OPLOC${packet.option} asked to open loc ${packet.id} ('${def.name}') at " +
                    "(${packet.x},${packet.y},plane $plane), but map_loc places no such loc there, so this " +
                    "server has no shape or rotation to send. Nothing changed."
            }
            return
        }

        // Collision, and the numbers that say it changed. Extracted so
 // can drive THIS code rather
        // than a copy of it that happens to agree.
        openCollision("OPLOC${packet.option}", context.contentPlayerAt(), packet.id,
            packet.x, packet.y, plane, placement.type, placement.rot)

        // Double doors: open the PARTNER leaf's collision too. LocChanges already
        // sends LOC_ADD_CHANGE for the partner (so both halves visually open), but
        // until now only the clicked leaf's wall edge was removed from CollisionMap.
        // The partner leaf stayed blocked, so a player could not walk through a double
        // gate even though it looked open on both sides.
        val partner = LocChanges.doubleDoorPartner(
            plane, packet.x, packet.y, placement.type, packet.id, placement.rot
        )
        if (partner != null) {
            val (px, py, partnerLocId) = partner
            val partnerPlacement = placementsAt(px, py, plane).firstOrNull { it.locId == partnerLocId }
            if (partnerPlacement != null) {
                openCollision("OPLOC${packet.option}-partner", context.contentPlayerAt(),
                    partnerLocId, px, py, plane, partnerPlacement.type, partnerPlacement.rot)
                logger.info {
                    "OPLOC${packet.option}: also opened partner leaf loc $partnerLocId at ($px,$py,plane $plane)"
                }
                val partnerVariant = LocChanges.openVariant(partnerLocId)
                if (partnerVariant != null) {
                    LocChanges.change(
                        plane = plane,
                        x = px,
                        y = py,
                        shape = partnerPlacement.type,
                        rotation = partnerPlacement.rot,
                        originalId = partnerLocId,
                        newId = partnerVariant.openId,
                        forcedSwingMode = if (com.opennxt.model.world.DoorSwing.mode == com.opennxt.model.world.DoorSwing.Mode.OFF) null else com.opennxt.model.world.DoorSwing.Mode.TURN_BACK
                    )
                }
            }
        }

        val variant = LocChanges.openVariant(packet.id)
        if (variant == null) {
 // UNRESOLVED, and stated as such rather than papered over. The
            // transmission path is complete and this is exactly where it stops:
            // the zone maths is done, the shape and rotation are in hand, the
            // packet would be well-formed - the only missing thing is which loc
            // id an open '${def.name}' is, and this cache does not say for this
            // one. Guessing here is what turns a door into a random object.
            logger.warn {
                "OPLOC${packet.option}: '${def.name}' (loc ${packet.id}) at (${packet.x},${packet.y}," +
                    "plane $plane) declares 'Open', but its opened variant is UNRESOLVED in this cache. " +
                    "Neither rule fired: loc ${packet.id + LocChanges.openOffset} is not a same-named loc " +
                    "declaring '${LocChanges.CLOSE}' (OFFSET), and no loc declaring '${LocChanges.CLOSE}' " +
                    "shares this one's name, model list and cache group while being absent from map_loc " +
                    "(TWIN). Would have sent UPDATE_ZONE_PARTIAL_FOLLOWS then " +
                    "LOC_ADD_CHANGE(shape=${placement.type}, rotation=${placement.rot}, coord=" +
                    "0x%02x, loc=<unresolved>). Sent nothing.".format(((packet.x and 7) shl 4) or (packet.y and 7))
            }
            return
        }
        val openId = variant.openId

        val change = LocChanges.change(
            plane = plane,
            x = packet.x,
            y = packet.y,
            shape = placement.type,
            rotation = placement.rot,
            originalId = packet.id,
            newId = openId,
            forcedSwingMode = if (partner != null && com.opennxt.model.world.DoorSwing.mode != com.opennxt.model.world.DoorSwing.Mode.OFF) com.opennxt.model.world.DoorSwing.Mode.TURN else null
        )
        if (change == null) {
            logger.warn {
                "OPLOC${packet.option}: '${def.name}' opens to loc $openId, but LOC_ADD_CHANGE could not be " +
                    "sent (experiment off, or no opcode on build ${OpenNXT.protocol.effectiveBuild}). " +
                    "Nothing changed."
            }
            return
        }
        logger.info {
            "OPLOC${packet.option} ${context.name} opened '${def.name}': loc ${packet.id} -> $openId at " +
                "(${packet.x},${packet.y},plane $plane) shape ${placement.type} rotation ${placement.rot} " +
                "[${variant.rule} rule, ${variant.candidates} candidate(s)] " +
 //. This clause was lost when openIfDoor was
                // rewritten for double-door collision + forcedSwingMode, and its
                // absence was NOT a decision: it left
                // LocChanges.Change.swingToken and .swingMoved with ZERO call
                // sites anywhere in the tree, while their own KDoc names THIS
                // line as the consumer they were hoisted out of `sendTo` for
                // ("That made the swing INVISIBLE to the one caller that has to
                // report it - OpLocHandler"), and left LocChanges.swingStateLine
                // promising every reader that "Every door opened by this JVM
                // names its mode on the OPLOC line, as swing[<MODE>]". Two files
                // asserting as fact what a third had silently stopped doing.
                //
 // which that check ran deliberately in and recorded
                // as costing 13 assertions - and the merged tree reproduced that
                // fingerprint check for check.
                //
                // The mode is named on EVERY open, OFF included, because the
 // test runs could not tell "the flag never arrived"
                // from "the flag arrived and OFF is a no-op". A line that only
                // speaks when the answer is interesting cannot settle that.
                change.swingToken +
                (if (change.swingMoved)
                    " leaf placed at (${change.swing.x},${change.swing.y}) rot ${change.swing.rotation} " +
                        "instead of (${packet.x},${packet.y}) rot ${placement.rot}"
                else " leaf stays at (${packet.x},${packet.y}) rot ${placement.rot}") +
                // Said out loud rather than left for someone to discover in-game:
                // 408 of the 594 resolved pairs share a model list, so for those the
                // swap changes the menu and nothing on screen. The leaf does not swing;
                // see LocChanges.openVariant for why that is not guessed at.
                (if (variant.modelsIdentical) {
                    " - NOTE: loc $openId lists the SAME models as ${packet.id}, so the client will show " +
                        "'${LocChanges.CLOSE}' on an object that has not visibly moved. The swing " +
                        "(rotation/tile change) is unmeasured and deliberately not sent."
                } else "")
        }
    }

    /**
     * Everything opening a loc does to the WORLD, as opposed to to the wire:
     * make the square's walls real, register the door with the shape and rotation
     * `map_loc` gives it, and ask content to open it.
     *
     * `internal` and separate from [openIfDoor] for the reason
     * [placementsAt] is: a check has to be able to run this, not a re-derivation
     * of it. Returns what the dispatch returned so a caller - or a check - can
     * tell "content opened it" from "nothing is bound".
     *
     * [tag] is only for the log lines, so the same text appears whether this ran
     * from OPLOC1 or from a check.
     */
    internal fun openCollision(
        tag: String,
        player: com.opennxt.content.ContentPlayer,
        locId: Int,
        x: Int,
        y: Int,
        plane: Int,
        type: Int,
        rot: Int
    ): DispatchResult {
        // Until this call the server had NO loc clipping at all: map_meta says
        // `object_clipping: NOT included`, so the fence this gate is a hole in was
        // never solid and "opening" it changed nothing a player could feel.
        //
        // applySceneAt and not applySquareAt: a wall does not stop at a square
        // boundary. The Lumbridge fence this gate is a gap in runs y 3255..3271
        // across squares 6450 AND 6578, and loading only the gate's own square left
        // a walkable hole at y = 3263 that PathFinder found immediately. Idempotent
        // and memoised per (square, plane), so a second click is free.
        val squares = LocClipping.applySceneAt(x, y, plane)
        if (squares > 0) {
            logger.info {
                "$tag: applied loc clipping around ($x,$y,plane $plane) - $squares square(s), " +
                    "${LocClipping.loadedSquares()} loaded, ${CollisionMap.walledTiles()} walled tile(s), " +
                    "${CollisionMap.occupiedTiles()} occupied tile(s)"
            }
        }

        // The door is registered with the SHAPE AND ROTATION map_loc gives, so its
        // collision is the wall EDGE it really sits on and not its whole tile. A
        // whole-tile block here would stop a player walking along the fence past
        // the gate, which is a different bug wearing the same clothes.
        Doors.placeIfAbsent(locId, x, y, plane, open = false, type = type, rot = rot)
        val edgeMask = Doors.at(x, y, plane)?.edgeMask ?: 0
        val maskBefore = CollisionMap.wallMask(x, y, plane)

        // Content owns what opening MEANS. This is the call that was missing: the
        // handler walked the player over and swapped a loc id, and Doors.onOpen -
        // the only thing that touches passability - was unreachable from a packet.
        // Dispatch validates the action against the definition first, so a client
        // cannot reach it with a row this loc does not declare.
        val dispatched = ContentRegistry.dispatchLoc(
            player, locId, LocChanges.OPEN, x, y, plane
        )
        val maskAfter = CollisionMap.wallMask(x, y, plane)
        when (dispatched) {
            is DispatchResult.Handled -> logger.info {
                "$tag: content handled '${LocChanges.OPEN}' on loc $locId -> '${dispatched.value}'. " +
                    "Wall edges on ($x,$y,plane $plane): 0x%02x -> 0x%02x (this door's edge 0x%02x)"
                        .format(maskBefore, maskAfter, edgeMask) +
                    if (maskBefore == maskAfter && edgeMask != 0) {
                        " - UNCHANGED, so something else still states that edge (a coincident wall), or " +
                            "this loc's clip type does not block."
                    } else ""
            }
            else -> logger.warn {
                "$tag: content did NOT handle '${LocChanges.OPEN}' on loc $locId ($dispatched). " +
                    "The loc id may still be swapped, but nothing became passable."
            }
        }
        return dispatched
    }
}

