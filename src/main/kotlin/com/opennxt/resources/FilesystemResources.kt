package com.opennxt.resources

import com.opennxt.filesystem.Filesystem
import com.opennxt.resources.config.enums.EnumFilesystemCodec
import com.opennxt.resources.config.enums.EnumDiskCodec
import com.opennxt.resources.config.params.ParamDiskCodec
import com.opennxt.resources.config.params.ParamFilesystemCodec
import com.opennxt.resources.config.structs.StructDiskCodec
import com.opennxt.resources.config.structs.StructFilesystemCodec
import com.opennxt.resources.defaults.Defaults
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.reflect.KClass
import com.opennxt.resources.sqlite.SqliteAnimGroupCodec
import com.opennxt.resources.sqlite.SqliteItemCodec
import com.opennxt.resources.sqlite.SqliteLocCodec
import com.opennxt.resources.sqlite.SqliteNpcCodec
import com.opennxt.resources.sqlite.SqliteQuestCodec
import com.opennxt.resources.sqlite.SqliteSequenceCodec
import com.opennxt.resources.sqlite.SqliteSpotAnimCodec
import com.opennxt.resources.sqlite.SqliteVarBitCodec

class FilesystemResources(val filesystem: Filesystem, val path: Path) {
    companion object {
        lateinit var instance: FilesystemResources
    }

    private val fsCodices = EnumMap<ResourceType, FilesystemResourceCodec<*>>(ResourceType::class.java)
    private val diskCodices = EnumMap<ResourceType, DiskResourceCodec<*>>(ResourceType::class.java)

    val defaults = Defaults(filesystem)

    init {
        instance = this

        if (!Files.exists(path))
            Files.createDirectories(path)

        fsCodices[ResourceType.ENUM] = EnumFilesystemCodec
        fsCodices[ResourceType.PARAM] = ParamFilesystemCodec
        fsCodices[ResourceType.STRUCT] = StructFilesystemCodec

        // SQLite-backed definitions. These ignore the Filesystem they are handed
        // and read data/rs3.sqlite instead; if that file is absent they return
        // null rather than throwing, so a server without the database behaves as
        // it did before. Registered unconditionally for that reason - a codec
        // that is present and returns null is easier to diagnose than one whose
        // registration silently depends on a file existing at startup.
        fsCodices[ResourceType.ITEM] = SqliteItemCodec
        fsCodices[ResourceType.NPC] = SqliteNpcCodec
        fsCodices[ResourceType.LOC] = SqliteLocCodec
        fsCodices[ResourceType.VARBIT] = SqliteVarBitCodec
        fsCodices[ResourceType.SEQUENCE] = SqliteSequenceCodec
        fsCodices[ResourceType.SPOTANIM] = SqliteSpotAnimCodec
        fsCodices[ResourceType.ANIMGROUP] = SqliteAnimGroupCodec
        fsCodices[ResourceType.QUEST] = SqliteQuestCodec

        diskCodices[ResourceType.ENUM] = EnumDiskCodec
        diskCodices[ResourceType.PARAM] = ParamDiskCodec
        diskCodices[ResourceType.STRUCT] = StructDiskCodec
    }

    fun getFilesystemCodex(type: KClass<*>): FilesystemResourceCodec<*> {
        val resourceType =
            ResourceType.forClass(type) ?: throw NullPointerException("No resource type linked to type $type")

        return fsCodices[resourceType] ?: throw NullPointerException("No filesystem codex found for type $type")
    }

    fun getDiskCodec(type: KClass<*>): DiskResourceCodec<*> {
        val resourceType =
            ResourceType.forClass(type) ?: throw NullPointerException("No resource type linked to type $type")

        return diskCodices[resourceType] ?: throw NullPointerException("No disk codex found for type $type")
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> get(id: Int): T? {
        return getFilesystemCodex(T::class).load(filesystem, id) as? T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> list(type: KClass<out T>): Map<Int, T> {
        return getFilesystemCodex(type).list(filesystem) as Map<Int, T>
    }
}