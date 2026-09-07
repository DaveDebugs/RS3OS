package com.opennxt.net.game.clientprot

import com.opennxt.net.game.protocol.PacketFieldDeclaration

/**
 * A click on an interface component - the first menu option. 949 opcode 55,
 * fixed 9 bytes.
 */
class IfButton1(hash: Int, mid: Int, arg2: Int) : IfButtonN(1, hash, mid, arg2) {
    class Codec(fields: Array<PacketFieldDeclaration>) : IfButtonN.Codec<IfButton1>(fields) {
        override fun create(hash: Int, mid: Int, arg2: Int) = IfButton1(hash, mid, arg2)
    }
}
