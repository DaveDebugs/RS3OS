package com.opennxt.net.game

import com.opennxt.Constants
import com.opennxt.OpenNXT
import com.opennxt.model.files.FileChecker
import com.opennxt.net.Side
import com.opennxt.net.game.clientprot.*
import com.opennxt.net.game.pipeline.DynamicGamePacketCodec
import com.opennxt.net.game.pipeline.GamePacketCodec
import com.opennxt.net.game.protocol.PacketFieldDeclaration
import com.opennxt.net.game.serverprot.*
import com.opennxt.net.game.serverprot.ifaces.*
import com.opennxt.net.game.serverprot.variables.*
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import mu.KotlinLogging
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.reflect.jvm.javaType
import com.opennxt.net.game.serverprot.audio.MidiSong
import com.opennxt.net.game.serverprot.audio.MidiSongStop
import com.opennxt.net.game.serverprot.audio.SoundGroupRelease
import com.opennxt.net.game.serverprot.audio.SoundGroupStop
import com.opennxt.net.game.serverprot.audio.SoundMixbussSetvolume
import com.opennxt.net.game.serverprot.interfaces.IfMovesub
import com.opennxt.net.game.serverprot.interfaces.IfSetcolour
import com.opennxt.net.game.clientprot.VarcTransmit
import com.opennxt.net.game.serverprot.variables.StoreServerpermVarcsAck
import com.opennxt.net.game.serverprot.variables.ClientSetvarcbitLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcbitSmall
import com.opennxt.net.game.serverprot.variables.VarbitLarge
import com.opennxt.net.game.serverprot.variables.VarbitSmall

object PacketRegistry {
    private val logger = KotlinLogging.logger { }

    private val serverProtByOpcode = Int2ObjectOpenHashMap<Registration>()
    private val clientProtByOpcode = Int2ObjectOpenHashMap<Registration>()

    private val serverProtByClass = Object2ObjectOpenHashMap<KClass<*>, Registration>()
    private val clientProtByClass = Object2ObjectOpenHashMap<KClass<*>, Registration>()

    /**
     * Names this build's protocol table has no opcode for, collected during
     * [reload] and reported once at the end rather than one warning per packet
     * buried in two hundred lines of successful registrations.
     */
    private val unmapped = HashMap<Side, java.util.SortedSet<String>>()

    /**
     * Names that DO have an opcode on this build but no field declaration file,
     * so they still cannot be encoded. Tracked separately from [unmapped]
     * because the two point at different missing work: one needs the opcode
     * recovered, the other needs the field layout recovered.
     */
    private val missingDeclaration = HashMap<Side, java.util.SortedSet<String>>()

    /** Packet names that could not be mapped for [side] on the current build. */
    fun unmappedNames(side: Side): Set<String> =
        (unmapped[side] ?: emptySet<String>()) + (missingDeclaration[side] ?: emptySet<String>())

    /** How many packets actually registered for [side] on the current build. */
    fun registeredCount(side: Side): Int =
        if (side == Side.CLIENT) clientProtByOpcode.size else serverProtByOpcode.size

    data class Registration(
        val name: String,
        val opcode: Int,
        val clazz: KClass<*>,
        val codec: GamePacketCodec<*>
    )

    fun <T : GamePacket> register(
        side: Side,
        name: String,
        clazz: KClass<T>,
        codecType: KClass<out DynamicGamePacketCodec<T>>
    ) {
        val constructor = codecType.constructors
            .first { it.parameters.size == 1 && it.parameters[0].type.javaType == Array<PacketFieldDeclaration>::class.java }

        // The build here MUST be the protocol build, not FileChecker.latestBuild().
        //
        // latestBuild() returns the highest numbered directory under data/clients
        // - the CLIENT STAGING area - which has nothing to do with which protocol
        // this server speaks. With server.toml build = 947 and only data/clients/919
        // present, this read the 947 name->opcode table and then loaded every field
        // declaration from data/prot/919/serverProt/, silently pairing 947 opcodes
        // with 919 field layouts.
        //
        // That is the worst failure shape available here, because it is invisible:
        // every packet registers, the log looks perfect, and the bytes on the wire
        // are wrong. It is not hypothetical either - the 947 layouts were recovered
        // from the client binary specifically because the 919 ones do not transfer.
        // Eleven packets differ, and CLIENT_SETVARCSTR_LARGE reverses the order of
        // its two fields between the builds, so it would have encoded a string
        // where the client expects an id.
        //
        // For the same reason it must be the build whose tables were actually
        // READ - normally the configured build, but not when the operator has
        // explicitly borrowed another build's tables with
        // -Dopennxt.prot.borrowBuild. Taking opcodes from one build's table and
        // field layouts from another build's directory is exactly the pairing
        // described above, one level down.
        val build = OpenNXT.protocol.effectiveBuild.takeIf { it > 0 } ?: OpenNXT.config.build
        val packetPath = Constants.PROT_PATH.resolve(build.toString())
            .resolve(if (side == Side.CLIENT) "clientProt" else "serverProt")
            .resolve("$name.txt")

        // ASK THE NAME->OPCODE TABLE FIRST.
        //
        // This declaration check used to come before any opcode lookup at all -
        // that lookup lives in the other register() overload, which is called
        // last - so the warning below asserted a fact it had not checked, and
        // the two buckets that exist specifically to say WHERE TO GO LOOKING
        // pointed at the wrong work for most of their contents.
        //
        // Measured, from the boot log of this exact tree before the change:
        //
        //   Build 949 has an opcode for 'CLIENT_CHEAT' but no field declaration ...
        //   Build 949 has an opcode for 'WORLDLIST_FETCH' but no field declaration ...
        //   Build 949 has an opcode for 'IF_SETHIDE' but no field declaration ...
        //   Build 949 has an opcode for 'CHAT_FILTER_SETTINGS_PRIVATECHAT' but ...
        //     no fields  (server): CHAT_FILTER_SETTINGS_PRIVATECHAT,
        //                          CLIENT_SETVARCSTR_SMALL, IF_SETHIDE, IF_SETTEXT
        //     no fields  (client): CLIENT_CHEAT, WORLDLIST_FETCH
        //
        // Four of those six are false. data/prot/949/clientProtNames.toml is a
        // ZERO-BYTE file, so there is no opcode for CLIENT_CHEAT or
        // WORLDLIST_FETCH - or for any client packet at all; the same log line
        // says "0 client packets registered". serverProtNames.toml's 17 names
        // include neither IF_SETHIDE nor CHAT_FILTER_SETTINGS_PRIVATECHAT. Only
        // IF_SETTEXT and CLIENT_SETVARCSTR_SMALL genuinely lacked a field layout.
        //
        // That is the same shape as the two build split-brains this codebase has
        // already been burned by: a log that reads as authoritative and is not.
        if (opcodeFor(side, name) == null) {
            unmapped.getOrPut(side) { sortedSetOf<String>() }.add(name)
            return
        }

        if (!Files.exists(packetPath)) {
            // Distinguish "this build has no declaration for a packet it DOES have
            // an opcode for" from the ordinary unmapped case. This one means the
            // opcode is known but the field layout is not, so the packet still
            // cannot be sent - and saying which of the two it is decides where to
            // go looking. Now that the opcode really has been resolved above,
            // the claim this line makes is true.
            logger.warn { "Build $build has an opcode for '$name' but no field declaration at $packetPath - packet unusable" }
            missingDeclaration.getOrPut(side) { sortedSetOf<String>() }.add(name)
            return
        }

        // Pass the file and line down. PacketFieldDeclaration.fromString throws
        // on a bad line and that kills boot; without this the operator got a
        // stack trace whose most specific fact was a type name, with nothing
        // saying which of the dozens of hand-recovered declaration files it came
        // from, or where in it.
        // Blank lines AND whole-line '#' comments are skipped.
        //
        // The comment skip was missing, and its absence was load-bearing in the
        // worst way: trailing comments already worked (fromString splits with
        // limit = 3, so everything after the type is absorbed), so every
        // declaration file looked like it supported comments. The moment a
 // header block was added above the fields - which is this server's
        // convention everywhere else, and the only place the recovered addresses
        // that PROVE a layout can live - the loader tried to parse
        // "# ClientProt 107 = MOVE_GAMECLICK." as a field and threw
        // "no codec for field type 'ClientProt'", killing boot before the first
        // packet was registered.
        //
        // Stripping the headers instead would have "fixed" it by deleting the
        // evidence, which is the wrong trade in a project where a layout is only
        // as good as the address behind it.
        val fields = Files.readAllLines(packetPath)
            .mapIndexed { i, line -> (i + 1) to line }
            .filter { it.second.isNotBlank() && !it.second.trimStart().startsWith("#") }
            .map { (lineNo, line) -> PacketFieldDeclaration.fromString(line, "$packetPath:$lineNo") }
            .toTypedArray()

        val codec = constructor.call(fields)

        register(side, name, clazz, codec)
    }

