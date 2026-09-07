package com.opennxt.net.login

import com.opennxt.OpenNXT
import com.opennxt.login.LoginThread
import com.opennxt.model.account.AccountStore
import com.opennxt.model.account.PlayerSave
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.lobby.LobbyPlayer
import com.opennxt.model.lobby.DefaultVariables
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.GenericResponse
import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.game.pipeline.DynamicPacketHandler
import com.opennxt.net.game.pipeline.GamePacketEncoder
import com.opennxt.net.game.pipeline.GamePacketFraming
import com.opennxt.util.ISAACCipher
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import mu.KotlinLogging

class LoginServerHandler : SimpleChannelInboundHandler<LoginPacket>() {
    private val logger = KotlinLogging.logger { }

    companion object {
        private val swapLogger = KotlinLogging.logger { }

        /**
         * The login->game pipeline swap, GUARDED, extracted so the guard
         * exists exactly once and every branch that swaps uses it:
         *
         * - the LOBBY branch below (from the LobbyLoginResponse write listener),
         * - the GAME hand-off in [com.opennxt.model.world.WorldPlayer.added]
         * (after the GameLoginResponse - carrying the tick-assigned player
         * index - is on the wire).
         *
         * `internal` rather than `private` for that second caller: the game
         * response cannot be sent before the world tick assigns the player's
         * entity index, so the swap it precedes cannot live in this class's
         * private scope without inventing an index-0 response.
         *
         * @return true if the pipeline is now the game pipeline; false if the
         */
        internal fun swapToGamePipeline(channel: Channel, username: String): Boolean {
            if (!channel.isActive) {
                swapLogger.warn { "Client $username disconnected before the game pipeline swap; not adding player" }
                return false
            }
            // The three replaces have to happen AS ONE STEP ON THE EVENT LOOP.
            //
            // On the LOBBY path they already did: that caller is a writeAndFlush
            // listener, which Netty runs on the channel's event loop, so no
            // inbound read can interleave. On the GAME path they did not.
            // WorldPlayer.added() runs on the TICK THREAD - the code says so at
            // WorldPlayer.kt:154 - and DefaultChannelPipeline.replace called from
            // off the event loop performs the list surgery synchronously while
            // only SCHEDULING the handlerAdded/handlerRemoved callbacks. Between
            // replace #1 and replace #3 the pipeline is
            // [game-decoder] [login-handler] [login-encoder], and the event loop
            // is free to process a read in that window.
            //
            // An inbound game frame arriving there is decoded by the newly
            // installed framer and handed to LoginServerHandler, whose
            // SimpleChannelInboundHandler.acceptInboundMessage rejects
            // OpcodeWithBuffer and forwards it to the tail - where
            // ReferenceCountUtil.release is a no-op, because OpcodeWithBuffer is
            // a plain data class and not ReferenceCounted. The packet is dropped
            // with no log at all and its POOLED buffer is leaked. The window is
            // microseconds wide; the reason it is worth closing is that nothing
            // would ever tell us it had happened.
            //
            // inEventLoop() is tested rather than always submitting because the
            // lobby path calls this FROM the event loop, where submit-then-await
            // would deadlock the thread against itself.
            val loop = channel.eventLoop()
            if (loop.inEventLoop()) return doSwap(channel, username)

            return try {
                loop.submit<Boolean> { doSwap(channel, username) }
                    .get(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                swapLogger.warn(e) { "Pipeline swap for $username did not complete on the event loop; not adding player" }
                false
            }
        }

        private fun doSwap(channel: Channel, username: String): Boolean {
            try {
                channel.pipeline().replace("login-decoder", "game-decoder", GamePacketFraming())
                channel.pipeline().replace("login-encoder", "game-encoder", GamePacketEncoder())
                channel.pipeline().replace("login-handler", "game-handler", DynamicPacketHandler())
            } catch (e: NoSuchElementException) {
                swapLogger.warn { "Login pipeline for $username was already torn down (client raced a disconnect); not adding player" }
                return false
            }
            return true
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: LoginPacket) {
        when {
            msg is LoginPacket.LobbyLoginRequest || msg is LoginPacket.GameLoginRequest -> handleLoginRequest(ctx, msg)
            msg is LoginPacket.GameLoginContinue -> handleGameLoginContinue(ctx)
            else -> throw IllegalStateException("idk how to handle $msg!")
        }
    }

    private fun handleLoginRequest(ctx: ChannelHandlerContext, msg: LoginPacket) {
        val header = if (msg is LoginPacket.LobbyLoginRequest) msg.header
        else (msg as LoginPacket.GameLoginRequest).header

        ctx.channel().attr(RSChannelAttributes.INCOMING_ISAAC).set(ISAACCipher(header.seeds))
        ctx.channel().attr(RSChannelAttributes.OUTGOING_ISAAC)
            .set(ISAACCipher(header.seeds.map { it + 50 }.toIntArray()))

        LoginThread.login(msg, ctx.channel()) {
            // THE ONLY place these are ever set, and set BEFORE the success byte is
            // flushed, so no ordering of client replies can find the byte on the
            // wire and the flag unset. The decoder's GAMELOGIN_CONTINUE gate and
 // handleGameLoginContinue both require them , CODE-REVIEW-FULL
            if (it.result.code == GenericResponse.SUCCESSFUL) {
                ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).set(it.username)
                ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).set(true)
            }
            val future = ctx.channel().writeAndFlush(Unpooled.buffer(1).writeByte(it.result.code.id))
            if (it.result.code != GenericResponse.SUCCESSFUL) {
                future.addListener(ChannelFutureListener.CLOSE)
                return@login
            }

            if (ctx.channel().attr(RSChannelAttributes.PASSTHROUGH_CHANNEL).get() != null) {
                logger.info { "Login was OK for proxy connection [client->open nxt], leaving channel management to proxy..." }
            } else {
                if (ctx.channel().attr(RSChannelAttributes.LOGIN_TYPE).get() == LoginType.GAME) {
                    // Send the POPULATED serverperm varc map. (This map used to
                    // be built and then DISCARDED - an empty map was sent in
                    // its place, so the client never received the ~220 values.)
                    val map = Int2IntOpenHashMap()
                    DefaultVariables.populateServerpermVarcs(map)
                    val response = LoginPacket.ServerpermVarcChunk(true, map)
                    ctx.channel().writeAndFlush(response)

                    logger.info { "Sending ${map.size} serverperm varcs" }

                    // Deliberately NO pipeline swap here: the client answers
                    // this chunk with the bare GAMELOGIN_CONTINUE opcode (26),
                    // which must still arrive through the login decoder. The
                    // guarded swap runs in WorldPlayer.added(), right after
                    // the GameLoginResponse is on the wire.
                    return@login
                }

                val response = LoginPacket.LobbyLoginResponse.forAccount(it.username, OpenNXT.config)
                // Full field list on every send, so bisecting field meanings
                // against a real client has something to read.
                logger.debug { "Sending lobby login response: $response" }

                ctx.channel().writeAndFlush(response).addListener { future ->
                    if (!future.isSuccess) {
                        logger.error(future.cause()) { "Failed to write login response" }
                        ctx.channel().close()
                        return@addListener
                    }

                    if (!swapToGamePipeline(ctx.channel(), it.username)) return@addListener

                    val player = LobbyPlayer(ctx.channel().attr(RSChannelAttributes.CONNECTED_CLIENT).get(), it.username)

                    OpenNXT.lobby.addPlayer(player)
                }

                logger.info { "Login on [SERVER] is completed. Should add this to a map somewhere to handle" }
            }
        }
    }

