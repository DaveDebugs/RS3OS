package com.opennxt.content.impl

import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.model.world.LocChanges
import com.opennxt.model.world.WorldPlayer
import mu.KotlinLogging

/**
 * THE LOC HALF OF THE DISPATCH GAP, and the flag that closes it.
 */
object LocWiring {
    private val logger = KotlinLogging.logger { }

    /**
     * The property. Read through a getter, not a `val`, so a check can flip it
     * inside one process and measure both states - the same shape
     * [com.opennxt.net.game.handlers.OpNpcHandler.dispatchEnabled] uses, and for
     * the same reason.
     */
    const val DISPATCH_SWITCH = "opennxt.experiment.loc.dispatch"

    val dispatchEnabled: Boolean get() = System.getProperty(DISPATCH_SWITCH) != "false"

    /**
     * The action strings `OpLocHandler` routes ITSELF. Routing them again here
     * would dispatch the same click twice, which for `Open` means opening and
     * then re-closing a door in one packet.
     *
     * `Open` is conditional on [LocChanges.enabled] because the handler's own
     * `openIfDoor` is: with `-Dopennxt.experiment.locChanges=false` the handler
     * drops `Open` before dispatch, and this router is then the only path left,
     * so it must not exclude it. That condition is not decoration - checks 3.7
     * and 3.8 of that check read it back out of this function.
     */
    fun alreadyRoutedByHandler(action: String): Boolean =
        (action == LocChanges.OPEN && LocChanges.enabled) ||
            ResourceNodes.isGatherAction(action) ||
            action == Banks.BANK ||
            action == Banks.USE

    /** How many dispatches asked to move the player and had the move dropped. See the class doc. */
    @Volatile
    var droppedMoves: Int = 0
        private set

    /** How many dispatches this router has handed to the registry, whatever the answer. */
    @Volatile
    var routed: Int = 0
        private set

    /**
     * How many clicks reached this router and were declined because the flag is
     * off. Non-zero on a default server is the proof that the seam is on the
     * live path - the failure mode this whole sweep is about is a wiring that
     * exists and is never consulted, and a counter that stays at 0 while people
     * click on ladders says so out loud.
     */
    @Volatile
    var skipped: Int = 0
        private set

    @Volatile
    private var offWarned = false

    internal fun resetCounters() {
        droppedMoves = 0
        routed = 0
        skipped = 0
        offWarned = false
    }

    /**
     * Route one loc click that `OpLocHandler` does not route itself.
     *
     * Returns true only when a handler RAN, so the caller's existing "nothing
     * consumed this" logging is unchanged for every other outcome - the same
     * contract [BanksWiring.handleLocClick] already had.
     *
     * All three wirings are bound first, for the reason
     * `OpNpcHandler.dispatchOther` gives: this one call site can now land in any
     * content module, each of which reaches the wire through its own
     * ContentPlayer -> WorldPlayer pairing. The binds are idempotent identity
     * writes into synchronized weak maps.
     *
     * A handler that throws does not take the session with it. This runs on the
     * Netty thread that decoded the packet; an exception out of content is a bug
     * in that handler, and letting it unwind would disconnect the player.
     */
    fun routeOther(world: WorldPlayer, locId: Int, action: String, x: Int, z: Int, plane: Int): Boolean {
        if (alreadyRoutedByHandler(action)) return false
        if (!dispatchEnabled) {
            skipped++
            // Once per process, and only after a real click has actually
            // arrived here - a boot-time line would say the same thing whether
            // or not this code was ever reached, which is precisely the class of
            // claim this sweep exists to catch.
            if (!offWarned) {
                offWarned = true
 // 2,156 -> 5,288. The old number was measured on
 // and was already two changes out of date: the three
 // modules bound on added 2,691 pairs, and until today
                // `OpLocHandler.gatherIfResource` was dispatching thirteen of
                // these categories itself, unflagged, so most of what this line
                // called unreachable was in fact reachable by a second path. With
                // that path narrowed back to 'Chop down'/'Chop'/'Mine', this
                // router really is the only one left for every category it owns.
                // if those move, so must this string.
                logger.warn {
                    "loc routing is OFF: '$action' on loc $locId reached the content seam and was " +
                        "dropped. 5,288 (loc id, action) pairs bound at boot - every 'Close', " +
                        "'Climb-up', 'Climb-down', 'Climb', 'Climb over', 'Climb-over', 'Walk-up', " +
                        "'Walk-down', 'Search', 'Pick', 'Take' and the 12 scenery shop openers - are " +
                        "REGISTERED BUT UNREACHABLE in this state (2,497 of them placed by map_loc, " +
                        "i.e. clickable in principle). " +
                        "-D$DISPATCH_SWITCH=true routes them. (Warned once.)"
                }
            }
            return false
        }

        val content = world.contentPlayerAt()
        BanksWiring.bind(content, world)
        DialogueWiring.bind(content, world)
        SkillingWiring.bind(content, world)
 //: 'Smelt', 'Heat' and 'Smith' come through this router (they are not gather
        // actions and not Bank/Use), so Smithing's seams need the pairing recorded here too.
        SmithingWiring.bind(content, world)

        // Snapshot BEFORE the handler runs; contentPlayerAt() has just overwritten
        // it from the world player, so this is the world player's own tile.
        val beforeX = content.location.x
        val beforeY = content.location.y
        val beforePlane = content.location.plane

        routed++
        val dispatched = try {
            ContentRegistry.dispatchLoc(content, locId, action, x, z, plane)
        } catch (t: Throwable) {
            logger.error(t) {
                "loc routing: the content handler bound to '$action' on loc $locId THREW. That is a bug " +
                    "in the handler, not in the client; the click is dropped so the session survives it."
            }
            return false
        }

        when (dispatched) {
            is DispatchResult.Handled -> {
                logger.info {
                    "loc routing: ${world.name} '$action' on loc $locId at ($x,$z,plane $plane) -> " +
                        "${dispatched.value} (-D$DISPATCH_SWITCH=true is ON)"
                }
                val after = content.location
                if (after.x != beforeX || after.y != beforeY || after.plane != beforePlane) {
                    droppedMoves++
                    logger.warn {
                        "loc routing: '$action' on loc $locId moved the CONTENT player from " +
                        "($beforeX,$beforeY,plane $beforePlane) to (${after.x},${after.y},plane " +
                        "${after.plane}). Write-back enabled: teleporting WorldPlayer."
                    }
                    world.entity.movement.teleport(after)
                }
                return true
            }
            // The ordinary content gap: the cache declares the option, nothing is
            // written for it. ContentRegistry's own doc says this is not abuse.
            is DispatchResult.NoHandler -> return false
            else -> {
                logger.warn {
                    "loc routing: '$action' on loc $locId was refused by the registry ($dispatched). " +
                        "OpLocHandler read that string out of the same definition the registry validates " +
                        "against, so this is a disagreement between the two and should not be possible."
                }
                return false
            }
        }
    }
}
