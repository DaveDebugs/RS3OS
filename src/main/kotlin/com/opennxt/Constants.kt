package com.opennxt

import java.nio.file.Path
import java.nio.file.Paths

object Constants {
    val DATA_PATH = Paths.get("./data/")
    val CLIENTS_PATH = DATA_PATH.resolve("clients")
    val LAUNCHERS_PATH = DATA_PATH.resolve("launchers")
    val CONFIG_PATH = DATA_PATH.resolve("config")

    /**
     * Where the game cache lives. `./data/cache` unless `-Dopennxt.cache=<path>`
     * overrides it.
     *
     * The override exists because the default requires the cache to sit INSIDE
     * the server directory, and on Windows that is an obstacle rather than a
     * convenience: the cache ships at `C:\ProgramData\Jagex\RuneScape`, so
     * making `./data/cache` reach it means copying 8.4 GB or creating a link.
     * `mklink /D` needs an elevated prompt or Developer Mode - a strange thing
     * to demand for a read-only path - and it fails before the server has
     * printed anything, so it reads as "the server is broken" rather than as a
     * permissions problem. (`mklink /J`, a junction, does not need elevation and
     * also works; this property means neither is necessary.)
     *
     * Absolute or relative both work; the value is used exactly as given.
     */
    val CACHE_PATH: Path = System.getProperty("opennxt.cache")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it) }
        ?: DATA_PATH.resolve("cache")
    val PROT_PATH = DATA_PATH.resolve("prot")
    val RESOURCE_PATH = DATA_PATH.resolve("resources")
    val PROXY_PATH = DATA_PATH.resolve("proxy")
    val PROXY_DUMP_PATH = PROXY_PATH.resolve("dumps")
}