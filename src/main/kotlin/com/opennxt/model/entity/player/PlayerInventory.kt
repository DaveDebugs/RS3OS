package com.opennxt.model.entity.player

import com.opennxt.OpenNXT
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.InvEntry
import com.opennxt.net.game.serverprot.UpdateInvFull
import com.opennxt.net.game.serverprot.UpdateInvPartial
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

/**
 * The player's backpack: which container it is, which inventory id it goes out
 * under, and the one call that puts it on the wire.
 */
object PlayerInventory {

    private val logger = KotlinLogging.logger { }

    /**
     * Master switch. With this off, [sendBackpack] returns before it looks at
     * the protocol table, builds a container, or touches the channel - the
     * session is byte-for-byte the world that shipped before this file existed.
     */
    val enabled: Boolean = System.getProperty("opennxt.experiment.inventory") != "false"

    /**
     * The backpack's inventory id. See the class doc for the cache evidence;
     */
    val backpackInv: Int = System.getProperty("opennxt.inventory.backpackInv")?.toIntOrNull() ?: 93

    /**
     * Whether a brand-new, empty backpack gets the starter kit below.
     * `-Dopennxt.experiment.inventory.starter=false` sends an EMPTY but validly
     * framed UPDATE_INV_FULL instead, which is still a complete test of the
     * packet - the client's store goes from "no record at all" to "a 28-slot
     * record of empties", and the panel's padlock reads the record, not its
     * contents.
     */
    val starterEnabled: Boolean = System.getProperty("opennxt.experiment.inventory.starter") != "false"

    /**
     * What a brand-new character finds in the backpack: nothing.
     *
     * The starting TOOLS used to live here - coins, a bronze pickaxe, a bronze
     * hatchet and a tinderbox. They now start on the tool belt instead
     * ([com.opennxt.model.account.PlayerSave.STARTING_TOOL_BELT]), which is
     * where the game keeps them, so a new player arrives with an empty backpack
     * and can still chop, mine, light a fire, smith, fletch and fish.
     *
     * The seeding machinery below is kept: put items back in this list to hand
     * every new character a kit again. It only ever fires when the backpack,
     * bank and worn slots are ALL empty.
     */
    val starterKit: List<Item> = emptyList()

    // =========================================================================
 // WORN EQUIPMENT
    // =========================================================================
    //
    // The gap this fills, stated as it was measured: `WorldPlayer` had exactly
    // ONE container, this file's backpack (inv 93), so the whole player-side
    // combat model - `PlayerCombatStats`, `EquippedWeapon`, item params 3267 /
    // 641 / 749 / 750 / 14 - had nothing to read a weapon FROM. It was reachable
    // only from check tools that hand-built a `HeadlessPlayer` and called
    // `equip(id)` on it. The live socket path could not equip anything at all.

    /**
     * The worn-equipment inventory id.
     */
    val wornInv: Int = System.getProperty("opennxt.inventory.wornInv")?.toIntOrNull() ?: 94

    /**
     * The largest `items.equipSlotId` in the cache: **18** (wings). Measured
     * over all 20,219 items that carry the column; see [wornInv].
     */
    const val MAX_EQUIP_SLOT = 18

    /**
     * The worn container's slot count: [MAX_EQUIP_SLOT] + 1 = **19**.
     */
    const val WORN_SIZE = MAX_EQUIP_SLOT + 1

    /**
     * The main-hand slot: `equipSlotId` **3**.
     */
    const val WEAPON_SLOT = 3

    val equipEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.equip") == "true"

