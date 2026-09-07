package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import mu.KotlinLogging
import com.opennxt.model.entity.player.PlayerInventory

object GlobalCloseWiring {
    private val logger = KotlinLogging.logger { }

    /**
     * `-Dopennxt.wiring.legacyBackpackOn1477_8=true` restores the pre-
     * behaviour in which a click on 1477:8 toggled the backpack. DEFAULT OFF,
     * because that mapping was wrong.
     */
    private val legacyBackpackOn1477_8: Boolean
        get() = System.getProperty("opennxt.wiring.legacyBackpackOn1477_8") == "true"

    private var closeSyncWindowStartMs = 0L
    private var closeSyncWindowCount = 0

    fun handleClientClosedInterfaces(world: WorldPlayer): Boolean {
        if (System.getProperty("opennxt.experiment.ui.clientCloseSync") == "false") return false
 // Log guard: reconciliation itself is idempotent and
        // stays on, but a client looping ClientProt 67 must not own the log. At most 10
        // logged occurrences per minute; the 11th prints one summary line and goes quiet.
        val now = System.currentTimeMillis()
        if (now - closeSyncWindowStartMs > 60_000) { closeSyncWindowStartMs = now; closeSyncWindowCount = 0 }
        closeSyncWindowCount++
        val logIt = closeSyncWindowCount <= 10
        if (closeSyncWindowCount == 11) logger.warn {
            "clientCloseSync: ClientProt 67 arrived 11+ times inside a minute - still reconciling, logging muted for this window"
        }
        val dropped = world.interfaces.markModalsClosedByClient()
        if (logIt) logger.info {
            if (dropped.isEmpty())
                "clientCloseSync: ClientProt 67 with no non-walkable subs open - no-op (67 also fires from an empty-context menu X)"
            else
                "clientCloseSync: ClientProt 67 -> reconciled ${dropped.size} client-closed sub(s) out of the server model (no IF_CLOSESUB sent): $dropped"
        }
        return true
    }

    /** Logged once when the raw-replay guard below first refuses a click. */
    private var replayGuardLogged = false

