package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.serverprot.InvEntry
import com.opennxt.net.game.serverprot.MessageGame
import com.opennxt.net.game.serverprot.UpdateInvFull
import mu.KotlinLogging

/**
 * The ONE place that knows both a [ContentPlayer] and a live [WorldPlayer] for
 * banking, in the shape [SkillingWiring] established and [DialogueWiring]
 * refined.
 */
object BanksWiring {

    private val logger = KotlinLogging.logger { }

    /**
     * ContentPlayer -> WorldPlayer, both held weakly, for the reason
     * [SkillingWiring.owners] documents: a WeakHashMap holds its VALUES
     * strongly, so a strong value would pin every WorldPlayer that ever opened
     * a bank for the lifetime of the process.
     *
     * Synchronized because [bind] runs on whichever Netty thread decoded the
     * packet while the read side runs from a content handler.
     */
    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    fun bind(content: ContentPlayer, world: WorldPlayer) {
        val existing = owners[content]?.get()
        if (existing === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = owners[content]?.get()

    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    internal fun clear() = synchronized(owners) { owners.clear() }

    /** Sends a bank's packets through the player's own interface manager and client. */
    private class LiveSink(val world: WorldPlayer) : Banks.Sink {

        override fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean) =
            world.interfaces.open(id = interfaceId, parent = parent, component = component, walkable = walkable)

        override fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int) =
            world.interfaces.events(interfaceId, component, fromSlot, toSlot, mask)

        override fun closeSub(parent: Int, component: Int) =
            world.interfaces.close(parent, component)

        /**
         * `UPDATE_INV_FULL` for a container that is not the backpack.
         *
         * Positional and FULL-LENGTH, for the reason
         * [com.opennxt.model.entity.player.PlayerInventory.fullPacketFor]
         * states: the client drives its loop off the count and can only address
         * `0..count-1`, so a `null` here is an empty slot on the wire
         * (`objPlusOne == 0`) and not an omission.
         */
        override fun updateInvFull(inv: Int, slots: List<Pair<Int, Int>?>) {
            world.client.write(
                UpdateInvFull(
                    inv = inv,
                    slots = slots.map { entry -> entry?.let { InvEntry(it.first, it.second) } },
                    flags = 0
                )
            )
        }
    }

    /**
     * Points [Banks.sinkSupplier] at the live server.
     *
     * Returns false, and leaves the no-op sink alone, when the bank screen
     * experiment is off - the seam is not moved for a module that will never
     * send anything. The npc/loc bindings and the [com.opennxt.model.bank.Bank]
     * itself are installed by [Banks.install] either way; this call is purely
     * about the wire.
     */
    fun install(): Boolean {
        if (!Banks.uiEnabled) {
            logger.info {
                "banks wiring: the bank SCREEN is off (-Dopennxt.experiment.banks.ui=true to enable). " +
                    "Bank clicks still dispatch, still produce a BankEvent and still reach the player's " +
                    "Bank; nothing is drawn and no interface packet is sent."
            }
            return false
        }
        Banks.sinkSupplier = { content -> ownerOf(content)?.let { LiveSink(it) } }
        logger.warn {
            "banks wiring: EXPERIMENT ON. IF_OPENSUB(${Banks.BANK_INTERFACE} -> " +
                "${Banks.GAMEFRAME}:${Banks.BANK_MOUNT}), UPDATE_INV_FULL(inv ${Banks.BANK_INV}, " +
                "${Banks.BANK_INV_SLOTS} slots) and IF_SETEVENTS(${Banks.BANK_INTERFACE}:" +
                "${Banks.CLOSE_COMPONENT}) now go on the wire on a bank click. This is the first time " +
                "this server has opened a full interface from a click. ${Banks.PROVENANCE_SHORT}"
        }
        return true
    }

    /** Puts the no-op sink back. Only a headless check needs this. */
    fun uninstall() {
        Banks.sinkSupplier = { null }
        clear()
    }

    /**
     * Routes a click on a bank BOOTH, CHEST or COUNTER at [Banks], through
     * [com.opennxt.content.ContentRegistry].
     */
    fun handleLocClick(world: WorldPlayer, locId: Int, action: String, x: Int, z: Int, plane: Int): Boolean {
        // Everything that is NOT Bank/Use goes to the general loc router, which
        // is DEFAULT OFF: with -Dopennxt.experiment.loc.dispatch unset it
        // dispatches nothing and returns false, exactly where the old
        // `return false` was. Stated exactly rather than as "identical": it also
        // bumps LocWiring.skipped and logs ONE warn line the first time a click
        // reaches it, which is how an operator finds out the bindings are inert
 // at all. Nothing is dispatched and no packet changes.
        // production install binds at every boot that no click could reach; see
        // LocWiring's class doc for the census and for what turning it on does
        // NOT fix.
        if (action != Banks.BANK && action != Banks.USE)
            return LocWiring.routeOther(world, locId, action, x, z, plane)
        val content = world.contentPlayerAt()
        bind(content, world)
        val result = com.opennxt.content.ContentRegistry.dispatchLoc(content, locId, action, x, z, plane)
        return when (result) {
            is com.opennxt.content.DispatchResult.Handled -> {
                // The bank screen's own backpack grid, 517:15, renders inv 93
                // (clientscript 13943 -> 9236 with `93` and "Deposit"; and every
                // observed deposit click on it names a backpack slot). The
                // server sends inv 93 at login and after every change, so it is
                // normally already there - but a re-send on open costs one
                // packet and removes the one state the bank's own view depends
                // on and this module does not control. The reference client re-sends it too.
                if (Banks.uiEnabled) PlayerInventory.sendBackpack(world)
                logger.info {
                    "banks: ${world.name} '$action' on loc $locId at ($x,$z,plane $plane) -> ${result.value}" +
                        (if (Banks.uiEnabled) ". ${Banks.PROVENANCE_SHORT}"
                         else " (screen off: -Dopennxt.experiment.banks.ui=true)")
                }
                true
            }
            // The ordinary content gap for a 'Use' on a loc that is not a bank.
            is com.opennxt.content.DispatchResult.NoHandler -> false
            else -> false
        }
    }

