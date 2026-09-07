package com.opennxt.model.entity.updating

import com.opennxt.OpenNXT
import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.entity.movement.CompassPoint
import com.opennxt.model.entity.movement.MovementSpeed
import com.opennxt.model.entity.player.Viewport
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.UpdateBlock
import com.opennxt.model.entity.rendering.UpdateBlockType
import com.opennxt.model.entity.rendering.blocks.AppearanceUpdateBlock
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.DataType
import com.opennxt.net.buf.GamePacketBuilder
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.security.MessageDigest
import kotlin.math.abs

object PlayerInfoEncoder {
    val MAX_PLAYERS_PER_ADD = 25

    fun createBufferFor(player: WorldPlayer): ByteBuf {
        val outBuf = Unpooled.buffer()
        val blockBuf = Unpooled.buffer()

        val buffer = GamePacketBuilder(outBuf)
        val block = GamePacketBuilder(blockBuf)
        val viewport = player.viewport

        // Opt-in, one-shot, and a no-op unless an operator set one of the
        // -Dopennxt.experiment.player.demo.* properties. See PlayerUpdates.Demo:
        // no id, text or damage is chosen by this server.
        PlayerUpdates.Demo.apply(player.entity)

        // What the extended-info section of THIS send carries, filled in by
        // packUpdateBlock as it writes. A parameter rather than a field on this
        // object because the four passes below all contribute to one section and
        // the section is per-send; see [ExtendedInfoTrace] for what the numbers
        // are for.
        val trace = ArrayList<ExtendedInfoTrace.Entry>(4)

        processLocalPlayers(player, viewport, buffer, block, true, trace)
        processLocalPlayers(player, viewport, buffer, block, false, trace)
        processOutsidePlayers(player, viewport, buffer, block, true, trace)
        processOutsidePlayers(player, viewport, buffer, block, false, trace)

        val extendedBytes = blockBuf.readableBytes()
        outBuf.writeBytes(blockBuf)
        blockBuf.release()

        lastReport = ExtendedInfoTrace.Report(
            ExtendedInfoTrace.PLAYER, player.name, trace, extendedBytes
        )
        ExtendedInfoTrace.record(lastReport!!)

        return outBuf
    }

    /**
     * The extended-info section of the last packet [createBufferFor] built, or
     * null before the first one. Same contract as
     * [NpcInfoEncoder.lastReport].
     */
    @Volatile
    var lastReport: ExtendedInfoTrace.Report? = null
        private set

