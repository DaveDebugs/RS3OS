package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.LocContext
import com.opennxt.content.NpcContext
import com.opennxt.model.bank.Bank
import com.opennxt.model.items.ItemContainer
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging

/**
 * Banks: dispatch validation and a clean seam for bank access points.
 *
 * ## Scope, stated up front
 *
 * **Item storage now exists**: [com.opennxt.model.bank.Bank], a per-player
 * container where everything stacks. What this module adds on top of the
 * original dispatch-validation job is the smallest possible seam between the
 * two: opening a bank access point hands the acting player's [Bank] on the
 * [BankEvent] (see [bankSupplier] for where that bank comes from). Bank
 * CONTENTS now persist: [restoreBank] loads them at login and the player-leave
 * and autosave hooks observation them through [Bank.contents]. Tabs, placeholders
 * and presets remain future work; the dispatch framework still proves the
 * requested action is one the definitions declare, exactly as [Doors]
 * established, and nothing about registration or validation changed.
 *
 * **The bank SCREEN now exists too, and it defaults OFF.** [openScreen] sends
 * the container and opens interface 517 at 1477:693; every number in that
 * sentence is re-derived from this cache and controlled in [BANK_INTERFACE]'s
 * doc, and the switch that turns it on is [uiEnabled]. Two things changed to
 * make it reachable from a real click: `Bank` is now bound on NPCs as well as
 * locs (see [onBankNpc] - 106 npc ids, and the npc route is the ONLY one the
 * packet layer already dispatches), and the close button is armed on the way in
 * (see [CLOSE_COMPONENT] for why an unarmed close is not a cosmetic problem).
 *
 * ## What is grounded in the cache
 *
 * Bank access points are found by their declared options, measured on
 * `data/rs3.sqlite` (`actions_0` plus the `locs_attr` slot rows, which
 * [com.opennxt.resources.sqlite.SqliteLocCodec] merges):
 *
 * ```
 *   locs declaring "Bank"                            74   (4 in actions_0, 70 in attr slots)
 *   ...by name: Bank booth 44, Counter 9, Bank chest 4, Treasure chest 4, Bank table 2, ...
 *   canonical booth: 782 'Bank booth', actions = [Bank, Collect, Load Last Preset from]
 *   canonical chest: 4483 'Bank chest', actions = [Use, Collect, Load Last Preset from]
 *   bank-named locs declaring "Use"                  67   (mostly 'Bank chest'; booths use "Bank")
 * bank-named locs declaring "Open" 0 measured - so nothing here binds "Open",
 *                                                         and binding it would collide with Doors'
 *                                                         category binding across all 2170 Open locs
 *   locs declaring "Collect"                        234   unbound - collection box, future work
 *   locs declaring "Load Last Preset from"          135   unbound - presets need storage first
 * ```
 *
 * Two binding strategies, because the two options differ in specificity:
 *
 *  1. **"Bank" is bound as a category.** Every one of the 74 declaring locs is a
 *     bank access point - the option name itself says so - so the category
 *     binding is safe the same way "Open" is for doors.
 *  2. **"Use" is bound per-id, scoped by name.** "Use" is generic: 278 locs
 *     declare it (172 in actions_0, 106 in attr slots), most of them nothing to
 *     do with banking. So the ids come out of the database by a query - locs
 *     named like a bank booth/chest that declare "Use" - rather than a category
 *     binding sweeping in every "Use" loc, and rather than a hardcoded list that
 *     goes stale when the cache moves. AUTHORED: the *selection rule* (scoping
 *     by name) is a decision made here; the ids it selects are the database's.
 *
 * A client sending "Bank" at a loc that does not declare it is rejected by
 * [ContentRegistry] before this module ever sees the packet - that is the
 * dispatch validation this module exists to exercise.
 */
object Banks {
    private val logger = KotlinLogging.logger { }

    const val BANK = "Bank"
    const val USE = "Use"

    /**
     * What every bank handler returns. A constant rather than a literal because
     * asserts on the exact string
     * and two handlers now produce it.
     */
    const val OPENED = "bank-opened"

    /**
     * The documented content event: opening a bank produced one of these. It is
     * the module's observable output the way collision movement is for [Doors],
     * and it is what asserts fires
     * for a bank booth and does NOT fire for a ladder.
     */
    data class BankEvent(val player: String, val bank: Bank, val locId: Int, val locName: String?,
                         val action: String, val x: Int, val z: Int, val plane: Int,
                         /**
                          * The npc id when the bank was opened by clicking a BANKER rather than a
                          * booth, and -1 otherwise. 106 npc ids in this cache declare 'Bank' (51 of
                          * them literally named 'Banker'); 74 loc ids do. A loc-sourced event keeps
                          * [locId] and leaves this -1, an npc-sourced event does the reverse, so the
                          * two are never confusable and [locName] carries whichever name applied.
                          */
                         val npcId: Int = -1,
                         val npcIndex: Int = -1) {
        val fromNpc: Boolean get() = npcId >= 0
    }

    private val events = ArrayList<BankEvent>()

    fun eventCount(): Int = events.size
    fun lastEvent(): BankEvent? = events.lastOrNull()

    /** Drops recorded events AND the per-player bank store. For checks and content reloads. */
    fun clear() {
        events.clear()
        banks.clear()
        synchronized(screenOpen) { screenOpen.clear() }
        opens = 0
        closes = 0
        lastScreen = null
        deposits = 0
        withdrawals = 0
        refusals = 0
 // with the grids: per-player UI state and the derived
        // quantity-button map. A check that ran two scenarios in one JVM would
        // otherwise carry one player's quantity mode into the next, and the
        // randomised conservation runs depend on starting from the default.
        quantityMode.clear()
        synchronized(armedMasks) { armedMasks.clear() }
        quantityButtonCache = null
    }

    // ---- the player->bank seam ------------------------------------------

    /**
     * The per-account bank store, and it is CONCURRENT because two real threads
     * write to it.
     *
     * It was a plain [HashMap] with a Kotlin `getOrPut`, and the two mutators
     * are on different threads:
     *
     * NETTY EVENT LOOP com.opennxt.net.login.LoginServerHandler
     * .handleGameLoginContinue (an inbound handler callback)
     * -> WorldPlayer.<init> -> [restoreBank] -> [bankSupplier]
     * TICK THREAD World.autosave / World.cullDisconnected
     * -> WorldPlayer.toSave -> [bankForAccount] -> [bankSupplier]
     *
     * So a login that lands on the same 600 ms window as an autosave is two
     * unsynchronized writers inside one HashMap.
     */
    private val banks = java.util.concurrent.ConcurrentHashMap<String, Bank>()

