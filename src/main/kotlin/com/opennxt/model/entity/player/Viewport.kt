package com.opennxt.model.entity.player

import com.opennxt.OpenNXT
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.entity.movement.MovementSpeed
import com.opennxt.model.world.MapSize
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.serverprot.RebuildNormal

class Viewport(val player: WorldPlayer) {
    val localPlayers = arrayOfNulls<PlayerEntity>(2048)
    val localPlayerIndices = IntArray(2048)
    var localPlayerIndicesCount = 0
    val outPlayerIndices = IntArray(2048)
    var outPlayerIndicesCount = 0
    val regionHashes = IntArray(2048)
    val slotFlags = ByteArray(2048)
    val movementTypes = ByteArray(2048)
    var localAddedPlayers = 0
    val cachedAppearanceHashes = arrayOfNulls<ByteArray>(2048)
    val cachedHeadIconHashes = arrayOfNulls<ByteArray>(2048)

    //    val regions = ObjectOpenHashSet<Region>()
    var sceneRadius = 7
    var baseTile = player.entity.location
    var playerViewingDistance = 14
    var mapSize = MapSize.SIZE_256

    fun init(buf: GamePacketBuilder) {
        val entity = player.entity

        baseTile = entity.location
        if (entity.index < 1 || entity.index >= 2048)
            throw IllegalStateException("Player index must be between 1 and 2047 for ${player.name}: $player.index")

        buf.switchToBitAccess()
        buf.putBits(30, entity.location.tileHash)

        localPlayers[entity.index] = entity
        localPlayerIndicesCount = 0
        outPlayerIndicesCount = 0
        localPlayerIndices[localPlayerIndicesCount++] = entity.index
        for (index in 1 until 2048) {
            if (index == entity.index)
                continue
            val other = OpenNXT.world.getPlayer(index)
            val speed = other?.movement?.currentSpeed ?: MovementSpeed.STATIONARY
            val hash = (other?.location?.regionHash ?: 0) or (speed.id shl 18)
            buf.putBits(20, hash)
            regionHashes[index] = hash
            outPlayerIndices[outPlayerIndicesCount++] = index
            if (speed != MovementSpeed.STATIONARY)
                continue
            slotFlags[index] = (slotFlags[index].toInt() or 0x1).toByte()
        }
        buf.switchToByteAccess()
        moveToRegion(entity.location, mapSize, false)
    }
    /*
    public void init(GamePacketBuilder buffer) {
        buffer.switchToBitAccess();
        buffer.putBits(30, player.getTileHash());
        localPlayers[player.getIndex()] = player;
        localPlayersIndexes[localPlayersIndexesCount++] = player.getIndex();
        for (int playerIndex = 1; playerIndex < 2048; playerIndex++) {
            if (playerIndex == player.getIndex())
                continue;
            Player player = World.getPlayers().get(playerIndex);
            MovementSpeed speed = MovementSpeed.STATIONARY;
            int hash = 0;
            if (player != null) {
                speed = player.getMovementSpeed();
                hash = player.getRegionHash() | (speed.getId() << 18);
            }
            buffer.putBits(20, regionHashes[playerIndex] = hash);
            outPlayersIndexes[outPlayersIndexesCount++] = playerIndex;
            if (speed != MovementSpeed.STATIONARY) continue;
            slotFlags[playerIndex] = (byte)(slotFlags[playerIndex] | 1);
        }
        buffer.switchToByteAccess();
    }
     */

    /**
     * Distance from the scene centre at which the scene is rebuilt.
     *
     * The client's scene spans baseTile +/- 128 tiles at mapSize 256. Rebuilding
     * at 96 leaves a 32-tile margin, so the edge is never reached mid-walk even
     * at run speed (2 tiles/tick).
     */
    private val rebuildThreshold = 96

