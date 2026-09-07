package com.opennxt.login

import com.opennxt.Constants
import com.opennxt.OpenNXT
import com.opennxt.model.account.AccountStore
import com.opennxt.model.proxy.PacketDumper
import com.opennxt.net.RSChannelAttributes
import com.opennxt.net.login.LoginPacket
import com.opennxt.net.proxy.ConnectedProxyClient
import com.opennxt.net.proxy.ProxyChannelAttributes
import com.opennxt.net.proxy.ProxyPlayer
import io.netty.channel.Channel
import mu.KotlinLogging
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

// TODO This should probably be re-done entirely.
object LoginThread : Thread("login-thread") {
    private val logger = KotlinLogging.logger { }

    val queue = LinkedBlockingQueue<LoginContext>()
    val running = AtomicBoolean(true)

    override fun run() {
        while (running.get()) {
            try {
                val next = queue.take()

                process(next)
            } catch (e: Exception) {
                logger.error(e) { "Uncaught exception occurred handling login request" }
            }
        }
    }

    private fun process(context: LoginContext) {
        if (OpenNXT.enableProxySupport && OpenNXT.proxyConfig.usernames.contains(context.username.toLowerCase())) {
            OpenNXT.proxyConnectionFactory.createLogin(context.packet) { channel, result ->
                if (channel != null) {
                    val now = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(LocalDateTime.now()).replace(':', '-')
                    val type = if (context.packet is LoginPacket.LobbyLoginRequest) "lobby" else "game"
 // HARDENED: the packet-dump folder used to be
                    // named "$now-$type-$username", writing the account name onto
                    // disk. The observation does not need it, and an alt account's name
                    // has no business sitting in a directory name. Use a short,
                    // non-reversible tag of the name so separate accounts still land
                    // in separate folders without recording who they are.
                    val name = Integer.toHexString(context.username.lowercase().hashCode())

                    val clientSide = ConnectedProxyClient(
                        context.channel.attr(RSChannelAttributes.CONNECTED_CLIENT).get(),
                        PacketDumper(com.opennxt.Constants.PROXY_DUMP_PATH.resolve("$now-$type-$name").resolve("clientprot.bin"))
                    )

                    val serverSide = ConnectedProxyClient(
                        channel.attr(RSChannelAttributes.CONNECTED_CLIENT).get(),
                        PacketDumper(com.opennxt.Constants.PROXY_DUMP_PATH.resolve("$now-$type-$name").resolve("serverprot.bin"))
                    )

                    val player = ProxyPlayer(clientSide)

                    context.channel.attr(ProxyChannelAttributes.PROXY_PLAYER).set(player)
                    channel.attr(ProxyChannelAttributes.PROXY_PLAYER).set(player)

                    clientSide.connection.processUnidentifiedPackets = true
                    serverSide.connection.processUnidentifiedPackets = true

                    clientSide.other = serverSide
                    serverSide.other = clientSide

                    context.channel.attr(ProxyChannelAttributes.PROXY_CLIENT).set(clientSide)
                    channel.attr(ProxyChannelAttributes.PROXY_CLIENT).set(serverSide)

                    context.channel.attr(RSChannelAttributes.PASSTHROUGH_CHANNEL).set(channel)
                    channel.attr(RSChannelAttributes.PASSTHROUGH_CHANNEL).set(context.channel)

                    OpenNXT.proxyConnectionHandler.registerProxyConnection(clientSide, serverSide)
                }

                context.result = result
                context.callback(context)
            }
        } else {
            // Credential check against the account database. Auto-registration on
            // first login (dev mode) is inside authenticate(), logged and flagged
            // there; a wrong password maps to the invalid-credentials response.
            val auth = AccountStore.instance.authenticate(context.username, context.password)
            context.result = if (auth.success) LoginResult.SUCCESS else LoginResult.INVALID_USERNAME_PASS
            context.callback(context)
        }
    }

    fun login(packet: LoginPacket, channel: Channel, callback: (LoginContext) -> Unit) {
        when (packet) {
            is LoginPacket.LobbyLoginRequest -> {
                queue.add(
                    LoginContext(
                        packet,
                        callback,
                        packet.build,
                        packet.username,
                        packet.password,
                        channel = channel
                    )
                )
            }
            is LoginPacket.GameLoginRequest -> {
                queue.add(
                    LoginContext(
                        packet,
                        callback,
                        packet.build,
                        packet.username,
                        packet.password,
                        channel = channel
                    )
                )
            }
            else -> throw IllegalArgumentException("expected LobbyLoginRequest or GameLoginRequest, got $packet")
        }
    }
}