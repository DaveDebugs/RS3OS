package com.opennxt.net.game.serverprot.audio

import com.opennxt.net.game.GamePacket

/**
 * ServerProt 228 (MIDI_SONG_STOP), size 0.
 */
object MidiSongStop : GamePacket {
    override fun toString(): String = "MidiSongStop"
}
