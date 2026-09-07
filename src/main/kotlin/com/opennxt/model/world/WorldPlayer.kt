package com.opennxt.model.world

import com.opennxt.OpenNXT
import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.StatContainer
import com.opennxt.content.ContentPlayer
import com.opennxt.content.impl.Banks
import com.opennxt.content.interfaces.InterfaceSlot
import com.opennxt.model.map.LocClipping
import com.opennxt.impl.stat.PlayerStatContainer
import com.opennxt.model.account.AccountStore
import com.opennxt.model.account.PlayerSave
import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.entity.movement.CompassPoint
import com.opennxt.model.entity.player.InterfaceManager
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.player.Viewport
import com.opennxt.model.entity.updating.NpcInfoEncoder
import com.opennxt.model.entity.updating.PlayerInfoEncoder
import com.opennxt.model.combat.Lifepoints
import com.opennxt.model.lobby.DefaultVariables
import com.opennxt.net.ConnectedClient
import com.opennxt.net.GenericResponse
import com.opennxt.net.Side
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.PacketRegistry
import com.opennxt.net.game.pipeline.*
import com.opennxt.net.game.serverprot.RebuildNormal
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.ServerTickEnd
import com.opennxt.net.game.serverprot.variables.ClientSetvarcLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ResetClientVarcache
import com.opennxt.net.login.LoginPacket
import com.opennxt.net.login.LoginServerHandler
import com.opennxt.net.proxy.UnidentifiedPacket
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import mu.KotlinLogging
import kotlin.reflect.KClass
import com.opennxt.net.game.serverprot.variables.VarpLarge

/**
 * @param initialXp per-stat xp from the account's
 *   [com.opennxt.model.account.PlayerSave] (levels derived through the
 *   verified curve by [PlayerStatContainer]); null keeps the legacy
 *   hardcoded fresh-account stats.
 * @param loadedSave the account's persisted state, when the caller already has
 *   it - [com.opennxt.net.login.LoginServerHandler] does, since it builds the
 *   [PlayerEntity] from the save's position. Omitted, it is loaded here from
 *   [AccountStore.instance] exactly the way
 *   [com.opennxt.model.lobby.LobbyPlayer] loads its own, so a WorldPlayer
 *   always has the save [toSave] copies forward. Passing it explicitly is also
 *   what lets a check drive this class against a scratch database instead of
 *   the live one.
 */