    /**
     * The identity the default supplier keys on: the account name, case-folded.
     *
     * FOLDED ON PURPOSE. [com.opennxt.model.account.AccountStore] makes
     * usernames unique case-insensitively (`COLLATE NOCASE`, see its doc), so
     * 'alice' and 'Alice' are ONE account with ONE save. A bank store
     * keyed on the raw string would hand 'Alice' an empty bank while
     * 'alice' owns the items, and the next save would then write that empty
     * bank over the real one. The two identity rules have to agree, so this one
     * folds too. [String.lowercase] with no locale is Locale.ROOT (folds a
     * little wider than SQLite's ASCII-only NOCASE, which is harmless for the
     * RS username alphabet and errs towards "same account", never towards
     * splitting one).
     */
    fun bankKey(name: String): String = name.lowercase()

    /**
     * The smallest seam that gets a player their bank: [ContentPlayer] (what a
     * [LocContext] carries) owns an inventory, equipment and varps but no bank,
     * and widening it is not this module's call. So the module owns the lookup
     * instead, as a replaceable supplier. The DEFAULT keeps an in-memory map
     * keyed by [bankKey] of the player name - one [Bank] per account, created
     * on first use.
     *
     * The bank a player *carries* is still in memory; what now survives a
     * restart is its CONTENTS, recorded into the account's
     * [com.opennxt.model.account.PlayerSave] on leave/autosave and put back by
     * [restoreBank] at login. A supplier replacement (a real per-player store,
     * a database) changes where the live object comes from and nothing else
     * here moves: [restoreBank] and [bankForAccount] both go through the
     * supplier rather than around it.
     */
    var bankSupplier: (ContentPlayer) -> Bank =
        { player -> banks.computeIfAbsent(bankKey(player.name)) { Bank() } }

    /** The acting player's bank, via [bankSupplier]. */
    fun bankOf(player: ContentPlayer): Bank = bankSupplier(player)

    /**
     * The same bank, reached by account name alone.
     *
     * A leaving or joining player is a [com.opennxt.model.lobby.LobbyPlayer] or
     * a [com.opennxt.model.world.WorldPlayer] - neither is a [ContentPlayer],
     * and neither should have to become one just to be saved. The supplier keys
     * on the player NAME (see [bankKey]), so presenting that same identity is
     * enough to reach the same [Bank] the bank booth hands out. The synthetic
     * [ContentPlayer] carries nothing but the name for exactly that reason: it
     * is an identity, not a stand-in player, and it is thrown away here.
     */
    fun bankForAccount(username: String): Bank = bankOf(ContentPlayer(name = username))

    /**
     * Puts an account's SAVED bank contents back into its live [Bank], at
     * login, before anything reads or writes that bank. Returns the bank so a
     * caller can see what it restored into.
     *
     * ## Relogin ordering - the choice, stated where a reader looks
     *
     * A login can land on a name that ALREADY has an in-memory bank: a relogin
     * after a dropped connection the cull has not processed yet, or a second
     * session for the same account. Three things could happen, and only one of
     * them is honest:
     *
     *  1. **Merge** the saved contents into the live bank - every relogin would
     *     duplicate the player's entire bank. An item duplication bug, not a
     *     policy.
     *  2. **Drop the map entry and build a fresh [Bank]** - the stale object
     *     stays alive in whatever still holds it (a recorded [BankEvent], an
     *     open bank screen, the old session's leave-save), and those deposits
     *     go into a bank nothing will ever store. The clobber would be silent:
     *     both banks look fine, one of them is a ghost.
     *  3. **Replace the contents of the live object** - what this does. The
     *     bank is cleared and the saved layout is placed slot-for-slot
     *     ([Bank.restore]), so every existing reference observes the login, and
     *     the live bank and the stored save agree the moment this returns.
     *
     * The stored save wins over whatever was in memory, which is the same
     * "latest stored wins" rule [com.opennxt.model.account.AccountStore]
     * already applies to saves - the alternative is an old session's in-memory
     * state quietly outranking the database.
     *
     * This does not make concurrent sessions safe; it makes the ordering
     * defined. Refusing a second session outright belongs at the login layer,
     * which is where the account is authenticated, not here.
     */
    fun restoreBank(username: String, items: List<Bank.BankedItem>): Bank {
        val bank = bankForAccount(username)
        val had = bank.usedSlots()
        bank.restore(items)
        if (had > 0) {
            logger.info {
                "bank for '$username' already existed in memory with $had slot(s) used; " +
                    "REPLACED with the ${items.size} slot(s) from the stored save (relogin ordering: the save wins)"
            }
        }
        return bank
    }

    /**
     * The loc handler: a bank booth, a bank chest, a counter.
     */
    fun onBank(ctx: LocContext): Any? {
        events.add(
            BankEvent(
                player = ctx.player.name,
                bank = bankOf(ctx.player),
                locId = ctx.locId,
                locName = ctx.definition.name,
                action = ctx.action,
                x = ctx.x, z = ctx.z, plane = ctx.plane
            )
        )
        openScreen(ctx.player)
        return OPENED
    }

    /**
     * The npc handler: a BANKER.
     */
    fun onBankNpc(ctx: NpcContext): Any? {
        events.add(
            BankEvent(
                player = ctx.player.name,
                bank = bankOf(ctx.player),
                locId = -1,
                locName = ctx.definition.name,
                action = ctx.action,
                x = ctx.x, z = ctx.z, plane = ctx.plane,
                npcId = ctx.npcId, npcIndex = ctx.npcIndex
            )
        )
        openScreen(ctx.player)
        return OPENED
    }


    // =====================================================================
    // THE BANK SCREEN
    // =====================================================================

    /**
     * The bank interface, its mount, and its container - every number below
     * re-derived from THIS cache in this pass, not inherited.
     */
    const val BANK_INTERFACE = 517

    /** The 949 world gameframe. Same constant [com.opennxt.model.entity.player.InterfaceManager] uses. */
    const val GAMEFRAME = 1477

    /** Component of [GAMEFRAME] the bank mounts into: struct 21308 param 3503. See [BANK_INTERFACE]. */
    const val BANK_MOUNT = 693

    /** The bank's container. See [BANK_INTERFACE] for the three listener components. */
    const val BANK_INV = 95

    /**
     * `js5[2, 5:95]` opcode 2 = 1820. Read from the cache, asserted by
     * section (1); it is a constant
     * here only so a headless check can compare the two.
     */
    const val BANK_INV_CACHE_SIZE = 1820

    /**
     * How many slots `UPDATE_INV_FULL` claims for [BANK_INV].
     *
     * The cache says the container is [BANK_INV_CACHE_SIZE] wide; the model can
     * only ever fill [Bank.CAPACITY] of them. Both widths are safe on the wire
     * (the client loops `count` times and can address `0..count-1`), so this is
     * a real choice and it is stated rather than defaulted into:
     *
     *  * sending 510 costs 2,040 bytes and leaves slots 510..1819 with whatever
     *    the client had before - nothing, on a fresh session;
     *  * sending 1820 costs 7,280 bytes, 5,240 of them guaranteed empties, and
 * removes a question this server cannot answer from the cache alone -
     *    whether the bank's own clientscripts index up to `inv_size(95) - 1`
     *    (1819) rather than to the packet's count.
     *
     * It sends the cache's width, because "the cache declares 1820" is a
     * measurement and "510 is surely enough" is an assumption. Override with
     * `-Dopennxt.experiment.banks.invSlots=<n>` without a rebuild.
     */
    val BANK_INV_SLOTS: Int =
        System.getProperty("opennxt.experiment.banks.invSlots")?.toIntOrNull() ?: BANK_INV_CACHE_SIZE