    private fun processLocalPlayers(player: WorldPlayer,
                                    viewport: Viewport,
                                    buffer: GamePacketBuilder,
                                    block: GamePacketBuilder,
                                    nsn0: Boolean,
                                    trace: MutableList<ExtendedInfoTrace.Entry>) {
        val viewingDist = viewport.playerViewingDistance

        buffer.switchToBitAccess()
        var skipCount = 0
        for (index in 0 until viewport.localPlayerIndicesCount) {
            val id = viewport.localPlayerIndices[index]
            if (if (nsn0) (viewport.slotFlags[id].toInt() and 0x1) != 0
                else (viewport.slotFlags[id].toInt() and 0x1) == 0) {
                continue
            }

            if (skipCount > 0) {
                skipCount--
                viewport.slotFlags[id] = (viewport.slotFlags[id].toInt() or 0x2).toByte()
                continue
            }

            val target = viewport.localPlayers[id]
                ?: throw NullPointerException("Player disappeared from local players")
            if (needsRemove(player, viewport, target)) {
                buffer.putBits(1, 1)
                buffer.putBits(1, 0)
                buffer.putBits(2, 0)

                val regionHash = target.location.regionHash
                if (regionHash == viewport.regionHashes[id]) {
                    buffer.putBits(1, 0)
                } else {
                    buffer.putBits(1, 1)
                    appendRegionHash(buffer, target, viewport.regionHashes[id], regionHash)
                    viewport.regionHashes[id] = regionHash
                }

                viewport.localPlayers[id] = null

                // Forget the cached appearance hash TOO, not just the slot.
                //
                // Without this, a second player who leaves view and comes back
                // crashes the world tick, reproduced over three ticks: on the
                // re-add, needsAppearanceUpdate() still sees a matching cached
                // hash and suppresses the appearance, nothing else is queued, and
                // packUpdateBlock's `check(queued.isNotEmpty())` throws - while
                // the add path has already written putBits(1,1) promising the
                // client a block that will never arrive. It repeats every tick.
                //
                // Single-player is immune, which is exactly why it has never
                // shown: the local player is always in the local list and never
                // in outPlayerIndices. The first time two people are online it
                // would have taken the tick down.
                //
                // Clearing it is also what the protocol requires: REMOVE makes
                // the client destroy the entity, so it genuinely needs the
                // appearance again on re-add. Suppressing it would leave an
                // invisible player even if the crash were guarded away.
                viewport.cachedAppearanceHashes[id] = null
            } else {
                viewport.regionHashes[id] = target.location.regionHash
                val needsUpdate = needsMaskUpdate(player, viewport, target, block.writerIndex())
                if (needsUpdate) {
                    packUpdateBlock(player, viewport, target, block, trace)
                }

                val movement = target.movement
                val speed = movement.currentSpeed
                val walkDirection = movement.nextWalkDirection
                val runDirection = movement.nextRunDirection

                if (walkDirection != null) {
                    buffer.putBits(1, 1) // Needs update
                    buffer.putBits(1, if (needsUpdate) 1 else 0) // Mask update

                    var dx = walkDirection.dx + (runDirection?.dx ?: 0)
                    var dy = walkDirection.dy + (runDirection?.dy ?: 0)

                    if (speed.id.toByte() != viewport.movementTypes[id]
                        && !(abs(dx) < 2 && abs(dy) < 2 && runDirection != null)) {
                        buffer.putBits(2, 3) // Teleport
                        buffer.putBits(1, 0) // Local

                        if (dx < 0) dx += 32
                        if (dy < 0) dy += 32

                        buffer.putBits(15, dy + (dx shl 5) + (0 shl 10) + ((speed.id + 1) shl 12))
                        viewport.movementTypes[id] = speed.id.toByte()
                    } else if ((dx == 0 && dy == 0)) {
                        buffer.putBits(2, 0)
                    } else if (runDirection == null) {
                        buffer.putBits(2, 1) // Walk opcode
                        buffer.putBits(3, getWalkOpcode(walkDirection.dx, walkDirection.dy))
                        buffer.putBits(1, 0)
                    } else if (abs(dx) < 2 && abs(dy) < 2) {
                        if (runDirection.diagonal || walkDirection.diagonal)
                            throw IllegalStateException("HELP WHAT THE FUCK DO I DO NOW")

                        val addedDx = runDirection.dx + walkDirection.dx
                        val addedDy = runDirection.dy + walkDirection.dy

                        buffer.putBits(2, 1) // Walk
                        buffer.putBits(3, getWalkOpcode(addedDx, addedDy))
                        buffer.putBits(1, 1)
                        buffer.putBits(2, when (walkDirection) {
                            CompassPoint.NORTH -> 0
                            CompassPoint.WEST -> 1
                            CompassPoint.EAST -> 2
                            CompassPoint.SOUTH -> 3
                            else -> throw IllegalArgumentException("Hmm idk")
                        })
                    } else {
                        buffer.putBits(2, 2) // Run opcode
                        buffer.putBits(4, getRunOpcode(dx, dy))
                    }

                    // Keep the cached movement type in step on EVERY movement
                    // branch, not just the teleport one.
                    //
                    // It used to be written only inside the `speed.id !=
                    // movementTypes[id]` teleport branch above, so after the
                    // player stopped the cache kept the last speed and the next
                    // fresh step re-entered that branch and went out as a
                    // one-tile TELEPORT rather than a walk. Measured against the
                    // compiled encoder: tick 1 of a walk emitted b2 00 10 =
                    // type 3, 15-bit value 8193 = jump (0,+1).
                    //
                    // It also fires mid-path: Movement.speed defaults to RUN and
                    // the last odd tile of a path degrades to WALK, so the final
                    // step of every odd-length run was a teleport too. Visible
                    // as the avatar snapping instead of stepping.
                    //
                    // The teleport branch is for a genuine speed CHANGE, which
                    // is what it can now detect.
                    viewport.movementTypes[id] = speed.id.toByte()
                } else if (speed == MovementSpeed.INSTANT) {
                    buffer.putBits(1, 1) // Needs update
                    buffer.putBits(1, if (needsUpdate) 1 else 0) // Mask update
                    buffer.putBits(2, 3) // No other updates

                    var xOffset = target.location.x - target.previousLocation.x
                    var yOffset = target.location.y - target.previousLocation.y
                    val planeOffset = target.location.plane - target.previousLocation.plane
                    if (abs(xOffset) < 16 && abs(yOffset) < 16) {
                        buffer.putBits(1, 0)
                        if (xOffset < 0) xOffset += 32
                        if (yOffset < 0) yOffset += 32
                        buffer.putBits(15, yOffset + (xOffset shl 5) + ((planeOffset and 0x3) shl 10) + (4 shl 12))
                    } else {
                        buffer.putBits(1, 1)
                        buffer.putBits(3, 4)
                        buffer.putBits(30, (yOffset and 0x3fff) + ((xOffset and 0x3fff) shl 14) + ((planeOffset and 0x3) shl 28))
                    }

                    viewport.movementTypes[id] = MovementSpeed.INSTANT.id.toByte()
                } else if (needsUpdate) {
                    buffer.putBits(1, 1) // Needs update
                    buffer.putBits(1, 1) // Mask update
                    buffer.putBits(2, 0) // No other updates
                } else {
                    buffer.putBits(1, 0) // No update needed
                    inner@ for (idx in index + 1 until viewport.localPlayerIndicesCount) {
                        val p2Index = viewport.localPlayerIndices[idx]
                        if (if (nsn0) (viewport.slotFlags[p2Index].toInt() and 0x1) != 0
                            else ((viewport.slotFlags[p2Index].toInt() and 0x1) == 0))
                            continue

                        val p2 = viewport.localPlayers[p2Index]!!
                        if (needsRemove(player, viewport, p2) || needsMaskUpdate(player, viewport, p2, block.writerIndex())
                            || p2.movement.nextWalkDirection != null || p2.movement.currentSpeed == MovementSpeed.INSTANT)
                            break@inner
                        skipCount++
                    }
                    putSkip(buffer, skipCount)
                    viewport.slotFlags[id] = (viewport.slotFlags[id].toInt() or 0x2).toByte()
                }
            }
        }
        buffer.switchToByteAccess()
    }

