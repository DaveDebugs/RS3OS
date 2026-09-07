package com.opennxt.net.game.pipeline

import com.opennxt.OpenNXT
import com.opennxt.ext.writeOpcode
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.Side
import com.opennxt.util.ISAACCipher
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder
import it.unimi.dsi.fastutil.ints.Int2IntMap
import mu.KotlinLogging

class GamePacketEncoder : MessageToByteEncoder<OpcodeWithBuffer>() {

    private val logger = KotlinLogging.logger { }

    private val protocol = OpenNXT.protocol

    private var inited = false

    private lateinit var isaac: ISAACCipher
    private lateinit var mapping: Int2IntMap
    private lateinit var side: Side
    private lateinit var names: it.unimi.dsi.fastutil.ints.Int2ObjectMap<String>

    private fun init(channel: Channel) {
        inited = true

        isaac = channel.attr(RSChannelAttributes.OUTGOING_ISAAC).get()
        side = channel.attr(RSChannelAttributes.SIDE).get()
        mapping = if (side == Side.CLIENT) protocol.serverProtSizes.values else protocol.clientProtSizes.values
        names = if (side == Side.CLIENT) protocol.serverProtNames.reversedValues()
                else protocol.clientProtNames.reversedValues()

        // Say out loud that the experiment is armed, and which names resolved to
        // opcodes. Without this a black screen is ambiguous between
        // "suppression happened and did not help" and "the flag never took",
        // and those call for opposite next steps. Names that do NOT resolve are
        // reported as errors, because a typo silently suppressing nothing would
        // read as a real negative result.
        //
        // Wrapped in its own catch: init() runs inside encode()'s try, whose
        // catch closes the channel. A bug in a log line has no business killing
        // the connection it was added to observe.
        try {
            if (suppressed.isNotEmpty() && side == Side.CLIENT) {
                val table = protocol.serverProtNames.values
                val resolved = suppressed.filter { table.containsKey(it) }.map { "$it=${table.getInt(it)}" }
                val unresolved = suppressed.filter { !table.containsKey(it) }
                logger.warn {
                    "EXPERIMENT ARMED: dropping ${resolved.size} packet type(s) before encode: " +
                        resolved.joinToString(", ")
                }
                if (unresolved.isNotEmpty())
                    logger.error {
                        "EXPERIMENT: not in build ${protocol.effectiveBuild}'s table, so these suppress NOTHING: " +
                            unresolved.joinToString(", ")
                    }
            }
        } catch (t: Throwable) {
            logger.warn(t) { "EXPERIMENT: could not report the armed suppression set (harmless)" }
        }
    }

    /**
     * OPT-IN EXPERIMENT: drop whole packets by name before they are encoded.
     *
     *     -Dopennxt.experiment.suppress=VARP_SMALL,VARP_LARGE,UPDATE_STAT
     *
     * Why it exists. A 949 client logs in, receives 1,435 packets - 1,413 of
     * them variable state, then IF_OPENTOP and 21 IF_OPENSUB - and draws a black
     * screen. Two explanations survive everything measured so far: the interface
     * opcodes mean something else in 949 (the tables in use are borrowed from
     * 947), or they are correct and the client's variable state is garbage,
     * because 1,385 varps went to whatever subsystem 949 maps opcodes 10 and 111
     * to. Sending the interfaces WITHOUT the variable state separates them.
     *
     * Read the outcome asymmetrically, which is the whole reason this comment is
     * here:
     *
     *   lobby RENDERS  -> strong. The interface opcodes are right, and variable
     *                     state was blanking it.
     *   lobby is BLACK -> weak. Consistent with wrong opcodes, but equally
     *                     consistent with "interfaces alone are not enough to
     *                     draw a lobby". It does not license a conclusion.
     *
     * Dropping is safe for framing: a packet the client never sees cannot
     * desynchronise it, and the ISAAC opcode stream advances only for packets
     * actually written, so the keystream stays in step.
     */
    private val suppressed: Set<String> =
        (System.getProperty("opennxt.experiment.suppress") ?: "")
            .split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    private var suppressedCount = 0

