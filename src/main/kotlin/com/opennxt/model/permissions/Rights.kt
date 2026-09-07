package com.opennxt.model.permissions

import com.opennxt.Constants
import com.google.gson.JsonParser
import mu.KotlinLogging
import java.nio.file.Files

/**
 * WHO IS ALLOWED TO DO WHAT, and the file a server owner edits to say so.
 *
 * Until now [PermissionsHolder.hasPermissions] returned `true` for every node and every player, which
 * was harmless while the only commands were `::anim` and `::prottest` and is not harmless the moment a
 * command can conjure items. This introduces exactly two ranks and one file.
 *
 * ## The ranks
 *
 * [Rights.PLAYER] (0) is everyone. [Rights.MOD] (2) is a GAME MOD: an account that may spawn items and
 * npcs, teleport itself anywhere, and use any lodestone whether or not it has been activated. There is
 * deliberately no third rank - a two-value model is one nobody can get subtly wrong, and the per-power
 * switches below already give a server owner finer control than a ladder of ranks would.
 *
 * The number is the same one the game login response carries in its `rights` field, which is why MOD is
 * 2 rather than 1: the client already understands 2, and a client told it is talking to a rank-2 account
 * offers staff-only affordances of its own (the world map's right-click menu among them).
 *
 * ## The file
 *
 * `data/config/mods.json`, read at boot and re-readable at runtime with [reload]:
 *
 * ```json
 * {
 *   "mods": [
 *     { "username": "yourname", "rights": 2,
 *       "powers": ["spawn-item", "spawn-npc", "teleport", "lodestone"],
 *       "note": "server owner" }
 *   ]
 * }
 * ```
 *
 * `powers` is optional; omitting it grants every power the rank implies. A username listed with
 * `"rights": 0` is an explicit demotion, which is why the file is consulted even for accounts it grants
 * nothing to. The file is OPTIONAL - no file means no mods, which is the right default for a server
 * somebody just started.
 *
 * The save carries the same number ([com.opennxt.model.account.PlayerSave.rights]) so a mod made in-game
 * with `::mod` survives a restart without anyone editing JSON; the file wins on login, so an owner can
 * always take rights back by editing it even if the save says otherwise. That ordering is deliberate:
 * the file is the one thing a locked-out owner can still reach.
 */
enum class Rights(val id: Int) {
    PLAYER(0),
    MOD(2);

    companion object {
        fun of(id: Int): Rights = if (id >= MOD.id) MOD else PLAYER
    }
}

/** The powers a mod can hold. Node names are what [PermissionsHolder.hasPermissions] is asked for. */
object Powers {
    const val SPAWN_ITEM = "spawn-item"
    const val SPAWN_NPC = "spawn-npc"
    const val TELEPORT = "teleport"
    const val LODESTONE = "lodestone"
    const val GRANT = "grant"

    /** Everything a MOD gets when a profile does not narrow it. GRANT is included: a mod can make a mod. */
    val ALL = setOf(SPAWN_ITEM, SPAWN_NPC, TELEPORT, LODESTONE, GRANT)
}

/**
 * The `data/config/mods.json` roster.
 *
 * Every failure here is survivable and loud: a missing file is normal, a malformed one is a warning and
 * an empty roster, never an exception out of the login path. A server that cannot read its mod list must
 * still let people play.
 */
object ModProfiles {
    private val logger = KotlinLogging.logger { }

    data class Profile(val username: String, val rights: Rights, val powers: Set<String>, val note: String?)

    val path get() = Constants.DATA_PATH.resolve("config").resolve("mods.json")

    @Volatile
    private var profiles: Map<String, Profile> = emptyMap()

    @Volatile
    private var loaded = false

    /** Case-insensitive: usernames are matched lowercased, as the account store stores them. */
    fun of(username: String): Profile? {
        if (!loaded) reload()
        return profiles[username.lowercase().trim()]
    }

    fun size(): Int {
        if (!loaded) reload()
        return profiles.size
    }

    /** Re-reads the file. Returns how many profiles are now in force. */
    fun reload(): Int {
        loaded = true
        val file = path
        if (!Files.isRegularFile(file)) {
            profiles = emptyMap()
            logger.info { "ModProfiles: no $file - no game mods are configured (this is the default)" }
            return 0
        }
        val out = LinkedHashMap<String, Profile>()
        try {
            val root = JsonParser().parse(Files.newBufferedReader(file)).asJsonObject
            val arr = root.getAsJsonArray("mods") ?: throw IllegalArgumentException("no 'mods' array")
            for (el in arr) {
                val o = el.asJsonObject
                val username = o.get("username")?.asString?.lowercase()?.trim()
                if (username.isNullOrEmpty()) {
                    logger.warn { "ModProfiles: an entry has no username - skipped" }
                    continue
                }
                val rights = Rights.of(o.get("rights")?.asInt ?: Rights.MOD.id)
                val powers = o.getAsJsonArray("powers")?.map { it.asString.lowercase().trim() }?.toSet()
                    ?: Powers.ALL
                out[username] = Profile(username, rights, powers, o.get("note")?.asString)
            }
        } catch (e: Exception) {
            logger.warn(e) { "ModProfiles: $file could not be read - NO mods are in force until it is fixed" }
            profiles = emptyMap()
            return 0
        }
        profiles = out
        logger.info {
            "ModProfiles: ${out.size} profile(s) from $file - " +
                out.values.joinToString { "${it.username}=${it.rights}(${it.powers.size} powers)" }
        }
        return out.size
    }

    /**
     * Adds or updates one profile and writes the file, so `::mod <name>` in-game and the roster on disk
     * never disagree. Returns false (having written nothing) if the file cannot be written.
     */
    fun grant(username: String, rights: Rights, powers: Set<String> = Powers.ALL): Boolean {
        if (!loaded) reload()
        val key = username.lowercase().trim()
        val merged = LinkedHashMap(profiles)
        merged[key] = Profile(key, rights, powers, "granted in-game")
        return try {
            Files.createDirectories(path.parent)
            val sb = StringBuilder("{\n  \"mods\": [\n")
            merged.values.forEachIndexed { i, p ->
                sb.append("    { \"username\": \"").append(p.username).append("\", \"rights\": ").append(p.rights.id)
                    .append(", \"powers\": [").append(p.powers.joinToString { "\"$it\"" }).append("]")
                p.note?.let { sb.append(", \"note\": \"").append(it.replace("\"", "'")).append("\"") }
                sb.append(" }").append(if (i == merged.size - 1) "\n" else ",\n")
            }
            sb.append("  ]\n}\n")
            Files.write(path, sb.toString().toByteArray())
            profiles = merged
            logger.info { "ModProfiles: $key is now ${rights} - written to $path" }
            true
        } catch (e: Exception) {
            logger.warn(e) { "ModProfiles: could not write $path - $key was NOT granted" }
            false
        }
    }
}
