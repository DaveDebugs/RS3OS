package com.opennxt.net.game.serverprot

import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

data class RunClientScript(val script: Int, val args: Array<Any> = emptyArray()) : GamePacket {
    object Codec : GamePacketCodec<RunClientScript> {
 //: a LONG argument is descriptor char 'l' and 8 bytes big-endian.
        // The reference client's login runs script 5559 with desc "l" (14-byte frame: desc, NUL,
        // 8-byte arg, 4-byte id - read exactly once
        // it learned 'l'); this codec could only say 'i' or 's', so our 5559 went
        // out with a 4-byte int where the client's script expects a long. A Kotlin
        // Long now encodes as 'l', and 'l' decodes back to a Long, so the round
        // trip is symmetric. Every other non-String argument is still an int.
        override fun encode(packet: RunClientScript, buf: GamePacketBuilder) {
            val desc = String(packet.args.map { when (it) { is String -> 's'; is Long -> 'l'; else -> 'i' } }.toCharArray())

            buf.putString(desc)
            for (i in desc.length - 1 downTo 0) {
                when (val it = packet.args[i]) {
                    is String -> buf.putString(it)
                    is Long -> buf.put(DataType.LONG, it)
                    is Int -> buf.put(DataType.INT, it)
                    else -> throw IllegalArgumentException("RUNCLIENTSCRIPT only takes String, Int and Long args, got '$it'")
                }
            }
            buf.put(DataType.INT, packet.script)
        }

        override fun decode(buf: GamePacketReader): RunClientScript {
            val desc = buf.getString()

            val args = arrayOfNulls<Any>(desc.length)
            val chars = desc.toCharArray()
            for (i in desc.length - 1 downTo 0) {
                args[i] = when (chars[i]) {
                    's' -> buf.getString()
                    'l' -> buf.getSigned(DataType.LONG)
                    else -> buf.getSigned(DataType.INT).toInt()
                }
            }

            return RunClientScript(buf.getSigned(DataType.INT).toInt(), args.requireNoNulls())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RunClientScript

        if (script != other.script) return false
        if (!args.contentEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = script
        result = 31 * result + args.contentHashCode()
        return result
    }

    override fun toString(): String = "RunClientScript(script=$script, args=[${
        args.joinToString(separator = ", ") { if (it is String) "\"$it\"" else it.toString() }
    }])"
}