    /**
     * Re-centre the scene on the player when they have walked far enough.
     */
    fun rebuildIfNeeded() {
        val loc = player.entity.location
        val dx = Math.abs(loc.x - baseTile.x)
        val dy = Math.abs(loc.y - baseTile.y)
        if (dx < rebuildThreshold && dy < rebuildThreshold) return
        moveToRegion(loc, mapSize, sendUpdate = true)
    }

    /**
     * How many times the scene has been (re)built for this player.
     */
    var sceneEpoch: Int = 0
        private set

    fun moveToRegion(tile: TileLocation, size: MapSize, sendUpdate: Boolean = true) {
        this.mapSize = size
        this.baseTile = tile
        sceneEpoch++

//        val oldRegions = ObjectOpenHashSet<Region>(regions)
//        regions.clear()

//        for (x in (tile.chunkX - (size.size shr 4)) / 8..(tile.chunkX + (size.size shr 4)) / 8) {
//            for (y in (tile.chunkY - (size.size shr 4)) / 8..(tile.chunkY + (size.size shr 4)) / 8) {
//                try {
//                    val id = (x shl 8) or y
//                    if (id < 0 || id > 65535) continue
//
//                    val region = GameServer.instance.world.regions.getRegion((x shl 8) + y, true)
//                    regions.add(region)
//                    region.addPlayer(player)
//                } catch (e: NullPointerException) {
//                }
//            }
//        }

//        oldRegions.filter { !regions.contains(it) }.forEach { it.removePlayer(player) }

        if (sendUpdate) {
            player.client.write(createPacket())
        }
    }

    fun createPacket(): GamePacket {
        return RebuildNormal(
            unused1 = 0,
            // Derived from where the scene actually IS, not hardcoded.
            //
            // These were literal 402s. The 949 handler computes the scene base
            // as (chunk - [scene+0x6a0]>>4) * 8, which with mapSize 256 is
            // (chunk - 16) * 8, so chunk = baseTile / 8 centres the scene on
            // baseTile. 402 happened to be correct for the spawn tile 3216
            // (3216/8 = 402) and wrong everywhere else.
            //
            // Combined with moveToRegion only ever being called once - from
            // init(), with sendUpdate=false - the client's scene stayed pinned
            // at (3216,3216) for the whole session while the server walked the
            // player out of it. The client then renders the avatar against a
            // stale frame and resolves clicks in that frame, which is why the
            // click delta was near-constant (~66 west, ~60 south) while the
            // server-side origin marched: the two sides were describing
            // different worlds.
            chunkX = baseTile.x / 8,
            unused2 = 0,
            chunkY = baseTile.y / 8,
            npcBits = sceneRadius,
            mapSize = mapSize.id,
            areaType = 474,
            // -1, not MIN_VALUE/MAX_VALUE. The 949 handler
            // treats -1 as the "no instance" sentinel and SKIPS these fields:
            //
            // cmp r8d, -1; hash1
            // je skip
            // shr r15d, 0xe; else derive (h >> 14) & 0x3fff
            // and r12d, 0x3fff; and h & 0x3fff
            //... identical block for hash2
            //
            // MIN_VALUE (0x80000000) and MAX_VALUE (0x7fffffff) are neither -1
            // nor meaningful instance handles, so the client took the derive
            // branch and built two 14-bit values out of nothing: (0, 0) from
            // MIN_VALUE and (0x3fff, 0x3fff) from MAX_VALUE. A normal,
            // non-instanced world login has no instance to point at, which is
            // exactly what the sentinel is for.
            hash1 = -1,
            hash2 = -1,
        )
    }

