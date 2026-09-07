package com.opennxt.impl.stat

import com.opennxt.api.stat.ExperienceSource
import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.StatContainer
import com.opennxt.api.stat.StatData
import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.serverprot.UpdateStat
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import mu.KotlinLogging
import java.util.*
import kotlin.math.min

/**
 * @param initialXp per-stat total xp to seed the container from - the
 *   [com.opennxt.model.account.PlayerSave.xp] map. When provided, every
 *   level is DERIVED from that xp through the verified curve
 *   ([PlayerStatData.calculateLevel] over the cache's own tables); nothing
 *   is hardcoded. A save always carries all 28 stats, so a missing entry is
 *   refused, not defaulted. When null (legacy callers with no save), the
 *   old hardcoded fresh-account state applies: level 1 everywhere except
 *   Constitution's 1154 xp / level 10.
 */
class PlayerStatContainer(val player: BasePlayer, initialXp: Map<Stat, Double>? = null) : StatContainer {
    private val logger = KotlinLogging.logger {}
    private val stats = EnumMap<Stat, PlayerStatData>(Stat::class.java)
    private val dirty = ObjectOpenHashSet<Stat>()

    override fun init() {
        for (stat in Stat.values()) {
            refresh(stat)
        }
    }

    override fun markDirty() {
        dirty.addAll(Stat.values())
    }

    override fun isDirty(): Boolean = dirty.isNotEmpty()

    override fun clean() {
        for (stat in dirty) {
            refresh(stat)
        }

        dirty.clear()
    }

    init {
        Stat.values().forEach { stat ->
            val data = if (initialXp == null) {
                // Legacy hardcoded fresh-account state (no save supplied).
                val d = PlayerStatData(stat, 0.0, 1, 1)
                if (stat == Stat.CONSTITUTION) {
                    d.experience = 1154.0
                    d.actualLevel = 10
                    d.boostedLevel = 10
                }
                d
            } else {
                val xp = initialXp[stat]
                    ?: throw IllegalArgumentException(
                        "initial xp map is missing $stat - a PlayerSave always carries all ${Stat.values().size} stats"
                    )
                val d = PlayerStatData(stat, xp)
                // Level is derived from the stored xp through the verified
                // curve; the save deliberately never stores a level.
                val level = d.calculateLevel()
                d.actualLevel = level
                d.boostedLevel = level
                d
            }

            stats[stat] = data
        }
    }

    override fun get(stat: Stat): StatData = stats.getValue(stat)

    override fun set(stat: Stat, data: StatData) {
        if (stat != data.stat) {
            throw IllegalArgumentException("'stat' and 'data#stat' do not match ($stat vs ${data.stat})")
        }

        if (data !is PlayerStatData) {
            stats[stat] = PlayerStatData(
                stat,
                data.experience,
                data.actualLevel,
                data.boostedLevel
            )
            return
        }

        stats[stat] = data
    }

    override fun addExperience(stat: Stat, amount: Double, source: ExperienceSource): Int {
        require(amount >= 0.0) { "xp award must be non-negative; got $amount for $stat" }
        val data = stats.getValue(stat)

        var actualAmount = amount * source.boostFactor

        val bonusExp = data.bonusExp
        if (bonusExp > 0) {
            val toAdd = min(bonusExp, actualAmount)
            data.bonusExp -= toAdd
            actualAmount += toAdd
        }

 // (audit C-06): the boosted-and-bonus amount was computed and then the RAW
        // amount was granted, so bonus xp drained without paying out. Latent until a source
        // boosts or a pool is filled; fixed at the one mutator.
        val levelsGained = data.addExperience(actualAmount)

        refresh(data)

        if (levelsGained > 0) {
            logger.info { "TODO: Level gained pop-up" }
        }

        return levelsGained
    }

    fun refresh(stat: Stat) {
        refresh(stats.getValue(stat))
    }

    /**
     * Pushes one stat to the client - **and by default does not**.
     */
    fun refresh(data: StatData) {
        if (!sendStatsEnabled) {
            suppressed++
            return
        }
        val packet =
            UpdateStat(stat = data.stat.id, level = data.boostedLevel, experience = data.experience.toInt())

        player.client.write(packet)
    }

    companion object {
        /**
         * `-Dopennxt.experiment.sendStats=true` puts UPDATE_STAT on the wire. The code default stays OFF only
         * deferred burst (STAT_SEND_DELAY_TICKS after REBUILD_NORMAL) survived all 13 test runs of -
         * the "stats object NULL" crash never happened. Treat it as safe.
         */
        val sendStatsEnabled: Boolean
            get() = System.getProperty("opennxt.experiment.sendStats") == "true"

        private var suppressed = 0

        /** UPDATE_STAT writes withheld by the crash gate, process-wide. */
        fun suppressedRefreshes(): Int = suppressed

        fun resetSuppressedRefreshes() {
            suppressed = 0
        }
    }

    override fun boostStat(stat: Stat, boostBy: Int) {
        TODO("Not yet implemented")
    }

    override fun hasLevel(stat: Stat, level: Int, boostAble: Boolean): Boolean {
        return getLevel(stat, boostAble) >= level
    }

    override fun getLevel(stat: Stat, boosted: Boolean): Int {
        val data = stats.getValue(stat)
        return if (boosted) {
            data.boostedLevel
        } else {
            data.actualLevel
        }
    }
}