    private fun getWalkOpcode(dx: Int, dy: Int): Int = when {
        dx == -1 && dy == -1 -> 0
        dx == 0 && dy == -1 -> 1
        dx == 1 && dy == -1 -> 2
        dx == -1 && dy == 0 -> 3
        dx == 1 && dy == 0 -> 4
        dx == -1 && dy == 1 -> 5
        dx == 0 && dy == 1 -> 6
        dx == 1 && dy == 1 -> 7
        else -> throw IllegalArgumentException("No walk opcode for delta: $dx $dy")
    }

    private fun getRunOpcode(dx: Int, dy: Int): Int = when {
        dx == -2 && dy == -2 -> 0
        dx == -1 && dy == -2 -> 1
        dx == 0 && dy == -2 -> 2
        dx == 1 && dy == -2 -> 3
        dx == 2 && dy == -2 -> 4
        dx == -2 && dy == -1 -> 5
        dx == 2 && dy == -1 -> 6
        dx == -2 && dy == 0 -> 7
        dx == 2 && dy == 0 -> 8
        dx == -2 && dy == 1 -> 9
        dx == 2 && dy == 1 -> 10
        dx == -2 && dy == 2 -> 11
        dx == -1 && dy == 2 -> 12
        dx == 0 && dy == 2 -> 13
        dx == 1 && dy == 2 -> 14
        dx == 2 && dy == 2 -> 15
        else -> throw IllegalArgumentException("No run opcode for delta $dx $dy")
    }