    /**
     * This build's opcode for [name] on [side], or null if the table has none.
     *
     * The boxed, deprecated fastutil `get` is deliberate and was verified rather
     * than assumed: it returns null for an absent key, where the primitive
     * `getInt` would return the default 0 - and 0 is a legal opcode. Extracted
     * so both register() overloads decide "unmapped" the same way; the
     * declaration-file overload used to skip this check entirely and then
     * mislabel the result.
     */
    private fun opcodeFor(side: Side, name: String): Int? {
        @Suppress("DEPRECATION")
        return (if (side == Side.CLIENT) OpenNXT.protocol.clientProtNames else OpenNXT.protocol.serverProtNames)
            .values[name]
    }

    fun <T : GamePacket> register(side: Side, name: String, clazz: KClass<T>, codec: GamePacketCodec<T>?) {
        val opcode = opcodeFor(side, name)

        if (opcode == null) {
            // A missing SERVER mapping used to be `throw NullPointerException(...)`,
            // which makes an INCOMPLETE protocol table indistinguishable from a
            // broken server: the process dies inside reload() with a stack trace
            // naming neither the build nor the table it was reading.
            //
            // That is the wrong severity for what it describes. Build 947's
            // name->opcode table is recovered from the client binary, and six of
            // twenty-three names are not pinned yet - three of them zero-byte
            // packets with no wire fingerprint to recover from at all. Refusing to
            // boot on that basis blocks everything that does NOT depend on the
            // missing six, including the whole login path, which is precisely what
            // needs exercising to learn what to recover next.
            //
            // Degrading is safe here, and that was checked rather than assumed:
            // ConnectedClient.write already handles a null registration by logging
            // and returning, and no `getRegistration(...)!!` site anywhere in the
            // tree names any of the unmapped packets. An unmapped packet is
            // therefore dropped with a warning at send time rather than taking the
            // server down at startup.
            unmapped.getOrPut(side) { sortedSetOf<String>() }.add(name)
            return
        }

        if (codec == null) {
            logger.warn { "Skipping registering packet $name on side $side: Codec is null" }
            return
        }

        val registration = Registration(name, opcode!!, clazz, codec)

        logger.info { "Registered packet ${clazz.simpleName} on side $side to opcode $opcode with codec ${codec::class.simpleName}" }

        if (side == Side.CLIENT) {
            clientProtByClass[clazz] = registration
            clientProtByOpcode[opcode] = registration
        } else {
            serverProtByClass[clazz] = registration
            serverProtByOpcode[opcode] = registration
        }
    }