    /**
     * Live worn containers, keyed weakly by player - same shape and same reason
     * as [backpacks]. [com.opennxt.model.world.WorldPlayer.worn] is the front
     * door; this map exists so a logout cannot leak one.
     *
     * NOT PERSISTED, and that is a stated gap rather than an oversight:
     */
    private val wornContainers: MutableMap<WorldPlayer, ItemContainer> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, ItemContainer>())

    /**
     * The player's live worn container, RESTORED FROM THE SAVE the first time it is asked for -
     * the same shape as [backpackOf], and the reason PlayerSave gained a `worn` field on
     *. Before that this returned an empty container every login, so a relog stripped
     * whatever the player was wearing.
     */
    fun wornOf(player: WorldPlayer): ItemContainer =
        wornContainers.getOrPut(player) { player.save.restoreWorn() }

    /** Drops a player's cached worn container. Only tools need this. */
    fun forgetWorn(player: WorldPlayer) {
        wornContainers.remove(player)
    }

    /**
     * The worn container as an `UPDATE_INV_FULL` for [wornInv]. Positional and
     * full-length, for the same reason [fullPacketFor] is.
     */
    fun wornPacketFor(container: ItemContainer): UpdateInvFull = fullPacketFor(container, wornInv)

    /**
     * Puts the worn container on the wire.
     *
     * Refuses - silently, and this is the point - unless BOTH
     * [PlayerInventory.enabled] and [equipEnabled] are on: the first is the
     * container family's existing kill switch, the second is this feature's
     * own. Degrades once with a warning when the build has no UPDATE_INV_FULL
     * opcode, exactly as [sendBackpack] does.
     */
    fun sendWorn(player: WorldPlayer) {
        if (!enabled || !equipEnabled) return

        // Contained, and NOT the same shape as [sendBackpack]'s lookup, for a
        // reason that was found by running it rather than by reasoning about it:
        // sendBackpack is only ever called from the login path, where
        // `OpenNXT.protocol` is loaded by definition. THIS is called from a
        // packet handler that an operator can reach at any moment, including
        // from a tool that never loaded a protocol table - and `protocol` is a
        // `lateinit`, so touching it there throws
        // UninitializedPropertyAccessException out of the middle of an equip.
        // An equip must never take a session down, so the whole lookup is
        // guarded and a failure degrades to "no packet", warned once.
        val mapped = try {
            OpenNXT.protocol.serverProtNames.values["UPDATE_INV_FULL"] != null
        } catch (t: Throwable) {
            false
        }
        if (!mapped) {
            if (!wornUnmappedWarned) {
                wornUnmappedWarned = true
                logger.warn {
                    "No usable UPDATE_INV_FULL opcode (unmapped, or the protocol table is not loaded in " +
                        "this process) - worn equipment cannot be shown. The container still changed. " +
                        "(Warned once.)"
                }
            }
            return
        }

        val container = wornOf(player)
        if (wornSends < 3) {
            wornSends++
            logger.info {
                "UPDATE_INV_FULL (worn) send #$wornSends: inv $wornInv, ${container.size} slot(s), " +
                    "${container.usedSlots()} occupied, for ${player.name}. $WORN_PROVENANCE"
            }
        }
        player.client.write(wornPacketFor(container))
    }

    /**
     * The worn container's provenance as a runtime string, so a log line cannot
     * claim more than the cache supports. Asserted by
     * .
     */
    val WORN_PROVENANCE: String =
        "worn equipment: (not). items.equipSlotId spans 0.$MAX_EQUIP_SLOT over " +
            "14 distinct values on 20219 of 63331 items, so a worn container is $WORN_SIZE slots wide; inv " +
            "$wornInv's cache-declared size (js5[2,5:$wornInv] opcode 2) is $WORN_SIZE, and 24 of the 149 " +
            "components in the cache with any inventory listener name $wornInv, including 9 on the " +
            "'Combat Lvl'/'Auto Retaliate' panel 1503 and 5 on 549, whose sibling text is 'You cannot " +
            "change equipped items at this photobooth'. CONTROL (fired): of the 6382 model-rendering " +
            "components only 6 listen to any inv and 4 of those 6 name $wornInv. AUTHORED: nothing about " +
            "the id. Not established: any client-side read of it - nothing confirms it, and " +
            "no live 949 client has been shown an inv-$wornInv packet. WHAT WOULD SETTLE IT: equip " +
            "something with -Dopennxt.experiment.equip=true against a real client and look at the " +
            "equipment panel."

    @Volatile
    private var wornUnmappedWarned = false

    @Volatile
    private var wornSends = 0

    /**
     * Live backpacks, keyed weakly by player so a logout cannot leak one.
     *
     * This lives here rather than on [WorldPlayer] because that class's own
     * comment records that no world-layer code writes a player's backpack yet,
     * and adding a field to it is outside what this change is allowed to touch.
     * Same shape as [com.opennxt.model.entity.updating.NpcInfoEncoder]'s
     * per-player state.
     */
    private val backpacks: MutableMap<WorldPlayer, ItemContainer> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, ItemContainer>())

    /**
     * The player's live backpack, restored from their save the first time it is
     * asked for.
     *
     * The starter kit is only ever placed into a backpack that came back
     * COMPLETELY EMPTY. A save that already holds items is left exactly as it
     * is: seeding over one would be inventing state on top of real data, and
     * [WorldPlayer.toSave] copies the saved backpack forward untouched, so
     * nothing this object does can delete anything.
     */
    fun backpackOf(player: WorldPlayer): ItemContainer = backpacks.getOrPut(player) {
        val restored = player.save.restoreBackpack()
 // (audit E-09): "the backpack came back empty" is not "a fresh account" once a
        // bank or worn slot holds anything - re-seeding on every empty backpack would mint a kit
        // per relog the day a drop or deposit path lands. All three containers must be empty.
        if (starterEnabled && starterKit.isNotEmpty() &&
            restored.usedSlots() == 0 && player.save.bank.isEmpty() && player.save.worn.isEmpty()) {
            starterKit.forEachIndexed { slot, item -> restored[slot] = item }
            logger.info { "starter kit seeded for ${player.name}: backpack, bank and worn were all empty" }
        }
        restored
    }

    /** Drops a player's cached backpack. Only tools need this. */
    fun forget(player: WorldPlayer) {
        backpacks.remove(player)
    }

    /**
     * The container as an `UPDATE_INV_FULL` for [backpackInv].
     *
     * Positional and FULL-LENGTH: `slots.size` is the container's size, not its
     * occupancy, because the client drives its loop off that count and can only
     * address `0..count-1`. Sending only the occupied slots would leave the
     * rest of the container holding whatever it held before - which for a fresh
     * client is nothing, but for a re-send would be stale items.
     */
    fun fullPacketFor(container: ItemContainer, inv: Int = backpackInv): UpdateInvFull {
        // every UPDATE_INV_FULL declares `slots` = (last occupied index + 1), not
        // the container's width: backpack 93 went out as 28 slots on the account
        // whose slot 27 was full and as 2 on the account with two items; worn 94
        // as 18 (slot 17 occupied) and as 4; 891 as 0. Nine containers, two
 // accounts, no exception. Whether the client CLEARS the
        // untransmitted tail on a full update (the KDoc above assumes it does
        // not) is exactly what the paragraph above worried about, and it is
        // decided by a login, not here: the rule is behind
        // -Dopennxt.experiment.inv.trimFull=true, default OFF, read at call time
        // so a check can flip it. Off, the packet is byte-identical to before.
        val width = if (trimFullEnabled()) container.lastOccupiedSlot() + 1 else container.size
        return UpdateInvFull(
            inv = inv,
            slots = (0 until width).map { slot ->
                container[slot]?.let { InvEntry(it.id, it.amount) }
            },
            flags = 0
        )
    }

    /** See [fullPacketFor]. Read per call, not cached, so tools can toggle it. */
    fun trimFullEnabled(): Boolean = System.getProperty("opennxt.experiment.inv.trimFull") == "true"

    private fun ItemContainer.lastOccupiedSlot(): Int {
        var last = -1
        for (slot in 0 until size) if (this[slot] != null) last = slot
        return last
    }

    /**
     * `-Dopennxt.experiment.ui.loginInvs=true` (default OFF). The containers
     * the reference client declares at every login that this server never did, sent as
     * sessions, two accounts:
     */
    // server-20260905-170912, the reference client's default HUD on screen). `=false` turns it off.
    fun loginInvsEnabled(): Boolean = System.getProperty("opennxt.experiment.ui.loginInvs") != "false"

    /** inv 895 as the reference client sends it, count 0 each, same ids on both recorded accounts. */
    val LOGIN_895_IDS: List<Int> = listOf(960, 8778, 8780, 8782, 54860, 54862, 54864, 54866, 54868, 54870, 63190)

    fun sendLoginContainers(player: WorldPlayer) {
        if (!enabled || !loginInvsEnabled()) return
        if (OpenNXT.protocol.serverProtNames.values["UPDATE_INV_FULL"] == null) return
        val worn = wornOf(player)
        player.client.write(UpdateInvFull(inv = 891, slots = emptyList(), flags = 0))
        player.client.write(wornPacketFor(worn))
        player.client.write(UpdateInvFull(inv = 895, slots = LOGIN_895_IDS.map { InvEntry(it, 0) }, flags = 0))
        for (id in 2254..2275) player.client.write(ClientSetvarcstrSmall(id, ""))
        logger.info {
            "ui.loginInvs: sent inv 891 (empty), worn ${wornInv} (${worn.usedSlots()} occupied), " +
                "inv 895 (${LOGIN_895_IDS.size} zero-count ids) and 22 empty varc strings 2254..2275 for ${player.name}"
        }
    }

    /**
     * Changed slots only, as an `UPDATE_INV_PARTIAL`.
     */
    fun partialPacketFor(container: ItemContainer, slots: Iterable<Int>, inv: Int = backpackInv): UpdateInvPartial =
        UpdateInvPartial(
            inv = inv,
            entries = slots.map { slot -> slot to container[slot]?.let { InvEntry(it.id, it.amount) } },
            flags = 0
        )

    /**
     * The ONE call a caller needs. Does nothing at all when the experiment is
     * off, and degrades - once, with a warning - when this build's protocol
     * table has no UPDATE_INV_FULL opcode, the same shape WorldPlayer already
     * uses for PLAYER_INFO. An unmapped packet must never take a login down.
     */
    fun sendBackpack(player: WorldPlayer) {
        if (!enabled) return

        if (OpenNXT.protocol.serverProtNames.values["UPDATE_INV_FULL"] == null) {
            if (!unmappedWarned) {
                unmappedWarned = true
                logger.warn {
                    "Build ${OpenNXT.protocol.effectiveBuild} has no opcode for UPDATE_INV_FULL - the backpack " +
                        "will stay empty. Recover it into data/prot/<build>/serverProtNames.toml. (Warned once.)"
                }
            }
            return
        }

        val container = try {
            backpackOf(player)
        } catch (t: Throwable) {
            if (!failureWarned) {
                failureWarned = true
                logger.error(t) {
                    "Could not build ${player.name}'s backpack; no inventory will be sent this session. " +
                        "Run with -Dopennxt.experiment.inventory=false to silence. (Warned once.)"
                }
            }
            return
        }

        if (sends < 3) {
            sends++
            logger.info {
                "UPDATE_INV_FULL send #$sends: inv $backpackInv, ${container.size} slot(s), " +
                    "${container.usedSlots()} occupied, for ${player.name}"
            }
        }

        player.client.write(fullPacketFor(container))
    }

    @Volatile
    private var unmappedWarned = false

    @Volatile
    private var failureWarned = false

    @Volatile
    private var sends = 0
}
