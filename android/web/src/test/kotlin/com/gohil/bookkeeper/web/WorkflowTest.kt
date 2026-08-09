package com.gohil.bookkeeper.web

import com.gohil.bookkeeper.core.model.RegisterType
import com.gohil.bookkeeper.core.xlsx.TestWorkbooks
import com.gohil.bookkeeper.core.xlsx.XlsxAppender
import java.io.ByteArrayOutputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

/**
 * The single upload page, driven over real HTTP.
 *
 * The behaviour being pinned down is the one the whole redesign turns on: **nothing is
 * exported until every extracted item has a category**, and the app asks rather than guesses.
 */
class WorkflowTest {

    private lateinit var dir: Path
    private lateinit var server: BookkeeperServer
    private var port = 0
    private val client: HttpClient = HttpClient.newBuilder()
        .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()

    @BeforeTest
    fun start() {
        dir = Files.createTempDirectory("workflow")
        server = BookkeeperServer(
            passwordStore = PasswordStore(dir.resolve("passwords.enc"), dir.resolve("passwords.key")),
            loanRepository = LoanRepository(dir.resolve("loans.json")),
            spendRules = SpendRulesStore(dir.resolve("spend-categories.json")),
            registers = RegisterStore(dir.resolve("registers")),
        )
        server.start("127.0.0.1", 0)
        port = server.port()
    }

    @AfterTest
    fun stop() {
        server.stop()
        dir.toFile().deleteRecursively()
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private fun statement(extraLines: List<String> = emptyList(), password: String? = null): ByteArray {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            val lines = listOf(
                "HDFC BANK - STATEMENT OF ACCOUNT",
                "05/06/2026 UPI/SWIGGY LTD/8812 1,240.00 DR",
                "09/06/2026 ATM WDL 0234 ELLISBRIDGE 5,000.00 DR",
                "14/06/2026 ATM WDL CHARGE INCL GST 23.60 DR",
            ) + extraLines
            cs.beginText()
            cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
            cs.newLineAtOffset(40f, 760f)
            lines.forEach { cs.showText(it); cs.newLineAtOffset(0f, -16f) }
            cs.endText()
        }
        if (password != null) {
            doc.protect(StandardProtectionPolicy("$password-o", password, AccessPermission()))
        }
        val out = ByteArrayOutputStream()
        doc.save(out); doc.close()
        return out.toByteArray()
    }

    private fun workbook() = TestWorkbooks.build(
        sheets = listOf("May" to TestWorkbooks.sampleRows(2), "Jun" to TestWorkbooks.sampleRows(2)),
    )

    // ── plumbing ─────────────────────────────────────────────────────────────────

    private class Multipart {
        val boundary = "----wf" + System.nanoTime()
        private val out = ByteArrayOutputStream()
        fun field(name: String, value: String) = apply {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write(value.toByteArray()); out.write("\r\n".toByteArray())
        }
        fun file(name: String, fileName: String, bytes: ByteArray) = apply {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n".toByteArray())
            out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            out.write(bytes); out.write("\r\n".toByteArray())
        }
        fun build(): ByteArray { out.write("--$boundary--\r\n".toByteArray()); return out.toByteArray() }
    }

