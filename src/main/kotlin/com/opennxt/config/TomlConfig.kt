package com.opennxt.config

import com.moandjiezana.toml.Toml
import com.moandjiezana.toml.TomlWriter
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.full.createInstance

abstract class TomlConfig {
    abstract fun save(map: MutableMap<String, Any>)

    abstract fun load(toml: Toml)

    companion object {
        inline fun <reified T : TomlConfig> load(
            path: Path,
            saveAfterLoad: Boolean = true,
            mustExist: Boolean = false
        ): T {
            if (mustExist && !Files.exists(path))
                throw FileNotFoundException(path.toString())

            if (!Files.exists(path.parent))
                Files.createDirectories(path.parent)

            val config = T::class.createInstance()
            if (!Files.exists(path)) {
                save(path, config)
                return config
            }

            val toml = Toml()
            toml.read(path.toFile())
            config.load(toml)

            if (saveAfterLoad)
                save(path, config)

            return config
        }

        /**
         * Write [config] to [path] ATOMICALLY, and not at all when the bytes
         * would be unchanged.
         */
        fun save(path: Path, config: TomlConfig) {
            if (!Files.exists(path.parent))
                Files.createDirectories(path.parent)

            val map = Object2ObjectOpenHashMap<String, Any>()
            config.save(map)

            val rendered = java.io.StringWriter().also { TomlWriter().write(map, it) }.toString()

            // No-op when nothing would change. This is the case for essentially
            // every read, and skipping it is what stops a read from being a
            // write at all.
            if (Files.exists(path)) {
                val current = try { String(Files.readAllBytes(path), Charsets.UTF_8) } catch (e: Exception) { null }
                if (current == rendered) return
            }

            // Atomic replace: a concurrent reader never observes a partial file.
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp." + ProcessHandle.current().pid())
            Files.write(tmp, rendered.toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }
}