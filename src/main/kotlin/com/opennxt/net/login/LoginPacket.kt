package com.opennxt.net.login

import com.opennxt.config.ServerConfig
import com.opennxt.model.Build
import com.opennxt.net.GenericResponse
import com.opennxt.net.IncomingPacket
import com.opennxt.net.OutgoingPacket
import io.netty.buffer.ByteBuf

sealed class LoginPacket : IncomingPacket, OutgoingPacket {
    data class SendUniqueId(val id: Long) : LoginPacket()
    class LobbyLoginRequest(
        val build: Build,
        val header: LoginRSAHeader,
        val username: String,
        val password: String,
        val remaining: ByteBuf
    ) : LoginPacket()

    class GameLoginRequest(
        val build: Build,
        val header: LoginRSAHeader,
        val username: String,
        val password: String,
        val remaining: ByteBuf
    ) : LoginPacket()

    data class GameLoginResponse(
        val byte0: Int,
        val rights: Int,
        val byte2: Int,
        val byte3: Int,
        val byte4: Int,
        val byte5: Int,
        val byte6: Int,
        val playerIndex: Int,
        val byte8: Int,
        val medium9: Int,
        val isMember: Int,
        val username: String,
        val short12: Int, // part of 6-byte-int
        val int13: Int // part of 6-byte-int
    ) : LoginPacket()

    data class LobbyLoginResponse(
        val byte0: Int,
        val rights: Int,
        val byte2: Int,
        val byte3: Int,
        val medium4: Int,
        val byte5: Int,
        val byte6: Int,
        val byte7: Int,
        val long8: Long,
        val int9: Int,
        val byte10: Int,
        val byte11: Int,
        val int12: Int,
        val int13: Int,
        val short14: Int,
        val short15: Int,
        val short16: Int,
        val ip: Int,
        val byte17: Int,
        val short18: Int,
        val short19: Int,
        val byte20: Int,
        val username: String,
        val byte22: Int,
        val int23: Int,
        val short24: Int,
        val defaultWorld: String,
        val defaultWorldPort1: Int,
        val defaultWorldPort2: Int
    ) : LoginPacket() {
        companion object {
            /**
             * Packs a dotted-quad IPv4 address into the big-endian int the
             * `ip` field carries: `a.b.c.d -> (a shl 24) or (b shl 16) or (c shl 8) or d`,
             * which [LoginEncoder]'s plain `writeInt` then puts on the wire
             * most-significant byte first, i.e. in address order.
             *
             * PACKING DETERMINATION: the previous hardcoded value was
             * 213076433 (0x0CB32F51). Read big-endian that is 12.179.47.81;
             * read little-endian it is 81.47.179.12 - neither means anything
             * for a loopback dev server. But 127.0.0.1 packed big-endian is
             * 0x7F000001 = 2130706433, and "2130706433" with the zero after
             * the 7 dropped is exactly "213076433": the old constant is a
             * one-digit typo of big-endian 127.0.0.1. That typo is the
             * evidence the field was always MEANT as a big-endian-packed
             * address, so 127.0.0.1 is packed accordingly here.
             */
            fun packIpv4(address: String): Int {
                val parts = address.split('.')
                require(parts.size == 4) { "not a dotted-quad IPv4 address: '$address'" }
                var packed = 0
                for (part in parts) {
                    val octet = part.toIntOrNull()
                    require(octet != null && octet in 0..255) { "bad IPv4 octet '$part' in '$address'" }
                    packed = (packed shl 8) or octet
                }
                return packed
            }

            /**
             * The one documented construction site for the lobby login
             * response. Field provenance, per project method:
             *
             *  - KNOWN fields (real values): [username], [ip] (this server's
             *    loopback address, packed per [packIpv4]'s determination),
             *    [defaultWorld] and both ports (straight from the loaded
             *    server config - not the previously hardcoded 43594).
             *  - CARRIED-OVER fields: [rights] = 2, the value this server has
             *    always sent. The name comes with the field declaration; the
             * value has not been verified against a real observation.
             *  - UNKNOWN fields: everything else is zero-until-proven-otherwise,
             *    commented individually below. Nothing is invented silently.
             *
             * The handler logs the full field list at DEBUG on every send so
             * a bisect against a real client has something to read.
             */
            fun forAccount(username: String, config: ServerConfig): LobbyLoginResponse = LobbyLoginResponse(
                byte0 = 0,          // unknown - zero until proven otherwise
                rights = 2,         // carried over: rights/privilege level; 2 is what this server always sent (unverified against an observation)
                byte2 = 0,          // unknown - zero until proven otherwise
                byte3 = 0,          // unknown - zero until proven otherwise
                medium4 = 0,        // unknown - zero until proven otherwise
                byte5 = 0,          // unknown - zero until proven otherwise
                byte6 = 0,          // unknown - zero until proven otherwise
                byte7 = 0,          // unknown - zero until proven otherwise
                long8 = 0,          // unknown - zero until proven otherwise (position suggests a timestamp; unproven)
                int9 = 0,           // unknown - zero until proven otherwise
                byte10 = 0,         // unknown - zero until proven otherwise
                byte11 = 0,         // unknown - zero until proven otherwise
                int12 = 0,          // unknown - zero until proven otherwise
                int13 = 0,          // unknown - zero until proven otherwise
                short14 = 0,        // unknown - zero until proven otherwise
                short15 = 0,        // unknown - zero until proven otherwise
                short16 = 0,        // unknown - zero until proven otherwise
                ip = packIpv4("127.0.0.1"), // known: an IPv4 address (plausibly "last login from"), big-endian-packed - see packIpv4's determination
                byte17 = 0,         // unknown - zero until proven otherwise
                short18 = 0,        // unknown - zero until proven otherwise
                short19 = 0,        // unknown - zero until proven otherwise
                byte20 = 0,         // unknown - zero until proven otherwise
                username = username, // known: the authenticated account's username, echoed back
                byte22 = 0,         // unknown - zero until proven otherwise
                int23 = 0,          // unknown - zero until proven otherwise
                short24 = 0,        // unknown - zero until proven otherwise
                defaultWorld = config.hostname,          // known: world host from server config
                defaultWorldPort1 = config.ports.game,   // known: game port from server config (was hardcoded 43594)
                defaultWorldPort2 = config.ports.game,   // known: game port from server config (was hardcoded 43594)
            )
        }
    }

    data class LoginResponse(val code: GenericResponse) : LoginPacket()

    /**
     * The client's bare GAMELOGIN_CONTINUE opcode (26), sent after it has
     * received the serverperm varc chunk(s). Carries no payload; decoded by
     * [LoginServerDecoder] and handled in [LoginServerHandler], where the
     * WorldPlayer is constructed from the persisted save.
     */
    object GameLoginContinue : LoginPacket()

    data class ServerpermVarcChunk(val finished: Boolean, val varcs: Map<Int, Any>) : LoginPacket()
}