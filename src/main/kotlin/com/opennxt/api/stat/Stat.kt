package com.opennxt.api.stat

import com.opennxt.resources.FilesystemResources
import com.opennxt.resources.config.enums.EnumDefinition
import com.opennxt.resources.defaults.stats.StatDefaults
import com.opennxt.resources.defaults.stats.StatDefinition
import com.opennxt.resources.defaults.stats.StatExperienceTable
import mu.KotlinLogging

enum class Stat(val id: Int) {
    ATTACK(0),
    DEFENCE(1),
    STRENGTH(2),
    CONSTITUTION(3),
    RANGED(4),
    PRAYER(5),
    MAGIC(6),
    COOKING(7),
    WOODCUTTING(8),
    FLETCHING(9),
    FISHING(10),
    FIREMAKING(11),
    CRAFTING(12),
    SMITHING(13),
    MINING(14),
    HERBLORE(15),
    AGILITY(16),
    THIEVING(17),
    SLAYER(18),
    FARMING(19),
    RUNECRAFTING(20),
    HUNTER(21),
    CONSTRUCTION(22),
    SUMMONING(23),
    DUNGEONEERING(24),
    DIVINATION(25),
    INVENTION(26),
    ARCHAEOLOGY(27),

    /**
     *. This build DOES have it, which the old skip comment
     * below assumed it did not.
     */
    NECROMANCY(28),
    ;

    lateinit var def: StatDefinition
    lateinit var table: StatExperienceTable
    lateinit var display: String

    /**
     * True once [reload] has attached a definition and an experience table to
     * this stat. `isInitialized` on a lateinit property is only visible from
     * inside the declaring class, so callers outside (see
     * [com.opennxt.api.stat.levelForXp]) need this to tell "not loaded yet"
     * apart from an UninitializedPropertyAccessException thrown deep in a
     * lookup.
     */
    val loaded: Boolean
        get() = this::def.isInitialized && this::table.isInitialized

    companion object {
        private val logger = KotlinLogging.logger { }
        private val VALUES = values()

        fun reload() {
            val defaults = FilesystemResources.instance.defaults.get<StatDefaults>()

            // Enum 680 supplies the display names, and it lives in cache index
            // 17. The `!!` here used to turn a missing index into a bare
            // NullPointerException at boot, which killed the server before it
            // bound the game port - so the visible symptom was a client stuck
            // in its retry loop, with a stack trace pointing at skills and
            // nothing pointing at the cache.
            //
            // An incomplete cache is a normal condition, not a fatal one: this
            // is a partially-downloaded cache by design, and the server already
            // tolerates a missing rs3.sqlite the same way. Names are cosmetic -
            // every stat still gets its definition and experience table below -
            // so a missing enum degrades the display and nothing else.
            val names = FilesystemResources.instance.get<EnumDefinition>(680)
            if (names == null) {
                logger.warn {
                    "Enum 680 (skill display names) could not be loaded - cache index 17 " +
                        "is probably absent. Stats will work; their names will read 'skill<id>'."
                }
            }

            defaults.stats.forEach { def ->
                // Skip skills this build has no enum constant for, instead of
                // indexing past the end of VALUES.
                //
 // This fired for id 28 (NECROMANCY) until, on the
                // assumption that the extra skill postdated build 949. A live
                // observation of the 949 client disproved that - see NECROMANCY's
                // own doc - so the constant was added and this guard now covers
                // only genuinely-newer caches. It is kept because that case is
                // real: `VALUES[def.id]` on an unknown id threw
                // "Index 28 out of bounds for length 28" during content reload -
                // a message that says nothing about skills or cache versions.
                //
                // Reading a NEWER cache than the server was written for is the
                // normal case when reusing cache data across builds, and an
                // unknown skill is not a reason to refuse to boot: the server
                // simply does not model it. Logged rather than silently dropped,
                // because a missing skill would otherwise show up much later as
                // an unexplained gap in a player's stats.
                if (def.id !in VALUES.indices) {
                    logger.warn {
                        "Cache declares skill id ${def.id}, but this build knows only " +
                                "${VALUES.size} (0..${VALUES.size - 1}). Skipping it."
                    }
                    return@forEach
                }
                val enum = VALUES[def.id]
                enum.def = def
                enum.table = def.table
                enum.display = if (names == null) "skill${def.id}"
                    else ((names.values[def.id] as? String) ?: names.defaultString) ?: "null"
            }
        }
    }
}