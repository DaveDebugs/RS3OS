package com.opennxt.content.impl

import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

/**
 * The ONE file that knows both a [com.opennxt.content.ContentPlayer] and a [WorldPlayer] for
 * fletching, shaped on [BuryWiring] and [FiremakingWiring] and for the reason their KDocs give:
 */
object FletchingWiring {

    private val logger = KotlinLogging.logger { }

    @Volatile
    private var installed = false

    /** Whether [install] has bound the hook and the seams. Observable so a check asserts a number. */
    fun isInstalled(): Boolean = installed

    /**
     * Held as a field so [install] is idempotent (two hooks would start two actions for one click,
     * and the second [Fletching.craft] would re-arm the first's cadence) and so [uninstall] removes
     * exactly it.
     */
    private val hook: (WorldPlayer, String, Int, String, Int) -> Boolean = { world, action, itemId, name, slot ->
 // NOT a fixed string since: [Fletching.claims] asks the CACHE which backpack row
        // this module owns for that item - `Craft` on a log, `Feather` on a shaft, `Tip` on a
        // headless shaft. An item with no make-X panel still answers only to `Craft`, so no click
        // that belonged to Bury, Firemaking or the tool belt can be taken here.
        if (!Fletching.claims(action, itemId)) false
        else {
            val content = world.contentPlayer
            SkillingWiring.bind(content, world)
            val result = Fletching.craft(content, itemId, name, slot, action)
            // NOT_MINE means the module declined (switched off, or the row was something else): let
            // ItemOps print its own line. Every other outcome - including the refusals - is a
            // decision this module made and owns.
            result.outcome != Fletching.Outcome.NOT_MINE
        }
    }

    /**
     * Points [Fletching]'s seams at the live server and adds the [ItemOps] hook.
     *
     * Returns false when fletching is switched off, so the seams are not moved for a module that
     * will never fire - the same contract [FiremakingWiring.install] has.
     */
    fun install(): Boolean {
        if (!Fletching.enabled) {
            logger.warn { "fletching wiring: fletching is disabled, leaving the ContentPlayer-only seams in place" }
            return false
        }

        // The SAME backpack the login path sends and WorldPlayer.toSave persists - not a copy.
        Fletching.containerSupplier = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }

        Fletching.levelSupplier = { content, stat ->
            val world = SkillingWiring.ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }

        // XP through PlayerStatContainer.addExperience, whose UPDATE_STAT refresh is gated behind
        // on every cut tick, this repository accrues the same 5 and withholds the packet.
        Fletching.xpSink = { content, stat, amount ->
            SkillingWiring.ownerOf(content)?.stats?.addExperience(stat, amount, SkillingWiring.SKILLING_SOURCE)
        }

        // The type is the 109, not the 0 the rest of this repository passes - see
        // Fletching.MESSAGE_TYPE, and Bury.MESSAGE_TYPE, which measured the same value on a
        // different action in a different observation.
        Fletching.messageSink = { content, type, msg ->
            SkillingWiring.ownerOf(content)?.client?.write(MessageGame(type, msg))
        }

