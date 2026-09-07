package com.opennxt.net.login

import com.opennxt.OpenNXT
import com.opennxt.ext.readString
import com.opennxt.ext.writeString
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.math.BigInteger

sealed class LoginRSAHeader(val seeds: IntArray, val uniqueId: Long) {
    class Fresh(
        seeds: IntArray,
        uniqueId: Long,
        val weirdThingId: Int, // name-related i think ?
        val weirdThingValue: Int, // name-related i think ?
        val thatBoolean: Boolean, // some boolean
        val password: String,
        val someLong: Long, // seems to be sent by server in another login stage, no clue what it is
        val randClient: Long // random value generated from client
    ) : LoginRSAHeader(seeds, uniqueId)

    class Reconnecting(seeds: IntArray, uniqueId: Long, val oldSeeds: IntArray) : LoginRSAHeader(seeds, uniqueId)

    companion object {
        fun ByteBuf.readLoginHeader(type: LoginType, exponent: BigInteger, modulus: BigInteger): LoginRSAHeader {
            val reconnecting = type == LoginType.GAME && readUnsignedByte().toInt() == 1

            // read rsa block
            val size = readUnsignedShort()
            val raw = ByteArray(size)
            readBytes(raw)

            // decrypt + validate block
            val block = Unpooled.wrappedBuffer(
                BigInteger(raw).modPow(exponent, modulus).toByteArray()
            )
            if (block.readUnsignedByte().toInt() != 10)
                throw IllegalStateException("rsa magic != 10")

            val seeds = IntArray(4) { block.readInt() }
            val uniqueId = block.readLong()

            if (reconnecting) {
                return Reconnecting(seeds, uniqueId, IntArray(4) { block.readInt() })
            }

            val thingId = block.readUnsignedByte().toInt()
            val thingValue = when (thingId) { // could read int but if nxt doesnt bzero packet you get invalid values
                0, 1 -> {
                    val value = block.readUnsignedMedium()
                    block.skipBytes(1)
                    value
                }
                2 -> block.readInt()
                3 -> {
                    block.skipBytes(4)
                    0
                }
                else -> throw IllegalStateException("got thingId $thingId")
            }

            val someBool = block.readUnsignedByte().toInt() == 1
            val password = block.readString()
            val someLong = block.readLong()
            val randClient = block.readLong()

            return Fresh(seeds, uniqueId, thingId, thingValue, someBool, password, someLong, randClient)
        }

        fun ByteBuf.writeLoginHeader(
            type: LoginType,
            header: LoginRSAHeader,
            exponent: BigInteger,
            modulus: BigInteger
        ) {
            if (type == LoginType.GAME)
                writeByte(if (header is Reconnecting) 1 else 0)

            val rsaBlock = Unpooled.buffer()
            rsaBlock.writeByte(10)
            rsaBlock.writeInt(header.seeds[0])
            rsaBlock.writeInt(header.seeds[1])
            rsaBlock.writeInt(header.seeds[2])
            rsaBlock.writeInt(header.seeds[3])
            rsaBlock.writeLong(header.uniqueId)
            if (type == LoginType.GAME && header is Reconnecting) {
                rsaBlock.writeInt(header.oldSeeds[0])
                rsaBlock.writeInt(header.oldSeeds[1])
                rsaBlock.writeInt(header.oldSeeds[2])
                rsaBlock.writeInt(header.oldSeeds[3])

                val raw = ByteArray(rsaBlock.writerIndex())
                rsaBlock.readBytes(raw)

                // Was `modPow(modulus, exponent)` - the two arguments the wrong
                // way round, so this raised the block to the power of the 1024-bit
                // MODULUS, reduced modulo the 17-bit EXPONENT 0x10001. The result
                // is an integer below 65537: a two- or three-byte "RSA block"
                // that no real server can decrypt. It does not throw, and
                // `writeShort(crypted.size)` faithfully writes 2 or 3, so the
                // frame goes out looking perfectly well formed.
                //
                // The fresh-login branch twenty-five lines below has always had
                // it the right way round, which is what makes this a typo rather
                // than a misunderstanding.
                //
                // Unreachable today - LoginClientHandler.createGameLoginPacket
                // always builds a Fresh header, and LoginServerDecoder rejects a
                // reconnect block on GAME login as unsupported - so this changes
                // nothing that currently runs. It goes live the moment reconnect
                // support is implemented, and the symptom then would be "the live
                // server rejects our login" with nothing pointing here.
                val crypted = BigInteger(raw).modPow(exponent, modulus).toByteArray()
                writeShort(crypted.size)
                writeBytes(crypted)
                return
            }

            header as Fresh
            rsaBlock.writeByte(header.weirdThingId)
            when (header.weirdThingId) {
                0, 1 -> {
                    rsaBlock.writeMedium(header.weirdThingValue)
                    rsaBlock.writeByte(0)
                }
                2 -> rsaBlock.writeInt(header.weirdThingValue)
                3 -> rsaBlock.writeInt(0)
                else -> throw IllegalStateException("thing id = ${header.weirdThingId}")
            }
            rsaBlock.writeBoolean(header.thatBoolean)
            rsaBlock.writeString(header.password)
            rsaBlock.writeLong(header.someLong)
            rsaBlock.writeLong(header.randClient)

            val raw = ByteArray(rsaBlock.writerIndex())
            rsaBlock.readBytes(raw)

            val crypted = BigInteger(raw).modPow(exponent, modulus).toByteArray()
            writeShort(crypted.size)
            writeBytes(crypted)
        }
    }
}