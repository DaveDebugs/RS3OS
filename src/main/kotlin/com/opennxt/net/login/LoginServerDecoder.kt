package com.opennxt.net.login

import com.opennxt.config.RsaConfig
import com.opennxt.ext.decipherXtea
import com.opennxt.ext.readBuild
import com.opennxt.ext.readString
import com.opennxt.net.DiagnosticLog
import com.opennxt.net.GenericResponse
import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.login.LoginRSAHeader.Companion.readLoginHeader
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import mu.KotlinLogging
import kotlin.system.exitProcess

class LoginServerDecoder(val rsaPair: RsaConfig.RsaKeyPair) : ByteToMessageDecoder() {
    private val logger = KotlinLogging.logger { }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        buf.markReaderIndex()

        val id = buf.readUnsignedByte().toInt()
        val type = LoginType.fromId(id)
        if (type == null) {
            logger.warn("Client from ${ctx.channel().remoteAddress()} attempted to login with unknown id: $id")
            // Diagnostic only (opt-in): the rejection below is unchanged; this dumps
            // what was rejected. An unhandled login type is exactly the shape a 2026
            // client's divergence would take.
            if (DiagnosticLog.enabled) {
                DiagnosticLog.bytes(
                    ctx.channel(), DiagnosticLog.Stage.LOGIN,
                    "UNKNOWN LOGIN TYPE $id (0x${Integer.toHexString(id)}) - closing; raw frame from the type byte",
                    byteArrayOf(id.toByte()) + DiagnosticLog.snapshot(buf, DiagnosticLog.DUMP_LIMIT - 1),
                    buf.readableBytes() + 1
                )
                DiagnosticLog.reason(ctx.channel(), "unknown login type $id")
            }
            buf.skipBytes(buf.readableBytes())
            ctx.close()
            return
        }

