package com.opennxt.model.commands

import com.opennxt.model.commands.impl.proxy.HexdumpOffCommand
import com.opennxt.model.commands.impl.proxy.HexdumpOnCommand
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import mu.KotlinLogging

class CommandRepository {
    private val logger = KotlinLogging.logger {  }

    val commands = Object2ObjectOpenHashMap<String, Command>()

    init {
        commands["hexdump-on"] = HexdumpOnCommand
        commands["hexdump-off"] = HexdumpOffCommand
        commands["anim"] = com.opennxt.model.commands.impl.player.AnimCommand
        commands["prottest"] = com.opennxt.model.commands.impl.player.ProtTestCommand
 //the run toggle. Not a mod command - the reference client exposes the toggle to everyone;
        // only its `energy` sub-command is gated. The orb (interface 326) is not mounted by this
        // server, so this is currently the only route to it - see content/impl/RunToggle.kt.
        commands["run"] = com.opennxt.model.commands.impl.player.RunCommand

        // The game-mod toolkit. Every one refuses unless the sender holds the matching power, so
        // registering them for everybody is safe - see model/commands/impl/mod/ModCommands.kt.
        commands["item"] = com.opennxt.model.commands.impl.mod.ItemCommand
        commands["spawn"] = com.opennxt.model.commands.impl.mod.ItemCommand
        commands["npc"] = com.opennxt.model.commands.impl.mod.NpcCommand
        commands["tele"] = com.opennxt.model.commands.impl.mod.TeleCommand
        commands["teleport"] = com.opennxt.model.commands.impl.mod.TeleCommand
        commands["where"] = com.opennxt.model.commands.impl.mod.WhereCommand
        commands["lodestone"] = com.opennxt.model.commands.impl.mod.LodestoneCommand
        commands["lode"] = com.opennxt.model.commands.impl.mod.LodestoneCommand
        commands["mod"] = com.opennxt.model.commands.impl.mod.ModCommand
        commands["mods"] = com.opennxt.model.commands.impl.mod.ModsCommand
 //: cosmetics, maxed stats, every emote (spawn-item power)
        commands["cosmetic"] = com.opennxt.model.commands.impl.mod.CosmeticCommand
        commands["cosmetics"] = com.opennxt.model.commands.impl.mod.CosmeticCommand
        commands["max"] = com.opennxt.model.commands.impl.mod.MaxCommand
        commands["emotes"] = com.opennxt.model.commands.impl.mod.EmotesCommand

        logger.info { "Registered ${commands.size} commands" }
    }

    fun complete(sender: CommandSender, input: String): Collection<String> {
        val split = input.split(" ", limit = 2)
        val commandName = split[0].toLowerCase()

        val match = commands[commandName]
        if (match != null) {
            return match.autocomplete(sender, split[0], if (split.size == 1) "" else split[1])
        }

        if (split.size == 1)
            return commands.keys.filter { it.startsWith(input.toLowerCase()) }

        throw CommandException("Could not find a command named '${commandName}'")
    }

    fun execute(sender: CommandSender, input: String) {
        val split = input.split(" ", limit = 2)
        val commandName = split[0].toLowerCase()

        val match = commands[commandName] ?: throw CommandException("Command not found: '$commandName'")

        match.execute(sender, split[0], if (split.size == 1) "" else split[1])
    }
}