class WorldPlayer(
    client: ConnectedClient,
    name: String,
    val entity: PlayerEntity,
    initialXp: Map<Stat, Double>? = null,
    loadedSave: PlayerSave? = null
) : BasePlayer(client, name), CombatDefender {

    /**
     * The persisted state this player logged in with. [toSave] copies it
     * forward with the fields the world actually changes refreshed; the rest
     * rides along untouched, because inventing empty values for state the world
     * layer does not own yet would DELETE what a save already carries.
     */
    val save: PlayerSave = loadedSave ?: AccountStore.instance.loadSave(name) ?: PlayerSave.fromNew(name)

    /**
     * Varps this player has changed and that must outlive the session.
     *
     * [PlayerSave.varps] has existed since the save format was written, with a KDoc reading
     * "Empty for now; the persistence seam exists" - and it stayed empty, because nothing ever
     * wrote to it and [toSave] never passed it. Every varp this server sent was therefore
     * session-scoped no matter what it meant. That is what made the Additional Action Bars
     * un-saveable: the toggle is varp 10092..10095 and a varp could not be saved.
     *
     * Seeded from the loaded save so a value written last session is the value this session
     * starts from. Only varps written through [setVarpOverride] land here - the bulk login varps
     * from `data/config/login-varps.tsv` are a static table and do not belong in a per-character
     * save.
     *
     * ## Threading: confined to the tick thread, and deliberately unsynchronised
     *
     * A plain [LinkedHashMap] in a networked server invites someone to wrap it in a lock. Do not.
     * The confinement is STRUCTURAL, and the chain is worth stating because an earlier draft of
     * this paragraph argued it from the `[tick-engine]` prefix in the log - which is a symptom,
     * not a mechanism, and would not have survived someone adding a second executor:
     *
     *   1. Netty's event loop only ever calls `ConnectedClient.incomingQueue.add(...)`. That
     *      queue is a [java.util.concurrent.ConcurrentLinkedQueue] and is the whole handoff -
     *      no handler runs on an event-loop thread.
     *   2. [handleIncomingPackets] drains it and invokes the handlers. Its only non-test callers
     *      are `World.tick` and `Lobby.tick`.
     *   3. `TickEngine`'s executor is `newScheduledThreadPool(1)`, pinned to one, with a comment
     *      saying a tick engine wants serial execution because world and lobby ticks touch the
     *      same players.
     *
     * One thread, always the same one, so there is no mutual-exclusion problem AND no visibility
     * problem between ticks. Reads via [toSave] come from `World.cullDisconnected` and
     * `World.autosave`, both inside that same tick.
     *
     * What WOULD break it: raising the TickEngine pool above 1, calling [setVarpOverride] from a
     * Netty handler, or moving autosave onto its own scheduler. Any of those makes this map need
     * a real synchronisation story, and this list is how someone will know.
     */
    private val varpOverrides: LinkedHashMap<Int, Int> = LinkedHashMap(save.varps)

    /**
     * Cosmetic overrides, equipment slot -> item id. Read by
     * [com.opennxt.model.entity.player.appearance.PlayerModel.appendAppearance] in place of the worn
     * item of that slot; written by `::cosmetic`. Persisted by [toSave].
     */
    private val cosmetics: LinkedHashMap<Int, Int> = LinkedHashMap(save.cosmetics)
    fun cosmeticFor(slot: Int): Int? = cosmetics[slot]
    fun cosmeticsSnapshot(): Map<Int, Int> = cosmetics.toMap()
    /** Sets (or with null clears) the override for [slot] and re-renders the appearance. Returns the previous item. */
    fun setCosmetic(slot: Int, itemId: Int?): Int? {
        val previous = if (itemId == null) cosmetics.remove(slot) else cosmetics.put(slot, itemId)
        entity.model.dirty = true
        return previous
    }
    fun clearCosmetics(): Int { val n = cosmetics.size; cosmetics.clear(); entity.model.dirty = true; return n }

    /**
     * THE TOOL BELT'S PER-PLAYER CONTENTS.
     */
    private val toolbelt: LinkedHashSet<Int> = LinkedHashSet(save.toolbelt)

    /** What this player has ADDED to the belt. The base tier is not in here - see the field. */
    fun toolbeltIds(): Set<Int> = toolbelt.toSet()

    /** True when this player has belted [itemId]. Does NOT consider the base tier. */
    fun toolbeltHolds(itemId: Int): Boolean = itemId in toolbelt

    /** Stores [itemId] on the belt. False when it was already there - the caller must not consume. */
    fun addToToolbelt(itemId: Int): Boolean = toolbelt.add(itemId)

    /** The player's present value of a varp: the override if one was ever set, else the login table's, else 0. */
    fun varpValue(id: Int): Int =
        varpOverrides[id] ?: com.opennxt.content.impl.LoginVarps.table.firstOrNull { it.first == id }?.second ?: 0

    /** The stored value of [id], or null if this player has never set it. */
    fun varpOverride(id: Int): Int? = varpOverrides[id]

    /** Tick number of the last accepted panel toggle, for [allowPanelToggleThisTick]. */
    private var lastPanelToggleTick: Long = -1

    /**
     * True at most once per tick, for [com.opennxt.content.impl.PanelToggles].
     *
     * A single Additional Action Bar toggle costs the server up to 179 outbound packets against a
     * 9-byte inbound one - the arming block is 64 common rows plus 28 for every enabled bar - and
     * nothing else throttles IF_BUTTON1. That is a ~179x fan-out a client can drive for free.
     *
     * These are clicks on a checkbox, so one per 600 ms tick is far above any real rate. The
     * caller DROPS the surplus rather than disconnecting: a double-click from an honest client is
     * the likeliest cause and losing the second one costs nothing.
     *
     * Tick-confined like [varpOverrides], so the read-modify-write needs no lock.
     *
     * IT READS ONE BEHIND, AND THAT IS FINE. `World.tick` increments `tickCount` at the END of
     * the tick, after the handleIncomingPackets phase, so during handling `currentTick` still
     * names the PREVIOUS tick. The throttle needs only that the value is stable within a tick and
     * different between ticks, and both hold. Written down because the number not matching the
     * tick being processed looks like an off-by-one and is not one - "fixing" it by moving the
     * increment would reorder the autosave phase, which keys off the same counter.
     */
    fun allowPanelToggleThisTick(): Boolean {
        val now = OpenNXT.world.currentTick
        if (now == lastPanelToggleTick) return false
        lastPanelToggleTick = now
        return true
    }

    /**
     * Send a varp AND remember it, so it survives a logout.
     *
     * [store] = false sends without recording, which is what the login replay wants: it is
     * transmitting what the save already holds, and re-storing it would be a no-op that muddies
     * the "has this player ever set it" question above.
     */
    fun setVarpOverride(id: Int, value: Int, store: Boolean = true) {
        if (store) varpOverrides[id] = value
        if (!client.channel.isActive) return
        if (!DefaultVariables.varpIsDefined(id)) {
            logger.warn("setVarpOverride: varp $id is not defined by this cache - stored but NOT " +
                "sent, because an undefined id trips the client's own kill check.")
            return
        }
        client.write(VarpLarge(id, value))
    }

    init {
        entity.controllingPlayer = this
        // Saved bank -> live bank, at construction, before a bank booth or a
        // leave-save can touch it. Same reasoning as LobbyPlayer's init:
        // toSave sessions the LIVE bank, so a player who was never restored
        // would store an empty bank over their real one on the way out.
        // [Banks.restoreBank] owns the relogin ordering rule.
        Banks.restoreBank(name, save.bankItems())

 // THE RUN RESERVE. Restored here, at construction, for the same reason the
        // bank is: [toSave] sessions the LIVE reserve, so a player who was never restored would
        // store a full bar over the empty one they logged out with - a free run for anyone who can
        // press the login button. Absent (`run == null`) means the defaults, which is what
        // RunEnergy already holds; there is nothing to do and nothing to log.
        save.run?.let { entity.runEnergy.restore(it.tenths, it.toggled) }
    }

    /**
     * The state content handlers are shown, synced from [entity] on the way in.
     *
     * [com.opennxt.content.ContentContext]'s doc anticipated this exactly: "when
     * the packet layer grows an OpLoc handler it will construct one of these from
     * the WorldPlayer rather than this being replaced." It is one per player and
     * not one per dispatch, so a handler that stores something on it - a dialogue
     * step, a partially built object - still finds it on the next click.
     *
     * Only the location is synced. Inventory, equipment and vars are the ones
     * WorldPlayer owns elsewhere and copying them here would create a second
     * truth; a handler that needs them should be given them explicitly rather
     * than reading a stale copy.
     */
    val contentPlayer: ContentPlayer by lazy {
        ContentPlayer(name, TileLocation(entity.location.x, entity.location.y, entity.location.plane))
    }

    /** [contentPlayer] with its tile brought up to date. Call this, not the field. */
    fun contentPlayerAt(): ContentPlayer {
        val l = entity.location
        contentPlayer.location = TileLocation(l.x, l.y, l.plane)
        return contentPlayer
    }

    /**
     * True when [save] carried a smithing project that `Smithing.restoreProject` REFUSED (its
     * product id is not in this build's recipe table). While it is set, [toSave] copies the stored
     * project forward untouched instead of overwriting it with the live null.
     *
     * Without this the refusal would DESTROY the save: restore says no, the live map stays empty,
     * and the very next autosave writes `smithing = null` over the row - so a database change that
     * temporarily hid a product would permanently orphan the player's 47068. The refusal is meant
     * to be survivable; a build that gets the product back must find the project still there.
     */
    private var smithingRestoreRefused = false

    /** Warn-once latch for the loc-clipping load in [tick]. */
    private var locClippingWarned = false

    private val handlers =
        Object2ObjectOpenHashMap<KClass<out GamePacket>, GamePacketHandler<in BasePlayer, out GamePacket>>()
    private val logger = KotlinLogging.logger { }

    /**
     * THE UNFINISHED ANVIL PROJECT, restored at construction.
     */
    init {
        val stored = save.smithing
        if (stored != null) {
            val ok = runCatching {
                com.opennxt.content.impl.Smithing.restoreProject(
                    contentPlayer,
                    stored.productId, stored.progress, stored.xpPaidTenths, stored.heat, stored.stage
                )
            }.onFailure {
                logger.warn(it) { "could not restore $name's smithing project (product ${stored.productId})" }
            }.getOrDefault(false)
            smithingRestoreRefused = !ok
        }
    }

    val viewport = Viewport(this)
    override val interfaces: InterfaceManager = InterfaceManager(this)
    override val stats: StatContainer = PlayerStatContainer(this, initialXp)

    // =====================================================================
    // LIFEPOINTS
    // =====================================================================
    //
    // THE GAP THIS FILLS, stated as it was measured: until now this class had
    // NO lifepoint field of any kind. [com.opennxt.model.combat.PlayerCombat]'s
    // own note said so ("[WorldPlayer] has NO lifepoint field of any kind"), and
    // the consequence was that a live player could not be hurt, could not die
    // and therefore could not lose a fight: `NpcRetaliation` existed, was
    // verified, and could only ever fight a [HeadlessPlayer] in a check.
    //
    // NOTHING NEW IS INVENTED HERE. Both the maximum and the death behaviour are
    // the ones [HeadlessPlayer] already carries, reached through the same
    // constants, so this server has ONE reconstruction of player health with two
    // readers rather than two reconstructions that can disagree - which is
    // exactly the failure PlayerCombat's own xp-rate retraction was.
    //
    // WHAT THE CLIENT IS TOLD: NOTHING. There is no lifepoint packet here, and
 // that is deliberate and unresolved rather than forgotten - see
    // [lifepointsUiStatus].

    /**
     * Maximum lifepoints: Constitution level x
     * [com.opennxt.model.combat.Lifepoints.PER_CONSTITUTION_LEVEL].
     *
     * RECONSTRUCTED, and the citation lives at that constant (public RS3
     * knowledge; measured NOT to be in this cache - no column, param or enum in
     * 32,687 npc rows or the item params multiplies a Constitution level into a
     * lifepoint total). Identical expression to
     * [HeadlessPlayer.maxLifepoints], on purpose.
     *
     * A derived getter, not a stored number: the level comes from the live stat
     * container, so a Constitution level-up raises the maximum with no event
     * needed. It does NOT heal - see [currentLifepoints].
     */
    override val maxLifepoints: Int
        get() = com.opennxt.model.combat.Lifepoints.forConstitutionLevel(stats.getLevel(Stat.CONSTITUTION))

    /**
     * Current lifepoints, seeded FULL at login.
     *
     * Full-at-login is a choice and it is ours: [PlayerSave] carries no
     * lifepoint column, so there is nothing persisted to restore and the only
     * alternatives are "full" or an invented fraction. RS3 does not persist
     * damage across a logout either (lifepoints regenerate), so full is the
     * reading that invents least. Nothing writes this to a save; a relog heals.
     */
    override var currentLifepoints: Int = 0
        private set

    init {
        // After `stats` is constructed, so the Constitution level is the save's.
        currentLifepoints = maxLifepoints
    }

    /**
     * Applies [amount] lifepoints of damage. Returns null while the player
     * survives, or the typed [PlayerDeath] at 0.
     *
     * Deliberately the same shape and the same consequences as
     * [HeadlessPlayer.takeDamage]: respawn at the Lumbridge tile
     * ([HeadlessPlayer.LUMBRIDGE_RESPAWN_X], RECONSTRUCTED, and already the tile
     * this codebase logs fresh players in on), lifepoints restored to
     * [maxLifepoints], and EVERY skill and xp total untouched, because RS3 death
     * does not drain stats.
     *
     * The one difference from the headless version is the one that makes it a
     * world player: the respawn is a [com.opennxt.model.entity.movement.Movement.teleport],
     * so the move goes out on PLAYER_INFO as a teleport record on the next tick
     * rather than being a field write nobody can see. Queued movement is dropped
     * by `teleport()` itself.
     *
     * Callers are responsible for ending a fight - [com.opennxt.model.combat.PlayerCombat]
     * disengages on death - because this class knows nothing about combat.
     */
    override fun takeDamage(amount: Int): PlayerDeath? {
        if (amount <= 0) return null
        currentLifepoints = (currentLifepoints - amount).coerceAtLeast(0)
        // The client is told on BOTH paths below, not just this one: a death
        // respawns at full, and a bar left showing the pre-death value is the
        // same defect as a bar left showing 0 - it just looks less obviously
        // wrong. See [sendLifepoints].
        sendLifepoints()
        if (currentLifepoints > 0) return null

        val loc = entity.location
        val diedAt = TileLocation(loc.x, loc.y, loc.plane)
        val respawn = TileLocation(
            HeadlessPlayer.LUMBRIDGE_RESPAWN_X,
            HeadlessPlayer.LUMBRIDGE_RESPAWN_Y,
            HeadlessPlayer.LUMBRIDGE_RESPAWN_PLANE
        )
        entity.movement.teleport(respawn)
        currentLifepoints = maxLifepoints
        sendLifepoints()
        return PlayerDeath(
            player = name,
            diedAt = diedAt,
            respawnedAt = respawn,
            lifepointsRestored = currentLifepoints
        )
    }

    /**
     * The live stat container's level in [stat]. [CombatDefender]'s member, and
     * the only reason it exists here: [NpcRetaliation] reads Defence through it.
     */
    override fun level(stat: Stat): Int = stats.getLevel(stat)

    /** Refills to [maxLifepoints]. Nothing on the live path calls it yet. */
    fun healToFull() {
        currentLifepoints = maxLifepoints
        sendLifepoints()
    }

    /**
     * Puts [currentLifepoints] on the wire, where the client's lifepoints bar reads it.
     */
    fun sendLifepoints() {
        if (System.getProperty("opennxt.experiment.ui.lifepoints") == "false") return
        if (!client.channel.isActive) return
        val id = Lifepoints.CURRENT_LIFEPOINTS_VARP
        if (!DefaultVariables.varpIsDefined(id)) {
            logger.warn("ui.lifepoints: varp $id is not defined by this cache - not sent. " +
                "See Lifepoints.CURRENT_LIFEPOINTS_VARP for how the id was derived.")
            return
        }
        client.write(VarpLarge(id, currentLifepoints))
    }

    // =====================================================================
    // WORN EQUIPMENT
    // =====================================================================
    //
    // THE GAP THIS FILLS, stated as it was measured. A complete player-side
    // combat model already existed in this repository - [PlayerCombatStats.derive],
    // [EquippedWeapon], [com.opennxt.model.combat.CombatFormulas.hitChance],
    // [com.opennxt.model.combat.CombatFormulas.damageRoll] - reading item
    // params 3267 (accuracy), 641 (damage x10), 749/750 (requirement) and 14
    // (attack speed). It was reachable from exactly FOUR call sites, ALL of
 // them check tools ( twice,
    // and call `equip(id)` on it. A player arriving over a socket had ONE
    // container - the backpack, inv 93 - so there was nothing to equip a
    // weapon INTO and the model was unreachable from the wire.
    //
    // The analysis that found this said "the single missing piece is a
    // worn-equipment container". Its own skeptic corrected that, and the
    // correction is respected here: a container alone leaves the model
    // unreachable, because there was ALSO no inbound equip path. Both halves
    // are below - the container, and [equipItem] with the chat command that
    // reaches it.

    /**
     * This player's worn equipment: 19 slots, indexed by the cache's own
     * `items.equipSlotId`.
     */
    val worn: com.opennxt.model.items.ItemContainer
        get() = PlayerInventory.wornOf(this)

    // ---- GAME MOD RANK ------------------------------------------------------------------------
    /**
     * This account's rank and powers, resolved ONCE per session on first use.
     *
     * `data/config/mods.json` wins over the save. That ordering is the whole safety story: a server
     * owner who has lost their account, or who wants to strip a mod who is currently logged in, edits a
     * file they can always reach, and no in-game grant can override it. When the roster says nothing
     * about this account the save's own [PlayerSave.rights] applies, which is how a grant made with
     * `::mod` survives a restart.
     */
    private val modProfile: com.opennxt.model.permissions.ModProfiles.Profile? by lazy {
        com.opennxt.model.permissions.ModProfiles.of(name)
    }

    /** This player's rank. */
    val rights: com.opennxt.model.permissions.Rights
        get() = modProfile?.rights ?: com.opennxt.model.permissions.Rights.of(save.rights)

    /** True when this player may use [node] - see [com.opennxt.model.permissions.Powers]. */
    fun hasPower(node: String): Boolean {
        if (rights != com.opennxt.model.permissions.Rights.MOD) return false
        val granted = modProfile?.powers ?: com.opennxt.model.permissions.Powers.ALL
        return node in granted
    }

    /**
     * The item in the main-hand slot as an [EquippedWeapon], or null when the
     * slot is empty or the id has no item row.
     *
     * This is the ONE reader that closes the loop: [PlayerCombatStats.derive]
     * takes an [EquippedWeapon] and every combat number the RECONSTRUCTED model
     * produces for a live player comes through here. Off-hand (slot 5) is NOT
     * read - 1,132 items carry an attack-speed param in slot 5 and dual-wield
     * is not modelled anywhere in this repository, so reading it would produce a
     * number no formula here knows what to do with.
     *
     * Resolved on every call from the WORN SLOT (so an equip is seen at once);
     */
    val wornWeapon: EquippedWeapon?
        get() = worn[PlayerInventory.WEAPON_SLOT]?.let { PlayerCombatStats.lookupWeapon(it.id) }

    /**
     * THE INBOUND EQUIP PATH: puts item [itemId] into the worn slot the CACHE
     * says it belongs in, and pushes the container to the client.
     */
    fun equipItem(itemId: Int): EquipResult {
        if (!PlayerInventory.equipEnabled) return EquipResult.Disabled
        val definition = com.opennxt.resources.sqlite.SqliteItemCodec.load(itemId)
            ?: return EquipResult.NoSuchItem(itemId)
        val slot = definition.equipSlotId
            ?: return EquipResult.NotEquipable(itemId, definition.name)
        if (slot < 0 || slot >= PlayerInventory.WORN_SIZE) {
            return EquipResult.SlotOutOfRange(itemId, definition.name, slot)
        }
        val container = worn
        val replaced = container[slot]
        container[slot] = com.opennxt.model.items.Item(itemId, 1)
        PlayerInventory.sendWorn(this)
        entity.model.dirty = true
        logger.info {
            "EQUIP ${name}: item $itemId (${definition.name ?: "unnamed"}) -> worn slot $slot" +
                (if (replaced != null) ", replacing ${replaced.id}" else "") +
                ". ${PlayerInventory.WORN_PROVENANCE}"
        }
        return EquipResult.Equipped(itemId, definition.name, slot, replaced?.id)
    }

    /** Empties the worn container and re-sends it. The other half of [equipItem]. */
    fun unequipAll(): Int {
        val container = worn
        val had = container.usedSlots()
        container.clear()
        PlayerInventory.sendWorn(this)
        entity.model.dirty = true
        return had
    }

    /**
     * How the client is told this number, as a runtime string.
     */
    val lifepointsUiStatus: String
        get() = if (System.getProperty("opennxt.experiment.ui.lifepoints") == "false")
            "SUPPRESSED by -Dopennxt.experiment.ui.lifepoints=false. The carrier IS established " +
                "(varp ${Lifepoints.CURRENT_LIFEPOINTS_VARP}); this run has simply turned it off. " +
                "currentLifepoints=$currentLifepoints/$maxLifepoints, server-side only."
        else
            "ON THE WIRE: varp ${Lifepoints.CURRENT_LIFEPOINTS_VARP} carries the CURRENT " +
                "value, sent at login and on every change. Derived twice - interface 1430:9 listens to it " +
                "and to stat 3 (Constitution) in this cache, and the protocol shows it take -88 and " +
                "regenerate +88 back to its starting value, stopping dead there. The MAXIMUM is not on this " +
                "varp and does not need to be: the client derives it from the Constitution level in " +
                "UPDATE_STAT. currentLifepoints=$currentLifepoints/$maxLifepoints. " +
                "-Dopennxt.experiment.ui.lifepoints=false removes it."

    /**
     * Current state as a persistable save. Called by [World]'s leave cull and
     * its periodic autosave.
     *
     * - **Position comes from the ENTITY's CURRENT location**, not from the
     * loaded [save]. That is the whole point of a world save: the lobby
     * cannot move a player and the world can, so storing the save's stale
     * position would put every player back where they logged in.
     * - **xp** is read back from the live stat container - the stored truth is
     * xp, never levels; levels re-derive through the verified curve.
     * - **Bank** is read out of the live [com.opennxt.model.bank.Bank] the
     * content layer hands this account, through the SAME identity
     * [Banks.bankSupplier] keys on (the account name), via
     * [Banks.bankForAccount].
     */
    fun toSave(): PlayerSave {
        val xp = LinkedHashMap<Stat, Double>()
        Stat.values().forEach { stat -> xp[stat] = stats.get(stat).experience }
        val location = entity.location
        // Read ONCE, before the copy, so the "refused restore" flag can be retired the moment
        // there is a live project to store instead. Without that, a player whose stored project
        // was refused at login and who then started a NEW one would keep writing the old,
        // unrestorable row forward for ever after finishing the new one.
        val liveProject = com.opennxt.content.impl.Smithing.projectSnapshot(contentPlayer)
        if (liveProject != null) smithingRestoreRefused = false
 // BACKPACK AND WORN ARE WRITTEN HERE, and until neither was.
        //
        // This method copied the save forward with only xp, position and bank replaced, so the
        // backpack it stored was whatever had been LOADED at login: eat a cabbage and relog and the
        // cabbage was back; chop a log and it was gone. SkillingWiring's comment ("the SAME
        // container WorldPlayer.toSave persists") described an intent this method did not carry out.
        // Worn had no field at all - see PlayerSave.worn.
        return save.copy(
            xp = xp,
            x = location.x, y = location.y, plane = location.plane,
            backpack = PlayerSave.containerContents(PlayerInventory.backpackOf(this)),
            worn = PlayerSave.containerContents(worn),
            rights = rights.id,
            bank = PlayerSave.bankContents(Banks.bankForAccount(name)),
 // THE INTERFACE SETTINGS. Same class of gap as backpack and worn above:
            // the client has been pushing its varc state at us on a ~1 s timer for as long as this
            // server has existed, and until now every byte of it was discarded, so a HUD
            // arrangement could not survive a relog. What the client sent this session is held by
            // VarcTransmitHandler; this is where it becomes durable.
            //
            // FALLING BACK TO THE LOADED SAVE, not to empty: a session in which the client never
            // sent a VARC_TRANSMIT (it only sends on change) must not wipe what the previous
            // session stored. `ifEmpty` is the whole difference between "no news" and "cleared".
            varcs = com.opennxt.net.game.handlers.VarcTransmitHandler.stateOf(this)
                .ifEmpty { save.varcs },
 // THE VARP OVERRIDES. PlayerSave.varps was declared with the save format
            // and never written by anything; this is the line that makes the seam real. No
            // ifEmpty fallback is needed here, unlike varcs above: varpOverrides is SEEDED from
            // save.varps at construction, so an untouched session already carries the old values
            // forward and cannot clear them.
            varps = varpOverrides.toMap(),
            cosmetics = cosmetics.toMap(),
 // THE TOOL BELT. Sorted so the blob is canonical, like every other map
            // here. NO `ifEmpty` fallback and none is needed, for the same reason `varps` needs
            // none: the live set is SEEDED from save.toolbelt at construction, so an untouched
            // session already carries the old ids forward and cannot clear them.
            toolbelt = toolbelt.sorted(),
 // THE RUN RESERVE AND TOGGLE. NULL AT THE DEFAULTS, and that is the whole
            // of the "only written when it matters" contract - PlayerSave.toJson simply omits a
            // null, so a player at a full bar with the default toggle serialises byte-identically
            // to a save written before this field existed. The decision lives HERE, at one site,
            // rather than being re-derived inside toJson.
            //
            // NO `ifEmpty` FALLBACK and none is possible: the live RunEnergy is SEEDED from
            // save.run in the init block above, so an untouched session already carries the old
            // numbers forward. What it must NOT do is fall back to save.run when the live state is
            // the default - that would resurrect an old empty bar after the player had stood still
            // and filled it, which is the mirror image of the bug the seeding closes.
            run = entity.runEnergy.let {
                if (it.isDefault()) null else PlayerSave.SavedRunEnergy(it.tenths, it.toggled)
            },
 // THE UNFINISHED ANVIL PROJECT. `Smithing.create` consumes the material
            // bars and puts item 47068 in the backpack; the backpack is persisted four lines up
            // and the project's four numbers were not, so a relog left the player holding a 47068
            // that `smith()` answered NEED_PROJECT for and only a SECOND set of bars could
            // continue. See PlayerSave.smithing.
            //
            // NO `ifEmpty` FALLBACK, AND THAT IS THE DELIBERATE PART. varcs falls back to
            // `save.varcs` because the client only sends VARC_TRANSMIT on change, so an untouched
            // session is "no news" rather than "cleared". A project is the opposite: it is
            // RESTORED into the live map at construction (see the init block above), so the live
            // map is authoritative for the whole session and a null is REAL NEWS. The case that
            // decides it is finishing: `smithCycle` removes the project on the last swing, and a
            // fallback would then write the completed project back and RESURRECT it - the player
            // would relog holding the finished product AND a project for it, and could smith the
            // same item again for free off a 47068 they no longer have. Item duplication is a
            // worse failure than the one this field closes.
            //
            // The one exception is a refused restore, and it is held by a flag rather than by a
            // fallback so that "the recipe table lost this product" cannot be confused with "the
            // player finished it". See [smithingRestoreRefused], cleared above the moment a live
            // project exists so the flag can never outlive the row it was protecting.
            smithing = liveProject?.let {
                PlayerSave.SavedProject(it.productId, it.progress, it.xpPaidTenths, it.heat, it.stage)
            } ?: if (smithingRestoreRefused) save.smithing else null,
        )
    }

    init {
        // WorldPlayer's handler map was NEVER populated, which is why every
        // inbound packet in the world logged "TODO: Handle incoming". The
        // client sent MOVE_GAMECLICK 58 times in one session and all 58 were
        // dropped on the floor.
        @Suppress("UNCHECKED_CAST")
        run {
            handlers[com.opennxt.net.game.serverprot.NoTimeout::class] =
                com.opennxt.net.game.handlers.NoTimeoutHandler
            // VARC_TRANSMIT (949 opcode 105) - the client pushing its own interface state.
            // Bound here AND in LobbyPlayer: the client sends it across the world handover.
            handlers[com.opennxt.net.game.clientprot.VarcTransmit::class] =
                com.opennxt.net.game.handlers.VarcTransmitHandler
            // CLIENT_CHEAT (opcode 147) - the `::` console.
            //
 // This binding was MISSING until, and its absence was
            // invisible in exactly the way this file's other comments warn
            // about: LobbyPlayer and ProxyPlayer both bind ClientCheatHandler,
            // so the console answered in the lobby, and every `::` typed in the
            // WORLD fell through to the unhandled-packet branch below. That is
            // the whole game-mod toolkit - ::item, ::npc, ::tele, ::mod - being
            // unreachable at the only place anyone would use it, with no error
            // at either end. The commands, their permission checks and their
            // web page had all shipped; the one line that lets the packet reach
            // them had not.
            handlers[com.opennxt.net.game.clientprot.ClientCheat::class] =
                com.opennxt.net.game.handlers.ClientCheatHandler
            // IF_BUTTON1 routes through the FAMILY handler now, not its own.
            // The builder proved all ten members share one
            // three-field layout, so IfButton1 is an IfButtonN like the other
            // nine and the bespoke IfButton1Handler no longer type-matches.
            handlers[com.opennxt.net.game.clientprot.IfButton1::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.MoveGameClick::class] =
                com.opennxt.net.game.handlers.MoveGameClickHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.MoveMinimapClick::class] =
                com.opennxt.net.game.handlers.MoveMinimapClickHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            // Window size/mode. Typed on BasePlayer, so no cast - the lobby
            // registers the very same object.
            handlers[com.opennxt.net.game.clientprot.WindowStatus::class] =
                com.opennxt.net.game.handlers.WindowStatusHandler

            // IF_BUTTON2..10 - the right-click menu rows. Nine opcodes, nine
            // packet classes, one handler: they differ only in which row was
            // chosen, and the packet carries that.
            handlers[com.opennxt.net.game.clientprot.IfButton2::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton3::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton4::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton5::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton6::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton7::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton8::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton9::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton10::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler

            // ClientProt 102 - a click on a component whose definition carries a
            // menu string goes here INSTEAD of to an IF_BUTTON (dispatcher fork
            //).
            //
 // QUALIFIED. This comment used to end "1184:15, the
            // dialogue continue arrow, is one of these, which is why the frame
            // has never been seen: it was arriving and being dropped as an
            // unregistered opcode." The fork is still read off the client and is
            // not in doubt. The claim about 1184:15 is, because the only observation
 // that exists disagrees with it: over run 20:10:38,
            // opcode 102 arrives ZERO times, while the sole inbound frame
            // off builder - see ObservedClientPacket.OPCODES).
            //
 // SETTLED 10:57. The continue click is opcode **127**,
            // confirmed by function: right-click the arrow with 1184:15 armed at
            // mask 2047, choose "Continue", and 127 arrives carrying 1184:15 and
            // ADVANCES the conversation through Dialogue.onButton, which refuses
            // any component the open page did not arm. 102 has still never
            // arrived, anywhere. This binding stays because the dispatcher
            // fork is read off the client and is not in doubt -
            // but 102 is NOT the continue click, and nothing should assume it is.
            //
            // So "the frame has never been seen" was true of 102 and remains
            // true; what is NOT established is that a continue click produces
            // 102 at all. 127 is not the replacement answer either - it fired
            // once in a run with three conversations. The trigger for both is
            // open, and this binding stays because the fork justifies it.
            handlers[com.opennxt.net.game.clientprot.IfButtonLabelled::class] =
                com.opennxt.net.game.handlers.IfButtonLabelledHandler

            // OPLOC1..6 - the six menu options on a scenery object. Six packet
            // classes (PacketRegistry's class map is KClass-keyed and would keep
            // only the last of six sharing one class), one handler: they differ
            // only in which row was clicked, and the packet carries that.
            // The cast is the MoveGameClick one - OpLocHandler is typed on
            // WorldPlayer because it needs the entity to walk.
            handlers[com.opennxt.net.game.clientprot.OpLoc1::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc2::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc3::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc4::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc5::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc6::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            // OPNPC1..6, OPPLAYER1..10, OPOBJ1..6 - the three entity menu
            // families. Twenty-two packet classes (PacketRegistry's class map is
            // KClass-keyed), three handlers: within a family the packets differ
            // only in which row was clicked, and each carries that.
            //
            // All three handlers say what was interacted with, by name from
            // rs3.sqlite where there is one, and then WALK - via
            // MoveGameClickHandler.walk, the same pathing MOVE_GAMECLICK uses.
            // None of them invents gameplay: no combat, no trade, no follow, no
            // pickup, no loot, no XP, no ContentRegistry dispatch. See each
            // handler's doc for why, in particular OpObjHandler, which
            // deliberately does not call GroundItems.pickup.
            //
            // The casts are OpLocHandler's: these three are typed on WorldPlayer
            // because they need the entity to walk.
            handlers[com.opennxt.net.game.clientprot.OpNpc1::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc2::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc3::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc4::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc5::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc6::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            handlers[com.opennxt.net.game.clientprot.OpPlayer1::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer2::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer3::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer4::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer5::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer6::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer7::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer8::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer9::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer10::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            handlers[com.opennxt.net.game.clientprot.OpObj1::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj2::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj3::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj4::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj5::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj6::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            // Client-side state reports: window focus and camera orientation.
            // Typed on BasePlayer, so no cast. Both only record; the camera one
            // rate-limits its own logging because it arrives per frame.
            handlers[com.opennxt.net.game.clientprot.EventAppletFocus::class] =
                com.opennxt.net.game.handlers.EventAppletFocusHandler
            handlers[com.opennxt.net.game.clientprot.EventCameraPosition::class] =
                com.opennxt.net.game.handlers.EventCameraPositionHandler

            // Keyboard events (949 opcode 77, var-short). Intercepts ESC to
            // close modals as a fallback for the CS2 setopkey keybind chain.
            handlers[com.opennxt.net.game.clientprot.EventKeyboard::class] =
                com.opennxt.net.game.handlers.EventKeyboardHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            // CHAT. MESSAGE_PUBLIC takes the WorldPlayer cast because it walks
            // every other player's viewport to decide who can see the speaker,
            // and a lobby player has no viewport at all. CHAT_SETMODE and
            // MESSAGE_PRIVATE are typed on BasePlayer and only record, no cast.
            // ...except that the EQUIP CHAT COMMAND sits in front of it. See
            // [EquipChatCommand]: it consumes a line that starts with the
            // command prefix and DELEGATES every other line to the handler that
            // was here before, so ordinary chat is byte-for-byte unchanged.
 // after a sandbox rollback: the recovery replay
            // ended on this pass's MUTATION CONTROL ("registration reverted"),
            // which put MessagePublicHandler back in front. EquipChatCommand is
            // the real registration - it consumes a line starting with the
            // command prefix and delegates everything else to
            // MessagePublicHandler, so ordinary chat is byte-for-byte unchanged.
            handlers[com.opennxt.net.game.clientprot.MessagePublic::class] =
                EquipChatCommand
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.ChatSetMode::class] =
                com.opennxt.net.game.handlers.ChatSetModeHandler
            handlers[com.opennxt.net.game.clientprot.MessagePrivate::class] =
                com.opennxt.net.game.handlers.MessagePrivateHandler

            // EVENT_MOUSE_CLICK (949 opcode 2). Typed on BasePlayer, so no
            // cast, and the lobby registers the very same object. It RECORDS a
            // cursor position and reacts to nothing - see
            // EventMouseClickHandler for why a bare (x, y) must not be turned
 // into an interaction. Until opcode 2 was consumed as an
            // unidentified blob although it had been named AND declared on disk
 // since; this binding and the register call, not any
            // decode, are what was missing.
            handlers[com.opennxt.net.game.clientprot.EventMouseClick::class] =
                com.opennxt.net.game.handlers.EventMouseClickHandler

            // WORLDLIST_FETCH (949 opcode 125). ANSWERED HERE, not only in the
            // lobby. The world stage had no binding at all, so an in-game world
            // list request framed, decoded, reached this map, matched nothing
            // and produced "[census] DECODED BUT UNHANDLED: WorldlistFetch" -
            // measured in run server-20260827-135435, where the client asked
            // once from the world after asking twice from the lobby. The
            // handler is typed on BasePlayer and picks the list per stage; see
            // WorldlistFetchHandler for the reply and its kill switch.
            handlers[com.opennxt.net.game.clientprot.WorldlistFetch::class] =
                com.opennxt.net.game.handlers.WorldlistFetchHandler

            // Framing known, meaning not. Without this entry every one of the
            // observed opcodes would hit the "TODO: Handle incoming" line below
            // at INFO with no rate limit - which is the same log flood the drop
            // warning had, one layer further in.
            handlers[com.opennxt.net.game.clientprot.ObservedClientPacket::class] =
                com.opennxt.net.game.handlers.ObservedClientPacketHandler

 //: IF_UPDATE_COUNT (89) keeps the opt-in panel-close experiment
            // it had as an observed blob; every other generated client packet class
            // logs its decoded fields until a gameplay handler claims it
            // (installClientHandlers never overrides an entry already in this map).
            handlers[com.opennxt.net.game.clientprot.generated.IfUpdateCount::class] =
                com.opennxt.net.game.handlers.IfUpdateCountHandler
            // RESUME_PAUSEBUTTON (127) keeps the dialogue-continue routing it had as a blob.
            handlers[com.opennxt.net.game.clientprot.generated.ResumePausebutton::class] =
                com.opennxt.net.game.handlers.ResumePausebuttonHandler
 // IF_BUTTOND (25) = window/item drags; names the endpoints.
            handlers[com.opennxt.net.game.clientprot.generated.IfButtond::class] =
                com.opennxt.net.game.handlers.IfButtondHandler
 // OPLOCT (116) = use item on loc; cooking on a fire.
            handlers[com.opennxt.net.game.clientprot.generated.Oploct::class] = com.opennxt.net.game.handlers.OpLocTHandler
            com.opennxt.net.game.GeneratedRegistrations.installClientHandlers(handlers)
        }
    }

    fun handleIncomingPackets() {
        val queue = client.incomingQueue
 // CAPPED. This was `while (true) { queue.poll ?: return }` - an unbounded
        // loop running inside World.tick, fed by Netty's event loop with no setAutoRead(false)
        // anywhere, so one client sending flat out stretched a tick for the whole world.
        // See [com.opennxt.net.InboundDrainLimit] for where 128 comes from (a real client's worst
        // measured tick, across six the protocol, is 37) and for why the surplus is KEPT
        // rather than dropped.
        var handled = 0
        while (handled < com.opennxt.net.InboundDrainLimit.MAX_PER_TICK) {
            val packet = queue.poll() ?: return
            handled++

            val handler = handlers[packet::class] as? GamePacketHandler<in BasePlayer, GamePacket>
            if (handler != null) {
                handler.handle(this, packet)
            } else {
                // Was `logger.info { "TODO: Handle incoming $packet" }`.
                //
                // Three problems with that line, all of which cost the
 // dialogue run: it is INFO, it is phrased as a
                // developer's note rather than as an observation anything can
                // grep for, and it is unbounded - one line per NO_TIMEOUT for
                // the life of the session. InboundCensus.noHandler names the
                // class, says out loud that the frame was RECEIVED and then
                // dropped on the floor, and rate limits per class.
                InboundCensus.noHandler(packet)
            }
        }
        // Reached only by exhausting the cap - the `poll() ?: return` above is the normal exit.
        // Anything still queued runs next tick.
        val remaining = queue.size
        if (remaining > 0) com.opennxt.net.InboundDrainLimit.reportBacklog(name, handled, remaining)
    }

    /**
     * Where interface 1433 - the Options menu, the one carrying 'Edit Layout
     * Mode' - is mounted under the gameframe.
     *
     * -Dopennxt.experiment.ui.optionsSlot=751 (the old, broken mount)
     */
    private val optionsMenuSlot: Int =
 // 808 -> 805: REFERENCE. 949-session4.jsonl opens 1433 at 1477:805
 // live on because the menu appeared - it appears at either seat, so
        // that observation could not tell them apart. The wire can.
        System.getProperty("opennxt.experiment.ui.optionsSlot")?.toIntOrNull() ?: 805

    /**
     * Where in [added] the panel-visibility apply (clientscript 9943) fires.
     *
     * `late` (default) - after every IF_OPENSUB and after the varp re-send.
     */
    private val panelApplyAt: String =
        (System.getProperty("opennxt.experiment.panelApply.at") ?: "late").lowercase().also {
            if (it !in setOf("early", "late", "both"))
                logger.warn("opennxt.experiment.panelApply.at=$it is not a position; " +
                    "use early, late or both. Falling back to late.")
        }.let { if (it in setOf("early", "late", "both")) it else "late" }

    /**
     * Sends clientscript 9943, the client's own visibility/layout apply over the
     * 94 panels of enum 7717, and says on the log which position it fired from
     * and how many sub-interface mounts were standing at the time. The count is
     * the whole point: 9943 can only lay out a panel that already exists, so a
     * run whose log says `mounts=24` has applied a layout to 24 panels and
     * skipped the rest.
     */
    private fun applyPanelVisibility(position: String) {
        if (System.getProperty("opennxt.experiment.panelApply") == "false") return
        val mounts = interfaces.openedCount()

        // THE VARPS GO BEFORE 9943, NOT AFTER, and the ordering is not arbitrary: 9943
        // lays out the panels that exist when it runs, and several of them size themselves
        // from the values they are holding at that moment. Filling shells after the layout
        // has already been computed is how a correctly-mounted panel ends up correctly
        // mounted and the wrong size. See [com.opennxt.content.impl.LoginVarps] for what
        // this table is, how weak the evidence behind it is, and the one flag that removes it.
        if (position == panelApplyAt || panelApplyAt == "both") {
            com.opennxt.content.impl.LoginVarps.send(this)
        }

        // The player's own stored interface settings, replayed here for the same reason the
        // varps are: 9943 lays out panels that size themselves from the state they hold when it
        // runs, and the varc store IS that state. The reference client sends its 212 CLIENT_SETVARC* in this
        // same position. See [com.opennxt.content.impl.VarcRestore].
        com.opennxt.content.impl.VarcRestore.send(this)

        // Lifepoints go in with the varps and BEFORE 9943, for the reason stated
        // directly above: 9943 lays out panels that size themselves from the values
        // they are holding when it runs, and the lifepoints bar is one of them.
        // The reference client sends this varp at t=45.9s in its own world-login burst, i.e. in
        // this same window. See [sendLifepoints].
        sendLifepoints()

        client.write(RunClientScript(script = 9943, args = arrayOf()))
        logger.info("panelApply[$position]: sent RunClientScript(9943) with $mounts " +
            "interface(s) mounted. 9943 lays out only panels that exist when it runs, " +
            "so this count is the number it could act on. Position is " +
            "-Dopennxt.experiment.panelApply.at=early|late|both (default late); " +
            "-Dopennxt.experiment.panelApply=false suppresses it entirely.")
    }

    fun added() {
        entity.model.refresh()

        // The ONE place the game login response is sent from. It runs here -
        // on the world tick, not at GAMELOGIN_CONTINUE time - because
        // playerIndex is only assigned when World.tick adds this player's
        // entity to its EntityList; sending earlier would invent index 0.
        client.channel.write(Unpooled.buffer(1).writeByte(GenericResponse.SUCCESSFUL.id))
        val response = LoginPacket.GameLoginResponse(
            byte0 = 0,
            rights = 2,
            byte2 = 0,
            byte3 = 0,
            byte4 = 0,
            byte5 = 0,
            byte6 = 0,
            playerIndex = entity.index,
            byte8 = 1,
            medium9 = 0,
            isMember = 1,
            username = name, // was the invented placeholder "usernametodo"
            short12 = 0,
            int13 = 0
        )
        logger.debug { "Sending game login response: $response" }
        val future = client.channel.writeAndFlush(response)

        // added() runs on the tick thread, not the event loop, so the write
        // above is only QUEUED here. The old code swapped the pipeline
        // immediately after writeAndFlush: if the event loop had not yet
        // encoded the LoginPacket, it would hit the just-installed
        // GamePacketEncoder and fail as an unsupported message - a race.
        // Await the write, then run the same guarded swap the lobby path uses.
        future.awaitUninterruptibly(3000)
        if (!future.isSuccess) {
            logger.warn(future.cause()) { "Game login response for $name never made it onto the wire; not swapping pipeline" }
            return
        }
        if (!LoginServerHandler.swapToGamePipeline(client.channel, name)) return

        // ???
        val packet = Unpooled.buffer(5140)
        val builder = GamePacketBuilder(packet)
        viewport.init(builder)
        val registration = PacketRegistry.getRegistration(Side.SERVER, RebuildNormal::class)!!
        (registration.codec as GamePacketCodec<GamePacket>).encode(viewport.createPacket(), builder)
        client.write(UnidentifiedPacket(OpcodeWithBuffer(registration.opcode, packet)))
        // Oh well.

        val player = this

        // stats.init crashed the client in the lobby AND here in
        // the world (run diag-20260816-105359), and the reason turned out to be
        // neither the stage nor the field layout: UPDATE_STAT was declared at the
        // WRONG OPCODE. 184's handler indexes a five-element container that is
        // empty at login and is not the skill store at all. The real one is
        // opcode 4, and the declaration + the retraction now live in
        // data/prot/949/serverProt/UPDATE_STAT.txt and
        // to be here: "the client has still not built its 0x68-byte skill
        // records" - there are no 0x68-byte skill records; they are 24 bytes and
        // they live somewhere else entirely.
        //
        // Still off by default, for one remaining unknown: the client's 24-byte
        // record vector is built by from its main loop once
        // [client+0x18d28]+0x3c == 4, and opcode 4's handler does not null-check
        // it, so a send that lands before that frame dies at [NULL+0x10]. Nobody
        // has put opcode 4 on the wire yet. See the KDoc on
        // PlayerStatContainer.refresh for the discriminator to watch for. The
        // container stays seeded from the save either way, so no state is lost
        // and a world still renders. Set
        //
        //     -Dopennxt.experiment.sendStats=true
        //
        // to put it back for a deliberate test.
        // DEFERRED, not immediate. Static analysis of the 949 client (see
        // dereferences a per-skill vector that is NULL until the client's own
        // main loop builds it - which happens only AFTER it has processed this
        // REBUILD_NORMAL and advanced its world-model stage to 4, i.e. NOT in
        // the same frame REBUILD_NORMAL arrives. Sending stats.init() here, one
        // line after the REBUILD_NORMAL write above, is precisely the too-early
        // case that crashes at [NULL+0x10].
        //
        // So instead of sending now, ARM a countdown. tick() fires the burst
        // STAT_SEND_DELAY_TICKS later, by which point REBUILD_NORMAL is a whole
        // tick (or more) in the client's past. The delay is a system property so
        // the eventual live test can escalate N without a rebuild; the crash
        // address discriminator that says "still too early, raise N" is on
        // PlayerStatContainer.refresh.
        if (PlayerStatContainer.sendStatsEnabled) {
            statBurstCountdown = STAT_SEND_DELAY_TICKS
            logger.warn {
                "EXPERIMENT: UPDATE_STAT (opcode 4) ARMED - will burst $statBurstCountdown tick(s) after " +
                    "this REBUILD_NORMAL, once the client has built its skill-record vector. Opcode 4 has " +
                    "never been on the wire; watch the crash address per PlayerStatContainer.refresh's KDoc."
            }
        }
 // RAW REFERENCE REPLAY, mode=only: the recorded the reference client 949 login IS the login
        // from here on - RESET_CLIENT_VARCACHE, the 949 varps, the reference client inventories/stats, IF_OPENTOP 1477 and
        // the 56 real opens with their 8862 announcements, the 86 scripts, the varcs - in recorded order.
        // Nothing below this line runs (no 919 transcript, no experiments, no tick countdowns).
        if (com.opennxt.content.impl.Replay949.enabled && com.opennxt.content.impl.Replay949.only) {
            replay949Cursor = 0
            replay949Countdown = com.opennxt.content.impl.Replay949.delayTicks
            logger.warn { "replay949[only]: ARMED - ${com.opennxt.content.impl.Replay949.size()} packet(s) start in ${replay949Countdown} tick(s); the 919 login line is SKIPPED" }
            return
        }
        player.client.write(ResetClientVarcache)
        DefaultVariables.sendDefaultVarps(client)
        player.client.write(RunClientScript(script = 671, args = arrayOf(0)))
        player.interfaces.openTop(id = 1477)
 // 27 -> 31: REFERENCE. The real client mounts 1482 at 1477:31 on login
        // (949-session4.jsonl, IF_OPENSUB at t=12750, the very first one it sends).
        player.interfaces.open(id = 1482, parent = 1477, component = 31, walkable = true)
        // -Dopennxt.experiment.ui.worldHover=true  DEFAULT OFF. THE HUD LEFT-CLICK EXPERIMENT.
        // MENU_DEFAULT_OP_949.md s17: the ground "Walk here" row is suppressed while a UI
        // component is hovered only if the world view is registered with a CS2 mouseover
        // listener (key 4). No clientscript in the cache installs one on 1482:0 (the 3D
        // viewport - the only component with contenttype 1337). Script 9671(a0,a1,a2) is
        // an ORPHAN (no gosub callers, no cache reference - the profile of a server-sent
        // script) that does exactly if_setonmouseover(16429(a0,a1), a2), 16429 being a
        // graphic setter. Sending it with a0=-1 and a1=a2=1482:0 installs the listener on
        // the viewport. PREDICTION: right-click on a HUD element no longer shows "Walk
        // here" and a plain left-click fires the component's op. A client crash here is a
        // result too (16429 on the viewport type) - it costs one run.
        // -Dopennxt.experiment.ui.worldRepeat=true  DEFAULT OFF (until run). THE FIX CANDIDATE.
 // Live probe (tools/949/hoverprobe.py, read-only): with the mouse on a HUD
        // chat tab the world view 1482:0 still had comp+0x31 bit 3 (cursor-over) SET. Capstone
        // read of the walk: that bit is cleared only through a QUEUED
        // listener record naming the world view (InterfaceManager+0x1d8 is a per-frame queue,
        // not a registry; record +0x154 = "cancellable pointer event"), and a key-4 mouseover
        // listener queues one only in the frame the cursor ENTERS the view ( skips
        // when bit 3 is already set) - the view covers the whole window, so it never re-fires;
        // that is why worldHover (9671) changed nothing. A key-5 ONMOUSEREPEAT listener queues
        // a cancellable record EVERY frame while bit 3 is set, so a
        // HUD component drawn on top cancels it and clears the world view's bit 3, and
        // then builds no ground row: no "Walk here" over the HUD, left-click takes
        // the component's own op. Cache script 4595(component, string) installs exactly
        // if_setonmouserepeat(8799(string, mousex, mousey)) on its component - 8799 is the
        // tooltip; an empty string should draw nothing (live question).
        if (System.getProperty("opennxt.experiment.ui.worldRepeat") == "true") {
            val worldView = (1482 shl 16) or 0
            player.client.write(RunClientScript(script = 4595, args = arrayOf(worldView, "")))
            logger.warn { "ui.worldRepeat: sent RunClientScript(4595, [1482:0, \"\"]) - onmouserepeat listener on the world view (EXPERIMENT: no 'Walk here' over the HUD, left-click fires the component op)" }
        }
        if (System.getProperty("opennxt.experiment.ui.worldHover") == "true") {
            val worldView = (1482 shl 16) or 0
            player.client.write(RunClientScript(script = 9671, args = arrayOf(-1, worldView, worldView)))
            logger.warn { "ui.worldHover: sent RunClientScript(9671, [-1, 1482:0, 1482:0]) - mouseover listener on the world view (EXPERIMENT)" }
        }
        player.interfaces.open(id = 1466, parent = 1477, component = 284, walkable = true)
        player.interfaces.events(id = 1466, component = 7, from = 0, to = 28, mask = 30)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(0, 1)))
        player.interfaces.open(id = 1473, parent = 1477, component = 98, walkable = true)
 // DISCRIMINATOR, -Dopennxt.experiment.unhideBackpack=false to disable.
        //
        // 1477:101 - the backpack's panel-slot CONTAINER, i.e. mount 103 minus 2 -
        // carries `flags = 1` in the served cache, as do 49 of the 50 panel-slot
        // containers under 1477:60. The lone exception is 1477:418, panel 18, the orb
        // cluster: the one panel that is never a tab.
        //
        // The component tree walk skips a hidden node AND ITS WHOLE SUBTREE -
        // opens each node with `if (*node != 0) goto next_node`, and
        // `node+0` is exactly the byte `if_sethide` (opcode 1710) writes. The skip
        // takes the layout, the draw, the trigger dispatch and the DESCENT, and the
        // descent is the only path from 1477:103 into interface 1473. So the item grid
        // is built and filled and never drawn. record 186 holds 28 entries /
        // 7 occupied, and 1473:4/5/6 each hold 28 dynamic children.
        //
        // What normally clears the flag never runs here. 1477:23 listens for varps
        // [5839, 3680] and runs clientscript 20507:
        //     gosub 8268(1004, 0);  if (varbit 27169 != 1) return;  gosub 9943
        // varbit 27169 is varp 3680 bits 21..21, and this server sends 3680 =
        // 0x400B9801, whose bit 21 is 0 - so 20507 returns and 9943, the client's own
        // panel-visibility apply over all 94 panels of enum 7717, never runs. The only
        // other route (8304) has no gosub caller: it is bound as a click handler, and
        // corpus-wide scan for `gosub 9943` finds exactly two callers, 20507
        // and 8781, and 8304 is the ribbon panel toggle, calling 8307 (open)
        // and 8323 (close). The CONCLUSION still holds through 20507; only this
        // sentence's account of the alternative route was wrong.
        // no button in this server transmits.
        //
        // NOT panel activation: RunClientScript(8862, [P, 1]) dispatches to 8863, which
        // draws or removes the PADLOCK overlay (sprite 18690, " currently
        // unavailable."). Availability is not visibility.
        //
        // This line is the NARROW test - it unhides the backpack only. The general fix
        // is `RunClientScript(9943)` (zero args; its CS2 trailer reads (15,0,0,0,0,0),
        // 15 locals and 0 parameters, calibrated against 8862's (2,0,0,2,0,0)), which
        // runs the client's own apply for all 50 panels. Prefer that once this is
        // confirmed. `hide()` deliberately bypasses panelRemap949 and mountFor(), so
        // 101 goes out unmodified - correct, because 101 is already the 949 component.
        if (System.getProperty("opennxt.experiment.unhideBackpack") != "false") {
            player.interfaces.hide(id = 1477, component = 101, hidden = false)
        }
 //: THE GENERAL FIX the comment above says to prefer once the
        // narrow one is confirmed. It is confirmed - the backpack draws - and
        // the residual symptom is exactly what this predicts.
        //
        // every panel is INVISIBLE UNTIL DRAGGED. Layout, hit-testing
        // and position persistence all work; a panel appears the moment the
        // user moves it and forces its region to invalidate. That is a panel
        // that never received its initial visibility apply, not a panel that
        // cannot paint.
        //
        // WHY IT NEVER RAN. 1477:23 listens for varps [5839, 3680] and runs
        // clientscript 20507, which is `gosub 8268(1004, 0); if (varbit 27169
        // != 1) return; gosub 9943`. varbit 27169 is varp 3680 bits 21..21, and
        // this server sends 3680 = 0x400B9801, whose bit 21 is 0 - so 20507
        // RETURNS and 9943 never runs. The only other route into 9943, script
        // 8304, has no gosub caller: it is bound as a click handler.
        // callers are 20507 and 8781; 8304 calls 8307/8323, never 9943. The
        // conclusion - that the client's own route into 9943 is dead on this
        // server - stands, because 20507 is the one that is gated off.
        //
        // 9943 is the client's OWN panel-visibility apply over all 94 panels of
        // enum 7717. Zero arguments - its CS2 trailer reads (15,0,0,0,0,0), 15
        // locals and 0 parameters, calibrated against 8862's (2,0,0,2,0,0).
        //
        // This calls it directly rather than fixing varp 3680, deliberately:
        // sending a different 3680 would change whatever else bit 21 gates, and
 // this server does not yet know what that is. Calling 9943 changes one
        // thing.
        //
        // Kill switch, no rebuild: -Dopennxt.experiment.panelApply=false
        //
 //THIS POSITION IS THE DEFECT, and it is now rather
        // than argued. See [panelApplyAt] and [applyPanelVisibility]. The call
        // moved to the END of added(); it fires here only under
        // -Dopennxt.experiment.panelApply.at=early (or =both), which exists so
        // the old behaviour stays reproducible as a control.
        if (panelApplyAt == "early" || panelApplyAt == "both") applyPanelVisibility("early")
        player.interfaces.events(id = 1473, component = 7, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1473, component = 7, from = 0, to = 27, mask = 15302030)
 //: ALSO arm 1473:4/5/6 with the same 28-slot range and mask.
        //
        // The line above arms 1473:7, which is what the recorded 919 LOGIN
        // TRANSCRIPT does - `added()` is a proxy recording of a real Jagex
        // server, so that line is a faithful replay and is NOT wrong for the
        // build it came from. It is wrong for 949: measured out of the served
        // cache, `1473:7` is `basewidth=0 baseheight=33 aspectwidthtype=1` - a
        // 33-PIXEL HEADER STRIP - while the body is `1473:2` at 142x194, and
        // 1473:3/4/5/6 are its proportional children. The comment fifteen lines
        // above already records that 1473:4/5/6 "each hold 28 dynamic
        // children"; nothing had connected that to the arming.
        //
        // WHY ALL THREE RATHER THAN A GUESS. The cache cannot say which of the
        // three carries the item grid: all six of 1473:2..7 have IDENTICAL
        // empty attr shapes - optable=[], menu empty, scripts={}, listeners all
        // empty - because they are layers populated at runtime by clientscript.
        // Picking one would be a coin flip dressed as a fix.
        //
        // Arming all three makes the CLIENT answer. IF_BUTTON reports
        // `interface=1473 component=N`, so the first backpack-slot click names
        // the component that actually carries the grid, and the other two can
        // then be dropped. This is a self-identifying experiment, not a
        // scattergun: the three are empty layers, the mask and range are copied
        // unchanged from the line above, and 1473:7 is deliberately LEFT ARMED
        // so nothing that works today can regress.
        //
        // Kill switch, no rebuild: -Dopennxt.experiment.backpackSlotArm=false
        if (System.getProperty("opennxt.experiment.backpackSlotArm") != "false") {
            for (slotParent in intArrayOf(4, 5, 6)) {
                player.interfaces.events(id = 1473, component = slotParent,
                    from = 0, to = 27, mask = 15302030)
            }
        }
        player.interfaces.events(id = 1473, component = 25, from = 0, to = 16, mask = 1422)
        player.interfaces.events(id = 1473, component = 1, from = 0, to = 5, mask = 2099198)
        player.interfaces.events(id = 1473, component = 28, from = 0, to = 5, mask = 2099198)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(2, 1)))
        player.interfaces.open(id = 1464, parent = 1477, component = 109, walkable = true)
        player.interfaces.events(id = 1464, component = 15, from = 0, to = 18, mask = 15302654)
        player.interfaces.events(id = 1464, component = 24, from = 0, to = 6, mask = 2046)
        player.interfaces.events(id = 1464, component = 19, from = 0, to = 6, mask = 2046)
        player.interfaces.events(id = 1464, component = 15, from = 0, to = 18, mask = 10749950)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(3, 1)))
        player.client.write(ClientSetvarcSmall(181, 0))
        // NOT a live mount, and it is filed as one every time someone greps this file:
        // 131 is in [InterfaceManager.panelDrop949], so `open` returns before anything
        // reaches the wire and records DROPPED. A source-text census of
        // `interfaces.open(... parent = 1477 ...)` that does not apply panelDrop949 and
        // panelRemapTable() will report this line as "interface 1458 mounted on the
 // Familiar panel's param 3509" - the overlay pass did exactly that.
        // What actually reaches the client is 1458 at 1477:136, substituted in by
        // panelRemap949's `142 to 1458` at the `open(id = 1460, ..., component = 142)`
 // call a few lines below. Re-checked.
        player.interfaces.open(id = 1458, parent = 1477, component = 131, walkable = true)
        player.interfaces.events(id = 1458, component = 39, from = 0, to = 38, mask = 8388610)
        player.interfaces.open(id = 1461, parent = 1477, component = 186, walkable = true)
        player.interfaces.open(id = 1884, parent = 1477, component = 197, walkable = true)
        player.interfaces.open(id = 1885, parent = 1477, component = 208, walkable = true)
        player.interfaces.open(id = 1887, parent = 1477, component = 219, walkable = true)
        player.interfaces.open(id = 1886, parent = 1477, component = 230, walkable = true)
        player.interfaces.open(id = 1460, parent = 1477, component = 142, walkable = true)
 // "Prayers currently unavailable." WAS THIS LINE'S POSITION.
        //
        // Clientscript 8863 - which 8862 gosubs - reads the panel struct's param 3505 (the DOCK)
        // and runs `if_hassub` on it. Dock filled -> it returns. Dock EMPTY -> it unhides the
        // placeholder, reads the panel name from param 3493 and concatenates the literal
        // " currently unavailable.". Panel 4 is 'Prayers'; its 3505 is 1477:136, and the open that
        // fills 1477:136 is the `1460 -> 1477:142` line above (panelMount949[142] = 136).
        //
        // This announce used to sit SIX LINES EARLIER, so the client evaluated `if_hassub` on an
        // empty dock, drew the padlock, and never re-ran 8863 - until a window resize forced a
        // relayout, which is exactly what the operator saw. The reference client's order is mount-then-announce:
        // 949-session4 t=8797ms sends `IF_OPENSUB 1458 -> 1477:136` and THEN `8862 [4, 1]`, and
        // every other flag-1 announce in that burst follows its own mount the same way.
        //
        // The second argument is the gate selector: 1 = assert the dock is filled, 0 = consult the
        player.client.write(RunClientScript(script = 8862, args = arrayOf(4, 1)))
 // THE TOOL BELT'S OPEN BUTTON. The reference client arms that strip with FIVE slot ranges
        // (mask 2046: 0..1, 4096..4097, 4352..4353, 4608..4609, 4864..4865) and the button is
        // SLOT 4353. This server armed one range, 0..6, so the slot was unarmed, the client built
        // no menu entry for it and NO PACKET EVER LEFT THE CLIENT - which is why the operator's
        // clicks appear nowhere in the server log while the tooltip still drew (tooltips are the
        // component's own trigger-5 script and need no IF_SETEVENTS). Additive: IF_SETEVENTS is
        // replace-per-range, so the existing 0..6 stands and the four high ranges are added.
 // GUARDED. These two are OPTIONAL panel armings, and an arming that cannot be
        // written must not abort the login path that carries the player's world state. Unguarded
        // is an EmbeddedChannel that deliberately fails every write, and a failed write surfaces on
        // the next flush. A tool that dies reports less than one that fails, so this is the more
        // important half of the fix; the check being green again is the lesser half.
        runCatching { com.opennxt.content.impl.ToolBeltPanel.armOpenButton(player) }
            .onFailure { logger.warn(it) { "could not arm the tool-belt open button for ${player.name}" } }
 // The minimap's "Open World Map" button (1465:11). It sends the CACHE's own
        // optmask (62), so it cannot renumber the ops - the point is only to separate "the click
        // never arrives" from "the click arrives and nothing answers", which is the one unverified
        // step left in the world map: no IF_BUTTON on 1465:11 has ever been seen on THIS server's
        // wire, only on the reference client's.
        runCatching { com.opennxt.content.impl.WorldMapWindow.arm(player) }
            .onFailure { logger.warn(it) { "could not arm the world-map button for ${player.name}" } }
        player.interfaces.open(id = 1881, parent = 1477, component = 153, walkable = true)
        player.interfaces.open(id = 1888, parent = 1477, component = 164, walkable = true)
        player.interfaces.open(id = 1883, parent = 1477, component = 241, walkable = true)
        player.interfaces.open(id = 1449, parent = 1477, component = 252, walkable = true)
        player.interfaces.open(id = 1882, parent = 1477, component = 263, walkable = true)
        player.interfaces.open(id = 1452, parent = 1477, component = 175, walkable = true)

        // THREE MOUNTS REFERENCE SENDS AND THIS SERVER NEVER DID (;
        // the diff was between our own write-site log and the
        // observation's 63 IF_OPENSUBs, so both sides are wire).
        //
        //     994  at 1477:43    t=12911
        //     1449 at 1477:268   t=12786
        //     1882 at 1477:279   t=12787
        //
        // 1449 and 1882 are the LAST TWO RUNGS of the ability ladder. The reference client mounts
        // fourteen panels there on the 11-tile step 136,147,...,268,279; the replay
        // this server grew from carried twelve, so the ladder stopped at 257. The ids
        // ARE opened above at replay seats 252 and 263, but panelRemap949 substitutes
        // different interfaces there, so neither 1449 nor 1882 has ever reached a
        // client from this server.
        //
        // native949 = true because these components are read straight off the reference client's
        // wire, not off the 919 replay: putting them through mountFor() would
 // translate ids that were never replay ids.
        //
        // GATED, and default ON: these are ADDITIONS rather than corrections, so
        // -Dopennxt.experiment.ui.referenceExtraMounts=false takes them back out
        // without disturbing the seven corrected seats.
        if (System.getProperty("opennxt.experiment.ui.referenceExtraMounts") != "false") {
            player.interfaces.open(id = 994, parent = 1477, component = 43, walkable = true, native949 = true)
            player.interfaces.open(id = 1449, parent = 1477, component = 268, walkable = true, native949 = true)
            player.interfaces.open(id = 1882, parent = 1477, component = 279, walkable = true, native949 = true)
        } else {
            logger.info("ui.referenceExtraMounts=false: 994@43, 1449@268 and 1882@279 not opened")
        }
        player.interfaces.events(id = 1461, component = 1, from = 0, to = 211, mask = 10320974)
        player.interfaces.events(id = 1884, component = 1, from = 0, to = 211, mask = 10320974)
        player.interfaces.events(id = 1885, component = 1, from = 0, to = 211, mask = 10320974)
        player.interfaces.events(id = 1887, component = 1, from = 0, to = 211, mask = 10320974)
        player.interfaces.events(id = 1886, component = 1, from = 0, to = 211, mask = 10320974)
        player.interfaces.events(id = 1461, component = 7, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1461, component = 7, from = 7, to = 10, mask = 10319874)
        player.interfaces.events(id = 1460, component = 5, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1460, component = 5, from = 7, to = 10, mask = 10319874)
        player.interfaces.events(id = 1452, component = 7, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1883, component = 7, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1883, component = 7, from = 7, to = 10, mask = 10319874)
        player.interfaces.events(id = 1881, component = 5, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1888, component = 5, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1449, component = 7, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1882, component = 7, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1884, component = 7, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1885, component = 7, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1887, component = 7, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1886, component = 7, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1460, component = 1, from = 0, to = 211, mask = 10320902)
        player.interfaces.events(id = 1881, component = 1, from = 0, to = 211, mask = 10320902)
        player.interfaces.events(id = 1888, component = 1, from = 0, to = 211, mask = 10320902)
        player.interfaces.events(id = 1452, component = 1, from = 0, to = 211, mask = 10320902)
        player.interfaces.events(id = 1883, component = 1, from = 0, to = 211, mask = 10320902)
        player.interfaces.events(id = 1449, component = 1, from = 0, to = 211, mask = 10320902)
        player.interfaces.events(id = 1882, component = 1, from = 0, to = 211, mask = 10320902)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(5, 1)))
        player.interfaces.open(id = 550, parent = 1477, component = 475, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(14, 1)))
        player.interfaces.events(id = 550, component = 7, from = 0, to = 500, mask = 2046)
        player.interfaces.events(id = 550, component = 60, from = 0, to = 500, mask = 6)
        player.interfaces.open(id = 1427, parent = 1477, component = 519, walkable = true)
        player.client.write(ClientSetvarcSmall(1027, 1))
        player.client.write(ClientSetvarcSmall(1034, 2))
        player.interfaces.events(id = 1427, component = 29, from = 0, to = 600, mask = 1040)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(15, 1)))
        player.interfaces.open(id = 1110, parent = 1477, component = 486, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(16, 1)))
        player.interfaces.events(id = 1110, component = 31, from = 0, to = 200, mask = 2)
        player.interfaces.events(id = 1110, component = 85, from = 0, to = 600, mask = 2)
        player.interfaces.events(id = 1110, component = 83, from = 0, to = 600, mask = 1040)
        player.interfaces.events(id = 1110, component = 38, from = 0, to = 600, mask = 1040)
        player.interfaces.open(id = 590, parent = 1477, component = 393, walkable = true)
 // EMOTES ARMING, GENERATED FROM REFERENCE.
        //
        // Replaced `component = 8, from = 0, to = 223` - one flat range on a component the reference client
        // never arms. The reference client arms component 1 once, component 11 once, and component 12 SEVENTY-
        // NINE times, each with from == to and a packed slot of k<<8 for k = 0 then 16..93.
        //
        // The contiguity is what made this safe to transcribe: an account's unlocked emotes would
        // be a subset with holes, and this has none. It is the interface's slot structure, not one
        // player's state.
        //
        // Sent TWICE by the reference client, 68 ms apart, so it appears twice here.
        player.interfaces.events(id = 590, component = 1, from = 0, to = 58, mask = 8388622)
        player.interfaces.events(id = 590, component = 11, from = 0, to = 230, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 0, to = 0, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4096, to = 4096, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4352, to = 4352, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4608, to = 4608, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4864, to = 4864, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5120, to = 5120, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5376, to = 5376, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5632, to = 5632, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5888, to = 5888, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6144, to = 6144, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6400, to = 6400, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6656, to = 6656, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6912, to = 6912, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7168, to = 7168, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7424, to = 7424, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7680, to = 7680, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7936, to = 7936, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8192, to = 8192, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8448, to = 8448, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8704, to = 8704, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8960, to = 8960, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9216, to = 9216, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9472, to = 9472, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9728, to = 9728, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9984, to = 9984, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10240, to = 10240, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10496, to = 10496, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10752, to = 10752, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11008, to = 11008, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11264, to = 11264, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11520, to = 11520, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11776, to = 11776, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12032, to = 12032, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12288, to = 12288, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12544, to = 12544, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12800, to = 12800, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13056, to = 13056, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13312, to = 13312, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13568, to = 13568, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13824, to = 13824, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14080, to = 14080, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14336, to = 14336, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14592, to = 14592, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14848, to = 14848, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15104, to = 15104, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15360, to = 15360, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15616, to = 15616, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15872, to = 15872, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16128, to = 16128, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16384, to = 16384, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16640, to = 16640, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16896, to = 16896, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17152, to = 17152, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17408, to = 17408, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17664, to = 17664, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17920, to = 17920, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18176, to = 18176, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18432, to = 18432, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18688, to = 18688, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18944, to = 18944, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19200, to = 19200, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19456, to = 19456, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19712, to = 19712, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19968, to = 19968, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20224, to = 20224, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20480, to = 20480, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20736, to = 20736, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20992, to = 20992, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21248, to = 21248, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21504, to = 21504, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21760, to = 21760, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22016, to = 22016, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22272, to = 22272, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22528, to = 22528, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22784, to = 22784, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23040, to = 23040, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23296, to = 23296, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23552, to = 23552, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23808, to = 23808, mask = 8388614)
        // The second the reference client burst - see the note on the first block.
        player.interfaces.events(id = 590, component = 1, from = 0, to = 58, mask = 8388622)
        player.interfaces.events(id = 590, component = 11, from = 0, to = 230, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 0, to = 0, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4096, to = 4096, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4352, to = 4352, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4608, to = 4608, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4864, to = 4864, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5120, to = 5120, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5376, to = 5376, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5632, to = 5632, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5888, to = 5888, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6144, to = 6144, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6400, to = 6400, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6656, to = 6656, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6912, to = 6912, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7168, to = 7168, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7424, to = 7424, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7680, to = 7680, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7936, to = 7936, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8192, to = 8192, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8448, to = 8448, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8704, to = 8704, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8960, to = 8960, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9216, to = 9216, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9472, to = 9472, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9728, to = 9728, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9984, to = 9984, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10240, to = 10240, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10496, to = 10496, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10752, to = 10752, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11008, to = 11008, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11264, to = 11264, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11520, to = 11520, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11776, to = 11776, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12032, to = 12032, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12288, to = 12288, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12544, to = 12544, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12800, to = 12800, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13056, to = 13056, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13312, to = 13312, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13568, to = 13568, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13824, to = 13824, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14080, to = 14080, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14336, to = 14336, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14592, to = 14592, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14848, to = 14848, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15104, to = 15104, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15360, to = 15360, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15616, to = 15616, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15872, to = 15872, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16128, to = 16128, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16384, to = 16384, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16640, to = 16640, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16896, to = 16896, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17152, to = 17152, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17408, to = 17408, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17664, to = 17664, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17920, to = 17920, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18176, to = 18176, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18432, to = 18432, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18688, to = 18688, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18944, to = 18944, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19200, to = 19200, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19456, to = 19456, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19712, to = 19712, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19968, to = 19968, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20224, to = 20224, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20480, to = 20480, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20736, to = 20736, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20992, to = 20992, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21248, to = 21248, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21504, to = 21504, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21760, to = 21760, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22016, to = 22016, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22272, to = 22272, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22528, to = 22528, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22784, to = 22784, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23040, to = 23040, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23296, to = 23296, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23552, to = 23552, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23808, to = 23808, mask = 8388614)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(9, 1)))
        player.interfaces.open(id = 1416, parent = 1477, component = 295, walkable = true)
        player.interfaces.events(id = 1416, component = 3, from = 0, to = 3087, mask = 62)
        player.interfaces.events(id = 1416, component = 11, from = 0, to = 99, mask = 2359334)
        player.interfaces.events(id = 1416, component = 11, from = 100, to = 199, mask = 4)
        player.interfaces.events(id = 1416, component = 11, from = 200, to = 200, mask = 2097152)
        player.interfaces.text(id = 1416, component = 6, text = "Adventure")
        player.client.write(RunClientScript(script = 8862, args = arrayOf(10, 1)))
        player.client.write(ClientSetvarcSmall(3497, 0))
        player.interfaces.open(id = 1417, parent = 1477, component = 508, walkable = true)
 // NOTES ARMING, GENERATED FROM REFERENCE.
        //
        // Replaced `component = 13, from = 0, to = 29` - again a flat range on the wrong
        // component. The reference client arms component 9 thirty times, from == to, slot k<<8 for k = 0 then
        // 16..44 with no gaps.
        //
        // The reference client ALSO sends three component-13 rows - but at t=22.3 s, five seconds after the
        // login burst ends, in response to something the player did. They are deliberately NOT
        // here; tools/949/emit_login_mounts.py now bounds the burst so they cannot creep in.
        player.interfaces.events(id = 1417, component = 9, from = 0, to = 0, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 4096, to = 4096, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 4352, to = 4352, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 4608, to = 4608, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 4864, to = 4864, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 5120, to = 5120, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 5376, to = 5376, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 5632, to = 5632, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 5888, to = 5888, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 6144, to = 6144, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 6400, to = 6400, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 6656, to = 6656, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 6912, to = 6912, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 7168, to = 7168, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 7424, to = 7424, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 7680, to = 7680, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 7936, to = 7936, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 8192, to = 8192, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 8448, to = 8448, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 8704, to = 8704, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 8960, to = 8960, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 9216, to = 9216, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 9472, to = 9472, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 9728, to = 9728, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 9984, to = 9984, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 10240, to = 10240, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 10496, to = 10496, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 10752, to = 10752, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 11008, to = 11008, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 11264, to = 11264, mask = 2621470)
        player.interfaces.text(id = 1417, component = 5, text = "Loading notes<br>Please wait...")
        player.interfaces.hide(id = 1417, component = 5, hidden = false)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(11, 1)))
        player.client.write(RunClientScript(script = 8862, args = arrayOf(12, 0)))
        player.interfaces.open(id = 1519, parent = 1477, component = 497, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(27, 1)))
        player.interfaces.open(id = 1588, parent = 1477, component = 327, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(28, 1)))
        player.interfaces.open(id = 1678, parent = 1477, component = 338, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(29, 1)))
        player.client.write(RunClientScript(script = 8862, args = arrayOf(30, 0)))
        player.interfaces.open(id = 190, parent = 1477, component = 360, walkable = true)
        player.interfaces.events(id = 190, component = 5, from = 0, to = 312, mask = 14)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(31, 1)))
        player.interfaces.open(id = 1854, parent = 1477, component = 371, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(32, 1)))
        player.interfaces.events(id = 1854, component = 6, from = 0, to = 4, mask = 66)
        player.interfaces.events(id = 1854, component = 5, from = 0, to = 1, mask = 2)
        player.interfaces.open(id = 1894, parent = 1477, component = 382, walkable = true)
        player.interfaces.events(id = 1894, component = 16, from = 0, to = 2, mask = 2)
        player.interfaces.events(id = 1894, component = 18, from = 0, to = 3, mask = 6)
        player.interfaces.events(id = 1894, component = 19, from = 0, to = 3, mask = 6)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(41, 1)))

 //: the HUD block below (1431, 1465, 1919, 1430, 1670, 1671,
        // 1673, 291, 1639) is re-seated by InterfaceManager.hudMountFor() - the
        // replay's components are one panel over on 949. Logged here so a run
        // can prove which build and which mode it was. See the table's KDoc.
        run {
            val t = InterfaceManager.hudMountTable()
            logger.info {
                "hudMount[${InterfaceManager.hudMountMode}] rows=${InterfaceManager.hudRowsEnabled}: ${t.size} HUD-block replay component(s) re-seated -> " +
                    t.entries.joinToString { "${it.key}->${it.value}" } +
                    " (-Dopennxt.experiment.hudMount=slot|dock|off, -Dopennxt.experiment.hudMount.rows=minimap,debuff,slayer,ribbon,mainbar,bars|all)"
            }
        }
        player.interfaces.open(id = 1431, parent = 1477, component = 59, walkable = true)
 // experiment: the replay announces every ADDITIONAL bar with
        // RunClientScript(8310, [panel]) - 8310 is `gosub 3379(panel, 1, 1)`, the
        // client's generic panel-show routine - and never announces the ribbon
        // (panel 1002) or the main bar (1003). 1430 draws nothing in its own
        // dock 70 while 1670 draws there, so the missing announcement is the
        // first suspect. Sent only when the row is re-seated (so `rows` stays
        // the single variable) and `-Dopennxt.experiment.hudMount.announce`
        // is not "false".
        if ("ribbon" in InterfaceManager.hudRowsEnabled && InterfaceManager.hudAnnounce) {
            player.client.write(RunClientScript(script = 8310, args = arrayOf(1002)))
            logger.info { "hudMount: announced the ribbon with RunClientScript(8310, [1002])" }
        }
        // 568 is the Ribbon-Setup popup (2400 positions 1477:690 and addresses 568:5/6); it is not a HUD panel.
 //from two fresh reference logins: this line had BOTH halves wrong.
        // The reference client mounts interface 568 at 1477:691, and 1477:638 is where it puts a
        // DIFFERENT interface, 598. This server was opening 568 on 598's seat. Both
        // sessions agree that 568 belongs at 691; only the higher-state account opened
        // 598 at all, so 598 is sent under the same popups gate and no further claim is
        // made about when it applies.
        if ("popups" !in InterfaceManager.hudRowsEnabled) {
            player.interfaces.open(id = 568, parent = 1477, component = 691, walkable = true, native949 = true)
            player.interfaces.open(id = 598, parent = 1477, component = 638, walkable = true, native949 = true)
        } else logger.info { "hudMount popups: not opening 568 at 1477:691 or 598 at 1477:638" }
        player.interfaces.events(id = 1477, component = 60, from = 1, to = 1, mask = 2)
        player.interfaces.events(id = 1431, component = 0, from = 0, to = 46, mask = 6)
        player.interfaces.events(id = 568, component = 5, from = 0, to = 46, mask = 6)
        player.interfaces.open(id = 1465, parent = 1477, component = 90, walkable = true)
        player.interfaces.open(id = 1919, parent = 1477, component = 91, walkable = true)
        player.interfaces.events(id = 1477, component = 94, from = 1, to = 1, mask = 6)
        // 634 is Premier Pass, a Central Interface window (the replay's own 8420 names panel 1007); not a HUD panel.
        if ("popups" !in InterfaceManager.hudRowsEnabled) player.interfaces.open(id = 634, parent = 1477, component = 739, walkable = true)
        else logger.info { "hudMount popups: not opening 634 (Premier Pass) at 1477:739" }
        player.client.write(RunClientScript(script = 11145, args = arrayOf(1067, 600, 0, 0, 96797408)))
        player.client.write(RunClientScript(script = 8420, args = arrayOf(-1, 96797410, 96797411, -1, "", 21259, 1007)))
        player.interfaces.hide(id = 634, component = 259, hidden = true)
        player.interfaces.hide(id = 634, component = 0, hidden = false)
        player.interfaces.events(id = 634, component = 152, from = 65535, to = 65535, mask = 1022)
        player.interfaces.events(id = 634, component = 265, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 67, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 68, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 138, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 139, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 2, from = 65535, to = 65535, mask = 2)
        // Interface 653 is the Travelling Artisan crafting panel. It is in this
        // login sequence only because the sequence is a REPLAYED RECORDING of a
        // real client session and that panel happened to be open when the
        // observation was taken. It is not part of the gameframe.
        //
        // Opened, it covers most of the screen and cannot be dismissed: the
        // event masks below are set on components 155-254, which are the recipe
        // rows, and never on the panel's close button -- so the X in its corner
        // sends nothing and the player is stuck behind it.
        //
        // DEFAULT OFF. -Dopennxt.experiment.panel653=true replays the protocol
        // verbatim, for anyone diffing this login against the recording.
        if (System.getProperty("opennxt.experiment.panel653") == "true") {
 // COMPONENT CORRECTED 744 -> 797.
            //
            // The reference client mounts 653 on 1477:797. This server was sending 744, and 744 is not a
            // remap key - not in panelMount949, no hudRow with from=744 - so it went out
            // verbatim and the panel was mounted where the client does not look for it.
            //
            // TWICE, on two different sessions on two different days:
            //   949-session4.jsonl                          653 -> 1477:797
 // 653 -> 1477:797
            // zero disagreements, so it is not an observation whose readings are in doubt.
            //
            // THIS IS THE SAME DEFECT CLASS AS THE MINIMAP. 1465 was mounted at 1477:94
            // instead of :95 and never painted, and a long investigation went looking at the
            // draw path, clientscript 9903 and native render state before the seat turned out
            // to be the whole story.
            //
            // WHAT THIS DOES NOT CLAIM. The comment above says this panel "covers most of the
            // screen and cannot be dismissed", which is why it is default OFF. A correct seat
            // may or may not change that - the events below are still set on the recipe rows
            // and never on the close button, which is a separate defect and is untouched here.
            // Nothing about the stuck-panel behaviour is fixed by this line.
            player.interfaces.open(id = 653, parent = 1477, component = 797, walkable = true)
            player.client.write(RunClientScript(script = 11145, args = arrayOf(1067, 600, 0, 0, 96797413)))
            player.client.write(RunClientScript(script = 8420, args = arrayOf(-1, 96797415, 96797416, -1, "", 21259, 1007)))
            player.interfaces.hide(id = 653, component = 71, hidden = true)
            player.interfaces.hide(id = 653, component = 0, hidden = false)
            player.interfaces.events(id = 653, component = 155, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 166, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 177, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 188, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 199, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 210, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 221, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 232, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 243, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 254, from = 0, to = 500, mask = 62)
        } else {
            logger.info {
                "interface 653 (Travelling Artisan) NOT opened -- artefact of the replayed " +
                    "login observation. It covers the screen and its close button has no event " +
                    "mask, so it cannot be shut. -Dopennxt.experiment.panel653=true replays it."
            }
        }
        player.interfaces.open(id = 1430, parent = 1477, component = 65, walkable = true)
        if ("mainbar" in InterfaceManager.hudRowsEnabled && InterfaceManager.hudAnnounce) {
            player.client.write(RunClientScript(script = 8310, args = arrayOf(1003)))
            logger.info { "hudMount: announced the main action bar with RunClientScript(8310, [1003])" }
        }
 // REVIEW (REVIEW-A 2/3c, REVIEW-B rank 1): these three 8310 = 3379(panel,1,1) shows were
        // UNCONDITIONAL replay residue - a TOGGLE (hides a shown panel) and a layout-store SNAPSHOT
        // (UI-C1 Q5) on every login, even when hudMount.announce=false. Gated like the other two.
        // ------------------------------------------------------------------------------------
 // THE FOUR ADDITIONAL ACTION BARS ARE NOW THE PLAYER'S CHOICE.
        //
        // What stood here opened all four unconditionally, at every login, for every account.
 // The the protocol says that is wrong twice over:
        // opening Edit Layout Mode pushes varps 10092..10095 = 0, and ticking Additional Action
        // Bar 1 sends varp 10092 = 1 with IF_OPENSUB 1430->1477:70 and 1670->1477:75. A fresh
        // the reference client account has NO additional bars and asks for them one at a time.
        //
        // The bug the operator hit follows directly: unticking a bar looked like it worked (the
        // client hid an interface it already had, with no server involvement) and ticking one
        // did nothing at all (only the server can open it, and nothing was listening). Both ends
        // are now [PanelToggles]', which also owns the varp so the choice survives a logout.
        //
        // THE SEATS ARE UNCHANGED and are still the ones the block below used to reach through
        // InterfaceManager.hudRows: 1670@75, 1671@80, 1672@85, 1673@90. PanelToggles names those
        // docks directly (native949) instead of the 70/75/80/85 replay seats, because it also
        // has to CLOSE them, and close takes the real component.
        //
        // The 8310 announcements move with their opens - announcing a bar that is not open was
 // the "TOGGLE / layout-store snapshot" residue the review already flagged
        // here, and the conditional makes that concern moot rather than gated.
        // ------------------------------------------------------------------------------------
        com.opennxt.content.impl.PanelToggles.sendLoginState(player)
        player.interfaces.events(id = 1477, component = 66, from = 1, to = 1, mask = 4)
 // THE MAIN ACTION BAR'S ARMING, GENERATED FROM REFERENCE.
        //
        // Replaced 36 hand-written rows that diverged from the reference client two ways at once, on the
        // interface the player has open at all times:
        //
        // * DRIFTED COMPONENTS - ours armed 11/16/22/64 where the reference client arms 13/18/26/66, and the
        //     drift GREW along the bar rather than being a constant offset, so no single shift
        //     would have corrected it;
        //   * NARROWED MASKS - ours sent 2098176 (bits 10 and 21 - two interactions) for the ability
        // slots where the reference client sends 11239422 (bits 1-14, 16, 17, 19, 21, 23 - nineteen). Most
        //     right-click options and drags on the action bar could not have worked, and nothing
        //     anywhere would have said why.
        //
        // WIRE ORDER IS PRESERVED AND MATTERS: the burst ends with component 38 twice, mask 0 then
        // mask 2 - a clear-then-set. Sorting these rows would leave it on 0 and silently disarm it,
        //
        // SENT TWICE, also from the reference client: two identical 39-frame bursts 96 ms apart. The second block
        // lower in this method is the other one, not a leftover.
        //
        // data/config/login-mounts.tsv. Regenerate both from a new observation with
        // tools/949/emit_login_mounts.py; do not hand-edit these rows.
        player.interfaces.events(id = 1430, component = 66, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 69, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 79, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 82, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 92, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 95, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 105, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 108, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 118, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 121, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 131, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 134, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 144, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 147, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 157, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 160, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 170, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 173, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 183, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 186, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 196, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 199, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 209, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 212, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 222, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 225, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 235, from = 65535, to = 65535, mask = 2098176)
        player.interfaces.events(id = 1430, component = 238, from = 65535, to = 65535, mask = 2098176)
        player.interfaces.events(id = 1430, component = 19, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1430, component = 13, from = 65535, to = 65535, mask = 8650758)
        player.interfaces.events(id = 1430, component = 26, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1430, component = 18, from = 65535, to = 65535, mask = 2046)
        player.interfaces.events(id = 1430, component = 59, from = 65535, to = 65535, mask = 8650754)
        player.interfaces.events(id = 1430, component = 261, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 260, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 259, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 258, from = 65535, to = 65535, mask = 448)
        player.interfaces.events(id = 1430, component = 38, from = 65535, to = 65535, mask = 0)
        player.interfaces.events(id = 1430, component = 38, from = 65535, to = 65535, mask = 2)
        // ------------------------------------------------------------------------------------
 // THE 168 STALE ARMING LINES FOR 1670/1671/1673 WERE DELETED HERE.
        //
        // They armed 56 components each, unconditionally, hand-written from the 919 replay. Two
        // things made them wrong rather than merely redundant:
        //
        //   * they fired for bars the player has switched OFF - the opens became conditional
        //     (PanelToggles, from PlayerSave.varps) and the arming was left behind, so a player
        //     with no additional bars still got 168 IF_SETEVENTS for three closed interfaces;
        //   * where a bar WAS open they duplicated it with a DIFFERENT set - 56 hand-written rows
        // against the reference client's 28, which data/config/action-bar-arm.tsv carries and ActionBarArm
        // replays. Two arming sets for one interface, one of them not the reference client's.
        //
        // 1672 never had any of these (the "bar this server never opened"), so the four bars were
        // not even armed alike. The table gives all four the same 28 rows the reference client sends.
        //
        // should go through ActionBarArm.arm, which is generated.
        // ------------------------------------------------------------------------------------
        player.interfaces.events(id = 1458, component = 39, from = 0, to = 38, mask = 8388610)
        player.interfaces.events(id = 1465, component = 15, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1460, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1881, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1888, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1452, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1461, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1884, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1885, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1887, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1886, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1883, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1449, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1882, component = 1, from = 0, to = 211, mask = 8616966)
        player.client.write(ClientSetvarcSmall(1436, 1))
        player.interfaces.open(id = 1433, parent = 1477, component = optionsMenuSlot, walkable = true)
        if (optionsMenuSlot == 805) {
            logger.info("options menu: interface 1433 mounted at 1477:805 - the seat the reference client uses " +
                "(949-session4.jsonl t=12895). ESC opens and closes it.")
        } else {
            logger.warn("options menu: interface 1433 mounted at 1477:$optionsMenuSlot, NOT the " +
                "confirmed 1477:808. 1477:751 is the historical broken value - it sits in the " +
                "DIALOGUE panel's subtree, where 8177/8179's if_sethide on 1477:805 cannot reach " +
                "it, so ESC will open an empty container. See the KDoc on optionsMenuSlot.")
        }
        player.interfaces.events(id = 1433, component = 6, from = 0, to = 6, mask = 2)
        // ==================================================================
        // EXPERIMENT: which interface belongs on the All Chat mount.
        //
        //   -Dopennxt.experiment.ui.chat1923=true      (DEFAULT OFF)
        //
        // The 919 replay opens 137 here, and InterfaceManager remaps the mount
        // 404 -> 420 ("All Chat panel 18"). Mining the 949 clientscripts for
        // constants that pair a 1477 component with an interface id disagrees:
        // it gives 1477:420 -> 1923, unambiguously (one candidate).
        //
        // That miner is a CANDIDATE GENERATOR, not a resolver - on the one pair
        // proven live (1477:808 -> 274) it returns [274, 1433] and cannot pick
        // between them. It sees only 24 of 1477's 923 components. So this is a
        // lead, not a finding, and the client is the thing that decides.
        //
        // Worth noting either way: 1477:420 is 4x4 base, which is a small box
        // to hang a 263-component chat interface on. 1923 has 75.
        if (System.getProperty("opennxt.experiment.ui.chat1923") == "true") {
            logger.info("chat-panel experiment: opening 1923 (not 137) on the All Chat mount")
            player.interfaces.open(id = 1923, parent = 1477, component = 404, walkable = true)
        } else {
            player.interfaces.open(id = 137, parent = 1477, component = 404, walkable = true)
        }
        player.interfaces.events(id = 137, component = 86, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 137, component = 62, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 137, component = 65, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 137, component = 59, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1467, parent = 1477, component = 415, walkable = true)
        player.interfaces.events(id = 1467, component = 192, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1467, component = 180, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1467, component = 183, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1467, component = 185, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1472, parent = 1477, component = 425, walkable = true)
        player.interfaces.events(id = 1472, component = 193, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1472, component = 187, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1472, component = 190, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1472, component = 192, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1471, parent = 1477, component = 435, walkable = true)
        player.interfaces.events(id = 1471, component = 193, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1471, component = 181, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1471, component = 184, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1471, component = 186, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1470, parent = 1477, component = 445, walkable = true)
        player.interfaces.events(id = 1470, component = 193, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1470, component = 181, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1470, component = 184, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1470, component = 186, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 464, parent = 1477, component = 455, walkable = true)
        player.interfaces.events(id = 464, component = 193, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 464, component = 181, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 464, component = 184, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 464, component = 186, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1529, parent = 1477, component = 465, walkable = true)
        player.interfaces.events(id = 1529, component = 192, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1529, component = 180, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1529, component = 183, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1529, component = 185, from = 0, to = 2, mask = 2)
 //: `open(InterfaceSlot.EVENTS, 1465, walkable = true)` REMOVED
        // from here. `added()` opened 1465 TWICE - hard-coded at 1477:90 on line
        // 945, and again here through InterfaceSlot.EVENTS, which resolves to
        // 1477:590. Unlike the 1919 duplicate below these are DIFFERENT
        // components, so both stayed open and neither warned.
        //
        // TWO INDEPENDENT LINES OF EVIDENCE that the second is spurious:
        //   * the build-946 OpenNXT fork (github.com/vgcman16/OpenNXT) opens
        //     1465 EXACTLY ONCE, at component 90, in its own
        //     openForcedFallbackSceneStartBridge(). Component 590 appears
        //     nowhere in its WorldPlayer.
        //   * with -Dopennxt.experiment.backpackGate=false the gameframe began
        //     drawing, and TWO independently draggable action bars appeared on
        //     screen where there should be one. Both are ~530x35 and both
        //     reorient horizontal/vertical, which is action-bar behaviour.
        //
        // FALSIFIABLE: if one bar disappears, this was the extra. If both
        // remain, this removal is wrong and should be reverted - the hypothesis
        // is not that 590 is a bad component, only that opening 1465 there IS
        // NOT SOMETHING THE CLIENT ASKED FOR.
        //
        // Note the upstream diff (protocol/UPSTREAM-UI-DIFF.md) lists this as
        // one of only three mount relocations separating this repository from the
        // proxy transcript - and separately establishes that upstream's UI was
        // NEVER SHOWN TO WORK, so "upstream does not do this" is corroboration
        // and not proof.
 //: the duplicate `open(1919, 1477, 91)` that sat here is
        // REMOVED. `added()` opened it TWICE - line 946 and again here - and the
        // two calls were BYTE-IDENTICAL, so the second sent a redundant
        // IF_OPENSUB and produced, on every login:
        //     WARN Overriding an interface on 1477:91 with interface 1919
        // Measured on server-20260828-085751.log, opens #41 and #56. Nothing
        // between the two touches 1477:91 - the only `component = 91` lines in
        // that span are events() on interfaces 1671 and 1673, which are THOSE
        // interfaces' own component 91, not the gameframe's - so removing this
        // one is behaviour-preserving and 1919 stays open from line 946.
        //
        // NOT a duplicate and NOT touched: 1465 opens at 1477:90 (line 945,
        // hard-coded) AND at 1477:590 (the line above, via InterfaceSlot.EVENTS
        // = panel 1037). Different components, so both stay open and neither
 // warns. Which of the two is correct is the unresolved
        // hard-coded-versus-slot question recorded as case 8 in
        // CONTRADICTED. It is not settled here.
        player.interfaces.events(id = 1477, component = 94, from = 1, to = 1, mask = 6)
        player.interfaces.open(InterfaceSlot.MINIMAP, 1484, walkable = true)
        player.interfaces.hide(id = 1477, component = 593, hidden = false)
 // REVIEW (UI-A6 8, REVIEW-B rank 7): 1483 = gravestone_timer ("Go to grave") is only a
        // sensible shell while DEAD (varbits 52409-52412, never sent). Opt-in: -Dopennxt.experiment.ui.gravestone=true.
        if (System.getProperty("opennxt.experiment.ui.gravestone") == "true") {
            player.interfaces.open(id = 1483, parent = 1477, component = 576, walkable = true)
        }
        player.interfaces.open(id = 745, parent = 1477, component = 589, walkable = true)
        player.interfaces.open(id = 284, parent = 1477, component = 572, walkable = true)
        player.interfaces.open(id = 1213, parent = 1477, component = 619, walkable = true)
        // 662 -> 715. The 919 replay put interface 1448 on 1477:662, which in 949 is
        // struct param 3503 of enum-7716 panel 1025 ("Clock", struct 29982) - a
        // DIFFERENT PANEL, and its authored-hidden 173x114 container at that.
        //
        // The client names the right slot outright. Clientscript 12293, decoded from
        // the served cache (949 table, 21,037/21,110 exact):
        //
        //     2  op 1335  96797387     ; 1477:715
        //     3  op 1335  1448
        //     4  op 1198  0            ; if_hassub_id(1477:715, 1448)
        //     ... then if_hassub_id(1448:3 / 1448:5 / 1448:7 / 1448:9 / 1448:11, arg)
        //
        // That is a LITERAL (component, interface) pair, not a param read, and it is
        // the ONLY literal `if_hassub_id` site in the whole corpus that names 1448.
        // 1477:715 is struct param 3505 of panel 1001 ("Management Windows", struct
        // 21301) - the one param the client ever feeds to `if_hassub` (9 sites to 0
        // for every other panel param). Three further agreements, all re-derived:
        //
        //   * 1448 really has components 3/5/7/9/11 as its five tab layers, widths
        //     110/98/94/101/101 at height 300 - exactly the five 12293 interrogates.
        //   * 1477:662 is 173x114 absolute with flags=1 - AUTHORED HIDDEN, the same
        //     state BACKPACK-VISIBILITY.md pins on 1477:101. A 300px-tall tab strip
        //     neither fits the clock box nor could ever draw inside it. The chain
        //     from 1477:715, by contrast, is unhidden the whole way up:
        //         715 (flags 0) -> 708 (flags 0, 800x600) -> 707 (0) -> 27 (0) -> root
        //     against
        //         662 (flags 1) -> 565 (0) -> 27 (0) -> root
        //     So this move takes 1448 out of a hidden subtree and into a visible one.
        //     IN-GAME CONSEQUENCE TO WATCH FOR: 1448 may now actually DRAW. If a
        //     ~5-column strip appears where nothing was, that is this change, and it
        //     is the interface arriving where the client says it lives - not a
        //     regression. If nothing appears, that is consistent with the global
        //     render fault the backpack work is chasing and says nothing either way.
        //   * the independent TopLevelOverlay reference table's row
        //     `toplevel_v2_parent -> toplevel_v2_parent_window_tabs` resolves to the
        //     same 1477:715.
        //
        // interface id. Nothing else in this server opens anything at 1477:715, and
        // moving 1448 off 1477:662 leaves the Clock panel exactly as it was: nothing
        // has ever been opened at the Clock's own mount (1477:664, its param 3505).
        //
        // NOT A CONTROL, stated so it is not re-derived: `RunClientScript(8862, [P,1])`
        // cannot discriminate this. 8863 reads struct param 3513 FIRST and returns at
        // instruction 11 when it is -1, and the Clock's struct declares no 3513 at all
        // (49 of the 101 panels do; the Clock and the Slayer Counter, the only two
        // non-dockable panels this server 8862s, are not among them). So the padlock
        // path never runs for this panel either before or after this change.
        // See protocol/OFFSLOT-MOUNTS.md.
        player.interfaces.open(id = 1448, parent = 1477, component = 715, walkable = true)
 // SUBOVERLAY ROUTE (interface_names_949.sym): the v2 HUD hosts the same content two ways -
        // as a WINDOW (toplevel_v2_inventory 1473, what we open at 1477:103) or inside toplevel_v2_parent
        // 1448's five tab layers (components 3/5/7/9/11) as toplevel_v2_parent_suboverlay_* (1474 inventory,
        // 1462 worn, 1436 action_bar, 1457 prayer). The grid builder 8678 serves BOTH hosts (1473:0 / 1474:3,
        // UI-A1). -Dopennxt.experiment.ui.suboverlay=inv|worn|bar|prayer (comma list) opens the suboverlay
        // variant(s) into 1448's first layers - the discriminator for "is the empty grid a window problem".
        System.getProperty("opennxt.experiment.ui.suboverlay")?.let { raw ->
            val map = mapOf("inv" to 1474, "worn" to 1462, "bar" to 1436, "prayer" to 1457)
            val layers = intArrayOf(3, 5, 7, 9, 11)
            raw.split(',').map { it.trim().lowercase() }.filter { it in map }.forEachIndexed { i, key ->
                if (i < layers.size) {
                    player.interfaces.open(id = map.getValue(key), parent = 1448, component = layers[i], walkable = true, native949 = true)
                    logger.warn { "ui.suboverlay: opened ${map.getValue(key)} (suboverlay $key) into 1448:${layers[i]} - READ THE SCREEN: content in the parent's tab layer = the window variant is the mis-packed one" }
                }
            }
        }
        player.interfaces.open(id = 291, parent = 1477, component = 568, walkable = true)
        player.client.write(ClientSetvarcSmall(2834, 1))
        player.interfaces.events(id = 1477, component = 22, from = 65535, to = 65535, mask = 2097152)
        player.client.write(RunClientScript(script = 139, args = arrayOf(96796695)))
        player.interfaces.open(id = 1488, parent = 1477, component = 760, walkable = true)
        player.interfaces.open(id = 1680, parent = 1477, component = 39, walkable = true)
        player.client.write(RunClientScript(script = 14150, args = arrayOf(5)))
        player.interfaces.events(id = 1477, component = 15, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 1477, component = 15, from = 0, to = 41, mask = 2)
        player.interfaces.events(id = 1477, component = 840, from = 0, to = 1000, mask = 2)
 // 856 -> 912: REFERENCE (949-session4.jsonl, t=12912).
        player.interfaces.open(id = 1847, parent = 1477, component = 912, walkable = true)
        player.interfaces.events(id = 1477, component = 912, from = 0, to = 0, mask = 2)
        // 635 is "Undead Army", a Necromancy Central Interface window (635:0 8420[...,1007]); it sat on the Buff Bar slot.
        if ("popups" !in InterfaceManager.hudRowsEnabled) player.interfaces.open(id = 635, parent = 1477, component = 615, walkable = true)
        else logger.info { "hudMount popups: not opening 635 (Undead Army window) at 1477:615" }
 // FLAG 1 -> 0. Flag 1 tells clientscript 8863 to ASSERT the panel's dock is
        // filled; panel 1025's dock is 1477:664 and this server never mounts it, so the assert
        // failed and drew the same "currently unavailable." padlock the Prayers panel had. The reference client
        // sends `[1025, 0]` in the same login burst - flag 0 consults the per-panel lock instead of
        // the dock. The alternative fix is to mount 1234 -> 1477:664; the reference client does not, so nor do we.
        player.client.write(RunClientScript(script = 8862, args = arrayOf(1025, 0)))
        player.client.write(RunClientScript(script = 2651, args = arrayOf(1025, 0)))
        player.client.write(RunClientScript(script = 7486, args = arrayOf(27066179, 41615368)))
        player.client.write(RunClientScript(script = 10903))
        player.interfaces.open(id = 1639, parent = 1477, component = 627, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(1031, 1)))
        player.client.write(RunClientScript(script = 2651, args = arrayOf(1031, 0)))
        player.client.write(ClientSetvarcSmall(1413, 1))
        player.client.write(RunClientScript(script = 8778))

        // ---- EXPERIMENT: panels the 919 replay never fills ------------------
        //
        //   -Dopennxt.experiment.ui.extraPanels=confirmed      1 panel
        //   -Dopennxt.experiment.ui.extraPanels=strong         9 panels
        //   -Dopennxt.experiment.ui.extraPanels=674,693,703    an explicit list
        //
        // Default OFF. `prot949.py panels --diff` reports 61 of the 949
        // gameframe's 104 panels with nothing ever opened on their mount, which
        // is why the operator's UI renders as structure without art. These are
        // the ones now resolved. Full table and per-row evidence:
        // tools/949/panel_interfaces.tsv.
        //
        // PROVENANCE. Three cache-side sources, all decoded out of js5-12 with
        // tools/949/cache949.py + cs2.py (enum 7716 has ZERO rows in
        // rs3.sqlite, which is why none of this was resolvable before):
        //   * clientscript 2141, a 58-case switch on panel id;
        //   * `if_hassub_id` assertions - the client stating a pairing outright.
        //     TWO opcodes carry it, 2184 and 1198 (128 sites); the second was
        //     missed on the first pass. That is where Management Windows = 1448
        //     comes from, literally `if_hassub_id(1477:715, 1448)`;
        //   * the 187 scripts that resolve a panel id through `enum(7716, n)`.
        //     Three `8310` calls sit immediately after `open(1670, .70)`,
        //     `open(1671, .75)`, `open(1673, .85)` and name panels 1032/1033/
        //     1035 - so the four action bars are distinct interfaces, not 1430
        //     four times, which was never physically possible.
        //
        // WHAT THIS IS *NOT*. An earlier reading of 2141 concluded the server
        // was wrong on twelve ability panels and was opening two interfaces
        // (1881, 1888) that have no components in this cache. **Both halves are
        // refuted.** open() logged the PRE-remap interface id beside the
        // POST-remap component, and two passes read that pair as real. The
        // remap substitutes 1460/1452 for 1881/1888 before the existence guard
        // ever sees them. Composing panelRemap949 with panelMount949 and
        // comparing the RESULT against 2141 gives 9 AGREE, 0 DISAGREE. The two
        // panels below are what actually survived that review.
        //
 // CONTROLS, because this server has been wrong three times with
        // methods that looked sound. The control is NOT the 43 live pairings -
        // for the ability family those inherit their label from
        // InterfaceManager.panelMount949, i.e. from the thing under test. It is
        // the 919 replay's own 8862 adjacency, extracted mechanically. The block
        // rule scores **7/7** on every pin it can answer. Negative control:
        // shuffling case->block assignment over 200 trials gives mean 0.17
        // correct, max 2 - it never reaches 7.
        //
        // TIERS ARE NOT DECORATION. `confirmed` = two independent sources agree.
        // (Main Action Bar, Ribbon, the four Additional Action Bars, Familiar,
        // Player Inspect, Twitch Stream) come from 2141's delegate rule, which
        // got 2 of its 4 testable answers WRONG, so they are deliberately NOT
        // reachable by name - list their components explicitly if you want them.
        //
        // TREAT A CRASH AS EXPECTED. `ui.slotBatch` crashed the client at
        // and cost a session. Bisect the list; do not reach for a
        // debugger first.
        run {
 // Regenerated from tools/949/panel_interfaces.tsv.
            // CHANGED SINCE THE FIRST CUT, and both changes are withdrawals:
            //   Minigames / Create Group 290 -> 522  REMOVED. 522 is the
            //     bestiary ("Monster name", "Base Hit Chance (Melee)"). It came
            //     from 2141 case 17, which is 114 instructions of if_hassub_id
            //     dispatch rather than setup code, so its lone component hash
            //     was never that panel's occupant.
            //   Clock 662 -> 1448 REMOVED. 1448 is claimed exactly once in the
            //     whole 21110-script corpus, and for 1477:715 (Management
            //     Windows), not for the Clock.
 // evening by the interfaces' own onloads (8420/3421 naming the panel),
            // mounted at the panel's 3505 dock (3934 resolves occupants by walking to param 3505):
            val confirmed = listOf(
                674 to 1639,   // Slayer Counter (8862 adjacency + 2141)
                592 to 1756,   // Events            1756:1 3421[...,1037]; 116x75 = struct 33448 params 3499/3500
                609 to 636,    // Gravestone Items  636:3 8420[...,"Gravestone",21217,1013]
                576 to 268,    // Combat Mode Icon  268:0 3421[...,1049]; ops "Combat Mode: Full Manual / Revolution"
                588 to 942,    // Dungeoneering Map 942:1 3421[...,1041]; root 280x280 = param 3500
                664 to 1234,   // Clock             1234:0 3421[...,1025]; op "Open Calendar"
            )
            val strong = confirmed + listOf(
                61 to 1431,    // Ribbon
                67 to 1430,    // Main Action Bar
                72 to 1670,    // Additional Action Bar 1
                77 to 1671,    // Additional Action Bar 2
                87 to 1673,    // Additional Action Bar 4
                125 to 662,    // Familiar
                268 to 1449,   // Defence Abilities
                279 to 1882,   // Constitution Abilities
                322 to 231,    // Twitch Stream
                611 to 291,    // Debuff Bar
                693 to 517,    // Bank
                703 to 1622,   // Loot
                715 to 1448,   // Management Windows
                737 to 374
            )
            val speculative = listOf(
                82 to 1672,    // Additional Action Bar 3 - by elimination only
                698 to 1557    // Player Inspect
            )
            val raw = System.getProperty("opennxt.experiment.ui.extraPanels")?.trim()
            val pick: List<Pair<Int, Int>> = when {
                raw.isNullOrEmpty() -> emptyList()
                raw.equals("confirmed", true) -> confirmed
                raw.equals("strong", true) -> strong
                raw.equals("all", true) -> strong + speculative
                else -> {
                    val want = raw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                    (strong + speculative).filter { it.first in want }
                }
            }
            if (pick.isNotEmpty()) {
                logger.warn {
                    "ui experiment: opening ${pick.size} panel(s) the 919 replay never filled -> " +
                        pick.joinToString { "1477:${it.first}=${it.second}" } +
                        ". Derived from clientscript 2141 (7/7 on the replay's own pins; " +
                        "negative control mean 0.17/7). A crash means bisect this list."
                }
                pick.forEach { (component, iface) ->
 //: the HUD block (1431/1430/1670/1671/1673/291/1639)
                    // and 1448 are now seated on these exact components by the
                    // replay itself (InterfaceManager.hudMountFor). Opening them
                    // again here would be the double-open commit 2cb9576 removed.
                    if (player.interfaces.isOpened(iface)) {
                        logger.info { "ui experiment: extraPanels skips 1477:$component=$iface - already open" }
                        return@forEach
                    }
                    player.interfaces.open(id = iface, parent = 1477, component = component, walkable = true)
                }
            }
        }
        // --------------------------------------------------------------------
        player.interfaces.text(id = 187, component = 7, text = "")
        player.interfaces.text(id = 1416, component = 6, text = "")
        player.client.write(ClientSetvarcSmall(6348, 0))
        player.client.write(ClientSetvarcSmall(1077, 0))
        player.client.write(ClientSetvarcSmall(2746, 0))
        player.client.write(ClientSetvarcSmall(1000, 7))
        player.client.write(RunClientScript(script = 4704))
        player.client.write(RunClientScript(script = 4308, args = arrayOf(18, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30522, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30758, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30759, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30821, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30828, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30964, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(31386, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(31562, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(31918, 0)))
          player.client.write(ClientSetvarcSmall(1469, 2))
          player.client.write(ClientSetvarcSmall(1470, 2))
          player.client.write(ClientSetvarcSmall(1471, 6))
          player.client.write(ClientSetvarcSmall(1472, 1))
          player.client.write(ClientSetvarcSmall(1473, 1))
          player.client.write(ClientSetvarcSmall(1474, 5))
          player.client.write(ClientSetvarcSmall(1475, 12))
          player.client.write(ClientSetvarcSmall(1476, 1))
          player.client.write(ClientSetvarcSmall(1477, 1))
          player.client.write(ClientSetvarcSmall(1478, 1))
          player.client.write(ClientSetvarcSmall(1479, 1))
          player.client.write(ClientSetvarcSmall(1480, 1))
          player.client.write(ClientSetvarcSmall(1481, 9))
          player.client.write(ClientSetvarcSmall(1482, 1))
          player.client.write(ClientSetvarcSmall(1483, 1))
          player.client.write(ClientSetvarcSmall(1484, 4))
          player.client.write(ClientSetvarcSmall(1485, 1))
          player.client.write(ClientSetvarcSmall(1486, 8))
          player.client.write(ClientSetvarcSmall(1487, 1))
          player.client.write(ClientSetvarcSmall(1488, 1))
          player.client.write(ClientSetvarcSmall(1489, 1))
          player.client.write(ClientSetvarcSmall(1490, 1))
          player.client.write(ClientSetvarcSmall(1491, 1))
          player.client.write(ClientSetvarcSmall(1492, 1))
          player.client.write(ClientSetvarcSmall(1493, 5))
          player.client.write(ClientSetvarcSmall(3715, 1))
          player.client.write(ClientSetvarcSmall(5125, 1))
          player.client.write(ClientSetvarcSmall(6783, 1))
          player.client.write(ClientSetvarcSmall(5153, 1))
        player.interfaces.hide(id = 1477, component = 555, hidden = false)
        player.interfaces.hide(id = 745, component = 5, hidden = true)
        // (descriptor "l", 14-byte frame) in both 949 log; this line
        // sent an INT, so the frame was 10 bytes and the script read a long off
        // a 4-byte push. 0L keeps the value we have always sent and fixes the
        // TYPE. The reference value is account-specific (differs between the two
        // the client accepts the 14-byte frame from us; it accepts it from the reference client.
        player.client.write(RunClientScript(script = 5559, args = arrayOf(0L)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(39392, 0)))
        player.client.write(ClientSetvarcSmall(2059, 0))
        player.client.write(RunClientScript(script = 3957))

        // ==============================================================================
 // THE LOGIN SCRIPTS REFERENCE RUNS AND THIS SERVER NEVER DID
        // ==============================================================================
        // is a Frida observation of the real client logging in to Jagex. Its login window
        // carries 96 RUNCLIENTSCRIPT frames over 35 distinct script ids. This server's
        // source sends 13 of those 35.
        //
        // The block below is the subset that is SAFE TO REPLAY, and the filter is not a
        // guess about what each script does - it is about what the ARGUMENTS carry:
        //
        //   INCLUDED - empty argument lists, or arguments that are small constants. A
        //              script called with [] or [1] or [7, 0] cannot be carrying anyone's
        //              account state, so replaying it reproduces chrome and nothing else.
        //   EXCLUDED - 9553 ["Ignatius's Hot Deals", ...] and 8420 [..., 21259, 1007],
        //              which carry that session's own promo content and window hashes;
        //              11145, 6504 and 139, which carry interface hashes minted for that
        //              client; and script id 1821584506, which is almost certainly this
        //              decoder misreading a variable-length frame rather than a real id.
        //              Replaying any of those would be putting a stranger's session into
        // the reference client.
        //
        // THE 18950/18952/18951/20392 RUN IS THE INTERESTING ONE. Eight calls to 18951
        // indexed 0..7, and four of them carry (255,211,0), (255,13,22), (26,235,255),
        // (238,100,0) - gold, red, cyan, orange. Eight indexed slots with RGB triples is
        // a bar colour table, sent immediately after 3957 and immediately before 20392.
        // This server has never sent any of it.
        //
        // ORDER IS THE REFERENCE CLIENT'S: not ours: t=12742 through t=14302, with the
        // ones this server already sends left where they are. Positions relative to the
        // opens matter - 8178 and 18468 arrive at t=14302, well after the burst.
        //
        // GATED, default ON. -Dopennxt.experiment.ui.referenceLoginScripts=false takes the
        // whole block back out in one flag, so it can be A/B'd against a run without it.
        if (System.getProperty("opennxt.experiment.ui.referenceLoginScripts") != "false") {
            // t=12917, straight after 3957 - the bar colour table
            player.client.write(RunClientScript(script = 18950, args = arrayOf(1)))
            player.client.write(RunClientScript(script = 18952, args = arrayOf(0)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(0, 0, 0, 0, 3, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(1, 0, 0, 0, 3, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(2, 0, 0, 0, 3, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(3, 255, 211, 0, 0, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(4, 255, 13, 22, 0, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(5, 26, 235, 255, 0, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(6, 238, 100, 0, 0, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(7, 238, 100, 0, 0, 7)))
            player.client.write(RunClientScript(script = 20392))
            // t=12913.12915, the no-argument run the reference client fires around the same point
            player.client.write(RunClientScript(script = 20093, args = arrayOf(40)))
            player.client.write(RunClientScript(script = 12297))
            player.client.write(RunClientScript(script = 3373, args = arrayOf(1014)))
            player.client.write(RunClientScript(script = 7466))
            player.client.write(RunClientScript(script = 9945))
            player.client.write(RunClientScript(script = 3543, args = arrayOf(1)))
            // t=12981
            player.client.write(RunClientScript(script = 9542))
            player.client.write(RunClientScript(script = 7466))
            // t=13123..13138
            player.client.write(RunClientScript(script = 17838))
            player.client.write(RunClientScript(script = 18954, args = arrayOf(7, 0)))
            player.client.write(RunClientScript(script = 3373, args = arrayOf(1014)))
            // t=14302, after the burst
            player.client.write(RunClientScript(script = 8178))
            player.client.write(RunClientScript(script = 18468))
            // t=12742/12750/12893 - the reference client sends these EARLIER than here. They are placed
            // at the end rather than at their true positions because moving them ahead of
            // the opens would reorder code this observation has not yet justified reordering;
            // if the block helps but these three do nothing, their position is the first
            // thing to try. Recorded as a known deviation, not as a match.
            player.client.write(RunClientScript(script = 16300, args = arrayOf(1)))
            player.client.write(RunClientScript(script = 20611))
            player.client.write(RunClientScript(script = 15997))
            logger.info("ui.referenceLoginScripts: sent 25 clientscript calls reference runs at login " +
                "and this server never did (18950/18952/18951 x8/20392 is a bar colour table). " +
                "-Dopennxt.experiment.ui.referenceLoginScripts=false removes them.")
        }
        player.client.write(ClientSetvarcLarge(779, 2699))
        player.interfaces.text(id = 187, component = 7, text = "Harmony")
        player.interfaces.text(id = 1416, component = 6, text = "Harmony")
        player.client.write(ClientSetvarcLarge(2771, 52727066))
 // THE GAMEFRAME'S ARMING, GENERATED FROM REFERENCE.
        //
        // 1477 carries 442 of the login's arming frames - more than every other interface put
        // together. Ours diverged two systematic ways at once, which is why the frame COUNT
        // (443 against 398) understated it:
        //
        // * COMPONENT DRIFT - the reference client 51/63/98/106/117.., ours 47/58/93/101/112.. Mostly +5 but
        //     not constant, so no single shift would have corrected it.
        // * A FROM-SLOT OFF-BY-ONE - the reference client sends `from = 0, to = 1, mask = 2` on 44 components
        //     and we sent `from = 1, to = 1` on 42 of them, arming one slot instead of two.
        //
        // Bounded to the LOGIN BURST: the reference client sends two more frames (1477:24 and 1477:25) after a
        // 232 ms gap, which is a later event and not part of login. They are deliberately absent.
        //
        // Nine other 1477 rows are scattered earlier in this method. Eight are in no the reference client set and
        // are left alone; all nine precede this block, so the reference client's row wins wherever they overlap.
        //
        // new observation; do not hand-edit these rows.
        player.interfaces.events(id = 1477, component = 65, from = 1, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 99, from = 1, to = 1, mask = 6)
        player.interfaces.events(id = 1477, component = 71, from = 1, to = 1, mask = 4)
        player.interfaces.events(id = 1477, component = 99, from = 1, to = 1, mask = 6)
        player.interfaces.events(id = 1477, component = 26, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 17, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 1477, component = 17, from = 0, to = 46, mask = 2)
        player.interfaces.events(id = 1477, component = 896, from = 0, to = 1000, mask = 2)
        player.interfaces.events(id = 1477, component = 912, from = 0, to = 0, mask = 2)
        player.interfaces.events(id = 1477, component = 1, from = 0, to = 12, mask = 2)
        player.interfaces.events(id = 1477, component = 2, from = 0, to = 300, mask = 2)
        player.interfaces.events(id = 1477, component = 63, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 63, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 63, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 61, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 89, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 89, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 89, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 87, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 84, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 84, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 84, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 82, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 79, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 79, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 79, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 77, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 74, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 74, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 74, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 72, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 69, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 69, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 69, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 67, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 98, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 98, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 98, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 98, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 92, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 423, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 423, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 423, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 423, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 418, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 424, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 433, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 433, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 433, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 433, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 429, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 434, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 443, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 443, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 443, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 443, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 439, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 444, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 453, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 453, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 453, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 453, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 449, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 454, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 463, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 463, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 463, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 463, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 459, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 464, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 473, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 473, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 473, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 473, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 469, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 474, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 483, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 483, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 483, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 483, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 479, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 484, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 493, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 493, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 493, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 493, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 489, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 494, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 412, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 412, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 412, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 412, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 407, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 413, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 106, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 106, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 106, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 106, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 101, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 106, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 107, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 150, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 150, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 150, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 150, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 145, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 150, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 151, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 161, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 161, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 161, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 161, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 156, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 161, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 162, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 172, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 172, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 172, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 172, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 167, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 173, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 183, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 183, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 183, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 183, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 178, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 183, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 184, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 194, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 194, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 194, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 194, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 189, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 194, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 195, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 205, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 205, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 205, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 205, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 200, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 205, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 206, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 216, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 216, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 216, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 216, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 211, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 216, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 217, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 227, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 227, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 227, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 227, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 222, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 228, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 238, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 238, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 238, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 238, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 233, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 238, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 239, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 249, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 249, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 249, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 249, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 244, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 249, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 250, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 260, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 260, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 260, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 260, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 255, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 260, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 261, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 271, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 271, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 271, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 271, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 266, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 272, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 282, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 282, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 282, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 282, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 277, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 283, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 117, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 117, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 117, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 117, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 112, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 117, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 118, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 128, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 128, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 128, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 128, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 123, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 129, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 292, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 292, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 292, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 292, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 288, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 294, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 303, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 303, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 303, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 303, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 298, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 304, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 139, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 139, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 139, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 139, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 134, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 139, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 140, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 314, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 314, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 314, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 314, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 309, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 315, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 548, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 548, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 548, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 548, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 543, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 549, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 504, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 504, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 504, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 504, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 499, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 505, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 515, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 515, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 515, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 515, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 510, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 516, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 559, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 559, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 559, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 559, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 554, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 560, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 537, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 537, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 537, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 537, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 532, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 538, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 641, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 641, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 641, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 641, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 751, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 751, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 751, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 736, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 736, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 736, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 727, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 727, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 727, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 741, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 737, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 742, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 618, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 618, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 618, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 618, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 597, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 597, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 597, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 622, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 622, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 622, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 628, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 628, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 628, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 610, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 610, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 610, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 635, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 635, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 635, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 601, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 601, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 601, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 823, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 823, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 823, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 696, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 696, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 696, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 696, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 631, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 631, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 631, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 649, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 649, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 649, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 649, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 712, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 712, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 712, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 708, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 717, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 51, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 51, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 51, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 51, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 28, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 657, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 657, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 657, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 325, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 325, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 325, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 325, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 320, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 326, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 336, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 336, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 336, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 336, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 331, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 721, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 721, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 721, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 526, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 526, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 526, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 526, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 521, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 527, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 701, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 6, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 11, to = 11, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 13, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 665, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 665, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 665, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 669, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 669, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 669, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 346, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 346, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 346, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 346, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 341, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 351, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 673, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 673, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 673, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 606, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 606, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 606, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 706, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 706, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 706, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 706, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 681, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 681, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 681, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 677, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 677, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 677, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 357, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 357, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 357, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 357, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 352, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 362, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 645, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 645, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 645, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 368, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 368, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 368, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 368, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 363, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 373, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 593, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 593, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 593, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 379, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 379, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 379, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 379, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 374, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 384, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 614, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 614, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 614, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 614, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 390, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 390, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 390, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 390, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 385, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 395, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 401, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 401, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 401, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 401, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 396, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 406, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 731, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 731, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 731, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 589, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 589, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 589, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 585, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 585, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 585, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 577, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 577, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 577, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 653, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 653, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 653, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 573, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 573, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 573, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 685, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 685, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 685, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 6, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 11, to = 11, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 13, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 3, to = 4, mask = 9175040)
        player.client.write(RunClientScript(script = 1264, args = arrayOf("PMod PvM Event", "Friday 18th June, 20:00 Game Time", "Nex: Angel of Death boss mass", "", "Nex lobby, God Wars Dungeon", "w88", "Pippyspot & Boss Guild", "Boss Guild", "", "", 1321)))
        player.client.write(RunClientScript(script = 3529))
        // The second the reference client burst - see the note on the first block above.
        player.interfaces.events(id = 1430, component = 66, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 69, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 79, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 82, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 92, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 95, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 105, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 108, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 118, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 121, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 131, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 134, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 144, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 147, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 157, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 160, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 170, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 173, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 183, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 186, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 196, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 199, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 209, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 212, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 222, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 225, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 235, from = 65535, to = 65535, mask = 2098176)
        player.interfaces.events(id = 1430, component = 238, from = 65535, to = 65535, mask = 2098176)
        player.interfaces.events(id = 1430, component = 19, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1430, component = 13, from = 65535, to = 65535, mask = 8650758)
        player.interfaces.events(id = 1430, component = 26, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1430, component = 18, from = 65535, to = 65535, mask = 2046)
        player.interfaces.events(id = 1430, component = 59, from = 65535, to = 65535, mask = 8650754)
        player.interfaces.events(id = 1430, component = 261, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 260, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 259, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 258, from = 65535, to = 65535, mask = 448)
        player.interfaces.events(id = 1430, component = 38, from = 65535, to = 65535, mask = 0)
        player.interfaces.events(id = 1430, component = 38, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 1458, component = 39, from = 0, to = 38, mask = 8388610)
        player.interfaces.events(id = 1465, component = 15, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1460, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1881, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1888, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1452, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1461, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1884, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1885, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1887, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1886, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1883, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1449, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1882, component = 1, from = 0, to = 211, mask = 8616966)

        // Everything above arms the components the recording happened to exercise.
        // Every other component in every open panel is still unarmed, and an
        // unarmed component transmits NOTHING when clicked -- which is why the
        // UI reads as dead rather than as unimplemented. This arms the rest so a
        // click at least reaches the server and gets logged.
        if (System.getProperty("opennxt.experiment.ui.armAll") == "true") {
            player.interfaces.armAllOpenComponents()
        }

        // armAll above arms ops 1-10 only. Dragging, resizing and the close button
        // live on bits 18/19/21/23, which nothing above ever sets for most panels:
        // of the 60 interfaces login opens, 17 are never armed at all and 19 more
        // get option bits only. That is the "panels will not move, resize, or close
        // on X" symptom. See InterfaceManager.armPanelInteractivity.
        System.getProperty("opennxt.experiment.ui.armPanels")?.let { mode ->
            if (mode == "gap" || mode == "all") player.interfaces.armPanelInteractivity(mode)
            else logger.warn("ui.armPanels=$mode is not a mode; use 'gap' or 'all'. Nothing armed.")
        }

        // ------------------------------------------------------------------
        // THE OPTIONS MENU BUTTONS, AND THROUGH THEM EDIT LAYOUT MODE.
        //
        // Moving and resizing panels in RS3 is NOT done by dragging them in
        // normal play. It is done in Edit Mode, reached from the Options menu's
        // "Edit Layout Mode" button - see reference/live-ui/, where the live
        // client's Edit Mode screen carries Load Layout, Display Windows,
        // Save Layout and a "Show Borders" checkbox, and only there do panels
        // gain drag borders and resize handles. So "the panels will not move"
        // may not be a panel problem at all: there is currently no way in.
        //
        // The route in, read out of the client's own scripts:
        //
        //   clientscript 2935 labels the Options menu buttons, and gates EVERY
        //   one of them on varbit 1899:
        //
        //       $int0 = 0;
        //       if (varbit1899 == 1) { $int0 = 1; }
        //       script13994(93913109, 93913110, 28556, $int0, "Edit Layout Mode");
        //
        //   93913109 >> 16 = 1433, so that button is 1433:21/22, and we DO open
        //   interface 1433 at 1477:751. `$int0` reaches script13999 as its 4th
        //   argument, where `$int3 == 1` selects a different button sprite state.
        //
        // varbit 1899 is varp 689 bit 9, and this server never writes varp 689
        // at all - the replay does not contain it - so the bit is 0 and every
        // Options button renders in whichever state that is.
        //
        // WHAT IS NOT ESTABLISHED: whether state 1 is ENABLED or DISABLED.
        // script13999 picks sprite index 5 for it, and the sprite convention is
        // not written down anywhere we have. That is exactly why this is a flag
        // with a settable VALUE rather than a fix: set it to 1, look at the
        // menu, and the screenshot answers in one run what no amount of reading
        // will. Try 0 as the control - it should look identical to today.
        //
        //   -Dopennxt.experiment.ui.optionsVarbit=1
        System.getProperty("opennxt.experiment.ui.optionsVarbit")?.let { raw ->
            val v = raw.toIntOrNull()
            if (v == null || v !in 0..1) {
                logger.warn("ui.optionsVarbit=$raw is not 0 or 1 - varbit 1899 is a SINGLE bit " +
                    "(varp 689 bit 9). Nothing written.")
            } else {
                // Varp 689 is not otherwise sent, so writing the whole varp
                // cannot clobber another varbit packed beside this one. If that
                // ever changes, read-modify-write instead of assigning.
                val value = v shl 9
                player.client.write(VarpLarge(689, value))
                logger.info("ui.optionsVarbit: varp 689 = $value (varbit 1899 = $v, bit 9). " +
                    "This gates EVERY Options-menu button in clientscript 2935 - Hop Worlds, " +
                    "Exit to Lobby, Logout, Report Issue, Ribbon Setup and Edit Layout Mode. " +
                    "LOOK AT THE OPTIONS MENU: if the buttons changed appearance, 1 is the " +
                    "enabled state and Edit Layout Mode (1433:21) is the next thing to answer.")
            }
        }

        // ------------------------------------------------------------------
        // THE VARP PROBE. Test a hypothesis without a rebuild.
        //
        //   -Dopennxt.experiment.ui.varps=6480:1,11967:0,689:512
        //
        // Every guess about UI state has cost a full rebuild to try. This sends
        // an arbitrary list of `varp:value` pairs at the end of login so a
        // hypothesis costs a flag edit instead.
        //
        // THE LIST WORTH TRYING FIRST, and where it came from. Walking the CS2
        // call graph from the backpack builder, the panel-activate chain and the
        // Options menu gives 114 reachable scripts reading 57 varps. Most of
        // those the CLIENT ITSELF assigns - `var2254 = ?` and so on - so their
        // being absent from our login means nothing. Filtering to the ones the
        // client only ever READS, and which we never send, leaves fifteen:
        //
        //   5427 5991 6480 6526 6527 6529 6530 6531 6532 6533 6534 6535
        //   11584 11967 12314
        //
        // Two of those sit on the paths that are actually broken:
        //
        //   varp  6480  read by script16559, which 8680 - the 697-instruction
        //               backpack builder - calls directly.
        //   varp 11967  read by script10405, the FIRST call inside script8863,
        //               which is the panel-availability path that draws
        //               "<name> currently unavailable" and the padlock.
        //
        // Ignore 12314: it is read by 75 scripts and is the Leagues flag
        // (`if (var12314 > 0) cmd_1815("Leagues: CATALYST")` in 8863).
        //
        // A varp reading 0 may well be the correct default, so "never sent" is
        // NOT the same as "wrong". This is a probe, not a fix - it exists so the
        // question can be asked cheaply.
        System.getProperty("opennxt.experiment.ui.varps")?.let { raw ->
            var ok = 0
            val bad = ArrayList<String>()
            for (pair in raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val parts = pair.split(':')
                val id = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val value = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (parts.size != 2 || id == null || value == null) {
                    bad += pair
                    continue
                }
                player.client.write(VarpLarge(id, value))
                logger.info("ui.varps: varp $id = $value")
                ok++
            }
            if (bad.isNotEmpty())
                logger.warn("ui.varps: ignored ${bad.size} malformed entry/entries $bad - " +
                    "the format is id:value, comma separated, e.g. 6480:1,11967:0")
            logger.info("ui.varps: sent $ok varp(s) as a probe. These are NOT part of the login " +
                "replay; anything that changes on screen is caused by exactly this list.")
        }

        // ------------------------------------------------------------------
        // THE VARP RE-SEND. -Dopennxt.experiment.ui.varpResend=false to disable.
        //
        // ORDERING BUG, hypothesis: `sendDefaultVarps` runs at the TOP of this
        // method, before `openTop(1477)`. So every one of the ~1,379 varps is on
        // the wire BEFORE a single component of the gameframe exists - and 1477
        // is a var-driven frame. If a component's ON_VAR_TRANSMIT cursor is
        // initialised to the CURRENT global counter when the component is
        // created, then no var-transmit handler can ever fire: the cursor
        // already equals the counter and nothing advances it again.
        //
        // It accounts for every symptom at once, which no per-panel theory did:
        //   * the 3D world renders          - no scripts involved
        //   * the backpack grid IS built    - ON_LOAD, a different trigger
        //   * IF_SETEVENTS works            - pure packet handling, no scripts
        //   * clientscript 9943 never runs  - its caller 20507 IS an
        //                                     ON_VAR_TRANSMIT handler, and 9943
        //                                     is the panel-visibility apply
        //   * the orbs DO render            - 1477:418 is the one panel
        //                                     container authored flags = 0, and
        //                                     it is static art
        //   * 919 and 949 layouts are equally broken - so the mount tables were
        //                                     never the primary cause
        //
        // It also reinterprets our own measurement: read_triggers.py reported
        // `cursor == counter` and that was read as "the trigger FIRED". A cursor
        // initialised equal and never advanced is observationally identical.
        //
        // SUPPORTING EVIDENCE: re-sending varp 3680 alone after login, via the
        // ui.varps probe above, visibly MOVED the orbs. A varp re-sent once the
        // frame exists demonstrably causes layout work.
        //
        // NOT the fix for the misrouted minimap/ribbon - those are a separate,
        // measured off-by-one-block in the action-bar run (919 had one fewer
        // bar), which is why the minimap's 949 mount 1477:94 receives nothing.
        if (System.getProperty("opennxt.experiment.ui.varpResend") != "false") {
            val n = DefaultVariables.sendDefaultVarps(client)
            logger.info("ui.varpResend: re-sent $n varp(s) AFTER the gameframe was " +
                "built. The first send happens before openTop(1477), when no " +
                "component exists to listen. If the UI now lays out, the login " +
                "ordering is the bug and this belongs in the sequence, not in a flag.")
        }

        // ------------------------------------------------------------------
        // VARBIT 27169 (varp 3680 bit 21) - `-Dopennxt.experiment.ui.varbit27169=false` clears it
        // DEFAULT ON (see below). The replay's 3680 = 0x400B9801 leaves bit 21 clear. That
        // one bit is what 1477:23's own varp listener (clientscript 20507) tests
        // before calling 9943, and it is ALSO read by 8705 - the client's panel
        // layout routine that every panel onload (9903 for the minimap, 8109 for
        // the bars, ...) ends in - so with it clear the client runs a different
        // layout branch from the one a live server's HUD runs. Setting it here,
        // AFTER the default re-send (which would otherwise clobber it), lets the
        // client's own 20507 -> 9943 path fire on the transmit and lets 8705 take
        // its bit-21 branch. Nothing else about 3680 changes. Written as an
        // experiment because nothing here knows what else bit 21 gates - the
        // very reason the 9943 call was made directly instead of this.
 // DEFAULT ON since the 14:1x-14:3x runs: with bit 21 set the
        // client switched to its LIVE HUD - chat box with the All/Game/Public tab
        // row, the modern minimap without window chrome, hover tooltips
        // ("Steal from Seed Stall, +3 options"), the activity-tracker hint, and
        // (with the bars on the old mounts) one action bar drawn above the chat.
        // With it clear every earlier run had been on the legacy/fallback
        // branch. Nothing regressed across four runs with it on. `=false` restores
        // the clear bit. STILL OPEN with it on: the bar family only draws in the
        // old (wrong) seats; re-seating 1430 into dock 70 draws nothing even in
        // live mode - 8110's other reads of 27169 (8115/8114) are the next thing.
 // POSITION: `-Dopennxt.experiment.ui.varbit27169.at=late|early|both`
        // (default late = the block below, unchanged). Static read of the slot onload
        // (8409 -> 8411): for the bar family (8137: 1003/1024/1031-1035/1042-1044/1048/1053)
        // it cc_creates the two hidden layers in the dock (3505) that 8138 later needs,
        // and stores their param 3537 as (0,0) when varbit 27169 == 1, else 8721(panel)'s
        // dims. 8411 runs when 1477 OPENS, i.e. before this transmit - so under `late`
        // every slot frame, 8705 layout pass and the main bar's 8110 build ran on the
        // fallback branch and only the var-transmit listeners saw the live bit. `early`/
        // `both` put the bit in sendDefaultVarps' table (DefaultVariables.varp3680Default)
        // so the very first send, before openTop(1477), reads 1. PREDICTION for
        // `both` + hudMount.rows=all: ribbon at 61, main bar at 67/70, bars, ring all draw.
        val varbit27169At = DefaultVariables.varbit27169At()
        if (varbit27169At != "late" && System.getProperty("opennxt.experiment.ui.varbit27169") == "true") {
            logger.warn("ui.varbit27169[$varbit27169At]: the default varp table already carried 3680 = " +
                "${DefaultVariables.varp3680Default()} (bit 21 set) BEFORE openTop(1477); " +
                (if (varbit27169At == "early") "no separate late transmit." else "the late transmit below still goes out."))
        }
        // Default flipped to OFF - see DefaultVariables.varp3680Default.
        if (System.getProperty("opennxt.experiment.ui.varbit27169") == "true" && varbit27169At != "early") {
            val v = 1074501633 or (1 shl 21)
            player.client.write(VarpLarge(3680, v))
            // This transmit lands AFTER the frame exists, so 1477:11's on-vartransmit
            // (11312 -> 11313 -> 2466 -> 8882 -> 8781 -> 8784) sees var530b != varbit
            // 27169 and finalises the HUD - the mechanism behind "the HUD switched to
            // live mode". The main bar's own build (8110 via 8109) ran at IF_OPENSUB
            // time; the additional bars get a deferred re-run through 8310 -> 3379 ->
            // 8391 -> 2141 -> 8110, the main bar never does. -Dopennxt.experiment.
            // hudMount.rerun8110=true re-runs it explicitly, N ticks later.
            logger.warn("ui.varbit27169: sent varp 3680 = $v (0x400B9801 | bit 21) so varbit 27169 reads 1. " +
                "EXPERIMENT - if the HUD lays itself out (bars AND minimap ring), this bit is the " +
                "'HUD ready' flag the layout scripts key on and belongs in sendDefaultVarps.")
        }
 // PROBE: -Dopennxt.experiment.hudMount.probe67=true (default off).
        // Decides between "the bars are BUILT but MISLAID" and "the bars are not
        // built" in live-HUD mode, with two cache scripts that are pure arg
        // wrappers (read from the disasm, tiny and total):
        //   13268(x, y, xType, yType, comp)  = if_setposition (op 1924)
        //   11145(w, h, wType, hType, comp)  = if_setsize     (op 1794)
        // (this login sequence already sends 11145 for Premier Pass, so the
        // wrapper is proven live). N ticks after login it force-unhides, sizes
        // (352x128, the slot's own cache dims) and positions (464,300 - screen
        // middle on 1280x720) slot 1477:67, the main action bar's slot.
        // If the bar APPEARS mid-screen: build fine, the HUD column layout
        // (8706, inputs varc 3477 + panel-tree struct params 3519/3520) is the
        // defect. If NOTHING appears: the live-mode build of 1430 is the defect
        // and 8115/8114's live branches are next. Deliberately does not touch
        // the dock or 1430 itself - one component, one variable.
        if (System.getProperty("opennxt.experiment.hudMount.probe67") == "true") {
            probe67Countdown = PROBE67_DELAY_TICKS
        }
 // TWO MORE PROBES afternoon, from a live observation that a manual
        // WINDOW RESIZE lays the minimap block out correctly (EVENTS strip gone, ring + map
        // + orbs draw at top-right) while the bars stay absent. So the minimap family is
        // BUILT-BUT-MISLAID (a relayout fixes it) and the bar family fails earlier.
        //
        // -Dopennxt.experiment.hudMount.relayout=true (default off): N ticks after login send
        // RunClientScript(8884, [8]) - 8884(mode) is the client's whole-HUD relayout driver
        // (it sizes 1477:27 from the saved window-size varcs 2997/2998, runs 8702(9,10),
        // then 2928 -> 8885 -> 8781; 13895, the varp-3680 transmit handler 8779 registers,
        // funnels into it with mode 8 when varbit 38842 is 0). PREDICTION: the minimap block
        // snaps into place WITHOUT a manual resize. If it does, this belongs in the login
        // sequence; if it does not, the resize enters the relayout somewhere else.
        //
        // -Dopennxt.experiment.hudMount.redim1003=true (default off): the slot onload 8411
        // stores the bar dock's cc param 3537 dims as (0,0) when varbit 27169 is 1 (read
        // statically, ops 3590-3598), while the sizing script 8141's live branch returns
        // 552 x 38/76/128 for the main bar. N+2 ticks after login send
        // RunClientScript(8140, [1003, 552, 76]) (re-store real dims) then
        // RunClientScript(8110, [1003]) (rebuild the main bar). PREDICTION: the main bar
        // draws in its dock. If it does, the (0,0) dims are the bar defect and the real fix
        // is whatever populates them on a live server (the saved-layout system).
 // DEFAULT ON since ~15:00: three runs, and it is the wire equivalent of
        // the manual resize that the operator measured fixing the minimap block; the best HUD state
        // yet (full keybound action bar, default mounts) was measured with it. =false restores
 // the behaviour.
        if (System.getProperty("opennxt.experiment.hudMount.relayout") != "false") {
            relayoutCountdown = hudRepair(RELAYOUT_DELAY_TICKS)
        }
 // REVIEW: UI-A3's 8115 read - (0,0) in the dock's cc param 3537 is the HORIZONTAL KEYBOUND
        // bar (552x76 was the live-mode 8141 value, and h is a mode flag in 8110). Both bar levers now fire
        // AFTER the last 8884 (relayout + again + 2) and are opt-in:
        //   -Dopennxt.experiment.hudMount.redim1003=true  -> 8140[1003,0,0] + 8110[1003]
        //   -Dopennxt.experiment.hudMount.rerun8110=true  -> 8110[1003] only (was unreachable: nested in ui.varbit27169)
        if (System.getProperty("opennxt.experiment.hudMount.redim1003") == "true") {
            redim1003Countdown = hudRepair(RELAYOUT_DELAY_TICKS + RELAYOUT_AGAIN_TICKS + 2)
        }
        if (System.getProperty("opennxt.experiment.hudMount.rerun8110") == "true") {
            rerun8110Countdown = hudRepair(RELAYOUT_DELAY_TICKS + RELAYOUT_AGAIN_TICKS + 2)
        }
        // Generalized position probe: -Dopennxt.experiment.hudMount.probe.comp=<n> (default
        // off) force-unhides 1477:<n>, sizes it 352x128 and centres it at (464,300), 4 ticks
        // AFTER the relayout so the layout pass cannot overwrite it. Built for the ribbon
        // slot 61 (where the main bar lives on the replay mounts) to answer, with the minimap
        // ring re-seated at 94, whether the ribbon/bar block is BUILT-BUT-MISLAID (it pops
        // mid-screen) or NOT BUILT (nothing appears).
        val probeComp = System.getProperty("opennxt.experiment.hudMount.probe.comp")?.toIntOrNull()
        if (probeComp != null) {
            probeCompId = probeComp
            probeCompCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 4)
        }
 // SLOT REBUILD evening. probe.comp=61 proved that with the minimap
        // furniture re-seated to its live dock (1465 -> 1477:94) the ribbon/bar block is NOT
        // BUILT (forcing the slot visible mid-screen drew nothing). Static read: 10400 (the
        // ribbon slot's onload) is just 8411(panel, 0), and script 8409 IS `8411(arg0, 0)` -
        // so the slot build of ANY panel is re-runnable from the wire as
        // RunClientScript(8409, [panel]). -Dopennxt.experiment.hudMount.rebuild=1002,1003
        // (comma list of panel ids, default off) re-runs those slot builds 6 ticks after the
        // relayout - i.e. AFTER the minimap has settled - then sends one more 8884[8] relayout
        // 2 ticks later so the rebuilt frames get packed. PREDICTION: with rows=minimap,popups,
        // the ribbon frame and the main action bar build on the second pass - ring AND bar.
 // Two X-button probes late (see PanelCloseWiring.armFrameOps KDoc):
        // ui.armFrames=true arms frame ops on every panel slot after the relayout;
        // ui.close8323=<panel> wire-invokes the client's own close routine
        // RunClientScript(8323, [panel, 0]) two ticks later - if THAT closes the window,
        // the close code works and arming the click is the whole fix.
 // DEFAULT ON since (opt-in for three runs first): arming the window frame
        // ops is what makes right-click -> Close Window work on every titled window (proven
 // live; left-click close additionally needs the noClickThrough pass,
        // still opt-in. `=false` disables.
        if (System.getProperty("opennxt.experiment.ui.armFrames") != "false") {
            armFramesCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 4)
        }
        System.getProperty("opennxt.experiment.ui.close8323")?.toIntOrNull()?.let {
            close8323Panel = it
            close8323Countdown = hudRepair(RELAYOUT_DELAY_TICKS + 6)
        }
        System.getProperty("opennxt.experiment.hudMount.rebuild")?.let { raw ->
            val ids = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (ids.isNotEmpty()) {
                rebuildPanels = ids
                rebuildCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 6)
            }
        }

        // ------------------------------------------------------------------
 // NOCLICKTHROUGH - the HUD left-click evening. Live probe + capstone
        // read: comp+0x31 bit 2 is `noclickthrough` and every component the 949 cache
        // constructs has it SET (layers, rects, texts, graphics - ctors,
        //,). The hit walk's cancel loop
        // skips a hovered component whose bit 2 is set, so the world
        // view's queued pointer record is never cancelled, its cursor-over bit stays
        // set, and the ground builder emits "Walk here" as the default op over the HUD.
        // Live RS3 clears the bit with CS2 1070 if_setnoclickthrough(1, comp): script
        // 6060 does it for the chat box (137:87, 1477:418), and 6060 is reached from
        // 2704 - a zero-arg script with no gosub callers, the profile of a script the
        // server sends. Our 919 replay never sends it.
        //   -Dopennxt.experiment.ui.chatSetup=true       RunClientScript(2704) after the chat opens
        //   -Dopennxt.experiment.ui.noClickThrough=137:0,1466:0   RunClientScript(7684,[comp]) per
        //        component: 7684 = if_setnoclickthrough(1, a0) plus a "Current Floor" tooltip
        //        on mouserepeat - a visible side effect, so this is the CONTROL, not the fix.
        // Both need ui.worldRepeat (the key-5 record on 1482:0 that gets cancelled).
        //
 // RE-READ (notes/UI-B1B3-menu.md) - PREDICTED NULL, DO NOT SHIP 7684:
        // cs2 op 1070 if_setnoclickthrough writes comp+0x31 bit 2 := (arg != 1), and 8411
        // op 254-290 already calls it with local37 = 1 on every panel's 3503 slot
        // (Backpack/Worn/Skills get noclickthrough SET; only the chat panels are made
        // click-through). The prior reading was inverted. The world view's "Walk here" row
        // does not depend on the hit-walk pick at all: suppressing it needs a key-4
        // onmouseover record on 1482:0 (op 1244 if_setonmouseover) and no cache script
        // pushes 1482:0's hash. 7684 adds a visible "Current Floor" tooltip and nothing
        // else. Zero-cost native discriminator: break while hovering the X
        // and count the queued records - a record naming 1482:0 = the walk order wins.
        if (System.getProperty("opennxt.experiment.ui.chatSetup") == "true") {
            player.client.write(RunClientScript(script = 2704, args = arrayOf()))
            logger.warn { "ui.chatSetup: sent RunClientScript(2704) - the orphan chat-box setup script (6060: noclickthrough on 137:87 / 1477:418)" }
        }
        System.getProperty("opennxt.experiment.ui.noClickThrough")?.let { raw ->
            raw.split(',').map { it.trim() }.filter { it.contains(':') }.forEach { pair ->
                val (i, c) = pair.split(':').map { it.trim().toInt() }
                player.client.write(RunClientScript(script = 7684, args = arrayOf((i shl 16) or c)))
                logger.warn { "ui.noClickThrough: sent RunClientScript(7684, [$i:$c]) - if_setnoclickthrough(1) (+ floor tooltip) on $i:$c" }
            }
        }

        // ------------------------------------------------------------------
        // THE PANEL VISIBILITY APPLY, now that every panel is actually mounted.
        //
 // on diag-20260829-033017-6780.log, a real login
        // through scripts/rs3.ps1 -Task local:
        //
        //     added() sends 77 IF_OPENSUB in total.
        //     RunClientScript(9943) went out as send #1390.
        //     IF_OPENSUB before it: 24.  IF_OPENSUB after it: 53.
        //
        // The send was identified WITHOUT relying on payload bytes, which the
        // diag does not dump for opcode 82: six zero-argument RUNCLIENTSCRIPTs
        // reach the wire (9943, 10903, 8778, 4704, 3957, 3529), all payload=5,
        // and only one of them is immediately preceded by an IF_SETHIDE. That
        // is send #1389 -> #1390, the unhide of 1477:101 followed by this call,
        // an adjacency that occurs exactly once in the method and exactly once
        // on the wire.
        //
        // 9943 iterates enum 7717 (94 panels), resolves each panel's struct via
        // enum 7716, reads struct params 3503/3505, and drives if_sethide plus
        // the five-argument position/size opcodes off them. Running it while 53
        // of the 77 mounts do not yet exist applies a layout to panels that are
        // not there. The 24 that DO exist at that moment get a correct apply -
        // which is why some panels render and most do not, and why the ones
        // that render sit at positions nothing later corrects.
        //
        // This is NOT a retraction of the varp-ordering analysis above: varbit
        // 27169 really is 0, 20507 really does return early, and the client's
        // own route into 9943 really is dead. That analysis explains why the
        // server has to call 9943 at all. It said nothing about WHEN, and the
        // call landed at the first convenient line rather than a correct one.
        //
        // CONTROL, deliberately preserved so this can be falsified in one run:
        //   -Dopennxt.experiment.panelApply.at=early   the pre-fix position
        //   -Dopennxt.experiment.panelApply.at=both    both positions
        //   -Dopennxt.experiment.panelApply=false      no apply at all
        // Default is "late": here, after all 77 mounts and after the varp
        // re-send, so the panels exist AND their var-driven state is current.
        //
        // PREDICTION, written before the run that tests it: panels that today
        // are invisible until dragged become visible without a drag, and the
        // ones already visible stop sitting at uncorrected positions. If
        // nothing changes, the apply is not what positions these panels and
        // this ordering is not the defect - say so and put the call back.
        if (panelApplyAt == "late" || panelApplyAt == "both") applyPanelVisibility("late")

        // ------------------------------------------------------------------
        // 9943 RE-HIDES THE BACKPACK. Measured, not argued.
        //
        // 9943's per-panel loop asks `enum_getreversecount(9010, panelId)` and,
        // when that count is 0, stores 1 into its local and calls
        // `if_sethide(<that local>, struct_param(panelStruct, 3503))`. So every
        // enum-7717 panel ABSENT from enum 9010 is hidden by the apply.
        //
        // DERIVED FROM THE CACHE, not typed: enum 9010 holds 41 ids and they are
        // the always-on HUD set - Ribbon 1002, Main Action Bar 1003, Minimap
        // 1004, All Chat 18, Additional Action Bars 1032/1033, Events 1037,
        // Game View 1000, HUD Overlays 1005, Dialogue Box 1006 ... Panel 2, the
        // Backpack (enum 7716 -> struct 21285, param 3503 = 1477:101), is not in
        // it, and neither is any other TAB panel: Skills 0, Worn Equipment 3,
        // Prayers 4, Magic 5, Emotes 9, Music Player 10, Notes 11.
        //
        // list was compared against it. Every panel that started drawing when
        // the apply moved late - minimap, both action bars, chat contents, and
        // the EVENTS strip that stopped being mis-positioned - is IN 9010. The
        // one thing that did not come up, the backpack, is in the complement.
        //
        // CS2 opcode 1710 is if_sethide with args (flag, component), the
        // component popped first, hidden = (flag == 1). Read off the binary, not
        // assumed: trampoline -> dispatcher -> body
        //, which does `cmpl $1,0x100(%r9,%rax,4); sete %r8b` and
        // hands that byte to, which stores it at node+0 on a
        // stride-24 record. node+0 is the same byte tests with
        // `if (*node != 0) goto next_node` - a skip that takes the node AND ITS
        // WHOLE SUBTREE. 1477:103, where 1473 mounts, is a CHILD of 1477:101,
        // so hiding 101 means the walk never descends into the backpack at all.
        //
        // On the wire: send #1389 IF_SETHIDE(1477:101, hidden=false) - the line
        // ~500 lines above - and then send #4246 RUNCLIENTSCRIPT 9943 undoes it.
        //
        // THIS IS THE NARROW FIX: re-assert the unhide after the apply. The
        // client-native route is 8307, the panel-OPEN routine (its op 49 is
        // if_sethide(0, struct.3503); 8323 is the matching CLOSE), which would
        // also update whatever tab state the ribbon keeps. It is NOT used here
        // because 8307 takes TWO int arguments and only the first is known to be
 // the panel id - inventing the second is exactly the guess this server
        // keeps getting burned by. Read that argument, then prefer 8307.
        //
        // KNOWN LIMIT, stated rather than discovered later: this unhides one tab
        // panel and leaves the other seven hidden, which is what the client does
        // anyway (one tab at a time). What it does NOT do is tell the ribbon a
        // panel is open, so tab switching may not close the backpack until the
        // 8307 route replaces this.
        //
        // ==================================================================
 // DEFAULTED OFF, THE SAME DAY IT WAS ADDED. Read this before
        // turning it on: the mechanism above is right and the RESULT is a net loss.
        //
        // within a single session (server-20260829-040723.out.log):
        //   * at login, with this re-assert ON: NO minimap, NO action bars, and
        //     no visible backpack either.
        //   * the player pressed ESC, the backpack closed, and the MINIMAP AND
        //     BOTH ACTION BARS IMMEDIATELY APPEARED.
        //
        //
        // It was written up the same day as "one variable, both directions:
        // backpack shown <=> minimap and action bars hidden", and as a THIRD
        // DEFECT, "the 1477:60 column cannot lay out two panels at once". Both
        // are FALSE, and the refutation is on the wire in that same session:
        //
        //   - The whole session contains EIGHT IF_SETHIDE sends and the LAST is
        //     #4247, this re-assert, in the same frame as the 9943 at #4246.
        //   - The ESC path sent #4353 IF_CLOSESUB and nothing else - no
        //     IF_SETHIDE at all.
        //   => 1477:101 was hidden=false in BOTH screens. The hide flag never
        //      changed. What changed was whether 1473 was MOUNTED at 1477:103.
        //
        // So "backpack shown <=> HUD hidden" was co-occurrence read as mechanism -
        // the failure mode THE METHOD names first - and the attribution to
        // ClientProt 67 was wrong too: 67 has no route into GlobalCloseWiring
        // (see the retraction written at that class). ESC reaches the server as
        // IF_BUTTON1 on 1477:8, the ribbon OPTIONS button.
        //
        // The geometry also refutes the collision directly. 9943 leaves 1477:92
        // (Minimap) entirely alone, so it keeps its authored 224x224 at
        // x = 1280-224 = 1056, y = 0. For 1477:101 it calls
        // if_setposition/if_setsize explicitly - 210x315 at x = 1070, y = 333 on a
        // 1280x720 frame. Those two rects are DISJOINT with 109px of clear air
        // between them. Nothing collides.
        //
        // not lay the column out itself. Its last act per panel (ops 376-387) is
        // to ARM a deferred one-shot hook - op 278 with script 9933 on the panel's
        // param-3503 slot - and 9933 gosubs 8390 -> 8391, which is where a panel
        // is actually positioned and sized from the theme struct's chrome insets.
        // Every enum-7717 panel gets that hook. So the open question is whether
        // that deferred pass ran at all, and an IF_CLOSESUB is simply the next
        // event that forces the invalidate - which would explain the HUD
        // "appearing on ESC" with nobody re-hiding anything.
        //
        // So unhiding 1477:101 costs the minimap and the action bars and does not
        // visibly gain the backpack. That is a worse screen than leaving it hidden,
        // and shipping it on by default would trade a confirmed-working HUD for
        // nothing.
        //
        // THE OPCODES, now read rather than guessed - the earlier note here named
        // them backwards and pointed at the wrong ops, so it is replaced:
        //   1924 = if_setposition(x, y, xType, yType, component)
        //   1794 = if_setsize(width, height, widthType, heightType, component)
        // Component is the LAST-pushed argument (the shared dispatcher
        // pops it first); the other four are read left-to-right in source order.
        // Bodies: 1794 -> writes base w/h to c+0x40/0x44 and the types
        // to c+0x34/0x35; 1924 -> writes base x/y to c+0x38/0x3c and
        // the types to c+0x32/0x33. Pinned by their resolvers, whose only callers
        // read exactly those fields: size (0=absolute, 1=parent-base,
        // 2=(base*parent)>>14) which writes c+0x58/0x5c;
        // position called from
        // which writes c+0x50/0x54. Corroborated independently by the
        // CS2 idiom in script 8392, which copies A's rect onto B as
        // 1924(getx,gety,0,0,B) then 1794(getwidth,getheight,0,0,B).
        // Ops 106-110 and 156-160 of 9943 are NEITHER of these - they are 1710,
        // if_sethide.
        //
        // Opt IN with: -Dopennxt.experiment.unhideBackpack.after=true
        if (System.getProperty("opennxt.experiment.unhideBackpack.after") == "true") {
 // DEFERRED, not same-frame. The run sent this IF_SETHIDE in
            // the SAME frame as the 9943 that precedes it (#4246 then #4247), which
            // is precisely the confound that made that run uninterpretable. 9943
            // arms a one-shot layout hook per panel and the layout happens after it
            // returns, so a packet landing inside that frame cannot be told apart
            // from one that perturbs it. delayTicks=0 reproduces the old behaviour
            // exactly, as a control.
            backpackUnhideCountdown = BACKPACK_UNHIDE_DELAY_TICKS
            if (BACKPACK_UNHIDE_DELAY_TICKS <= 0) player.interfaces.hide(id = 1477, component = 101, hidden = false)
            logger.warn("backpack unhide RE-ASSERTED after panelApply (OPT-IN, default OFF). " +
                "9943 hides every enum-7717 panel absent from enum 9010 and panel 2 " +
                "(Backpack, slot 1477:101) is absent, so this undoes that. COST: " +
                "with this on, the minimap and both action bars do not lay out and the " +
                "backpack is not visibly gained either - see the KDoc at this site. Off " +
                "by default until the 1477:60 column layout is understood.")
        }

        // The backpack, after every panel above is mounted.
        //
        // Ordering: interface 1473 has to EXIST client-side before its contents
        // mean anything - the container store and the interface store are
        // different client members (0x19988 vs 0x198c0) and nothing links them
        // except the panel's own listener list, so an UPDATE_INV_FULL that
        // arrived first would sit in the store unread until something made the
        // panel rebuild. Sent last, the panel is already up and the transmit
        // itself is the rebuild trigger.
        //
        // Off with -Dopennxt.experiment.inventory=false. See PlayerInventory.
        PlayerInventory.sendBackpack(player)
        // that we never did - 891, worn 94, 895 - and the 22 empty varc strings.
        // -Dopennxt.experiment.ui.loginInvs=true, default OFF; see
        // PlayerInventory.sendLoginContainers for what and why.
        PlayerInventory.sendLoginContainers(player)

        // ==================================================================
        // EXPERIMENT: kick the options-menu script.
        //
        //   -Dopennxt.experiment.ui.optionsMenu=true    (DEFAULT OFF)
        //
        // WHAT IS BEING TESTED, AND WHY IT IS ONE CALL
        //
        // On this server ESC does nothing.
        // IF_BUTTON1 on 1477:8 (op 1 "Options", ESC-bound via optable
        // [[16,0,13,0]]) -> cs2 8181 -> 8182 -> 8177 -> 13831, and 13831's
        // instruction 253 is cs2 784, whose ClientProt 67 was observed on the
        // wire (5 presses, 67 on 1/3/5 - 8182 is a toggle). Every guard passes
        // and 1477:805 is unhidden. What is missing is not the kick: it is that
        // interface 1433 is mounted at 1477:751, in the dialogue box's subtree,
        // while 8177/8179 toggle 1477:805. See the KDoc on [optionsMenuSlot].
        // The companion claim that 9922 having no gosub caller means "nothing in
        // the cache starts it" is also misleading - 13831 is started from the
        // cache, by 8177. On live it opens the OPTIONS MENU -
        // the one carrying 'Edit Layout Mode', which is the door to the whole
        // gameframe layout the client builds its UI from.
        //
        // Read out of the 949 clientscript corpus (21,110 scripts, all decoded):
        //
        //   9922   push 0; gosub 13831; return          <- 3 instructions
        //   13831  'Select a preset to load'
        //   2935   'Hop Worlds' 'Exit to Lobby' 'Logout' 'Report Issue'
        //          'Ribbon Setup' 'Edit Layout Mode'    <- the menu itself
        //
        // and the constants 13831 pushes decode (game_id = iface*65536 + comp) to
        //
        //   96797480 -> 1477:808   where the menu mounts (parent 1477:805)
        //   17956864 ->  274:0     the options-menu interface, 218 components
        //
        // 9922 has NO caller anywhere in the corpus, so nothing in the cache
        // starts it: it is driven from outside, by a client keybind or by
        // RUNCLIENTSCRIPT. This tries the second.
        //
        // WHAT EITHER OUTCOME MEANS
        //
        //   menu appears  -> the ESC path is intact and merely unkicked, and the
        //                    same trace finds the gameframe-build script next.
        //   nothing       -> a script alone is not enough; the frame needs state
        //                    (varcs / layout) set before it, and THAT is the gap.
        //
        // Either way it is one packet and it is off by default, so a normal run
        // is byte-identical to before this block existed.
        if (System.getProperty("opennxt.experiment.ui.optionsMenu") == "true") {
            logger.info("options-menu experiment: RUNCLIENTSCRIPT(9922) - expect " +
                "interface 274 on 1477:808 if the ESC path is intact")
            player.client.write(RunClientScript(script = 9922))
        }

        // ==================================================================
        // EXPERIMENT 2, the other half of the same evidence.
        //
        //   -Dopennxt.experiment.ui.optionsOpen=true    (DEFAULT OFF)
        //
        // RESULT OF EXPERIMENT 1, recorded here so it is not re-run blind:
        // RUNCLIENTSCRIPT(9922) went out (51 sends where there had been 50, and
        // the log line above fired) and NO menu appeared. So a script call on
        // its own is not enough.
        //
        // That was not an encoding fault. The client handler scans
        // for a NUL first - it reads the descriptor STRING before anything else,
        // which is exactly what RunClientScript.Codec writes - and the empty-
        // descriptor branch continues into normal processing
        // rather than an error path.
        //
        // So this tries the opposite model: that 274 is a normal sub-interface
        // the SERVER opens, and the script only populates one that is already
        // there. Both numbers come from script 13831 itself:
        //
        //   17956864 ->  274:0     the interface  (218 components)
        //   96797480 -> 1477:808   where it mounts (parent 1477:805)
        //
        // Run this one ALONE, not with experiment 1, or a menu appearing says
        // nothing about which of the two put it there.
        if (System.getProperty("opennxt.experiment.ui.optionsOpen") == "true") {
            logger.info("options-menu experiment 2: opening interface 274 on 1477:808")
            player.interfaces.open(id = 274, parent = 1477, component = 808, walkable = true)
            player.client.write(RunClientScript(script = 9922))
        }

        // ==================================================================
        // EXPERIMENT: the script-evidenced slot pairings, all in one run.
        //
        //   -Dopennxt.experiment.ui.slotBatch=true          all seven
        //   -Dopennxt.experiment.ui.slotBatch=28,37,690     only these slots
        //                                                   (DEFAULT OFF)
        //
 // *** THIS BATCH CRASHES THE CLIENT. ***
        //
        // Two runs, both reproducible, both identical: access violation
        // 0xc0000005. The faulting chain is
        //
        //     mov rax, [rcx + 0x199a8]   ; a client-side store
        //     mov rcx, [rax + 0x10]
        //     mov rax, [rcx + 0x10]      ; rcx is NULL here
        //
        // i.e. an empty or uninitialised store being walked - the same shape as
        // the UPDATE_STAT crash recorded in serverProtNames.toml, where stat ids
        // were fed into a zero-element array. No anchor in anchors_949.json
        // covers +0x199a8, so the subsystem is unnamed.
        //
        // WHICH of the seven does it is NOT known - all seven go out before the
        // client dies, so the server log cannot attribute it. That is why the
        // flag now takes an explicit list: bisecting costs runs, and a list
        // makes it about three instead of seven.
        //
        // PRIME SUSPECT, from static analysis rather than from bisecting:
        // interface 1422, at slot 1477:37.
        //
        // A byte scan for the displacement (a linear disassembly sweep MISSES
        // sites - it missed the crash site itself) finds 56 references to
        // [client+0x199a8]. Only TWO are writes, both in the constructor and
        // teardown, so nothing populates that store at runtime. About 45 of the
        // reads cluster in 0x14014axxx-0x14014cxxx, and one more sits at
        // - inside the interface hit-walk.
        //
        // Of the seven interfaces here, 1422 is the ONLY one with a non-zero
        // contenttype: three components carrying 1401, 1405 and 1406.
        // contenttype is what wires a component to a special client subsystem.
        // None of the three interfaces known to work here (274, 517, 1184) has
        // a single one.
        //
        // So the first run should be everything EXCEPT 37:
        //   -Dopennxt.experiment.ui.slotBatch=28,56,596,690,703,880
        // If that does not crash, 1422 is the culprit and the remaining six are
        // safe to keep. If it still crashes, fall back to bisecting and the
        // next suspect is the 56/596 pair - the only two slots given the SAME
        // interface (279), which is a plausible way to corrupt a per-interface
        // store.
        //
        // tools/949/slot_resolver.py recovers slot -> interface pairings from
        // clientscripts that hardcode both literals close together. Its scoring
        // is calibrated on two known pairings and neither is mis-ranked; see
        // that file for why the bare-int channel had to be thrown away.
        //
        // These seven are the ones it resolves that the server currently opens
        // NOTHING on, so each is purely additive - nothing that works today can
        // be displaced by them. Each logs its own line, so a screenshot plus the
        // log says which of the seven drew and which did not.
        //
        // DELIBERATELY EXCLUDED, both worth stating:
        //   1477:809 -> 274   808 and 809 both resolve to 274 and are adjacent,
        //                     which is the container/mount shape seen at 747/750.
        //                     808 already works; opening 274 twice could
        //                     break the one thing that does.
        //   1477:27  -> 906   the server already opens 1482 there. That is a
        //                     SWAP, not an addition, and a swap belongs in its
        //                     own run so a regression is attributable.
        //
        // 56 and 596 both resolve to 279. That is not necessarily wrong - one
        // interface can legitimately mount in two slots - but if exactly one of
        // them draws, that asymmetry is the finding.
        val slotBatchProp = System.getProperty("opennxt.experiment.ui.slotBatch")
        if (slotBatchProp != null) {
            val only = slotBatchProp.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
            val batch = listOf(
                28 to 1177,     // Game View
                37 to 1422,
                56 to 279,
                596 to 279,     // Combat Target
                690 to 1431,
                703 to 1622,    // Loot
                880 to 1476
            )
            val chosen = if (only.isEmpty()) batch else batch.filter { it.first in only }
            logger.info("slot-batch experiment: opening ${chosen.size} of ${batch.size} " +
                "script-evidenced pairings" +
                (if (only.isEmpty()) " (all)" else " (restricted to $only)"))
            for ((comp, iface) in chosen) {
                logger.info("slot-batch: 1477:$comp <- interface $iface")
                player.interfaces.open(id = iface, parent = 1477, component = comp,
                    walkable = true)
            }
        }

        // ------------------------------------------------------------------
 // THE LOGIN TAIL (-Dopennxt.experiment.ui.loginTail=false to
        // disable). The recorded real login ends with exactly this pair - it was
        // preserved verbatim, commented out, in LobbyPlayer.kt:681-682 - plus a
        // welcome line. MESSAGE_GAME (21) was implemented and in live use by
        // SkillingWiring but never sent at login, so the chat window sat empty;
        // FRIENDLIST_LOADED (18) is what releases the friends list from its
        // loading state; CHAT_FILTER_SETTINGS_PRIVATECHAT (74) seeds the filter.
        if (System.getProperty("opennxt.experiment.ui.loginTail") != "false") {
            player.client.write(com.opennxt.net.game.serverprot.MessageGame(0, "Welcome to RuneScape."))
            player.client.write(com.opennxt.net.game.serverprot.ChatFilterSettingsPrivatechat(0))
            player.client.write(com.opennxt.net.game.serverprot.FriendlistLoaded)
            logger.info { "ui.loginTail: sent MESSAGE_GAME welcome + CHAT_FILTER_SETTINGS_PRIVATECHAT(0) + FRIENDLIST_LOADED (the recorded login's terminal pair)" }
        }

        // ------------------------------------------------------------------
 // THE RIBBON (-Dopennxt.experiment.ui.ribbon=true, default
        // OFF until run live). Static read (subagent report, evidence in cs2):
        // 8310 = gosub 3379(panel,1,1); 3379 is the canonical client-side "show
        // panel" (unhides the slot from struct param 3503 and runs the whole
        // windowed layout). The ribbon icon builder is 8144 (0 args) and BAILS
        // when width(1431:1)==0 - so it must run AFTER the ribbon panel is
        // shown, and the cache only triggers it from 1431:8's var-transmit hook
        // (varps 3680/3814/3711/4334/6501/12314), which never fires if those
        // varps landed before 1431 opened. Ribbon clicks need NO IF_SETEVENTS -
        // 1431:6 has cache optmask 510 ("Open" x8, onop cs2 5587 -> 5588 ->
        // 8159 toggle / 8287 group dropdown on 1448). Sequence here: show the
        // ribbon panel, then build the icons a tick later.
        // -Dopennxt.experiment.ui.showPanels=<panel,panel,...> (default off): send the
        // client's own canonical show-panel, RunClientScript(3379, [panel, 1, 1]), for each
        // listed panel AFTER the relayout has settled (relayout+7 ticks). 3379 unhides the
        // panel's slot (struct param 3503) and runs the full windowed layout - proven from
        // the wire (it is what 8310 wraps, and the wire-invoked close 8323 is its sibling).
 // Built to test whether the combat bars (panels 1003=toplevel_v2_combat_bar,
        // 1032=combat_bar2) can coexist with the re-seated minimap in windowed mode by simply
        // SHOWING them after everything settles - the cheap lever before more statics on the
        // ring-vs-bars build defect.
        System.getProperty("opennxt.experiment.ui.showPanels")?.let { raw ->
            val ids = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (ids.isNotEmpty()) {
                showPanelsList = ids
                showPanelsCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 7)
            }
        }
        // -Dopennxt.experiment.ui.fillPanels=books|all|off. THE DEFAULT IS "all", not off: this
 // line said "(default off)" until while the code below defaulted to "all",
 // contradicting the "DEFAULT \"all\" since" paragraph eight lines down. The
        // paragraph was right; this line was stale.
        //
 // AGAINST REFERENCE, and NOT acted on - this is for whoever decides it.
 // Diffing our login mount set against 's, on 1477:
        // the reference client opens 61 components at login, we open 64, and the eight we open that the reference client does
        // NOT are EXACTLY this feature's eight fills:
        //     1477:92  <- 1484 subscribe_button      1477:534 <- 1299 group_ironman_child
        //     1477:125 <- 722  summoning_side        1477:644 <- 1731 bxp_countdown
        //     1477:290 <- 1344 minigames_main        1477:652 <- 1591 boss_instance
        //     1477:684 <- 1177 info_box              1477:664 <- 1234 clock_wrapper
        // ("opened 8 name-matched fill(s)" in this run's own log.) The reference client opens none of them at
 // login; they are on-demand panels there. On the operator's screenshot showed
        // the Minigames panel sitting on the HUD at login and asked why it was there - this is
        // why. The default is NOT flipped here, because the evidence that put it at "all" is live
        // and specific (the fills render with real content) and outranks a preference of mine for
        // matching the reference client's mount count. `=off` is the one-flag comparison run.
        //
        // Opens the NAME-MATCHED
        // content interface into each panel dock the replay leaves empty. Matching evidence is
 // the client's interface.sym:
        // e.g. panel "Prayers" dock 1477:136 <- 1457 toplevel_v2_parent_suboverlay_prayer;
        // every pair below is a symbol-table name joined to enum 7716's panel struct (params
        // 3505 = dock). `books` = the ability-book/prayer/magic family; `all` adds the misc
        // HUD (minigames, graphs, clock, boss timer, loot, info box). Opened walkable at the
        // dock, the same shape as every replay panel open (backpack 1473 @ dock 103).
 // DEFAULT "all" since (was opt-in for one run): a test run showed the
        // fills RENDER WITH REAL CONTENT - Minigames came up with the spotlight rotation and
        // the D&D checklist, Loot with its grid and Loot All button, and the ability books
        // joined the window tab strips. `=off` disables entirely.
 // DECIDED: DEFAULT "off". the operator put the reference client's default HUD next to ours the same
 // evening: the reference client's login opens none of the eight, the
        // Minigames panel was the visible difference he asked about twice, and the mount diff
        // above already said 61 vs 64 + 8. "The fills render with real content" is true and is
        // not the question; whether the reference client shows them at login is, and it does not. `=all`
        // is the opt-in now, `=books` still means all.
        (System.getProperty("opennxt.experiment.ui.fillPanels") ?: "off").lowercase().let { mode ->
            if (mode == "books") {
 // finding 3: "books" is RETIRED - the replay provides the book
                // windows (see the collision note below), so there is no books-only set.
                logger.warn { "ui.fillPanels=books is retired (the replay provides the books); treating as \"all\"" }
            }
            if (mode == "books" || mode == "all") {
 // THE BOOKS WERE ALREADY THERE. Run 104106: the replay itself
                // opens the whole toplevel_v2_window_ability_book_* family plus
                // toplevel_v2_prayer (1458, 1460, 1461, 1452, 1883, 1884-1887, 1219-1221),
                // and the Backpack-gate remap seats them at the correct 949 docks - the
                // sym table proved my 14 "book" fills were the WRONG variant
                // (parent_suboverlay_*, the 1448-window overlay family) opened ON TOP of
                // the right ones. The books group is REMOVED; what remains is the misc set
                // the replay genuinely never provides, each guarded by hasSubAt so a fill
                // can never displace an existing mount again.
                val fills = com.opennxt.content.impl.PanelCloseWiring.FILL_PANELS
                var opened = 0
                for ((dock, iface, label) in fills) {
 // (observed live): the Loot window (1622) is a TRANSIENT popup on
                    // live RS3 - it opens when you loot a kill or the ground, never at login.
                    // Filling it at 1477:705 parks an empty loot grid on screen all session.
                    // Off by default; -Dopennxt.experiment.ui.fillPanels.loot=true restores it.
                    if (iface == 1622 && System.getProperty("opennxt.experiment.ui.fillPanels.loot") != "true") {
                        logger.info { "ui.fillPanels: SKIP 1477:$dock ($label) - transient loot popup, not a login panel" }
                        continue
                    }
                    if (player.interfaces.hasSubAt(1477, dock)) {
                        logger.info { "ui.fillPanels: SKIP 1477:$dock ($label) - already occupied" }
                        continue
                    }
                    logger.info { "ui.fillPanels: 1477:$dock <- $iface  ($label)" }
                    player.interfaces.open(id = iface, parent = 1477, component = dock, walkable = true, native949 = true)
                    opened++
                }
                logger.warn { "ui.fillPanels[$mode]: opened $opened name-matched fill(s); book fills removed (replay provides them)" }
            } else if (mode.isNotEmpty() && mode != "off") {
                logger.warn { "opennxt.experiment.ui.fillPanels=$mode is not books|all|off; ignored" }
            }
        }

        // -Dopennxt.experiment.ui.invResend=false to disable. The backpack window renders
        // TRANSPARENT (no slot grid) in windowed mode while UPDATE_INV_FULL goes out AFTER
        // the window is built (run 104106: mount ~13.x s, inv send #4209 at 14.1s) - the
        // same ordering family as the varp re-send: a listener cursor initialised at build
        // time never sees the earlier transmit. Re-send the backpack a few ticks after the
        // relayout so the window has data to draw. If the grid appears, this belongs in the
        // login sequence proper.
 // THE LATE 3680 TRANSMIT (-Dopennxt.experiment.ui.varp3680Late=false to
        // disable; sends the CURRENT default value, bits unchanged). Static chain that makes
        // it the backpack-grid AND ribbon-icon candidate in one packet:
        //   * 1473:0 (inventory root) listeners = [[3680, 3704, 2180], [93], ...] with
        //     trigger 18 (on-var-transmit) = 8677 -> 8678, the SAME builder as its onload -
        //     a 3680 transmit re-runs the whole grid build after the window has real size.
        //     The grid rendered in modern-HUD runs, which sent a late 3680 (the bit-21
        //     experiment), and went transparent exactly when the windowed default removed
        //     that send - run 105307 proved data alone (UPDATE_INV_FULL x2) does nothing.
        //   * 1431:8 (ribbon) hook 18/37 -> 13833 -> ... -> 8144, the icon builder that
 // never fires because 3680 lands before 1431 opens.
        // One transmit, after the relayout has given the windows size.
 // REFUTED run 113625, and the refutation carries information: the
        // transmit fired and NO hook ran (no grid, no icons) - while the bit-21 runs, whose
        // 3680 sends CHANGED the value, demonstrably fired 20507/8781. Inference: this
        // client fires varp listeners on VALUE CHANGE only, not on any transmit. Default
        // now OFF; kept as the control for that inference.
        if (System.getProperty("opennxt.experiment.ui.varp3680Late") == "true") {
            varp3680LateCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 5)
        }

        // THE DIRECT INVOKE instead (-Dopennxt.experiment.ui.rebuildInv=false to disable):
        // 1473:0's on-load and on-var-transmit are the SAME script pair (8677 -> 8678, the
        // grid builder; 8682 inside resolves the six grid parts for 1473:0 or 1474:3). No
        // varp semantics needed - call the builder by id with the component argument, after
        // the windows have size, exactly the pattern that carried 8323 (close), 3379 (show)
        // and 8409 (slot rebuild). 96534528 = 1473:0.
 // REVIEW: opt-in (RUN2b/2c: no effect; the grid is not a builder problem - UI-A1).
        if (System.getProperty("opennxt.experiment.ui.rebuildInv") == "true") {
            rebuildInvCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 6)
        }

 // -Dopennxt.experiment.ui.fullPack=false to disable.
        // THE PACK IS THE MISSING LOGIN STEP - disasm evidence: 8705(panel), the per-panel
        // layout pass (called by 3379 show, 8304 tab toggle, and the drag path), reads
        // VARC 3475 as a dirty flag: ==1 -> run 8702(9,8), the FULL windowed packer, then
        // clear the flag; else only the incremental 8708(panel,8). IF_OPENSUB alone never
        // reaches any of this, and nothing in our login sequence sets the flag - which is
        // why chat windows only materialized when the player DRAGGED (drag -> 8705 -> pack), why
        // the backpack grid ccs (built unconditionally at 1473:0 onload -> 8677 -> 8678,
        // no varbit gate: varbit 22875 field is 0 on our default varp 3814=24 -> the
        // VISIBLE sprite 18266 branch) sit in a never-packed container, and why rebuildInv
        // alone was negative. So: set varc 3475 = 1, then send the canonical show for the
        // Backpack panel (2) - 8705 sees the dirty flag and packs the WHOLE layout once.
        if (System.getProperty("opennxt.experiment.ui.fullPack") != "false") {
            fullPackCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 4)
        }

        // REFUTED AS THE BACKPACK FIX, run 105307: the re-send fired (UPDATE_INV_FULL twice
        // on the wire) and the grid still did not draw - the windowed backpack needs more
        // than data. Name-table lead for next session: 1474 = toplevel_v2_parent_suboverlay_
        // inventory may be the actual grid surface, 1473 only the chrome. Default now OFF.
        if (System.getProperty("opennxt.experiment.ui.invResend") == "true") {
            invResendCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 3)
        }

        if (System.getProperty("opennxt.experiment.ui.ribbon") == "true") {
 // +6 (was +2) run 163700: the show + 8144 fired but no icons drew -
            // 8144's width(1431:1)==0 bail is the suspect, so the build now lands after the
            // probe/arming window when the layout has settled.
            ribbonCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 6)
        }

        // ------------------------------------------------------------------
 // UI PASS (notes/UI-A1-backpack-slots.md, UI-A2A3-ribbon-bars.md,
        // UI-B1B3-menu.md, UI-A6-feeds.md - four static reads of the 949 cs2 + native).
        //
        // RIBBON BUILD - -Dopennxt.experiment.ui.ribbonBuild=false to disable (DEFAULT ON).
        // The ribbon icon cc-children are CREATED by 13845 (via 13833 -> 13843), never by
        // 8144: 8144 is only the positioner (its loop skips any icon whose cc pair does not
        // exist - ops 322-333). The cache reaches 13833 solely from 1431:8's var-transmit
        // hook (varps 3680/3814/3711/4334/6501/12314), which fires on VALUE CHANGE only, and
        // those varps land before 1431 opens - so on our login the creator never runs and
        // `ui.ribbon` (3379 + 8144) could never draw an icon: both of its sends were
        // positioners with nothing to position (explains runs 163700 and the +6 retest).
        // The lever is the hook call itself, args identical to the cache blob
        // (1431 file 8, offsets 50/70): RunClientScript(13833, [1431:0, 1431:12, 0]),
        // sent after 1431 is open and the slot has been laid out (the 8884[8] relayout),
        // then RunClientScript(8144, []) three ticks later as the belt-and-braces
        // reposition (idempotent; 8702/8705 never touch 1431, so a later relayout does
        // not destroy the cc's). 8144 needs width(1431:1) > 0 on its tick: 1431:1 is
        // aw=ah=1 of its mount, i.e. the mount's own width - 352 in dock 64 (rows=ribbon),
        // width(1477:27) = the window width at the replay seat 59 after 8884 sizes 27.
        // that this alone makes icons DRAW (61's ancestry must also be unhidden).
        // READ THE SCREEN: ribbon icons appearing = the creator was the missing step.
 // REVIEW (REVIEW-B rank 8): fire AFTER the last 8884 (relayout + again + 4) so 8144 positions
        // into a laid-out dock; never drew an icon yet (13 runs) - kept ON because it is harmless and the dock
        // is now the default seat (rows=all).
        if (System.getProperty("opennxt.experiment.ui.ribbonBuild") != "false") {
            ribbonBuildCountdown = hudRepair(RELAYOUT_DELAY_TICKS + RELAYOUT_AGAIN_TICKS + 4)
        }

        // BACKPACK PROBE - -Dopennxt.experiment.ui.backpackProbe=size|unhide|reopen (default
        // off; ONE per run - they discriminate, they do not combine). UI-A1: the slot
        // layer is 1473:4/5 (children of the scroll container 1473:2), 8678/8680 have NO
        // server-dependent gate, cannot early-out or fault, and the client-side
        // measurement already showed 28 children per layer and own-inv 93 present
        // (record 186 = (93<<1)|0). So the grid BUILDS and has nothing to draw into: the
        // only things that blank a built grid are in the container chain
        // 1477:101 (authored hidden, 224x288) -> 1477:103 (100% of 101) -> 1473:0 -> 1473:2.
        // Each probe is one send at relayout+8, after every layout pass:
        //   size    RunClientScript(11145, [224, 255, 0, 0, 1473:0]) - force 1473:0 to
 // 224x255 absolute; hook 44 (onresize) re-runs 8680.
        //           Slots appear = the dock/pack size is the defect (size the root, or
        //           open 1473 after 101 has its size - struct 21285 props 3495-3497 next).
        //   unhide  IF_SETHIDE(1477:101, false) + IF_SETHIDE(1477:103, false); run with
        //           ui.fullPack=false (3379(2,1,1) is a TOGGLE when panel 2 is already
        //           shown). Slots appear = visibility; then find the hider on the wire.
        //   reopen  re-send IF_OPENSUB(1473 -> 1477:103) so onload 8677 -> 8678 -> 8680
        //           runs on a sized dock. Slots appear = mount-before-size ordering.
        System.getProperty("opennxt.experiment.ui.backpackProbe")?.trim()?.lowercase()?.let { mode ->
            if (mode in setOf("size", "unhide", "reopen")) {
                backpackProbeMode = mode
                backpackProbeCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 8)
            } else {
                logger.warn { "ui.backpackProbe=$mode is not size|unhide|reopen; ignored" }
            }
        }

        // BAR RELAYOUT AFTER THE RING - -Dopennxt.experiment.ui.barRelayoutAfterRing=true
        // (default off). UI-A3: the main bar's real build is 8109 = 8110(1003) on 1430:0's
        // onload, reading the dock's cc param 3537 that 8411 stored when 1477 opened; the
        // 8391 -> 2141 -> 8110 hook is a ONE-SHOT that fires before 3537 is set (so its
        // "8138 returns -1" bail is by design, not the defect). Seating the ring (1465 at
        // 94) runs 8705(1004) over the shared enum-7717 packer list, and 8705 contains no
        // sethide - so the bars vanishing with the ring is PLAUSIBLY placement order in
        // 8708/8702. Diagnostic: re-show the main bar AFTER 1465 is open and everything
        // has settled - RunClientScript(3379, [1003, 1, 1]) at relayout+8 (3379 -> 8705(1003)
        // re-places the bar after the minimap's pass; 8391 -> 2141 -> 8110 rebuilds it with
        // the stored dims). Toggle hazard: only meaningful when 1003 is NOT already shown.
        // READ THE SCREEN: bar back with the ring still there = placement order; fold the
        // re-show into the login sequence after the ring.
        if (System.getProperty("opennxt.experiment.ui.barRelayoutAfterRing") == "true") {
            barRelayoutCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 8)
        }

        // RUN ENERGY / WEIGHT - -Dopennxt.experiment.ui.runEnergy=false to disable (DEFAULT
        // ON). UI-A6: the minimap ring's "100%" text is NATIVE - no cs2 op in 1315/1316/1534/
        // 1741 reads a var for it; it is the client's own run-energy field, fed only by
        // UPDATE_RUNENERGY (ServerProt 92, u8 -> stats object +0x18) and UPDATE_RUNWEIGHT
        // (ServerProt 0, s16 -> +0x1c, the weight tooltip). Neither has ever been on our
        // wire. Both handlers write through
        // [ctx+0x198e0]->[0x7618] with NO null check - the SAME stats object UPDATE_STAT's
        // NULL until the client's world-model stage reaches 4. So this is deferred to the
        // relayout tick (RELAYOUT_DELAY_TICKS + 1, ~6 s after REBUILD_NORMAL), not sent at
        // login. DISCRIMINATOR if the client dies on it: fault address 0x18 / 0x1c at
        // / = still too early -> raise
        // -Dopennxt.experiment.hudMount.relayout.delayTicks, and sendStats would die the
        // same way. Values: energy 100 (u8 0..100), weight 0 (kg, signed on the client).
 // REVIEW: decoupled from the relayout - tick 11 is the PROVEN-safe point (RUN3b/3g/4a/4b).
        // -Dopennxt.experiment.ui.runEnergy.delayTicks (default 11).
        if (System.getProperty("opennxt.experiment.ui.runEnergy") != "false") {
            runEnergyCountdown = RUN_ENERGY_DELAY_TICKS
        }

 // RAW REFERENCE REPLAY - see content/impl/Replay949.kt.
        if (com.opennxt.content.impl.Replay949.enabled && !com.opennxt.content.impl.Replay949.only) {
            replay949Cursor = 0
            replay949Countdown = com.opennxt.content.impl.Replay949.delayTicks
            logger.warn { "replay949: ARMED - ${com.opennxt.content.impl.Replay949.size()} packet(s) start in ${replay949Countdown} tick(s)" }
        }

        // TODO Rebuild [region | dynamic] packet
    }

    override fun tick() {
        // This was `...values["PLAYER_INFO"]!!`, and on build 949 that `!!`
        // threw on EVERY TICK OF EVERY LOGGED-IN PLAYER.
        //
        // data/prot/949/serverProtNames.toml has 17 names and PLAYER_INFO is not
        // among them; the boxed fastutil get returns null for an absent key, and
        // `!!` turned that into a NullPointerException one frame inside
        // World.tick(). Because TickEngine submitted tickables straight to
        // scheduleAtFixedRate, that throw did not just drop one packet - it
        // SILENTLY UNSCHEDULED THE WORLD, permanently, with no log line
        // anywhere. See TickEngine.submitTickable for the measurement; the catch
        // there is the other half of this fix and the more important one.
        //
        // Degrading matches how every other unmapped packet is already handled
        // (ConnectedClient.write logs and returns rather than throwing). Warned
        // once per process, not once per tick, because at 600 ms per tick per
        // player the un-limited version would be the log.
        // Consume one tick of queued movement BEFORE the player is encoded.
        //
        // This call did not exist for a real WorldPlayer. `entity.movement.process()`
        // player's step queue filled up and was never drained: a click queued 64
        // steps and the player stayed exactly where they were, forever. Measured
        // directly - every MOVE_GAMECLICK after the first reported the same origin
        // (3222,3222) while the queue sat full behind it.
        //
        // Order matters. process() advances entity.location and sets
        // nextWalkDirection / nextRunDirection, and PlayerInfoEncoder below reads
        // exactly those. Running it after the encode would transmit the previous
        // tick's position every tick - a permanent one-tick lag that looks like
        // rubber-banding and is miserable to diagnose later.
        // Fire the deferred UPDATE_STAT burst if it is due. Armed at
        // REBUILD_NORMAL (see added()); this is the "+N ticks later" the client
        // needs before its skill-record vector exists. One-shot: the countdown
        // is only re-armed by another REBUILD_NORMAL.
        if (statBurstCountdown > 0) {
            statBurstCountdown--
            if (statBurstCountdown == 0) {
                logger.warn {
                    "EXPERIMENT: firing the deferred UPDATE_STAT burst now " +
                        "(${WorldPlayer.STAT_SEND_DELAY_TICKS} tick(s) after REBUILD_NORMAL)"
                }
                stats.init()
            }
        }

        if (rerun8110Countdown > 0) {
            rerun8110Countdown--
            if (rerun8110Countdown == 0) {
                client.write(RunClientScript(script = 8110, args = arrayOf(1003)))
                logger.warn {
                    "hudMount.rerun8110: sent RunClientScript(8110, [1003]) ${WorldPlayer.RERUN_8110_DELAY_TICKS} " +
                        "tick(s) after the varp-3680 transmit - the main action bar's own build, which the " +
                        "additional bars get through 8310 -> 3379 -> 8391 -> 2141 and the main bar never does. " +
                        "PREDICTION: 1430 in dock 1477:70 draws. If not, 8115's if_getwidth(1430:6) is not the issue."
                }
            }
        }

        if (probe67Countdown > 0) {
            probe67Countdown--
            if (probe67Countdown == 0) {
                interfaces.hide(id = 1477, component = 67, hidden = false)
                client.write(RunClientScript(script = 11145, args = arrayOf(352, 128, 0, 0, 96796739)))
                client.write(RunClientScript(script = 13268, args = arrayOf(464, 300, 0, 0, 96796739)))
                logger.warn {
                    "hudMount.probe67: forced 1477:67 (main action bar slot) visible, 352x128 at " +
                        "(464,300) via cache wrappers 11145/13268, ${WorldPlayer.PROBE67_DELAY_TICKS} tick(s) " +
                        "after login. READ THE SCREEN: bar mid-screen = built-but-mislaid (8706 layout is " +
                        "the defect); nothing = the live-mode build of 1430 is the defect."
                }
            }
        }

        if (relayoutCountdown > 0) {
            relayoutCountdown--
            if (relayoutCountdown == 0) {
 // MODE (notes/UI-C1-layout-system.md): 8884(mode) = 8781(mode) applies layout
                // slot <mode> to the components; 8 = the LIVE store (varcs), which our login poisons - 8707's
                // snapshot records shown=0 for every panel whose 3503 slot is cache-hidden (minimap 92, bars
                // 67/72/77/87), so each 8884[8] re-hides them (13 test runs, 16). 15 = the CACHE DEFAULT
                // layout (enum 15511 -> struct params 3482-3488, shown=1) and 8885(15) re-seeds slot 8 from
                // it. -Dopennxt.experiment.hudMount.relayout.mode=15 is the repair; THE DEFAULT IS
 // 15, not 8 - this sentence said "default stays 8" until while
                // RELAYOUT_MODE was already 15. Whole block now off by default anyway: see
                // [HUD_REPAIR].
                client.write(RunClientScript(script = 8884, args = arrayOf(RELAYOUT_MODE)))
 // AGAIN RUN4b: the backpack show (fullPack, +4) and the bar shows (+7) snapshot
                // the store and re-hide the minimap that [15] had just placed. -Dopennxt.experiment.
                // hudMount.relayout.again=N (default 0 = off) re-sends 8884[mode] N ticks after this one,
                // i.e. AFTER those shows, so the cache Default is the last word.
                if (RELAYOUT_AGAIN_TICKS > 0 && relayoutAgainCountdown == 0) relayoutAgainCountdown = RELAYOUT_AGAIN_TICKS + 1   // +1: decremented in this same pass
                logger.warn {
                    "hudMount.relayout: sent RunClientScript(8884, [$RELAYOUT_MODE]) - the client's whole-HUD relayout " +
                        "driver, the wire equivalent of a manual window resize. THE SENTENCE THAT USED TO END " +
                        "THIS LINE IS it read \"minimap block snaps into place = mechanism confirmed, " +
                        "belongs in the login sequence\", and STATE.md retraction 7 withdrew it - 8884 is the " +
                        "WIPE, it wipes minimap and both bars. Printing a refuted claim on every run is how a " +
                        "reader ends up believing one."
                }
            }
        }

        if (replay949Countdown > 0) {
            replay949Countdown--
            if (replay949Countdown == 0) {
                val left = com.opennxt.content.impl.Replay949.step(this, replay949Cursor)
                replay949Cursor = com.opennxt.content.impl.Replay949.size() - left
                if (left > 0) replay949Countdown = 1
                else {
                    logger.warn { "replay949: DONE - ${replay949Cursor} packet(s) replayed. READ THE SCREEN." }
                    if (com.opennxt.content.impl.Replay949.only) {
 // RUN6b: the replayed the reference client UPDATE_INV_FULLs put REFERENCE's items in the client's
                        // backpack/worn, so every item click carried the reference client item id at the reference client slot and nothing
                        // matched our containers (wield/eat did nothing). Overwrite with OURS now that the windows
                        // exist - the normal login line that sends these was skipped in only-mode.
                        PlayerInventory.sendBackpack(this)
                        PlayerInventory.sendWorn(this)
                        logger.warn { "replay949[only]: sent OUR backpack + worn over the replayed the reference client inventories" }
                    }
                }
            }
        }

        if (relayoutAgainCountdown > 0) {
            relayoutAgainCountdown--
            if (relayoutAgainCountdown == 0) {
                client.write(RunClientScript(script = 8884, args = arrayOf(RELAYOUT_MODE)))
                logger.warn { "hudMount.relayout.again: re-sent RunClientScript(8884, [$RELAYOUT_MODE]) $RELAYOUT_AGAIN_TICKS tick(s) after the first - after the panel shows. READ THE SCREEN: minimap ring back and staying = the shows' snapshot was the re-hider." }
            }
        }

        if (redim1003Countdown > 0) {
            redim1003Countdown--
            if (redim1003Countdown == 0) {
                client.write(RunClientScript(script = 8140, args = arrayOf(1003, 0, 0)))
                client.write(RunClientScript(script = 8110, args = arrayOf(1003)))
                logger.warn {
                    "hudMount.redim1003: sent RunClientScript(8140, [1003, 0, 0]) then " +
                        "RunClientScript(8110, [1003]) - re-store real dims over the live-mode (0,0) in the " +
                        "dock's cc param 3537, then rebuild the main bar. READ THE SCREEN: main bar draws in " +
                        "its dock = the (0,0) dims are the bar defect."
                }
            }
        }

        if (fullPackCountdown > 0) {
            fullPackCountdown--
            if (fullPackCountdown == 0) {
                client.write(ClientSetvarcSmall(3475, 1))
                client.write(RunClientScript(script = 3379, args = arrayOf(2, 1, 1)))
                logger.warn {
                    "ui.fullPack: sent SETVARC(3475=1) + RunClientScript(3379, [2, 1, 1]) - the layout " +
                        "dirty flag plus the canonical show for the Backpack panel, so 8705 runs the FULL " +
                        "packer 8702(9,8). READ THE SCREEN: backpack slot grid appearing AND the windows " +
                        "snapping into their grouped login arrangement (chat bottom-left) without dragging = " +
                        "the pack was the missing login step; grid still absent = the defect is below layout."
                }
            }
        }

        if (rebuildInvCountdown > 0) {
            rebuildInvCountdown--
            if (rebuildInvCountdown == 0) {
                client.write(RunClientScript(script = 8678, args = arrayOf(96534528)))
                logger.warn {
                    "ui.rebuildInv: sent RunClientScript(8678, [1473:0]) - the backpack grid builder, invoked " +
                        "directly after the windows settled. READ THE SCREEN: slot grid appearing = the missing " +
                        "piece was the rebuild trigger (value-change-only listeners), and this belongs in the login sequence."
                }
            }
        }

        if (varp3680LateCountdown > 0) {
            varp3680LateCountdown--
            if (varp3680LateCountdown == 0) {
                client.write(VarpLarge(3680, DefaultVariables.varp3680Default()))
                logger.warn {
                    "ui.varp3680Late: re-transmitted varp 3680 (value unchanged) after the windows settled - " +
                        "fires every 3680 var-transmit hook: 1473:0's 8677/8678 (backpack grid rebuild), " +
                        "1431:8's 13833/8144 (ribbon icons, when mounted), 20507 (9943). READ THE SCREEN: " +
                        "backpack slot grid appearing = the windowed default lost this send when bit-21 went; " +
                        "it then belongs in the login sequence."
                }
            }
        }

        if (invResendCountdown > 0) {
            invResendCountdown--
            if (invResendCountdown == 0) {
                com.opennxt.model.entity.player.PlayerInventory.sendBackpack(this)
                logger.warn { "ui.invResend: re-sent the backpack inventory (UPDATE_INV_FULL) after the windows settled - the transmit-after-build ordering probe" }
            }
        }

        if (showPanelsCountdown > 0) {
            showPanelsCountdown--
            if (showPanelsCountdown == 0) {
                showPanelsList.forEach { p ->
                    client.write(RunClientScript(script = 3379, args = arrayOf(p, 1, 1)))
                }
                logger.warn {
                    "ui.showPanels: sent RunClientScript(3379, [panel, 1, 1]) for $showPanelsList - " +
                        "the canonical show-panel, after the relayout. READ THE SCREEN: listed panels drawing = " +
                        "they only needed showing; still absent = the build defect is deeper than visibility."
                }
            }
        }

        if (ribbonCountdown > 0) {
            ribbonCountdown--
            if (ribbonCountdown == 1) {
                client.write(RunClientScript(script = 3379, args = arrayOf(1002, 1, 1)))
                logger.warn { "ui.ribbon: sent RunClientScript(3379, [1002, 1, 1]) - the client's own show-panel for the Ribbon" }
            }
            if (ribbonCountdown == 0) {
                client.write(RunClientScript(script = 8144, args = arrayOf()))
                logger.warn {
                    "ui.ribbon: sent RunClientScript(8144) - the ribbon icon builder (bails unless width(1431:1) > 0, " +
                        "hence one tick after the show). READ THE SCREEN: ribbon icons appearing = the reopen path is live; " +
                        "left-clicking an icon toggles its window (5587 -> 5588 -> 8159/8287, cache-armed optmask 510)."
                }
            }
        }

        if (ribbonBuildCountdown > 0) {
            ribbonBuildCountdown--
            if (ribbonBuildCountdown == 0) {
                client.write(RunClientScript(script = 13833, args = arrayOf((1431 shl 16) or 0, (1431 shl 16) or 12, 0)))
 // A test run (rows=popups, 1431 at the replay seat 59) showed the icons APPEARING after
                // this send and were GONE later - the creator works. Suspect for the vanish: the 8144 re-send
                // below re-positions them inside 1431:1, which at seat 59 is a 10 px strip (1477:59 h=10), so
                // they are clipped away. -Dopennxt.experiment.ui.ribbonBuild.position=false skips the 8144
                // follow-up (the discriminator); rows=ribbon seats 1431 in its 352x64 dock 64 (the fix).
                if (System.getProperty("opennxt.experiment.ui.ribbonBuild.position") != "false") {
                    ribbonPositionCountdown = 3 + 1   // +1: the next block decrements it in this same tick pass
                } else {
                    logger.warn { "ui.ribbonBuild.position=false: NOT re-sending 8144 - icons stay where 13845 created them" }
                }
                logger.warn {
                    "ui.ribbonBuild: sent RunClientScript(13833, [1431:0, 1431:12, 0]) - the cache's own 1431:8 " +
                        "var-transmit hook call (15781; 13843 -> 13845 CREATES the icon cc's; 8144 positions). " +
                        "8144 re-sent in 3 ticks. READ THE SCREEN: ribbon icons = the creator was the missing step."
                }
            }
        }

        if (ribbonPositionCountdown > 0) {
            ribbonPositionCountdown--
            if (ribbonPositionCountdown == 0) {
                client.write(RunClientScript(script = 8144, args = arrayOf()))
                logger.warn { "ui.ribbonBuild: sent RunClientScript(8144) - the ribbon icon positioner, 3 ticks after the creator" }
            }
        }

        if (backpackProbeCountdown > 0) {
            backpackProbeCountdown--
            if (backpackProbeCountdown == 0) {
                when (backpackProbeMode) {
                    "size" -> {
                        client.write(RunClientScript(script = 11145, args = arrayOf(224, 255, 0, 0, (1473 shl 16) or 0)))
                        logger.warn { "ui.backpackProbe=size: sent RunClientScript(11145, [224, 255, 0, 0, 1473:0]) - forced 1473:0 to 224x255 absolute (hook 44 onresize re-runs 8680). READ THE SCREEN: slots = the dock/pack size is the defect." }
                    }
                    "unhide" -> {
                        interfaces.hide(id = 1477, component = 101, hidden = false)
                        interfaces.hide(id = 1477, component = 103, hidden = false)
                        logger.warn { "ui.backpackProbe=unhide: sent IF_SETHIDE(1477:101, false) + IF_SETHIDE(1477:103, false). READ THE SCREEN: slots = visibility; find the hider on the wire. (Run with ui.fullPack=false.)" }
                    }
                    "reopen" -> {
                        interfaces.open(id = 1473, parent = 1477, component = 103, walkable = true, native949 = true)
                        logger.warn { "ui.backpackProbe=reopen: re-sent IF_OPENSUB(1473 -> 1477:103) after the layout settled (onload 8677 -> 8678 -> 8680 on a sized dock). READ THE SCREEN: slots = mount-before-size ordering." }
                    }
                }
            }
        }

        if (barRelayoutCountdown > 0) {
            barRelayoutCountdown--
            if (barRelayoutCountdown == 0) {
                client.write(RunClientScript(script = 3379, args = arrayOf(1003, 1, 1)))
                logger.warn { "ui.barRelayoutAfterRing: sent RunClientScript(3379, [1003, 1, 1]) after the ring settled - re-places (8705) and rebuilds (8391 -> 2141 -> 8110) the main bar. READ THE SCREEN: bar back with the ring = placement order in 8708/8702." }
            }
        }

        if (runEnergyCountdown > 0) {
            runEnergyCountdown--
            if (runEnergyCountdown == 0) {
 // THE PLAYER'S OWN RESERVE - this was a hardcoded 100 and the only
                // UPDATE_RUNENERGY this server ever sent. It now carries what the save restored
                // and marks it sent, so the per-tick sender in tick() does not repeat it, and the
                // run varp goes out beside it so the orb starts in the state the player left it.
                com.opennxt.content.impl.RunToggle.sendLogin(this)
                client.write(com.opennxt.net.game.serverprot.generated.UpdateRunweight(weight = 0))
                // single frames the reference client sends once per login that we never did,
                // at the reference client's position - RESET_ANIMS and MINIMAP_TOGGLE(0) come
                // straight after UPDATE_RUNENERGY in both sessions; CAM_RESET
                // sits later, between two RUNCLIENTSCRIPT bursts, and is put
                // here because its position carries no data. All three are
                // identical on both recorded accounts. Behind
                // -Dopennxt.experiment.ui.loginSingles=true (default OFF): an
 // DEFAULT ON since evening (proven on run server-20260905-170912 with the
                if (System.getProperty("opennxt.experiment.ui.loginSingles") != "false") {
                    client.write(com.opennxt.net.game.serverprot.ResetAnims)
                    client.write(com.opennxt.net.game.serverprot.generated.MinimapToggle(mode = 0))
                    client.write(com.opennxt.net.game.serverprot.CamReset)
                    logger.info { "ui.loginSingles: sent RESET_ANIMS, MINIMAP_TOGGLE(0), CAM_RESET (the reference client's per-login singles)" }
                }
                // UI-A6 1 / REVIEW-B rank 6: 5653 = the XP tracker's baseline snapshot (reads op 179 per skill
                // into varcs 1243..). It ran at 1213's onload BEFORE the UPDATE_STAT burst landed; re-run it now
                // so the tracker rows stop reading 0/0. -Dopennxt.experiment.ui.xpBaseline=false to skip.
                if (System.getProperty("opennxt.experiment.ui.xpBaseline") != "false") {
                    client.write(RunClientScript(script = 5653, args = arrayOf()))
                }
                logger.warn {
                    "ui.runEnergy: sent UPDATE_RUNENERGY(100) + UPDATE_RUNWEIGHT(0) - first time on our wire. " +
                        "READ THE SCREEN: the ring's energy text reads 100%. A client death with fault address " +
                        "0x18/0x1c = stats object still NULL -> raise hudMount.relayout.delayTicks."
                }
            }
        }

        if (armFramesCountdown > 0) {
            armFramesCountdown--
            if (armFramesCountdown == 0) {
                com.opennxt.content.impl.PanelCloseWiring.armFrameOps(this)
            }
        }

        if (close8323Countdown > 0) {
            close8323Countdown--
            if (close8323Countdown == 0) {
                client.write(RunClientScript(script = 8323, args = arrayOf(close8323Panel, 0)))
                logger.warn {
                    "ui.close8323: sent RunClientScript(8323, [$close8323Panel, 0]) - the client's own " +
                        "window-close routine, invoked from the wire. READ THE SCREEN: that window vanishing " +
                        "proves the close code runs and only the click arming was missing."
                }
            }
        }

        if (rebuildCountdown > 0) {
            rebuildCountdown--
            if (rebuildCountdown == 0) {
                rebuildPanels.forEach { p ->
                    client.write(RunClientScript(script = 8409, args = arrayOf(p)))
                }
                relayoutCountdown = 2
                logger.warn {
                    "hudMount.rebuild: re-ran the slot build (RunClientScript(8409, [panel])) for panels " +
                        "$rebuildPanels, 6 tick(s) after the relayout; one more 8884[8] follows in 2 ticks. " +
                        "READ THE SCREEN: ribbon/bar appearing now = the build order (bars before the minimap " +
                        "settles) is the defect and the rebuild belongs in the login sequence."
                }
            }
        }

        if (probeCompCountdown > 0) {
            probeCompCountdown--
            if (probeCompCountdown == 0) {
                interfaces.hide(id = 1477, component = probeCompId, hidden = false)
                client.write(RunClientScript(script = 11145, args = arrayOf(352, 128, 0, 0, (1477 shl 16) or probeCompId)))
                client.write(RunClientScript(script = 13268, args = arrayOf(464, 300, 0, 0, (1477 shl 16) or probeCompId)))
                logger.warn {
                    "hudMount.probe.comp: forced 1477:$probeCompId visible, 352x128 at (464,300), " +
                        "4 tick(s) after the relayout. READ THE SCREEN: content mid-screen = built-but-mislaid; " +
                        "nothing = not built."
                }
            }
        }

        if (backpackUnhideCountdown > 0) {
            backpackUnhideCountdown--
            if (backpackUnhideCountdown == 0) {
                interfaces.hide(id = 1477, component = 101, hidden = false)
                logger.warn {
                    "backpack unhide re-asserted ${WorldPlayer.BACKPACK_UNHIDE_DELAY_TICKS} " +
                        "tick(s) after RUNCLIENTSCRIPT(9943), deliberately NOT in its frame. " +
                        "READ THE SCREEN NOW: if the minimap and both action bars draw WHILE " +
                        "the backpack is up, the retracted 'column cannot hold two panels' " +
                        "reading is dead for good and the defect was same-frame ordering. If " +
                        "the HUD still does not draw, ordering is NOT the cause either and the " +
                        "next thread is the deferred layout hook - 9943 ops 376-387 arm op 278 " +
                        "with script 9933, which gosubs 8390 -> 8391, and 8391 is what actually " +
                        "positions and sizes a panel. Expected backpack rect on a 1280x720 " +
                        "frame: 210x315 at (1070, 333), i.e. below the minimap's (1056, 0) " +
                        "224x224, disjoint by 109px."
                }
            }
        }

 // THE RUN TOGGLE DRIVES THE SPEED - this line was `entity.movement.process`.
        // Before it, `Movement.speed` was `MovementSpeed.RUN` from the moment a PlayerEntity was
        // constructed and nothing on the server ever wrote it again, so every player ran for ever
        // and the client's run orb was decoration: toggling it moved a sprite and nothing else.
        //
        // [Movement.processPlayerTick] is the same `process()` with the toggle asserted before it
        // and the reserve charged after it; it returns whether two tiles were actually taken. It
        // the real sequence - `WorldPlayer` is not constructible without a `ConnectedClient`.
        val ranThisTick = entity.movement.processPlayerTick()

        // The UPDATE_RUNENERGY frame this tick produced, if the displayed percent changed. BEFORE
        // the encoder for the same reason movement is: the frame and the PLAYER_INFO step that
        // caused it belong to the same tick.
        com.opennxt.content.impl.RunToggle.tick(this, ranThisTick)

        // Re-centre the scene if the walk has taken the player far enough from
        // where it was built. Must run AFTER movement (the new position is what
        // decides) and BEFORE the encoder, so a rebuild and the player deltas
        // that depend on it never arrive in the wrong order.
        viewport.rebuildIfNeeded()

        val opcode = OpenNXT.protocol.serverProtNames.values["PLAYER_INFO"]
        if (opcode == null) {
            if (!playerInfoUnmappedWarned) {
                playerInfoUnmappedWarned = true
                logger.warn {
                    "Build ${OpenNXT.protocol.effectiveBuild} has no opcode for PLAYER_INFO - player movement and " +
                        "appearance are NOT being sent to any client. Recover the opcode into " +
                        "data/prot/<build>/serverProtNames.toml to fix. (Warned once.)"
                }
            }
        } else {
            playerInfoTicks++

            // Appearance RE-SEND, now OFF by default. I added it as insurance
            // against the client missing the single appearance block, firing on
            // ticks 10 and 30. On the very next three runs the client died after
            // 10, 11 and 12 PLAYER_INFO sends - i.e. 0 to 2 ticks after send #10,
            // the one this re-arms - having previously survived 58 sends and a
            // clean close. That is a regression I introduced, so it defaults off.
            //
            // It is NOT yet proven to be the cause: 10 ticks is also ~6 seconds,
 // and this server has already once mistaken a crash for a 6-second
            // timeout. The two are confounded by coincidence, not by evidence.
            //
            // So this flag is the discriminator, and the DEFAULT is the arm of the
            // experiment most likely to be playable:
            //   default (off)  -> survives past ~15 ticks  => the re-send was the
            //                     killer, the tick-1 appearance is fine, play on.
            //                  -> still dies at ~10 ticks  => the re-send is
            //                     innocent and the appearance PAYLOAD is wrong;
            //                     fall back to -Dopennxt.experiment.appearance=false.
            //   -Dopennxt.experiment.appearanceResend=true -> reproduce the death.
            if (System.getProperty("opennxt.experiment.appearanceResend") == "true" &&
                (playerInfoTicks == 10 || playerInfoTicks == 30)
            ) {
                viewport.cachedAppearanceHashes[entity.index] = null
            }

            val infoBuf = PlayerInfoEncoder.createBufferFor(this)

            // The discriminator for "is appearance actually going out". A previous
            // session inferred from "58 sends, all 3 bytes" that the block buffer
            // was always empty. It was not - that sample simply began after tick 1,
            // and the real defect was the mask bit. Logging the size and the mask
            // byte makes that inference impossible to repeat.
            //
            // Log EVERY send that carries an appearance block, plus the first few
            // regardless. The previous version stopped at tick 4, which is exactly
            // why the tick-10 re-send went out unlogged and the next run could not
            // say whether it had fired.
            val n = infoBuf.readableBytes()
            if (playerInfoTicks <= 4 || n > 8) {
                val mask =
                    if (n > 5) String.format("0x%02x", infoBuf.getByte(5).toInt() and 0xff) else "n/a"
                logger.info {
                    "PLAYER_INFO tick $playerInfoTicks: $n byte(s), mask byte=$mask " +
                        "(tick 1 should be 60-80 with mask 0x04; 3 bytes is movement only, no appearance)"
                }
            }

            // UNCAPPED, and silent when there is nothing to report: one line per
            // PLAYER_INFO whose extended-info section carried at least one block,
            // naming the block types, the entity index each rode on and the
            // section's byte length. See ExtendedInfoTrace for why the capped
            // instrumentation above cannot answer "did block X reach the wire".
            PlayerInfoEncoder.lastReport?.let { report ->
                if (!report.isEmpty()) {
                    logger.info {
                        "PLAYER_INFO ext-info -> $name: $report [packet ${n}B; cumulative " +
                            "${com.opennxt.model.entity.updating.ExtendedInfoTrace.counts()}]"
                    }
                }
            }

            client.write(UnidentifiedPacket(OpcodeWithBuffer(opcode, infoBuf)))
        }

        // NPC_INFO must go out AFTER PLAYER_INFO, and the order is not cosmetic.
        // The client resolves an add record's dx/dy against the local player's
        // QUEUED END position. Send
        // NPC_INFO first and the client still holds the previous tick's tile, so
        // every npc lands one tile out.
        //
        // writeTo is self-contained: it does its own flag check, opcode lookup
        // with a warn-once degrade, and encodes inside a try/catch, so a fault in
        // npc encoding cannot take the player's own tick down with it.
        NpcInfoEncoder.writeTo(this)

        // World content - ground items and changed locs - AFTER the two entity
        // encoders and BEFORE resetForNextTransmit().
        //
        // After, because both of these run through viewport.rebuildIfNeeded()
        // above: a rebuild on this tick has already bumped
        // Viewport.sceneEpoch, so the two syncs below see the epoch change and
        // re-send everything the client just threw away, in the same tick and
        // after the REBUILD_NORMAL that caused it. Ordering them before the
        // rebuild check would send content into a scene that is about to be
        // destroyed.
        //
        // They do NOT depend on PLAYER_INFO or NPC_INFO and nothing depends on
        // them: the zone family addresses tiles through the scene origin
        // REBUILD_NORMAL established, not through any entity's position, which
        // is why they can sit here without disturbing the PLAYER_INFO ->
        // NPC_INFO order that the npc add-coord resolution needs
        //
        // Both are self-contained - own flag check, own opcode lookup with a
        // warn-once degrade, own try/catch - so a fault in either cannot take
        // this player's tick down.
        GroundItemTransmitter.sync(this)
        LocChanges.syncFor(this)

        // Make the walls in this player's scene solid.
        //
        // map_blocked is TERRAIN ONLY (map_meta: `object_clipping: NOT included`),
        // so without this every wall, fence and gate in the world is walk-through -
        // which is the "walking along boundaries isn't 100%" symptom, and it is not
        // a pathfinding bug, it is missing data. LocClipping derives the wall edges
        // from map_loc on demand, one map square at a time, bounded and idempotent.
        //
        // Here rather than at login because a rebuild is what changes which squares
        // matter, and self-contained - own kill switch, own try/catch - for the same
        // reason the two syncs above are: a fault in collision loading must not take
        // a player's tick down with it.
        if (LocClipping.enabled) {
            try {
                val loc = entity.location
                // At most ONE new square per tick. A cold square is a SQLite query
                // over map_loc and costs about 3.7 ms; nine of them at once cost 29 ms
                // and pushed a full world tick from under 60 ms to 66.7 ms, which
                // the tile the player is standing on is solid immediately and the ring
                // fills in over the next eight ticks.
                val applied = LocClipping.applySceneAt(loc.x, loc.y, loc.plane, maxSquares = Int.MAX_VALUE)
                // Also load plane+1 for bridge tiles. BridgeFlags.effectivePlane
                // remaps plane 0 to plane 1 where settings bit 0x2 is set, so the
                // wall edges on plane 1 (bridge railings/parapets) must be loaded
                // for the remap to find them.
                val bridgeApplied = if (loc.plane < 3) {
                    LocClipping.applySceneAt(loc.x, loc.y, loc.plane + 1, maxSquares = Int.MAX_VALUE)
                } else 0
                val totalApplied = applied + bridgeApplied
                if (totalApplied > 0) {
                    logger.info {
                        "loc clipping: applied $applied+$bridgeApplied map square(s) around (${loc.x},${loc.y}," +
                            "plane ${loc.plane}) for $name; ${LocClipping.loadedSquares()} loaded, " +
                            "${com.opennxt.model.map.CollisionMap.walledTiles()} walled tile(s)"
                    }
                }
            } catch (t: Throwable) {
                if (!locClippingWarned) {
                    locClippingWarned = true
                    logger.error(t) {
                        "loc clipping failed for $name; run with " +
                            "-Dopennxt.experiment.locClipping=false to take it out of the picture. (Warned once.)"
                    }
                }
            }
        }

        viewport.resetForNextTransmit()

        client.write(ServerTickEnd)
    }

    /** PLAYER_INFO sends made for THIS player. Drives the appearance re-arm and the size log. */
    /**
     * Ticks remaining until the deferred UPDATE_STAT burst fires, or 0/negative
     * for "not armed". Armed at REBUILD_NORMAL in [added] and counted down in
     * burst cannot be sent in the same frame as REBUILD_NORMAL.
     */
    /**
     * Ticks until the deferred `IF_SETHIDE(1477:101, false)` re-assert, or 0 for
     * "not armed". Armed in [added] only under
     * `-Dopennxt.experiment.unhideBackpack.after=true`; counted down in [tick].
     */
    private var backpackUnhideCountdown = 0

    private var rerun8110Countdown = 0

    private var probe67Countdown = 0

    private var relayoutCountdown = 0

    private var relayoutAgainCountdown = 0

    private var replay949Countdown = 0

    private var replay949Cursor = 0

    private var rebuildInvCountdown = 0

    private var fullPackCountdown = 0

    private var varp3680LateCountdown = 0

    private var invResendCountdown = 0

    private var showPanelsCountdown = 0

    private var showPanelsList: List<Int> = emptyList()

    private var ribbonCountdown = 0

    private var armFramesCountdown = 0

    private var close8323Countdown = 0

    private var close8323Panel = -1

    private var rebuildCountdown = 0

    private var rebuildPanels: List<Int> = emptyList()

    private var probeCompCountdown = 0

    private var probeCompId = -1

    private var redim1003Countdown = 0

    private var ribbonBuildCountdown = 0

    private var ribbonPositionCountdown = 0

    private var backpackProbeCountdown = 0

    private var backpackProbeMode = ""

    private var barRelayoutCountdown = 0

    private var runEnergyCountdown = 0

    private var statBurstCountdown = 0

    private var playerInfoTicks = 0

    companion object {
        /** One-shot latch for the PLAYER_INFO warning in [tick]. */
        @Volatile
        private var playerInfoUnmappedWarned = false

        /**
         * Ticks after RUNCLIENTSCRIPT(9943) to defer the backpack unhide re-assert.
         */
        /**
         * -Dopennxt.experiment.hudMount.relayout.delayTicks, default **20**; redim1003 fires 2
         * ticks later. (This KDoc said "default 10" until while the value was 20 -
         * a stale doc beside its own constant, corrected rather than left standing.)
         */
        val RELAYOUT_DELAY_TICKS: Int = System.getProperty("opennxt.experiment.hudMount.relayout.delayTicks")?.toIntOrNull() ?: 20

        /**
         * -Dopennxt.experiment.hudMount.relayout.mode, default **15** = apply + re-seed the cache
         * Default layout (UI-C1); 8 = the live store. (This KDoc said "default stays 8" until
         * while the value was 15. Same correction as above; the sentence at the send
         * site, which also said "default stays 8", is corrected there.)
         */
        val RELAYOUT_MODE: Int = System.getProperty("opennxt.experiment.hudMount.relayout.mode")?.toIntOrNull() ?: 15

        /**
         * THE DEFERRED HUD-REPAIR WAVE. **Default OFF since**, and this is the flag
         * that puts it back: `-Dopennxt.experiment.ui.hudRepair=true`.
         */
        val HUD_REPAIR: Boolean = System.getProperty("opennxt.experiment.ui.hudRepair") == "true"

        /**
         * [ticks] when the deferred repair wave is on, 0 when it is off.
         *
         * 0 is the disable value rather than a sentinel because every consumer guards on
         * `if (xCountdown > 0)` before decrementing, so a zero-armed countdown is inert by the
         * existing contract and nothing downstream needed changing.
         */
        fun hudRepair(ticks: Int): Int = if (HUD_REPAIR) ticks else 0

        /** -Dopennxt.experiment.ui.runEnergy.delayTicks, default 11 (absolute, from login) - proven safe in 4 runs. */
        val RUN_ENERGY_DELAY_TICKS: Int = System.getProperty("opennxt.experiment.ui.runEnergy.delayTicks")?.toIntOrNull() ?: 11

        /** -Dopennxt.experiment.hudMount.relayout.again, default 0 (off): re-send 8884[mode] this many ticks after the first send. */
        val RELAYOUT_AGAIN_TICKS: Int = System.getProperty("opennxt.experiment.hudMount.relayout.again")?.toIntOrNull() ?: 10

        /** -Dopennxt.experiment.hudMount.probe67.delayTicks, default 8 (well after 9943, the 3680 transmit and every deferred hook). */
        val PROBE67_DELAY_TICKS: Int = System.getProperty("opennxt.experiment.hudMount.probe67.delayTicks")?.toIntOrNull() ?: 8

        /** -Dopennxt.experiment.hudMount.rerun8110.delayTicks, default 3 (after 9943 late and the 3680 transmit have landed). */
        val RERUN_8110_DELAY_TICKS: Int = System.getProperty("opennxt.experiment.hudMount.rerun8110.delayTicks")?.toIntOrNull() ?: 3
                val BACKPACK_UNHIDE_DELAY_TICKS: Int =
            System.getProperty("opennxt.experiment.unhideBackpack.after.delayTicks")?.toIntOrNull() ?: 2

        val STAT_SEND_DELAY_TICKS: Int =
            System.getProperty("opennxt.experiment.sendStats.delayTicks")?.toIntOrNull() ?: 2
    }
}

