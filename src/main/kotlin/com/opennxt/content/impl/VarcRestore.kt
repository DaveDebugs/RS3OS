package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.variables.ClientSetvarcLarge
import com.opennxt.net.game.serverprot.generated.ClientSetvarcLarge64
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcstrSmall
import mu.KotlinLogging

/**
 * Plays a character's stored interface settings back at login.
 */
object VarcRestore {
    private val logger = KotlinLogging.logger { }

    /** Beyond this many characters a varcstr goes out in the large form. This file's cutoff; see the KDoc. */
    private const val STR_SMALL_MAX = 250

    /**
     * Sends `data/config/interface-defaults.tsv` - the 240 interface values a real 949 client was
     * handed at login - to a character that has none of its own yet.
     *
     * Includes varcBITS, which [send] cannot: `VARC_TRANSMIT` carries whole varcs, so a bit that a
     * player changes comes back inside its varc and needs no separate storage, but the SEED has to
     * set them because the client has nothing to derive them from on a first run. That asymmetry is
     * deliberate and is why this is a separate path rather than a default value for `stored`.
     */
    private fun seedFromReference(player: WorldPlayer, varcs: Boolean) {
 // BISECT SWITCH. Measured that day: with ALL varc state suppressed
        // (-Dopennxt.experiment.ui.varcRestore=false) a window resize keeps every HUD panel, in
        // two otherwise-different configurations - replay on/fillPanels off, and replay
        // off/fillPanels on. With it on, the minimap, backpack and ribbon vanish on resize. So the
        // varc state this class sends is the cause; what is NOT yet known is which half - the
        // character's own restored varcs, or the reference defaults seeded here.
        //
        // This flag removes only the seed, leaving the restore, so the two can be told apart in
        // one run instead of argued about.
        if (System.getProperty("opennxt.experiment.ui.varcSeed") == "false") {
            logger.info { "ui.varcSeed=false: the reference client's default varc/varcbit/varcstr table is NOT sent" }
            return
        }
        val path = com.opennxt.Constants.DATA_PATH.resolve("config").resolve("interface-defaults.tsv")
        if (!java.nio.file.Files.exists(path)) {
            logger.warn {
                "ui.varcRestore: ${player.name} is a first login and there is no seed table at " +
                    "$path, so the client starts from whatever it falls back to. Regenerate it " +
                    "from a reference login observation; see the file's own header."
            }
            return
        }
        var varc = 0; var bit = 0; var str = 0; var bad = 0
        java.nio.file.Files.readAllLines(path).forEach { line ->
            if (line.startsWith("#") || line.isBlank()) return@forEach
            val f = line.split('	')
            if (f.size < 3) { bad++; return@forEach }
            val id = f[1].toIntOrNull() ?: run { bad++; return@forEach }
            when (f[0]) {
                "varc" -> if (!varcs) Unit else f[2].toIntOrNull()?.let {
                    if (it in -128..127) player.client.write(ClientSetvarcSmall(id, it))
                    else player.client.write(ClientSetvarcLarge(id, it))
                    varc++
                } ?: bad++
                "varcbit" -> f[2].toIntOrNull()?.let {
                    player.client.write(com.opennxt.net.game.serverprot.variables.ClientSetvarcbitSmall(it, id)); bit++
                } ?: bad++
                // The value is the REST of the line, not f[2]: a varcstr may contain tabs, and
                // splitting it away would silently truncate exactly the values most likely to.
                "varcstr" -> {
                    player.client.write(ClientSetvarcstrSmall(id, line.substringAfter('	').substringAfter('	')))
                    str++
                }
                else -> bad++
            }
        }
        logger.info {
            (if (varcs) "ui.varcRestore: ${player.name} is a FIRST LOGIN - seeded "
             else "ui.varcRestore: seeding the un-storable half for ${player.name} - ") +
                "$varc varc(s), $bit varcbit(s) and $str varcstr(s) from the reference client's defaults" +
                (if (bad > 0) " ($bad unparseable row(s) skipped)" else "") +
                (if (varcs) ". From here the character's own settings are stored and the varc half of this seed is never sent again."
                 else ". Seed-only call with varcs=false - this path is no longer used on the " +
                     "restore route; see the retraction block in send().")
        }
    }

