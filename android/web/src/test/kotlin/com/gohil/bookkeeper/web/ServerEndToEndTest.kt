package com.gohil.bookkeeper.web

import com.gohil.bookkeeper.core.xlsx.TestWorkbooks
import com.gohil.bookkeeper.core.xlsx.XlsxAppender
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy

/**
 * Drives the real server over real HTTP with real files.
 *
 * This is the check the Android app cannot have in this project: an actual upload, an actual
 * parse, an actual .xlsx coming back out, verified by reopening it. It covers the whole path
 * — multipart handling, extraction, the shared pipeline and the Excel writer — rather than
 * any one piece in isolation.
 */
class ServerEndToEndTest {

    private lateinit var server: BookkeeperServer
    private var port = 0
    private val client: HttpClient = HttpClient.newBuilder()
        .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build()

    @BeforeTest
    fun start() {
        server = BookkeeperServer()
        // Port 0 lets the OS pick a free one, so the test cannot collide with a real server.
        server.start("127.0.0.1", 0)
        port = server.port()
    }

    @AfterTest
    fun stop() = server.stop()

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private fun invoicePdf(password: String? = null): ByteArray {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        PDPageContentStream(doc, page).use { cs ->
            val lines = listOf(
                "SHARMA OFFICE SUPPLIES PVT LTD",
                "GSTIN: 24AABCU9603R1ZM",
                "TAX INVOICE",
                "Invoice No: INV-2025-0412",
                "Date: 12/06/2025",
                "Taxable Value: 4500.00",
                "CGST @ 9%: 405.00",
                "SGST @ 9%: 405.00",
                "Grand Total: 5310.00",
            )
            cs.beginText()
            cs.setFont(font, 12f)
            cs.newLineAtOffset(50f, 750f)
            lines.forEach { cs.showText(it); cs.newLineAtOffset(0f, -18f) }
            cs.endText()
        }
        if (password != null) {
            doc.protect(StandardProtectionPolicy(password + "-owner", password, AccessPermission()))
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun workbook(): ByteArray = TestWorkbooks.build(
        sheets = listOf(
            "May" to TestWorkbooks.sampleRows(2),
            "Jun" to TestWorkbooks.sampleRows(2),
        ),
    )

    // ── multipart ────────────────────────────────────────────────────────────────

    private class Multipart {
        val boundary = "----bk" + System.nanoTime()
        private val out = ByteArrayOutputStream()

        fun field(name: String, value: String) = apply {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write(value.toByteArray())
            out.write("\r\n".toByteArray())
        }

        fun file(name: String, fileName: String, bytes: ByteArray) = apply {
            out.write("--$boundary\r\n".toByteArray())
            out.write(
                "Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n".toByteArray(),
            )
            out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            out.write(bytes)
            out.write("\r\n".toByteArray())
        }

        fun build(): ByteArray {
            out.write("--$boundary--\r\n".toByteArray())
            return out.toByteArray()
        }
    }

    private fun post(path: String, multipart: Multipart): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "multipart/form-data; boundary=${multipart.boundary}")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.build()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    // ── tests ────────────────────────────────────────────────────────────────────

    @Test
    fun `serves the page`() {
        val res = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertContains(res.body(), "Gohil Bookkeeper")
        assertContains(res.body(), "Statement passwords")
    }

    @Test
    fun `processes an invoice and writes it into the workbook`() {
        val processed = post(
            "/api/process",
            Multipart()
                .file("purchaseWorkbook", "Purchase_Sheet.xlsx", workbook())
                .field("purchaseTab", "Jun")
                .field("passwords", "")
                .file("purchaseInvoices", "invoice.pdf", invoicePdf()),
        )
        assertEquals(200, processed.statusCode(), processed.body())
        val body = processed.body()
        assertContains(body, "SHARMA OFFICE SUPPLIES", message = "vendor read from the letterhead")
        assertContains(body, "5310.00")
        assertContains(body, "\"purchaseRows\"")

        val written = post("/api/write", Multipart().field("approved", ""))
        assertEquals(200, written.statusCode(), written.body())
        assertContains(written.body(), "added 1 row")
        assertContains(written.body(), "Purchase_Sheet-updated.xlsx")

        val key = Regex(""""key"\s*:\s*"([^"]+)"""").find(written.body())!!.groupValues[1]
        val download = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/download/$key")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, download.statusCode())

        // The returned bytes must be a workbook that reopens cleanly with both tabs intact.
        val reopened = XlsxAppender.open(download.body())
        assertEquals(listOf("May", "Jun"), reopened.sheetNames())

        // Dumped so verify_fixtures.py can check the actual cell values with openpyxl — an
        // independent parser, rather than the same code that produced the file.
        File("build/web-fixtures").apply { mkdirs() }
            .resolve("web_purchase_after.xlsx")
            .writeBytes(download.body())
    }

    @Test
    fun `opens a password protected PDF using one of several supplied passwords`() {
        val processed = post(
            "/api/process",
            Multipart()
                .file("purchaseWorkbook", "Purchase_Sheet.xlsx", workbook())
                .field("purchaseTab", "Jun")
                // The right one is last, among decoys — the real situation with several banks.
                .field("passwords", "wrong-one\nanother-wrong\nsecret123")
                .file("purchaseInvoices", "protected.pdf", invoicePdf(password = "secret123")),
        )
        assertEquals(200, processed.statusCode(), processed.body())
        assertContains(processed.body(), "SHARMA OFFICE SUPPLIES")
        assertTrue(
            !processed.body().contains("PASSWORD_REQUIRED"),
            "should have opened with the matching password",
        )
    }

    @Test
    fun `reports a protected PDF it cannot open instead of failing the run`() {
        val processed = post(
            "/api/process",
            Multipart()
                .file("purchaseWorkbook", "Purchase_Sheet.xlsx", workbook())
                .field("purchaseTab", "Jun")
                .field("passwords", "not-the-password")
                .file("purchaseInvoices", "protected.pdf", invoicePdf(password = "secret123")),
        )
        assertEquals(200, processed.statusCode(), processed.body())
        assertContains(processed.body(), "PASSWORD_REQUIRED")
    }

    @Test
    fun `refuses a file that is not a workbook with a readable message`() {
        val processed = post(
            "/api/process",
            Multipart()
                .file("purchaseWorkbook", "notes.xlsx", "definitely not a zip".toByteArray())
                .field("purchaseTab", "Jun")
                .file("purchaseInvoices", "invoice.pdf", invoicePdf()),
        )
        assertEquals(200, processed.statusCode())
        val written = post("/api/write", Multipart().field("approved", ""))
        assertEquals(400, written.statusCode())
        assertContains(written.body(), "xlsx")
    }
}