    /**
     * Interface 517's close button: `(517 shl 16) or 317` is literally the
     * fourth argument of 517:0's own onload `script 8420`, alongside the title
     * string `Bank of Gielinor` and the panel id 1017. 517 has three components
     * whose menu option is `Close` (86, 310, 317); 317 is the one the cache's
     * own window-frame call names, so it is the one that gets armed.
     *
     * ## Why arming it is not optional
     *
     * This build maps NO inbound "close interface" ClientProt - the 949
     * clientProt directory has IF_BUTTON1..10, IF_BUTTON_LABELLED and nothing
     * else that could carry one - and `IF_SETEVENTS`'s own analysis in
     * [com.opennxt.model.entity.player.InterfaceManager.events] records that the
     * client only reports a click on a component the server armed, and that the
     * 1477 family has no cache-baked `optmask` fallback to lean on (7 of 923
     * components carry one).
     *
     * A previous experiment on this server opened a full-screen modal that
     * could not be closed for exactly that reason and cost the operator the
     * whole game UI. So the open sequence arms this component in the same
     * breath as opening the interface, and [handleClose] is what a click on it
     * reaches. It is still an experiment and still defaults OFF - see
     * [uiEnabled] - because "the arm packet is correct" is a claim about a wire
     * format, and only a live client can settle it.
     */
    const val CLOSE_COMPONENT = 317

    /**
     * The click mask that arms op 1 on a component.
     */
    const val OP1_MASK = 2

    /**
     * THE KILL SWITCH, and it defaults OFF.
     */
    val uiEnabled: Boolean get() = System.getProperty("opennxt.experiment.banks.ui") == "true"

    /**
     * The property name of
     * [com.opennxt.net.game.handlers.OpNpcHandler.DISPATCH_SWITCH], repeated as
     * a LITERAL.
     */
    const val NPC_DISPATCH_SWITCH = "opennxt.experiment.npc.dispatch"

    /**
     * Whether a click on one of the 106 `Bank` npcs can reach [onBankNpc] at
     * all, read straight off the system property named by [NPC_DISPATCH_SWITCH].
     * Default false, matching `OpNpcHandler.dispatchEnabled`.
     */
    val npcClickReaches: Boolean get() = System.getProperty(NPC_DISPATCH_SWITCH) == "true"

    /** One line a log or an event can carry that says what is measured and what is not. */
    const val PROVENANCE_SHORT =
        "bank screen: interface 517 at 1477:693 (struct 21308 param 3503, 3 client-script literal sites, " +
            "0 for param 3505), container inv 95 (3 listener components on 517; 4 of 1869 interfaces name 95), " +
            "cache-declared 1820 slots. cache; not verified against a live client."

    /**
     * What [openScreen] did, as a value rather than a boolean, so "the
     * experiment is off" and "this player has no live session" cannot be
     * confused with "it opened".
     */
    sealed class ScreenResult {
        /** Opened, and the container went with it. [resent] is true for a re-open on an already-open screen. */
        data class Opened(val interfaceId: Int, val parent: Int, val component: Int,
                          val inv: Int, val slots: Int, val occupied: Int, val resent: Boolean) : ScreenResult()

        /** `-Dopennxt.experiment.banks.ui` is not `true`. Nothing was sent. */
        object Disabled : ScreenResult()

        /** No sink for this player - a headless [ContentPlayer], or the wiring is not installed. */
        object NoSink : ScreenResult()
    }

    /**
     * The seam between this module and the wire, in the shape [Dialogue.Sink]
     */
    interface Sink {
        /** IF_OPENSUB. */
        fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean)

        /**
         * UPDATE_INV_FULL. [slots] is POSITIONAL - index i is slot i, `null` is
         * an empty slot, `(id, amount)` otherwise - because that is what the
         * packet is: the client drives its loop off the count and can only
         * address `0..count-1`.
         */
        fun updateInvFull(inv: Int, slots: List<Pair<Int, Int>?>)

