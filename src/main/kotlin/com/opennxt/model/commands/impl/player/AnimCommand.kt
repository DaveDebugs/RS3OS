package com.opennxt.model.commands.impl.player

import com.opennxt.model.commands.CommandSender
import com.opennxt.model.commands.SimpleCommand
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.world.WorldPlayer
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.entity.rendering.PlayerUpdates

object AnimCommand : SimpleCommand() {
    override fun execute(sender: CommandSender, alias: String, command: String) {
        if (sender !is WorldPlayer) {
            sender.error("This command can only be used by a player.")
            return
        }
        val args = command.split(" ").drop(1)
        if (args.isEmpty()) {
            sender.error("Usage: ::anim <id> [delay]")
            return
        }
        val id = args[0].toIntOrNull() ?: return sender.error("Invalid animation ID.")
        val delay = if (args.size > 1) args[1].toIntOrNull() ?: 0 else 0
        PlayerUpdates.animate(sender.entity, id, delay)
        sender.console("Playing animation id with delay delay.")
    }
}
