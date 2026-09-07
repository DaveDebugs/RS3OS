package com.opennxt.model.account

import com.opennxt.Constants
import mu.KotlinLogging
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Account + save persistence: one SQLite database, owned by this class alone.
 *
 * Deliberately NOT [com.opennxt.resources.sqlite.RsDatabase]: that connection
 * is read-only by design (a build artefact of buildall.sh must never be
 * written), and accounts are the opposite - mutable server state. Same JDBC
 * driver (sqlite-jdbc is already a dependency; RsDatabase uses it), separate
 * file, separate connection, WAL journal mode so a reader never blocks the
 * login thread's writes.
 *
 * ## Schema gate - checked FIRST, refuses loudly
 *
 * A `meta(schema_version)` row is read before anything else touches the file.
 * Three outcomes, none of them silent:
 *
 *  - fresh/empty file: the schema is created and stamped [SCHEMA_VERSION];
 *  - version == [SCHEMA_VERSION]: normal open;
 *  - anything else (wrong version, or tables without a meta row): the
 *    constructor THROWS with instructions. A half-understood database is
 *    never half-loaded - a schema this code does not know might store
 *    passwords or xp in a shape it would misread as zeros.
 *
 * ## Password hashing
 *
 * bcrypt is not on the classpath and adding dependencies is out of scope, so
 * hashing is salted PBKDF2WithHmacSHA256 from the JDK ([SecretKeyFactory],
 * javax.crypto):
 *
 *  - [PBKDF2_ITERATIONS] = 210,000 - the OWASP Password Storage Cheat Sheet
 *    recommendation for PBKDF2-HMAC-SHA256 (2023 revision);
 *  - 16-byte [SecureRandom] salt per password, 32-byte derived key;
 *  - stored as `pbkdf2-sha256$iterations$saltHex$keyHex`, so the iteration
 *    count travels with the hash and can be raised later without breaking
 *    existing rows;
 *  - verification recomputes with the STORED parameters and compares via
 *    [MessageDigest.isEqual] - constant-time, so the comparison itself leaks
 *    nothing about how many bytes matched.
 *
 * ## Usernames
 *
 * Unique case-insensitively, like RS: the column is `COLLATE NOCASE` (unique
 * index included) and every lookup binds through that collation, so
 * `alice`, `Alice` and `ALICE` are one account. NOCASE is ASCII
 * case-folding, which covers the RS username alphabet.
 *
 * ## Auto-registration (dev mode)
 *
 * [authenticate] with `autoRegister = true` (the default - this is a private
 * dev server) creates the account on first login. That path is flagged twice,
 * per project method: a log line at WARN, and `auto_registered = 1` in the
 * row, so a production operator can find every account that was never
 * explicitly created.
 */
class AccountStore(val path: Path) : AutoCloseable {