    fun send(player: WorldPlayer) {
        if (System.getProperty("opennxt.experiment.ui.varcRestore") == "false") {
            logger.info { "ui.varcRestore=false: stored interface settings are NOT replayed" }
            return
        }
        val stored = player.save.varcs
        if (stored.isEmpty()) {
            // FIRST LOGIN OF A CHARACTER: seed it with the reference client's, then let it diverge.
            //
 // This is the operator's design, stated: "the initial run of a new account
            // should run the reference client, after the account creation, and then it'll save how the user
            // sets it up". It is also the only sensible reading of the loop - a brand-new character
            // has no layout to restore, and handing it nothing means handing it whatever the client
            // falls back to, which is what a bare HUD looked like before any of this work.
            //
            // The seed is sent ONCE and never again: the moment the client transmits anything back
            // (which it does within a second of login), PlayerSave.varcs is non-empty and this
            // branch is not taken on the next login. The reference client's values are a starting point, not a
            // policy applied every time.
            seedFromReference(player, varcs = true)
            return
        }
        var ints = 0; var longs = 0; var strings = 0
        for ((id, v) in stored) {
            when (v) {
                is Int -> {
                    if (v in -128..127) player.client.write(ClientSetvarcSmall(id, v))
                    else player.client.write(ClientSetvarcLarge(id, v))
                    ints++
                }
                is Long -> {
                    player.client.write(ClientSetvarcLarge64((v ushr 32).toInt(), v.toInt(), id))
                    longs++
                }
                is String -> {
                    if (v.length <= STR_SMALL_MAX) player.client.write(ClientSetvarcstrSmall(id, v))
                    else player.client.write(ClientSetvarcstrLarge(id, v))
                    strings++
                }
                else -> logger.warn {
                    "ui.varcRestore: varc $id holds ${v.javaClass.simpleName}, which PlayerSave " +
                        "should have refused at construction - not sent"
                }
            }
        }
        // ===================================================================================
        // SEEDED ON EVERY LOGIN, and the paragraph below is kept because it is the record of a
        // fix that caused a worse bug than the one it fixed.
        //
        // WHAT IT SAID, and why it was wrong: taking the varc opcodes out of the Replay949 UI set
        // left this server sending 0 varcbits and 0 varcstrs where it had been sending 100 and 50,
        // so this call was added to re-seed that half from the reference client's defaults at every login. That
        // reasoning was sound about the gap and wrong about the remedy: it imprinted ANOTHER
        // ACCOUNT'S recorded layout on top of the player's own, every single time they logged in.
        //
 // by bisection, on:
        //
        //   varcs on,  seed on   -> maximise the window and the minimap, backpack and ribbon go
        //   varcs off, seed off  -> survives   (two configurations: replay on/off, fillPanels on/off)
        //   varcs on,  seed OFF  -> SURVIVES, and the HUD is the character's own
        //
        // The third run is the one that isolates it. The character's own restored varcs are fine;
        // the reference client seed on top of them is what breaks a window-state change - it carries layout
        // values that were valid for the recorded account at ITS window size, and the operator's
        // resize is the MAXIMISE button, i.e. a discrete window-state change rather than a drag.
        //
        // So the seed goes back to what it was designed to be: a FIRST-LOGIN starting layout, sent
        // once to a character that has none of its own, and never again.
        //
        // THE GAP THIS RE-OPENS, stated rather than hidden: varcstrs are still never stored,
        // because VARC_TRANSMIT carries only whole varcs and every value observed on the wire is
        // an int. After a character's first login its varcstrs are whatever the client holds. That
        // is a real gap and the honest place for it is a known limitation, not a workaround that
        // overwrites the player's layout every login to paper over it.
        // ===================================================================================
        //
 // ---- the retracted reasoning, as it stood for about twenty minutes ----
        // THE BITS AND STRINGS ARE SEEDED EVERY LOGIN, and this is not an oversight - it is the
        // shape of what VARC_TRANSMIT carries.
        //
        // The client pushes whole VARCS. Every value observed on the wire is an int (228 of 228
        // for this account), and no varcSTR has ever arrived - so there is nothing stored to
        // restore for the string half, and a character that has played for hours still has none.
        // Varcbits live INSIDE varcs, so in principle a restored varc carries its bits with it;
        // in practice the 100 the reference client sets bits on are not all inside the 228 the client transmits.
        //
        // and it is why this call exists: after the varc opcodes were taken out of the
 // Replay949 UI set on, this server sent 0 varcbits and 0 varcstrs where it had
        // been sending 100 and 50. Removing the replay's copy was right - it was overwriting the
        // player's own state - but it left the un-storable half with no source at all, which is a
        // regression introduced by the fix and caught in the run that followed it.
        //
        // So: the varc half comes from the character (below), the bit/string half from the reference client's
        // defaults (here). When VARC_TRANSMIT is understood well enough to store those too, this
        // call goes away and the seed goes back to being first-login-only.
        // seedFromReference(player, varcs = false)   <- REMOVED, see the block above.

        logger.info {
            "ui.varcRestore: replayed ${stored.size} stored interface setting(s) for ${player.name} " +
                "($ints int, $longs long, $strings string). This is the player's own saved HUD state " +
                "coming back - the reference client sends 212 CLIENT_SETVARC* in the same position. " +
                "-Dopennxt.experiment.ui.varcRestore=false disables."
        }
    }
}
