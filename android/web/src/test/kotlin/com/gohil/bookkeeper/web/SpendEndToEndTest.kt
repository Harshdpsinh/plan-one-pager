package com.gohil.bookkeeper.web

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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.gohil.bookkeeper.core.xlsx.XlsxAppender
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

/**
 * The Spend Analysis screen over real HTTP: upload, per-file unlock, categorised summary,
 * loans and cash flow.
 *
 * The store and the loan file are redirected into a temp directory so the test can never touch
 * the real ones in the developer's home.
 */
class SpendEndToEndTest {

    private lateinit var dir: Path
    private lateinit var server: BookkeeperServer
    private var port = 0
    private val client: HttpClient = HttpClient.newBuilder()
        .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build()

    @BeforeTest
    fun start() {
        dir = Files.createTempDirectory("spend")
        server = BookkeeperServer(
            passwordStore = PasswordStore(dir.resolve("passwords.enc"), dir.resolve("passwords.key")),
            loanRepository = LoanRepository(dir.resolve("loans.json")),
            spendRules = SpendRulesStore(dir.resolve("spend-categories.json")),
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

    private fun statementPdf(password: String? = null): ByteArray {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            val lines = listOf(
                "HDFC BANK - STATEMENT OF ACCOUNT",
                "05/06/2026 UPI/SWIGGY LTD/8812 1,240.00 DR",
                "07/06/2026 POS IRCTC WEB BOOKING 3,410.50 DR",
                "11/06/2026 AMAZON WEB SERVICES INDIA 8,900.00 DR",
                "14/06/2026 NEFT DR AIRTEL BROADBAND 1,199.00 DR",
                "18/06/2026 ACH D- SIP INSTALMENT PARAG PARIKH 10,000.00 DR",
                "21/06/2026 NEFT CR SALARY CREDIT 85,000.00 CR",
            )
            cs.beginText()
            cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
            cs.newLineAtOffset(40f, 760f)
            lines.forEach { cs.showText(it); cs.newLineAtOffset(0f, -18f) }
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

    // ── plumbing ─────────────────────────────────────────────────────────────────

    private class Multipart {
        val boundary = "----sp" + System.nanoTime()
        private val out = ByteArrayOutputStream()

        fun file(name: String, fileName: String, bytes: ByteArray) = apply {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n".toByteArray())
            out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            out.write(bytes)
            out.write("\r\n".toByteArray())
        }

        fun build(): ByteArray {
            out.write("--$boundary--\r\n".toByteArray())
            return out.toByteArray()
        }
    }

    private fun upload(multipart: Multipart): String = client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/spend/upload"))
            .header("Content-Type", "multipart/form-data; boundary=${multipart.boundary}")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.build()))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    private fun form(path: String, fields: Map<String, String>): HttpResponse<String> {
        val body = fields.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
        }
        return client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun hashOf(json: String): String =
        Regex(""""fileHash"\s*:\s*"([0-9a-f]{64})"""").find(json)!!.groupValues[1]

    /** Every worksheet part concatenated, for asserting a value landed somewhere in the book. */
    private fun worksheetText(bytes: ByteArray): String = buildString {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("xl/worksheets/")) append(zip.readBytes().decodeToString())
                entry = zip.nextEntry
            }
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────────

    @Test
    fun `serves the spend page`() {
        val res = get("/spend")
        assertEquals(200, res.statusCode())
        assertContains(res.body(), "Spend Analysis")
        assertContains(res.body(), "Personal loans")
    }

    @Test
    fun `an unprotected statement is analysed without ever asking for a password`() {
        val listed = upload(Multipart().file("files", "hdfc-june.pdf", statementPdf()))
        assertContains(listed, "\"needsPassword\":0")
        assertContains(listed, "\"state\":\"OPEN\"")

        val res = form("/api/spend/analyse", emptyMap())
        assertEquals(200, res.statusCode(), res.body())
        val body = res.body()

        // Four debits categorised, and the salary credit deliberately left out of spending.
        assertContains(body, "MEALS")
        assertContains(body, "TRAVEL")
        assertContains(body, "SOFTWARE")
        assertContains(body, "UTILITIES")
        assertFalse(body.contains("85000"), "a credit is money in, not spending")
        assertContains(body, "\"matchedOn\":\"swiggy\"")
    }

    @Test
    fun `a protected statement prompts for that one file and then remembers it`() {
        // One set of bytes, uploaded twice. Identity is the file's content — the same PDF
        // re-encrypted would carry a fresh salt and be a different document, which is correct:
        // it is not the file whose password was verified.
        val bytes = statementPdf(password = "hdfc@2026")
        val listed = upload(Multipart().file("files", "locked.pdf", bytes))
        assertContains(listed, "\"needsPassword\":1")
        assertContains(listed, "\"state\":\"NEEDS_PASSWORD\"")
        val hash = hashOf(listed)

        val wrong = form("/api/spend/unlock", mapOf("fileHash" to hash, "password" to "nope"))
        assertContains(wrong.body(), "\"ok\":false")
        assertContains(wrong.body(), "did not open locked.pdf")

        val right = form(
            "/api/spend/unlock",
            mapOf("fileHash" to hash, "password" to "hdfc@2026", "remember" to "true"),
        )
        assertContains(right.body(), "\"ok\":true")
        assertContains(right.body(), "\"needsPassword\":0")

        assertContains(form("/api/spend/analyse", emptyMap()).body(), "TRAVEL")

        // The same document uploaded again — in a fresh browser session — opens straight away.
        val fresh = HttpClient.newBuilder()
            .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
        val multipart = Multipart().file("files", "locked-again.pdf", bytes)
        val second = fresh.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/spend/upload"))
                .header("Content-Type", "multipart/form-data; boundary=${multipart.boundary}")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.build()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        assertContains(second, "\"state\":\"UNLOCKED_FROM_STORE\"")
        assertContains(second, "\"needsPassword\":0")
    }

    @Test
    fun `mixing a locked and an unlocked file only asks about the locked one`() {
        val listed = upload(
            Multipart()
                .file("files", "open.pdf", statementPdf())
                .file("files", "locked.pdf", statementPdf(password = "x1")),
        )
        assertContains(listed, "\"needsPassword\":1")
        // The open one is ready with no prompt at all, which is the point of probing per file.
        assertContains(listed, "\"fileName\":\"open.pdf\",\"state\":\"OPEN\"")
    }

    @Test
    fun `the browser is never told what any password is`() {
        val listed = upload(Multipart().file("files", "locked.pdf", statementPdf(password = "topsecret")))
        val hash = hashOf(listed)
        val unlocked = form(
            "/api/spend/unlock",
            mapOf("fileHash" to hash, "password" to "topsecret", "remember" to "true"),
        ).body()
        assertFalse(unlocked.contains("topsecret"), "the password must not come back down the wire")
        assertFalse(get("/api/spend/passwords").body().contains("topsecret"))
    }

    @Test
    fun `a SIP is separated from spending rather than charted as an expense`() {
        upload(Multipart().file("files", "hdfc-june.pdf", statementPdf()))
        val body = form("/api/spend/analyse", emptyMap()).body()

        assertContains(body, "\"investedTotal\":\"10000.00\"")
        assertContains(body, "MUTUAL_FUND")
        // The four real expenses only: 1,240 + 3,410.50 + 8,900 + 1,199.
        assertContains(body, "\"total\":\"14749.50\"")
        assertFalse(
            body.substringBefore("\"investments\"").contains("PARAG PARIKH"),
            "the SIP must not appear among the categorised expenses",
        )

        assertContains(get("/api/spend/cashflow").body(), "\"invested\":\"10000.00\"")
    }

    @Test
    fun `downloads a CA workbook that a spreadsheet reader can open`() {
        upload(Multipart().file("files", "hdfc-june.pdf", statementPdf()))
        form("/api/spend/analyse", emptyMap())
        form(
            "/api/spend/loans",
            mapOf(
                "counterparty" to "Ramesh Patel", "direction" to "GIVEN", "principal" to "50000",
                "annualRatePct" to "0", "startDate" to "2026-01-10", "termMonths" to "10",
            ),
        )

        val res = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/spend/export")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, res.statusCode())
        assertContains(
            res.headers().firstValue("Content-Disposition").orElse(""),
            "CA-Pack-Jun-2026.xlsx",
            message = "the filename should name the period so it explains itself in an inbox",
        )

        // Reopening it with the appender is the real check: if the package were malformed,
        // this is where it would fail rather than in Excel on the accountant's machine.
        assertEquals(
            listOf(
                "Summary", "Expenses", "Expense by category", "Investments",
                "Sales (GST)", "Purchases (GST)", "Loans", "Cash flow",
            ),
            XlsxAppender.open(res.body()).sheetNames(),
        )

        val text = worksheetText(res.body())
        assertContains(text, "SWIGGY")
        assertContains(text, "PARAG PARIKH")
        assertContains(text, "Ramesh Patel")

        // Written to disk so verify_fixtures.py can reopen it with openpyxl.
        java.io.File("build/web-fixtures").apply { mkdirs() }
            .resolve("web_ca_pack.xlsx").writeBytes(res.body())
    }

    @Test
    fun `exporting with nothing loaded explains what to do instead of failing silently`() {
        val res = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/spend/export")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(400, res.statusCode())
        assertContains(res.body(), "Analyse some statements")
    }

    @Test
    fun `a loan can be recorded, repaid and seen in cash flow`() {
        val saved = form(
            "/api/spend/loans",
            mapOf(
                "counterparty" to "Ramesh Patel",
                "direction" to "GIVEN",
                "principal" to "50000",
                "annualRatePct" to "0",
                "startDate" to "2026-01-10",
                "termMonths" to "10",
            ),
        )
        assertEquals(200, saved.statusCode(), saved.body())
        assertContains(saved.body(), "Ramesh Patel")
        assertContains(saved.body(), "\"owedToYou\":\"50000.00\"")
        val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(saved.body())!!.groupValues[1]

        val repaid = form(
            "/api/spend/loans/repayment",
            mapOf("id" to id, "date" to "2026-02-10", "amount" to "20000"),
        )
        assertContains(repaid.body(), "\"owedToYou\":\"30000.00\"")
        assertContains(repaid.body(), "\"totalRepaid\":\"20000.00\"")

        val flow = get("/api/spend/cashflow").body()
        assertContains(flow, "\"loansDisbursed\":\"50000\"")
        assertContains(flow, "\"repaymentsReceived\":\"20000\"")

        // Editing the loan must not wipe the repayment already recorded against it.
        val edited = form(
            "/api/spend/loans",
            mapOf(
                "id" to id,
                "counterparty" to "Ramesh Patel",
                "direction" to "GIVEN",
                "principal" to "50000",
                "annualRatePct" to "6",
                "startDate" to "2026-01-10",
                "termMonths" to "12",
            ),
        )
        assertContains(edited.body(), "\"totalRepaid\":\"20000.00\"")

        assertTrue(Files.exists(dir.resolve("loans.json")), "the loan book must survive a restart")
        assertContains(form("/api/spend/loans/delete", mapOf("id" to id)).body(), "\"loans\":[]")
    }

    @Test
    fun `a loan with a nonsense amount is refused with a readable message`() {
        val res = form(
            "/api/spend/loans",
            mapOf("counterparty" to "X", "direction" to "GIVEN", "principal" to "abc", "startDate" to "2026-01-01"),
        )
        assertEquals(400, res.statusCode())
        assertContains(res.body(), "Enter a number")
    }

    @Test
    fun `forgetting the remembered passwords empties the store`() {
        val listed = upload(Multipart().file("files", "locked.pdf", statementPdf(password = "abc123")))
        form("/api/spend/unlock", mapOf("fileHash" to hashOf(listed), "password" to "abc123"))
        assertContains(get("/api/spend/passwords").body(), "\"remembered\":1")

        form("/api/spend/passwords/forget", emptyMap())
        assertContains(get("/api/spend/passwords").body(), "\"remembered\":0")
    }

    @Test
    fun `editing the keyword file changes the next analysis with no restart`() {
        // The whole promise of the file: a merchant the shipped rules do not know becomes
        // categorised by editing a list, on the very next run.
        upload(Multipart().file("files", "hdfc-june.pdf", statementPdf()))
        assertContains(form("/api/spend/analyse", emptyMap()).body(), "\"matchedOn\":\"swiggy\"")

        Files.writeString(
            dir.resolve("spend-categories.json"),
            """{"categories":{"PROFESSIONAL":{"keywords":["swiggy"],"priority":9}}}""",
        )

        val after = form("/api/spend/analyse", emptyMap()).body()
        assertContains(after, "PROFESSIONAL")
        assertFalse(after.contains("\"category\":\"MEALS\""), "the edited rule should win")
    }

    @Test
    fun `a broken keyword file falls back to the built-in lists and says so`() {
        Files.writeString(dir.resolve("spend-categories.json"), "{ this is not json")
        upload(Multipart().file("files", "hdfc-june.pdf", statementPdf()))

        val body = form("/api/spend/analyse", emptyMap()).body()
        // Still classified, using the defaults...
        assertContains(body, "\"matchedOn\":\"swiggy\"")
        // ...but the user is told, rather than quietly wondering why their edit did nothing.
        assertContains(body, "could not be read")

        assertContains(get("/api/spend/rules").body(), "spend-categories.json")
    }

    @Test
    fun `resetting restores the shipped lists`() {
        Files.writeString(dir.resolve("spend-categories.json"), """{"categories":{}}""")
        form("/api/spend/rules/reset", emptyMap())

        val rules = get("/api/spend/rules").body()
        assertContains(rules, "MEALS")
        assertContains(rules, "MUTUAL_FUND")
        assertFalse(rules.contains("could not be read"))
    }
}