/**
 * What [WorldPlayer.equipItem] did, as a value rather than a Boolean.
 *
 * Every refusal carries the reason, for the reason
 * [com.opennxt.model.combat.EngageResult] gives: a caller must not be able to
 * mistake "the experiment is off" for "that item cannot be worn", and a debug
 * command whose failure mode is a silent no-op is a debug command that costs
 * an hour.
 */
sealed class EquipResult {
    /** `-Dopennxt.experiment.equip` is not `true`. Not a statement about the item. */
    object Disabled : EquipResult()

    /** No `items` row for that id. There is no default item. */
    data class NoSuchItem(val itemId: Int) : EquipResult()

    /** The item exists and the cache gives it NO `equipSlotId`. Absent is not slot 0. */
    data class NotEquipable(val itemId: Int, val name: String?) : EquipResult()

    /** The cache's slot is outside the container. Unreachable against this cache; see [WorldPlayer.equipItem]. */
    data class SlotOutOfRange(val itemId: Int, val name: String?, val slot: Int) : EquipResult()

    /** Worn. [slot] is the CACHE's slot for this item, never one this server picked. */
    data class Equipped(val itemId: Int, val name: String?, val slot: Int, val replaced: Int?) : EquipResult()
}

/**
 * The inbound equip path: `MESSAGE_PUBLIC` with a command prefix.
 */