    fun handleButton(world: WorldPlayer, packet: IfButtonN): Boolean {
 // RAW-REPLAY GUARD. Every mapping in this file was derived under the
        // 919-replay HUD, where the server chose which interface sat on which component. Under the
        // replayed reference login the client's own components mean different things, and these
        // guesses actively break it: a click on a SKILL ROW inside 1466 arrived as 1466:7, which
        // the "Skills X" rule below read as a close and un-mounted the skills panel - the operator
        // saw every skill vanish from an open Skills window. The reference HUD wires its own closes
        // client-side, so the correct behaviour here is to do nothing.
        // -Dopennxt.wiring.globalClose.underReplay=true to force the old mappings back on.
        if (com.opennxt.content.impl.Replay949.only &&
            System.getProperty("opennxt.wiring.globalClose.underReplay") != "true"
        ) {
            if (!replayGuardLogged) {
                replayGuardLogged = true
                logger.warn {
                    "GlobalCloseWiring: DISABLED for this session - the login is a raw reference replay " +
                        "(replay949.mode=only) and these component mappings are 919-replay-era guesses. " +
                        "First refused click: ${packet.interfaceId}:${packet.component}."
                }
            }
            return false
        }
        // ==================================================================
        // 1477:8 IS THE RIBBON **OPTIONS** BUTTON, NOT A BACKPACK TOGGLE.
        //
        // READ OFF THE SERVED CACHE, not inferred from behaviour:
        //     optmask  = 254                       (bits 1..7 -> ops 1..7 armed)
        //     menu.opts= ["Options","Global","Music","SFX","Ambient","Voice","Area Loot"]
        //     optable  = [[16,0,13,0]]
        // Op 1 is "Options". [[16,0,13,0]] is the ESCAPE binding: 46 components
        // cache-wide carry that exact optable and their op-1 labels are Close x32,
        // Select x4, Skip x2, Exit, Back, Info - and this one, "Options". Derived
        // by scanning every optable in the cache, not typed in.
        //
        // SO PRESSING ESC SENDS IF_BUTTON1 ON 1477:8, and this branch used to
        // answer it by closing interface 1473. That is why ESC "closed the
        // backpack" and why 1477:8 has been labelled "backpack toggle - WORKS" in
        // this repository: it worked only because THIS CODE made it work. The label was
        // the server's own wiring read back as if it were a client fact.
        //
        // "ESC -> ClientProt 67 -> GlobalCloseWiring read it as a click on
        // 1477:8". ClientProt 67 has NO route into this class. It decodes to
        // ObservedClientPacket and ObservedClientPacketHandler routes only opcode
        // 127 into content; this method is reached only from IfButtonNHandler,
        // which is registered for IF_BUTTON1..10 (opcodes 55 and 430-447 in
        // WorldPlayer). The firing control is in
        // An earlier server log shows a
        // "GlobalCloseWiring: User clicked 1477:8" with NO opcode 67 anywhere near
        // it, and the census closes it arithmetically - 4 IF_BUTTON1 frames, 4
        // consumers (1477:8 x3 plus 517:317 x1). 67 merely arrived on the same
        // tick as two of the three. Co-occurrence, read as mechanism.
        //
        // WHAT 67 ACTUALLY IS, since it will come up again: the only thing in the
        // image that can send it is the body of CS2 command 784,
        // which calls - PACKET_BEGIN on Prot object - and
        // then walks the interface store closing every qualifying open interface
        // and clears store+0x178/+0x180. It rides along with ESC because
        // 1477:8's own hook (trigger 39 -> cs2 8181 -> 8182 -> 8177 -> 13831(1))
        // ends in that command. It is a notification that the CLIENT has closed
        // its interfaces, not a click and not a dialogue dismissal.
        //
        // WHY THE SERVER SHOULD DO NOTHING HERE: the Options menu is built
        // entirely client-side - 13831 opens interface 274 at 1477:808 and gosubs
        // 2935 ('Hop Worlds' / 'Exit to Lobby' / 'Logout' / 'Report Issue' /
        // 'Ribbon Setup' / 'Edit Layout Mode') locally. The IF_BUTTON1 is a
        // notification, not a request, and this repository holds no server-side state
        // behind ops 1..7. So: record it and return false, which also lets
        // IfButtonNHandler print the raw frame beside it.
        if (packet.interfaceId == 1477 && packet.component == 8 && !legacyBackpackOn1477_8) {
            logger.info {
                "ribbon Options: IF_BUTTON${packet.buttonOp} on 1477:8 (cache optmask 254, " +
                    "op 1 = \"Options\", ESC-bound via optable [[16,0,13,0]]). The client builds " +
                    "this menu itself (trigger 39 -> cs2 8181 -> 8182 -> 8177 -> 13831), so the " +
                    "server owns no state here and does nothing. This is NOT the backpack - the " +
                    "old mapping is behind -Dopennxt.wiring.legacyBackpackOn1477_8=true."
            }
            return false
        }

        // Backpack X / Ribbon toggle - REACHABLE ONLY UNDER THE LEGACY FLAG above.
        if (packet.interfaceId == 1477 && packet.component == 8) {
            if (world.interfaces.isOpened(1473)) {
                logger.info { "GlobalCloseWiring: User clicked 1477:8. Closing backpack..." }
                world.interfaces.close(1477, 98) // Backpack is mounted at 1477:98
            } else {
                logger.info { "GlobalCloseWiring: User clicked 1477:8. Opening backpack..." }
                world.interfaces.open(id = 1473, parent = 1477, component = 98, walkable = true)
                world.interfaces.events(id = 1473, component = 7, from = 65535, to = 65535, mask = 2097152)
                world.interfaces.events(id = 1473, component = 7, from = 0, to = 27, mask = 15302030)
                world.interfaces.events(id = 1473, component = 25, from = 0, to = 16, mask = 1422)
                world.interfaces.events(id = 1473, component = 1, from = 0, to = 5, mask = 2099198)
                world.interfaces.events(id = 1473, component = 28, from = 0, to = 5, mask = 2099198)
                PlayerInventory.sendBackpack(world)
            }
            return true
        }
        
        // Skills X
        if (packet.interfaceId == 1466 && packet.component == 7) {
            if (world.interfaces.isOpened(1466)) {
                logger.info { "GlobalCloseWiring: User clicked 1466:7. Closing skills..." }
                world.interfaces.close(1477, 284) // Skills is mounted at 1477:284
            } else {
                logger.info { "GlobalCloseWiring: User clicked 1466:7. Opening skills..." }
                world.interfaces.open(id = 1466, parent = 1477, component = 284, walkable = true)
            }
            return true
        }

        // Worn Equipment X / Ribbon toggle (1477:11)
        //
 // NOTE, flagged and deliberately NOT deleted: this branch
        // appears to be unreachable. 1477:11 has optmask 0 and an empty opts list
        // in the served cache, so the client cannot emit an IF_BUTTON naming it,
        // and it has never fired in any log read to date. Left in place because
        // "cannot fire" is a cache reading, not a proof about every client state,
        // and deleting it would remove the evidence that it does not.
        if (packet.interfaceId == 1477 && packet.component == 11) {
            if (world.interfaces.isOpened(1464)) {
                logger.info { "GlobalCloseWiring: User clicked 1477:11. Closing equipment..." }
                world.interfaces.close(1477, 109) // Worn Equipment is mounted at 1477:109
            } else {
                logger.info { "GlobalCloseWiring: User clicked 1477:11. Opening equipment..." }
                world.interfaces.open(id = 1464, parent = 1477, component = 109, walkable = true)
                PlayerInventory.sendWorn(world)
            }
            return true
        }

 //: the options menu's close X. 1433:79 hosts the cc button the frame
        // builder (8420 -> 8421) creates with 'Close'; its cache onop (key 39) is 8178 ->
        // 8179 = if_sethide(1, 1477:805) + tidy-up, i.e. the close is CLIENT-side and the
        // server only ever sees the click. Answer it by running 8179 for the client
        // (harmless if it already ran) and closing our record of 1433 at 1477:808.
        if (packet.interfaceId == 1433 && packet.component == 79) {
            world.client.write(com.opennxt.net.game.serverprot.RunClientScript(script = 8179, args = arrayOf()))
            world.interfaces.close(id = 1477, component = 808)
            logger.info { "options menu: Close (1433:79) - ran clientscript 8179 and closed 1477:808 (1433)" }
            return true
        }

        // Settings / Lobby X
        if (packet.interfaceId == 906 && packet.component == 81) {
            logger.info { "GlobalCloseWiring: User clicked 906:81. Closing modals..." }
            // 906 is the lobby/settings. We can just call closeModals()
            world.interfaces.closeModals()
            return true
        }

        // Unknown panel 1465 X
        if (packet.interfaceId == 1465 && packet.component == 11) {
            logger.info { "GlobalCloseWiring: User clicked 1465:11. Closing modals..." }
            world.interfaces.closeModals()
            return true
        }

        return false
    }
}