        /** IF_SETEVENTS. */
        fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int)

        /** IF_CLOSESUB. */
        fun closeSub(parent: Int, component: Int)
    }

    /** Replaced by [com.opennxt.content.impl.BanksWiring.install]; a no-op sink by default. */
    var sinkSupplier: (ContentPlayer) -> Sink? = { null }

    /**
     * Which players currently have the screen open, weakly keyed so a logout
     * cannot pin a [ContentPlayer].
     *
     * It exists so a second bank click does not send a second IF_OPENSUB into a
     * component that already holds one -
     * [com.opennxt.model.entity.player.InterfaceManager.open] logs
     * "Overriding an interface" for that and the client's mount map would keep
     * only the last one. The second click still RE-SENDS the container, because
     * that is the cheap way to be sure the screen shows what the bank holds.
     */
    private val screenOpen: MutableMap<ContentPlayer, Boolean> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    private var opens = 0
    private var closes = 0
    private var lastScreen: ScreenResult? = null

    fun screenOpens(): Int = opens
    fun screenCloses(): Int = closes
    fun lastScreenResult(): ScreenResult? = lastScreen
    fun isScreenOpen(player: ContentPlayer): Boolean = screenOpen[player] == true

    /**
     * A bank's contents as the positional slot list `UPDATE_INV_FULL` wants.
     *
     * [Bank] deliberately hands out `contents()` - immutable triples for the
     * OCCUPIED slots only - rather than its container, so this is the one place
     * that turns that into a dense array. A slot outside `0 until size` is
     * DROPPED rather than silently wrapped or allowed to throw: with
     * [BANK_INV_SLOTS] at the cache's 1820 and [Bank.CAPACITY] at 510 that
     * cannot happen today, and it is written so that lowering the property
     * cannot turn into an exception inside a click.
     */
    fun positionalSlots(bank: Bank, size: Int = BANK_INV_SLOTS): List<Pair<Int, Int>?> {
        val out = arrayOfNulls<Pair<Int, Int>>(size)
        var dropped = 0
        bank.contents().forEach { item ->
            if (item.slot in 0 until size) out[item.slot] = item.id to item.amount else dropped++
        }
        if (dropped > 0) {
            logger.warn {
                "bank screen: $dropped bank slot(s) sit past the $size slots UPDATE_INV_FULL is sending " +
                    "for inv $BANK_INV and were NOT transmitted (-Dopennxt.experiment.banks.invSlots)"
            }
        }
        return out.toList()
    }

    /**
     * Opens the bank screen for [player], if the experiment is on and the
     * player has a live sink.
     *
     * ## The order, and which part of it is authored
     *
     * 1. `UPDATE_INV_FULL(inv 95)` **first**, then `IF_OPENSUB(517 -> 1477:693)`.
     * 2. `IF_SETEVENTS(517:317, -1..-1, mask 2)` last, so the close button is
     *    armed only once the interface it belongs to is mounted -
     *    [com.opennxt.model.entity.player.InterfaceManager.events] warns for an
     *    arm on an interface the player does not have open, and that warning is
     *    worth keeping meaningful.
     *
     * Step 2's position is forced. Step 1's is **AUTHORED**: nothing in the
     * cache orders the container against the open. The reasoning is the
     * backpack gate's - a panel whose state was never sent renders locked - so
     * the state is on the client before the thing that reads it exists. The
     * client rebuilds a component when a listened inventory changes, so the
     * other order would very likely work too; what would settle it is a live
 * observation of Jagex's own bank open, which this server does not have.
     */
    fun openScreen(player: ContentPlayer): ScreenResult {
        if (!uiEnabled) {
            lastScreen = ScreenResult.Disabled
            return ScreenResult.Disabled
        }
        val sink = sinkSupplier(player) ?: run {
            lastScreen = ScreenResult.NoSink
            return ScreenResult.NoSink
        }

        val bank = bankOf(player)
        val slots = positionalSlots(bank)
        sink.updateInvFull(BANK_INV, slots)

        val already = screenOpen[player] == true
        if (!already) {
            // walkable = true: every IF_OPENSUB this server has ever sent uses
            // flag 1, because the recorded 919 login replay it inherited does.
            // That is the only observed convention for this field on this
            // build; inventing flag 0 for the bank alone would be a second
            // unmeasured variable in an experiment that already has one.
            sink.openSub(BANK_INTERFACE, GAMEFRAME, BANK_MOUNT, walkable = true)
            sink.setEvents(BANK_INTERFACE, CLOSE_COMPONENT, -1, -1, OP1_MASK)
            // -------------------------------------------------------------
 // THE TWO GRIDS. This is the fix for the
            // operator's "withdrawing doesn't put it in my backpack": both
            // grids carry cache optmask 0, so until these two packets existed
            // the client reported no click on either and the server never saw
            // a withdraw or a per-item deposit at all
            // (logs/server-20260906-234053.out.log: 517:39 and 517:317, nothing
            // else, over 25 IF_BUTTON1 frames).
            //
            // The component, range and mask of each are REFERENCE's, identical
            // across seven bank opens in six sessions - see [REFERENCE_517]. The
            // previous pass declined to send them because they would have been
            // a guess; they are now a measurement, and the same paragraph that
            // declined names this as the thing that would change its mind.
            //
            // Order: the close button first (it is the one packet this server
            // has already exercised live, and moving it would put an
            // un-measured variable into a sequence that works), then the two
            // grids in the reference client's own relative order - 201 before 15.
            // -------------------------------------------------------------
            sink.setEvents(BANK_INTERFACE, ITEM_LAYER_COMPONENT, 0, ITEM_LAYER_SLOTS, ITEM_LAYER_MASK)
            recordArm(player, ITEM_LAYER_COMPONENT, ITEM_LAYER_MASK)
            sink.setEvents(BANK_INTERFACE, BACKPACK_LAYER_COMPONENT, 0, BACKPACK_LAYER_SLOTS,
                BACKPACK_LAYER_MASK)
            recordArm(player, BACKPACK_LAYER_COMPONENT, BACKPACK_LAYER_MASK)
            screenOpen[player] = true
            opens++
        }

        val result = ScreenResult.Opened(
            interfaceId = BANK_INTERFACE, parent = GAMEFRAME, component = BANK_MOUNT,
            inv = BANK_INV, slots = slots.size, occupied = bank.usedSlots(), resent = already
        )
        lastScreen = result
        logger.info {
            "bank screen: ${player.name} " + (if (already) "re-sent" else "opened") +
                " interface $BANK_INTERFACE at $GAMEFRAME:$BANK_MOUNT with inv $BANK_INV " +
                "(${slots.size} slots, ${bank.usedSlots()} occupied). $PROVENANCE_SHORT"
        }
        return result
    }

    /**
     * Closes the bank screen. Returns true when a close was actually sent -
     * that is, when this player had one open.
     */
    fun closeScreen(player: ContentPlayer): Boolean {
        if (screenOpen[player] != true) return false
        val sink = sinkSupplier(player) ?: return false
        sink.closeSub(GAMEFRAME, BANK_MOUNT)
        screenOpen[player] = false
        closes++
        logger.info { "bank screen: ${player.name} closed $GAMEFRAME:$BANK_MOUNT (was interface $BANK_INTERFACE)" }
        return true
    }

    /**
     * Routes a click on interface [BANK_INTERFACE]'s close button.
     *
     * Returns true only when this really was a click on the component this
     * module armed. Anything else is refused and falls through to whatever else
     * wants the click, on the same principle
     * [Dialogue.onButton] applies: the client picks the hash it sends, and only
     * the server knows which components it armed.
     */
    fun handleClose(player: ContentPlayer, interfaceId: Int, component: Int): Boolean {
        if (interfaceId != BANK_INTERFACE || component != CLOSE_COMPONENT) return false
        return closeScreen(player)
    }

    // ---- the buttons that MOVE items -------------------------------------

    const val DEPOSIT_BACKPACK_COMPONENT = 39

    /** "Deposit worn items" - see [DEPOSIT_BACKPACK_COMPONENT]'s table. */
    const val DEPOSIT_WORN_COMPONENT = 42

    /** "Deposit familiar items". No familiars exist in this repository; refused with a message, never silently. */
    const val DEPOSIT_FAMILIAR_COMPONENT = 45

    /** "Deposit coin pouch". No money pouch exists in this repository; refused with a message. */
    const val DEPOSIT_COINPOUCH_COMPONENT = 48

    /** The bank grid: the only component of 517 that both listens on inv [BANK_INV] and is a 336x1905 layer. */
    const val ITEM_LAYER_COMPONENT = 201

    /** The tab strip's `View all` button - recorded so nobody mistakes it for a deposit again. */
    const val TAB_STRIP_COMPONENT = 165

    /** `mid` on an IF_BUTTON frame when the clicked component carried no item, per [ItemOps]. */
    const val NO_ITEM = 0xFFFFFF

    // =====================================================================
 // WHAT THE REFERENCE CLIENT ARMS ON 517, AND WHAT ITS CLICKS MEAN -
    // =====================================================================

    const val REFERENCE_517 =
        "the reference client 517 arm (7 opens across 6 sessions): 517:201 slots 0.1820 mask 11012094, " +
            "517:15 slots 0..27 mask 14682110 (inv 93), both enabling ops 1..10"

    /** toplevel bank grid, inv [BANK_INV]. The reference client's `toSlot`; see [REFERENCE_517]. */
    const val ITEM_LAYER_SLOTS = 1820

    /** The reference client's events mask for [ITEM_LAYER_COMPONENT]. Bits 1.10 set (+19, 21, 23). See [REFERENCE_517]. */
    const val ITEM_LAYER_MASK = 11012094

    /**
     * The bank screen's own BACKPACK grid - inv 93, 28 slots.
     *
     * Named by the cache (clientscript 13943 -> 9236 with `93` and `"Deposit"`,
     * component hash 33882127) and by the wire (22 clicks whose `arg2` is a
     * backpack slot). See [REFERENCE_517].
     */
    const val BACKPACK_LAYER_COMPONENT = 15

    /** The reference client's `toSlot` for [BACKPACK_LAYER_COMPONENT]: 27, i.e. 28 slots. */
    const val BACKPACK_LAYER_SLOTS = 27

    /** The reference client's events mask for [BACKPACK_LAYER_COMPONENT]. Bits 1.10 set (+21, 22, 23). */
    const val BACKPACK_LAYER_MASK = 14682110

    /**
     * The sentence every quantity button's own op-5 tooltip carries, which is
     * how the five are IDENTIFIED rather than typed.
     *
     * `interfaces_attr.scripts` trigger `"5"` on 517:93/96/99/103/106 is
     * `script 10009` whose first argument is an English sentence:
     *
     * ```
     * 517:93 "Change the default number of items to move to 1."
     * 517:96 "Change the default number of items to move to 5."
     * 517:99 "Change the default number of items to move to 10."
     * 517:103 "Change the default number of items to move to all."
     * 517:106 "Change the default number of items to move to a custom number."
     * ```
     *
     * [quantityButtons] runs that query at run time and parses the trailing
     * token, so the component ids are the DATABASE's and not this file's; a
     * cache in which 517 has no such buttons yields an empty map and the mode
     * simply never changes. All five carry cache `optmask` 2, so their clicks
     * already reach this server with no `IF_SETEVENTS` at all - which is why
     * they were arriving and being dropped before today.
     */
    const val QUANTITY_TOOLTIP = "Change the default number of items to move to "

    /** [ROW_AMOUNTS] and the quantity mode use this for "as many as there are". */
    const val ALL = Int.MAX_VALUE

    /** A row this repository cannot serve (Withdraw-X / Deposit-X: no numeric-input path exists here). */
    const val UNSUPPORTED = -1

    /** A row whose meaning is the player's current quantity mode. Row 0, i.e. the plain left click. */
    const val USE_QUANTITY_MODE = -2

    /**
     * MENU ROW (0-based) -> how many items that row moves, for BOTH grids.
     *
     * Resolved from an inbound op through [rowFor], which goes through
     * [ItemOps.rowForOp] against the mask THIS module armed - never `op - 1`.
     */
    val ROW_AMOUNTS: List<Int> = listOf(USE_QUANTITY_MODE, 1, 5, 10, ALL, UNSUPPORTED)

    /**
     * The live containers a bank button may move items between, supplied by the
     * caller because this module owns no player state and imports no
     * `model.world`.
     *
     * A `null` member means "this player has no such container right now" and is
     * REFUSED, never treated as empty: emptiness and absence produce the same
     * zero and only one of them is a bug worth a log line.
     */
    data class Containers(val backpack: ItemContainer? = null, val worn: ItemContainer? = null)

    /** What [handleButton] did. A type, so "not mine" and "moved nothing" cannot be confused. */
    sealed class ButtonResult {
        /** Not a click this module owns. The caller must keep offering it to whatever is next. */
        object NotOurs : ButtonResult()

        /**
         * [ids] distinct item ids and [units] individual items left [source] and
         * are now in the bank. [leftBehind] counts ids the bank could not take
         * in full - those are still in the source, never destroyed.
         */
        data class Deposited(val component: Int, val source: String, val ids: Int, val units: Long,
                             val leftBehind: Int, val bankSlots: Int) : ButtonResult()

        /** [withdrawn] of [requested] x [id] moved out of bank slot [slot]. */
        data class Withdrew(val id: Int, val slot: Int, val requested: Int, val withdrawn: Int,
                            val stillBanked: Long) : ButtonResult()

        /**
         * [moved] of [requested] x [id] left backpack slot [slot] for the bank -
         * the per-item deposit off [BACKPACK_LAYER_COMPONENT], as opposed to
         * [Deposited], which is a whole container emptied by one of the four
         * `Deposit ...` buttons. Two types rather than one because the two have
         * different failure modes and the wiring re-sends different containers.
         */
        data class DepositedOne(val id: Int, val slot: Int, val requested: Int, val moved: Int,
                                val bankedTotal: Long, val bankSlots: Int) : ButtonResult()

        /**
         * A quantity button ([QUANTITY_TOOLTIP]) moved this player's default
         * move size from [was] to [now]. Nothing was moved; the client already
         * repainted its own highlight.
         */
        data class QuantityChanged(val component: Int, val was: Int, val now: Int) : ButtonResult()

        /**
         * The click was ours and nothing moved. [why] is for the log, [message]
         * for the player (null when there is nothing honest to tell them).
         */
        data class Refused(val component: Int, val why: String, val message: String? = null) : ButtonResult()
    }

    private var deposits = 0
    private var withdrawals = 0
    private var refusals = 0

    fun depositCount(): Int = deposits
    fun withdrawCount(): Int = withdrawals
    fun refusalCount(): Int = refusals

    // ---- the quantity mode -----------------------------------------------

    /**
     * The five quantity buttons of interface [BANK_INTERFACE], component id ->
     * the number that button sets, DERIVED FROM THE DATABASE.
     *
     * The component ids are not typed here and must not be: the query pulls
     * every `scripts` blob on 517, finds the op-5 (hover) trigger whose first
     * argument begins with [QUANTITY_TOOLTIP], and reads the amount off the end
     * of the sentence the client itself shows the player. `all` becomes [ALL];
     * a bare integer becomes itself; anything else (`a custom number`) becomes
     * [UNSUPPORTED], so a cache that grows a sixth button gets a refusal with a
     * log rather than a silent mis-route.
     *
     * Computed once and cached, because it is read on the click path;
     */
    @Volatile
    private var quantityButtonCache: Map<Int, Int>? = null

    fun quantityButtons(): Map<Int, Int> {
        quantityButtonCache?.let { return it }
        val out = LinkedHashMap<Int, Int>()
        runCatching {
            RsDatabase.queryAll(
                "SELECT id, value FROM interfaces_attr WHERE field = 'scripts' AND id BETWEEN " +
                    "${BANK_INTERFACE shl 16} AND ${(BANK_INTERFACE shl 16) or 0xffff} ORDER BY id"
            ) { it.getInt(1) to (it.getString(2) ?: "") }
        }.getOrDefault(emptyList()).forEach { (id, blob) ->
            val at = blob.indexOf(QUANTITY_TOOLTIP)
            if (at < 0) return@forEach
            val tail = blob.substring(at + QUANTITY_TOOLTIP.length).substringBefore('.').trim()
            val amount = when {
                tail.equals("all", ignoreCase = true) -> ALL
                tail.toIntOrNull() != null && tail.toInt() >= 1 -> tail.toInt()
                else -> UNSUPPORTED
            }
            out[id and 0xffff] = amount
        }
        quantityButtonCache = out
        return out
    }

    /**
     * How many items one plain (row-0) click moves for this player, right now.
     *
     * Per-player and weakly keyed, exactly like [screenOpen]: it is a UI
     * preference, not an entitlement, so it lives with the session rather than
     * in [com.opennxt.model.account.PlayerSave]. The reference client persists it across
     * logins; this does not, and that is a stated gap rather than an oversight -
     * persisting it means editing `PlayerSave`, which is not this task's file.
     */
    private val quantityMode: MutableMap<ContentPlayer, Int> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    const val DEFAULT_QUANTITY = 1

    fun quantityOf(player: ContentPlayer): Int = quantityMode[player] ?: DEFAULT_QUANTITY

    // ---- what THIS module armed ------------------------------------------

    /**
     * The events mask this module last sent for `(player, component)` on
     * [BANK_INTERFACE] - the input [rowFor] resolves an inbound op against.
     */
    private val armedMasks: MutableMap<ContentPlayer, MutableMap<Int, Int>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    fun armedMaskFor(player: ContentPlayer, component: Int): Int? =
        synchronized(armedMasks) { armedMasks[player]?.get(component) }

    private fun recordArm(player: ContentPlayer, component: Int, mask: Int) {
        synchronized(armedMasks) { armedMasks.getOrPut(player) { HashMap() }[component] = mask }
    }

    /**
     * The 0-based MENU ROW an inbound op means on [component], for [player].
     */
    fun rowFor(player: ContentPlayer, component: Int, op: Int): Int? =
        ItemOps.rowForOp(armedMaskFor(player, component), op)

    /**
     * How many items row [row] moves for [player]: [ROW_AMOUNTS], with row 0
     * resolved through the quantity mode. Null when the row is off the end of
     * the table, [UNSUPPORTED], or a mode this repository cannot serve.
     */
    private fun amountForRow(player: ContentPlayer, row: Int?): Int? {
        val declared = row?.let { ROW_AMOUNTS.getOrNull(it) } ?: return null
        val amount = if (declared == USE_QUANTITY_MODE) quantityOf(player) else declared
        return if (amount == UNSUPPORTED || amount < 1) null else amount
    }

    /**
     * Re-sends the bank container to a player whose screen is open. Returns
     * false when there is nothing to send to.
     *
     * It reuses [Sink.updateInvFull] - the same call [openScreen] makes, the
     * same `UPDATE_INV_FULL` for inv [BANK_INV] - rather than inventing a
     * packet. There is no partial-update path here on purpose: a deposit-all
     * touches an unbounded number of bank slots and [positionalSlots] is already
     * the full picture.
     */
    fun refresh(player: ContentPlayer): Boolean {
        if (screenOpen[player] != true) return false
        val sink = sinkSupplier(player) ?: return false
        sink.updateInvFull(BANK_INV, positionalSlots(bankOf(player)))
        return true
    }

    /**
     * Moves everything in [from] into [player]'s bank.
     */
    fun depositAll(player: ContentPlayer, from: ItemContainer, source: String, component: Int): ButtonResult {
        val bank = bankOf(player)
        val ids = LinkedHashSet<Int>()
        from.toArray().forEach { item -> item?.let { ids += it.id } }
        if (ids.isEmpty()) {
            refusals++
            return ButtonResult.Refused(component, "$source is empty", "You have nothing to deposit.")
        }
        var movedIds = 0
        var movedUnits = 0L
        var leftBehind = 0
        ids.forEach { id ->
            val held = from.count(id)
            if (held <= 0L) return@forEach
            val want = held.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            when (val r = bank.deposit(id, want, from)) {
                is Bank.DepositResult.Deposited -> {
                    if (r.deposited > 0) {
                        movedIds++
                        movedUnits += r.deposited.toLong()
                    }
                    if (!r.complete) leftBehind++
                }
                // UnknownItem / NothingToDeposit / BankFull all leave the source
                // untouched by contract; the item stays where the player can see it.
                else -> leftBehind++
            }
        }
        deposits++
        logger.info {
            "bank: ${player.name} deposited $movedUnits item(s) over $movedIds id(s) from $source " +
                "(517:$component); $leftBehind id(s) would not fit and stayed put. " +
                "Bank now ${bank.usedSlots()}/${Bank.CAPACITY} slots."
        }
        return ButtonResult.Deposited(component, source, movedIds, movedUnits, leftBehind, bank.usedSlots())
    }

    /**
     * Moves [amount] out of BANK SLOT [slot] into [to]. [clientItemId] is the
     * `mid` the frame carried and is a HINT, not the key.
     */
    fun withdrawFrom(player: ContentPlayer, to: ItemContainer, slot: Int, amount: Int,
                     clientItemId: Int = NO_ITEM): ButtonResult {
        val component = ITEM_LAYER_COMPONENT
        if (amount < 1) {
            refusals++
            return ButtonResult.Refused(component, "amount $amount is not positive")
        }
        val bank = bankOf(player)
        val held = bank.itemAt(slot)
        if (held == null) {
            refusals++
            logger.info {
                "bank: ${player.name} asked to withdraw from bank slot $slot, which this server has empty " +
                    "(the frame said item $clientItemId). Nothing moved."
            }
            return ButtonResult.Refused(component, "bank slot $slot is empty on this server",
                "That item is not in your bank.")
        }
        // The hint, checked only when it is a real id. See the KDoc.
        if (clientItemId != NO_ITEM && clientItemId >= 0 && clientItemId != held.id) {
            refusals++
            logger.info {
                "bank: ${player.name} refused a withdraw - the client says bank slot $slot holds item " +
                    "$clientItemId, this server has ${held.id} there."
            }
            return ButtonResult.Refused(component,
                "stale item: client $clientItemId, server ${held.id} in slot $slot")
        }
        return when (val r = bank.withdraw(held.id, amount, to)) {
            is Bank.WithdrawResult.Withdrawn -> {
                withdrawals++
                logger.info {
                    "bank: ${player.name} withdrew ${r.withdrawn} of ${r.requested} x ${held.id} from bank " +
                        "slot $slot; ${r.stillBanked} still banked."
                }
                ButtonResult.Withdrew(held.id, slot, amount, r.withdrawn, r.stillBanked)
            }
            is Bank.WithdrawResult.TargetFull -> {
                refusals++
                ButtonResult.Refused(component, "target container is full", "Your backpack is too full.")
            }
            // NotBanked and UnknownItem cannot be reached through the itemAt
            // gate above; they are still named rather than folded into an else,
            // so a future change to Bank cannot make this branch silently wrong.
            is Bank.WithdrawResult.NotBanked -> {
                refusals++
                ButtonResult.Refused(component, "item ${held.id} is not banked", "That item is not in your bank.")
            }
            is Bank.WithdrawResult.UnknownItem -> {
                refusals++
                ButtonResult.Refused(component, "item ${held.id} has no definition row")
            }
        }
    }

    /**
     * The pre- signature, kept because callers and checks use it, and
     * because it is the honest way to say "the client named an item AND a slot".
     */
    fun withdraw(player: ContentPlayer, to: ItemContainer, itemId: Int, slot: Int, amount: Int): ButtonResult =
        withdrawFrom(player, to, slot, amount, itemId)

    /**
     * The PER-ITEM deposit: moves [amount] of whatever [from] holds in slot
     * [slot] into the bank. This is [BACKPACK_LAYER_COMPONENT]'s click, and it
     * did not exist before because the component was believed not to
     * be a backpack grid at all (see the retraction in this object's own doc).
     */
    fun depositFrom(player: ContentPlayer, from: ItemContainer, slot: Int, amount: Int,
                    clientItemId: Int = NO_ITEM): ButtonResult {
        val component = BACKPACK_LAYER_COMPONENT
        if (amount < 1) {
            refusals++
            return ButtonResult.Refused(component, "amount $amount is not positive")
        }
        if (slot < 0 || slot >= from.size) {
            refusals++
            logger.info { "bank: ${player.name} named backpack slot $slot, outside 0..${from.size - 1}." }
            return ButtonResult.Refused(component, "backpack slot $slot is outside the container")
        }
        val held = from[slot]
        if (held == null) {
            refusals++
            logger.info {
                "bank: ${player.name} asked to deposit backpack slot $slot, which this server has empty " +
                    "(the frame said item $clientItemId). Nothing moved."
            }
            return ButtonResult.Refused(component, "backpack slot $slot is empty on this server",
                "There is nothing there to deposit.")
        }
        if (clientItemId != NO_ITEM && clientItemId >= 0 && clientItemId != held.id) {
            refusals++
            logger.info {
                "bank: ${player.name} refused a deposit - the client says backpack slot $slot holds item " +
                    "$clientItemId, this server has ${held.id} there."
            }
            return ButtonResult.Refused(component,
                "stale item: client $clientItemId, server ${held.id} in slot $slot")
        }
        val bank = bankOf(player)
        return when (val r = bank.deposit(held.id, amount, from)) {
            is Bank.DepositResult.Deposited -> {
                if (r.deposited <= 0) {
                    refusals++
                    return ButtonResult.Refused(component, "nothing of ${held.id} moved")
                }
                deposits++
                logger.info {
                    "bank: ${player.name} deposited ${r.deposited} of ${r.requested} x ${held.id} from " +
                        "backpack slot $slot (517:$component); ${r.bankedTotal} banked, " +
                        "${bank.usedSlots()}/${Bank.CAPACITY} slots."
                }
                ButtonResult.DepositedOne(held.id, slot, amount, r.deposited, r.bankedTotal, bank.usedSlots())
            }
            is Bank.DepositResult.BankFull -> {
                refusals++
                logger.info { "bank: ${player.name} could not deposit ${held.id} - the bank is full." }
                ButtonResult.Refused(component, "the bank is full", "Your bank is too full to hold that.")
            }
            is Bank.DepositResult.NothingToDeposit -> {
                refusals++
                ButtonResult.Refused(component, "the backpack holds none of ${held.id}")
            }
            is Bank.DepositResult.UnknownItem -> {
                refusals++
                logger.info { "bank: ${player.name} tried to deposit item ${held.id}, which has no definition row." }
                ButtonResult.Refused(component, "item ${held.id} has no definition row",
                    "You can't put that in your bank.")
            }
        }
    }

    /**
     * Routes one IF_BUTTON click on interface [BANK_INTERFACE] onto the bank
     * model. [ButtonResult.NotOurs] means the caller must keep offering the
     * click to whatever is next; anything else means this module consumed it.
     *
     * [CLOSE_COMPONENT] is deliberately NOT handled here - [handleClose] owns it
     * and the caller asks that first, so the close path this server has already
     * exercised live is untouched by this addition.
     *
     * ## The screen must be open
     *
     * A click naming 517 from a player this server never opened 517 for is a
     * client inventing an interface, and it is refused loudly. Without that gate
     * a forged frame would be a bank with no bank booth - the request is a
     * request, exactly as `ContentRegistry.DispatchResult.Rejected` has it.
     */
    fun handleButton(player: ContentPlayer, interfaceId: Int, component: Int, op: Int,
                     itemId: Int, slot: Int, containers: Containers): ButtonResult {
        if (interfaceId != BANK_INTERFACE) return ButtonResult.NotOurs
        if (component != DEPOSIT_BACKPACK_COMPONENT && component != DEPOSIT_WORN_COMPONENT &&
            component != DEPOSIT_FAMILIAR_COMPONENT && component != DEPOSIT_COINPOUCH_COMPONENT &&
            component != ITEM_LAYER_COMPONENT && component != BACKPACK_LAYER_COMPONENT &&
            component !in quantityButtons()
        ) return ButtonResult.NotOurs

        if (!isScreenOpen(player)) {
            refusals++
            logger.warn {
                "bank: ${player.name} sent IF_BUTTON$op on $interfaceId:$component with NO bank screen open " +
                    "on this server - refused. Nothing was moved."
            }
            return ButtonResult.Refused(component, "no bank screen is open for this player")
        }

        val result = route(player, component, op, itemId, slot, containers)
        // The bank's OWN container goes back on the wire here, not in the
        // wiring, for the reason [Sink] exists at all: this object owns inv 95
        // and a headless check must be able to see the send. The caller still
        // owns the player's containers (backpack, worn) - it is the only thing
        // that knows what they are.
        if (result is ButtonResult.Deposited || result is ButtonResult.Withdrew ||
            result is ButtonResult.DepositedOne
        ) refresh(player)
        return result
    }

    private fun route(player: ContentPlayer, component: Int, op: Int,
                      itemId: Int, slot: Int, containers: Containers): ButtonResult {
        return when (component) {
            DEPOSIT_BACKPACK_COMPONENT -> {
                if (op != 1) return refusedRow(component, op)
                val backpack = containers.backpack
                    ?: return refusedContainer(component, "backpack")
                depositAll(player, backpack, "backpack", component)
            }
            DEPOSIT_WORN_COMPONENT -> {
                if (op != 1) return refusedRow(component, op)
                val worn = containers.worn
                    ?: return refusedContainer(component, "worn equipment")
                depositAll(player, worn, "worn equipment", component)
            }
            DEPOSIT_FAMILIAR_COMPONENT -> {
                refusals++
                ButtonResult.Refused(component, "no familiar system in this repository",
                    "You don't have a familiar with you.")
            }
            DEPOSIT_COINPOUCH_COMPONENT -> {
                refusals++
                ButtonResult.Refused(component, "no money pouch in this repository",
                    "You have no coins in your money pouch.")
            }
            // ---------------------------------------------------------
 // THE BANK GRID - withdraw. Rewritten: the op is
            // resolved to a MENU ROW through the mask this module armed
            // ([rowFor]) and the row to an AMOUNT through [ROW_AMOUNTS],
            // instead of the old "op 1 means one, everything else is refused".
            // Every number in that chain is measured; see [REFERENCE_517].
            // ---------------------------------------------------------
            ITEM_LAYER_COMPONENT -> {
                val backpack = containers.backpack
                    ?: return refusedContainer(component, "backpack")
                val amount = amountForRow(player, rowFor(player, component, op))
                    ?: return refusedRow(component, op)
                withdrawFrom(player, backpack, slot, amount, itemId)
            }

            // The bank's own BACKPACK grid - the per-item deposit.
            BACKPACK_LAYER_COMPONENT -> {
                val backpack = containers.backpack
                    ?: return refusedContainer(component, "backpack")
                val amount = amountForRow(player, rowFor(player, component, op))
                    ?: return refusedRow(component, op)
                depositFrom(player, backpack, slot, amount, itemId)
            }

            // A quantity button. Its cache optmask is 2, so op 1 is the only row
            // it can ever send; anything else on it is forged or a client this
            // project has not measured, and is refused.
            else -> {
                val amount = quantityButtons()[component] ?: return ButtonResult.NotOurs
                if (op != 1) return refusedRow(component, op)
                if (amount == UNSUPPORTED) {
                    refusals++
                    logger.info {
                        "bank: ${player.name} pressed the custom-quantity button 517:$component; this repository " +
                            "has no numeric-input path, so the mode is unchanged."
                    }
                    return ButtonResult.Refused(component, "the custom (X) quantity needs an input this repository lacks",
                        "Choosing your own number isn't available on this server yet.")
                }
                val was = quantityOf(player)
                quantityMode[player] = amount
                logger.info {
                    "bank: ${player.name} set the bank move quantity to " +
                        (if (amount == ALL) "ALL" else "$amount") + " (517:$component), was " +
                        (if (was == ALL) "ALL" else "$was") + "."
                }
                ButtonResult.QuantityChanged(component, was, amount)
            }
        }
    }

    private fun refusedRow(component: Int, op: Int): ButtonResult {
        refusals++
        logger.info {
            "bank: IF_BUTTON$op on $BANK_INTERFACE:$component is a menu row this server has no " +
                "measurement for - refused. Nothing was moved. (The armed mask enables ops " +
                "${maskOpsOf(component)}; the rows this module serves are ${ROW_AMOUNTS.size}, and " +
                "op 7 on a grid is the one observed row deliberately left unmapped - see ROW_AMOUNTS.)"
        }
        return ButtonResult.Refused(component, "menu row for op $op is not measured for this component")
    }

    /** For the refusal log: which ops the constant mask for [component] enables. */
    private fun maskOpsOf(component: Int): List<Int> = when (component) {
        ITEM_LAYER_COMPONENT -> ItemOps.enabledOps(ITEM_LAYER_MASK)
        BACKPACK_LAYER_COMPONENT -> ItemOps.enabledOps(BACKPACK_LAYER_MASK)
        else -> ItemOps.enabledOps(OP1_MASK)
    }

    private fun refusedContainer(component: Int, what: String): ButtonResult {
        refusals++
        logger.info { "bank: 517:$component needs the player's $what and none was supplied - refused." }
        return ButtonResult.Refused(component, "no $what container was supplied")
    }

    // ---- registration ---------------------------------------------------

    /**
     * The bank-named locs that declare "Use", straight out of the database at
     * registration time. The name scope is the authored part (see the class
     * comment); everything the query returns is re-validated by
     * [ContentRegistry.onLoc] against the merged definition, so a row this query
     * selected wrongly would refuse to bind rather than bind wrongly.
     */
    fun useDeclaringBankLocs(): List<Int> = RsDatabase.queryAll(
        "SELECT l.id FROM locs l WHERE (l.name LIKE '%ank booth%' OR l.name LIKE '%ank chest%') " +
            "AND (l.actions_0 = 'Use' OR EXISTS (SELECT 1 FROM locs_attr a WHERE a.id = l.id " +
            "AND a.field LIKE 'actions_%' AND a.value = '\"Use\"')) ORDER BY l.id"
    ) { it.getInt("id") }

    var npcBound: Int = -1
        private set

    /**
     * Binds "Bank" as a category on locs AND on npcs, and "Use" per-id on the
     * bank-named locs. Returns (locs bound to Bank, loc ids bound to Use) - the
     * npc count is [npcBound].
     *
     * The npc binding is NOT gated on [uiEnabled]. Whether a banker produces a
     * [BankEvent] is a fact about this server's model, and it was wrong before
     * this pass regardless of whether anything is drawn; only [openScreen] is an
     * experiment, and it gates itself.
     */
    fun install(): Pair<Int, Int> {
        val bank = ContentRegistry.onLocAction(BANK, ::onBank)
        val useIds = useDeclaringBankLocs()
        useIds.forEach { ContentRegistry.onLoc(it, USE, ::onBank) }
        npcBound = ContentRegistry.onNpcAction(BANK, ::onBankNpc)
        logger.info {
            "banks: bound Bank across $bank locs and $npcBound npcs, Use on ${useIds.size} bank-named " +
                "loc ids (banks live in memory; their CONTENTS persist - restored at login by " +
                "restoreBank, recorded on leave and by the world autosave). Bank SCREEN is " +
                (if (uiEnabled) "ENABLED: $PROVENANCE_SHORT"
                 else "OFF (-Dopennxt.experiment.banks.ui=true to open interface $BANK_INTERFACE " +
                     "at $GAMEFRAME:$BANK_MOUNT)")
        }
        // Said out loud at boot because the npc binding above is worth exactly
 // nothing without it, and until it was worth exactly nothing.
        // See NPC_DISPATCH_SWITCH.
        logger.info {
            if (npcClickReaches)
                "banks: NPC CLICK ROUTING IS ON (-D$NPC_DISPATCH_SWITCH=true) - a click on any of the " +
                    "$npcBound 'Bank' npcs now reaches Banks.onBankNpc through OpNpcHandler.dispatchOther"
            else
                "banks: npc click routing is OFF - the $npcBound 'Bank' npc bindings above are " +
                    "REGISTERED BUT UNREACHABLE from a real click (OpNpcHandler drops every option that " +
                    "is neither 'Talk to' nor 'Attack'). -D$NPC_DISPATCH_SWITCH=true routes them."
        }
        return bank to useIds.size
    }
}