    private fun packUpdateBlock(
        player: WorldPlayer,
        viewport: Viewport,
        target: PlayerEntity,
        block: GamePacketBuilder,
        trace: MutableList<ExtendedInfoTrace.Entry>
    ) {
        val startedAt = block.writerIndex()
        var maskData = 0x0
        var appearanceBlock: AppearanceUpdateBlock? = null
        if (needsAppearanceUpdate(viewport, target.index, target.model.hash, block.writerIndex())) {
            appearanceBlock = AppearanceUpdateBlock()
            maskData = maskData or appearanceBlock.type.playerMask
            viewport.cachedAppearanceHashes[target.index] = target.model.hash
        }
        // TODO HeadIconUpdate

        val queued = enabledBlocks(player, target)
        if (appearanceBlock != null) queued += appearanceBlock

        // THE ORDER OF THE BLOCKS ON THE WIRE IS THE ORDER OF THE CLIENT'S
        // TESTS, not the order of this array. fcn is one
        // straight-line chain of bit tests and NOTHING in the section is
        // length-prefixed (the byte the appearance block carries is part of
        // the appearance payload, not an envelope), so a block written out of
        // order does not produce one bad block - it produces a bad remainder.
        // UpdateBlockType.wireOrder is the index in that chain, derived from
        // UpdateBlockType.DISPATCH_ORDER so it cannot drift from it.
        queued.sortBy { it.type.wireOrder }
        for (b in queued) maskData = maskData or b.type.playerMask

        // needsMaskUpdate and enabledBlocks agree by construction, so this is
        // unreachable; it is an assertion, not a fallback. Writing the two
        // skipped bytes and no mask would leave the client reading the next
        // record's first byte as this record's mask.
        check(queued.isNotEmpty()) {
            "packUpdateBlock was called for player ${target.index} with nothing to write"
        }

        // The two bytes the client SKIPS without reading -
        // every extended-info record is preceded by them. Not a length: the
        // client never looks at the value, which is why this placeholder 0 has
        // always been harmless.
        block.put(DataType.SHORT, 0)

        // The 1..4 mask bytes and their continuation bits (0x20 / bit 14 /
        // bit 17, read / /). This used
        // to be open-coded here with the inherited 0x40 / 0x400, which are
        // real DATA bits on 949, so a
        // two-byte mask would have set a live block's bit AND failed to
        // announce the continuation. It also used to be unreachable, because
        // maskData was always 0x4. It is reachable now.
        UpdateBlockType.writeMask(block, maskData)

        for (b in queued) b.encode(block, player, target)

        // Measured from the buffer, after the fact - see the note on the npc
        // side. `queued` is already in wire order, so the names are in the order
        // the bytes are.
        trace.add(
            ExtendedInfoTrace.Entry(
                ExtendedInfoTrace.PLAYER,
                target.index,
                queued.map { it.type.name },
                block.writerIndex() - startedAt
            )
        )
    }

    private fun needsRemove(player: WorldPlayer, viewport: Viewport, target: PlayerEntity): Boolean {
        if (target.index <= 0) return true
        if (!player.entity.location.withinDistance(target.location, viewport.playerViewingDistance)) return true
//        if (player.entity.location.getRegionKey() != target.location.getRegionKey()) return true // TODO Re-add
        return false
    }

    private fun needsAdd(player: WorldPlayer, viewport: Viewport, target: PlayerEntity): Boolean {
        if (target.index <= 0) return false
        if (viewport.localAddedPlayers > MAX_PLAYERS_PER_ADD) return false
        if (!player.entity.location.withinDistance(target.location, viewport.playerViewingDistance)) return false
//        if (player.entity.location.getRegionKey() != target.location.getRegionKey()) return false // TODO Re-add
        return true
    }