    override fun encode(ctx: ChannelHandlerContext, msg: OpcodeWithBuffer, out: ByteBuf) {
        // Ownership of msg.buf ends inside this method, on EVERY path.
        //
        // It used to end on exactly two of them (success, and suppression).
        // MessageToByteEncoder.write does call ReferenceCountUtil.release(cast)
        // in a finally, but `cast` here is an OpcodeWithBuffer - a plain Kotlin
        // data class, not ReferenceCounted - so that release is a no-op, and the
        // three error paths below (unmapped opcode, size assertion, catch-all)
        // dropped the buffer on the floor.
        //
        // Harmless in non-proxy mode, where everything reaching this encoder is
        // Unpooled heap from ConnectedClient.write or PlayerInfoEncoder and the
        // GC reclaims it. NOT harmless under --enable-proxy-support:
        // ConnectedClient.receive relays `pair.buf.copy()` of a POOLED buffer
        // that came off the socket, so every occurrence lost pooled memory that
        // never returned to the arena. The trigger is mundane - one wrong entry
        // in serverProtSizes.toml makes the size assertion throw on relay.
        //
        // One release site now, in a finally, guarded on refCnt so it stays
        // correct if some future path releases early.
        val buffer = msg.buf
        try {
            if (!inited) init(ctx.channel())

            if (suppressed.isNotEmpty()) {
                val name = names[msg.opcode]
                if (name != null && name.uppercase() in suppressed) {
                    suppressedCount++
                    if (suppressedCount <= 3 || suppressedCount % 250 == 0)
                        logger.warn { "EXPERIMENT: suppressing $name (opcode ${msg.opcode}), $suppressedCount so far" }
                    return
                }
            }

            if (!mapping.containsKey(msg.opcode)) {
                logger.error { "No opcode->size mapping for opcode ${msg.opcode} (side=$side)" }
                ctx.channel().close()
                return
            }

            // `readableBytes()`, not `writerIndex()`.
            //
            // The length prefix has to describe what `out.writeBytes(buffer)`
            // below actually copies, and that is readableBytes. The two are
            // identical for every producer in this repository today (readerIndex is
            // always 0), so nothing changes on the wire now - but they are
            // different quantities, and the day a producer reads from its own
            // buffer before handing it over, a writerIndex prefix would announce
            // a length that does not match the body and desynchronise the
            // client's framing with no error at either end.
            val length = buffer.readableBytes()
            val size = mapping[msg.opcode]

            // These read `>= 255` and `>= 65535`. A var-byte body of exactly 255
            // bytes and a var-short body of exactly 65535 bytes are both
            // representable - writeByte(255) / writeShort(65535) put the right
            // value on the wire and the client reads them unsigned - so a legal
            // maximum-length packet threw here, was caught below, and CLOSED THE
            // CHANNEL. It errs safe rather than corrupting, which is the only
            // reason it survived this long.
            if (size == -1 && length > 255)
                throw IllegalStateException("Var byte packet exceeds 255 bytes: ${msg.opcode} is $length bytes")
            else if (size == -2 && length > 65535)
                throw IllegalStateException("Var short packet exceeds 65535 bytes: ${msg.opcode} is $length bytes")
            else if (size >= 0 && size != length)
                throw IllegalStateException("Encoded buffer size does not match expected size (expected: ${size}, got $length) opcode ${msg.opcode}")

            // Report what the SERVER believes it is sending, by name, before
            // the opcode is enciphered.
            //
            // The socket-level tap cannot recover this. `writeOpcode` runs the
            // opcode through the outgoing ISAAC stream, so on the wire the first
            // byte of every packet is indistinguishable from noise - one run
            // shows writes like `b4 21 00 00 00 00 00 81` where `b4` is an
            // enciphered opcode, not data. Reversing that from the outside means
            // reproducing the keystream; from in here the opcode is simply still
            // in the clear.
            //
            // The name comes from the table actually in use, which may be
            // BORROWED from another build - so this says which build it read.
            // That is the point: it makes "the server sent X as opcode N,
            // according to build B's table" a measured statement, and whether
            // build 949 agrees about N is then a separate, answerable question.
            //
            // The NAME is passed in rather than looked up inside DiagnosticLog.
            // That lookup was `names.reversedValues()[opcode]`, and
            // Name2OpcodeConfig.reversedValues() BUILDS A NEW
            // Int2ObjectOpenHashMap from the whole name table on every call - so
            // it ran once per outbound packet, on the event loop, ~1,435 times
            // in the ~87 ms login burst. `names` here is the same reversed map,
            // already built once in init().
            // along so the diag line can name the INTERFACE a component packet
            // addresses. Our login sends ~2,600 IF_SETEVENTS to the reference client's ~1,000
            // and, with opcode and length alone, nothing could say for which
            // interfaces. Read with getBytes at readerIndex: the buffer is not
            // advanced, and the bytes copied to the wire below are unchanged.
            // Allocated only when the diag log will read it: recording ON for
            // this channel, and a packet whose first field is a component. With
            // diagnostics off this is one boolean per packet, no allocation.
            val name = names[msg.opcode]
            val head = if (length >= 4 && DiagnosticLog.wantsHead(ctx.channel(), name))
                ByteArray(4).also { buffer.getBytes(buffer.readerIndex(), it) } else null
            DiagnosticLog.sendingPacket(ctx.channel(), side, msg.opcode, length, size, name, head)

            synchronized(isaac) {
                out.writeOpcode(isaac, msg.opcode)
                if (size == -1) out.writeByte(length)
                else if (size == -2) out.writeShort(length)
                out.writeBytes(buffer)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to encode opcode ${msg.opcode} on side $side to ${ctx.channel().remoteAddress()}" }
            ctx.channel().close()
        } finally {
            if (buffer.refCnt() > 0) buffer.release()
        }
    }
}