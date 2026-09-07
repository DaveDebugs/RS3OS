package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

/**
 * Every right-click menu option on an interface component (IF_BUTTON2..10).
 *
 * ONE handler for nine opcodes: the packets differ only in which menu row was
 * chosen, which the packet already carries as [IfButtonN.buttonOp].
 *
 * It LOGS and does nothing else, for exactly the reason
 * [IfButton1Handler] does: there is no per-interface component data in this
 * tree, so nothing here can know what component 32 of interface 906 is
 * supposed to do. Logging is how that mapping gets built.
 *
 * It prints BOTH candidate readings of the trailing five bytes because the
 * evidence does not choose between them yet - see the class doc on
 * [IfButtonN]. The first real right-click with a non-0xff payload will make one
 * of the two lines obviously right and the other obviously wrong.
 */
object IfButtonNHandler : GamePacketHandler<BasePlayer, IfButtonN> {
    private val logger = KotlinLogging.logger { }

    override fun handle(context: BasePlayer, packet: IfButtonN) {
        // A dialogue gets first refusal, and only a click on a component the
        // OPEN PAGE armed is taken - Dialogue.onButton refuses everything else,
        // so an ordinary panel click still falls through to the log below and a
        // forged hash cannot advance a conversation. This is the only inbound
        // route the dialogue system has; there is nothing else in the tree that
        // knows a chat box is open.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.DialogueWiring.handleButton(context, packet)
        ) return