    /**
     * Register [opcode] as an packet: framing known, meaning not.
     *
     * This is the one registration path that does NOT go through the
     * name->opcode table, because by definition these opcodes have no name -
     * and giving them one is exactly the mistake this exists to avoid. The
     * synthetic registration name is "OBSERVED_$opcode" so that anything
     * printing a registration name cannot be mistaken for having identified it.
     *
     * Three things it deliberately does:
     *
     *  1. REFUSES to shadow a named packet. If [opcode] already registered
     *     above, the name wins and this says so out loud. That is what makes
     *     naming an opcode later a one-line change: add the name, and the
     * observed entry announces itself as redundant instead of silently
     *     overriding or being overridden depending on call order.
     *  2. CHECKS the size table. An opcode with no size entry can never be
     *     framed - GamePacketFraming closes the channel on one - so registering
     *     a codec for it would be dead code that reads as coverage.
     * 3. Does NOT populate the class map. Every observed opcode shares one
     *     class, so a class->registration entry could only name whichever
     *     opcode registered last, and outbound writes would then send packets
     *     under the wrong opcode. [com.opennxt.net.ConnectedClient.write]
     *     routes this class by the packet's own opcode instead.
     */
    fun registerObserved(side: Side, opcode: Int) {
        val existing = if (side == Side.CLIENT) clientProtByOpcode[opcode] else serverProtByOpcode[opcode]
        if (existing != null) {
            logger.warn {
                "Opcode $opcode on side $side is listed as an observed/unidentified packet, but it is " +
                    "already registered as '${existing.name}'. Keeping '${existing.name}' and dropping the " +
                    "observed entry - remove $opcode from ObservedClientPacket.OPCODES."
            }
            return
        }

        val sizes = if (side == Side.CLIENT) OpenNXT.protocol.clientProtSizes else OpenNXT.protocol.serverProtSizes
        if (!sizes.values.containsKey(opcode)) {
            logger.warn {
                "Opcode $opcode on side $side has no entry in the size table for build " +
                    "${OpenNXT.protocol.effectiveBuild}, so it can never be framed; not registering it as observed."
            }
            return
        }

        val registration = Registration(
            "OBSERVED_$opcode", opcode, ObservedClientPacket::class, ObservedClientPacket.Codec(opcode)
        )

        if (side == Side.CLIENT) clientProtByOpcode[opcode] = registration
        else serverProtByOpcode[opcode] = registration

        observed.getOrPut(side) { sortedSetOf<Int>() }.add(opcode)
    }

    /** Opcodes consumed as observed blobs on [side], for the coverage report. */
    private val observed = HashMap<Side, java.util.SortedSet<Int>>()

    fun observedOpcodes(side: Side): Set<Int> = observed[side] ?: emptySet()

    /**
     * What the inbound census should print for [opcode] on [side]. Exactly one
     * of three answers, and the distinction between them is the point:
     *
     *   a real name    - there is a codec and a field layout; the frame was
     *                    interpreted.
     * -ONLY - framing is known, meaning is NOT. The bytes were
     *                    consumed verbatim and nothing was concluded from them.
     *   UNMAPPED       - no registration at all; ConnectedClient dropped it.
     *
     * -ONLY deliberately does NOT leak the synthetic "OBSERVED_$opcode"
     * registration name into the log. That name exists so that nothing printing
     * a registration name can be mistaken for having identified the packet, and
     * a census line reading `name=OBSERVED_102` would read, at a glance, as a
     * name. The opcode is on the same line already.
     */
    fun describeOpcode(side: Side, opcode: Int): String {
        val registration = getRegistration(side, opcode) ?: return "UNMAPPED"
        return if (registration.name.startsWith(OBSERVED_PREFIX)) "-ONLY" else registration.name
    }

    /** True when [opcode] is registered as a framing-known/meaning-unknown blob. */
    fun isObserved(side: Side, opcode: Int): Boolean =
        getRegistration(side, opcode)?.name?.startsWith(OBSERVED_PREFIX) == true

    /** Prefix of the synthetic name [registerObserved] mints. */
    const val OBSERVED_PREFIX = "OBSERVED_"

    /**
     * Every registration on [side], opcode order. Exposed so a tool can ask the
     * registry what it actually holds instead of re-deriving it from the .toml
     * files - the split-brain this class has already been burned by twice.
     */
    fun registrations(side: Side): List<Registration> {
        val map = if (side == Side.CLIENT) clientProtByOpcode else serverProtByOpcode
        return map.values.sortedBy { it.opcode }
    }

    /**
     * How the 949 prot table declares [opcode]'s size, as words rather than as
     * a negative number whose meaning is a framing convention: >=0 is a fixed
     * width, -1 is a one-byte length prefix, -2 is a two-byte one.
     */
    fun sizeLabel(side: Side, opcode: Int): String {
        val sizes = if (side == Side.CLIENT) OpenNXT.protocol.clientProtSizes else OpenNXT.protocol.serverProtSizes
        if (!sizes.values.containsKey(opcode)) return "NO SIZE ENTRY"
        return when (val size = sizes.values.get(opcode)) {
            -1 -> "var-byte"
            -2 -> "var-short"
            else -> "size $size"
        }
    }