    /**
     * GAMELOGIN_CONTINUE: the world hand-off. The WorldPlayer is constructed
     * HERE (it used to be built inside LoginServerDecoder's decode loop) from
     * the account's persisted [PlayerSave]:
     *
     *  - position: the save's (x, y, plane); a fresh account gets
     *    [PlayerSave.fromNew]'s spawn (3222, 3222, 0),
     *  - stats: the save's per-stat xp, levels derived through the verified
     *    curve by [com.opennxt.impl.stat.PlayerStatContainer].
     *
     * The GameLoginResponse is sent from ONE place - [WorldPlayer.added] on
     * the world tick, once the entity index it carries has been assigned -
     * followed there by the shared guarded pipeline swap ([swapToGamePipeline]).
     */
    private fun handleGameLoginContinue(ctx: ChannelHandlerContext) {
 // Defence in depth behind the decoder's gate , CODE-REVIEW-FULL
        // #1): nothing below may run for a channel whose credential check has not
        // SUCCEEDED, whatever route delivered the packet object.
        val username = ctx.channel().attr(RSChannelAttributes.LOGIN_USERNAME).get()
        val authedName = ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED_USERNAME).get()
        if (ctx.channel().attr(RSChannelAttributes.LOGIN_AUTHENTICATED).get() != true ||
            ctx.channel().attr(RSChannelAttributes.LOGIN_TYPE).get() != LoginType.GAME ||
            username == null || authedName == null || authedName != username
        ) {
            logger.error {
                "GAMELOGIN_CONTINUE reached the handler on a channel that is not authenticated for '$username' " +
                    "(authenticated as '$authedName') - closing"
            }
            ctx.channel().close()
            return
        }

        // ONE WORLD SESSION PER ACCOUNT, claimed BEFORE anything is built.
        //
        // The order is the whole point. `WorldPlayer`'s constructor calls
        // Banks.restoreBank, which WHOLESALE-REPLACES the shared per-account
        // bank with what the database last stored - so by the time a duplicate
        // session could be noticed after construction, the first session's
        // unsaved deposits are already gone. The claim is atomic
        // (ConcurrentHashMap.putIfAbsent), so two GAMELOGIN_CONTINUEs racing on
        // two netty threads cannot both win it.
        //
        // See World.reserveSession for the measurement this exists for, and
        val world = OpenNXT.world
        if (!world.reserveSession(username)) {
            logger.warn {
                "REFUSED GAMELOGIN_CONTINUE for '$username': that account already holds a world session. " +
                    "Admitting it would hand both sessions the same bank and lose the live one's deposits. " +
                    "Answering ${GenericResponse.LOGGED_IN} (${GenericResponse.LOGGED_IN.id}) and closing."
            }
            ctx.channel().writeAndFlush(Unpooled.buffer(1).writeByte(GenericResponse.LOGGED_IN.id))
                .addListener(ChannelFutureListener.CLOSE)
            return
        }

        val player = try {
            val save = AccountStore.instance.loadSave(username) ?: PlayerSave.fromNew(username)
            WorldPlayer(
                ctx.channel().attr(RSChannelAttributes.CONNECTED_CLIENT).get(),
                username,
                PlayerEntity(TileLocation(save.x, save.y, save.plane)),
                save.xp
            )
        } catch (t: Throwable) {
            // A claim that never becomes a player would lock the account out
            // for the life of the process, because only the cull releases it
            // and there is nothing to cull.
            world.releaseSession(username)
            logger.error(t) { "Failed to build the world player for '$username'; released the session claim" }
            ctx.channel().close()
            return
        }

        if (!world.addPlayer(player)) {
            world.releaseSession(username)
            ctx.channel().close()
        }
    }
}
