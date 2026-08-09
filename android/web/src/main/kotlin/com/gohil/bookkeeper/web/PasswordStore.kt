package com.gohil.bookkeeper.web

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Remembers, per file, the password that opened it.
 *
 * ## The tradeoff, stated plainly
 *
 * PROJECT_CONTEXT.md says statement passwords should live in the OS keyring, and that a chat
 * log is not a credential vault. That still holds — but "never ask me twice" cannot be met by
 * memory alone, because the server is a process the user closes. So this writes them down, and
 * the honest description of what that buys is:
 *
 *  - The file is encrypted with AES-256-GCM, and the key lives in a **separate** file. Both are
 *    0600, owner-only, in the user's home directory. Neither is ever sent anywhere: this server
 *    makes no outbound connections.
 *  - What this defends against: a cloud-backup agent or a desktop search indexer slurping up a
 *    plaintext file of bank passwords, and anyone who gets one of the two files but not the
 *    other.
 *  - What it does **not** defend against: anyone who can already run as this user. They can
 *    read both files and decrypt. Splitting the key off is a real speed bump, not a vault.
 *
 * That is a genuine reduction in safety compared with typing the password each time, and it is
 * the direct cost of the "remember it" requirement. It is opt-out per file in the UI, and
 * [forgetAll] deletes both files.
 *
 * The Android app does not use this class — it has a real keyring (Android Keystore via
 * EncryptedSharedPreferences), so it should keep using that.
 */
class PasswordStore(
    private val file: Path = defaultDir().resolve("passwords.enc"),
    private val keyFile: Path = defaultDir().resolve("passwords.key"),
) {

    private val cache = ConcurrentHashMap<String, String>()
    private var loaded = false

    @Synchronized
    fun get(hash: String): String? {
        load()
        return cache[hash]
    }

    @Synchronized
    fun put(hash: String, password: String) {
        load()
        cache[hash] = password
        persist()
    }

    @Synchronized
    fun forget(hash: String) {
        load()
        if (cache.remove(hash) != null) persist()
    }

    /** Wipes everything, including the key. The next run starts from nothing. */
    @Synchronized
    fun forgetAll() {
        cache.clear()
        Files.deleteIfExists(file)
        Files.deleteIfExists(keyFile)
        loaded = true
    }

    @Synchronized
    fun size(): Int {
        load()
        return cache.size
    }

    // ── storage ──────────────────────────────────────────────────────────────────

    private fun load() {
        if (loaded) return
        loaded = true
        if (!Files.exists(file) || !Files.exists(keyFile)) return
        runCatching {
            val blob = Files.readAllBytes(file)
            val nonce = blob.copyOfRange(0, NONCE_BYTES)
            val payload = blob.copyOfRange(NONCE_BYTES, blob.size)
            val plain = cipher(Cipher.DECRYPT_MODE, nonce).doFinal(payload).decodeToString()
            for (line in plain.lines()) {
                val hash = line.substringBefore('\t', "")
                val encoded = line.substringAfter('\t', "")
                if (hash.isNotBlank() && encoded.isNotBlank()) {
                    cache[hash] = Base64.getDecoder().decode(encoded).decodeToString()
                }
            }
        }
        // A store that will not decrypt (key deleted, file truncated) must not stop the app —
        // the user can always retype. Failing loudly here would block the whole run over a
        // cache, so it degrades to "we remember nothing".
    }

    private fun persist() {
        // Base64 the password, not the hash: a password may contain a tab or a newline, and a
        // record separator that can appear inside a value is how stores silently corrupt.
        val plain = cache.entries.joinToString("\n") { (hash, password) ->
            "$hash\t${Base64.getEncoder().encodeToString(password.encodeToByteArray())}"
        }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val sealed = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(plain.encodeToByteArray())

        Files.createDirectories(file.parent)
        Files.write(
            file,
            nonce + sealed,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        restrict(file)
    }

    private fun cipher(mode: Int, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key(), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }

    private fun key(): ByteArray {
        if (Files.exists(keyFile)) {
            val existing = Files.readAllBytes(keyFile)
            if (existing.size == KEY_BYTES) return existing
        }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        Files.createDirectories(keyFile.parent)
        Files.write(
            keyFile,
            fresh,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        restrict(keyFile)
        return fresh
    }

    /**
     * Owner-only. On Windows there are no POSIX permissions, so this is a no-op there and the
     * files rely on the user profile directory's own ACL — weaker, and worth knowing.
     */
    private fun restrict(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        fun defaultDir(): Path =
            Path.of(System.getProperty("user.home"), ".gohil-bookkeeper")

        /** Where the user can find — and delete — the two files. */
        fun describeLocation(): String = defaultDir().toString()
    }
}