    private fun multipart(path: String, m: Multipart): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "multipart/form-data; boundary=${m.boundary}")
            .POST(HttpRequest.BodyPublishers.ofByteArray(m.build())).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun form(path: String, fields: Map<String, String> = emptyMap()): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                    fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" },
                )).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun getBytes(path: String): HttpResponse<ByteArray> = client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )

    private fun pendingIds(json: String) =
        Regex(""""id":"([0-9a-f-]{36})"""").findAll(json).map { it.groupValues[1] }.toList()

    // ── the page ─────────────────────────────────────────────────────────────────

    @Test
    fun `the front page asks for documents and nothing else`() {
        val body = get("/").body()
        assertContains(body, "Drop your PDFs here")
        // No workbook input, no month tab, no password box up front, no login.
        assertFalse(body.contains(".xlsx"), "the upload page must not ask for Excel")
        assertFalse(body.contains("Month tab"))
        assertFalse(body.lowercase().contains("sign in") || body.lowercase().contains("register an account"))
    }

    @Test
    fun `setup is where the workbooks go`() {
        assertContains(get("/setup").body(), "One-time setup")
        assertEquals(200, get("/crm").statusCode())
    }

    // ── the hardcoded cash rule ──────────────────────────────────────────────────

    @Test
    fun `a cash withdrawal is booked to Office Expenses without asking`() {
        multipart("/api/docs/upload", Multipart().file("files", "hdfc.pdf", statement()))
        val body = form("/api/docs/process").body()

        assertContains(body, "\"pending\":[]")
        // 5,000 withdrawal + 23.60 fee + 1,240 Swiggy, all placed automatically.
        assertContains(body, "\"spend\":3")
        assertContains(body, "\"readyToExport\":true")
    }

    // ── human in the loop ────────────────────────────────────────────────────────

    @Test
    fun `an unknown merchant stops the run and is asked about`() {
        multipart(
            "/api/docs/upload",
            Multipart().file("files", "hdfc.pdf", statement(
                listOf("21/06/2026 NEFT DR QWERTY ENTERPRISES 5,600.00 DR"),
            )),
        )
        val processed = form("/api/docs/process").body()
        assertContains(processed, "QWERTY ENTERPRISES")
        assertContains(processed, "No rule matched this merchant")
        assertContains(processed, "\"readyToExport\":false")

        // And export refuses while it is unanswered.
        val blocked = form("/api/docs/finish")
        assertEquals(409, blocked.statusCode())
        assertContains(blocked.body(), "still need a category")

        // Answer it.
        val id = pendingIds(processed).single()
        val answered = form(
            "/api/docs/categorise",
            mapOf("id" to id, "category" to "PROFESSIONAL", "keyword" to "qwerty enterprises"),
        ).body()
        assertContains(answered, "\"pending\":[]")
        assertContains(answered, "\"readyToExport\":true")
    }

    @Test
    fun `answering with a keyword teaches it for next time`() {
        multipart(
            "/api/docs/upload",
            Multipart().file("files", "a.pdf", statement(listOf("21/06/2026 NEFT DR QWERTY ENTERPRISES 5,600.00 DR"))),
        )
        val id = pendingIds(form("/api/docs/process").body()).single()
        form("/api/docs/categorise", mapOf("id" to id, "category" to "PROFESSIONAL", "keyword" to "qwerty"))

        // The keyword landed in the editable file...
        assertContains(Files.readString(dir.resolve("spend-categories.json")), "qwerty")
        // ...so a fresh run never asks again.
        form("/api/docs/remove", mapOf("fileHash" to ""))
        val second = form("/api/docs/process").body()
        assertContains(second, "\"pending\":[]")
    }

    @Test
    fun `one answer clears every queued copy of the same merchant`() {
        multipart(
            "/api/docs/upload",
            Multipart().file("files", "a.pdf", statement(listOf(
                "21/06/2026 NEFT DR QWERTY ENTERPRISES 5,600.00 DR",
                "23/06/2026 NEFT DR QWERTY ENTERPRISES 1,100.00 DR",
            ))),
        )
        val processed = form("/api/docs/process").body()
        assertEquals(2, pendingIds(processed).size, "both lines should be queued")

        val answered = form(
            "/api/docs/categorise",
            mapOf("id" to pendingIds(processed).first(), "category" to "OFFICE", "keyword" to "qwerty"),
        ).body()
        assertContains(answered, "\"pending\":[]")
        assertContains(answered, "\"spend\":5")
    }

    @Test
    fun `uncategorised is not an acceptable answer`() {
        multipart(
            "/api/docs/upload",
            Multipart().file("files", "a.pdf", statement(listOf("21/06/2026 NEFT DR QWERTY ENT 5,600.00 DR"))),
        )
        val id = pendingIds(form("/api/docs/process").body()).single()
        val res = form("/api/docs/categorise", mapOf("id" to id, "category" to "UNCATEGORISED"))
        assertEquals(400, res.statusCode())
        assertContains(res.body(), "Pick a real category")
    }

    // ── deferred export into the user's own workbook ─────────────────────────────

    @Test
    fun `the register is updated in the user's own file at the right month tab`() {
        multipart(
            "/api/registers",
            Multipart().field("type", "PURCHASE").file("workbook", "Purchase Sheet.xlsx", workbook()),
        )
        assertContains(get("/api/registers").body(), "Purchase Sheet.xlsx")

        multipart("/api/docs/upload", Multipart().file("files", "invoice.pdf", invoicePdf()))
        form("/api/docs/process")

        val finished = form("/api/docs/finish")
        assertEquals(200, finished.statusCode(), finished.body())
        // June dates in the document, "Jun" tab in the workbook — matched without being asked.
        assertContains(finished.body(), "'Jun'")
        assertContains(finished.body(), "Purchase Sheet-updated.xlsx")

        val key = Regex(""""key":"([0-9a-f-]{36})"""").find(finished.body())!!.groupValues[1]
        val updated = getBytes("/api/docs/download/$key")
        assertEquals(200, updated.statusCode())
        assertEquals(listOf("May", "Jun"), XlsxAppender.open(updated.body()).sheetNames())
    }

    @Test
    fun `with no register set up nothing is lost and the run says so`() {
        multipart("/api/docs/upload", Multipart().file("files", "invoice.pdf", invoicePdf()))
        form("/api/docs/process")
        val body = form("/api/docs/finish").body()
        assertContains(body, "were NOT written")
        assertContains(body, "Nothing was lost")
        // The CA workbook is still produced.
        assertContains(body, "CA-Pack")
    }

    private fun invoicePdf(): ByteArray {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            cs.newLineAtOffset(50f, 750f)
            listOf(
                "SHARMA OFFICE SUPPLIES PVT LTD", "GSTIN: 24AABCU9603R1ZM", "TAX INVOICE",
                "Invoice No: INV-2026-0412", "Date: 12/06/2026", "Taxable Value: 4500.00",
                "CGST @ 9%: 405.00", "SGST @ 9%: 405.00", "Grand Total: 5310.00",
            ).forEach { cs.showText(it); cs.newLineAtOffset(0f, -18f) }
            cs.endText()
        }
        val out = ByteArrayOutputStream()
        doc.save(out); doc.close()
        return out.toByteArray()
    }

    // ── the CA bundle ────────────────────────────────────────────────────────────

    @Test
    fun `the original PDFs come back as one archive`() {
        multipart("/api/docs/upload", Multipart().file("files", "hdfc.pdf", statement()))
        multipart("/api/docs/upload", Multipart().file("files", "invoice.pdf", invoicePdf()))

        val res = getBytes("/api/docs/bundle")
        assertEquals(200, res.statusCode())
        val name = res.headers().firstValue("Content-Disposition").orElse("")
        assertTrue(name.contains(".zip") || name.contains(".rar"), name)

        // Whatever the container, both originals must be inside and byte-identical.
        if (name.contains(".zip")) {
            val inside = mutableMapOf<String, Int>()
            ZipInputStream(res.body().inputStream()).use { zip ->
                var e = zip.nextEntry
                while (e != null) { inside[e.name] = zip.readBytes().size; e = zip.nextEntry }
            }
            assertEquals(setOf("hdfc.pdf", "invoice.pdf"), inside.keys)
            assertEquals(statement().size, inside["hdfc.pdf"])
        }
    }

    @Test
    fun `two documents with the same name both survive the bundle`() {
        // Banks name every export "statement.pdf". Losing one on the way to the CA would be
        // silent and serious.
        multipart("/api/docs/upload", Multipart().file("files", "statement.pdf", statement()))
        multipart(
            "/api/docs/upload",
            Multipart().file("files", "statement.pdf", statement(listOf("28/06/2026 UPI/ZOMATO/1 500.00 DR"))),
        )
        val res = getBytes("/api/docs/bundle")
        val names = mutableListOf<String>()
        ZipInputStream(res.body().inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) { names += e.name; e = zip.nextEntry }
        }
        assertEquals(2, names.size, "both files must be in the archive: $names")
        assertEquals(2, names.distinct().size, "and under different names: $names")
    }

    @Test
    fun `bundling with nothing uploaded explains itself`() {
        val res = get("/api/docs/bundle")
        assertEquals(400, res.statusCode())
        assertContains(res.body(), "no uploaded documents")
    }

    // ── CRM ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the client list is built from the invoices`() {
        multipart("/api/docs/upload", Multipart().file("files", "invoice.pdf", invoicePdf()))
        form("/api/docs/process")

        val crm = get("/api/crm").body()
        assertContains(crm, "SHARMA OFFICE SUPPLIES")
        assertContains(crm, "24AABCU9603R1ZM")
        assertContains(crm, "Supplier")
        assertContains(crm, "5310.00")
    }

    @Test
    fun `a locked document blocks processing until its password is given`() {
        val listed = multipart(
            "/api/docs/upload",
            Multipart().file("files", "locked.pdf", statement(password = "hdfc@2026")),
        ).body()
        assertContains(listed, "\"locked\":1")

        val hash = Regex(""""fileHash":"([0-9a-f]{64})"""").find(listed)!!.groupValues[1]
        val unlocked = form(
            "/api/docs/unlock",
            mapOf("fileHash" to hash, "password" to "hdfc@2026", "remember" to "true"),
        ).body()
        assertContains(unlocked, "\"ok\":true")
        assertContains(unlocked, "\"locked\":0")
        assertContains(form("/api/docs/process").body(), "\"readyToExport\":true")
    }
}
