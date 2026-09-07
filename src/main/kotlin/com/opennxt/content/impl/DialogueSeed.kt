package com.opennxt.content.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import mu.KotlinLogging
import java.nio.file.Files

/**
 * The WIKI layer for NPC dialogue.
 */
object DialogueSeed {

    private val logger = KotlinLogging.logger {}

    /** Read per call so a check can flip it; the seed itself loads once. */
    val enabled: Boolean get() = System.getProperty("opennxt.seed.dialogue") != "off"

    private val seedPath = Constants.DATA_PATH.resolve("seed").resolve("dialogue_wiki.json")

    /**
     * The placeholder every transcript uses for the local player, both as a
     * speaker (`'''Player:'''`) and inside a line ("Greetings Player, what
     * brings you here?"). Substituted whole-word for the real name by
     * [materialise], which is the only edit this layer makes to a seeded string.
     *
     * The transcripts also carry `[assigned creature]`, `[number]`, `[boy/girl]`
     * and similar square-bracket placeholders for state this server does not
     * model. Those are LEFT AS THEY ARE, visibly, rather than guessed at.
     */
    const val PLAYER_PLACEHOLDER = "Player"

    private val playerToken = Regex("\\bPlayer\\b")

    class Conversation(
        val page: String,
        val revid: Int,
        val section: String,
        val npcName: String,
        val npcIds: List<Int>,
        val droppedConditional: Int,
        val droppedAction: Int,
        val truncatedOptions: Int,
        val pages: List<Dialogue.Page>
    ) {
        override fun toString() = "$page # $section (npc '$npcName' ${npcIds.size} ids, ${pages.size} pages)"
    }

    class Seed(
        val conversations: List<Conversation>,
        val byId: Map<Int, Conversation>,
        val byName: Map<String, Conversation>,
        /** Conversations the LOADER refused, by reason. The builder's own refusals are in the file. */
        val refused: Map<String, Int>,
        val retrieved: String
    ) {
        val pageCount: Int get() = conversations.sumOf { it.pages.size }
    }

    val seed: Seed by lazy { load() }

    /** How many conversations the seed holds. Observable so a check can assert a number. */
    fun size(): Int = if (enabled) seed.conversations.size else 0

    /**
     * The conversation this layer would use for an npc, or null.
     *
     * Id first, then name (case-insensitively) - see the class doc for why the
     * name arm is load-bearing rather than defensive.
     */
    fun conversationFor(npcId: Int, npcName: String): Conversation? {
        if (!enabled) return null
        return seed.byId[npcId] ?: seed.byName[npcName.lowercase()]
    }

    /**
     * The pages for an npc with [playerName] substituted, or null when the seed
     * has nothing for it (which sends [Dialogue.script] on to its fallback).
     */
    fun pagesFor(npcId: Int, npcName: String, playerName: String): List<Dialogue.Page>? {
        val c = conversationFor(npcId, npcName) ?: return null
        return materialise(c.pages, playerName)
    }

    /** Substitutes the player's real name for the transcripts' `Player` placeholder. */
    fun materialise(pages: List<Dialogue.Page>, playerName: String): List<Dialogue.Page> =
        pages.map { p ->
            when (p) {
                is Dialogue.Page.Say -> p.copy(
                    name = if (p.speaker == Dialogue.Speaker.PLAYER) playerName
                    else playerToken.replace(p.name, playerName),
                    text = playerToken.replace(p.text, playerName)
                )

                is Dialogue.Page.Choose -> p.copy(
                    options = p.options.map { playerToken.replace(it, playerName) },
                    title = playerToken.replace(p.title, playerName)
                )
            }
        }

    private fun load(): Seed {
        if (!Files.isRegularFile(seedPath)) {
            logger.warn { "dialogue seed: $seedPath is absent; the wiki dialogue layer is empty" }
            return Seed(emptyList(), emptyMap(), emptyMap(), emptyMap(), "")
        }
        val root = JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
        val out = ArrayList<Conversation>()
        val refused = LinkedHashMap<String, Int>()
        fun refuse(why: String) { refused[why] = (refused[why] ?: 0) + 1 }

        for (e in root.getAsJsonArray("conversations")) {
            val o = e as? JsonObject ?: continue
            val pages = ArrayList<Dialogue.Page>()
            var broken: String? = null
            for (pe in o.getAsJsonArray("pages")) {
                val p = pe as? JsonObject ?: continue
                when (p.get("t")?.asString) {
                    "say" -> {
                        val text = p.get("x")?.asString ?: ""
                        val name = p.get("n")?.asString ?: ""
                        if (text.isEmpty() || name.isEmpty()) { broken = "empty-say"; break }
                        pages += Dialogue.Page.Say(
                            speaker = if (p.get("s")?.asString == "player") Dialogue.Speaker.PLAYER
                            else Dialogue.Speaker.NPC,
                            name = name,
                            text = text,
                            endsHere = p.get("e")?.asBoolean == true
                        )
                    }

                    "choose" -> {
                        val opts = p.getAsJsonArray("o").map { it.asString }
                        val targets = p.getAsJsonArray("g").map { it.asInt }
                        if (opts.isEmpty() || opts.size != targets.size ||
                            opts.size > Dialogue.OPTION_ROWS.size || opts.any { it.isEmpty() }
                        ) {
                            broken = "bad-choose"; break
                        }
                        pages += Dialogue.Page.Choose(opts, targets)
                    }

                    else -> { broken = "unknown-page-kind"; break }
                }
            }
            if (broken != null) { refuse(broken); continue }
            // Every jump target must land inside the conversation. A stale or
            // hand-edited seed that points past the end would silently close a
            // conversation early; refusing here makes it a countable defect.
            val bad = pages.filterIsInstance<Dialogue.Page.Choose>()
                .any { c -> c.targets.any { it >= pages.size || it < -1 } }
            if (bad) { refuse("target-out-of-range"); continue }
            if (pages.size < 2 || pages.first() !is Dialogue.Page.Say) { refuse("shape"); continue }

            out += Conversation(
                page = o.get("page").asString,
                revid = o.get("revid").asInt,
                section = o.get("section")?.asString ?: "",
                npcName = o.get("npc_name").asString,
                npcIds = o.getAsJsonArray("npc_ids").map { it.asInt },
                droppedConditional = o.get("dropped_conditional")?.asInt ?: 0,
                droppedAction = o.get("dropped_action")?.asInt ?: 0,
                truncatedOptions = o.get("truncated_options")?.asInt ?: 0,
                pages = pages
            )
        }

        // File order is the builder's ranking: longest conversation first. First
        // one to claim an id or a name keeps it, so the richest script wins.
        val byId = LinkedHashMap<Int, Conversation>()
        val byName = LinkedHashMap<String, Conversation>()
        for (c in out) {
            for (id in c.npcIds) byId.putIfAbsent(id, c)
            byName.putIfAbsent(c.npcName.lowercase(), c)
        }
        val seed = Seed(out, byId, byName, refused, root.get("retrieved")?.asString ?: "")
        logger.info {
            "dialogue seed: ${out.size} conversations, ${seed.pageCount} pages, " +
                "${byId.size} npc ids, ${byName.size} npc names, retrieved ${seed.retrieved}" +
                (if (refused.isEmpty()) "" else "; REFUSED $refused") +
                ". wiki transcripts, not this build's wire."
        }
        return seed
    }
}
