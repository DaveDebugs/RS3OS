package com.opennxt.net.http

import com.opennxt.config.ServerConfig
import com.opennxt.model.files.ClientParams
import com.opennxt.model.files.FileChecker
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import mu.KotlinLogging
import kotlin.system.exitProcess

class HttpServer(val config: ServerConfig) : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private var initialized = false

    private val handler = HttpRequestHandler()
    private val httpBootstrap = ServerBootstrap()
        .group(NioEventLoopGroup())
        .channel(NioServerSocketChannel::class.java)
        .childHandler(HttpChannelInitializer(handler))
        .childOption(ChannelOption.SO_REUSEADDR, true)
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)

    fun init(skipFileChecks: Boolean) {
        if (skipFileChecks) {
            logger.info { "Skipping http file verification" }
        } else {
            FileChecker.checkFiles("compressed")
        }
        // TODO Checksum table?

        initialized = true
    }

    fun bind(httpPort: Int = config.ports.http) {
        check(initialized) { "Attempted to bind http server before initializing" }

        logger.info { "Binding http server to 0.0.0.0:$httpPort" }

        // `await()`, not `sync()`. sync() RETHROWS the failure cause instead of
        // returning a failed future, so the isSuccess branch below was
        // unreachable and this message - the only one that names the address
        // that could not be bound - never printed; the operator got a bare
        // BindException stack trace instead. Behaviour is still fatal here, as
        // intended; it now says what failed before it exits.
        val result = httpBootstrap.bind("0.0.0.0", httpPort).await()
        if (!result.isSuccess) {
            logger.error(result.cause()) { "Failed to bind to 0.0.0.0:$httpPort" }
            exitProcess(1)
        }

        logger.info { "Http server bound to 0.0.0.0:$httpPort" }

        bindContentPort()
    }

    /**
     * Also listen on the port the CLIENT computes for its HTTP content fetches.
     *
     * The client does not use the port it fetched jav_config from for cache
     * content. It builds a second base URL, `http://<param 49 host>:<param 38 +
     * 7000>`, and asks that for `/ms?m=0&a=..&k=..&g=..&c=..&v=..`.
     */
    private fun bindContentPort() {
        val offset = System.getProperty("opennxt.http.contentPortOffset")?.toIntOrNull() ?: 7000
        val endpointId = ClientParams.build(config)["38"]?.toIntOrNull()
        if (endpointId == null) {
            logger.warn { "No param 38 in the generated client params - cannot derive the content port" }
            return
        }

        val contentPort = endpointId + offset
        if (contentPort == config.ports.http) {
            logger.info { "Client content port $contentPort is already bound" }
            return
        }
        if (contentPort !in 1..65535) {
            logger.warn { "Derived content port $contentPort (param 38 = $endpointId, + $offset) is out of range" }
            return
        }

        logger.info { "Binding http server to 0.0.0.0:$contentPort (client content port: param 38 = $endpointId, + $offset)" }
        // `await()`, not `sync()`.
        //
        // ChannelFuture.sync() RETHROWS the failure cause; it does not return a
        // failed future. So the branch below was unreachable, and the three
        // paragraphs of explanation the author wrote for exactly this moment -
        // including the -Dopennxt.http.contentPortOffset hint and the note that
        // without this port "the loading bar stalls with no error" - could never
        // print. What happened instead was that the BindException propagated out
        // of here, out of bind(), out of OpenNXT.run(), and KILLED THE PROCESS
        // after the game port and port 80 were already bound: the exact opposite
        // of the KDoc's stated intent that failing to bind here is a warning
        // rather than fatal. Verified with a scratch Netty program - the second
        // bind of the same port threw `java.net.BindException: Address already
        // in use` out of sync(), and no failed future was ever returned.
        //
        // Nothing exotic is needed to hit it: a previous server that did not
        // exit cleanly, or a second OpenNXT for another build, holds 8200.
        val result = httpBootstrap.bind("0.0.0.0", contentPort).await()
        if (!result.isSuccess) {
            logger.warn(result.cause()) {
                "Could not bind content port $contentPort. The client fetches its master index there, and " +
                    "without it no cache group is ever requested - the loading bar stalls with no error. " +
                    "Override the offset with -Dopennxt.http.contentPortOffset=<n>."
            }
            return
        }
        logger.info { "Http server also bound to 0.0.0.0:$contentPort" }
    }

    override fun close() {
        logger.warn { "TODO - Close http server connections" }
    }

    private class HttpChannelInitializer(val handler: HttpRequestHandler) : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            ch.pipeline().addLast("codec", HttpServerCodec())
            // 2048 capped every HTTP request at 2KB - a jav_config-sized GET with
            // headers already brushes that. 64KB is still tiny for a file server.
            ch.pipeline().addLast("aggregator", HttpObjectAggregator(65536))
            ch.pipeline().addLast("handler", handler)
        }
    }
}