    /**
     * Everything queued on [target] that is allowed on the wire for [viewer],
     * appearance excluded (it is not stored in the renderer array).
     *
     * ONE function, used by BOTH [needsMaskUpdate] and [packUpdateBlock]. They
     * have to agree exactly: the bit section carries a one-bit "this player
     * has extended info" flag, and if that flag says yes while the block
     * buffer gets nothing - which is what happens the moment a block is queued
     * and then gated off by `-Dopennxt.experiment.player.block.*` - the client
     * reads the NEXT player's record as this one's. The gate has to be applied
     * in the same place for both answers, so it is applied here.
     */
    private fun enabledBlocks(viewer: WorldPlayer, target: PlayerEntity): ArrayList<UpdateBlock> {
        val out = ArrayList<UpdateBlock>(4)
        val blocks = target.renderer.blocks
        for (pos in 0 until blocks.size) {
            val current: UpdateBlock = blocks[pos] ?: continue
            if (!PlayerUpdates.blockEnabled(current.type)) continue
            if (!current.needsUpdate(viewer)) continue
            out += current
        }
        return out
    }

    private fun needsMaskUpdate(player: WorldPlayer, viewport: Viewport, target: PlayerEntity, blockSize: Int): Boolean {
        val appearanceUpdate = needsAppearanceUpdate(viewport, target.index, target.model.hash, blockSize)
        // TODO Icon update
        return appearanceUpdate || enabledBlocks(player, target).isNotEmpty()
    }

    /**
     * Kill switch for the whole appearance block.
     *
     * Until the mask bit was corrected to 0x4 the client never reached its model
     * parser, so a wrong appearance PAYLOAD could not show up as a crash - it was
     * being discarded three bytes earlier. Now that the parser is reached, the
     * payload is on the critical path for the first time and is NOT verified:
     * [com.opennxt.model.entity.player.appearance.PlayerModel.appendAppearance]
     * writes u16 with kit bias 0x100, while an unverified note claims 949 wants
     * LEB128 varints with kit bias 2 and obj bias 0x800.
     *
     * `-Dopennxt.experiment.appearance=false` puts the session back to a world
     * that renders and walks with no avatar - the known-good state - without a
     * rebuild. It is also the second arm of the crash experiment described in
     * [com.opennxt.model.world.WorldPlayer.tick].
     */
    private val appearanceEnabled: Boolean =
        System.getProperty("opennxt.experiment.appearance") != "false"

    private fun needsAppearanceUpdate(viewport: Viewport, index: Int, hash: ByteArray, blockSize: Int): Boolean {
        if (!appearanceEnabled) return false
        if (blockSize > ((7500 - 500) / 2) || hash.isEmpty()) return false
        val cachedHash = viewport.cachedAppearanceHashes[index]
        return cachedHash == null || !MessageDigest.isEqual(cachedHash, hash)
    }

    private fun putSkip(buffer: GamePacketBuilder, skipCount: Int) {
        val type = when {
            skipCount == 0 -> 0
            skipCount > 255 -> 3
            skipCount > 31 -> 2
            else -> 1
        }
        buffer.putBits(2, type)
        if (skipCount > 0) {
            val bits = when {
                skipCount > 255 -> 11
                skipCount > 31 -> 8
                else -> 5
            }

            buffer.putBits(bits, skipCount)
        }
    }

