package com.opennxt.net.game.serverprot

import com.opennxt.net.buf.DataTransformation
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.buf.GamePacketReader
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.pipeline.GamePacketCodec

/**
 * `OBJ_ADD_949` - build 949 ServerProt opcode 112, fixed 6 bytes. The
 * 3-byte-obj-id twin of [ObjAdd] (41): same message, same sink, same argument
 * roles, and `_949` is this server's suffix rather than a Jagex name.
 */
data class ObjAdd949(val id: Int, val count: Int, val coord: Int) : GamePacket {

    object Codec : GamePacketCodec<ObjAdd949> {

        override fun encode(packet: ObjAdd949, buf: GamePacketBuilder) {
            require(packet.id in 0..0xffffff) { "obj id does not fit a umedium: ${packet.id}" }
            require(packet.count in 0..0xffff) { "count does not fit a ushort: ${packet.count}" }
            require(packet.coord in 0..0xff) { "coord does not fit a ubyte: ${packet.coord}" }

            // Written byte by byte because no DataOrder expresses [mid, high, low].
            buf.put(DataType.BYTE, (packet.id shr 8) and 0xff)
            buf.put(DataType.BYTE, (packet.id shr 16) and 0xff)
            buf.put(DataType.BYTE, packet.id and 0xff)

            buf.put(DataType.SHORT, DataTransformation.ADD, packet.count)
            buf.put(DataType.BYTE, packet.coord)
        }

        override fun decode(buf: GamePacketReader): ObjAdd949 {
            val d0 = buf.getUnsigned(DataType.BYTE).toInt()
            val d1 = buf.getUnsigned(DataType.BYTE).toInt()
            val d2 = buf.getUnsigned(DataType.BYTE).toInt()
            val id = (d1 shl 16) or (d0 shl 8) or d2

            val count = buf.getUnsigned(DataType.SHORT, DataTransformation.ADD).toInt()
            val coord = buf.getUnsigned(DataType.BYTE).toInt()

            return ObjAdd949(id, count, coord)
        }
    }
}
