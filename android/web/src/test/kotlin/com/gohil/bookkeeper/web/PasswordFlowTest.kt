package com.gohil.bookkeeper.web

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

/**
 * The per-file password behaviour, on real encrypted PDFs.
 *
 * This is the requirement the user called critical: check each file individually, prompt only
 * for the ones that need it, and never ask twice for the same document.
 */
class PasswordFlowTest {

    private lateinit var dir: Path
    private lateinit var store: PasswordStore

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("pwstore")
        store = PasswordStore(dir.resolve("passwords.enc"), dir.resolve("passwords.key"))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun pdf(text: String, password: String? = null): ByteArray {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText(text)
            cs.endText()
        }
        if (password != null) {
            doc.protect(StandardProtectionPolicy("$password-owner", password, AccessPermission()))
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    // ── probing ──────────────────────────────────────────────────────────────────

    @Test
    fun `an unprotected PDF is never prompted for`() {
        val probe = PdfProbe.probe(pdf("plain invoice"), "invoice.pdf", store)
        assertEquals(PdfProbe.State.OPEN, probe.state)
        assertFalse(probe.needsPassword)
    }

    @Test
    fun `a protected PDF asks only for itself`() {
        val probe = PdfProbe.probe(pdf("secret", password = "hdfc2026"), "hdfc.pdf", store)
        assertEquals(PdfProbe.State.NEEDS_PASSWORD, probe.state)
        assertEquals("hdfc.pdf", probe.fileName)
        // The password is not guessed at from anywhere; nothing is returned until it is given.
        assertNull(probe.password)
    }

    @Test
    fun `a non-PDF is not treated as an encryption question at all`() {
        val probe = PdfProbe.probe(byteArrayOf(1, 2, 3), "receipt.jpg", store)
        assertEquals(PdfProbe.State.NOT_A_PDF, probe.state)
    }

    @Test
    fun `garbage that claims to be a PDF is reported as unreadable, not as locked`() {
        val probe = PdfProbe.probe("not a pdf at all".toByteArray(), "broken.pdf", store)
        assertEquals(PdfProbe.State.UNREADABLE, probe.state)
        assertFalse(probe.needsPassword, "prompting for a password would be a dead end here")
    }

    // ── remembering ──────────────────────────────────────────────────────────────

    @Test
    fun `once verified the same file is never asked about again`() {
        val bytes = pdf("statement", password = "icici@123")
        assertEquals(PdfProbe.State.NEEDS_PASSWORD, PdfProbe.probe(bytes, "icici.pdf", store).state)

        assertTrue(PdfProbe.verify(bytes, "icici@123", store, remember = true))

        val again = PdfProbe.probe(bytes, "icici.pdf", store)
        assertEquals(PdfProbe.State.UNLOCKED_FROM_STORE, again.state)
        assertEquals("icici@123", again.password)
    }

    @Test
    fun `the store survives a restart because it is on disk`() {
        val bytes = pdf("statement", password = "axis-99")
        PdfProbe.verify(bytes, "axis-99", store, remember = true)

        // A brand new PasswordStore over the same files — this is what closing and reopening
        // the app actually does.
        val reopened = PasswordStore(dir.resolve("passwords.enc"), dir.resolve("passwords.key"))
        assertEquals(PdfProbe.State.UNLOCKED_FROM_STORE, PdfProbe.probe(bytes, "axis.pdf", reopened).state)
    }

    @Test
    fun `declining to remember keeps it out of the store`() {
        val bytes = pdf("statement", password = "kotak1")
        assertTrue(PdfProbe.verify(bytes, "kotak1", store, remember = false))
        assertEquals(0, store.size())
        assertEquals(PdfProbe.State.NEEDS_PASSWORD, PdfProbe.probe(bytes, "kotak.pdf", store).state)
    }

    @Test
    fun `files are keyed by content so two statements named the same do not collide`() {
        val june = pdf("June statement", password = "june-pw")
        val july = pdf("July statement", password = "july-pw")
        assertNotEquals(PdfProbe.hash(june), PdfProbe.hash(july))

        PdfProbe.verify(june, "june-pw", store, remember = true)
        // Both banks export "statement.pdf"; only the June one is known.
        assertEquals(PdfProbe.State.UNLOCKED_FROM_STORE, PdfProbe.probe(june, "statement.pdf", store).state)
        assertEquals(PdfProbe.State.NEEDS_PASSWORD, PdfProbe.probe(july, "statement.pdf", store).state)
    }

    @Test
    fun `a wrong password is rejected and not remembered`() {
        val bytes = pdf("statement", password = "right")
        assertFalse(PdfProbe.verify(bytes, "wrong", store, remember = true))
        assertEquals(0, store.size())
    }

    @Test
    fun `forgetting everything removes both files from disk`() {
        PdfProbe.verify(pdf("s", password = "p"), "p", store, remember = true)
        assertTrue(Files.exists(dir.resolve("passwords.enc")))
        store.forgetAll()
        assertEquals(0, store.size())
        assertFalse(Files.exists(dir.resolve("passwords.enc")))
        assertFalse(Files.exists(dir.resolve("passwords.key")))
    }

    // ── at rest ──────────────────────────────────────────────────────────────────

    @Test
    fun `the stored file does not contain the password in the clear`() {
        PdfProbe.verify(pdf("s", password = "SuperSecret2026"), "SuperSecret2026", store, remember = true)
        val onDisk = dir.resolve("passwords.enc").readBytes().decodeToString()
        assertFalse(
            onDisk.contains("SuperSecret2026"),
            "a grep-able password file is exactly what encrypting it is meant to prevent",
        )
    }

    @Test
    fun `losing the key file degrades to forgetting rather than crashing`() {
        PdfProbe.verify(pdf("s", password = "p1"), "p1", store, remember = true)
        Files.delete(dir.resolve("passwords.key"))

        val reopened = PasswordStore(dir.resolve("passwords.enc"), dir.resolve("passwords.key"))
        // Nothing recoverable, but the app must still run — the user can always retype.
        assertEquals(0, reopened.size())
    }

    @Test
    fun `passwords containing tabs and newlines round trip intact`() {
        val awkward = "line1\tline2\nline3"
        val bytes = pdf("s", password = awkward)
        PdfProbe.verify(bytes, awkward, store, remember = true)

        val reopened = PasswordStore(dir.resolve("passwords.enc"), dir.resolve("passwords.key"))
        assertEquals(awkward, reopened.get(PdfProbe.hash(bytes)))
    }

    @Test
    fun `next month's statement opens itself with a password proven on last month's`() {
        // Why the per-file store alone was not enough: a bank names every export
        // statement.pdf and re-encrypts it, so July is a different hash from June even
        // though the password never changed. Being asked again every month is precisely
        // what "never ask me twice" was supposed to prevent.
        val june = pdf("June statement", password = "97894070894")
        assertTrue(PdfProbe.verify(june, "97894070894", store, remember = true))

        val july = pdf("July statement", password = "97894070894")
        val probe = PdfProbe.probe(july, "statement.pdf", store)

        assertEquals(PdfProbe.State.UNLOCKED_FROM_STORE, probe.state)
        assertFalse(probe.needsPassword)
        // And it is now known by its own hash, so next time the direct look-up answers.
        assertEquals("97894070894", store.get(PdfProbe.hash(july)))
    }

    @Test
    fun `a file none of the saved passwords opens still asks, and names itself`() {
        PdfProbe.verify(pdf("known", password = "97894070894"), "97894070894", store, remember = true)

        val stranger = pdf("some other bank", password = "totally-different")
        val probe = PdfProbe.probe(stranger, "hdfc-july.pdf", store)

        assertEquals(PdfProbe.State.NEEDS_PASSWORD, probe.state)
        assertEquals("hdfc-july.pdf", probe.fileName)
        assertNull(probe.password)
    }

    @Test
    fun `an unprotected statement is not affected by the saved passwords`() {
        // Trying saved passwords must not change the answer for a file that needs none —
        // that would put a prompt in front of the majority of documents.
        PdfProbe.verify(pdf("known", password = "97894070894"), "97894070894", store, remember = true)

        assertEquals(PdfProbe.State.OPEN, PdfProbe.probe(pdf("open invoice"), "invoice.pdf", store).state)
    }
}
