package com.opennxt.model.items

import com.opennxt.resources.sqlite.ItemDefinition
import com.opennxt.resources.sqlite.RsDatabase

/**
 * The worn-equipment slots, taken from what `items.equipSlotId` actually holds
 * in `data/rs3.sqlite` rather than from a remembered number.
 *
 * The distribution over all 60617 item rows, counted before this file was
 * written (`SELECT equipSlotId, COUNT(*) FROM items GROUP BY equipSlotId`):
 *
 * ```
 *   0 -> 2815    1 ->  929    2 ->  543    3 -> 5057    4 -> 2226
 *   5 -> 2299    7 -> 2131    9 -> 1191   10 -> 1313   12 ->  243
 *  13 ->  401   14 ->  182   17 ->  291   18 ->  120   NULL -> 40876
 * ```
 *
 * So: 14 distinct slot ids, the highest is 18, and ids **6, 8, 11, 15 and 16
 * carry no items at all** in this cache. 19741 rows are equippable and 40876 are
 * not. "11 slots" and "13 slots" are both wrong for this data, and a 14-element
 * array indexed by `equipSlotId` would be wrong in a worse way, because the ids
 * are not contiguous - id 18 is a real slot and there is no slot 15 or 16. An
 * [Equipment] is therefore [SLOT_ID_COUNT] = 19 wide and indexed by the raw id,
 * with the five unoccupied ids simply never filled.
 *
 * Names are inferred from the item names on each id, not from a lookup table:
 * every constant below records the sample it was named from. Ids 6, 8 and 11
 * are conventionally the hidden model slots (arms/hair/jaw) in RS item configs,
 * but nothing in this database says so - no row uses them - so they are left out
 * rather than named on a guess.
 */
enum class EquipmentSlot(val id: Int, val label: String) {
    /** 0: "Khazard helmet", "Wizard hat", "Blue partyhat". */
    HEAD(0, "Head"),

    /** 1: "Cape", "Cape of legends". */
    CAPE(1, "Cape"),

    /** 2: "Ghostspeak amulet", "Armadyl pendant", "'Perfect' necklace". */
    NECK(2, "Neck"),

    /** 3: "Excalibur", "Abyssal whip", "Bronze dart". The main hand. */
    MAIN_HAND(3, "Main hand"),

    /** 4: "Khazard armour", "Priest gown", "Druid's robe". */
    TORSO(4, "Torso"),

    /** 5: "Wooden shield", "Dragon sq shield". The off hand. */
    OFF_HAND(5, "Off hand"),

    /** 7: "Plague trousers", "Shade robe bottom", "Blue robe bottoms". */
    LEGS(7, "Legs"),

    /** 9: "Cooking gauntlets", "Leather gloves", "Green dragonhide vambraces". */
    HANDS(9, "Hands"),

    /** 10: "Boots of lightness", "Leather boots". */
    FEET(10, "Feet"),

    /** 12: "Gold ring", "Ring of recoil", "Dragonstone ring". */
    RING(12, "Ring"),

    /** 13: "Bronze bolts", "Ice arrows", "Barbed bolts". */
    AMMO(13, "Ammunition"),

    /** 14: "Poison purge aura", "Jack of trades aura". Every name on this id ends in "aura". */
    AURA(14, "Aura"),

    /** 17: "Saradomin's Book of Wisdom", "Holy wrench", "Bonecrusher", "Attacker lvl 1". */
    POCKET(17, "Pocket"),

    /** 18: "Armadyl Wings", "Butterfly Wings", "Lava wings". Every name on this id contains "wings". */
    WINGS(18, "Wings");

