package com.gohil.bookkeeper.web

import java.security.MessageDigest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException

/**
 * Answers one question per file, before any parsing is attempted: *does this specific file
 * need a password, and do we already know it?*
 *
 * This replaces the earlier blanket approach, where every password the user had typed was
 * tried against every encrypted file. That was wrong in a way that got worse the more accounts
 * were added: with four banks and three cards, opening one statement meant up to seven decrypt
 * attempts, six of them guaranteed failures, and a failure message that could not say which
 * password was actually missing. Probing each file individually costs one cheap open and makes
 * the prompt specific — "HDFC-Jun.pdf needs a password" rather than "something didn't open".
 */
object PdfProbe {

    /**
     * Files are identified by the SHA-256 of their *contents*, not their name.
     *
     * A bank exports "statement.pdf" every month, so names collide constantly and would make
     * the store hand June's password to July's file. Content hashing also means the same
     * statement re-uploaded from a different folder is recognised immediately, which is the
     * behaviour the user asked for: never being asked twice for the same document.
     */
    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    enum class State {
        /** Opens with no password. Go straight to parsing; do not prompt. */
        OPEN,

        /** Encrypted, and a remembered password opens it. Also no prompt. */
        UNLOCKED_FROM_STORE,

        /** Encrypted and we have nothing that works. This — and only this — prompts. */
        NEEDS_PASSWORD,

        /** Not a PDF at all (an image, say). Nothing to unlock. */
        NOT_A_PDF,

        /** Structurally broken, or an encryption PDFBox cannot handle. Prompting would not help. */
        UNREADABLE,
    }

    data class Probe(
        val fileName: String,
        val fileHash: String,
        val state: State,
        /** Set only for [State.UNLOCKED_FROM_STORE]; never serialised to the browser. */
        val password: String? = null,
        val detail: String = "",
    ) {
        val needsPassword: Boolean get() = state == State.NEEDS_PASSWORD
    }

    /**
     * Probe one file. [store] is consulted but never written to — remembering only happens
     * once a password has actually been proven to work, in [verify].
     */
    fun probe(bytes: ByteArray, fileName: String, store: PasswordStore?): Probe {
        val hash = hash(bytes)
        if (!fileName.endsWith(".pdf", ignoreCase = true)) {
            return Probe(fileName, hash, State.NOT_A_PDF)
        }

        when (val open = tryOpen(bytes, "")) {
            is Attempt.Ok -> return Probe(fileName, hash, State.OPEN)
            is Attempt.Broken -> return Probe(fileName, hash, State.UNREADABLE, detail = open.detail)
            Attempt.WrongPassword -> Unit
        }

        // Encrypted. This file's own remembered password is tried first — it is the one that
        // is known to work, and trying it alone is the whole point of hashing the contents.
        val remembered = store?.get(hash)
        if (remembered != null) {
            when (val open = tryOpen(bytes, remembered)) {
                is Attempt.Ok -> return Probe(
                    fileName, hash, State.UNLOCKED_FROM_STORE, remembered,
                    detail = OPENED_FROM_STORE,
                )
                is Attempt.Broken -> return Probe(fileName, hash, State.UNREADABLE, detail = open.detail)
                // A stored password that stopped working means the file changed under the same
                // hash — impossible — or the store is stale. Drop it and ask again rather than
                // silently failing every future run with a password the user cannot see.
                Attempt.WrongPassword -> store.forget(hash)
            }
        }

        // A file never seen before, which is what every new month's download is: the bank
        // names it statement.pdf again and re-encrypts it, so the hash is new even though
        // the password is not. Try what has already been proven to work on this machine
        // before making the user type anything.
        //
        // This is not the blanket behaviour the per-file model replaced. That one tried
        // whatever the user had typed into the box this session against every encrypted
        // file, got slower and vaguer with each account added, and could never say which
        // password was missing. This tries only passwords already proven against some file,
        // only after the file's own entry has been tried, and it still ends at
        // NEEDS_PASSWORD naming the one file nothing opened.
        for (candidate in store?.known().orEmpty()) {
            if (candidate == remembered) continue
            when (val open = tryOpen(bytes, candidate)) {
                is Attempt.Ok -> {
                    // Record it against this file too, so next time the direct hit answers.
                    store?.put(hash, candidate)
                    return Probe(
                        fileName, hash, State.UNLOCKED_FROM_STORE, candidate,
                        detail = OPENED_FROM_STORE,
                    )
                }
                is Attempt.Broken -> return Probe(fileName, hash, State.UNREADABLE, detail = open.detail)
                Attempt.WrongPassword -> Unit
            }
        }

        return Probe(fileName, hash, State.NEEDS_PASSWORD)
    }

    /**
     * Check a password the user just typed for one specific file. On success it is remembered
     * against the file's hash, which is what stops the app ever asking for it again.
     */
    fun verify(bytes: ByteArray, password: String, store: PasswordStore?, remember: Boolean): Boolean {
        val ok = tryOpen(bytes, password) is Attempt.Ok
        if (ok && remember) store?.put(hash(bytes), password)
        return ok
    }

    const val OPENED_FROM_STORE = "Opened with a password you saved earlier."

    private sealed interface Attempt {
        data object Ok : Attempt
        data object WrongPassword : Attempt
        data class Broken(val detail: String) : Attempt
    }

    private fun tryOpen(bytes: ByteArray, password: String): Attempt = try {
        // Closed immediately: this only establishes whether the password works. The real
        // extraction re-opens it, so a probe never holds a decrypted document in memory.
        Loader.loadPDF(bytes, password).close()
        Attempt.Ok
    } catch (e: InvalidPasswordException) {
        Attempt.WrongPassword
    } catch (e: Exception) {
        // PDFBox does not always use the typed exception, so fall back to the message. A file
        // misreported as broken would be a dead end for the user; misreporting it as
        // password-protected only costs one prompt.
        val message = (e.message ?: "").lowercase()
        if ("password" in message || "decrypt" in message) Attempt.WrongPassword
        else Attempt.Broken("${e::class.java.simpleName}: ${e.message}")
    }
}