        if (type == LoginType.GAMELOGIN_CONTINUE) {
 // (CODE-REVIEW-FULL #1, CRITICAL): the gate used to be
            // `LOGIN_TYPE != null`, and LOGIN_TYPE is set below the moment a login
            // frame is FRAMED - before it is parsed, before LoginThread has run the
            // password check. A client that pipelined a wrong-password GAME login
            // and byte 26 in one segment reached handleGameLoginContinue
            // unauthenticated: the account's world session claimed, its save
            // loaded, its shared bank replaced. The gate is now the credential
            // check's own answer: LOGIN_AUTHENTICATED is set only in the SUCCESS
            // branch of the login callback, and only a GAME login may continue.
            val loginType = ctx.channel().attr(RSChannelAttributes.LOGIN_TYPE).get()
            val authenticated = ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).get() == true
            val authedName = ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).get()
            val framedName = ctx.channel().attr(RSChannelAttributes.LOGIN_USERNAME).get()
            val sameName = authedName != null && authedName == framedName
            if (loginType != LoginType.GAME || !authenticated || !sameName) {
                logger.error {
                    "REFUSED GAMELOGIN_CONTINUE from ${ctx.channel().remoteAddress()}: " +
                        (if (loginType == null) "no login frame seen"
                        else if (loginType != LoginType.GAME) "login type is $loginType, not GAME"
                        else if (!authenticated) "the credential check has not SUCCEEDED on this channel"
                        else "the framed username is not the one that authenticated") +
                        " - closing"
                }
                buf.skipBytes(buf.readableBytes())
                ctx.channel().close()
                return
            }

            if (ctx.channel().attr(RSChannelAttributes.PASSTHROUGH_CHANNEL).get() == null) {
                // A decoder decodes; it does not construct players. The
                // WorldPlayer construction (position + stats from the
                // persisted save) and the game login response live in
                // LoginServerHandler.handleGameLoginContinue.
                out.add(LoginPacket.GameLoginContinue)
            }
            return
        }

        if (buf.readableBytes() < 2) {
            buf.resetReaderIndex()
            return
        }

        val length = buf.readUnsignedShort()
        if (buf.readableBytes() < length) {
            buf.resetReaderIndex()
            return
        }

        // A login frame on a channel whose credential check already SUCCEEDED is
        // refused outright, and any login frame CLEARS the authenticated state
        // with the flag never cleared, "log in as yourself, frame a second login
        // naming the victim (LOGIN_USERNAME is rewritten below at framing time),
        // send 26" passed both gates. One channel, one login.
        if (ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).get() == true) {
            logger.error { "REFUSED a second login frame on an already-authenticated channel from ${ctx.channel().remoteAddress()} - closing" }
            // Cleared BEFORE the close, so nothing racing the close on this
            // channel can still find it authenticated.
            ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).set(false)
            ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).set(null)
            buf.skipBytes(buf.readableBytes())
            ctx.channel().close()
            return
        }
        ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).set(false)
        ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).set(null)
        ctx.channel().attr(RSChannelAttributes.LOGIN_TYPE).set(type)

        val payload = buf.readBytes(length)

        // Diagnostic only (opt-in): the RAW frame, recorded BEFORE anything parses
        // or deciphers it in place, so a failure below can dump what actually
        // arrived instead of only saying that it threw. Absolute getters; the
        // payload's reader index is untouched. Nothing here is allocated when the
        // recorder is off.
        val diagRaw: ByteArray? = if (DiagnosticLog.enabled) DiagnosticLog.snapshot(payload) else null
        if (diagRaw != null) {
            DiagnosticLog.bytes(
                ctx.channel(), DiagnosticLog.Stage.LOGIN,
                "LOGIN FRAME type=$type typeByte=$id declaredLength=$length rsaKeyBits=${rsaPair.modulus.bitLength()}",
                diagRaw, length
            )
        }

        try {
            val build = payload.readBuild()
            if (diagRaw != null) {
                DiagnosticLog.buildAnnounced(
                    ctx.channel(), DiagnosticLog.Stage.LOGIN, build.major,
                    "login frame type=$type, minor=${build.minor}"
                )
            }
            val header = payload.readLoginHeader(type, rsaPair.exponent, rsaPair.modulus)

            // THIS GUARD USED TO REJECT EVERY RECONNECT THERE CAN BE.
            //
            // It read as "a reconnecting block outside the lobby is
            // impossible", but readLoginHeader begins with
            //
            //     val reconnecting = type == LoginType.GAME && readUnsignedByte() == 1
            //
            // so a Reconnecting header is only ever produced for a GAME login -
            // it is impossible in the LOBBY, which is the exact opposite of what
            // the comment claimed. The condition therefore matched 100% of
            // reconnects and rejected them with MALFORMED_PACKET, which is also
            // the wrong reason code: the packet was fine, we just refused it.
            //
            // Observed live (diag-20260816-105747): the 949 client logs into the
            // world, holds the session about a second, closes CLEANLY, and comes
            // straight back with a reconnecting GAME login - twice in one run,
            // rejected both times in ~10ms. Reconnecting is normal client
            // behaviour, not a malformed frame.
            //
            // Left as an assertion of the real invariant so it still fires if
            // that ever stops being true.
            if (header !is LoginRSAHeader.Fresh && type == LoginType.LOBBY) {
                logger.error { "reconnecting block on a LOBBY login - readLoginHeader should make this unreachable" }
                if (diagRaw != null) {
                    DiagnosticLog.bytes(
                        ctx.channel(), DiagnosticLog.Stage.LOGIN,
                        "LOGIN REJECTED (MALFORMED_PACKET): reconnecting block on a LOBBY login; raw frame",
                        diagRaw, length
                    )
                    DiagnosticLog.reason(ctx.channel(), "login rejected: reconnecting block on a lobby login")
                }
                ctx.channel()
                    .writeAndFlush(LoginPacket.LoginResponse(GenericResponse.MALFORMED_PACKET))
                    .addListener(ChannelFutureListener.CLOSE)
                return
            }

            payload.decipherXtea(header.seeds)

            payload.markReaderIndex()
            val original = ByteArray(payload.readableBytes())
            payload.readBytes(original)
            payload.resetReaderIndex()

            payload.skipBytes(1) // TODO This has to do with name being encoded as long, should prolly check it
            val name = payload.readString()

            ctx.channel().attr(RSChannelAttributes.LOGIN_USERNAME).set(name)
            if (header.uniqueId != ctx.channel().attr(RSChannelAttributes.LOGIN_UNIQUE_ID).get()) {
                logger.error { "Unique id mismatch - possible replay attack?" }
                if (diagRaw != null) {
                    DiagnosticLog.bytes(
                        ctx.channel(), DiagnosticLog.Stage.LOGIN,
                        "LOGIN REJECTED (MALFORMED_PACKET): unique id mismatch " +
                            "(block=${header.uniqueId}, issued=${ctx.channel().attr(RSChannelAttributes.LOGIN_UNIQUE_ID).get()}); raw frame",
                        diagRaw, length
                    )
                    DiagnosticLog.reason(ctx.channel(), "login rejected: unique id mismatch")
                }
                ctx.channel()
                    .writeAndFlush(LoginPacket.LoginResponse(GenericResponse.MALFORMED_PACKET))
                    .addListener(ChannelFutureListener.CLOSE)
                return
            }

            when (type) {
                LoginType.LOBBY -> {
                    header as LoginRSAHeader.Fresh

                    logger.info { "Attempted lobby login: $name, *****" }
                    out.add(
                        LoginPacket.LobbyLoginRequest(
                            build,
                            header,
                            name,
                            header.password,
                            Unpooled.wrappedBuffer(original)
                        )
                    )
                }
                LoginType.GAME -> {
                    // A reconnect is now ACCEPTED and treated as a fresh game
                    // login. It is not a true session resume - we do not restore
                    // the previous session's state, we build a new one - but
                    // this server has no durable world state to resume anyway,
                    // so re-running the world init is the honest equivalent.
                    //
                    // GameLoginRequest.header is typed as the sealed parent
                    // LoginRSAHeader, not Fresh, so this is type-safe. The two
                    // `as Fresh` casts in the tree (LoginEncoder,
                    // LoginClientHandler) are both on the OUTBOUND/proxy path
                    // and are not reached from here.
                    //
                    // A Reconnecting block carries oldSeeds instead of a
                    // password - there is nothing to authenticate against and
                    // nothing to check it with, so the password is empty. Worth
                    // being explicit that this means a reconnect is currently
                    // UNAUTHENTICATED: fine for a loopback dev server, not fine
                    // if this is ever exposed.
                    val password = if (header is LoginRSAHeader.Fresh) header.password else ""
                    if (header !is LoginRSAHeader.Fresh)
                        logger.warn {
                            "Game login for $name is a RECONNECT - accepted as a fresh login " +
                                "(no session resume, no password check)"
                        }
                    else
                        logger.info { "Attempted game login: $name, *****" }

                    out.add(
                        LoginPacket.GameLoginRequest(
                            build,
                            header,
                            name,
                            password,
                            Unpooled.wrappedBuffer(original)
                        )
                    )
                }
                else -> throw IllegalStateException("Unhandled login type $type")
            }
        } catch (e: Exception) {
            // Was e.printStackTrace(). The hex dump below is opt-in
            // (-Dopennxt.diag=true), so on a default run this was the ONLY
            // record that a login had failed to parse - and it went to stderr
            // with no peer, no login type and no correlation to any other line
            // in the log.
            logger.error(e) {
                "Login frame from ${ctx.channel().remoteAddress()} could not be parsed - " +
                    "answering MALFORMED_PACKET and closing"
            }
            // Diagnostic only (opt-in): the MALFORMED_PACKET reply and the close
            // below are exactly what they were. This adds the one thing the log
            // never had - the bytes that could not be parsed. Everything the
            // failure could be (RSA magic wrong, XTEA garbage, an unhandled type,
            // a 2026 block layout) shows up here as its raw shape.
            if (diagRaw != null) {
                DiagnosticLog.bytes(
                    ctx.channel(), DiagnosticLog.Stage.LOGIN,
                    "LOGIN PARSE FAILURE ${e.javaClass.name}: ${e.message} - answering MALFORMED_PACKET; raw frame " +
                        "(type=$type typeByte=$id declaredLength=$length)",
                    diagRaw, length
                )
                DiagnosticLog.reason(ctx.channel(), "login parse failure: ${e.javaClass.simpleName}")
            }
            ctx.channel()
                .writeAndFlush(LoginPacket.LoginResponse(GenericResponse.MALFORMED_PACKET))
                .addListener(ChannelFutureListener.CLOSE)
        } finally {
            payload.release()
        }
    }
}