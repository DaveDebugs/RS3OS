package com.opennxt.model.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.api.stat.Stat
import com.opennxt.model.bank.Bank
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import mu.KotlinLogging

/**
 * The serializable state of one player: what survives a server restart.
 */
data class PlayerSave(
    val username: String,
    /** Total xp per stat, all 28 present, each a finite non-negative double. */
    val xp: Map<Stat, Double>,
    val x: Int,
    val y: Int,
    val plane: Int,
    /** Backpack contents, slot-addressed, slots 0..27 ([ItemContainer.INVENTORY_SIZE]). */
    val backpack: List<SavedItem>,
    /** Bank contents, slot-addressed, slots 0..509 ([Bank.CAPACITY]). */
    val bank: List<SavedItem>,
    /**
     * Varp overrides (varp id -> value).
     */
    val varps: Map<Int, Int>,
    /**
     * Worn equipment, slot-addressed by the cache's own `items.equipSlotId` (0..18).
     */
    val worn: List<SavedItem> = emptyList(),
    /**
     * Account rank: 0 an ordinary player, 2 a GAME MOD ([com.opennxt.model.permissions.Rights]).
     */
    val rights: Int = 0,
    /**
     * THE PLAYER'S INTERFACE SETTINGS - the client's own varc state, id -> value.
     */
    val varcs: Map<Int, Any> = emptyMap(),
    /**
     * Cosmetic overrides (equipment slot -> item id). What the APPEARANCE shows in a
     * slot instead of the worn item - the reference client's wardrobe: a "Glacial siren robe top" (51127) over
     * whatever body armour is really worn, "Ice wings" (51140) in slot 18 with nothing worn there.
     * The worn container is untouched; only [com.opennxt.model.entity.player.appearance.PlayerModel]
     */
    val cosmetics: Map<Int, Int> = emptyMap(),
    /**
     * THE TOOL BELT'S PER-PLAYER CONTENTS. Item ids, sorted, no duplicates.
     */
    val toolbelt: List<Int> = emptyList(),
    /**
     * THE UNFINISHED ANVIL PROJECT. Null when the player is not carrying one.
     */
    val smithing: SavedProject? = null,
    /**
     * THE RUN RESERVE AND THE RUN TOGGLE. Null when the player is at the defaults.
     */
    val run: SavedRunEnergy? = null,
) {

    /** One occupied container slot. Amount >= 1 - an empty slot is absent, not zero. */
    data class SavedItem(val slot: Int, val id: Int, val amount: Int) {
        init {
            require(slot >= 0) { "slot must not be negative: $slot" }
            require(id >= 0) { "item id must not be negative: $id" }
            require(amount >= 1) { "amount must be at least 1: $amount (an empty slot is omitted, not zero)" }
        }
    }

    /**
     * One in-progress anvil project: which recipe, and the four numbers the reference client keeps in its own
     * `UPDATE_INV_PARTIAL` param table (index 1 progress, index 2 xp remaining, index 3 heat).
     *
     * [productId] is the key of `Smithing.smithRecipes`. The remaining four are the mutable
     * fields of `Smithing.Project`, in the same order.
     *
     * Validated here, in its own `init`, the way [SavedItem] is: every number is non-negative.
     * `xpPaidTenths` is what has ALREADY been paid (the wire sends what remains; the module
     * stores the complement), so 0 is the legitimate value for a freshly created project and
     * this class must not require >= 1 the way [SavedItem.amount] does.
     */
    data class SavedProject(
        val productId: Int,
        val progress: Int,
        val xpPaidTenths: Int,
        val heat: Int,
        val stage: Int,
    ) {
        init {
            require(productId >= 0) { "smithing product id must not be negative: $productId" }
            require(progress >= 0) { "smithing progress must not be negative: $progress" }
            require(xpPaidTenths >= 0) { "smithing xpPaidTenths must not be negative: $xpPaidTenths" }
            require(heat >= 0) { "smithing heat must not be negative: $heat" }
            require(stage >= 0) { "smithing heat stage must not be negative: $stage" }
        }
    }

    /**
     * The stored run reserve: tenths of a displayed point, and the toggle.
     *
     * Validated in its own `init` the way [SavedItem] and [SavedProject] are. The upper bound is
     * checked here rather than clamped, because a blob claiming 4,000 tenths is a blob this build
     * does not understand, and [fromJson]'s contract is to throw rather than half-parse.
     * `RunEnergy.restore` clamps as well - belt and braces, and the two disagree about WHOSE fault
     * a bad value is: this one says the file is wrong, that one says the caller is.
     */
    data class SavedRunEnergy(val tenths: Int, val toggled: Boolean) {
        init {
            require(tenths >= 0) { "run energy tenths must not be negative: $tenths" }
            require(tenths <= MAX_RUN_TENTHS) { "run energy tenths above the maximum: $tenths > $MAX_RUN_TENTHS" }
        }
    }

    init {
        require(username.isNotBlank()) { "username must not be blank" }
        for (stat in Stat.values()) {
            if (stat !in xp) throw IllegalArgumentException("save for '$username' is missing xp for $stat")
        }
        require(xp.size == Stat.values().size) { "xp map has ${xp.size} entries, expected ${Stat.values().size}" }
        xp.forEach { (stat, v) ->
            require(v.isFinite() && v >= 0.0) { "xp for $stat is not a finite non-negative double: $v" }
        }
        requireSlots(backpack, ItemContainer.INVENTORY_SIZE, "backpack")
        requireSlots(bank, Bank.CAPACITY, "bank")
        requireSlots(worn, WORN_CAPACITY, "worn")
        require(rights >= 0) { "rights must not be negative: $rights" }
 // The tool belt. Validated here rather than at the call site so that no path
        // into this class - construction, copy(), fromJson - can build a save with a duplicated or
        // negative belt id. See the field.
        toolbelt.forEach { id -> require(id >= 0) { "tool belt item id must not be negative: $id" } }
        require(toolbelt.size == toolbelt.toSet().size) {
            "tool belt holds a duplicate item id: $toolbelt"
        }
        varcs.forEach { (id, v) ->
            require(id >= 0) { "varc id must not be negative: $id" }
            require(v is Int || v is Long || v is String) {
                "varc $id holds ${v.javaClass.simpleName}; only Int, Long and String are storable " +
                    "(the three BaseVarType kinds a varc can be)"
            }
        }
    }

    private fun requireSlots(items: List<SavedItem>, capacity: Int, what: String) {
        val seen = HashSet<Int>()
        items.forEach {
            require(it.slot < capacity) { "$what slot ${it.slot} outside 0..${capacity - 1}" }
            require(seen.add(it.slot)) { "$what slot ${it.slot} appears twice" }
        }
    }

    // ---- container bridging ---------------------------------------------

    /**
     * Rebuilds an [ItemContainer] of [capacity] from a saved list via
     * [ItemContainer.set] - the direct-placement path its doc reserves for
     * deserialisation, so stacking rules cannot rearrange a restored layout.
     */
    fun restoreContainer(items: List<SavedItem>, capacity: Int, stackAll: Boolean = false): ItemContainer {
        val container = ItemContainer(capacity, stackAll)
        items.forEach { container[it.slot] = Item(it.id, it.amount) }
        return container
    }

    fun restoreBackpack(): ItemContainer = restoreContainer(backpack, ItemContainer.INVENTORY_SIZE)

    /** The worn container, 19 wide - see [com.opennxt.model.entity.player.PlayerInventory.WORN_SIZE]. */
    fun restoreWorn(): ItemContainer = restoreContainer(worn, WORN_CAPACITY)

    /**
     * The bank CONTENTS as the container [Bank] is documented to be built on
     * (`ItemContainer(510, stackAll = true)`).
     *
     * This is the DATA-level view and it stays: it is what proves a stored bank
     * list rebuilds the same layout without a live [Bank] anywhere. The live
     * wiring that used to be missing now exists - [Bank.contents] reads a bank
     * out and [Bank.restore] puts one back - so use [bankItems] /
     * [Companion.bankContents] to move contents between a save and a real bank.
     */
    fun restoreBankContents(): ItemContainer = restoreContainer(bank, Bank.CAPACITY, stackAll = true)

    /**
     * This save's bank list in the shape [Bank.restore] takes. The two triples
     * carry the same three numbers; the conversion lives here rather than in
     * [Bank] so the bank model keeps no dependency on the account layer.
     */
    fun bankItems(): List<Bank.BankedItem> = bank.map { Bank.BankedItem(it.slot, it.id, it.amount) }

    // ---- JSON ------------------------------------------------------------

    /** Canonical serialization: fixed field order, byte-stable across round-trips. */
    fun toJson(): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("username", username)
        val xpObj = JsonObject()
        // Stat declaration order IS the canonical order (ids 0..27).
        Stat.values().forEach { stat -> xpObj.addProperty(stat.name, xp.getValue(stat)) }
        root.add("xp", xpObj)
        val pos = JsonObject()
        pos.addProperty("x", x)
        pos.addProperty("y", y)
        pos.addProperty("plane", plane)
        root.add("position", pos)
        root.add("backpack", itemsToJson(backpack))
        root.add("worn", itemsToJson(worn))
        root.addProperty("rights", rights)
        root.add("bank", itemsToJson(bank))
        val varpObj = JsonObject()
        varps.keys.sorted().forEach { id -> varpObj.addProperty(id.toString(), varps.getValue(id)) }
        root.add("varps", varpObj)
        // Sorted by id like every other map here, so serialize -> parse -> serialize stays
        val varcObj = JsonObject()
        varcs.keys.sorted().forEach { id ->
            when (val v = varcs.getValue(id)) {
                is Int -> varcObj.addProperty(id.toString(), v)
                is Long -> varcObj.addProperty(id.toString(), v)
                is String -> varcObj.addProperty(id.toString(), v)
                else -> error("unstorable varc $id: ${v.javaClass.simpleName}")
            }
        }
        root.add("varcs", varcObj)
        if (cosmetics.isNotEmpty()) {
            val cosObj = JsonObject()
            cosmetics.keys.sorted().forEach { slot -> cosObj.addProperty(slot.toString(), cosmetics.getValue(slot)) }
            root.add("cosmetics", cosObj)
        }
 // The tool belt. Sorted and only when non-empty, for the reason `cosmetics`
        // above is: a save whose owner never touched the belt must serialize byte-identically to
        if (toolbelt.isNotEmpty()) {
            val beltArr = JsonArray()
            toolbelt.sorted().forEach { beltArr.add(it) }
            root.add("toolbelt", beltArr)
        }
 // The unfinished anvil project. LAST and only when present, for the reason
        // `cosmetics` above is: a save with no project must serialize byte-identically to one
        smithing?.let { p ->
            val obj = JsonObject()
            obj.addProperty("product", p.productId)
            obj.addProperty("progress", p.progress)
            obj.addProperty("xpPaidTenths", p.xpPaidTenths)
            obj.addProperty("heat", p.heat)
            obj.addProperty("stage", p.stage)
            root.add("smithing", obj)
        }
 // The run reserve and toggle. LAST and only when present, for the reason
        // `cosmetics` above is: a save from a player at full energy with the default toggle must
        // serialize byte-identically to one written before this field existed, which is what
        // default, so the "is this worth writing" decision lives at ONE site, not two.
        run?.let { r ->
            val obj = JsonObject()
            obj.addProperty("tenths", r.tenths)
            obj.addProperty("on", r.toggled)
            root.add("run", obj)
        }
        return root.toString()
    }

    private fun itemsToJson(items: List<SavedItem>): JsonArray {
        val arr = JsonArray()
        items.sortedBy { it.slot }.forEach {
            val o = JsonObject()
            o.addProperty("slot", it.slot)
            o.addProperty("id", it.id)
            o.addProperty("amount", it.amount)
            arr.add(o)
        }
        return arr
    }

    companion object {
        private val logger = KotlinLogging.logger { }
        /** Save-blob format version, gated in [fromJson] the way the db schema is gated in [AccountStore]. */
        const val FORMAT = 1

        const val WORN_CAPACITY = 19

        const val MAX_RUN_TENTHS = 1000

        /**
         * WHERE A BRAND-NEW CHARACTER STARTS. **Burthorpe** (was Lumbridge 3222,3222).
         */
 // REVERTED TO LUMBRIDGE, and the Burthorpe work is NOT lost - see below.
        //
        // The move to (2898, 3544) is correct and derived (the KDoc above stands). It is reverted
        // because the LOGIN TILE turned out to be load-bearing in at least three other places that
        // each hardcode 3222: `NpcInfoEncoder.DEMO_X/DEMO_Y` (fixed - they now derive from these
        // outright and leaves 451 assertions unrun. Shipping a NO-VERDICT check to move a spawn is
        // a bad trade, so the spawn waits for its own pass.
        //
        // the spawn, then set these two to 2898 / 3544. The derivation, the check that verifies it
        // from `map_loc`, and the demo-npc coupling are all already in place and stay.
        const val SPAWN_X = 3222
        const val SPAWN_Y = 3222
        const val SPAWN_PLANE = 0

        /**
         * Constitution's starting xp: 1154.0, total xp for level 10 on the
         * standard curve. This is the exact value
         * [com.opennxt.impl.stat.PlayerStatContainer]'s init block already
         * special-cases (`data.experience = 1154.0` with level 10) for
         * Constitution while every other stat starts at 0.0 / level 1 - a
         * fresh RS account has 100 lifepoints, not 10.
         */
        const val CONSTITUTION_START_XP = 1154.0

        /**
         * The tool belt a brand-new character starts with: one base tool for each
         * skill this server implements, so a new player can act on the world
         * immediately without carrying tools in the backpack.
         *
         * Every id is an item the CACHE itself marks with the "Add to tool belt"
         * action, so nothing here is belted that the game would refuse to belt.
         * The set is derived from the skills that exist, not from a wishlist:
         * add a row when a skill that needs a tool is implemented.
         *
         *   1351  Bronze hatchet   Woodcutting
         *   1265  Bronze pickaxe   Mining
         *    590  Tinderbox        Firemaking
         *   2347  Hammer           Smithing
         *    946  Knife            Fletching
         *  13431  Crayfish cage    Fishing
         *
         * The hatchet and pickaxe are also [com.opennxt.content.impl.Skilling.TOOLBELT_BASE],
         * which the skilling code treats as always held. Listing them here as well
         * is what makes them VISIBLE on the tool belt interface rather than only
         * usable, and the belt is a set, so the overlap costs nothing.
         */
        val STARTING_TOOL_BELT: List<Int> = listOf(1351, 1265, 590, 2347, 946, 13431)

        /** A brand-new player: Lumbridge spawn, level-1 skills except Constitution, empty containers. */
        fun fromNew(username: String): PlayerSave {
            val xp = LinkedHashMap<Stat, Double>()
            Stat.values().forEach { stat ->
                xp[stat] = if (stat == Stat.CONSTITUTION) CONSTITUTION_START_XP else 0.0
            }
            return PlayerSave(
                username = username,
                xp = xp,
                x = SPAWN_X, y = SPAWN_Y, plane = SPAWN_PLANE,
                backpack = emptyList(),
                bank = emptyList(),
                toolbelt = STARTING_TOOL_BELT,
                varps = emptyMap(),
            )
        }

        /** [ItemContainer] contents -> saved list, slots explicit, empty slots omitted. */
        fun containerContents(container: ItemContainer): List<SavedItem> =
            container.toArray().withIndex()
                .filter { it.value != null }
                .map { (slot, item) -> SavedItem(slot, item!!.id, item.amount) }

        /**
         * A LIVE bank's contents -> saved list. The inverse of [bankItems], and
         * the protocol side of the wire described in [Bank]'s "Persistence"
         * section: a leave-save or an autosave reads the player's real bank
         * through [Bank.contents] and stores exactly what it found.
         */
        fun bankContents(bank: Bank): List<SavedItem> =
            bank.contents().map { SavedItem(it.slot, it.id, it.amount) }

        /**
         * Parses a save blob, refusing anything malformed. Every failure is an
         * [IllegalArgumentException] naming what was wrong - never a defaulted
         * field, never null-for-corrupt.
         */
        fun fromJson(json: String): PlayerSave {
            val root = try {
                JsonParser().parse(json).asJsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("save blob is not a JSON object: ${e.message}", e)
            }
            val format = root.get("format")?.takeIf { it.isJsonPrimitive }?.asInt
                ?: throw IllegalArgumentException("save blob has no 'format' field - refusing to guess its layout")
            if (format != FORMAT) {
                throw IllegalArgumentException(
                    "save blob is format $format; this build reads only format $FORMAT. " +
                            "Refusing to half-parse - a newer format may store fields this code would misread."
                )
            }
            val username = root.get("username")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw IllegalArgumentException("save blob has no 'username'")
            val xpObj = root.getAsJsonObject("xp")
                ?: throw IllegalArgumentException("save blob has no 'xp' object")
            val xp = LinkedHashMap<Stat, Double>()
            // A stat this build knows and the SAVE does not is a save written by an
            // older build, which is a normal condition and not a corrupt file - the
            // same judgement `worn` gets four lines below. It starts at 0.
            //
            // This path is not hypothetical: NECROMANCY was added to [Stat] on
 // after a live observation showed the 949 client carrying skill id
            // 28, and every save on disk at that moment had 28 entries. Throwing here
            // would have refused every existing account at login, which is the worst
            // possible shape for a migration - it looks like data loss.
            val missing = ArrayList<String>()
            Stat.values().forEach { stat ->
                val v = xpObj.get(stat.name)
                if (v == null) { missing.add(stat.name); xp[stat] = 0.0 } else xp[stat] = v.asDouble
            }
            if (missing.isNotEmpty()) {
                logger.info {
                    "save for '$username' predates ${missing.joinToString()} - starting " +
                        "${if (missing.size == 1) "it" else "them"} at 0 xp"
                }
            }
            // UNKNOWN keys are still refused. That is the half of the old size check
            // worth keeping: a key this build cannot name is either a typo or a save
            // from a NEWER build, and silently dropping it would lose xp.
            val known = Stat.values().map { it.name }.toSet()
            xpObj.entrySet().forEach { (k, _) ->
                if (k !in known) throw IllegalArgumentException(
                    "xp object carries unknown stat key '$k' - refusing rather than dropping it"
                )
            }
            val pos = root.getAsJsonObject("position")
                ?: throw IllegalArgumentException("save blob has no 'position'")
            val backpack = itemsFromJson(root, "backpack")
 // Absent in every save written before; empty, not an error. See the field.
            val worn = if (root.has("worn")) itemsFromJson(root, "worn") else emptyList()
            val rights = if (root.has("rights")) root.get("rights").asInt else 0
            val bank = itemsFromJson(root, "bank")
            val varpObj = root.getAsJsonObject("varps")
                ?: throw IllegalArgumentException("save blob has no 'varps' object")
            val varps = LinkedHashMap<Int, Int>()
            varpObj.entrySet().forEach { (k, v) ->
                val id = k.toIntOrNull() ?: throw IllegalArgumentException("non-numeric varp id '$k'")
                varps[id] = v.asInt
            }
 // Absent in every save written before; empty, not an error, exactly like
            // `worn`. The JSON type IS the value type: a string element is a STRING varc, a
            // number is INT or LONG, and which of those is decided by magnitude rather than by
            // consulting the cache - so a change to the cache's type table cannot retroactively
            // re-interpret a value that is already stored.
            val varcs = LinkedHashMap<Int, Any>()
            root.getAsJsonObject("varcs")?.entrySet()?.forEach { (k, v) ->
                val id = k.toIntOrNull() ?: throw IllegalArgumentException("non-numeric varc id '$k'")
                val prim = v.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    ?: throw IllegalArgumentException("varc $id is not a primitive: $v")
                varcs[id] = when {
                    prim.isString -> prim.asString
                    prim.isNumber -> prim.asLong.let { l -> if (l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) l.toInt() else l }
                    else -> throw IllegalArgumentException("varc $id is neither a number nor a string: $v")
                }
            }
 // Absent before; empty, not an error (see the field).
            val cosmetics = LinkedHashMap<Int, Int>()
            root.getAsJsonObject("cosmetics")?.entrySet()?.forEach { (k, v) ->
                val slot = k.toIntOrNull() ?: throw IllegalArgumentException("non-numeric cosmetic slot '$k'")
                val id = v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                    ?: throw IllegalArgumentException("cosmetic slot $slot is not an item id: $v")
                require(slot >= 0 && id >= 0) { "cosmetic slot $slot -> item $id: negative" }
                cosmetics[slot] = id
            }
 // Absent before; empty, not an error (see the field). PRESENT means an array
            // of numbers - anything else is REFUSED rather than skipped, because a belt this build
            // cannot read is a belt whose items would be silently destroyed by the next autosave.
            // Duplicates are caught by `init`, not here, so `copy()` is guarded too.
            val toolbelt = ArrayList<Int>()
            root.get("toolbelt")?.let { el ->
                val arr = el.takeIf { it.isJsonArray }?.asJsonArray
                    ?: throw IllegalArgumentException("'toolbelt' is not an array: $el")
                arr.forEach { e ->
                    val prim = e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                        ?: throw IllegalArgumentException("tool belt entry is not an item id: $e")
                    toolbelt += prim.asInt
                }
            }
 // Absent before; null, not an error (see the field). PRESENT means all five
            // numbers must be there and must be numbers - [intField] throws on a missing or
            // non-numeric one and [SavedProject]'s init throws on a negative one, so a malformed
            // project is REFUSED rather than silently defaulted to "no project", which would be
            // the same item loss this field exists to close.
            val smithing = root.get("smithing")?.let { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("'smithing' is not an object: $el")
                SavedProject(
                    productId = intField(o, "product"),
                    progress = intField(o, "progress"),
                    xpPaidTenths = intField(o, "xpPaidTenths"),
                    heat = intField(o, "heat"),
                    stage = intField(o, "stage"),
                )
            }
 // Absent before; null, not an error (see the field). PRESENT means both
            // members must be there and must be the right shape - REFUSED rather than silently
            // defaulted to "full energy", because defaulting is exactly the free-run-on-relog this
            // field exists to close, and a blob that half-parses is worse than one that throws.
            val run = root.get("run")?.let { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("'run' is not an object: $el")
                val on = o.get("on")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                    ?: throw IllegalArgumentException("'run.on' is not a boolean: ${o.get("on")}")
                SavedRunEnergy(tenths = intField(o, "tenths"), toggled = on)
            }
            return PlayerSave(
                username = username,
                xp = xp,
                x = intField(pos, "x"), y = intField(pos, "y"), plane = intField(pos, "plane"),
                backpack = backpack,
                bank = bank,
                varps = varps,
                worn = worn,
                rights = rights,
                varcs = varcs,
                cosmetics = cosmetics,
                toolbelt = toolbelt,
                smithing = smithing,
                run = run,
            )
        }

        private fun itemsFromJson(root: JsonObject, field: String): List<SavedItem> {
            val arr = root.getAsJsonArray(field)
                ?: throw IllegalArgumentException("save blob has no '$field' array")
            return arr.map { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("'$field' entry is not an object: $el")
                SavedItem(intField(o, "slot"), intField(o, "id"), intField(o, "amount"))
            }
        }

        private fun intField(o: JsonObject, name: String): Int {
            val el = o.get(name) ?: throw IllegalArgumentException("missing int field '$name' in $o")
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber)
                throw IllegalArgumentException("field '$name' is not a number: $el")
            return el.asInt
        }
    }
}
