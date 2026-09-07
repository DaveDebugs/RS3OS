package com.opennxt.model.lobby

import com.opennxt.OpenNXT
import com.opennxt.net.ConnectedClient
import com.opennxt.net.game.serverprot.variables.VarpSmall
import com.opennxt.net.game.serverprot.variables.VarpLarge
import io.netty.channel.Channel
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import mu.KotlinLogging

object DefaultVariables {
    fun populateServerpermVarcs(values: MutableMap<Int, Int>) {
        values[5139] = -2146664148
        values[5140] = 2048
        values[4120] = 620756991
        values[5154] = -2146959360
        values[5155] = 82668
        values[4646] = -2129022716
        values[4647] = 16777216
        values[5160] = 1597647
        values[5161] = 16777215
        values[4154] = 377032
        values[4155] = 10186752
        values[5192] = -2147176332
        values[5193] = 2048
        values[4684] = -2147360683
        values[4685] = 3452927
        values[4705] = -2147237488
        values[4706] = 2048
        values[4723] = 1597647
        values[4724] = 16777215
        values[6277] = 1638676
        values[6278] = 8388608
        values[3721] = 100992003
        values[3722] = -13304057
        values[3723] = 4198400
        values[6296] = 65535
        values[4764] = -2146090718
        values[4765] = 8392703
        values[4254] = -2147352464
        values[6302] = -1
        values[4255] = 1075
        values[6304] = -2146041344
        values[6305] = 8390656
        values[6323] = -2146336488
        values[6324] = 200
        values[3769] = 385875968
        values[3770] = 436207616
        values[4794] = -1
        values[4798] = -2146377458
        values[4813] = -2146897680
        values[4814] = 1805193
        values[5840] = 1597647
        values[5841] = 16777215
        values[4322] = 1597647
        values[4323] = 16777215
        values[4324] = 1597647
        values[4325] = 16777215
        values[3825] = -2146827988
        values[3826] = 2048
        values[6417] = -2147098374
        values[6418] = 13493424
        values[2852] = 319951120
        values[2853] = 387323156
        values[2854] = 454695192
        values[2855] = 572588031
        values[6439] = -2146254592
        values[2856] = 319951120
        values[2857] = -236
        values[2858] = -42497
        values[2859] = -268435456
        values[2860] = 1023
        values[2862] = -1
        values[2863] = 691470335
        values[2864] = 642008373
        values[2865] = -13162457
        values[5937] = 16
        values[2866] = -1
        values[2867] = 589579832
        values[2868] = 842019105
        values[2869] = 33646952
        values[6457] = -2145025248
        values[6458] = 8390656
        values[5947] = -2147098374
        values[5948] = 13493424
        values[4939] = -2146135748
        values[4954] = -2130378572
        values[4955] = 16780678
        values[2912] = 32
        values[2913] = -2130706433
        values[2914] = 8390656
        values[2915] = -2147155691
        values[2916] = 16777215
        values[2917] = -2146635569
        values[2918] = 4095
        values[2919] = -2130394606
        values[2920] = 29310976
        values[2921] = -2146041326
        values[2922] = 352317440
        values[2923] = -2146008881
        values[2924] = 83886079
        values[2925] = -2144451968
        values[2926] = 2048
        values[2927] = 318767104
        values[2928] = 352321536
        values[2929] = 335544320
        values[2930] = 369098752
        values[2931] = 352321536
        values[2932] = 385875968
        values[2933] = 50331648
        values[6005] = 1597647
        values[2934] = 16777216
        values[6006] = 16777215
        values[2935] = -2146885110
        values[2936] = 15155700
        values[2937] = -2146041344
        values[2938] = 8390656
        values[2939] = 1597647
        values[2940] = 16777215
        values[2941] = 1597647
        values[2942] = 16777215
        values[2943] = 287005
        values[2944] = 8388608
        values[2945] = 1597647
        values[2946] = 16777215
        values[2947] = 1597647
        values[2948] = 16777215
        values[2949] = 1597647
        values[4997] = -1
        values[4998] = -1
        values[2950] = 16777215
        values[2951] = 1597647
        values[4999] = -1
        values[5000] = -1
        values[2952] = 16777215
        values[2953] = 1597647
        values[5001] = -1
        values[5002] = -1
        values[2954] = 16777215
        values[2955] = 1102022
        values[5003] = -1
        values[2956] = 16777215
        values[5004] = -1
        values[2957] = 436207616
        values[5005] = -1
        values[5006] = -1
        values[2959] = 1597647
        values[5007] = -1
        values[2960] = 16777215
        values[5008] = -1
        values[4496] = 128
        values[2961] = 1597647
        values[5009] = -1
        values[2962] = 16777215
        values[5010] = -1
        values[2963] = 369098752
        values[2964] = 402653184
        values[2965] = 1597647
        values[5014] = -2130550254
        values[2966] = 16777215
        values[2967] = 1597647
        values[5015] = 28426240
        values[5016] = -2130550254
        values[2968] = 16777215
        values[2969] = 1597647
        values[5017] = 27836416
        values[5018] = -2112843700
        values[2970] = 16777215
        values[2971] = 1597647
        values[5019] = 47685109
        values[5020] = -2112843700
        values[2972] = 16777215
        values[2973] = -2147098374
        values[5021] = 47684947
        values[2974] = 10187906
        values[4510] = -51969
        values[6046] = -2147110729
        values[2975] = -2147348352
        values[4511] = 402653184
        values[6047] = 4196352
        values[2976] = 5607423
        values[4512] = 167772160
        values[2977] = -2146115221
        values[4513] = -2146827988
        values[2978] = 2048
        values[4514] = 8390656
        values[2979] = -2146664148
        values[4515] = 1597647
        values[2980] = 4196352
        values[4516] = 16777215
        values[2981] = -2146303776
        values[2982] = 8392703
        values[2983] = -2147348326
        values[2984] = 3073
        values[2985] = 1597647
        values[2986] = 16777215
        values[2987] = 307380
        values[2988] = 1805193
        values[2989] = -2147299198
        values[2990] = 8388608
        values[2991] = -2145025360
        values[2992] = 8390656
        values[2993] = -2146807458
        values[2994] = 4196352
        values[2995] = 2360096
        values[2996] = 8390656
        values[6102] = -1
        values[5079] = -1
        values[6103] = -1
        values[6104] = 1597647
        values[5081] = 1597647
        values[6105] = 16777215
        values[6106] = 1597647
        values[5082] = 16777215
        values[6107] = 16777215
        values[6108] = 1597647
        values[6109] = 16777215
        values[6110] = 1597647
        values[6111] = 16777215
        values[6112] = 1597647
        values[6113] = 16777215
        values[6114] = 1597647
        values[6115] = 16777215
        values[6116] = 1597647
        values[6117] = 16777215
        values[6118] = 1597647
        values[6119] = 16777215
    }

    /**
     * Replay the recorded varp dump.
     *
     * @param limit if non-null, send only the first [limit] varps. Halving it
     * @return how many were actually written, so the caller logs the truth
     */
    private val logger = KotlinLogging.logger { }

    /**
     * The varp ids this build's cache actually defines.
     *
     * WHY THIS EXISTS - it is a crash fix, not a tidiness measure.
     */
    private val definedVarpIds: IntOpenHashSet? by lazy {
        try {
            val table = OpenNXT.filesystem.getReferenceTable(2)
                ?: throw IllegalStateException("no reference table for index 2")
            val archive = table.loadArchive(VARPLAYER_GROUP)
                ?: throw IllegalStateException("index 2 has no group $VARPLAYER_GROUP")
            val ids = IntOpenHashSet(archive.files.keys)
            if (ids.isEmpty()) throw IllegalStateException("group $VARPLAYER_GROUP decoded to zero files")
            logger.info { "Varp guard: ${ids.size} varplayer definitions read from cache index 2 group $VARPLAYER_GROUP" }
            ids
        } catch (t: Throwable) {
            // Deliberately NOT a silent fallback to "allow everything". Allowing
            // everything is the behaviour that crashes the client, so a failure
            // here has to be loud and has to still block the ids we know are
            // fatal on this pair.
            logger.error(t) {
                "Varp guard: could not read varplayer definitions from the cache. " +
                    "Falling back to the six ids measured as undefined on build 949 " +
                    "(${KNOWN_UNDEFINED_949.joinToString(", ")}). If you are running a " +
                    "different cache, that list is NOT authoritative and the client may crash."
            }
            null
        }
    }

    /** Cache index 2 group id holding the varplayer definitions. */
    private const val VARPLAYER_GROUP = 60

    /**
     * Fallback only. The ids the 949 cache does not define, out of the 1,385 in
     * the replay below. Derived by reading all 13,267 varplayer definitions -
     * classified by hand.
     */
    private val KNOWN_UNDEFINED_949 = intArrayOf(142, 688, 1238, 1455, 3928, 5776)

    /**
     * true when this varp is safe to send at all - i.e. the client can resolve it.
     */
    fun varpIsDefined(id: Int): Boolean {
        val defined = definedVarpIds
        return if (defined != null) defined.contains(id) else id !in KNOWN_UNDEFINED_949
    }

    /**
     * The replay's varp 3680 is 0x400B9801 (1074501633); bit 21 = varbit 27169, the
     * client's LIVE-HUD switch (see WorldPlayer's ui.varbit27169 block).
     */
    fun varbit27169At(): String =
        (System.getProperty("opennxt.experiment.ui.varbit27169.at") ?: "both").lowercase().let {
            if (it in setOf("late", "early", "both")) it else {
                logger.warn { "opennxt.experiment.ui.varbit27169.at=$it is not late|early|both; using both" }
                "both"
            }
        }

    fun varp3680Default(): Int {
        val base = 1074501633
 // DEFAULT FLIPPED TO OFF evening (was ON since 08-29, table-early since
        // 08-30 morning): with the bit CLEAR plus the day's other fixes (relayout, panelApply
        // late, varp ordering) the client assembles the WINDOWED interface - titled
        // MINIMAP/ALL CHAT/BACKPACK/WORN EQUIPMENT/SKILLS windows, populated, and measured
        // INTERACTIVE: left-click, drag, resize and tab switching all work (run 150925 +
        // the operator's report). With the bit SET the modern HUD drew but the bar family broke and
        // left-click was dead. `=true` restores the modern-HUD experiment.
        val on = System.getProperty("opennxt.experiment.ui.varbit27169") == "true"
        return if (on && varbit27169At() != "late") base or (1 shl 21) else base
    }