    companion object {
        /**
         * Width of a slot-id-indexed array: highest observed id (18) + 1.
         * Five of these 19 positions (6, 8, 11, 15, 16) are never occupied.
         */
        const val SLOT_ID_COUNT = 19

        private val byId = arrayOfNulls<EquipmentSlot>(SLOT_ID_COUNT).also { arr ->
            values().forEach { arr[it.id] = it }
        }

        /** The slot with this id, or null - including for the five ids no item uses. */
        fun forId(id: Int?): EquipmentSlot? =
            if (id == null || id < 0 || id >= SLOT_ID_COUNT) null else byId[id]

        /** Slot ids in 0 until [SLOT_ID_COUNT] that no [EquipmentSlot] claims. */
        val unoccupiedIds: List<Int> = (0 until SLOT_ID_COUNT).filter { byId[it] == null }

        /** Which slot this definition goes in, or null if it is not equippable. */
        fun of(def: ItemDefinition?): EquipmentSlot? = forId(def?.equipSlotId)

        /**
         * Whether the definition can be worn.
         *
         * An item is equippable exactly when it carries an `equipSlotId` that
         * names a slot. Note that `equipId` is a *different* field and is not
         * consulted: only 6573 rows have one, it takes the values 3, 5, 6, 8 and
         * 11, and cross-tabulating it against `equipSlotId` shows it is a
         * model-hiding class, not a slot - e.g. every `equipId = 8` row is a
         * `equipSlotId = 0` helmet and every `equipId = 6` row is an
         * `equipSlotId = 4` body. Treating it as a slot would put 2017 helmets
         * in slot 8, which no item uses.
         */
        fun canEquip(def: ItemDefinition?): Boolean = of(def) != null

        /** How many item rows name each slot, straight from the database. Empty if there is no database. */
        fun distributionFromDatabase(): Map<Int, Int> =
            RsDatabase.queryAll(
                "SELECT equipSlotId AS s, COUNT(*) AS n FROM items WHERE equipSlotId IS NOT NULL GROUP BY equipSlotId"
            ) { rs -> rs.getInt("s") to rs.getInt("n") }.toMap()
    }
}

/**
 * A worn-equipment set: one [ItemContainer] indexed by raw `equipSlotId`.
 *
 * Equipment does not use [ItemContainer.add], because "put it wherever there is
 * room" is meaningless here - a helmet goes on the head or nowhere. [equip]
 * places by slot and hands back whatever it displaced, which is what the server
 * needs to put back in the inventory.
 */
class Equipment {
    private val container = ItemContainer(EquipmentSlot.SLOT_ID_COUNT)

    operator fun get(slot: EquipmentSlot): Item? = container[slot.id]

    operator fun set(slot: EquipmentSlot, item: Item?) {
        container[slot.id] = item
    }

    fun isEmpty(slot: EquipmentSlot): Boolean = get(slot) == null

    /**
     * Wears [item], returning what came off - null if the slot was empty.
     *
     * Throws if the item is not equippable: the caller is expected to have asked
     * [EquipmentSlot.canEquip] first, and silently doing nothing would hide the
     * bug where an item's definition is missing.
     */
    fun equip(item: Item): Item? {
        val def = item.definition
            ?: throw IllegalArgumentException("item ${item.id} has no definition, cannot be equipped")
        val slot = EquipmentSlot.of(def)
            ?: throw IllegalArgumentException("${def.name ?: def.id} is not equippable (equipSlotId=${def.equipSlotId})")

        val displaced = container[slot.id]
        // Ammunition is the case where equipping onto an occupied slot merges
        // rather than displaces - but only when it is the same id and it stacks.
        if (displaced != null && displaced.id == item.id && ItemStacking.isStackable(item.id)) {
            container[slot.id] = displaced.plus(item.amount)
            return null
        }
        container[slot.id] = item
        return displaced
    }

    fun unequip(slot: EquipmentSlot): Item? = container.removeSlot(slot.id)

    fun worn(): Map<EquipmentSlot, Item> =
        EquipmentSlot.values().mapNotNull { s -> container[s.id]?.let { s to it } }.toMap()

    fun usedSlots(): Int = container.usedSlots()

    fun clear() = container.clear()

    override fun toString() = "Equipment(${worn()})"
}