    fun reload() {
        unmapped.clear()
        missingDeclaration.clear()
        observed.clear()
        clientProtByOpcode.clear()
        clientProtByClass.clear()
        serverProtByClass.clear()
        serverProtByOpcode.clear()

        register(Side.SERVER, "UPDATE_STAT", UpdateStat::class, UpdateStat.Codec::class)
        register(Side.SERVER, "VARP_SMALL", VarpSmall::class, VarpSmall.Codec::class)
        register(Side.SERVER, "VARP_LARGE", VarpLarge::class, VarpLarge.Codec::class)

 // ----: eleven serverprots read out of the client's own
        // handlers via the runtime dispatch table, not carried from another
        // client's descriptor declares. See data/prot949/ for evidence and the
        // negative controls, and data/prot/949/serverProt/<NAME>.txt for the
        // field declaration each of these codecs is driven by.
        //
        // NOT a capability claim: registering a packet means the server CAN
        // frame it correctly. Whether the client does anything visible with it
        // is what the ::prottest command exists to find out.
        register(Side.SERVER, "VARBIT_SMALL", VarbitSmall::class, VarbitSmall.Codec::class)
        register(Side.SERVER, "VARBIT_LARGE", VarbitLarge::class, VarbitLarge.Codec::class)
        register(Side.SERVER, "CLIENT_SETVARCBIT_SMALL", ClientSetvarcbitSmall::class, ClientSetvarcbitSmall.Codec::class)
        register(Side.SERVER, "CLIENT_SETVARCBIT_LARGE", ClientSetvarcbitLarge::class, ClientSetvarcbitLarge.Codec::class)
        register(Side.SERVER, "IF_SETCOLOUR", IfSetcolour::class, IfSetcolour.Codec::class)
        register(Side.SERVER, "IF_MOVESUB", IfMovesub::class, IfMovesub.Codec::class)
        register(Side.SERVER, "MIDI_SONG", MidiSong::class, MidiSong.Codec::class)
        register(Side.SERVER, "MIDI_SONG_STOP", MidiSongStop::class, EmptyPacketCodec(MidiSongStop))
        register(Side.SERVER, "SOUND_GROUP_STOP", SoundGroupStop::class, SoundGroupStop.Codec::class)
        register(Side.SERVER, "SOUND_GROUP_RELEASE", SoundGroupRelease::class, SoundGroupRelease.Codec::class)
        register(Side.SERVER, "SOUND_MIXBUSS_SETVOLUME", SoundMixbussSetvolume::class, SoundMixbussSetvolume.Codec::class)
        register(
            Side.SERVER,
            "RESET_CLIENT_VARCACHE",
            ResetClientVarcache::class,
            EmptyPacketCodec(ResetClientVarcache)
        )
        register(Side.SERVER, "CLIENT_SETVARC_SMALL", ClientSetvarcSmall::class, ClientSetvarcSmall.Codec::class)
        register(Side.SERVER, "CLIENT_SETVARC_LARGE", ClientSetvarcLarge::class, ClientSetvarcLarge.Codec::class)
        register(Side.SERVER, "NO_TIMEOUT", NoTimeout::class, EmptyPacketCodec(NoTimeout))
        // login - CAM_RESET (106) and RESET_ANIMS (46) - registered so WorldPlayer
        // can send them under -Dopennxt.experiment.ui.loginSingles=true.
        register(Side.SERVER, "CAM_RESET", com.opennxt.net.game.serverprot.CamReset::class,
            EmptyPacketCodec(com.opennxt.net.game.serverprot.CamReset))
        register(Side.SERVER, "RESET_ANIMS", com.opennxt.net.game.serverprot.ResetAnims::class,
            EmptyPacketCodec(com.opennxt.net.game.serverprot.ResetAnims))
        register(Side.SERVER, "RUNCLIENTSCRIPT", RunClientScript::class, RunClientScript.Codec)
        register(
            Side.SERVER,
            "CLIENT_SETVARCSTR_SMALL",
            ClientSetvarcstrSmall::class,
            ClientSetvarcstrSmall.Codec::class
        )
        register(
            Side.SERVER,
            "CLIENT_SETVARCSTR_LARGE",
            ClientSetvarcstrLarge::class,
            ClientSetvarcstrLarge.Codec::class
        )
        register(Side.SERVER, "WORLDLIST_FETCH_REPLY", WorldListFetchReply::class, WorldListFetchReply.Codec)
        register(Side.SERVER, "IF_OPENTOP", IfOpenTop::class, IfOpenTop.Codec::class)
        register(Side.SERVER, "IF_OPENSUB", IfOpenSub::class, IfOpenSub.Codec::class)
 //: opcode 71, the target-information panel's mount. Hand-written codec
        // (no declaration file exists) - see IfOpensubActiveNpc's KDoc for the two observed
        // 25-byte shapes it is replayed from and what is constant in them.
        register(Side.SERVER, "IF_OPENSUB_ACTIVE_NPC", IfOpensubActiveNpc::class, IfOpensubActiveNpc.Codec)
        register(Side.SERVER, "IF_CLOSESUB", IfClosesub::class, IfClosesub.Codec::class)
        register(Side.SERVER, "IF_SETEVENTS", IfSetevents::class, IfSetevents.Codec::class)
        register(Side.SERVER, "IF_SETTEXT", IfSettext::class, IfSettext.Codec::class)
        register(Side.SERVER, "IF_SETHIDE", IfSethide::class, IfSethide.Codec::class)
        // The attribute-4 family - see data/prot/949/serverProt/_ATTR4_COMMON.md.
 // RENAMED from the structural placeholders IF_MODEL_K1 / K2 /
        // K3_SELF / K5_SELF once the property-record KINDs were read from the
        // client (notes/PROTOCOL-HELD-SETS-ordered.md): K=1 obj/def model,
        // K=2 NPC head, K=3 the local player's HEAD, K=5 the local player's
        // MODEL. Class names keep the K numbers; the wire names are 919's.
        register(Side.SERVER, "IF_SETMODEL", IfModelK1::class, IfModelK1.Codec::class)
        register(Side.SERVER, "IF_SETNPCHEAD", IfModelK2::class, IfModelK2.Codec::class)
        register(Side.SERVER, "IF_SETPLAYERHEAD", IfModelK3Self::class, IfModelK3Self.Codec::class)
        register(Side.SERVER, "IF_SETPLAYERMODEL_SELF", IfModelK5Self::class, IfModelK5Self.Codec::class)
        register(
            Side.SERVER,
            "CHAT_FILTER_SETTINGS_PRIVATECHAT",
            ChatFilterSettingsPrivatechat::class,
            ChatFilterSettingsPrivatechat.Codec::class
        )
        register(Side.SERVER, "FRIENDLIST_LOADED", FriendlistLoaded::class, EmptyPacketCodec(FriendlistLoaded))
        register(Side.SERVER, "MESSAGE_GAME", MessageGame::class, MessageGame.Codec)
        register(Side.SERVER, "CONSOLE_FEEDBACK", ConsoleFeedback::class, ConsoleFeedback.Codec)
        register(Side.SERVER, "REBUILD_NORMAL", RebuildNormal::class, RebuildNormal.Codec::class)
        register(Side.SERVER, "SERVER_TICK_END", ServerTickEnd::class, EmptyPacketCodec(ServerTickEnd))
        register(Side.SERVER, "SET_MAP_FLAG", SetMapFlag::class, SetMapFlag.Codec::class)

        // The INVENTORY / CONTAINER family (949: 8, 43, 10). Names are already
        // in data/prot/949/serverProtNames.toml; layouts, and the address in
        // the client binary proving each field, are in each class's kdoc.
        //
        // Two of the three take a codec INSTANCE rather than a codec CLASS, and
        // that is not a style choice. The four-argument overload above reads a
        // flat `.txt` field list and hands it to DynamicGamePacketCodec, which
        // walks those fields once, in order. UPDATE_INV_FULL and
        // UPDATE_INV_PARTIAL each contain a per-entry loop, a length escape on
        // `amount`, and - on PARTIAL - a field that is present or absent
        // depending on another field's value. A flat declaration cannot say any
        // of that. It would not fail either: it would encode a DIFFERENT packet
        // and nothing at either end would report it. So those two are
        // hand-written and deliberately have NO `.txt`.
        //
        // UPDATE_INV_STOP_TRANSMIT is genuinely flat (ubytec + ushort128 = the
        // size table's 3 bytes) and uses the declaration path like everything
        // else. Its field names must stay `flags`, `inv`.
        register(Side.SERVER, "UPDATE_INV_FULL", UpdateInvFull::class, UpdateInvFull.Codec)
        register(Side.SERVER, "UPDATE_INV_PARTIAL", UpdateInvPartial::class, UpdateInvPartial.Codec)
        register(
            Side.SERVER,
            "UPDATE_INV_STOP_TRANSMIT",
            UpdateInvStopTransmit::class,
            UpdateInvStopTransmit.Codec::class
        )

        // The WORLD-CONTENT / ZONE family (949: 126, 28, 41, 14, 202, 157, 62,
        // 1, 47). Names come from data/prot/949/serverProtNames.toml; the
        // layouts, and the client address proving each field, are in the
        // matching data/prot/949/serverProt/*.txt and restated in each class's
        // kdoc.
        //
        // All nine are FLAT and use the declaration path, unlike
        // UPDATE_INV_FULL/PARTIAL above: there is no loop, no length escape and
        // no conditional field anywhere in them, so a flat field list says
        // exactly what the packet is.
        //
        // ORDER OF USE, not of registration: 126 is a CURSOR. It sets three
        // globals and returns, and every OBJ_* and LOC_*
        // packet reads them. So one of the two framing packets must IMMEDIATELY
        // precede any content packet, and content for two different zones must
        // never be interleaved between one cursor and its packets. 28 is the
        // same cursor plus a destructive clear of the whole 8x8 zone, so
        // sending it where 126 was meant deletes whatever was already there.
        //
        // WHAT IS DELIBERATELY NOT HERE, and how each would fail SILENTLY:
        //
        //   UPDATE_ZONE_PARTIAL_ENCLOSED (109) and LOC_CUSTOMISE (23) contain
        //     loops - 109 dispatches through a 22-entry zone sub-protocol table
        // to end of payload. A flat declaration
        //     cannot express that and would encode a DIFFERENT packet, with
        //     nothing at either end reporting it. Neither has a .txt.
        //   OBJ_ADD_949 (112) reads its 3-byte id as [mid, high, low]
        //, a byte order
        //     PacketFieldDeclaration.Codecs cannot express. It has no .txt for
        //     exactly that reason and must not be given one - it is registered
        //     below with a HAND-WRITTEN codec instead, the same way
        //     UPDATE_INV_FULL/PARTIAL are, so the byte order lives in Kotlin
        //     where it can be written correctly.
        //   MAP_PROJANIM_HALFSQ (168), MAP_ANIM (173) and LOC_ANIM_SPECIFIC
        //     (160) are NAMED but have no recovered field layout at all.
        //
        //   The two remaining declarable _949 twins (186, 211) are omitted
 // because they are not needed. '_949' is this server's suffix, not a Jagex
        //   name, and it marks the member of a pair whose obj id is 3 bytes
        //   instead of 2; the served cache's max item id is 60906 over 60,617
        // rows (re-measured in this pass), so the 16-bit forms cover every
        //   item that exists. Registering an unnecessary wider twin would give
        //   a future caller two ways to send the same thing and no reason to
        //   prefer either.
        register(
            Side.SERVER,
            "UPDATE_ZONE_PARTIAL_FOLLOWS",
            UpdateZonePartialFollows::class,
            UpdateZonePartialFollows.Codec::class
        )
        register(
            Side.SERVER,
            "UPDATE_ZONE_FULL_FOLLOWS",
            UpdateZoneFullFollows::class,
            UpdateZoneFullFollows.Codec::class
        )
        register(Side.SERVER, "OBJ_ADD", ObjAdd::class, ObjAdd.Codec::class)
        register(Side.SERVER, "OBJ_DEL", ObjDel::class, ObjDel.Codec::class)
        // OBJ_DEL_949 (949 opcode 12, 4 bytes) - the 3-byte-obj-id twin of
        // OBJ_DEL above. Same sink and the same argument roles; the id is a
        // umedium here where 14 uses a ushort. Declaration order is id then
        // coord, the reverse of OBJ_DEL, and that is the wire order.
        register(Side.SERVER, "OBJ_DEL_949", ObjDel949::class, ObjDel949.Codec::class)
        // OBJ_ADD_949 (949 opcode 112, 6 bytes). Hand-written, not declared:
        // see ObjAdd949's own doc and the note above for why its id byte order
        // cannot go in a .txt. Registering it costs nothing and means a cache
        // whose item ids outgrow 16 bits has a correct encoder already there.
        register(Side.SERVER, "OBJ_ADD_949", ObjAdd949::class, ObjAdd949.Codec)
        register(Side.SERVER, "OBJ_COUNT", ObjCount::class, ObjCount.Codec::class)
        // OBJ_COUNT_949 (949 opcode 186, 8 bytes) - the 3-byte-obj-id twin of
        // OBJ_COUNT above, same sink. coord/id/oldCount/newCount = 1+3+2+2.
        register(Side.SERVER, "OBJ_COUNT_949", ObjCount949::class, ObjCount949.Codec::class)
        register(Side.SERVER, "OBJ_REVEAL", ObjReveal::class, ObjReveal.Codec::class)
        // OBJ_REVEAL_949 (949 opcode 211, 8 bytes) - the 3-byte-obj-id twin of
        // OBJ_REVEAL above. Declaration order is count/id/coord/receiver,
        // which differs from ObjReveal's constructor; that is the wire order.
        register(Side.SERVER, "OBJ_REVEAL_949", ObjReveal949::class, ObjReveal949.Codec::class)
        register(Side.SERVER, "LOC_ADD_CHANGE", LocAddChange::class, LocAddChange.Codec::class)
        register(Side.SERVER, "LOC_DEL", LocDel::class, LocDel.Codec::class)
        register(Side.SERVER, "LOC_ANIM", LocAnim::class, LocAnim.Codec::class)

        // MESSAGE_PUBLIC outbound (949 opcode 33). Hand-written and .txt-less for
        // the same reason as the inbound half - a Huffman body is not a flat
        // field - and named `MessagePublicOut` only because its ClientProt
        // counterpart shares the protocol name and both packages are
        // star-imported at the top of this file.
        register(Side.SERVER, "MESSAGE_PUBLIC", MessagePublicOut::class, MessagePublicOut.Codec)

 // SOUND_MIXBUSS_ADD (949 opcode 217 since the rename; it sat
        // on 206 before, which is really a stop-sound-by-id op, now SOUND_STOP).
        // bus / parent / gain, three big-endian ushorts = the size table's 6.
        register(Side.SERVER, "SOUND_MIXBUSS_ADD", SoundMixbussAdd::class, SoundMixbussAdd.Codec::class)

        register(Side.CLIENT, "NO_TIMEOUT", NoTimeout::class, EmptyPacketCodec(NoTimeout))
        register(Side.CLIENT, "CLIENT_CHEAT", ClientCheat::class, ClientCheat.Codec::class)
        register(Side.CLIENT, "WORLDLIST_FETCH", WorldlistFetch::class, WorldlistFetch.Codec::class)
        register(Side.CLIENT, "MOVE_GAMECLICK", MoveGameClick::class, MoveGameClick.Codec::class)
 //: opcode 66, the minimap twin of MOVE_GAMECLICK (same builder
        //, mode 1, 13 extra constant/camera bytes - see
        // data/prot/949/clientProt/MOVE_MINIMAPCLICK.txt). Was DROPPED with
        // "no registered codec" until now.
        register(Side.CLIENT, "MOVE_MINIMAPCLICK", MoveMinimapClick::class, MoveMinimapClick.Codec::class)
        register(Side.CLIENT, "WINDOW_STATUS", WindowStatus::class, WindowStatus.Codec::class)
        register(Side.CLIENT, "IF_BUTTON1", IfButton1::class, IfButton1.Codec::class)

        // The other nine interface-click opcodes - the right-click menu rows.
        // Names and opcodes come from clientProtNames.toml (extracted from the
        // client binary); the field layout comes from the declaration files in
        // data/prot/949/clientProt/, which is where the one genuinely open
        // question about them lives. See IfButtonN's class doc.
        register(Side.CLIENT, "IF_BUTTON2", IfButton2::class, IfButton2.Codec::class)
        register(Side.CLIENT, "IF_BUTTON3", IfButton3::class, IfButton3.Codec::class)
        register(Side.CLIENT, "IF_BUTTON4", IfButton4::class, IfButton4.Codec::class)
        register(Side.CLIENT, "IF_BUTTON5", IfButton5::class, IfButton5.Codec::class)
        register(Side.CLIENT, "IF_BUTTON6", IfButton6::class, IfButton6.Codec::class)
        register(Side.CLIENT, "IF_BUTTON7", IfButton7::class, IfButton7.Codec::class)
        register(Side.CLIENT, "IF_BUTTON8", IfButton8::class, IfButton8.Codec::class)
        register(Side.CLIENT, "IF_BUTTON9", IfButton9::class, IfButton9.Codec::class)
        register(Side.CLIENT, "IF_BUTTON10", IfButton10::class, IfButton10.Codec::class)

        // ClientProt 102 - the ELEVENTH member of the interface-click family,
        // and the reason a click can produce no IF_BUTTON at all.
        //
        // The dispatcher forks on
        // strlen(componentDef + 0x130): empty goes to the IF_BUTTON builder
        //, non-empty goes to and sends THIS instead.
        // 1184:15 - the dialogue continue arrow - has "Continue" there, so
        // before this line the server armed the mask correctly, the client
        // accepted the click correctly, and the frame was dropped as an
        // unregistered opcode. Not a mis-decode: no observation at all.
        //
        // The NAME is AUTHORED and clientProtNames.toml says so at length; the
        // opcode, the size and all five fields are measured off the client.
        // toml row, size row, declaration field names AND types, this
        // registration, and a byte-exact round trip - and carries the negative
        // controls. See IfButtonLabelled.PROVENANCE.
        register(Side.CLIENT, "IF_BUTTON_LABELLED", IfButtonLabelled::class, IfButtonLabelled.Codec::class)

        // Clicks on scenery - the six loc menu options (949: 41, 70, 33, 91, 1,
        // id, tile) pairs that exist verbatim in rs3.sqlite's map_loc, with the
        // no-transform and the axis-swapped controls both missing. The other
        // five share one builder body with 41, so the LAYOUT carries over; what
        //
        // Six classes for six opcodes, not one class with an option field: the
        // class map below is keyed by KClass and would keep only the last.
        register(Side.CLIENT, "OPLOC1", OpLoc1::class, OpLoc1.Codec::class)
        register(Side.CLIENT, "OPLOC2", OpLoc2::class, OpLoc2.Codec::class)
        register(Side.CLIENT, "OPLOC3", OpLoc3::class, OpLoc3.Codec::class)
        register(Side.CLIENT, "OPLOC4", OpLoc4::class, OpLoc4.Codec::class)
        register(Side.CLIENT, "OPLOC5", OpLoc5::class, OpLoc5.Codec::class)
        register(Side.CLIENT, "OPLOC6", OpLoc6::class, OpLoc6.Codec::class)

        // Clicks on an NPC, on another player and on a ground object - the three
        // entity menu families (949: 60/78/39/53/75/65,
        // 130/79/140/103/59/96/26/122/80/54, 28/64/143/43/45/68).
        //
        // The two size-3 families were previously reported as indistinguishable.
        // What separates them is not in the builders - both write "an entity
        // index and the ctrl bit" and neither touches an entity list - but in
        // the static menu-row objects, which carry an ENTITY-KIND TAG at +0x44
        // that the client's own consumers resolve through [client+0x198f0] (the
        // list NPC_INFO's handler drives) or [client+0x19910] (PLAYER_INFO's).
        // See the class docs and data/prot/949/clientProtNames.toml.
        //
        // them has ever been seen on the wire. What IS checked is that the
        // declarations parse, that each layout's width equals the size table's,
        // that twenty-two opcodes resolve to twenty-two distinct classes, and
        // that the two MIRRORED size-3 layouts really do disagree - the one
        // mistake here that three bytes on the wire could never reveal. See
        register(Side.CLIENT, "OPNPC1", OpNpc1::class, OpNpc1.Codec::class)
        register(Side.CLIENT, "OPNPC2", OpNpc2::class, OpNpc2.Codec::class)
        register(Side.CLIENT, "OPNPC3", OpNpc3::class, OpNpc3.Codec::class)
        register(Side.CLIENT, "OPNPC4", OpNpc4::class, OpNpc4.Codec::class)
        register(Side.CLIENT, "OPNPC5", OpNpc5::class, OpNpc5.Codec::class)
        register(Side.CLIENT, "OPNPC6", OpNpc6::class, OpNpc6.Codec::class)

        register(Side.CLIENT, "OPPLAYER1", OpPlayer1::class, OpPlayer1.Codec::class)
        register(Side.CLIENT, "OPPLAYER2", OpPlayer2::class, OpPlayer2.Codec::class)
        register(Side.CLIENT, "OPPLAYER3", OpPlayer3::class, OpPlayer3.Codec::class)
        register(Side.CLIENT, "OPPLAYER4", OpPlayer4::class, OpPlayer4.Codec::class)
        register(Side.CLIENT, "OPPLAYER5", OpPlayer5::class, OpPlayer5.Codec::class)
        register(Side.CLIENT, "OPPLAYER6", OpPlayer6::class, OpPlayer6.Codec::class)
        register(Side.CLIENT, "OPPLAYER7", OpPlayer7::class, OpPlayer7.Codec::class)
        register(Side.CLIENT, "OPPLAYER8", OpPlayer8::class, OpPlayer8.Codec::class)
        register(Side.CLIENT, "OPPLAYER9", OpPlayer9::class, OpPlayer9.Codec::class)
        register(Side.CLIENT, "OPPLAYER10", OpPlayer10::class, OpPlayer10.Codec::class)

        register(Side.CLIENT, "OPOBJ1", OpObj1::class, OpObj1.Codec::class)
        register(Side.CLIENT, "OPOBJ2", OpObj2::class, OpObj2.Codec::class)
        register(Side.CLIENT, "OPOBJ3", OpObj3::class, OpObj3.Codec::class)
        register(Side.CLIENT, "OPOBJ4", OpObj4::class, OpObj4.Codec::class)
        register(Side.CLIENT, "OPOBJ5", OpObj5::class, OpObj5.Codec::class)
        register(Side.CLIENT, "OPOBJ6", OpObj6::class, OpObj6.Codec::class)

        // Client-side state reports. Both were consumed as observed blobs until
        register(Side.CLIENT, "EVENT_APPLET_FOCUS", EventAppletFocus::class, EventAppletFocus.Codec::class)
        register(Side.CLIENT, "EVENT_CAMERA_POSITION", EventCameraPosition::class, EventCameraPosition.Codec::class)

        // EVENT_MOUSE_CLICK (949 opcode 2, fixed 6). THE REGISTRATION GAP THIS
 // FILE USED TO REPORT ON EVERY BOOT, closed.
        //
        // reportUnmapped() below warned "REGISTRATION GAP, not a knowledge gap:
        // ... 2=EVENT_MOUSE_CLICK", because `2 = "EVENT_MOUSE_CLICK"` has been
 // in data/prot/949/clientProtNames.toml since and
        // clientProt/EVENT_MOUSE_CLICK.txt has carried all three fields with a
        // per-field instruction address for just as long. Nothing here is
        // recovered knowledge: the name, the opcode, the size and the layout
        // were all already on disk, and the only thing missing was this line
        // plus the class it names.
        //
        // Re-confirmed on a fresh observation before it was wired - four opcode-2
        // frames from run server-20260827-135435, each agreeing byte for byte
        // with the opcode-16 frame it paired with in the same tick, across two
        // independently recovered byte orders. See EventMouseClick's kdoc for
        // the frames and the controls.
        //
        // Registering it does NOT make anything react to a click: the handler
        // stores the position and counts. See EventMouseClickHandler for why a
        // bare (x, y) must not be turned into an interaction.
        register(Side.CLIENT, "EVENT_MOUSE_CLICK", EventMouseClick::class, EventMouseClick.Codec::class)

        // Keyboard event batch (949 opcode 77, var-short). Uses a raw-bytes
        // codec because the field layout is not fully confirmed. The handler
        // intercepts VK_ESCAPE (0x1B) and closes modal interfaces as a fallback
        // for the CS2 setopkey keybind chain that is not yet functional.
        register(Side.CLIENT, "EVENT_KEYBOARD", EventKeyboard::class, EventKeyboard.Codec())
 // The interface-settings save loop. 105 carries the client's own varc state
        // and 200 acknowledges it; see VarcTransmit's KDoc for the layout and its provenance.
        // 105 leaves ObservedClientPacket.OPCODES in the same change - a named packet and an
        // observed entry for one opcode cannot coexist, and registerObserved refuses it by name.
        register(Side.CLIENT, "VARC_TRANSMIT", VarcTransmit::class, VarcTransmit.Codec)
        register(Side.SERVER, "STORE_SERVERPERM_VARCS_ACK", StoreServerpermVarcsAck::class, StoreServerpermVarcsAck.Codec)

        // The CHAT family (949 client: 35, 71, 21; 949 server: 33).
        //
        // TWO OF THE THREE CLIENT PACKETS TAKE A CODEC INSTANCE, and for the same
        // reason UPDATE_INV_FULL does. MESSAGE_PUBLIC and MESSAGE_PRIVATE carry a
        // Huffman-compressed body - `[smart charCount][bitstream]`, where the
        // BYTE length of the field is a function of the DECODED contents - and
        // there is no flat `name type` line that can say that. A declaration
        // would not fail either: it would decode a different packet and leave
        // bytes on the floor. So neither has a .txt, on purpose.
        //
        // CHAT_SETMODE genuinely is flat (ubyte + ubyte = the size table's 2) and
        // uses the declaration path like everything else; its field names must
        // stay `mode`, `arg`.
        //
        // Registration is NOT gated on -Dopennxt.experiment.chat. The switch
        // turns off behaviour (see PublicChat), not framing: an unregistered
        // opcode 35 is dropped by ConnectedClient with a warning per occurrence,
        // which reads as a protocol gap rather than as a switch someone threw.
        register(Side.CLIENT, "MESSAGE_PUBLIC", MessagePublic::class, MessagePublic.Codec)
        register(Side.CLIENT, "MESSAGE_PRIVATE", MessagePrivate::class, MessagePrivate.Codec)
        register(Side.CLIENT, "CHAT_SETMODE", ChatSetMode::class, ChatSetMode.Codec::class)

        // Opcodes whose framing is known and whose meaning is not. Registered
        // LAST, so that anything named above wins and this can say so.
 //: every packet that is DECLARED in data/prot/949 but has no
        // hand-written class above is registered here through a generated class
        // (tools/949/gen_packet_classes.py -> */generated/*.kt). Same register()
        // overload, same name table, same unmapped/no-declaration reporting - the
        // only difference is who wrote the data class. Runs BEFORE the observed
        // list so a newly named opcode wins and the observed entry announces
        // itself as redundant (see registerObserved).
        GeneratedRegistrations.registerAll()

        ObservedClientPacket.OPCODES.forEach { registerObserved(Side.CLIENT, it) }

        reportUnmapped()
    }