        // The DELAY is carried, not flattened to 0: the reference client's stop block on the last cut tick had
        // delay 20 and every cut animation had delay 0.
        Fletching.animationSink = { content, ids, delay ->
            SkillingWiring.ownerOf(content)?.let { world ->
                PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, delay))
            }
        }

        Fletching.inventoryResend = { content ->
            val world = SkillingWiring.ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }

        // A disconnect stops the action on the next tick even if the cull path is ever removed -
        // the same belt and braces [Cooking.onlineCheck] has. A ContentPlayer with no WorldPlayer
        // behind it (a headless one) is treated as online, which is what a check needs.
        // runCatching, exactly as [CookingWiring] does it: `OpenNXT.world` is a lateinit-style
        // singleton and a check that installs the wiring headlessly must not blow up on the first
        // tick. No owner and no world means no evidence of a logout, so the action keeps running.
        Fletching.onlineCheck = { content ->
            val owner = SkillingWiring.ownerOf(content)
            val world = runCatching { com.opennxt.OpenNXT.world }.getOrNull()
            owner == null || world == null || world.isOnline(owner.name)
        }

 // THE MAKE-X PANEL. This is the ONE place that knows both a ContentPlayer and a
        // WorldPlayer for it, which is why [Fletching] can stay free of `model.world` and
        // [MakeXPanel] can stay free of [ContentPlayer].
        //
        // The three varps that build the panel - 1168, 7881, 1169 - are for this exact
        // chain and for no other: 09-07T01-06-17 t368, the tick after the operator chose "fletch"
        // on Logs. varp 1169 is the whole lever (the client builds the rows from it), so a material
        // this triple was not measured for gets NO panel rather than a guessed category, and
        // [Fletching] falls back to its pre-panel behaviour.
        Fletching.panelOpener = { content, recipe, product, slot ->
            val world = SkillingWiring.ownerOf(content)
            val panel = Fletching.panelFor(recipe.logId)
            if (world == null || panel == null || !MakeXPanel.enabled) false
            else {
                val categories = MakeXPanel.categoriesFor(panel)
                val opening = categories.firstOrNull { it.index == panel.defaultIndex }
                    ?: categories.firstOrNull { it.rows.isNotEmpty() }
                val selected = opening?.rows?.get(product.gridSlot)
                    ?: opening?.rows?.entries?.minByOrNull { it.key }?.value
                if (opening == null || selected == null) false
                else MakeXPanel.open(
                    world,
                    MakeXPanel.Session(
                        materialId = panel.materialId,
                        materialName = panel.materialName,
                        categoryA = panel.categoryEnum,
                        categoryB = panel.nameEnum,
                        recipe = opening.recipeEnum,
                        rows = opening.rows,
                        gridToSlot = opening.gridToSlot,
                        selected = selected,
                        // THE COUNT is re-derived from the backpack at the open, at every material
                        // switch and again at the confirm, never carried: it is the reference client's varp 8846
                        // and it counts whole-or-partial CYCLES over EVERY ingredient, which is why
                        // five feathers show 1 rather than 0 (Fletching.cyclesAvailable).
                        countOf = { w, row ->
                            val r = Fletching.recipeFor(panel.materialId)
                            val p = r?.products?.firstOrNull { it.itemId == row.itemId }
                                ?: panel.products.firstOrNull { it.itemId == row.itemId }
                            if (r == null || p == null) 0
                            else Fletching.cyclesAvailable(PlayerInventory.backpackOf(w), r, p)
                        },
                        onConfirm = { w, row, count ->
                            SkillingWiring.bind(w.contentPlayer, w)
                            Fletching.startFromPanel(w.contentPlayer, panel.materialId, row.itemId, slot, count)
                                .outcome == Fletching.Outcome.STARTED
                        },
                        categories = categories,
                        categoryIndex = panel.defaultIndex
                    )
                )
            }
        }

        if (!ItemOps.backpackActionHooks.contains(hook)) ItemOps.backpackActionHooks.add(hook)
        installed = true

        val recipes = runCatching { Fletching.recipes }.getOrDefault(emptyMap())
        val panelStats = runCatching { Fletching.panelStats() }
            .getOrDefault(Fletching.PanelStats(0, 0, 0, 0, 0, 0))
        logger.info {
            "fletching wiring: the '${Fletching.CRAFT_ACTION}' backpack row is hooked over " +
                "${runCatching { Fletching.craftableItemCount() }.getOrDefault(-1)} craftable item ids; " +
                "${recipes.size} material(s), ${recipes.values.count { it.default != null }} of them clickable today; " +
                "${Fletching.SHAFT_COUNT} x ${Fletching.SHAFT_ITEM} per ${Fletching.LOGS_ITEM} for " +
                "${Fletching.LOGS_XP_TENTHS / 10.0} ${Fletching.STAT.name} xp every ${Fletching.CYCLE_TICKS} ticks, " +
                "animation ${Fletching.CUT_ANIMATION[0]}, '${Fletching.MEASURED_MESSAGE}' type ${Fletching.MESSAGE_TYPE} " +
                " (all). The MAKE-X PANEL is bound: a " +
                "Craft click on Logs opens ${MakeXPanel.IFACE}/${MakeXPanel.CONTROLS_IFACE} at " +
                "${MakeXPanel.TOPLEVEL}:${MakeXPanel.MOUNT} and starts nothing until RESUME_PAUSEBUTTON " +
                "${MakeXPanel.IFACE}:${MakeXPanel.CONFIRM}, which then makes varp ${MakeXPanel.VARP_COUNT} of them - " +
                "the reference client's own default. The PLAYER-CHOSEN count is still unresolved (F29): " +
                "${MakeXPanel.CONTROLS_IFACE}:${MakeXPanel.QUANTITY} is armed for it and no observation clicks it. " +
                "THE MATERIAL DROPDOWN and the TWO-INGREDIENT recipes landed the same day: " +
                "${panelStats.panels} panel(s) over ${panelStats.categories} dropdown rows with " +
                "${panelStats.products} product row(s) bound to a wiki recipe; ${panelStats.measuredPanels} of " +
                "the panels carry a varp (1168,7881) pair and ${panelStats.inheritedPanels} INHERIT the " +
                "Logs pair through the dropdown - that inheritance is NOT and " +
                "-Dopennxt.fletching.panelInherit=false removes it. The switch itself is measured: " +
                "${MakeXPanel.CONTROLS_IFACE}:${MakeXPanel.CATEGORY_BUTTON} opens the list (reference answers " +
                "NOTHING, 4/4) and ${MakeXPanel.TOPLEVEL}:${MakeXPanel.CATEGORY_LIST} arg2 carries the row, " +
                "answered with varp ${MakeXPanel.VARP_RECIPE} = enum(varp ${MakeXPanel.VARP_CATEGORY_A})[row] " +
                ". Grid slot = ${Fletching.GRID_STRIDE} x index + 1."
        }
        return true
    }

    /**
     * Puts the ContentPlayer-only seams back and removes the hook. Only
     * needs this, for the reason [FiremakingWiring.uninstall]
     * gives: installing the live seams inside a headless check would make every cut depend on a World.
     */
    fun uninstall() {
        ItemOps.backpackActionHooks.remove(hook)
        Fletching.resetSeams()
        installed = false
    }
}