    private fun processOutsidePlayers(player: WorldPlayer,
                                      viewport: Viewport,
                                      buffer: GamePacketBuilder,
                                      block: GamePacketBuilder,
                                      nsn2: Boolean,
                                      trace: MutableList<ExtendedInfoTrace.Entry>) {
        buffer.switchToBitAccess()
        var skipCount = 0
        for (index in 0 until viewport.outPlayerIndicesCount) {
            val id = viewport.outPlayerIndices[index]
            if (if (nsn2) (viewport.slotFlags[id].toInt() and 0x1) == 0
                else (viewport.slotFlags[id].toInt() and 0x1) != 0)
                continue
            if (skipCount > 0) {
                skipCount--
                viewport.slotFlags[id] = (viewport.slotFlags[id].toInt() or 2).toByte()
                continue
            }

            val target = OpenNXT.world.getPlayer(id)
//                    ?: throw NullPointerException("Player not found (but should be added) in index: $id")
            if (target != null && needsAdd(player, viewport, target)) {
                buffer.putBits(1, 1)
                buffer.putBits(2, 0)
                val hash = target.location.regionHash
                if (hash == viewport.regionHashes[id]) {
                    buffer.putBits(1, 0)
                } else {
                    buffer.putBits(1, 1)
                    appendRegionHash(buffer, target, viewport.regionHashes[id], hash)
                    viewport.regionHashes[id] = hash
                }
                buffer.putBits(6, target.location.xInRegion)
                buffer.putBits(6, target.location.yInRegion)
                packUpdateBlock(player, viewport, target, block, trace)
                buffer.putBits(1, 1)
                viewport.localPlayers[id] = target
                viewport.slotFlags[id] = (viewport.slotFlags[id].toInt() or 2).toByte()
            } else {
                val hash = target?.location?.regionHash ?: viewport.regionHashes[index]
                if (target != null && hash != viewport.regionHashes[id]) {
                    buffer.putBits(1, 1)
                    appendRegionHash(buffer, target, viewport.regionHashes[id], hash)
                    viewport.regionHashes[id] = hash
                } else {
                    buffer.putBits(1, 0)
                    for (idx in index + 1 until viewport.outPlayerIndicesCount) {
                        val p2Index = viewport.outPlayerIndices[idx]
                        if (if (nsn2) (viewport.slotFlags[p2Index].toInt() and 0x1) == 0
                            else (viewport.slotFlags[p2Index].toInt() and 0x1) != 0)
                            continue

                        val p2 = OpenNXT.world.getPlayer(p2Index)
                        if (p2 != null && (needsAdd(player, viewport, p2) || p2.location.regionHash != viewport.regionHashes[p2Index]))
                            break
                        skipCount++
                    }
                    putSkip(buffer, skipCount)
                    viewport.slotFlags[id] = (viewport.slotFlags[id].toInt() or 0x2).toByte()
                }
            }
        }
        buffer.switchToByteAccess()
    }

    private fun appendRegionHash(buf: GamePacketBuilder, entity: Entity, lastHash: Int, currentHash: Int) {
        val lastRegionX = lastHash shr 8 and 0xff
        val lastRegionY = 0xff and lastHash
        val lastPlane = lastHash shr 16 and 0x3
        val currentRegionX = currentHash shr 8 and 0xff
        val currentRegionY = 0xff and currentHash
        val currentPlane = currentHash shr 16 and 0x3
        val planeOffset = currentPlane - lastPlane
        if (lastRegionX == currentRegionX && lastRegionY == currentRegionY) {
            buf.putBits(2, 1)
            buf.putBits(2, planeOffset and 0x3)
        } else if (Math.abs(currentRegionX - lastRegionX) <= 1 && Math.abs(currentRegionY - lastRegionY) <= 1) {
            val opcode: Int
            val dx = currentRegionX - lastRegionX
            val dy = currentRegionY - lastRegionY
            if (dx == -1 && dy == -1) {
                opcode = 0
            } else if (dx == 1 && dy == -1) {
                opcode = 2
            } else if (dx == -1 && dy == 1) {
                opcode = 5
            } else if (dx == 1 && dy == 1) {
                opcode = 7
            } else if (dy == -1) {
                opcode = 1
            } else if (dx == -1) {
                opcode = 3
            } else if (dx == 1) {
                opcode = 4
            } else if (dy == 1) {
                opcode = 6
            } else {
                throw RuntimeException("Invalid delta value for region hash!")
            }
            buf.putBits(2, 2)
            buf.putBits(5, (planeOffset and 0x3 shl 3) + (opcode and 0x7))
        } else {
            val xOffset = currentRegionX - lastRegionX
            val yOffset = currentRegionY - lastRegionY
            buf.putBits(2, 3)
            buf.putBits(20, (yOffset and 0xff) + (xOffset and 0xff shl 8)
                    + (planeOffset and 0x3 shl 16) + ((entity.movement.speed.id + 1) shl 18))
        }
    }
}