    /**
     * State the coverage of the protocol table out loud, once.
     *
     * The point is that "registered 17 packets" and "registered 23 packets" look
     * identical in a boot log unless something says so. Anything listed here is
     * a packet the server physically cannot send on this build, so a feature
     * that depends on it will not work and will say nothing at the time beyond a
     * single warning from ConnectedClient.write.
     */
    private fun reportUnmapped() {
        val server = unmapped[Side.SERVER] ?: emptySet<String>()
        val client = unmapped[Side.CLIENT] ?: emptySet<String>()
        val noDeclServer = missingDeclaration[Side.SERVER] ?: emptySet<String>()
        val noDeclClient = missingDeclaration[Side.CLIENT] ?: emptySet<String>()

        logger.info {
            "Protocol coverage for build ${OpenNXT.config.build}: " +
                "${serverProtByOpcode.size} server packets registered (${server.size} unmapped), " +
                "${clientProtByOpcode.size} client packets registered (${client.size} unmapped)"
        }

        // Say the observed count separately from the registered count. They are
        // both "registered" as far as the maps are concerned, and that is
        // precisely why the line above must not be allowed to imply that N
        // packets are UNDERSTOOD.
        val observedClient = observed[Side.CLIENT] ?: emptySet<Int>()
        if (observedClient.isNotEmpty()) {
            // With the DECLARED SIZE next to each opcode, because that is the
            // one fact about an unidentified packet that is not a guess, and it
            // is what decides whether a given opcode could carry a given
            // payload at all. An interface click on this build is 9 bytes
            // (IF_BUTTON1..10) or 8 (IF_BUTTON_LABELLED, var-byte): an opcode
            // declared "size 3" cannot be one, and saying so requires the size
            // to be on the same line as the opcode.
            logger.info {
                "Of those, ${observedClient.size} client opcodes are ONLY - NO CODEC IS REGISTERED " +
                    "here, so their bytes are consumed verbatim and nothing is concluded from them: " +
                    observedClient.joinToString(", ") { "$it(${sizeLabel(Side.CLIENT, it)})" }
            }
            // MEANING UNKNOWN, consumed and sampled but not interpreted", and
            // that overstated the ignorance by a wide margin against this
            // project's own files.
            //
            // data/prot/949/clientProtNames.toml carries an EXACT, tiered
            // layout for ten of the eleven - "DELIBERATELY NOT NAMED, although
            // each layout below is now exact" are its words - with builder
            // addresses, field widths and byte order for 4, 16, 77, 98, 105,
            // 113, 114, 133 and 146. Only opcode 89 is genuinely bare ("one BE
            // int from [obj+0x10]", no referent).
            //
            // And opcode 2 was worse than overstated, it was FALSE: that file
            // and data/prot/949/clientProt/EVENT_MOUSE_CLICK.txt declares its
            // three fields. What opcode 2 lacked was a Kotlin class and a
            // register() call in this file - a registration gap, not a
            // knowledge gap - so it fell through to registerObserved() and was
            // then described in the log as meaningless.
            //
 // CLOSED. EventMouseClick + EventMouseClickHandler exist,
            // the register() call is above, and 2 came off
            // ObservedClientPacket.OPCODES in the same change, so this warning
            // now has NOTHING to report on build 949 and the block below does
            // not print. That is the intended end state, not a silenced alarm:
            // the moment another named-but-unregistered opcode appears it prints
            // that wiring opcode 2 up would TRIP the check rather than leave
            // this comment to rot - it did, and those assertions were flipped to
            // pin the repaired state the same way opcode 77's were.
            //
            // The distinction the warning draws is not cosmetic. "We do not
            // know what this is" and "we know exactly what this is and have not
            // wired it up" send an operator to two different places.
            val clientNamesByOpcode = OpenNXT.protocol.clientProtNames.reversedValues()
            val namedButObserved = observedClient.filter { clientNamesByOpcode.containsKey(it) }
            if (namedButObserved.isNotEmpty()) {
                logger.warn {
                    "REGISTRATION GAP, not a knowledge gap: ${namedButObserved.size} of those opcodes ALREADY " +
                        "HAVE A NAME in this build's clientProtNames.toml and are still consumed as " +
                        "unidentified blobs, because no class is registered for them here: " +
                        namedButObserved.joinToString(", ") { "$it=${clientNamesByOpcode[it]}" }
                }
            }
        }

        if (server.isEmpty() && client.isEmpty() && noDeclServer.isEmpty() && noDeclClient.isEmpty()) return

        logger.warn { "-------------------------------------------------------------" }
        logger.warn { " INCOMPLETE PROTOCOL TABLE for build ${OpenNXT.config.build}." }
        logger.warn { " These packets have no name -> opcode mapping, so the server" }
        logger.warn { " CANNOT SEND OR RECEIVE them. Anything depending on one will" }
        logger.warn { " silently do nothing:" }
        if (server.isNotEmpty()) logger.warn { "   no opcode  (server): ${server.joinToString(", ")}" }
        if (client.isNotEmpty()) logger.warn { "   no opcode  (client): ${client.joinToString(", ")}" }
        if (noDeclServer.isNotEmpty()) logger.warn { "   no fields  (server): ${noDeclServer.joinToString(", ")}" }
        if (noDeclClient.isNotEmpty()) logger.warn { "   no fields  (client): ${noDeclClient.joinToString(", ")}" }
        logger.warn { "" }
        logger.warn { " This is expected while a build's tables are still being" }
        logger.warn { " recovered. It is NOT a reason the server failed to start." }
        logger.warn { "-------------------------------------------------------------" }
    }


    fun getRegistration(side: Side, opcode: Int): Registration? {
        return if (side == Side.CLIENT) {
            clientProtByOpcode[opcode]
        } else {
            serverProtByOpcode[opcode]
        }
    }

    fun getRegistration(side: Side, clazz: KClass<*>): Registration? {
        return if (side == Side.CLIENT) {
            clientProtByClass[clazz]
        } else {
            serverProtByClass[clazz]
        }
    }
}