 // The worn RING teleport, 1464:15, wired.
        //
        // THE ORDER IS LOAD-BEARING AND THIS BRANCH MUST STAY ABOVE ItemOps. ItemOps claims
        // EVERY 1464:15 click and unequips on ANY op, so a teleport row wired after it would take
        // the ring off instead of teleporting - the click would look handled and do the wrong
        // claims the same frame, so this ordering requirement is a live assertion and not a
        // comment that can quietly stop being true.
        //
        // It claims a frame only for the measured item, slot and row (item 39812, worn slot 12,
        // row 2 - Teleports' KDoc "THE RING"), so every other worn click still reaches ItemOps
 // unchanged. Measured: two teleports to the Grand
        // Exchange, 5 ticks each.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.Teleports.handleWornButton(context, packet)
        ) return

        // Backpack / worn item options (eat, wield, wear, remove). Before the wiring objects
        // below because those are component GUESSES from the 919-replay era and this one is a
        // measured, item-id-validated match - see ItemOps' KDoc for the four frames that settled
        // what `mid` and `arg2` carry.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.ItemOps.handleButton(context, packet)
        ) return

        // The ribbon's category buttons. 13833 builds them as dynamic children of 1431:0, so a
        // click arrives as interface 1431, component 0, with the BUTTON in the slot field.
        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == 1431 && packet.component == 0 &&
            com.opennxt.content.impl.ParentWindows.handleRibbonClick(context, packet.arg2)
        ) return

 // The category window's tab strip and close button.
        if (context is com.opennxt.model.world.WorldPlayer && packet.interfaceId == 1477 &&
            ((packet.component == 714 && com.opennxt.content.impl.ParentWindows.handleTabClick(context, packet.arg2)) ||
                (packet.component == 717 && com.opennxt.content.impl.ParentWindows.handleCloseClick(context, packet.arg2)))
        ) return

        // A skill in the Skills panel -> the skill guide. The slot is enum 7674's index, NOT the
        // skill id (ParentWindows.skillSlots carries the measurement). The panel is mounted under
        // two ids and the reference client's sessions show clicks on both: 1466:7 `stats_child` and 320:9 `stats`.
        if (context is com.opennxt.model.world.WorldPlayer &&
            ((packet.interfaceId == com.opennxt.content.impl.ParentWindows.SKILLS_IFACE &&
                packet.component == com.opennxt.content.impl.ParentWindows.SKILLS_BUTTON_LAYER) ||
                (packet.interfaceId == com.opennxt.content.impl.ParentWindows.SKILLS_IFACE_ALT &&
                    packet.component == com.opennxt.content.impl.ParentWindows.SKILLS_BUTTON_LAYER_ALT)) &&
            com.opennxt.content.impl.ParentWindows.handleSkillClick(context, packet.arg2)
        ) return

        // ...and the skill guide's OWN left-hand icon strip, interface 1218. These clicks reached no
 // handler at all before: the branch above matches 1466, and 1218 matched nothing,
        // so the guide never reloaded and sat on "Loading...". The component IS the button here -
        // every one of these frames carries slot 0xffff.
        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == com.opennxt.content.impl.ParentWindows.SKILLGUIDE_IFACE &&
            com.opennxt.content.impl.ParentWindows.handleSkillGuideClick(context, packet.component)
        ) return

 // The run orb's "Toggle run mode" (326:1 op 6). RunToggle's KDoc argues the
        // three numbers from the cache's own menu and clientscripts 1315/1741 - the interface is
        // not mounted by this server yet, so this path is cache-derived and not wire-confirmed.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.RunToggle.handleButton(context, packet)
        ) return

 // The tool belt panel. THE COMPONENT IS NOT THE TEST - THE SLOT IS.
        // The worn panel's icon strip is 1464:19 docked and 1462:35 in a window, and BOTH carry
        // the belt at slot 4353; slot 1 on the same components is the window-layout button and
        // must fall through (4 negative samples, ToolBeltPanel section 1b). Matching on the
        // component alone - which this branch did until today - opened the belt on the wrong
        // icon and could never have opened it on the windowed one.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.ToolBeltPanel.isOpenButton(
                packet.interfaceId, packet.component, packet.arg2
            )
        ) {
            com.opennxt.content.impl.ToolBeltPanel.open(context)
            return
        }
        if (context is com.opennxt.model.world.WorldPlayer && packet.interfaceId == 1944 &&
            packet.component == 7
        ) {
            com.opennxt.content.impl.ToolBeltPanel.handleSlotClick(context, packet.arg2)
            return
        }
 // The belt panel's CLOSE button. Until today the open button and the
        // slot grid were routed and 1944:102 was not, so a player who opened the belt could not
 // dismiss it for the rest of the session. whole
        // t855; see ToolBeltPanel.close.
        if (context is com.opennxt.model.world.WorldPlayer && packet.interfaceId == 1944 &&
            packet.component == com.opennxt.content.impl.ToolBeltPanel.CLOSE_COMPONENT
        ) {
            com.opennxt.content.impl.ToolBeltPanel.close(context)
            return
        }

 // The make-X panel. 1371:22 is the product grid and 1371:20 the quantity
        // selector - both armed mask 2 by MakeXPanel.open, so both arrive as IF_BUTTON1 - and
        // 1370:32 is "Close" (optmask 2 in the cache). The CONFIRM is not here: 1370:30 carries
        // optmask 1, the pausebutton bit, so it arrives as ClientProt 127 and is routed by
        // ResumePausebuttonHandler. Each branch returns only when the module took the frame, so a
        // click with no panel open still falls through to the log.
        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == com.opennxt.content.impl.MakeXPanel.CONTROLS_IFACE
        ) {
            when (packet.component) {
                com.opennxt.content.impl.MakeXPanel.PRODUCT_GRID ->
                    if (com.opennxt.content.impl.MakeXPanel.handleProductClick(context, packet.arg2)) return
                com.opennxt.content.impl.MakeXPanel.QUANTITY ->
                    if (com.opennxt.content.impl.MakeXPanel.handleQuantityClick(context, packet.arg2)) return
                // The MATERIAL dropdown button. Reference answers this frame with NOTHING (4 of 4,
                // 09-07T01-06-17 t29/t32/t37/t41); the module takes it only to arm the gate on the
                // shared 1477:896 below.
                com.opennxt.content.impl.MakeXPanel.CATEGORY_BUTTON ->
                    if (com.opennxt.content.impl.MakeXPanel.handleCategoryButton(context)) return
            }
        }
        // The row the player picked out of that dropdown, on the GAMEFRAME's own list host.
        // 4 of 4: `IF_BUTTON1 1477:896 arg2 = <row>` and the reply is varp 1169 =
        // enum(varp 1168)[row]. 1477:896 is shared by every dropdown in the client, so the module
        // claims it only while a make-X panel is open AND 1371:28 armed it - a frame it does not
        // claim falls straight through to whatever handles 1477 below.
        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == com.opennxt.content.impl.MakeXPanel.TOPLEVEL &&
            packet.component == com.opennxt.content.impl.MakeXPanel.CATEGORY_LIST &&
            com.opennxt.content.impl.MakeXPanel.handleCategoryChoice(context, packet.arg2)
        ) return
        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == com.opennxt.content.impl.MakeXPanel.IFACE &&
            packet.component == com.opennxt.content.impl.MakeXPanel.CLOSE &&
            com.opennxt.content.impl.MakeXPanel.handleClose(context)
        ) return

        // The ground-item LOOT WINDOW (1622 at 1477:705): the grid, Loot All and Close.
        // reference answers a ground click by OPENING this, not by taking the item -
        // 34/34 opens to that one parent, the pile is inventory 773, the grid is 1622:11.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.LootWindow.handleButton(context, packet)
        ) return

        // The minimap's "Open World Map" (1465:11) and the two clicks that close the map.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.WorldMapWindow.handleButton(context, packet)
        ) return

        // ...and the bank's close button, the ONLY way a player can shut
        // interface 517: this build maps no inbound close-interface ClientProt.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.BanksWiring.handleButton(context, packet)
        ) return

        // The Options menu's Edit Layout Mode button. Before GlobalCloseWiring only because
        // that object already owns 1433:79 (the Close X) and the two must not race; they
        // match on different components, so the order is documentation, not a dependency.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.OptionsMenu.handleButton(context, packet)
        ) return

        // ...and the two buttons that leave the layout editor it opens.
        if (context is com.opennxt.model.world.WorldPlayer &&
            (com.opennxt.content.impl.OptionsMenu.handleLayoutExit(context, packet) ||
                com.opennxt.content.impl.OptionsMenu.handleSaveConfirm(context, packet))
        ) return

        // ...and the Display Windows list inside it, where a panel is ticked on or off.
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.PanelToggles.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.GlobalCloseWiring.handleButton(context, packet)
        ) return

        logger.info {
            "IF_BUTTON${packet.buttonOp} " +
                com.opennxt.resources.Names949.component(packet.interfaceId, packet.component) + " " +
                "| item=${packet.mid} slot=${packet.arg2} " +
                "| as-ifbutton1[slot=${packet.altSlot} item=${packet.altItem} " +
                "flags=0x${packet.altFlags.toString(16).padStart(2, '0')}]"
        }
    }
}
