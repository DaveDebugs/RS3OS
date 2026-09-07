package com.opennxt.model.files

import com.opennxt.config.ServerConfig

/**
 * The `param=<N>=<value>` block the NXT client actually reads out of
 * `jav_config.ws`, generated from this server's own configuration.
 */
object ClientParams {

    /** Environment enum. 4 = "local", which makes the client reuse [MASTER_HOST] for every endpoint. */
    const val ENV_LOCAL = 4

    /**
     * Params whose absence stops the client dead, and why.
     *
     * Two distinct failure modes, established separately:
     *  - **25** and **60** are dereferenced with no presence check (25 at
     *    `14001ed54`, 60 in a clientscript opcode at `1401fced0`), so absence
     *    is an access violation.
     */
    val MANDATORY = setOf(5, 6, 25, 27, 35, 60)

    /**
     * Builds the block this server serves.
     *
     * The client carries four endpoint descriptors - A(host 35, id 27, ports
     * 45/46), B(3, 2, 47/48), C(37, 38, 41/42) and D(49, 38, 43/44). Jagex
     * points them at a lobby host, a content host and a world host on ports
     * 43594/443; we point every one of them at this server's single listener,
     * because whichever the client dials it then reaches us and the diagnostic
     * recorder says which handshake byte arrived. The pairing is preserved in
     * the code below even though both halves resolve to the same port here, so
     * that the game/TLS distinction stays visible rather than being lost.
     */
    fun build(config: ServerConfig): Map<String, String> {
        val host = config.hostname
        val game = config.ports.game.toString()

        // Every value below is either taken from Jagex's real config
        // (data/config/jav_config_reference.ws) or is a deliberate local
        // substitution. Nothing here is a guess any more; the previous version
        // had four, and three of them were wrong.
        val out = LinkedHashMap<String, String>()

        // --- the six the client cannot start without ------------------------
        // 25: environment. Jagex ships 0 (live). We ship 4 = "local", which is
        //     the one deliberate divergence: in that mode the client fills the
        //     other endpoint hosts from param 35 itself, which is exactly what
        //     a single-host private server wants.
        out["25"] = ENV_LOCAL.toString()
        out["5"] = "0"                       // game id; real config agrees
        out["6"] = "0"                       // language; real config agrees
        out["27"] = "5"                      // endpoint A id. Real value is 5; we shipped 1
        out["35"] = "http://$host"           // master server. Real one is a URL, not a bare
                                             // host - the client strips it on "//", "/" and ":"
        out["60"] = "0"                      // real config agrees

        // --- endpoint hosts -------------------------------------------------
        out["3"] = host                      // lobby   (real: lobby47a.runescape.com)
        out["37"] = host                     // content (real: content.runescape.com)
        out["49"] = host                     // content (real: content.runescape.com)
        out["40"] = "http://$host"           // real: https://world5.runescape.com

        // --- endpoint ids ---------------------------------------------------
        out["2"] = "1146"                    // we shipped 1
        out["38"] = "1200"                   // we shipped 1

        // --- ports ----------------------------------------------------------
        // Each endpoint carries a PAIR: a game port and a TLS port. Jagex ships
        // 43594 and 443. We had collapsed both onto the game port, which would
        // have had the client attempting TLS against a plaintext listener the
        // moment it preferred the secure one. Both now point at the game port
        // deliberately - we terminate no TLS - but they are kept as separate
        // entries so the distinction is visible rather than lost.
        for (p in listOf(41, 43, 45, 47)) out[p.toString()] = game   // real: 43594
        for (p in listOf(42, 44, 46, 48)) out[p.toString()] = game   // real: 443

        // --- the rest, copied from the real config -------------------------
        // These were previously absent entirely. Values that name a Jagex
        // service are pointed at us or blanked; the rest are carried across as
        // shipped, because a client that reads a field we left empty is a
        // failure mode we already paid for once.
        out["1"] = "0"
        out["4"] = "0"
        out["7"] = "0"
        out["8"] = "false"
        out["11"] = "225"                    // launcher version
        out["13"] = "false"
        out["14"] = "false"
        out["16"] = ".$host"                 // real: .runescape.com (cookie/domain suffix)
        out["17"] = "false"
        out["18"] = "0"
        out["20"] = "false"
        out["23"] = "false"
        out["24"] = "true"
        out["26"] = "false"
        out["28"] = "581101278"
        out["31"] = "11449"
        out["34"] = "0"
        out["39"] = "false"
        out["50"] = "0"
        out["51"] = "0"
        out["52"] = "0"
        out["57"] = "6438"

        // --- params Jagex ships EMPTY ---------------------------------------
        // 15, 19, 22, 32, 33 are present-but-empty in the real config. We used
        // to omit them entirely, which is NOT the same thing: the client's
        // lookup distinguishes "absent" from "present and empty", and the two
        // take different paths for any field read without a null guard. Copying
        // the real config means copying its empty strings too.
        for (p in listOf(15, 19, 22, 32, 33)) out[p.toString()] = ""

        // 21 is the loading-screen spec, carried across with the two Jagex
        // hostnames it contains left alone - it names image files, not
        // endpoints, so there is nothing here to point at us.
        // Value taken VERBATIM from data/config/jav_config_reference.ws. An
        // earlier draft of this line invented plausible-looking field names
        // (a "prcntbar=" spec and an orb sprite) that do not exist. A made-up
        // value here is worse than omitting the param, because it looks
        // authoritative to the next reader.
        out["21"] = "halign=true|valign=true|image=rs_logo.gif,0,-43|rotatingimage=rs3_loading_spinner.gif,0,47,9.6|progress=true,Verdana,13,0xFFFFFF,0,51"

        // Deliberately NOT copied: 10, 29 (Jagex session tokens), 36, 53-56,
        // 58, 59 (Jagex auth/payment/social URLs). Pointing a client at Jagex's
        // auth service while it talks to our game server is the one combination
        // guaranteed to confuse it, and none of them are needed to reach a
        // lobby.

        return out
    }
}