object EquipChatCommand : com.opennxt.net.game.pipeline.GamePacketHandler<WorldPlayer, com.opennxt.net.game.clientprot.MessagePublic> {

    private val logger = KotlinLogging.logger { }

    /**
     * The command prefix. `-Dopennxt.equip.command` overrides it; see the class
     * doc for why an operator might have to.
     *
     * A getter, not a recorded val, so a check can drive both spellings in one
     * JVM.
     */
    val prefix: String
        get() = System.getProperty("opennxt.equip.command")?.trim()?.takeIf { it.isNotEmpty() } ?: "::equip"

    /** True when [text] is addressed to this command rather than to the chat relay. */
    fun matches(text: String): Boolean {
        val t = text.trim()
        return t.equals(prefix, ignoreCase = true) || t.startsWith("$prefix ", ignoreCase = true)
    }

    /**
     * Runs the command and returns what happened, as a string, for the log and
     * for the check. Returns null when [text] is not this command at all.
     */
    fun run(player: WorldPlayer, text: String): String? {
        if (!matches(text)) return null
        // Case-INSENSITIVE strip: [matches] accepted the line ignoring case, so
        // the argument is everything past the prefix's LENGTH. Kotlin's
        // removePrefix is case-sensitive and would leave `::EQUIP 1277` intact.
        val argument = text.trim().substring(prefix.length).trim()
        if (argument.isEmpty()) {
            val worn = player.worn
            return "worn (inv ${PlayerInventory.wornInv}, ${worn.size} slots): " +
                (0 until worn.size).mapNotNull { slot -> worn[slot]?.let { "$slot=${it.id}" } }
                    .ifEmpty { listOf("empty") }.joinToString(" ")
        }
        if (argument.equals("clear", ignoreCase = true)) {
            return "unequipped ${player.unequipAll()} item(s)"
        }
        val id = argument.toIntOrNull() ?: return "not a number: '$argument' (usage: $prefix <itemId>)"
        return when (val result = player.equipItem(id)) {
            is EquipResult.Disabled ->
                "refused: -Dopennxt.experiment.equip is not true, so nothing was equipped and no packet was sent"
            is EquipResult.NoSuchItem -> "refused: no items row for id ${result.itemId} - there is no default item"
            is EquipResult.NotEquipable ->
                "refused: item ${result.itemId} (${result.name ?: "unnamed"}) has no equipSlotId in the cache - " +
                    "absent is not slot 0"
            is EquipResult.SlotOutOfRange ->
                "refused: item ${result.itemId} declares equipSlotId ${result.slot}, outside the " +
                    "${PlayerInventory.WORN_SIZE}-slot container"
            is EquipResult.Equipped ->
                "equipped ${result.itemId} (${result.name ?: "unnamed"}) in cache slot ${result.slot}" +
                    (if (result.replaced != null) ", replacing ${result.replaced}" else "")
        }
    }

    override fun handle(context: WorldPlayer, packet: com.opennxt.net.game.clientprot.MessagePublic) {
        val outcome = run(context, packet.text)
        if (outcome == null) {
            // Not this command: the line goes to the handler that owned it
            // before this object existed, unchanged.
            com.opennxt.net.game.handlers.MessagePublicHandler.handle(context, packet)
            return
        }
        logger.info { "EQUIP COMMAND from ${context.name}: '${packet.text.trim()}' -> $outcome" }
    }
}