    fun sendDefaultVarps(channel: ConnectedClient, limit: Int? = null, delayMs: Long = 0L): Int {
        var sent = 0
        var skipped = 0
        val cap = limit ?: Int.MAX_VALUE
        fun w(p: com.opennxt.net.game.GamePacket): Boolean {
            if (sent >= cap) return false

            // Drop varps this cache has no definition for. See [definedVarpIds]:
            // the client has no null check on the lookup miss and dies on the
            // spot, so this is the difference between a lobby and a crash dump.
            val id = when (p) {
                is VarpSmall -> p.id
                is VarpLarge -> p.id
                else -> -1
            }
            if (id >= 0 && !varpIsDefined(id)) {
                skipped++
                logger.warn { "Varp guard: skipping undefined varp id $id (this cache has no definition; sending it crashes the client)" }
                return true
            }

            // Stop the moment the client is gone. Without this the replay keeps
            // writing into a dead channel for the rest of its 14 seconds, and
            // the last SEND line - the whole point of the spaced replay - ends
            // up being whichever varp the loop reached, not the one the client
            // was looking at when it died.
            if (!channel.channel.isActive) throw IllegalStateException("client disconnected after $sent varps")
            channel.write(p); sent++
            if (delayMs > 0) Thread.sleep(delayMs)
            return true
        }
        if (!w(VarpSmall(0, 28))) return sent
 // VARP 3 WAS A MAXED ACCOUNT'S VALUE.
        //
        // This table is a replay of one real login, and that account had most of the world
        // unlocked. varp 3 carries the LODESTONE bitfield (varbits 28..40 are its low bits), and
        // the replayed -1807741724 = 0x94400CE4 sets varbits 30, 33, 34, 35, 38, 39 - Burthorpe,
        // Edgeville, Falador, Lumbridge, Taverley and VARROCK - i.e. it tells every fresh account
        // that six lodestones are already activated.
        //
        // A lodestone is a LOC MORPH: the map places an unnamed parent (69840 for Varrock) whose
        // morph switches on that varbit, `varbit == 0 -> 'Activate'`, anything else -> the
        // already-activated branch whose only option is 'Info'. So the operator's report - "couldn't
        // activate Varrock lodestone", with a menu reading Walk here / Info / Examine - was this
        // recording channel and decodes the varp off the encoded frame rather than trusting a literal.
        //
        // 167772164 = 0x0A000004 is what a REAL FRESH LOGIN sends, identical across four sessions
        // (11-50-01, 15-40-06, 16-21-50, 16-35-56): varbits 30 and 41 only, i.e. Burthorpe alone,
        // which is the one the reference client gives you at Taverley's tutorial.
        //
        // NOT PERSISTED YET: an activation is account state and belongs in PlayerSave next to
        // `toolbelt`. Until it is, activating a lodestone lasts the session. Known limitation.
        if (!w(VarpLarge(3, 167772164))) return sent
        if (!w(VarpLarge(20, 83886080))) return sent
        if (!w(VarpLarge(25, 4194304))) return sent
        if (!w(VarpLarge(26, 252))) return sent
        if (!w(VarpSmall(27, -1))) return sent
        if (!w(VarpLarge(37, -494730441))) return sent
        if (!w(VarpLarge(38, -1768566388))) return sent
        if (!w(VarpLarge(39, 638849540))) return sent
        if (!w(VarpLarge(40, 6770560))) return sent
        if (!w(VarpLarge(41, -2015981016))) return sent
        if (!w(VarpLarge(42, -771718037))) return sent
        if (!w(VarpLarge(43, 272))) return sent
        if (!w(VarpLarge(45, 515))) return sent
        if (!w(VarpLarge(46, 1342177280))) return sent
        if (!w(VarpLarge(47, 1076953098))) return sent
        if (!w(VarpLarge(48, 34079488))) return sent
        if (!w(VarpLarge(49, 406847488))) return sent
        if (!w(VarpLarge(51, -2013251072))) return sent
        if (!w(VarpLarge(52, 536870916))) return sent
        if (!w(VarpLarge(53, 2540))) return sent
        if (!w(VarpLarge(54, 2048))) return sent
        if (!w(VarpLarge(55, 336068608))) return sent
        if (!w(VarpLarge(56, 67174408))) return sent
        if (!w(VarpLarge(57, 1024))) return sent
        if (!w(VarpLarge(58, 25165824))) return sent
        if (!w(VarpLarge(59, -2144919552))) return sent
        if (!w(VarpLarge(60, 1073743875))) return sent
        if (!w(VarpLarge(61, 1177747455))) return sent
        if (!w(VarpLarge(62, 65535))) return sent
        if (!w(VarpLarge(63, 65535))) return sent
        if (!w(VarpLarge(65, 102770696))) return sent
        if (!w(VarpLarge(66, 393216))) return sent
        if (!w(VarpLarge(67, 40632322))) return sent
        if (!w(VarpLarge(68, -267915008))) return sent
        if (!w(VarpLarge(69, 3670023))) return sent
        if (!w(VarpLarge(70, -536852992))) return sent
        if (!w(VarpLarge(71, 1551))) return sent
        if (!w(VarpLarge(76, 1073741823))) return sent
        if (!w(VarpLarge(77, 2147483647))) return sent
        if (!w(VarpLarge(78, 2147483647))) return sent
        if (!w(VarpLarge(79, 2147483647))) return sent
        if (!w(VarpLarge(80, 1073741823))) return sent
        if (!w(VarpLarge(81, 1073741823))) return sent
        if (!w(VarpSmall(85, -1))) return sent
        if (!w(VarpLarge(94, 470351903))) return sent
        if (!w(VarpSmall(97, -1))) return sent
        if (!w(VarpLarge(108, -2147483648))) return sent
        if (!w(VarpLarge(110, 134217728))) return sent
        if (!w(VarpLarge(111, 531))) return sent
        if (!w(VarpLarge(115, 4672))) return sent
        if (!w(VarpSmall(120, -1))) return sent
        if (!w(VarpSmall(135, -1))) return sent
        if (!w(VarpSmall(142, -1))) return sent
        if (!w(VarpLarge(144, 327906))) return sent
        if (!w(VarpLarge(145, 1381))) return sent
        if (!w(VarpLarge(146, 2138))) return sent
        if (!w(VarpLarge(147, 1734))) return sent
        if (!w(VarpLarge(148, 1931))) return sent
        if (!w(VarpLarge(149, 1925))) return sent
        if (!w(VarpLarge(150, 1935))) return sent
        if (!w(VarpLarge(151, 6860))) return sent
        if (!w(VarpSmall(152, 90))) return sent
        if (!w(VarpSmall(153, 30))) return sent
        if (!w(VarpSmall(154, 68))) return sent
        if (!w(VarpSmall(155, 1))) return sent
        if (!w(VarpSmall(156, 10))) return sent
        if (!w(VarpSmall(157, 10))) return sent
        if (!w(VarpSmall(158, 1))) return sent
        if (!w(VarpSmall(159, 1))) return sent
        if (!w(VarpSmall(161, 0))) return sent
        if (!w(VarpSmall(183, 12))) return sent
        if (!w(VarpSmall(185, 18))) return sent
        if (!w(VarpSmall(186, -1))) return sent
        if (!w(VarpSmall(187, -1))) return sent
        if (!w(VarpSmall(188, -1))) return sent
        if (!w(VarpSmall(189, 6))) return sent
        if (!w(VarpSmall(190, 1))) return sent
        if (!w(VarpLarge(248, 35651618))) return sent
        if (!w(VarpSmall(249, -1))) return sent
        if (!w(VarpSmall(250, -1))) return sent
        if (!w(VarpSmall(260, -1))) return sent
        if (!w(VarpSmall(261, -1))) return sent
        if (!w(VarpSmall(262, -1))) return sent
        if (!w(VarpLarge(288, 524288))) return sent
        if (!w(VarpLarge(292, 67108864))) return sent
        if (!w(VarpLarge(295, 81928))) return sent
        if (!w(VarpSmall(299, -1))) return sent
        if (!w(VarpSmall(300, -1))) return sent
        if (!w(VarpSmall(304, -1))) return sent
        if (!w(VarpSmall(305, -1))) return sent
        if (!w(VarpLarge(307, 16384))) return sent
        if (!w(VarpLarge(424, -21954882))) return sent
        if (!w(VarpLarge(425, -166461790))) return sent
        if (!w(VarpSmall(429, -1))) return sent
        if (!w(VarpLarge(446, 2621440))) return sent
        if (!w(VarpLarge(449, 1024))) return sent
        if (!w(VarpSmall(452, 0))) return sent
        if (!w(VarpSmall(453, 0))) return sent
        if (!w(VarpSmall(454, 0))) return sent
        if (!w(VarpSmall(455, 0))) return sent
        if (!w(VarpSmall(456, 0))) return sent
        if (!w(VarpSmall(457, 1))) return sent
        if (!w(VarpLarge(458, 65536))) return sent
        if (!w(VarpLarge(459, 10645))) return sent
        if (!w(VarpSmall(460, 0))) return sent
        if (!w(VarpSmall(463, 1))) return sent
        if (!w(VarpSmall(476, -1))) return sent
        if (!w(VarpSmall(616, -1))) return sent
        if (!w(VarpSmall(623, -1))) return sent
        if (!w(VarpLarge(627, 1527))) return sent
        if (!w(VarpLarge(659, 78645600))) return sent
        if (!w(VarpSmall(674, 21))) return sent
        if (!w(VarpLarge(682, 58720288))) return sent
        if (!w(VarpSmall(686, -1))) return sent
        if (!w(VarpSmall(687, -1))) return sent
        if (!w(VarpSmall(688, -1))) return sent
        if (!w(VarpSmall(698, -1))) return sent
        if (!w(VarpSmall(699, -1))) return sent
        if (!w(VarpSmall(700, -1))) return sent
        if (!w(VarpLarge(723, 128))) return sent
        if (!w(VarpSmall(739, 14))) return sent
        if (!w(VarpSmall(811, -1))) return sent
        if (!w(VarpSmall(812, -1))) return sent
        if (!w(VarpSmall(813, -1))) return sent
        if (!w(VarpSmall(814, -1))) return sent
        if (!w(VarpSmall(815, -1))) return sent
        if (!w(VarpSmall(816, -1))) return sent
        if (!w(VarpSmall(817, -1))) return sent
        if (!w(VarpSmall(818, -1))) return sent
        if (!w(VarpSmall(819, -1))) return sent
        if (!w(VarpSmall(820, -1))) return sent
        if (!w(VarpSmall(821, -1))) return sent
        if (!w(VarpSmall(822, -1))) return sent
        if (!w(VarpSmall(823, -1))) return sent
        if (!w(VarpSmall(824, -1))) return sent
        if (!w(VarpSmall(825, -1))) return sent
        if (!w(VarpSmall(826, -1))) return sent
        if (!w(VarpSmall(827, -1))) return sent
        if (!w(VarpSmall(828, -1))) return sent
        if (!w(VarpSmall(829, -1))) return sent
        if (!w(VarpSmall(830, -1))) return sent
        if (!w(VarpSmall(831, -1))) return sent
        if (!w(VarpSmall(832, -1))) return sent
        if (!w(VarpSmall(833, -1))) return sent
        if (!w(VarpSmall(834, -1))) return sent
        if (!w(VarpSmall(835, -1))) return sent
        if (!w(VarpSmall(836, -1))) return sent
        if (!w(VarpSmall(837, -1))) return sent
        if (!w(VarpSmall(838, -1))) return sent
        if (!w(VarpSmall(839, -1))) return sent
        if (!w(VarpSmall(840, -1))) return sent
        if (!w(VarpSmall(841, -1))) return sent
        if (!w(VarpSmall(842, -1))) return sent
        if (!w(VarpSmall(843, -1))) return sent
        if (!w(VarpSmall(844, -1))) return sent
        if (!w(VarpSmall(845, -1))) return sent
        if (!w(VarpSmall(846, -1))) return sent
        if (!w(VarpSmall(847, -1))) return sent
        if (!w(VarpSmall(848, -1))) return sent
        if (!w(VarpSmall(849, -1))) return sent
        if (!w(VarpSmall(850, -1))) return sent
        if (!w(VarpSmall(851, -1))) return sent
        if (!w(VarpSmall(852, -1))) return sent
        if (!w(VarpSmall(853, -1))) return sent
        if (!w(VarpSmall(854, -1))) return sent
        if (!w(VarpSmall(855, -1))) return sent
        if (!w(VarpSmall(856, -1))) return sent
        if (!w(VarpSmall(857, -1))) return sent
        if (!w(VarpSmall(858, -1))) return sent
        if (!w(VarpSmall(859, -1))) return sent
        if (!w(VarpSmall(860, -1))) return sent
        if (!w(VarpSmall(861, -1))) return sent
        if (!w(VarpSmall(862, -1))) return sent
        if (!w(VarpSmall(863, -1))) return sent
        if (!w(VarpSmall(864, -1))) return sent
        if (!w(VarpSmall(865, -1))) return sent
        if (!w(VarpSmall(866, -1))) return sent
        if (!w(VarpSmall(867, -1))) return sent
        if (!w(VarpSmall(868, -1))) return sent
        if (!w(VarpSmall(869, -1))) return sent
        if (!w(VarpSmall(870, -1))) return sent
        if (!w(VarpSmall(871, -1))) return sent
        if (!w(VarpSmall(872, -1))) return sent
        if (!w(VarpSmall(873, -1))) return sent
        if (!w(VarpSmall(874, -1))) return sent
        if (!w(VarpSmall(875, -1))) return sent
        if (!w(VarpSmall(876, -1))) return sent
        if (!w(VarpSmall(877, -1))) return sent
        if (!w(VarpSmall(878, -1))) return sent
        if (!w(VarpSmall(879, -1))) return sent
        if (!w(VarpSmall(880, -1))) return sent
        if (!w(VarpSmall(881, -1))) return sent
        if (!w(VarpSmall(882, -1))) return sent
        if (!w(VarpSmall(883, -1))) return sent
        if (!w(VarpSmall(884, -1))) return sent
        if (!w(VarpSmall(885, -1))) return sent
        if (!w(VarpSmall(886, -1))) return sent
        if (!w(VarpSmall(887, -1))) return sent
        if (!w(VarpSmall(888, -1))) return sent
        if (!w(VarpSmall(889, -1))) return sent
        if (!w(VarpSmall(890, -1))) return sent
        if (!w(VarpSmall(891, -1))) return sent
        if (!w(VarpSmall(892, -1))) return sent
        if (!w(VarpSmall(893, -1))) return sent
        if (!w(VarpSmall(894, -1))) return sent
        if (!w(VarpSmall(1069, -1))) return sent
        if (!w(VarpSmall(1071, 1))) return sent
        if (!w(VarpLarge(1074, -2147483648))) return sent
        if (!w(VarpLarge(1075, 46497792))) return sent
        if (!w(VarpLarge(1091, 3145729))) return sent
        if (!w(VarpLarge(1092, 2048))) return sent
        if (!w(VarpSmall(1094, 1))) return sent
        if (!w(VarpLarge(1096, -2080374784))) return sent
        if (!w(VarpSmall(1097, 21))) return sent
        if (!w(VarpLarge(1098, 606))) return sent
        if (!w(VarpLarge(1099, 268436108))) return sent
        if (!w(VarpSmall(1101, -1))) return sent
        if (!w(VarpLarge(1102, 1048559))) return sent
        if (!w(VarpLarge(1103, 137101209))) return sent
        if (!w(VarpSmall(1104, -1))) return sent
        if (!w(VarpSmall(1106, -1))) return sent
        if (!w(VarpLarge(1107, 32289))) return sent
        if (!w(VarpSmall(1168, -1))) return sent
        if (!w(VarpSmall(1169, -1))) return sent
        if (!w(VarpSmall(1170, -1))) return sent
        if (!w(VarpLarge(1172, 12047))) return sent
        if (!w(VarpLarge(1173, 536870912))) return sent
        if (!w(VarpSmall(1175, -1))) return sent
        if (!w(VarpSmall(1191, 35))) return sent
        if (!w(VarpSmall(1238, -1))) return sent
        if (!w(VarpLarge(1258, 56304))) return sent
        if (!w(VarpLarge(1261, 32768))) return sent
        if (!w(VarpLarge(1262, 1228223091))) return sent
        if (!w(VarpLarge(1263, 8395776))) return sent
        if (!w(VarpLarge(1264, 1176505600))) return sent
        if (!w(VarpLarge(1265, 1342015029))) return sent
        if (!w(VarpLarge(1267, 524288))) return sent
        if (!w(VarpSmall(1272, 9))) return sent
        if (!w(VarpSmall(1273, 2))) return sent
        if (!w(VarpLarge(1295, 1000))) return sent
        if (!w(VarpSmall(1297, 2))) return sent
        if (!w(VarpSmall(1302, -1))) return sent
        if (!w(VarpLarge(1305, 335545856))) return sent
        if (!w(VarpLarge(1357, 67108864))) return sent
        if (!w(VarpSmall(1384, -1))) return sent
        if (!w(VarpLarge(1408, 4194304))) return sent
        if (!w(VarpLarge(1409, 7035))) return sent
        if (!w(VarpLarge(1415, 33554432))) return sent
        if (!w(VarpSmall(1442, -1))) return sent
        if (!w(VarpLarge(1444, 134224778))) return sent
        if (!w(VarpLarge(1448, 4194304))) return sent
        if (!w(VarpLarge(1449, -1341384536))) return sent
        if (!w(VarpLarge(1450, -2147479551))) return sent
        if (!w(VarpLarge(1451, 1083179008))) return sent
        if (!w(VarpLarge(1452, 4972506))) return sent
        if (!w(VarpLarge(1453, 1710222592))) return sent
        if (!w(VarpSmall(1455, -1))) return sent
        if (!w(VarpLarge(1457, 19890025))) return sent
        if (!w(VarpLarge(1480, -2147483648))) return sent
        if (!w(VarpSmall(1499, -1))) return sent
        if (!w(VarpSmall(1500, -1))) return sent
        if (!w(VarpSmall(1501, -1))) return sent
        if (!w(VarpSmall(1502, -1))) return sent
        if (!w(VarpSmall(1503, -1))) return sent
        if (!w(VarpSmall(1504, -1))) return sent
        if (!w(VarpSmall(1506, -1))) return sent
        if (!w(VarpSmall(1507, -1))) return sent
        if (!w(VarpSmall(1508, -1))) return sent
        if (!w(VarpSmall(1509, -1))) return sent
        if (!w(VarpSmall(1510, -1))) return sent
        if (!w(VarpLarge(1569, 262144))) return sent
        if (!w(VarpSmall(1581, 14))) return sent
        if (!w(VarpSmall(1602, -1))) return sent
        if (!w(VarpSmall(1604, -1))) return sent
        if (!w(VarpSmall(1608, -1))) return sent
        if (!w(VarpSmall(1609, -1))) return sent
        if (!w(VarpLarge(1618, 4096))) return sent
        if (!w(VarpSmall(1661, -1))) return sent
        if (!w(VarpSmall(1697, -1))) return sent
        if (!w(VarpSmall(1701, -1))) return sent
        if (!w(VarpSmall(1720, -1))) return sent
        if (!w(VarpLarge(1748, 161))) return sent
        if (!w(VarpLarge(1749, 5955))) return sent
        if (!w(VarpLarge(1750, -16912800))) return sent
        if (!w(VarpLarge(1751, 9891237))) return sent
        if (!w(VarpLarge(1752, 10153256))) return sent
        if (!w(VarpLarge(1753, 161))) return sent
        if (!w(VarpSmall(1754, 16))) return sent
        if (!w(VarpSmall(1760, -1))) return sent
        if (!w(VarpSmall(1761, -1))) return sent
        if (!w(VarpSmall(1762, -1))) return sent
        if (!w(VarpSmall(1763, -1))) return sent
        if (!w(VarpSmall(1764, -1))) return sent
        if (!w(VarpSmall(1765, -1))) return sent
        if (!w(VarpSmall(1766, -1))) return sent
        if (!w(VarpSmall(1767, -1))) return sent
        if (!w(VarpLarge(1772, 753695))) return sent
        if (!w(VarpLarge(1773, 524296))) return sent
        if (!w(VarpLarge(1774, 67121160))) return sent
        if (!w(VarpLarge(1775, 524288))) return sent
        if (!w(VarpLarge(1776, 4608))) return sent
        if (!w(VarpLarge(1779, 234881024))) return sent
        if (!w(VarpLarge(1780, 18022400))) return sent
        if (!w(VarpSmall(1784, -1))) return sent
        if (!w(VarpLarge(1785, 51914))) return sent
        if (!w(VarpSmall(1787, 60))) return sent
        if (!w(VarpLarge(1789, 436900))) return sent
        if (!w(VarpSmall(1793, -1))) return sent
        if (!w(VarpSmall(1831, -1))) return sent
        if (!w(VarpLarge(2006, 1073741824))) return sent
        if (!w(VarpSmall(2088, -1))) return sent
        if (!w(VarpLarge(2091, 14680064))) return sent
        if (!w(VarpLarge(2092, 131072))) return sent
        if (!w(VarpLarge(2141, 536870912))) return sent
        if (!w(VarpLarge(2145, 2097152))) return sent
        if (!w(VarpLarge(2170, 24064))) return sent
        if (!w(VarpLarge(2193, 536870912))) return sent
        if (!w(VarpLarge(2195, -1610612736))) return sent
        if (!w(VarpSmall(2196, 8))) return sent
        if (!w(VarpLarge(2214, 268435456))) return sent
        if (!w(VarpLarge(2269, 268435456))) return sent
        if (!w(VarpSmall(2279, -1))) return sent
        if (!w(VarpSmall(2280, -1))) return sent
        if (!w(VarpSmall(2281, -1))) return sent
        if (!w(VarpSmall(2282, -1))) return sent
        if (!w(VarpSmall(2283, -1))) return sent
        if (!w(VarpSmall(2284, -1))) return sent
        if (!w(VarpSmall(2492, 2))) return sent
        if (!w(VarpSmall(2493, 48))) return sent
        if (!w(VarpLarge(2497, 536870912))) return sent
        if (!w(VarpSmall(2533, -1))) return sent
        if (!w(VarpSmall(2604, -1))) return sent
        if (!w(VarpSmall(2605, -1))) return sent
        if (!w(VarpSmall(2606, -1))) return sent
        if (!w(VarpSmall(2607, -1))) return sent
        if (!w(VarpSmall(2608, -1))) return sent
        if (!w(VarpSmall(2609, -1))) return sent
        if (!w(VarpSmall(2610, -1))) return sent
        if (!w(VarpSmall(2611, -1))) return sent
        if (!w(VarpSmall(2644, -1))) return sent
        if (!w(VarpSmall(2645, -1))) return sent
        if (!w(VarpSmall(2646, -1))) return sent
        if (!w(VarpSmall(2647, -1))) return sent
        if (!w(VarpSmall(2648, -1))) return sent
        if (!w(VarpSmall(2649, -1))) return sent
        if (!w(VarpSmall(2650, -1))) return sent
        if (!w(VarpSmall(2651, -1))) return sent
        if (!w(VarpSmall(2652, -1))) return sent
        if (!w(VarpSmall(2653, -1))) return sent
        if (!w(VarpSmall(2654, -1))) return sent
        if (!w(VarpSmall(2655, -1))) return sent
        if (!w(VarpSmall(2656, -1))) return sent
        if (!w(VarpSmall(2662, -1))) return sent
        if (!w(VarpLarge(2724, 27047710))) return sent
        if (!w(VarpSmall(2776, -1))) return sent
        if (!w(VarpSmall(2807, -1))) return sent
        if (!w(VarpLarge(2863, 385875968))) return sent
        if (!w(VarpLarge(2914, -2147483648))) return sent
        if (!w(VarpLarge(2937, 268435456))) return sent
        if (!w(VarpSmall(2947, -1))) return sent
        if (!w(VarpSmall(2956, -1))) return sent
        if (!w(VarpSmall(2957, -1))) return sent
        if (!w(VarpSmall(2958, -1))) return sent
        if (!w(VarpSmall(2975, -1))) return sent
        if (!w(VarpSmall(2985, -1))) return sent
        if (!w(VarpSmall(2999, -1))) return sent
        if (!w(VarpLarge(3000, 2056899890))) return sent
        if (!w(VarpLarge(3001, 2684520))) return sent
        if (!w(VarpLarge(3002, 262144))) return sent
        if (!w(VarpSmall(3013, -1))) return sent
        if (!w(VarpSmall(3014, -1))) return sent
        if (!w(VarpSmall(3015, -1))) return sent
        if (!w(VarpSmall(3016, -1))) return sent
        if (!w(VarpLarge(3022, 134217728))) return sent
        if (!w(VarpLarge(3043, 131072))) return sent
        if (!w(VarpSmall(3045, -1))) return sent
        if (!w(VarpLarge(3075, 1342279680))) return sent
        if (!w(VarpLarge(3076, 16384))) return sent
        if (!w(VarpLarge(3079, 7038))) return sent
        if (!w(VarpLarge(3083, 1073741824))) return sent
        if (!w(VarpLarge(3091, 16384))) return sent
        if (!w(VarpSmall(3092, 1))) return sent
        if (!w(VarpLarge(3093, 16384))) return sent
        if (!w(VarpLarge(3094, 536870913))) return sent
        if (!w(VarpSmall(3098, 1))) return sent
        if (!w(VarpLarge(3100, -2147483648))) return sent
        if (!w(VarpLarge(3103, 268435456))) return sent
        if (!w(VarpLarge(3120, 204))) return sent
        if (!w(VarpLarge(3121, 671744))) return sent
        if (!w(VarpLarge(3166, -2140631008))) return sent
        if (!w(VarpSmall(3170, -1))) return sent
        if (!w(VarpLarge(3184, 2049))) return sent
        if (!w(VarpSmall(3185, 4))) return sent
        if (!w(VarpSmall(3186, -1))) return sent
        if (!w(VarpSmall(3220, 4))) return sent
        if (!w(VarpSmall(3226, -1))) return sent
        if (!w(VarpLarge(3239, 7038))) return sent
        if (!w(VarpLarge(3240, 268435602))) return sent
        if (!w(VarpLarge(3241, 301990029))) return sent
        if (!w(VarpLarge(3242, 33554575))) return sent
        if (!w(VarpLarge(3243, 33554567))) return sent
        if (!w(VarpLarge(3244, 234881153))) return sent
        if (!w(VarpLarge(3249, 1073742913))) return sent
        if (!w(VarpSmall(3252, -1))) return sent
        if (!w(VarpSmall(3253, -1))) return sent
        if (!w(VarpSmall(3254, -1))) return sent
        if (!w(VarpSmall(3255, -1))) return sent
        if (!w(VarpSmall(3256, -1))) return sent
        if (!w(VarpSmall(3257, -1))) return sent
        if (!w(VarpSmall(3258, -1))) return sent
        if (!w(VarpLarge(3259, 1536))) return sent
        if (!w(VarpLarge(3260, 33554432))) return sent
        if (!w(VarpLarge(3261, 1048580))) return sent
        if (!w(VarpLarge(3262, 1073741824))) return sent
        if (!w(VarpLarge(3263, 448))) return sent
        if (!w(VarpLarge(3264, 536870912))) return sent
        if (!w(VarpLarge(3270, 10485760))) return sent
        if (!w(VarpLarge(3274, 65636))) return sent
        if (!w(VarpLarge(3276, 511305630))) return sent
        if (!w(VarpSmall(3277, 4))) return sent
        if (!w(VarpLarge(3294, 134217728))) return sent
        if (!w(VarpLarge(3311, 3000))) return sent
        if (!w(VarpLarge(3320, 3790))) return sent
        if (!w(VarpLarge(3324, 8160))) return sent
        if (!w(VarpLarge(3325, 758))) return sent
        if (!w(VarpSmall(3354, -1))) return sent
        if (!w(VarpSmall(3472, -1))) return sent
        if (!w(VarpSmall(3473, -1))) return sent
        if (!w(VarpLarge(3501, 805338709))) return sent
        if (!w(VarpLarge(3502, 1073741824))) return sent
        if (!w(VarpSmall(3506, -1))) return sent
        if (!w(VarpLarge(3515, 536870912))) return sent
        if (!w(VarpLarge(3516, 104857600))) return sent
        if (!w(VarpSmall(3524, 4))) return sent
        if (!w(VarpLarge(3535, 65536))) return sent
        if (!w(VarpSmall(3538, -1))) return sent
        if (!w(VarpSmall(3550, -1))) return sent
        if (!w(VarpLarge(3551, -1140850944))) return sent
        if (!w(VarpSmall(3573, -1))) return sent
        if (!w(VarpSmall(3574, -1))) return sent
        if (!w(VarpSmall(3620, 124))) return sent
        if (!w(VarpLarge(3624, 2048))) return sent
        if (!w(VarpLarge(3668, 1073741824))) return sent
        if (!w(VarpLarge(3680, varp3680Default()))) return sent
        if (!w(VarpLarge(3691, 262147))) return sent
        if (!w(VarpSmall(3695, -1))) return sent
        if (!w(VarpSmall(3696, -1))) return sent
        if (!w(VarpSmall(3697, -1))) return sent
        if (!w(VarpSmall(3698, -1))) return sent
        if (!w(VarpSmall(3699, -1))) return sent
        if (!w(VarpLarge(3700, -2147483648))) return sent
        if (!w(VarpLarge(3704, 1376256))) return sent
        if (!w(VarpLarge(3705, 3145728))) return sent
        if (!w(VarpLarge(3706, 128))) return sent
        if (!w(VarpLarge(3708, 214067203))) return sent
        if (!w(VarpLarge(3709, 4194577))) return sent
        if (!w(VarpSmall(3814, 24))) return sent
        if (!w(VarpLarge(3829, 2147328))) return sent
        if (!w(VarpLarge(3843, 1610678272))) return sent
        if (!w(VarpSmall(3857, -1))) return sent
        if (!w(VarpSmall(3858, -1))) return sent
        if (!w(VarpLarge(3859, 36992))) return sent
        if (!w(VarpSmall(3864, -1))) return sent
        if (!w(VarpSmall(3865, -1))) return sent
        if (!w(VarpSmall(3866, -1))) return sent
        if (!w(VarpSmall(3867, -1))) return sent
        if (!w(VarpLarge(3883, 98539522))) return sent
        if (!w(VarpLarge(3889, 12288))) return sent
        if (!w(VarpSmall(3890, -1))) return sent
        if (!w(VarpSmall(3906, -1))) return sent
        if (!w(VarpLarge(3919, 972800))) return sent
        if (!w(VarpLarge(3924, 960))) return sent
        if (!w(VarpLarge(3925, 16386))) return sent
        if (!w(VarpSmall(3928, -1))) return sent
        if (!w(VarpLarge(3930, 99960))) return sent
        if (!w(VarpLarge(3936, 194))) return sent
        if (!w(VarpSmall(3940, 64))) return sent
        if (!w(VarpLarge(3977, 1073741824))) return sent
        if (!w(VarpSmall(3986, -1))) return sent
        if (!w(VarpLarge(4013, 1000000))) return sent
        if (!w(VarpSmall(4050, 1))) return sent
        if (!w(VarpSmall(4051, 1))) return sent
        if (!w(VarpSmall(4052, 1))) return sent
        if (!w(VarpLarge(4053, 135168))) return sent
        if (!w(VarpLarge(4065, 23729))) return sent
        if (!w(VarpSmall(4066, 17))) return sent
        if (!w(VarpLarge(4067, 23736))) return sent
        if (!w(VarpSmall(4068, 20))) return sent
        if (!w(VarpLarge(4069, 30505))) return sent
        if (!w(VarpSmall(4070, 18))) return sent
        if (!w(VarpLarge(4071, 30528))) return sent
        if (!w(VarpSmall(4072, 17))) return sent
        if (!w(VarpLarge(4073, 30523))) return sent
        if (!w(VarpSmall(4074, 18))) return sent
        if (!w(VarpLarge(4075, 30541))) return sent
        if (!w(VarpSmall(4076, 17))) return sent
        if (!w(VarpLarge(4077, 13435))) return sent
        if (!w(VarpLarge(4078, 897))) return sent
        if (!w(VarpLarge(4079, 34528))) return sent
        if (!w(VarpLarge(4080, 801))) return sent
        if (!w(VarpLarge(4081, 30550))) return sent
        if (!w(VarpSmall(4082, 17))) return sent
        if (!w(VarpLarge(4083, 30534))) return sent
        if (!w(VarpSmall(4084, 17))) return sent
        if (!w(VarpLarge(4085, 30523))) return sent
        if (!w(VarpSmall(4086, 18))) return sent
        if (!w(VarpLarge(4087, 30546))) return sent
        if (!w(VarpSmall(4088, 17))) return sent
        if (!w(VarpLarge(4089, 30531))) return sent
        if (!w(VarpSmall(4090, 17))) return sent
        if (!w(VarpLarge(4091, 30547))) return sent
        if (!w(VarpSmall(4092, 17))) return sent
        if (!w(VarpLarge(4093, 23717))) return sent
        if (!w(VarpSmall(4094, 17))) return sent
        if (!w(VarpLarge(4095, 30550))) return sent
        if (!w(VarpSmall(4096, 17))) return sent
        if (!w(VarpLarge(4097, 30550))) return sent
        if (!w(VarpSmall(4098, 17))) return sent
        if (!w(VarpLarge(4099, 30523))) return sent
        if (!w(VarpSmall(4100, 18))) return sent
        if (!w(VarpLarge(4101, 30542))) return sent
        if (!w(VarpSmall(4102, 17))) return sent
        if (!w(VarpLarge(4103, 30545))) return sent
        if (!w(VarpSmall(4104, 17))) return sent
        if (!w(VarpLarge(4105, 30550))) return sent
        if (!w(VarpSmall(4106, 17))) return sent
        if (!w(VarpLarge(4107, 30533))) return sent
        // VARP 4108 IS THE 949 GAMEFRAME LAYOUT MODE. The observation says 17; 17 is
        // not a mode this build implements. -Dopennxt.experiment.ui.layoutMode=17
        // restores the recorded value, =<n> forces any other.
        //
        // The chain, read out of the cache and the client: component 1477:26 is a
        // canvas-sized root carrying trigger slot 37 (ON_LOAD) -> clientscript 8779
        // -> `193(0)` -> `8884(varp 4108)`. It is synchronous and needs no resize
        // event, so THE LAYOUT PASS DOES RUN, every login. `8884` implements modes
        // 5, 8, 9, 10, 11, 14 and 15, special-cases only 9 and 10, and defaults
        // only -1 to 8. Nothing between this send and 8884 corrects 17, because
        // script 435 assigns only when its argument is not -1.
        //
        // So the frame lays out in a mode that does not exist. That accounts for
        // every symptom at once, and for why nothing else worked: the mount
        // components were correct (1477:103 verified from cache bytes), the raw 919
        // layout was equally broken, the components exist and are armed and filled,
        // and the 3D scene renders perfectly - because none of those involve this
        // pass. 759 of 1477's 924 components are parent-relative or proportional,
        // so with no resolved rect their text lands at the accumulated parent
        // origin - which is the stray glyphs down the left edge of the screen.
        //
        // Note this varp sits in a run where EVERY even id in the block is 17
        // (4100..4114), i.e. it is recorded bulk, not a considered value.
        //
        // mode varp; 17 is unimplemented; nothing corrects it. NOT confirmed:
        // that 8885/8781 assign no geometry at all for an unknown mode - testing
        // this is cheaper than closing that gap by disassembly.
 // REVERTED to the recorded 17. Trying 8 was WRONG and the
        // reasoning above is superseded - kept only so the mistake is legible.
        //
        // 4108 is BOTH the mode and the layout index, and **17 is a valid,
        // cache-backed layout index** (clientscript 8701's switch). It is why
        // the panels are positioned correctly to the pixel today. Mode 8 instead
        // reads a varbit store (19333+, stride 7) that this server never writes,
        // and its per-panel fallback provably cannot fire because it requires
        // prevTab == -1, which a varbit cannot represent. Predicted effect of 8:
        // two panels (1002, 1003) collapse to 0x0 and the other 92 hide.
        //
        // The real defect is elsewhere and is now measured: of 1477's laid-out,
        // unhidden, trigger-carrying components, exactly EIGHT have bit 2 (0x04)
        // of `cd+0x31` CLEAR - 61, 67, 72, 77, 87, 92, 101 and 737, i.e. the
        // ribbon, three action bars, a vertical strip, the minimap and the
        // backpack panel. Everything that paints has that bit SET. Hit-testing
        // reads cd+0x50..0x5c and never looks at cd+0x31, which is why those
        // panels stay draggable and right-clickable while painting nothing.
        val layoutMode = System.getProperty("opennxt.experiment.ui.layoutMode")?.toIntOrNull() ?: 17
        if (!w(VarpSmall(4108, layoutMode))) return sent
        if (!w(VarpLarge(4109, 30535))) return sent
        if (!w(VarpSmall(4110, 17))) return sent
        if (!w(VarpLarge(4111, 30550))) return sent
        if (!w(VarpSmall(4112, 17))) return sent
        if (!w(VarpLarge(4113, 30550))) return sent
        if (!w(VarpSmall(4114, 17))) return sent
        if (!w(VarpLarge(4115, 29921))) return sent
        if (!w(VarpSmall(4116, 19))) return sent
        if (!w(VarpSmall(4117, -1))) return sent
        if (!w(VarpLarge(4119, 27235))) return sent
        if (!w(VarpSmall(4120, 18))) return sent
        if (!w(VarpLarge(4121, 37845))) return sent
        if (!w(VarpSmall(4122, 20))) return sent
        if (!w(VarpLarge(4123, 29865))) return sent
        if (!w(VarpSmall(4124, 20))) return sent
        if (!w(VarpLarge(4125, 30550))) return sent
        if (!w(VarpSmall(4126, 17))) return sent
        if (!w(VarpLarge(4127, 30915))) return sent
        if (!w(VarpLarge(4128, 4805))) return sent
        if (!w(VarpSmall(4129, -1))) return sent
        if (!w(VarpSmall(4131, -1))) return sent
        if (!w(VarpSmall(4133, -1))) return sent
        if (!w(VarpSmall(4135, -1))) return sent
        if (!w(VarpSmall(4137, -1))) return sent
        if (!w(VarpSmall(4142, 119))) return sent
        if (!w(VarpSmall(4148, 16))) return sent
        if (!w(VarpSmall(4160, -1))) return sent
        if (!w(VarpLarge(4165, 3145728))) return sent
        if (!w(VarpLarge(4168, 1835036))) return sent
        if (!w(VarpLarge(4263, 16785920))) return sent
        if (!w(VarpLarge(4265, 536870912))) return sent
        if (!w(VarpSmall(4319, -1))) return sent
        if (!w(VarpSmall(4320, -1))) return sent
        if (!w(VarpSmall(4321, -1))) return sent
        if (!w(VarpSmall(4322, -1))) return sent
        if (!w(VarpSmall(4323, -1))) return sent
        if (!w(VarpSmall(4324, -1))) return sent
        if (!w(VarpSmall(4325, -1))) return sent
        if (!w(VarpSmall(4326, -1))) return sent
        if (!w(VarpSmall(4327, -1))) return sent
        if (!w(VarpSmall(4328, -1))) return sent
        if (!w(VarpSmall(4329, -1))) return sent
        if (!w(VarpSmall(4330, -1))) return sent
        if (!w(VarpLarge(4332, 6144))) return sent
        if (!w(VarpSmall(4335, -1))) return sent
        if (!w(VarpLarge(4391, 67108864))) return sent
        if (!w(VarpSmall(4408, -1))) return sent
        if (!w(VarpSmall(4409, -1))) return sent
        if (!w(VarpLarge(4410, 1073741824))) return sent
        if (!w(VarpSmall(4415, 30))) return sent
        if (!w(VarpSmall(4427, -1))) return sent
        if (!w(VarpSmall(4428, -1))) return sent
        if (!w(VarpSmall(4429, -1))) return sent
        if (!w(VarpSmall(4430, -1))) return sent
        if (!w(VarpSmall(4431, -1))) return sent
        if (!w(VarpSmall(4432, -1))) return sent
        if (!w(VarpSmall(4433, -1))) return sent
        if (!w(VarpSmall(4434, -1))) return sent
        if (!w(VarpSmall(4435, -1))) return sent
        if (!w(VarpSmall(4436, -1))) return sent
        if (!w(VarpSmall(4437, -1))) return sent
        if (!w(VarpSmall(4438, -1))) return sent
        if (!w(VarpSmall(4439, -1))) return sent
        if (!w(VarpSmall(4440, -1))) return sent
        if (!w(VarpLarge(4445, 384))) return sent
        if (!w(VarpLarge(4457, 118489090))) return sent
        if (!w(VarpSmall(4468, 64))) return sent
        if (!w(VarpLarge(4477, 9216))) return sent
        if (!w(VarpLarge(4497, 507928))) return sent
        if (!w(VarpLarge(4516, 41943519))) return sent
        if (!w(VarpLarge(4519, 16384))) return sent
        if (!w(VarpLarge(4520, 384))) return sent
        if (!w(VarpSmall(4562, -1))) return sent
        if (!w(VarpLarge(4686, 536870912))) return sent
        if (!w(VarpSmall(4691, 64))) return sent
        if (!w(VarpSmall(4734, -1))) return sent
        if (!w(VarpSmall(4735, -1))) return sent
        if (!w(VarpLarge(4737, 3211872))) return sent
        if (!w(VarpLarge(4739, 4227073))) return sent
        if (!w(VarpLarge(4756, 50074))) return sent
        if (!w(VarpLarge(4759, 655364))) return sent
        if (!w(VarpSmall(4760, 2))) return sent
        if (!w(VarpLarge(4763, -2147481600))) return sent
        if (!w(VarpLarge(4781, 1048576))) return sent
        if (!w(VarpLarge(4786, 955657116))) return sent
        if (!w(VarpLarge(4788, 67108864))) return sent
        if (!w(VarpSmall(4789, -1))) return sent
        if (!w(VarpLarge(4813, 8388608))) return sent
        if (!w(VarpLarge(4818, 49152))) return sent
        if (!w(VarpSmall(4824, -1))) return sent
        if (!w(VarpSmall(4850, -1))) return sent
        if (!w(VarpSmall(4859, -1))) return sent
        if (!w(VarpSmall(4860, -1))) return sent
        if (!w(VarpSmall(4871, -1))) return sent
        if (!w(VarpLarge(4878, 50000))) return sent
        if (!w(VarpLarge(4879, 500000))) return sent
        if (!w(VarpLarge(4895, 771751936))) return sent
        if (!w(VarpLarge(4896, 637534208))) return sent
        if (!w(VarpLarge(4897, 570425344))) return sent
        if (!w(VarpLarge(4898, 872415232))) return sent
        if (!w(VarpSmall(4911, 1))) return sent
        if (!w(VarpSmall(4915, 2))) return sent
        if (!w(VarpSmall(4919, -1))) return sent
        if (!w(VarpSmall(5005, -1))) return sent
        if (!w(VarpSmall(5006, -1))) return sent
        if (!w(VarpLarge(5019, 1103102976))) return sent
        if (!w(VarpLarge(5020, 1073745920))) return sent
        if (!w(VarpSmall(5030, -1))) return sent
        if (!w(VarpLarge(5050, 1024))) return sent
        if (!w(VarpLarge(5053, 512))) return sent
        if (!w(VarpSmall(5060, -1))) return sent
        if (!w(VarpSmall(5062, -1))) return sent
        if (!w(VarpLarge(5085, 536870917))) return sent
        if (!w(VarpSmall(5087, -1))) return sent
        if (!w(VarpLarge(5091, -1316663040))) return sent
        if (!w(VarpSmall(5107, -1))) return sent
        if (!w(VarpSmall(5108, -1))) return sent
        if (!w(VarpSmall(5144, -1))) return sent
        if (!w(VarpSmall(5148, -1))) return sent
        if (!w(VarpLarge(5152, 1073741824))) return sent
        if (!w(VarpLarge(5166, 262157))) return sent
        if (!w(VarpLarge(5186, 54525952))) return sent
        if (!w(VarpLarge(5191, 6312))) return sent
        if (!w(VarpSmall(5196, -1))) return sent
        if (!w(VarpSmall(5197, -1))) return sent
        if (!w(VarpSmall(5200, -1))) return sent
        if (!w(VarpLarge(5212, 1072))) return sent
        if (!w(VarpLarge(5263, 33554848))) return sent
        if (!w(VarpLarge(5264, 8437329))) return sent
        if (!w(VarpSmall(5335, -1))) return sent
        if (!w(VarpSmall(5336, -1))) return sent
        if (!w(VarpSmall(5337, -1))) return sent
        if (!w(VarpSmall(5338, -1))) return sent
        if (!w(VarpSmall(5339, -1))) return sent
        if (!w(VarpSmall(5340, -1))) return sent
        if (!w(VarpSmall(5341, -1))) return sent
        if (!w(VarpSmall(5342, -1))) return sent
        if (!w(VarpSmall(5343, -1))) return sent
        if (!w(VarpSmall(5344, -1))) return sent
        if (!w(VarpSmall(5345, -1))) return sent
        if (!w(VarpSmall(5346, -1))) return sent
        if (!w(VarpSmall(5347, -1))) return sent
        if (!w(VarpSmall(5348, -1))) return sent
        if (!w(VarpSmall(5349, -1))) return sent
        if (!w(VarpSmall(5350, -1))) return sent
        if (!w(VarpSmall(5351, -1))) return sent
        if (!w(VarpSmall(5352, -1))) return sent
        if (!w(VarpSmall(5353, -1))) return sent
        if (!w(VarpSmall(5354, -1))) return sent
        if (!w(VarpSmall(5355, -1))) return sent
        if (!w(VarpSmall(5356, -1))) return sent
        if (!w(VarpSmall(5357, -1))) return sent
        if (!w(VarpSmall(5358, -1))) return sent
        if (!w(VarpSmall(5359, -1))) return sent
        if (!w(VarpSmall(5360, -1))) return sent
        if (!w(VarpSmall(5361, -1))) return sent
        if (!w(VarpSmall(5362, -1))) return sent
        if (!w(VarpSmall(5363, -1))) return sent
        if (!w(VarpSmall(5364, -1))) return sent
        if (!w(VarpSmall(5365, -1))) return sent
        if (!w(VarpSmall(5366, -1))) return sent
        if (!w(VarpSmall(5367, -1))) return sent
        if (!w(VarpSmall(5368, -1))) return sent
        if (!w(VarpSmall(5369, -1))) return sent
        if (!w(VarpSmall(5370, -1))) return sent
        if (!w(VarpSmall(5371, -1))) return sent
        if (!w(VarpSmall(5372, -1))) return sent
        if (!w(VarpSmall(5373, -1))) return sent
        if (!w(VarpSmall(5374, -1))) return sent
        if (!w(VarpSmall(5375, -1))) return sent
        if (!w(VarpSmall(5376, -1))) return sent
        if (!w(VarpSmall(5377, -1))) return sent
        if (!w(VarpSmall(5378, -1))) return sent
        if (!w(VarpSmall(5379, -1))) return sent
        if (!w(VarpSmall(5380, -1))) return sent
        if (!w(VarpSmall(5381, -1))) return sent
        if (!w(VarpSmall(5382, -1))) return sent
        if (!w(VarpSmall(5383, -1))) return sent
        if (!w(VarpSmall(5384, -1))) return sent
        if (!w(VarpSmall(5385, -1))) return sent
        if (!w(VarpSmall(5386, -1))) return sent
        if (!w(VarpSmall(5387, -1))) return sent
        if (!w(VarpSmall(5388, -1))) return sent
        if (!w(VarpSmall(5389, -1))) return sent
        if (!w(VarpSmall(5390, -1))) return sent
        if (!w(VarpSmall(5391, -1))) return sent
        if (!w(VarpSmall(5392, -1))) return sent
        if (!w(VarpSmall(5393, -1))) return sent
        if (!w(VarpSmall(5394, -1))) return sent
        if (!w(VarpSmall(5395, -1))) return sent
        if (!w(VarpSmall(5396, -1))) return sent
        if (!w(VarpSmall(5397, -1))) return sent
        if (!w(VarpSmall(5398, -1))) return sent
        if (!w(VarpSmall(5399, -1))) return sent
        if (!w(VarpSmall(5400, -1))) return sent
        if (!w(VarpSmall(5401, -1))) return sent
        if (!w(VarpSmall(5402, -1))) return sent
        if (!w(VarpSmall(5403, -1))) return sent
        if (!w(VarpSmall(5404, -1))) return sent
        if (!w(VarpLarge(5413, 1572867))) return sent
        if (!w(VarpSmall(5414, -1))) return sent
        if (!w(VarpSmall(5425, -1))) return sent
        if (!w(VarpSmall(5426, -1))) return sent
        if (!w(VarpSmall(5428, -1))) return sent
        if (!w(VarpLarge(5673, 1073741824))) return sent
        if (!w(VarpSmall(5676, -1))) return sent
        if (!w(VarpLarge(5682, 7168))) return sent
        if (!w(VarpSmall(5684, -1))) return sent
        if (!w(VarpLarge(5685, 835111276))) return sent
        if (!w(VarpSmall(5686, 2))) return sent
        if (!w(VarpLarge(5701, 2048))) return sent
        if (!w(VarpLarge(5704, 401543073))) return sent
        if (!w(VarpLarge(5712, 1511))) return sent
        if (!w(VarpSmall(5713, -1))) return sent
        if (!w(VarpSmall(5714, -1))) return sent
        if (!w(VarpSmall(5715, -1))) return sent
        if (!w(VarpSmall(5716, -1))) return sent
        if (!w(VarpLarge(5717, 634))) return sent
        if (!w(VarpSmall(5722, 2))) return sent
        if (!w(VarpLarge(5733, 55066624))) return sent
        if (!w(VarpLarge(5734, 262144))) return sent
        if (!w(VarpLarge(5758, 339738624))) return sent
        if (!w(VarpSmall(5776, -1))) return sent
        if (!w(VarpSmall(5787, -1))) return sent
        if (!w(VarpSmall(5804, -1))) return sent
        if (!w(VarpSmall(5810, -1))) return sent
        if (!w(VarpSmall(5816, 1))) return sent
        if (!w(VarpSmall(5826, -1))) return sent
        if (!w(VarpSmall(5827, -1))) return sent
        if (!w(VarpLarge(5830, 1073741824))) return sent
        if (!w(VarpLarge(5833, 268435456))) return sent
        if (!w(VarpSmall(5839, 74))) return sent
        if (!w(VarpLarge(5841, 4194304))) return sent
        if (!w(VarpSmall(5854, -1))) return sent
        if (!w(VarpLarge(5860, 1443258418))) return sent
        if (!w(VarpLarge(5863, 6145))) return sent
        if (!w(VarpLarge(5868, 268502912))) return sent
        if (!w(VarpLarge(5872, 1073741823))) return sent
        if (!w(VarpLarge(5873, 1073741823))) return sent
        if (!w(VarpLarge(5874, 1073741823))) return sent
        if (!w(VarpLarge(5875, 1073741823))) return sent
        if (!w(VarpLarge(5876, 1073741823))) return sent
        if (!w(VarpLarge(5877, 1073741823))) return sent
        if (!w(VarpLarge(5878, 1073741823))) return sent
        if (!w(VarpLarge(5879, 1073741823))) return sent
        if (!w(VarpLarge(5880, 1073741823))) return sent
        if (!w(VarpSmall(5886, -1))) return sent
        if (!w(VarpLarge(5900, 131072))) return sent
        if (!w(VarpLarge(5905, 1084227584))) return sent
        if (!w(VarpLarge(5906, 134217792))) return sent
        if (!w(VarpLarge(5908, 25165824))) return sent
        if (!w(VarpLarge(5910, -2147483648))) return sent
        if (!w(VarpSmall(5936, -1))) return sent
        if (!w(VarpLarge(5938, -2147483648))) return sent
        if (!w(VarpLarge(5939, 16777216))) return sent
        if (!w(VarpLarge(5943, 16777216))) return sent
        if (!w(VarpLarge(5945, 83886112))) return sent
        if (!w(VarpLarge(5953, 8388608))) return sent
        if (!w(VarpLarge(5962, 1300298918))) return sent
        if (!w(VarpLarge(5967, -1073741176))) return sent
        if (!w(VarpLarge(5971, 3080))) return sent
        if (!w(VarpLarge(5987, -1073741824))) return sent
        if (!w(VarpSmall(5992, -1))) return sent
        if (!w(VarpSmall(5993, -1))) return sent
        if (!w(VarpSmall(6079, -1))) return sent
        if (!w(VarpSmall(6080, -1))) return sent
        if (!w(VarpSmall(6081, -1))) return sent
        if (!w(VarpSmall(6082, -1))) return sent
        if (!w(VarpSmall(6083, -1))) return sent
        if (!w(VarpSmall(6084, -1))) return sent
        if (!w(VarpSmall(6085, -1))) return sent
        if (!w(VarpSmall(6086, -1))) return sent
        if (!w(VarpSmall(6087, -1))) return sent
        if (!w(VarpLarge(6101, 16384))) return sent
        if (!w(VarpLarge(6107, 1073741824))) return sent
        if (!w(VarpLarge(6115, -2147483648))) return sent
        if (!w(VarpLarge(6117, -2147483648))) return sent
        if (!w(VarpLarge(6120, -2147483648))) return sent
        if (!w(VarpLarge(6140, 133296))) return sent
        if (!w(VarpLarge(6152, -16912800))) return sent
        if (!w(VarpLarge(6153, -16912800))) return sent
        if (!w(VarpLarge(6154, 10153256))) return sent
        if (!w(VarpSmall(6156, 96))) return sent
        if (!w(VarpLarge(6157, 180224))) return sent
        if (!w(VarpLarge(6158, 1024))) return sent
        if (!w(VarpLarge(6162, 7149))) return sent
        if (!w(VarpLarge(6164, 8192))) return sent
        if (!w(VarpLarge(6165, 1048576))) return sent
        if (!w(VarpSmall(6168, -1))) return sent
        if (!w(VarpLarge(6199, -2147483648))) return sent
        if (!w(VarpSmall(6214, -1))) return sent
        if (!w(VarpLarge(6230, 4194304))) return sent
        if (!w(VarpLarge(6231, -679473056))) return sent
        if (!w(VarpLarge(6251, 1094713344))) return sent
        if (!w(VarpLarge(6349, 1048576))) return sent
        if (!w(VarpSmall(6361, -1))) return sent
        if (!w(VarpSmall(6370, -1))) return sent
        if (!w(VarpLarge(6379, 543162368))) return sent
        if (!w(VarpSmall(6380, 2))) return sent
        if (!w(VarpLarge(6384, 397312))) return sent
        if (!w(VarpSmall(6392, -1))) return sent
        if (!w(VarpSmall(6393, -1))) return sent
        if (!w(VarpLarge(6405, 134217728))) return sent
        if (!w(VarpLarge(6409, 419430527))) return sent
        if (!w(VarpLarge(6410, 419430527))) return sent
        if (!w(VarpLarge(6411, 419430527))) return sent
        if (!w(VarpLarge(6412, 419430527))) return sent
        if (!w(VarpLarge(6413, 419430527))) return sent
        if (!w(VarpLarge(6414, 419430527))) return sent
        if (!w(VarpLarge(6415, 419430527))) return sent
        if (!w(VarpLarge(6416, 419430527))) return sent
        if (!w(VarpLarge(6447, 2048))) return sent
        if (!w(VarpLarge(6448, 163840))) return sent
        if (!w(VarpLarge(6451, 20971620))) return sent
        if (!w(VarpLarge(6452, 16640))) return sent
        if (!w(VarpLarge(6454, 540672))) return sent
        if (!w(VarpLarge(6461, 1061417984))) return sent
        if (!w(VarpLarge(6464, 540672))) return sent
        if (!w(VarpLarge(6465, 536870912))) return sent
        if (!w(VarpLarge(6505, 1073741824))) return sent
        if (!w(VarpLarge(6507, 69632))) return sent
        if (!w(VarpLarge(6522, 16384))) return sent
        if (!w(VarpLarge(6543, 262144))) return sent
        if (!w(VarpLarge(6562, 65536))) return sent
        if (!w(VarpLarge(6565, 33554432))) return sent
        if (!w(VarpSmall(6568, -1))) return sent
        if (!w(VarpLarge(6594, 419430527))) return sent
        if (!w(VarpLarge(6595, 419430527))) return sent
        if (!w(VarpLarge(6596, 419430527))) return sent
        if (!w(VarpLarge(6597, 419430527))) return sent
        if (!w(VarpLarge(6601, 7050))) return sent
        if (!w(VarpLarge(6613, -2147483648))) return sent
        if (!w(VarpLarge(6617, 1610612736))) return sent
        if (!w(VarpSmall(6620, -1))) return sent
        if (!w(VarpLarge(6625, 33605632))) return sent
        if (!w(VarpSmall(6628, -1))) return sent
        if (!w(VarpLarge(6631, 4097))) return sent
        if (!w(VarpLarge(6648, 155))) return sent
        if (!w(VarpSmall(6679, -1))) return sent
        if (!w(VarpLarge(6680, 27052050))) return sent
        if (!w(VarpLarge(6681, 227483))) return sent
        if (!w(VarpSmall(6687, 2))) return sent
        if (!w(VarpLarge(6691, 16384))) return sent
        if (!w(VarpLarge(6692, -2147483648))) return sent
        if (!w(VarpLarge(6694, 1073741824))) return sent
        if (!w(VarpLarge(6744, 4970964))) return sent
        if (!w(VarpLarge(6745, 37132))) return sent
        if (!w(VarpSmall(6746, 21))) return sent
        if (!w(VarpLarge(6748, 1073741824))) return sent
        if (!w(VarpLarge(6754, -2147483648))) return sent
        if (!w(VarpSmall(6758, -1))) return sent
        if (!w(VarpSmall(6766, -1))) return sent
        if (!w(VarpSmall(6767, -1))) return sent
        if (!w(VarpLarge(6777, 524288))) return sent
        if (!w(VarpLarge(6795, 8388608))) return sent
        if (!w(VarpSmall(6796, -1))) return sent
        if (!w(VarpLarge(6797, 200))) return sent
        if (!w(VarpLarge(6798, 256))) return sent
        if (!w(VarpLarge(6807, 225))) return sent
        if (!w(VarpSmall(6808, 6))) return sent
        if (!w(VarpSmall(6810, -1))) return sent
        if (!w(VarpSmall(6811, -1))) return sent
        if (!w(VarpSmall(6812, -1))) return sent
        if (!w(VarpSmall(6813, -1))) return sent
        if (!w(VarpSmall(6814, -1))) return sent
        if (!w(VarpSmall(6815, -1))) return sent
        if (!w(VarpSmall(6816, -1))) return sent
        if (!w(VarpSmall(6817, -1))) return sent
        if (!w(VarpSmall(6818, -1))) return sent
        if (!w(VarpSmall(6819, -1))) return sent
        if (!w(VarpSmall(6820, -1))) return sent
        if (!w(VarpSmall(6867, -1))) return sent
        if (!w(VarpSmall(6868, -1))) return sent
        if (!w(VarpSmall(6880, 1))) return sent
        if (!w(VarpSmall(6882, 14))) return sent
        if (!w(VarpSmall(6884, 2))) return sent
        if (!w(VarpSmall(6891, -1))) return sent
        if (!w(VarpLarge(6899, 4049))) return sent
        if (!w(VarpLarge(6901, 2073091))) return sent
        if (!w(VarpLarge(6910, 536870912))) return sent
        if (!w(VarpLarge(6912, 256))) return sent
        if (!w(VarpLarge(6920, 384))) return sent
        if (!w(VarpLarge(6926, 2048))) return sent
        if (!w(VarpSmall(6991, 1))) return sent
        if (!w(VarpSmall(7007, 6))) return sent
        if (!w(VarpLarge(7008, 1890582528))) return sent
        if (!w(VarpLarge(7010, 65536))) return sent
        if (!w(VarpLarge(7011, -2147483648))) return sent
        if (!w(VarpLarge(7014, 1073741824))) return sent
        if (!w(VarpLarge(7035, 260128768))) return sent
        if (!w(VarpSmall(7044, 1))) return sent
        if (!w(VarpLarge(7047, 512))) return sent
        if (!w(VarpLarge(7048, 128))) return sent
        if (!w(VarpLarge(7052, 1325400064))) return sent
        if (!w(VarpSmall(7054, 1))) return sent
        if (!w(VarpLarge(7057, -2147483648))) return sent
        if (!w(VarpLarge(7078, 28905))) return sent
        if (!w(VarpSmall(7079, 18))) return sent
        if (!w(VarpLarge(7080, 29923))) return sent
        if (!w(VarpSmall(7081, 18))) return sent
        if (!w(VarpLarge(7082, 29923))) return sent
        if (!w(VarpSmall(7083, 19))) return sent
        if (!w(VarpLarge(7084, 29923))) return sent
        if (!w(VarpSmall(7085, 19))) return sent
        if (!w(VarpLarge(7086, 29896))) return sent
        if (!w(VarpSmall(7087, 20))) return sent
        if (!w(VarpLarge(7088, 29896))) return sent
        if (!w(VarpSmall(7089, 20))) return sent
        if (!w(VarpLarge(7090, 29896))) return sent
        if (!w(VarpSmall(7091, 20))) return sent
        if (!w(VarpLarge(7092, 37129))) return sent
        if (!w(VarpSmall(7093, 22))) return sent
        if (!w(VarpLarge(7095, -2147483648))) return sent
        if (!w(VarpLarge(7099, 1108378721))) return sent
        if (!w(VarpSmall(7100, 1))) return sent
        if (!w(VarpSmall(7101, -1))) return sent
        if (!w(VarpSmall(7103, 6))) return sent
        if (!w(VarpLarge(7107, 12582912))) return sent
        if (!w(VarpLarge(7120, 53776))) return sent
        if (!w(VarpSmall(7123, 23))) return sent
        if (!w(VarpLarge(7124, 4049))) return sent
        if (!w(VarpSmall(7134, -1))) return sent
        if (!w(VarpLarge(7138, 131072))) return sent
        if (!w(VarpLarge(7172, 36944))) return sent
        if (!w(VarpSmall(7173, 23))) return sent
        if (!w(VarpSmall(7246, -1))) return sent
        if (!w(VarpSmall(7253, -1))) return sent
        if (!w(VarpSmall(7254, -1))) return sent
        if (!w(VarpLarge(7256, 1073741824))) return sent
        if (!w(VarpSmall(7534, 8))) return sent
        if (!w(VarpSmall(7535, -1))) return sent
        if (!w(VarpLarge(7539, -1073741824))) return sent
        if (!w(VarpLarge(7544, 1025))) return sent
        if (!w(VarpLarge(7550, 512))) return sent
        if (!w(VarpSmall(7612, 4))) return sent
        if (!w(VarpLarge(7614, 3286088))) return sent
        if (!w(VarpLarge(7618, 8208))) return sent
        if (!w(VarpLarge(7622, 134217728))) return sent
        if (!w(VarpSmall(7623, 1))) return sent
        if (!w(VarpSmall(7624, 49))) return sent
        if (!w(VarpSmall(7633, 1))) return sent
        if (!w(VarpLarge(7640, 513))) return sent
        if (!w(VarpLarge(7641, -2147483648))) return sent
        if (!w(VarpSmall(7650, -1))) return sent
        if (!w(VarpSmall(7651, -1))) return sent
        if (!w(VarpSmall(7652, -1))) return sent
        if (!w(VarpLarge(7655, 1573921))) return sent
        if (!w(VarpSmall(7721, 1))) return sent
        if (!w(VarpSmall(7722, 8))) return sent
        if (!w(VarpSmall(7723, -1))) return sent
        if (!w(VarpSmall(7724, -1))) return sent
        if (!w(VarpSmall(7725, -1))) return sent
        if (!w(VarpSmall(7798, 4))) return sent
        if (!w(VarpSmall(7809, -1))) return sent
        if (!w(VarpSmall(7810, -1))) return sent
        if (!w(VarpLarge(7811, 2688))) return sent
        if (!w(VarpSmall(7825, 98))) return sent
        if (!w(VarpLarge(7836, 536870912))) return sent
        if (!w(VarpLarge(7838, 32768))) return sent
        if (!w(VarpLarge(7847, 268435456))) return sent
        if (!w(VarpSmall(7848, 3))) return sent
        if (!w(VarpSmall(7863, -1))) return sent
        if (!w(VarpLarge(7864, 16777216))) return sent
        if (!w(VarpLarge(7871, 1073741824))) return sent
        if (!w(VarpSmall(7881, -1))) return sent
        if (!w(VarpLarge(7894, 1048576))) return sent
        if (!w(VarpLarge(7904, 134217732))) return sent
        if (!w(VarpSmall(7909, -1))) return sent
        if (!w(VarpLarge(7910, 536870912))) return sent
        if (!w(VarpLarge(7916, 805306368))) return sent
        if (!w(VarpLarge(7923, 33554432))) return sent
        if (!w(VarpLarge(7927, 528))) return sent
        if (!w(VarpSmall(7960, -1))) return sent
        if (!w(VarpLarge(7969, 67108928))) return sent
        if (!w(VarpSmall(7970, 2))) return sent
        if (!w(VarpLarge(7978, 8388608))) return sent
        if (!w(VarpSmall(7981, -1))) return sent
        if (!w(VarpSmall(7982, -1))) return sent
        if (!w(VarpSmall(7983, -1))) return sent
        if (!w(VarpLarge(7988, 77216))) return sent
        if (!w(VarpSmall(7994, -1))) return sent
        if (!w(VarpSmall(7995, -1))) return sent
        if (!w(VarpLarge(7998, 2048))) return sent
        if (!w(VarpSmall(7999, 6))) return sent
        if (!w(VarpLarge(8001, 1536))) return sent
        if (!w(VarpSmall(8004, -1))) return sent
        if (!w(VarpSmall(8029, -1))) return sent
        if (!w(VarpSmall(8030, -1))) return sent
        if (!w(VarpSmall(8031, -1))) return sent
        if (!w(VarpLarge(8040, 98404))) return sent
        if (!w(VarpLarge(8041, 201326592))) return sent
        if (!w(VarpSmall(8042, -1))) return sent
        if (!w(VarpLarge(8043, 16384))) return sent
        if (!w(VarpSmall(8067, -1))) return sent
        if (!w(VarpSmall(8068, -1))) return sent
        if (!w(VarpSmall(8069, -1))) return sent
        if (!w(VarpSmall(8070, -1))) return sent
        if (!w(VarpSmall(8071, -1))) return sent
        if (!w(VarpSmall(8072, -1))) return sent
        if (!w(VarpLarge(8089, -2147483648))) return sent
        if (!w(VarpLarge(8091, -2147221504))) return sent
        if (!w(VarpLarge(8101, 1885356294))) return sent
        if (!w(VarpLarge(8102, 8405377))) return sent
        if (!w(VarpSmall(8103, -1))) return sent
        if (!w(VarpSmall(8112, -1))) return sent
        if (!w(VarpSmall(8113, -1))) return sent
        if (!w(VarpSmall(8114, -1))) return sent
        if (!w(VarpSmall(8115, -1))) return sent
        if (!w(VarpSmall(8116, -1))) return sent
        if (!w(VarpSmall(8117, -1))) return sent
        if (!w(VarpSmall(8118, -1))) return sent
        if (!w(VarpSmall(8119, -1))) return sent
        if (!w(VarpSmall(8120, -1))) return sent
        if (!w(VarpSmall(8121, -1))) return sent
        if (!w(VarpSmall(8152, 1))) return sent
        if (!w(VarpSmall(8154, 3))) return sent
        if (!w(VarpSmall(8157, -1))) return sent
        if (!w(VarpLarge(8158, 134217728))) return sent
        if (!w(VarpLarge(8172, 4129))) return sent
        if (!w(VarpSmall(8173, -1))) return sent
        if (!w(VarpSmall(8174, -1))) return sent
        if (!w(VarpLarge(8176, 8225))) return sent
        if (!w(VarpLarge(8197, 98304))) return sent
        if (!w(VarpSmall(8198, 2))) return sent
        if (!w(VarpLarge(8203, 1610612736))) return sent
        if (!w(VarpLarge(8207, 524288))) return sent
        if (!w(VarpLarge(8208, 65536))) return sent
        if (!w(VarpLarge(8213, 1073741825))) return sent
        if (!w(VarpSmall(8218, 1))) return sent
        if (!w(VarpSmall(8223, 64))) return sent
        if (!w(VarpLarge(8227, 7040))) return sent
        if (!w(VarpLarge(8240, 7034))) return sent
        if (!w(VarpSmall(8242, -1))) return sent
        if (!w(VarpLarge(8245, 1048576))) return sent
        if (!w(VarpLarge(8258, 360448))) return sent
        if (!w(VarpLarge(8276, 195166231))) return sent
        if (!w(VarpLarge(8282, 2048))) return sent
        if (!w(VarpLarge(8283, -1073702911))) return sent
        if (!w(VarpLarge(8284, 33816773))) return sent
        if (!w(VarpLarge(8285, 17065984))) return sent
        if (!w(VarpLarge(8295, 201457664))) return sent
        if (!w(VarpSmall(8296, 40))) return sent
        if (!w(VarpLarge(8309, 1114112))) return sent
        if (!w(VarpLarge(8310, 344064))) return sent
        if (!w(VarpSmall(8328, 19))) return sent
        if (!w(VarpSmall(8331, -1))) return sent
        if (!w(VarpSmall(8332, -1))) return sent
        if (!w(VarpSmall(8333, -1))) return sent
        if (!w(VarpSmall(8334, -1))) return sent
        if (!w(VarpSmall(8335, -1))) return sent
        if (!w(VarpSmall(8338, 2))) return sent
        if (!w(VarpSmall(8339, -1))) return sent
        if (!w(VarpSmall(8442, 8))) return sent
        if (!w(VarpLarge(8446, 65535))) return sent
        if (!w(VarpSmall(8463, -1))) return sent
        if (!w(VarpSmall(8464, -1))) return sent
        if (!w(VarpSmall(8465, -1))) return sent
        if (!w(VarpSmall(8466, -1))) return sent
        if (!w(VarpSmall(8467, -1))) return sent
        if (!w(VarpSmall(8468, -1))) return sent
        if (!w(VarpSmall(8469, -1))) return sent
        if (!w(VarpSmall(8470, -1))) return sent
        if (!w(VarpSmall(8471, -1))) return sent
        if (!w(VarpSmall(8472, -1))) return sent
        if (!w(VarpSmall(8473, -1))) return sent
        if (!w(VarpSmall(8474, -1))) return sent
        if (!w(VarpSmall(8475, -1))) return sent
        if (!w(VarpSmall(8476, -1))) return sent
        if (!w(VarpSmall(8477, -1))) return sent
        if (!w(VarpSmall(8478, -1))) return sent
        if (!w(VarpSmall(8479, -1))) return sent
        if (!w(VarpSmall(8480, -1))) return sent
        if (!w(VarpSmall(8481, -1))) return sent
        if (!w(VarpSmall(8482, -1))) return sent
        if (!w(VarpSmall(8531, -1))) return sent
        if (!w(VarpSmall(8551, -1))) return sent
        if (!w(VarpLarge(8568, -21954882))) return sent
        if (!w(VarpLarge(8569, -166461790))) return sent
        if (!w(VarpLarge(8570, -21954882))) return sent
        if (!w(VarpLarge(8571, -166461790))) return sent
        if (!w(VarpSmall(8573, -1))) return sent
        if (!w(VarpLarge(8579, 7038))) return sent
        if (!w(VarpSmall(8601, -1))) return sent
        if (!w(VarpSmall(8628, -1))) return sent
        if (!w(VarpSmall(8629, -1))) return sent
        if (!w(VarpSmall(8630, -1))) return sent
        if (!w(VarpSmall(8631, -1))) return sent
        if (!w(VarpLarge(8693, 1610645505))) return sent
        if (!w(VarpSmall(8698, 1))) return sent
        if (!w(VarpSmall(8725, 1))) return sent
        if (!w(VarpSmall(8729, 2))) return sent
        if (!w(VarpSmall(8735, 1))) return sent
        if (!w(VarpSmall(8745, -1))) return sent
        if (!w(VarpSmall(8746, -1))) return sent
        if (!w(VarpSmall(8748, -1))) return sent
        if (!w(VarpSmall(8749, -1))) return sent
        if (!w(VarpSmall(8750, -1))) return sent
        if (!w(VarpSmall(8751, -1))) return sent
        if (!w(VarpSmall(8752, -1))) return sent
        if (!w(VarpLarge(8756, 553648128))) return sent
        if (!w(VarpSmall(8757, 3))) return sent
        if (!w(VarpSmall(8801, -1))) return sent
        if (!w(VarpSmall(8802, -1))) return sent
        if (!w(VarpSmall(8803, -1))) return sent
        if (!w(VarpSmall(8804, -1))) return sent
        if (!w(VarpSmall(8805, -1))) return sent
        if (!w(VarpSmall(8806, -1))) return sent
        if (!w(VarpSmall(8807, -1))) return sent
        if (!w(VarpSmall(8808, -1))) return sent
        if (!w(VarpSmall(8809, -1))) return sent
        if (!w(VarpSmall(8810, -1))) return sent
        if (!w(VarpSmall(8811, -1))) return sent
        if (!w(VarpSmall(8812, -1))) return sent
        if (!w(VarpSmall(8813, -1))) return sent
        if (!w(VarpSmall(8814, -1))) return sent
        if (!w(VarpSmall(8815, -1))) return sent
        if (!w(VarpSmall(8816, -1))) return sent
        if (!w(VarpSmall(8817, -1))) return sent
        if (!w(VarpSmall(8818, -1))) return sent
        if (!w(VarpSmall(8819, -1))) return sent
        if (!w(VarpSmall(8820, -1))) return sent
        if (!w(VarpSmall(8821, -1))) return sent
        if (!w(VarpSmall(8822, -1))) return sent
        if (!w(VarpSmall(8823, -1))) return sent
        if (!w(VarpSmall(8824, -1))) return sent
        if (!w(VarpSmall(8825, -1))) return sent
        if (!w(VarpSmall(8826, -1))) return sent
        if (!w(VarpSmall(8827, -1))) return sent
        if (!w(VarpSmall(8828, -1))) return sent
        if (!w(VarpSmall(8829, -1))) return sent
        if (!w(VarpSmall(8830, -1))) return sent
        if (!w(VarpSmall(8831, -1))) return sent
        if (!w(VarpSmall(8832, -1))) return sent
        if (!w(VarpSmall(8833, -1))) return sent
        if (!w(VarpSmall(8834, -1))) return sent
        if (!w(VarpSmall(8835, -1))) return sent
        if (!w(VarpSmall(8836, -1))) return sent
        if (!w(VarpSmall(8837, -1))) return sent
        if (!w(VarpSmall(8838, -1))) return sent
        if (!w(VarpSmall(8839, -1))) return sent
        if (!w(VarpSmall(8840, -1))) return sent
        if (!w(VarpSmall(8841, -1))) return sent
        if (!w(VarpSmall(8842, -1))) return sent
        if (!w(VarpLarge(8849, 427366777))) return sent
        if (!w(VarpLarge(8852, 427366777))) return sent
        if (!w(VarpSmall(8958, 37))) return sent
        if (!w(VarpSmall(8959, 16))) return sent
        if (!w(VarpSmall(8963, 51))) return sent
        if (!w(VarpSmall(9005, -1))) return sent
        if (!w(VarpLarge(9015, 3584))) return sent
        if (!w(VarpLarge(9017, 284235))) return sent
        if (!w(VarpSmall(9062, 16))) return sent
        if (!w(VarpSmall(9063, -1))) return sent
        if (!w(VarpSmall(9064, -1))) return sent
        if (!w(VarpSmall(9068, 1))) return sent
        if (!w(VarpSmall(9069, 50))) return sent
        if (!w(VarpLarge(9070, 255))) return sent
        if (!w(VarpSmall(9071, -1))) return sent
        if (!w(VarpSmall(9072, 1))) return sent
        if (!w(VarpSmall(9075, 10))) return sent
        if (!w(VarpSmall(9076, 50))) return sent
        if (!w(VarpLarge(9077, 255))) return sent
        if (!w(VarpSmall(9084, 1))) return sent
        if (!w(VarpLarge(9089, 572822089))) return sent
        if (!w(VarpLarge(9090, 528))) return sent
        if (!w(VarpLarge(9091, 13435))) return sent
        if (!w(VarpLarge(9092, 23717))) return sent
        if (!w(VarpLarge(9093, 30534))) return sent
        if (!w(VarpLarge(9094, 30534))) return sent
        if (!w(VarpLarge(9095, 30546))) return sent
        if (!w(VarpLarge(9096, 30550))) return sent
        if (!w(VarpLarge(9097, 30550))) return sent
        if (!w(VarpLarge(9098, 30550))) return sent
        if (!w(VarpLarge(9099, 30505))) return sent
        if (!w(VarpLarge(9100, 23736))) return sent
        if (!w(VarpSmall(9101, 56))) return sent
        if (!w(VarpSmall(9102, 1))) return sent
        if (!w(VarpSmall(9103, 1))) return sent
        if (!w(VarpSmall(9104, 1))) return sent
        if (!w(VarpSmall(9105, 1))) return sent
        if (!w(VarpSmall(9106, 1))) return sent
        if (!w(VarpSmall(9107, 1))) return sent
        if (!w(VarpSmall(9108, 1))) return sent
        if (!w(VarpSmall(9109, 1))) return sent
        if (!w(VarpSmall(9110, 1))) return sent
        if (!w(VarpLarge(9114, 136323072))) return sent
        if (!w(VarpSmall(9117, -1))) return sent
        if (!w(VarpSmall(9118, -1))) return sent
        if (!w(VarpSmall(9119, -1))) return sent
        if (!w(VarpSmall(9120, -1))) return sent
        if (!w(VarpSmall(9121, -1))) return sent
        if (!w(VarpSmall(9122, -1))) return sent
        if (!w(VarpSmall(9123, -1))) return sent
        if (!w(VarpSmall(9124, -1))) return sent
        if (!w(VarpSmall(9125, -1))) return sent
        if (!w(VarpSmall(9126, -1))) return sent
        if (!w(VarpSmall(9127, -1))) return sent
        if (!w(VarpSmall(9128, -1))) return sent
        if (!w(VarpLarge(9133, 34823))) return sent
        if (!w(VarpSmall(9137, -1))) return sent
        if (!w(VarpSmall(9140, -1))) return sent
        if (!w(VarpSmall(9143, -1))) return sent
        if (!w(VarpSmall(9146, -1))) return sent
        if (!w(VarpSmall(9149, -1))) return sent
        if (!w(VarpLarge(9168, 1280))) return sent
        if (!w(VarpSmall(9178, -1))) return sent
        if (!w(VarpSmall(9179, -1))) return sent
        if (!w(VarpSmall(9180, -1))) return sent
        if (!w(VarpSmall(9181, -1))) return sent
        if (!w(VarpSmall(9182, -1))) return sent
        if (!w(VarpSmall(9183, -1))) return sent
        if (!w(VarpSmall(9184, -1))) return sent
        if (!w(VarpSmall(9185, -1))) return sent
        if (!w(VarpSmall(9186, -1))) return sent
        if (!w(VarpSmall(9187, -1))) return sent
        if (!w(VarpSmall(9188, -1))) return sent
        if (!w(VarpSmall(9189, -1))) return sent
        if (!w(VarpSmall(9190, -1))) return sent
        if (!w(VarpSmall(9191, 16))) return sent
        if (!w(VarpLarge(9194, 1434451968))) return sent
        if (!w(VarpLarge(9199, 2113665))) return sent
        if (!w(VarpSmall(9200, -1))) return sent
        if (!w(VarpLarge(9201, 134217728))) return sent
        if (!w(VarpSmall(9212, -1))) return sent
        if (!w(VarpSmall(9215, -1))) return sent
        if (!w(VarpSmall(9216, -1))) return sent
        if (!w(VarpSmall(9284, -1))) return sent
        if (!w(VarpSmall(9288, -1))) return sent
        if (!w(VarpSmall(9294, -1))) return sent
        if (!w(VarpLarge(9420, 4096))) return sent
        if (!w(VarpSmall(9436, -1))) return sent
        if (!w(VarpSmall(9438, -1))) return sent
        if (!w(VarpSmall(9440, -1))) return sent
        if (!w(VarpLarge(9445, 6722))) return sent
        if (!w(VarpLarge(9447, 3765))) return sent
        if (!w(VarpSmall(9451, 1))) return sent
        if (!w(VarpSmall(9460, -1))) return sent
        if (!w(VarpSmall(9462, 1))) return sent
        if (!w(VarpSmall(9465, -1))) return sent
        if (!w(VarpSmall(9501, -1))) return sent
        if (!w(VarpLarge(9502, 7009))) return sent
        if (!w(VarpLarge(9503, 1795))) return sent
        if (!w(VarpSmall(9528, -1))) return sent
        if (!w(VarpSmall(9547, 2))) return sent
        if (!w(VarpSmall(9548, 6))) return sent
        if (!w(VarpSmall(9561, 8))) return sent
        if (!w(VarpSmall(9590, -1))) return sent
        if (!w(VarpSmall(9594, -1))) return sent
        if (!w(VarpSmall(9596, -1))) return sent
        if (!w(VarpSmall(9607, -1))) return sent
        if (!w(VarpSmall(9608, -1))) return sent
        if (!w(VarpSmall(9609, -1))) return sent
        if (!w(VarpLarge(9612, 1001))) return sent
        if (!w(VarpLarge(9613, 821))) return sent
        if (!w(VarpLarge(9614, 796))) return sent
        if (!w(VarpSmall(9615, -1))) return sent
        if (!w(VarpSmall(9616, -1))) return sent
        if (!w(VarpSmall(9617, -1))) return sent
        if (!w(VarpSmall(9618, -1))) return sent
        if (!w(VarpSmall(9619, -1))) return sent
        if (!w(VarpSmall(9620, 1))) return sent
        if (!w(VarpSmall(9621, 4))) return sent
        if (!w(VarpSmall(9622, -1))) return sent
        if (!w(VarpSmall(9623, -1))) return sent
        if (!w(VarpSmall(9624, -1))) return sent
        if (!w(VarpSmall(9625, -1))) return sent
        if (!w(VarpSmall(9626, -1))) return sent
        if (!w(VarpSmall(9628, -1))) return sent
        if (!w(VarpSmall(9629, -1))) return sent
        if (!w(VarpSmall(9630, -1))) return sent
        if (!w(VarpSmall(9631, -1))) return sent
        if (!w(VarpSmall(9663, 1))) return sent
        if (!w(VarpSmall(9684, -1))) return sent
        if (!w(VarpSmall(9685, -1))) return sent
        if (!w(VarpSmall(9686, -1))) return sent
        if (!w(VarpSmall(9687, -1))) return sent
        if (!w(VarpSmall(9688, -1))) return sent
        if (!w(VarpSmall(9689, -1))) return sent
        if (!w(VarpSmall(9690, -1))) return sent
        if (!w(VarpSmall(9691, -1))) return sent
        if (!w(VarpSmall(9692, -1))) return sent
        if (!w(VarpSmall(9693, -1))) return sent
        if (!w(VarpSmall(9694, -1))) return sent
        if (!w(VarpSmall(9695, -1))) return sent
        if (!w(VarpSmall(9696, -1))) return sent
        if (!w(VarpSmall(9697, -1))) return sent
        if (!w(VarpSmall(9715, -1))) return sent
        if (!w(VarpSmall(9717, 31))) return sent
        if (!w(VarpSmall(9727, -1))) return sent
        if (!w(VarpSmall(9728, -1))) return sent
        if (!w(VarpSmall(9729, -1))) return sent
        if (!w(VarpSmall(9730, -1))) return sent
        if (!w(VarpSmall(9731, -1))) return sent
        if (!w(VarpSmall(9732, -1))) return sent
        if (!w(VarpSmall(9733, -1))) return sent
        if (!w(VarpSmall(9749, -1))) return sent
        if (!w(VarpSmall(9750, -1))) return sent
        if (!w(VarpSmall(9751, -1))) return sent
        if (!w(VarpSmall(9752, -1))) return sent
        if (!w(VarpSmall(9753, -1))) return sent
        if (!w(VarpSmall(9754, -1))) return sent
        if (!w(VarpSmall(9755, -1))) return sent
        if (!w(VarpSmall(9756, -1))) return sent
        if (!w(VarpSmall(9759, -1))) return sent
        if (!w(VarpSmall(9761, -1))) return sent
        if (!w(VarpSmall(9773, -1))) return sent
        if (!w(VarpSmall(9774, -1))) return sent
        if (!w(VarpSmall(9775, 100))) return sent
        if (!w(VarpSmall(9787, 32))) return sent
        if (!w(VarpSmall(9793, -1))) return sent
        if (!w(VarpLarge(9796, 7007))) return sent
        if (!w(VarpSmall(9800, 1))) return sent
        if (!w(VarpLarge(9801, 1073741823))) return sent
        if (!w(VarpLarge(9802, 1073741823))) return sent
        if (!w(VarpLarge(9803, 1073741823))) return sent
        if (!w(VarpLarge(9804, 1073741823))) return sent
        if (!w(VarpLarge(9805, 1073741823))) return sent
        if (!w(VarpLarge(9806, 1073741823))) return sent
        if (!w(VarpLarge(9807, 1073741823))) return sent
        if (!w(VarpLarge(9808, 1073741823))) return sent
        if (!w(VarpLarge(9809, 1073741823))) return sent
        if (!w(VarpLarge(9810, 1073741823))) return sent
        if (!w(VarpLarge(9811, 1073741823))) return sent
        if (!w(VarpLarge(9812, 1073741823))) return sent
        if (!w(VarpLarge(9813, 1073741823))) return sent
        if (!w(VarpLarge(9814, 1073741823))) return sent
        if (!w(VarpLarge(9815, 1073741823))) return sent
        if (!w(VarpLarge(9816, 1073741823))) return sent
        if (!w(VarpLarge(9817, 1073741823))) return sent
        if (!w(VarpLarge(9818, 1073741823))) return sent
        if (!w(VarpLarge(9819, 1073741823))) return sent
        if (!w(VarpLarge(9820, 1073741823))) return sent
        if (!w(VarpLarge(9821, 1073741823))) return sent
        if (!w(VarpLarge(9822, 1073741823))) return sent
        if (!w(VarpLarge(9823, 1073741823))) return sent
        if (!w(VarpLarge(9824, 1073741823))) return sent
        if (!w(VarpLarge(9825, 1073741823))) return sent
        if (!w(VarpLarge(9826, 1073741823))) return sent
        if (!w(VarpLarge(9827, 1073741823))) return sent
        if (!w(VarpLarge(9828, 1073741823))) return sent
        if (!w(VarpLarge(9829, 1073741823))) return sent
        if (!w(VarpLarge(9830, 1073741823))) return sent
        if (!w(VarpLarge(9831, 1073741823))) return sent
        if (!w(VarpLarge(9832, 1073741823))) return sent
        if (!w(VarpLarge(9833, 1073741823))) return sent
        if (!w(VarpLarge(9834, 1073741823))) return sent
        if (!w(VarpLarge(9835, 1073741823))) return sent
        if (!w(VarpSmall(9853, -1))) return sent
        if (!w(VarpLarge(9865, 401))) return sent
        if (!w(VarpLarge(9866, 7038))) return sent
        if (!w(VarpSmall(9874, -1))) return sent
        if (!w(VarpSmall(9875, -1))) return sent
        if (!w(VarpSmall(9876, -1))) return sent
        if (!w(VarpSmall(9877, -1))) return sent
        if (!w(VarpSmall(9881, -1))) return sent
        if (!w(VarpSmall(9894, -1))) return sent
        if (skipped > 0)
            logger.warn { "Varp guard: replay finished - $sent varps sent, $skipped skipped as undefined in this cache" }
        else
            logger.info { "Varp guard: replay finished - $sent varps sent, none skipped" }
        lastSent = sent
        lastSkipped = skipped
        return sent
    }

    /**
     * How many varps the most recent [sendDefaultVarps] wrote, and how many the
     * guard dropped as undefined in this cache. "Most recent", across all players -
     * this is an observation hook, not per-player state.
     */
    @Volatile var lastSent: Int = -1
        private set

    /** @see lastSent */
    @Volatile var lastSkipped: Int = -1
        private set
}