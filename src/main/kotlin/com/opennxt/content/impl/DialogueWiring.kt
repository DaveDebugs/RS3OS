package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import mu.KotlinLogging

/**
 * The ONE place that knows both a [ContentPlayer] and a live [WorldPlayer] for
 * dialogue, in the shape [SkillingWiring] established.
 */
object DialogueWiring {

    private val logger = KotlinLogging.logger { }

    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    fun bind(content: ContentPlayer, world: WorldPlayer) {
        val existing = owners[content]?.get()
        if (existing === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = owners[content]?.get()

    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    internal fun clear() = synchronized(owners) { owners.clear() }

    /** Sends a dialogue's packets through the player's own [com.opennxt.model.entity.player.InterfaceManager]. */
    private class LiveSink(val world: WorldPlayer) : Dialogue.Sink {
        override fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean) =
            world.interfaces.open(id = interfaceId, parent = parent, component = component, walkable = walkable)

        override fun setText(interfaceId: Int, component: Int, text: String) =
            world.interfaces.text(interfaceId, component, text)

        override fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int) =
            world.interfaces.events(interfaceId, component, fromSlot, toSlot, mask)

        override fun setHide(interfaceId: Int, component: Int, hidden: Boolean) =
            world.interfaces.hide(interfaceId, component, hidden)

        override fun setModel(interfaceId: Int, component: Int, kind: Int, value: Int) =
            world.interfaces.model(interfaceId, component, kind, value)

        /**
         * IF_SETANIM, opcode 104. with the reference sequence.
         */
        override fun setAnim(interfaceId: Int, component: Int, anim: Int) {
            world.client.write(
                com.opennxt.net.game.serverprot.generated.IfSetanim(
                    com.opennxt.model.InterfaceHash(interfaceId, component).hash, anim
                )
            )
        }

        // setNpcHead / setPlayerHead are NOT overridden: the Sink's own default
        // bodies route them through setModel above, which is the InterfaceManager
        // path that already picks IfModelK2 (opcode 17, IF_SETNPCHEAD) and
        // IfModelK3Self (opcode 24, IF_SETPLAYERHEAD). Overriding them here would
        // be a second way to reach the same two encoders.

        override fun closeSub(parent: Int, component: Int) =
            world.interfaces.close(parent, component)

        override fun runClientScript(scriptId: Int, vararg args: Any) {
            world.client.write(com.opennxt.net.game.serverprot.RunClientScript(scriptId, arrayOf(*args)))
        }
    }

    /**
     * Points [Dialogue.sinkSupplier] at the live server. Returns false when
     * dialogue is switched off, so the seam is not moved for a module that will
     * never fire.
     */
    fun install(): Boolean {
        if (!Dialogue.enabled) {
            logger.warn { "dialogue wiring: dialogue is disabled, leaving the no-op sink in place" }
            return false
        }
        Dialogue.sinkSupplier = { content -> ownerOf(content)?.let { LiveSink(it) } }
        logger.info {
            "dialogue wiring: IF_OPENSUB / IF_SETTEXT / IF_SETEVENTS / IF_CLOSESUB" +
                (if (Dialogue.sethideEnabled) " / IF_SETHIDE" else "") +
                " now go through the player's own InterfaceManager. IF_SETTEXT (opcode 34), " +
                "IF_SETHIDE (124) and IF_CLOSESUB (107) have never been on this server's wire before."
        }
        return true
    }

    /** Puts the no-op sink back. Only a headless check needs this. */
    fun uninstall() {
        Dialogue.sinkSupplier = { null }
        clear()
    }

    /**
     * Routes an inbound interface click at a conversation, if there is one.
     *
     * Returns true when the dialogue consumed the click, so the caller can tell
     * "this was a dialogue click" from "this was something else" and log
     * accordingly. A click on a component the open page did not arm is NOT
     * consumed: [Dialogue.onButton] refuses it, and refusing is the point -
     * the client picks the hash it sends and only the server knows which
     * components it armed.
     *
     * The hash split is IF_BUTTON1's `arg1`, which the one live observation in this
     * build confirmed is `(interface shl 16) or component`.
     */
    fun handleButtonLabelled(world: WorldPlayer, packet: com.opennxt.net.game.clientprot.IfButtonLabelled): Boolean {
        if (!Dialogue.enabled) return false
        val content = world.contentPlayerAt()
        bind(content, world)
        if (Dialogue.sessionOf(content) == null) return false

        val result = Dialogue.onButton(content, packet.interfaceId, packet.component)
        return when (result) {
            is Dialogue.ClickResult.Consumed -> {
                logger.info {
                    "dialogue: ${world.name} ClientProt 102 (Labelled) on " +
                        "${packet.interfaceId}:${packet.component}" +
                        (if (result.chose != null) " chose option ${result.chose + 1}" else "") +
                        (if (result.ended) " - conversation ENDED" else " - next page") +
                        ". ${Dialogue.PROVENANCE_SHORT}"
                }
                true
            }
            else -> false
        }
    }

    fun handleButton(world: WorldPlayer, packet: IfButtonN): Boolean {
        if (!Dialogue.enabled) return false
        val content = world.contentPlayerAt()
        bind(content, world)
        if (Dialogue.sessionOf(content) == null) return false

        val result = Dialogue.onButton(content, packet.interfaceId, packet.component)
        return when (result) {
            is Dialogue.ClickResult.Consumed -> {
                logger.info {
                    "dialogue: ${world.name} IF_BUTTON${packet.buttonOp} on " +
                        "${packet.interfaceId}:${packet.component}" +
                        (if (result.chose != null) " chose option ${result.chose + 1}" else "") +
                        (if (result.ended) " - conversation ENDED" else " - next page") +
                        ". ${Dialogue.PROVENANCE_SHORT}"
                }
                true
            }

            is Dialogue.ClickResult.NotArmed -> false
            Dialogue.ClickResult.NoSession -> false
        }
    }

    /**
     * ClientProt 127 - a component click, routed here as an blob.
     */
    fun handleObservedComponentClick(world: WorldPlayer, interfaceId: Int, component: Int): Boolean {
        if (!Dialogue.enabled || !observed127Enabled) return false
        val content = world.contentPlayerAt()
        bind(content, world)
        if (Dialogue.sessionOf(content) == null) return false

        return when (val result = Dialogue.onButton(content, interfaceId, component)) {
            is Dialogue.ClickResult.Consumed -> {
                logger.info {
                    "dialogue: ${world.name} ClientProt 127 (observed component click) on " +
                        "$interfaceId:$component" +
                        (if (result.chose != null) " chose option ${result.chose + 1}" else "") +
                        (if (result.ended) " - conversation ENDED" else " - next page") +
                        ". THIS IS THE FIRST TIME 127 HAS ADVANCED A CONVERSATION - if you are " +
                        "reading this line, 127 is the continue click and has earned a name. " +
                        "${Dialogue.PROVENANCE_SHORT}"
                }
                true
            }

            is Dialogue.ClickResult.NotArmed -> false
            Dialogue.ClickResult.NoSession -> false
        }
    }

    /** See [handleObservedComponentClick]. Default ON; the armed guard makes it safe. */
    val observed127Enabled: Boolean
        get() = System.getProperty("opennxt.experiment.dialogue.observed127") != "false"
}
