package com.opennxt.net.game.serverprot

/**
 * One occupied slot of a container, as the INVENTORY family puts it on the wire.
 *
 * Deliberately NOT [com.opennxt.model.items.Item]: `Item` refuses `amount < 1`
 * and carries definition lookups, and the wire has to be able to express things
 * the model layer forbids - amount 0, and (on FULL) an amount byte for a slot
 * that holds nothing at all. A packet type that could not represent what the
 * protocol represents would silently round the caller's intent off.
 */
data class InvEntry(val obj: Int, val amount: Int, val params: List<InvParam> = emptyList()) {
    init {
        require(obj in 0..0xfffffe) { "obj id out of the 3-byte wire range (objId + 1 must fit umedium): $obj" }
        require(params.size in 0..0xff) { "a slot cannot carry more than 255 params: ${params.size}" }
    }
}

/**
 * One entry of a slot's parameter list.
 *
 * The client reads `ushort key` then `int value` per entry, after a `ubyte`
 */
data class InvParam(val key: Int, val value: Int) {
    init {
        require(key in 0..0xffff) { "param key does not fit a ushort: $key" }
    }
}