    companion object {
        private val logger = KotlinLogging.logger { }

        /** The one schema this code understands. Bump ONLY with a migration path. */
        const val SCHEMA_VERSION = 1

        /**
         * PBKDF2-HMAC-SHA256 iteration count: 210,000, the OWASP Password
         * Storage Cheat Sheet figure for this algorithm (2023 revision).
         */
        const val PBKDF2_ITERATIONS = 210_000

        const val SALT_BYTES = 16
        const val KEY_BYTES = 32
        private const val HASH_PREFIX = "pbkdf2-sha256"

        /** The real server database. Tools/checks must use their own scratch path. */
        val DEFAULT_PATH: Path = Constants.DATA_PATH.resolve("accounts.sqlite")

        /** Lazily opened server-wide store at [DEFAULT_PATH]. */
        val instance: AccountStore by lazy { AccountStore(DEFAULT_PATH) }

        private val random = SecureRandom()

        // ---- password hashing (static: pure functions of their inputs) ----

        fun hashPassword(password: String): String {
            val salt = ByteArray(SALT_BYTES)
            random.nextBytes(salt)
            val key = pbkdf2(password, salt, PBKDF2_ITERATIONS)
            return "$HASH_PREFIX\$$PBKDF2_ITERATIONS\$${hex(salt)}\$${hex(key)}"
        }

        /**
         * Constant-time verification against a stored hash string. A stored
         * value this code did not produce (wrong prefix, wrong field count)
         * throws rather than quietly failing the login: a mangled hash column
         * is corruption, not a wrong password.
         */
        fun verifyPassword(password: String, stored: String): Boolean {
            val parts = stored.split('$')
            if (parts.size != 4 || parts[0] != HASH_PREFIX)
                throw IllegalStateException(
                    "unparseable password hash (expected $HASH_PREFIX\$iter\$salt\$key): '${stored.take(24)}...'"
                )
            val iterations = parts[1].toIntOrNull()
                ?: throw IllegalStateException("non-numeric iteration count in stored hash: '${parts[1]}'")
            val salt = unhex(parts[2])
            val expected = unhex(parts[3])
            val actual = pbkdf2(password, salt, iterations)
            // MessageDigest.isEqual is documented constant-time since JDK 6u17.
            return MessageDigest.isEqual(expected, actual)
        }

        private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BYTES * 8)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        private fun unhex(s: String): ByteArray {
            require(s.length % 2 == 0) { "odd-length hex string" }
            return ByteArray(s.length / 2) { i ->
                ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
            }
        }
    }

    /** What one [authenticate] call concluded. Nothing here is a silent boolean-false. */
    enum class AuthResult(val success: Boolean) {
        /** Existing account, correct password. last_login_ms stamped. */
        OK(true),

        /** No such account existed; dev-mode auto-registration created it (logged + flagged in the row). */
        AUTO_REGISTERED(true),

        /** The account exists and the password does not match. */
        WRONG_PASSWORD(false),

        /** No such account and auto-registration was off. */
        NO_ACCOUNT(false),
    }

    private val connection: Connection

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        val config = SQLiteConfig()
        config.setJournalMode(SQLiteConfig.JournalMode.WAL)
        connection = config.createConnection("jdbc:sqlite:$path")
        try {
            openOrCreateSchema()
        } catch (t: Throwable) {
            connection.close() // never leave a rejected database half-open
            throw t
        }
        logger.info { "Opened account database $path (schema v$SCHEMA_VERSION, WAL)" }
    }

    /** The schema gate described in the class doc: version FIRST, refuse loudly. */
    private fun openOrCreateSchema() {
        val hasMeta = tableExists("meta")
        if (!hasMeta) {
            if (tableExists("accounts") || tableExists("saves")) {
                throw IllegalStateException(
                    "Account database $path has account tables but NO meta(schema_version) row. " +
                            "This file was not produced by AccountStore and will not be guessed at. " +
                            "Move it aside and let the server create a fresh database, or restore a backup."
                )
            }
            createSchema()
            return
        }
        val version = connection.createStatement().use { st ->
            st.executeQuery("SELECT schema_version FROM meta").use { rs ->
                if (!rs.next()) null else rs.getInt(1)
            }
        } ?: throw IllegalStateException(
            "Account database $path has a meta table but no schema_version row. " +
                    "The file is corrupt or half-initialised; restore a backup or move it aside."
        )
        if (version != SCHEMA_VERSION) {
            throw IllegalStateException(
                "Account database $path is schema version $version; this build understands only " +
                        "$SCHEMA_VERSION. REFUSING to open - a half-understood schema could misread " +
                        "passwords or player saves. If $version > $SCHEMA_VERSION: this file was written " +
                        "by a newer build, run that build instead. If $version < $SCHEMA_VERSION: a " +
                        "migration is required; back the file up first. Nothing was loaded."
            )
        }
    }

    private fun createSchema() {
        connection.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE meta (schema_version INTEGER NOT NULL)")
            st.executeUpdate("INSERT INTO meta (schema_version) VALUES ($SCHEMA_VERSION)")
            st.executeUpdate(
                """CREATE TABLE accounts (
                       username        TEXT NOT NULL COLLATE NOCASE,
                       pass_hash       TEXT NOT NULL,
                       created_ms      INTEGER NOT NULL,
                       last_login_ms   INTEGER,
                       auto_registered INTEGER NOT NULL DEFAULT 0
                   )"""
            )
            // The case-insensitive uniqueness lives in the index collation:
            // 'alice' and 'Alice' collide here.
            st.executeUpdate("CREATE UNIQUE INDEX accounts_username ON accounts (username COLLATE NOCASE)")
            st.executeUpdate(
                """CREATE TABLE saves (
                       username TEXT NOT NULL COLLATE NOCASE,
                       blob     TEXT NOT NULL,
                       saved_ms INTEGER NOT NULL
                   )"""
            )
            st.executeUpdate("CREATE UNIQUE INDEX saves_username ON saves (username COLLATE NOCASE)")
        }
        logger.info { "Created fresh account schema v$SCHEMA_VERSION at $path" }
    }

    @Synchronized
    private fun tableExists(name: String): Boolean {
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { st ->
            st.setString(1, name)
            st.executeQuery().use { return it.next() }
        }
    }

    // ---- accounts --------------------------------------------------------

    /**
     * Creates an account. Returns false (creating nothing) when the username
     * is already taken under case-insensitive comparison.
     */
    // ------------------------------------------------------------------------------------
 // EVERY METHOD THAT TOUCHES `connection` IS @Synchronized..
    //
    // This class holds ONE java.sql.Connection and was handing it to two threads:
    //
    //   * `loadSave` runs on a NETTY EVENT-LOOP thread - LoginServerHandler is a
    //     SimpleChannelInboundHandler and calls it from channelRead0 during login;
    //   * `storeSave` runs on the TICK thread - World.cullDisconnected and World.autosave, both
    //     inside World.tick.
    //
    // A JDBC Connection is not thread-safe and the contract does not promise otherwise, whatever
    // a particular driver happens to tolerate. The window is small (a login has to coincide with
    // an autosave or a cull) which is exactly why it would have been found in production rather
    // than here.
    //
    // Synchronizing the store, not the connection: these are short single-statement calls against
    // a local SQLite file in WAL mode, so the contention cost is negligible next to the failure it
    // removes. This is NOT indiscriminate locking - it is one lock over one demonstrably shared
    // resource with two proven writers on different threads.
    //
    // STILL OPEN, and not fixed here because it is a design change rather than a correctness one:
    // `loadSave` is blocking file I/O ON THE NETTY EVENT LOOP. Serializing it makes it correct,
    // not fast, and a slow disk now blocks a network thread. Moving login's save load onto the
    // tick thread (or a dedicated executor) is the real answer.
    // ------------------------------------------------------------------------------------

    @Synchronized
    fun register(
        username: String,
        password: String,
        autoRegistered: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        require(username.isNotBlank()) { "username must not be blank" }
        require(password.isNotEmpty()) { "password must not be empty" }
        if (exists(username)) return false
        val hash = hashPassword(password)
        connection.prepareStatement(
            "INSERT INTO accounts (username, pass_hash, created_ms, last_login_ms, auto_registered) VALUES (?, ?, ?, NULL, ?)"
        ).use { st ->
            st.setString(1, username)
            st.setString(2, hash)
            st.setLong(3, nowMs)
            st.setInt(4, if (autoRegistered) 1 else 0)
            st.executeUpdate()
        }
        if (autoRegistered) {
            logger.warn { "AUTO-REGISTERED account '$username' on first login (dev mode); flagged auto_registered=1" }
        } else {
            logger.info { "Registered account '$username'" }
        }
        return true
    }

    @Synchronized
    fun exists(username: String): Boolean {
        connection.prepareStatement("SELECT 1 FROM accounts WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { return it.next() }
        }
    }

    /**
     * The login-time credential check. Success (either flavour) stamps
     * `last_login_ms`; a wrong password is [AuthResult.WRONG_PASSWORD], never
     * an exception and never a silent false.
     *
     * [autoRegister] defaults true: this is a private dev server and the
     * first login creates the account - loudly (see the class doc).
     */
    @Synchronized
    fun authenticate(
        username: String,
        password: String,
        autoRegister: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): AuthResult {
        val stored = connection.prepareStatement("SELECT pass_hash FROM accounts WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (stored == null) {
            if (!autoRegister) return AuthResult.NO_ACCOUNT
            if (!register(username, password, autoRegistered = true, nowMs = nowMs)) {
                // Raced/collided registration: re-run as a plain authenticate.
                return authenticate(username, password, autoRegister = false, nowMs = nowMs)
            }
            stampLastLogin(username, nowMs)
            return AuthResult.AUTO_REGISTERED
        }
        if (!verifyPassword(password, stored)) return AuthResult.WRONG_PASSWORD
        stampLastLogin(username, nowMs)
        return AuthResult.OK
    }

    @Synchronized
    private fun stampLastLogin(username: String, nowMs: Long) {
        connection.prepareStatement("UPDATE accounts SET last_login_ms = ? WHERE username = ?").use { st ->
            st.setLong(1, nowMs)
            st.setString(2, username)
            st.executeUpdate()
        }
    }

    /** `last_login_ms` for the account, or null when never logged in / no account. */
    @Synchronized
    fun lastLoginMs(username: String): Long? {
        connection.prepareStatement("SELECT last_login_ms FROM accounts WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                val v = rs.getLong(1)
                return if (rs.wasNull()) null else v
            }
        }
    }

    /** The `auto_registered` flag, or null when no such account. For operators and checks. */
    @Synchronized
    fun autoRegistered(username: String): Boolean? {
        connection.prepareStatement("SELECT auto_registered FROM accounts WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) != 0 else null }
        }
    }

    // ---- saves -----------------------------------------------------------

    /** One row of the saves table, raw: the JSON exactly as stored plus its timestamp. */
    data class SaveRow(val blob: String, val savedMs: Long)

    /**
     * Stores [save] for [username], overwriting any previous save - latest
     * wins, there is no history table. Storing for an account that does not
     * exist throws: a save with no owner is a bug upstream, not data.
     */
    @Synchronized
    fun storeSave(username: String, save: PlayerSave, nowMs: Long = System.currentTimeMillis()) {
        if (!exists(username))
            throw IllegalStateException("cannot store a save for unknown account '$username' - register it first")
        val json = save.toJson()
        connection.prepareStatement(
            "INSERT INTO saves (username, blob, saved_ms) VALUES (?, ?, ?) " +
                    "ON CONFLICT (username) DO UPDATE SET blob = excluded.blob, saved_ms = excluded.saved_ms"
        ).use { st ->
            st.setString(1, username)
            st.setString(2, json)
            st.setLong(3, nowMs)
            st.executeUpdate()
        }
    }

    /**
     * Loads the save, or null when the account has none yet. A blob that does
     * not parse as a [PlayerSave] THROWS (from [PlayerSave.fromJson]) - a
     * corrupted save must never come back as a quietly-default player.
     */
    @Synchronized
    fun loadSave(username: String): PlayerSave? {
        val row = saveRow(username) ?: return null
        return PlayerSave.fromJson(row.blob)
    }

    /** The raw stored row, for checks that need the exact JSON and timestamp. */
    @Synchronized
    fun saveRow(username: String): SaveRow? {
        connection.prepareStatement("SELECT blob, saved_ms FROM saves WHERE username = ?").use { st ->
            st.setString(1, username)
            st.executeQuery().use { rs ->
                return if (rs.next()) SaveRow(rs.getString(1), rs.getLong(2)) else null
            }
        }
    }

    override fun close() {
        connection.close()
    }
}
