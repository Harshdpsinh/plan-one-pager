package com.gohil.bookkeeper.web

import com.gohil.bookkeeper.core.logic.Pipeline
import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.Invoice
import com.gohil.bookkeeper.core.model.ProcessResult
import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.model.RegisterType
import com.gohil.bookkeeper.core.model.ReviewItem
import com.gohil.bookkeeper.core.model.ReviewReason
import com.gohil.bookkeeper.core.model.StatementSource
import com.gohil.bookkeeper.core.parse.BankParser
import com.gohil.bookkeeper.core.parse.InvoiceParser
import com.gohil.bookkeeper.core.rules.CategoryRules
import com.gohil.bookkeeper.core.xlsx.XlsxAppender
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.UploadedFile
import java.util.concurrent.ConcurrentHashMap

/**
 * The local web app.
 *
 * State is held per browser session in memory only. Nothing — workbooks, statements or
 * passwords — is written to disk by the server, and the user's own workbook file is never
 * modified: the updated copy is produced as a download, so the original on disk is by
 * construction untouched. That is a stronger guarantee than the phone app can make, and it
 * is why this deliberately does not try to write files in place.
 */
class BookkeeperServer(
    private val token: String? = null,
    /**
     * Null turns off remembering passwords entirely — nothing is written to disk and every
     * protected file is asked about every run. Tests pass a temporary store so they never
     * touch the real one in the user's home directory.
     */
    private val passwordStore: PasswordStore? = PasswordStore(),
    loanRepository: LoanRepository = LoanRepository(),
    spendRules: SpendRulesStore = SpendRulesStore(),
) {

    private val sessions = ConcurrentHashMap<String, Session>()
    private val extractor = JvmExtractor()
    private val spend = SpendRoutes(passwordStore, loanRepository, extractor, spendRules)
    private var app: Javalin? = null

    class Session {
        var purchaseWorkbook: Upload? = null
        var salesWorkbook: Upload? = null
        var purchaseTab: String = ""
        var salesTab: String = ""
        var result: ProcessResult? = null
        val outputs = ConcurrentHashMap<String, ByteArray>()

        /** The Spend Analysis side of the same browser session. */
        val spend = SpendRoutes.Session()
    }

    data class Upload(val name: String, val bytes: ByteArray)

    fun start(host: String, port: Int) {
        app = Javalin.create { config ->
            config.showJavalinBanner = false
            config.jetty.multipartConfig.maxTotalRequestSize(200, io.javalin.config.SizeUnit.MB)
            config.jetty.multipartConfig.maxFileSize(50, io.javalin.config.SizeUnit.MB)
        }.apply {
            before { ctx -> requireToken(ctx) }
            get("/") { ctx -> ctx.contentType("text/html; charset=utf-8").result(page("index.html")) }
            get("/spend") { ctx -> ctx.contentType("text/html; charset=utf-8").result(page("spend.html")) }
            post("/api/process") { ctx -> handleProcess(ctx) }
            post("/api/write") { ctx -> handleWrite(ctx) }
            get("/api/download/{key}") { ctx -> handleDownload(ctx) }
            spend.register(
                app = this,
                sessionOf = { ctx -> sessionOf(ctx).spend },
                // The CA pack wants the GST detail too, and it is already in this session.
                gstOf = { ctx -> sessionOf(ctx).result },
            )
            exception(Exception::class.java) { e, ctx ->
                ctx.status(400).json(mapOf("error" to (e.message ?: "Something went wrong.")))
            }
        }.start(host, port)
    }

    fun stop() {
        app?.stop()
    }

    fun port(): Int = app?.port() ?: -1

    /**
     * Gate on the access token when one is set.
     *
     * Only applies when the server was started with --network. On loopback there is no token
     * and no prompt, because a login screen guarding a service only reachable from the same
     * machine is friction without a threat to answer.
     */
    private fun requireToken(ctx: Context) {
        val expected = token ?: return
        val supplied = ctx.queryParam("token")
            ?: ctx.header("X-Access-Token")
            ?: ctx.cookie("token")
        if (supplied == expected) {
            if (ctx.cookie("token") != expected) ctx.cookie("token", expected)
            return
        }
        ctx.status(401)
        throw io.javalin.http.UnauthorizedResponse(
            "Access token required. Open the link printed in the terminal when the server started.",
        )
    }

    private fun sessionOf(ctx: Context): Session {
        val id = ctx.cookie(SESSION_COOKIE) ?: java.util.UUID.randomUUID().toString().also {
            ctx.cookie(SESSION_COOKIE, it)
        }
        return sessions.getOrPut(id) { Session() }
    }

    // ── processing ───────────────────────────────────────────────────────────────

    private fun handleProcess(ctx: Context) {
        val session = sessionOf(ctx)

        ctx.uploadedFile("purchaseWorkbook")?.let {
            session.purchaseWorkbook = Upload(it.filename(), it.content().readBytes())
        }
        ctx.uploadedFile("salesWorkbook")?.let {
            session.salesWorkbook = Upload(it.filename(), it.content().readBytes())
        }
        session.purchaseTab = ctx.formParam("purchaseTab").orEmpty().trim()
        session.salesTab = ctx.formParam("salesTab").orEmpty().trim()

        // One password per line. Every encrypted PDF is tried against all of them, which is
        // what makes several banks and several cards work without asking which is which.
        val passwords = ctx.formParam("passwords").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

        val purchaseInvoices = mutableListOf<Invoice>()
        val salesInvoices = mutableListOf<Invoice>()
        val transactions = mutableListOf<BankTxn>()
        val unparsed = mutableListOf<String>()
        val failures = mutableListOf<ReviewItem>()

        fun handle(files: List<UploadedFile>, kind: String) {
            for (file in files) {
                val bytes = file.content().readBytes()

                // Ask the store about this specific file first. When it knows the password the
                // typed list is irrelevant, and when it does not, the typed list still works —
                // so an existing habit of pasting every password keeps functioning while the
                // Spend screen's per-file prompt gradually makes it unnecessary.
                val probe = PdfProbe.probe(bytes, file.filename(), passwordStore)
                val candidates = probe.password?.let { listOf(it) } ?: passwords
                // A password typed here that opens the file is worth remembering too.
                if (probe.needsPassword) {
                    passwords.firstOrNull { PdfProbe.verify(bytes, it, passwordStore, remember = true) }
                }

                when (val extracted = extractor.extract(bytes, file.filename(), candidates)) {
                    is JvmExtractor.Result.PasswordProblem -> failures += ReviewItem(
                        sourceFile = file.filename(),
                        reason = ReviewReason.PASSWORD_REQUIRED,
                        detail = buildString {
                            if (extracted.triedCount == 0) {
                                append("This PDF is password protected but you did not enter any passwords.")
                            } else {
                                append("Password protected. Tried ${extracted.triedCount} ")
                                append("password(s) and none opened it. ")
                                append("Check for typos, and note that bank passwords are usually ")
                                append("case sensitive and often combine name and date of birth.")
                            }
                            append("\n\nTechnical detail: ${extracted.detail}")
                        },
                        target = if (kind == "salesInvoices") RegisterType.SALES else RegisterType.PURCHASE,
                    )

                    is JvmExtractor.Result.Failed -> failures += ReviewItem(
                        sourceFile = file.filename(),
                        reason = ReviewReason.EXTRACTION_FAILED,
                        detail = extracted.reason,
                        target = if (kind == "salesInvoices") RegisterType.SALES else RegisterType.PURCHASE,
                    )

                    is JvmExtractor.Result.Text -> when (kind) {
                        "purchaseInvoices" ->
                            purchaseInvoices += InvoiceParser.parse(extracted.text, file.filename())
                        "salesInvoices" ->
                            salesInvoices += InvoiceParser.parse(extracted.text, file.filename())
                        "bankStatements" -> BankParser.parse(extracted.text, StatementSource.BANK).let {
                            transactions += it.transactions
                            unparsed += it.unparsed
                        }
                        "cardStatements" ->
                            BankParser.parse(extracted.text, StatementSource.CREDIT_CARD).let {
                                transactions += it.transactions
                                unparsed += it.unparsed
                            }
                    }
                }
            }
        }

        handle(ctx.uploadedFiles("purchaseInvoices"), "purchaseInvoices")
        handle(ctx.uploadedFiles("salesInvoices"), "salesInvoices")
        handle(ctx.uploadedFiles("bankStatements"), "bankStatements")
        handle(ctx.uploadedFiles("cardStatements"), "cardStatements")

        val result = Pipeline(CategoryRules.defaults()).process(
            Pipeline.Input(
                purchaseInvoices = purchaseInvoices,
                salesInvoices = salesInvoices,
                transactions = transactions,
                unparsedLines = unparsed,
                failures = failures,
            ),
        )
        session.result = result
        ctx.json(result.toJsonMap())
    }

    // ── writing ──────────────────────────────────────────────────────────────────

    private fun handleWrite(ctx: Context) {
        val session = sessionOf(ctx)
        val result = session.result ?: throw IllegalStateException("Process some documents first.")

        val approved = ctx.formParam("approved").orEmpty()
            .split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

        val approvedRows = approved.mapNotNull { result.review.getOrNull(it) }
            .mapNotNull { item -> item.suggested?.let { item.target to it } }

        val purchaseRows = result.purchaseRows +
            approvedRows.filter { it.first == RegisterType.PURCHASE }.map { it.second }
        val salesRows = result.salesRows +
            approvedRows.filter { it.first == RegisterType.SALES }.map { it.second }

        val downloads = mutableListOf<Map<String, String>>()
        session.outputs.clear()

        fun write(upload: Upload?, tab: String, rows: List<RegisterRow>, type: RegisterType, label: String) {
            if (upload == null || tab.isBlank() || rows.isEmpty()) return
            val appender = XlsxAppender.open(upload.bytes)
            val outcome = appender.append(
                XlsxAppender.Request(
                    sheetName = tab,
                    rows = rows,
                    registerType = type,
                    createSheetIfMissing = true,
                ),
            )
            val bytes = appender.toByteArray()
            // Reopen before offering it: a workbook that will not parse back must never be
            // handed to the user as their new register.
            XlsxAppender.open(bytes)

            val key = java.util.UUID.randomUUID().toString()
            session.outputs[key] = bytes
            val name = upload.name.substringBeforeLast('.') + "-updated.xlsx"
            downloads += mapOf(
                "key" to key,
                "name" to name,
                "summary" to buildString {
                    append("$label — added ${outcome.rowsWritten} row(s) to '${outcome.sheetName}', ")
                    append("SR NO ${outcome.firstSrNo} onwards.")
                    if (outcome.addedColumns.isNotEmpty()) {
                        append(" Added column(s): ${outcome.addedColumns.joinToString(", ")}.")
                    }
                    outcome.warnings.forEach { append(" $it") }
                },
            )
        }

        write(session.purchaseWorkbook, session.purchaseTab, purchaseRows, RegisterType.PURCHASE, "Purchase Register")
        write(session.salesWorkbook, session.salesTab, salesRows, RegisterType.SALES, "Sales Register")

        ctx.json(mapOf("downloads" to downloads))
    }

    private fun handleDownload(ctx: Context) {
        val session = sessionOf(ctx)
        val bytes = session.outputs[ctx.pathParam("key")]
            ?: throw IllegalStateException("That download has expired. Process the documents again.")
        val name = ctx.queryParam("name") ?: "register-updated.xlsx"
        ctx.contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .header("Content-Disposition", "attachment; filename=\"$name\"")
            .result(bytes)
    }

    private fun ProcessResult.toJsonMap(): Map<String, Any> = mapOf(
        "purchaseRows" to purchaseRows.map { it.toJsonMap() },
        "salesRows" to salesRows.map { it.toJsonMap() },
        "review" to review.mapIndexed { index, item ->
            mapOf(
                "index" to index,
                "sourceFile" to item.sourceFile,
                "reason" to item.reason.name,
                "reasonLabel" to item.reason.friendly(),
                "detail" to item.detail,
                "target" to item.target.name,
                "hasSuggestion" to (item.suggested != null),
                "suggested" to (item.suggested?.toJsonMap() ?: emptyMap<String, Any?>()),
                "rawText" to item.rawText.take(1500),
            )
        },
        "stats" to stats,
        "ocrAvailable" to extractor.let { TesseractOcr().available },
    )

    private fun RegisterRow.toJsonMap(): Map<String, Any?> = mapOf(
        "date" to date?.toString(),
        "gstNo" to gstNo,
        "invoiceNo" to invoiceNo,
        "name" to name,
        "taxable" to taxable?.toPlainString(),
        "cgst" to cgst?.toPlainString(),
        "sgst" to sgst?.toPlainString(),
        "igst" to igst?.toPlainString(),
        "totalGrand" to totalGrand?.toPlainString(),
        "paidDate" to paidDate?.toString(),
        "paymentMethod" to paymentMethod.label,
        "category" to category?.label,
        "gstType" to gstType?.name,
    )

    private fun ReviewReason.friendly(): String = when (this) {
        ReviewReason.LOW_CONFIDENCE -> "Could not read everything"
        ReviewReason.PARSE_FAILED -> "Could not understand this line"
        ReviewReason.EXTRACTION_FAILED -> "Could not open this file"
        ReviewReason.TIMED_OUT -> "Took too long"
        ReviewReason.PASSWORD_REQUIRED -> "Password needed"
        ReviewReason.AMBIGUOUS_CATEGORY -> "Personal or business?"
        ReviewReason.UNMATCHED_CREDIT -> "Unexplained money received"
        ReviewReason.DUPLICATE_SUSPECTED -> "Possible duplicate"
    }

    private fun page(name: String): String =
        javaClass.getResourceAsStream("/web/$name")?.bufferedReader()?.readText()
            ?: "<h1>UI resource missing: $name</h1>"

    companion object {
        private const val SESSION_COOKIE = "bk_session"
    }
}
