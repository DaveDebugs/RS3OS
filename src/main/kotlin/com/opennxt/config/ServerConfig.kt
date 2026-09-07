package com.opennxt.config

import com.moandjiezana.toml.Toml
import com.opennxt.Constants

class ServerConfig : TomlConfig() {
    companion object {
        val DEFAULT_PATH = Constants.CONFIG_PATH.resolve("server.toml")

        /** The only protocol build this server supports. See [build]. */
        const val SUPPORTED_BUILD = 949
    }

    data class Ports(var game: Int = 43594, var http: Int = 80, var https: Int = 443)

    var ports = Ports()
    var hostname = "127.0.0.1"
    var configUrl = "http://127.0.0.1/jav_config.ws?binaryType=2"

    /**
     * The protocol build. This server targets **949 only**.
     *
     * The default is deliberately an IMPOSSIBLE value rather than a plausible
     * one. It used to be 918: if `server.toml` went missing, or lost its
     * `build` key, the server came up speaking build-918 protocol to a 949
     * client and said nothing about it. Every symptom of that is downstream and
     * confusing -- wrong opcodes, silent packet drops, a client that connects
     * and then does nothing.
     *
     * -1 cannot be mistaken for a real build, and [requireSupportedBuild] turns it
     * into one loud failure at boot instead.
     */
    var build = -1

    /**
     * Fail loudly rather than run mismatched.
     *
     * Called after load. There is no sensible recovery from a wrong build --
     * every packet on the wire would be framed against the wrong table -- so
     * this throws rather than warning and continuing.
     */
    fun requireSupportedBuild(source: String) {
        if (build == SUPPORTED_BUILD) return
        val what = if (build == -1)
            "no `build` key was found in $source"
        else
            "$source says build $build"
        throw IllegalStateException(
            "This server supports build $SUPPORTED_BUILD only, but $what. " +
                "Refusing to start: a mismatched protocol table frames every " +
                "packet wrongly and the symptoms all appear somewhere else. " +
                "Set `build = $SUPPORTED_BUILD` in $source."
        )
    }

    override fun save(map: MutableMap<String, Any>) {
        map["networking"] = mapOf(
            "ports" to mapOf(
                "game" to ports.game,
                "http" to ports.http,
                "https" to ports.https
            )
        )
        map["hostname"] = hostname
        map["configUrl"] = configUrl
        map["build"] = build
    }

    override fun load(toml: Toml) {
        hostname = toml.getString("hostname", hostname)
        configUrl = toml.getString("configUrl", configUrl)
        build = toml.getLong("build", build.toLong()).toInt()

        val networking = toml.getTable("networking")
        if (networking != null) {
            // was toml.getTable("ports") - the top-level lookup missed the
            // [networking.ports] nesting the file actually uses, so configured
            // ports were silently ignored and the defaults always applied
            val ports = networking.getTable("ports")
            if (ports != null) {
                this.ports.game = ports.getLong("game", this.ports.game.toLong()).toInt()
                this.ports.http = ports.getLong("http", this.ports.http.toLong()).toInt()
                this.ports.https = ports.getLong("https", this.ports.https.toLong()).toInt()
            }
        }
    }
}