    /**
     * Routes an inbound interface click at the bank's close button.
     *
     * Returns true when the bank consumed the click, so the caller can tell "a
     * bank click" from "something else" and keep logging the rest.
     * [Banks.handleClose] refuses every component but the one this module armed
     * - refusing is the point, on the same principle
     * [DialogueWiring.handleButton] applies: the client picks the hash it sends
     * and only the server knows what it armed.
     *
     * It is a no-op when the experiment is off, because nothing was ever armed
     * in that world and a click naming 517 would then be a client inventing an
     * interface it was never given.
     */
    fun handleButton(world: WorldPlayer, packet: IfButtonN): Boolean {
        if (!Banks.uiEnabled) return false
        val content = world.contentPlayerAt()
        bind(content, world)
        if (Banks.handleClose(content, packet.interfaceId, packet.component)) {
            logger.info {
                "bank screen: ${world.name} IF_BUTTON${packet.buttonOp} on ${packet.interfaceId}:" +
                    "${packet.component} closed the bank"
            }
            return true
        }
        return handleItemButton(world, content, packet)
    }

    /**
     * The deposit and withdraw buttons - the half of the bank that did not
     * exist until.
     */
    private fun handleItemButton(world: WorldPlayer, content: ContentPlayer, packet: IfButtonN): Boolean {
        val backpack = PlayerInventory.backpackOf(world)
        val worn = if (PlayerInventory.equipEnabled) world.worn else null
        val result = Banks.handleButton(
            content, packet.interfaceId, packet.component, packet.buttonOp,
            packet.mid, packet.arg2, Banks.Containers(backpack, worn)
        )
        when (result) {
            is Banks.ButtonResult.NotOurs -> return false

            is Banks.ButtonResult.Deposited -> {
                if (result.component == Banks.DEPOSIT_WORN_COMPONENT) {
                    PlayerInventory.sendWorn(world)
                    world.entity.model.dirty = true
                } else {
                    PlayerInventory.sendBackpack(world)
                }
                world.client.write(
                    MessageGame(0, "You deposit ${result.units} item(s) into your bank.")
                )
                if (result.leftBehind > 0) {
                    world.client.write(MessageGame(0, "Your bank is too full to hold everything."))
                }
                logger.info {
                    "bank screen: ${world.name} IF_BUTTON${packet.buttonOp} on 517:${result.component} " +
                        "deposited ${result.units} item(s) over ${result.ids} id(s) from ${result.source}; " +
                        "${result.leftBehind} id(s) left behind; bank ${result.bankSlots} slot(s)."
                }
            }

            is Banks.ButtonResult.Withdrew -> {
                // BOTH containers, and this is the visible half of the operator's
 // report. Banks.handleButton has already re-sent inv 95
                // through its own Sink; inv 93 is the player's and only this file
                // knows it. Without it the standalone backpack panel (1473:5) and
                // the bank's own backpack grid (517:15) both keep showing the
                // pre-click contents, which is exactly "it only shows in the
                // backpack space in the bank menu".
                PlayerInventory.sendBackpack(world)
                logger.info {
                    "bank screen: ${world.name} withdrew ${result.withdrawn} x ${result.id} from bank " +
                        "slot ${result.slot} (${result.stillBanked} still banked); inv " +
                        "${PlayerInventory.backpackInv} and inv ${Banks.BANK_INV} both re-sent."
                }
                if (result.withdrawn < result.requested) {
                    world.client.write(MessageGame(0, "You don't have enough room for all of that."))
                }
            }

            is Banks.ButtonResult.DepositedOne -> {
                PlayerInventory.sendBackpack(world)
                logger.info {
                    "bank screen: ${world.name} deposited ${result.moved} x ${result.id} from backpack " +
                        "slot ${result.slot} (${result.bankedTotal} banked, ${result.bankSlots} slot(s)); " +
                        "inv ${PlayerInventory.backpackInv} and inv ${Banks.BANK_INV} both re-sent."
                }
            }

            // Nothing moved and no container changed, so nothing is re-sent: the
            // client repaints its own quantity highlight from the click.
            is Banks.ButtonResult.QuantityChanged -> {
                logger.info {
                    "bank screen: ${world.name} set the bank move quantity to " +
                        (if (result.now == Banks.ALL) "ALL" else "${result.now}") +
                        " on 517:${result.component} (was " +
                        (if (result.was == Banks.ALL) "ALL" else "${result.was}") + ")."
                }
            }

            is Banks.ButtonResult.Refused -> {
                result.message?.let { world.client.write(MessageGame(0, it)) }
                logger.info {
                    "bank screen: ${world.name} IF_BUTTON${packet.buttonOp} on 517:${result.component} " +
                        "refused - ${result.why}. Nothing moved."
                }
            }
        }
        return true
    }
}