    // ================================================================
    // WORLD -> ZONE CONVERSION. The only copy of this arithmetic.
    // ================================================================
    //
    // Every packet in the world-content family (UPDATE_ZONE_*_FOLLOWS, OBJ_*,
    // LOC_*) addresses tiles as a SCENE-RELATIVE 8x8 zone index plus a packed
    // tile byte. Neither is a world coordinate, and both fail SILENTLY when
    // wrong: a wrong zone base puts the item in a different part of the map,
    // which from the player's chair is indistinguishable from the server
    // having sent nothing at all.
    //
    // The arithmetic is closed against the client, not conventional:
    //
    // REBUILD_NORMAL sends chunkX and chunkY.
    //   The handler computes  origin = (chunk - (mapSizeTiles shr 4)) * 8
    // ( reads [scene+0x6a0] = 256, sar 4 -> 16,
    // sub, shl 3) and stores the pair at
    ///, which writes into [scene+0x698]
    //   as ONE qword - so the LOW dword, 0x698, is X.
    //
    //   UPDATE_ZONE_PARTIAL_FOLLOWS then does
    // global = [scene+0x698] + wireZoneX * 8
    //   and OBJ_ADD's sink adds the coord nibbles to that global
    // (// for X,/
    //    for Y).
    //
    // Solving global + (coord nibble) == worldX gives the two functions below.
 // re-derives them from those
    // client steps and has a negative control for the missing +16.

    /**
     * Tiles the client subtracts from the transmitted chunk when it builds the
     * scene origin: `mapSizeTiles shr 4`, which is 16 at [MapSize.SIZE_256].
     */
    val zoneOrigin: Int get() = zoneOrigin(mapSize)

    /** The chunk x this player's REBUILD_NORMAL last put on the wire. */
    val chunkX: Int get() = baseTile.x / 8

    /** The chunk y this player's REBUILD_NORMAL last put on the wire. */
    val chunkY: Int get() = baseTile.y / 8

    /** Scene-relative zone index of world tile x, for the zone-framing packets. */
    fun zoneX(worldX: Int): Int = zoneIndex(worldX, chunkX, mapSize)

    /** Scene-relative zone index of world tile y, for the zone-framing packets. */
    fun zoneY(worldY: Int): Int = zoneIndex(worldY, chunkY, mapSize)

    /**
     * Whether (worldX, worldY) is inside the scene this client currently holds.
     */
    fun containsTile(worldX: Int, worldY: Int): Boolean =
        contains(worldX, worldY, chunkX, chunkY, mapSize)

    companion object {

        /**
         * The pure form of the world->zone conversion, taking everything it
         * needs as arguments.
         *
         * It exists so a check can drive THE SAME EXPRESSION the wire uses
         * without constructing a [WorldPlayer], a channel and a world. The
         * instance methods above delegate here and contain no arithmetic of
         * their own, so there is exactly ONE copy of this formula in the tree
         * and a test cannot end up comparing a restatement to itself.
         *
         * `zone = (worldTile shr 3) - chunk + (mapSizeTiles shr 4)`, derived by
         * solving the client's own steps for the wire field. See the block
         * comment above [zoneOrigin] for those steps and their addresses.
         */
        fun zoneIndex(worldTile: Int, chunk: Int, mapSize: MapSize): Int =
            (worldTile shr 3) - chunk + zoneOrigin(mapSize)

        /** `mapSizeTiles shr 4` - what the client subtracts. */
        fun zoneOrigin(mapSize: MapSize): Int = mapSize.size shr 4

        /**
         * Whether a tile falls inside a scene built at ([chunkX], [chunkY]).
         */
        fun contains(worldX: Int, worldY: Int, chunkX: Int, chunkY: Int, mapSize: MapSize): Boolean {
            val span = zoneOrigin(mapSize) * 2
            return zoneIndex(worldX, chunkX, mapSize) in 0 until span &&
                zoneIndex(worldY, chunkY, mapSize) in 0 until span
        }
    }

    fun resetForNextTransmit() {
        localPlayerIndicesCount = 0
        outPlayerIndicesCount = 0
        localAddedPlayers = 0
        for (idx in 1 until 2048) {
            slotFlags[idx] = (slotFlags[idx].toInt() shr 1).toByte()
            val player = localPlayers[idx]
            if (player == null)
                outPlayerIndices[outPlayerIndicesCount++] = idx
            else
                localPlayerIndices[localPlayerIndicesCount++] = idx
        }
    }
}