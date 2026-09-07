package com.opennxt.model.commands.impl.player

import com.opennxt.model.commands.CommandSender
import com.opennxt.model.commands.SimpleCommand
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.audio.MidiSong
import com.opennxt.net.game.serverprot.audio.MidiSongStop
import com.opennxt.net.game.serverprot.audio.SoundGroupRelease
import com.opennxt.net.game.serverprot.audio.SoundGroupStop
import com.opennxt.net.game.serverprot.audio.SoundMixbussSetvolume
import com.opennxt.net.game.serverprot.interfaces.IfMovesub
import com.opennxt.net.game.serverprot.interfaces.IfSetcolour
import com.opennxt.net.game.serverprot.variables.ClientSetvarcbitLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcbitSmall
import com.opennxt.net.game.serverprot.variables.VarbitLarge
import com.opennxt.net.game.serverprot.variables.VarbitSmall

/**
 * `::prottest` - fire one of the eleven serverprots derived from the client's
 * own handlers on, so a live client can say whether the derivation
 * is right.
 */
object ProtTestCommand : SimpleCommand() {

    private val USAGE = listOf(
        "::prottest colour <component> <rgb15>   IF_SETCOLOUR (op 108)",
        "::prottest movesub <from> <to>          IF_MOVESUB (op 88)",
        "::prottest varbit <id> [value]          VARBIT_SMALL (op 22)",
        "::prottest varbitbig <id> <value>       VARBIT_LARGE (op 51)",
        "::prottest varcbit <id> [value]         CLIENT_SETVARCBIT_SMALL (op 54)",
        "::prottest varcbitbig <id> <value>      CLIENT_SETVARCBIT_LARGE (op 50)",
        "::prottest song <id> [volume]           MIDI_SONG (op 37)",
        "::prottest songstop                     MIDI_SONG_STOP (op 228)",
        "::prottest soundstop <group>            SOUND_GROUP_STOP (op 195)",
        "::prottest soundrelease <group>         SOUND_GROUP_RELEASE (op 130)",
        "::prottest mixbuss <bus> <gain>         SOUND_MIXBUSS_SETVOLUME (op 128)"
    )

    override fun execute(sender: CommandSender, alias: String, command: String) {
        if (sender !is WorldPlayer) {
            sender.error("This command can only be used by a player.")
            return
        }
        val args = command.split(" ").drop(1)
        if (args.isEmpty()) {
            sender.console("Eleven serverprots derived from the client.")
            sender.console("Each prints WHAT TO LOOK FOR; if it does not happen, the layout is wrong.")
            USAGE.forEach { sender.console(it) }
            return
        }

        fun arg(i: Int, name: String): Int? {
            val v = args.getOrNull(i)?.toIntOrNull()
            if (v == null) sender.error("Expected an integer for <$name>.")
            return v
        }

        when (args[0].lowercase()) {
            "colour" -> {
                val comp = arg(1, "component") ?: return
                val rgb = arg(2, "rgb15") ?: return
                sender.console("LOOK FOR: component $comp changes colour. rgb15 = 5 bits each, 0x7fff is white.")
                sender.write(IfSetcolour(rgb, comp))
            }
            "movesub" -> {
                val from = arg(1, "from") ?: return
                val to = arg(2, "to") ?: return
                sender.console("LOOK FOR: the sub-interface at $from reappears at $to. Both are (iface shl 16) or component.")
                sender.write(IfMovesub(from, to))
            }
            "varbit" -> {
                val id = arg(1, "id") ?: return
                val v = args.getOrNull(2)?.toIntOrNull() ?: 1
                sender.console("LOOK FOR: whatever varbit $id drives. Value $v - out-of-range values are SILENTLY IGNORED by the client.")
                sender.write(VarbitSmall(id, v))
            }
            "varbitbig" -> {
                val id = arg(1, "id") ?: return
                val v = arg(2, "value") ?: return
                sender.console("LOOK FOR: whatever varbit $id drives. Same silent-ignore rule as ::prottest varbit.")
                sender.write(VarbitLarge(v, id))
            }
            "varcbit" -> {
                val id = arg(1, "id") ?: return
                val v = args.getOrNull(2)?.toIntOrNull() ?: 1
                sender.console("LOOK FOR: a CLIENT-side varbit change (varc domain, not varp).")
                sender.write(ClientSetvarcbitSmall(v, id))
            }
            "varcbitbig" -> {
                val id = arg(1, "id") ?: return
                val v = arg(2, "value") ?: return
                sender.console("LOOK FOR: a CLIENT-side varbit change (varc domain, not varp).")
                sender.write(ClientSetvarcbitLarge(v, id))
            }
            "song" -> {
                val id = arg(1, "id") ?: return
                val vol = args.getOrNull(2)?.toIntOrNull() ?: 255
                sender.console("LISTEN FOR: music track $id starts. This is the clearest test in the set - it is audible or it is not.")
                sender.write(MidiSong(vol, id))
            }
            "songstop" -> {
                sender.console("LISTEN FOR: the current music stops. Run ::prottest song first or this proves nothing.")
                sender.write(MidiSongStop)
            }
            "soundstop" -> {
                val g = arg(1, "group") ?: return
                sender.console("LISTEN FOR: sounds in group $g stop.")
                sender.write(SoundGroupStop(g))
            }
            "soundrelease" -> {
                val g = arg(1, "group") ?: return
                sender.console("LISTEN FOR: sounds in group $g are released (allowed to finish, not cut).")
                sender.write(SoundGroupRelease(g))
            }
            "mixbuss" -> {
                val bus = arg(1, "bus") ?: return
                val gain = arg(2, "gain") ?: return
                sender.console("LISTEN FOR: volume change on bus $bus. Gain is gain/65536, so 65536 = unity, 0 = silent.")
                sender.write(SoundMixbussSetvolume(bus, gain))
            }
            else -> {
                sender.error("Unknown subcommand '${args[0]}'.")
                USAGE.forEach { sender.console(it) }
            }
        }
    }
}
