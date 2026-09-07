package com.opennxt.net.game.protocol

import com.opennxt.net.buf.*
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap

data class PacketFieldDeclaration(val key: String, val dataCodec: DataCodec<Any>) {
    interface DataCodec<T : Any> {
        fun read(buffer: GamePacketReader): T
        fun write(buffer: GamePacketBuilder, value: T)
    }

    companion object {
        /**
         * Parses one field declaration line.
         *
         * [where] names the file and line number, and it exists because neither
         * message below used to name either one. The throw propagates out of
         * PacketRegistry.register -> reload() -> ProtocolInformation.load() ->
         * OpenNXT.run() and kills boot, and the most specific fact anywhere in
         * the resulting stack trace was a type name - in a tree with dozens of
         * hand-recovered declaration files, where "which file, which line" is
         * the only question worth answering.
         *
         * The split is whitespace-tolerant now as well. `split(" ", limit = 3)`
         * on `id  ushort` - two spaces, which hand-aligning a column produces
         * without thinking about it - yielded ["id", "", "ushort"], so parts[1]
         * was empty and the error read "Codec not found for type " with nothing
         * after it. Splitting on runs of whitespace parses that line as
         * intended. No existing file changes meaning: every current 949
         * declaration uses single spaces before the type, and the double spaces
         * that do appear are before the trailing comment, which limit = 3
         * already absorbed.
         */
        fun fromString(value: String, where: String = "<unknown location>"): PacketFieldDeclaration {
            val parts = value.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 2)
                throw IllegalArgumentException(
                    "$where: '$value' has ${parts.size} part(s). Proper format: 'name type <comment>'"
                )

            val codec = Codecs.codecs[parts[1]]
                ?: throw IllegalArgumentException(
                    "$where: no codec for field type '${parts[1]}' in '$value'. " +
                        "Known field types: ${Codecs.codecs.keys.sorted().joinToString(", ")}"
                )

            return PacketFieldDeclaration(parts[0], codec as DataCodec<Any>)
        }
    }

    object Codecs {
        val codecs = Object2ObjectOpenHashMap<String, DataCodec<*>>()

        init {
            codecs["string"] = StringCodec

            codecs["ubyte"] = UByteCodec
            codecs["ubyte128"] = UByte128Codec
            codecs["u128byte"] = U128ByteCodec
            codecs["ubytec"] = UByteCCodec

            codecs["sbyte"] = SByteCodec
            codecs["sbyte128"] = SByte128Codec
            codecs["s128byte"] = S128ByteCodec
            codecs["sbytec"] = SByteCCodec

            codecs["ushort"] = UShortCodec
            codecs["ushort128"] = UShort128Codec
            codecs["ushortle"] = UShortLECodec
            codecs["ushortle128"] = UShortLE128Codec

            codecs["umedium"] = UMediumCodec
            codecs["umediumle"] = UMediumLECodec
 //: two MIXED-ORDER mediums the 949 client writes in its "use
            // selection on X" packets (notes/PROTOCOL-MECHANISM-CA-clientprot-fixed.md):
            //   umediumx1 = bytes (v>>16, v, v>>8)   - IF_BUTTONT 15, OPOBJT 49
            //   umediumx2 = bytes (v>>8, v>>16, v)   - IF_BUTTOND 25
            // Named literally after their byte order; neither is a published codec name.
            codecs["umediumx1"] = UMediumX1Codec
            codecs["umediumx2"] = UMediumX2Codec

            codecs["int"] = IntCodec
            codecs["intle"] = IntLECodec
            codecs["intv1"] = IntV1Codec
            codecs["intv2"] = IntV2Codec
        }

        object StringCodec : DataCodec<String> {
            override fun read(buffer: GamePacketReader): String = buffer.getString()
            override fun write(buffer: GamePacketBuilder, value: String) = buffer.putString(value)
        }

        object UByteCodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int = buffer.getUnsigned(DataType.BYTE).toInt()
            override fun write(buffer: GamePacketBuilder, value: Int) = buffer.put(DataType.BYTE, value)
        }

        object UByte128Codec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.BYTE, DataTransformation.ADD).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.BYTE, DataTransformation.ADD, value)
        }

        object U128ByteCodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.BYTE, DataTransformation.SUBTRACT).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.BYTE, DataTransformation.SUBTRACT, value)
        }

        object UByteCCodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.BYTE, DataTransformation.NEGATE).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.BYTE, DataTransformation.NEGATE, value)
        }

        object SByteCodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int = buffer.getSigned(DataType.BYTE).toInt()
            override fun write(buffer: GamePacketBuilder, value: Int) = buffer.put(DataType.BYTE, value)
        }

        object SByte128Codec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getSigned(DataType.BYTE, DataTransformation.ADD).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.BYTE, DataTransformation.ADD, value)
        }

        object S128ByteCodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getSigned(DataType.BYTE, DataTransformation.SUBTRACT).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.BYTE, DataTransformation.SUBTRACT, value)
        }

        object SByteCCodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getSigned(DataType.BYTE, DataTransformation.NEGATE).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.BYTE, DataTransformation.NEGATE, value)
        }

        object UShortCodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int = buffer.getUnsigned(DataType.SHORT).toInt()
            override fun write(buffer: GamePacketBuilder, value: Int) = buffer.put(DataType.SHORT, value)
        }

        object UShort128Codec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.SHORT, DataTransformation.ADD).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.SHORT, DataTransformation.ADD, value)
        }

        object UShortLECodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.SHORT, DataOrder.LITTLE).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.SHORT, DataOrder.LITTLE, value)
        }

        object UShortLE128Codec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.SHORT, DataOrder.LITTLE, DataTransformation.ADD).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.SHORT, DataOrder.LITTLE, DataTransformation.ADD, value)
        }

        object UMediumCodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int = buffer.getUnsigned(DataType.MEDIUM).toInt()
            override fun write(buffer: GamePacketBuilder, value: Int) = buffer.put(DataType.MEDIUM, value)
        }

        object UMediumLECodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.MEDIUM, DataOrder.LITTLE).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.MEDIUM, DataOrder.LITTLE, value)
        }

        /** Mixed-order medium: wire bytes are (v>>16, v, v>>8). See the codec table comment. */
        object UMediumX1Codec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int {
                val hi = buffer.getUnsigned(DataType.BYTE).toInt()
                val lo = buffer.getUnsigned(DataType.BYTE).toInt()
                val mid = buffer.getUnsigned(DataType.BYTE).toInt()
                return (hi shl 16) or (mid shl 8) or lo
            }

            override fun write(buffer: GamePacketBuilder, value: Int) {
                buffer.put(DataType.BYTE, (value shr 16) and 0xFF)
                buffer.put(DataType.BYTE, value and 0xFF)
                buffer.put(DataType.BYTE, (value shr 8) and 0xFF)
            }
        }

        /** Mixed-order medium: wire bytes are (v>>8, v>>16, v). See the codec table comment. */
        object UMediumX2Codec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int {
                val mid = buffer.getUnsigned(DataType.BYTE).toInt()
                val hi = buffer.getUnsigned(DataType.BYTE).toInt()
                val lo = buffer.getUnsigned(DataType.BYTE).toInt()
                return (hi shl 16) or (mid shl 8) or lo
            }

            override fun write(buffer: GamePacketBuilder, value: Int) {
                buffer.put(DataType.BYTE, (value shr 8) and 0xFF)
                buffer.put(DataType.BYTE, (value shr 16) and 0xFF)
                buffer.put(DataType.BYTE, value and 0xFF)
            }
        }

        object IntCodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int = buffer.getUnsigned(DataType.INT).toInt()
            override fun write(buffer: GamePacketBuilder, value: Int) = buffer.put(DataType.INT, value)
        }

        object IntLECodec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.INT, DataOrder.LITTLE).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.INT, DataOrder.LITTLE, value)
        }

        object IntV1Codec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.INT, DataOrder.MIDDLE).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.INT, DataOrder.MIDDLE, value)
        }

        object IntV2Codec : DataCodec<Int> {
            override fun read(buffer: GamePacketReader): Int =
                buffer.getUnsigned(DataType.INT, DataOrder.INVERSED_MIDDLE).toInt()

            override fun write(buffer: GamePacketBuilder, value: Int) =
                buffer.put(DataType.INT, DataOrder.INVERSED_MIDDLE, value)
        }
    }
}