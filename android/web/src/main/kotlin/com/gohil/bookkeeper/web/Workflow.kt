package com.gohil.bookkeeper.web

import com.gohil.bookkeeper.core.logic.Pipeline
import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.Invoice
import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.model.RegisterType
import com.gohil.bookkeeper.core.model.StatementSource
import com.gohil.bookkeeper.core.parse.BankParser
import com.gohil.bookkeeper.core.parse.InvoiceParser
import com.gohil.bookkeeper.core.rules.CategoryRules
import com.gohil.bookkeeper.core.spend.CaExport
import com.gohil.bookkeeper.core.spend.Crm
import com.gohil.bookkeeper.core.spend.InvestmentItem
import com.gohil.bookkeeper.core.spend.InvestmentSummary
import com.gohil.bookkeeper.core.spend.SpendCategory
import com.gohil.bookkeeper.core.spend.SpendItem
import com.gohil.bookkeeper.core.spend.SpendSummary
import com.gohil.bookkeeper.core.xlsx.XlsxAppender
import io.javalin.Javalin
import io.javalin.http.Context
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The upload-only workflow behind the single page.
 *
 * One rule shapes the whole thing: **nothing is exported until every extracted item has a
 * category.** Anything the rules cannot decide stops the run and is put to the user as a
 * question. That is slower than guessing, and it is the point — these figures go to a
 * chartered accountant, and a wrong category is worse than a question.
 *
 * The registers are not uploaded here. They live in [RegisterStore], configured once, so this
 * page only ever takes PDFs.
 */
class Workflow(
    private val registers: RegisterStore,
    private val rules: SpendRulesStore,
    private val passwords: PasswordStore?,
    private val extractor: JvmExtractor = JvmExtractor(),
    private val bundler: DocumentBundle = DocumentBundle(),
) {

    // ── session state ────────────────────────────────────────────────────────────

    class Session {
        val docs = ConcurrentHashMap<String, Doc>()
        @Volatile var run: Run? = null
        val outputs = ConcurrentHashMap<String, Download>()
    }

    class Doc(
        val fileName: String,
        val bytes: ByteArray,
        @Volatile var state: PdfProbe.State,
        @Volatile var password: String? = null,
        @Volatile var note: String = "",
    )

    data class Download(val name: String, val bytes: ByteArray, val contentType: String)

    /** Everything one processing pass produced, including what it could not decide. */
    class Run {
        val spend = mutableListOf<SpendItem>()
        val investments = mutableListOf<InvestmentItem>()
        val purchaseRows = mutableListOf<RegisterRow>()
        val salesRows = mutableListOf<RegisterRow>()
        val pending = mutableListOf<Pending>()
        val skipped = mutableListOf<Map<String, String>>()
        @Volatile var written = false
    }

    /**
     * One item the rules could not place, waiting on the user.
     *
     * Carries the raw narration and the source file, because the only way someone can answer
     * "what is this?" is by seeing exactly what the statement said and which document it
     * came from.
     */
    data class Pending(
        val id: String,
        val date: LocalDate,
        val description: String,
        val amount: BigDecimal,
        val source: String,
        /** Best guess, offered as a default but never applied on its own. */
        val suggestion: SpendCategory?,
        val why: String,
    )

    // ── routes ───────────────────────────────────────────────────────────────────

    fun register(app: Javalin, sessionOf: (Context) -> Session) {
        app.post("/api/docs/upload") { ctx -> upload(ctx, sessionOf(ctx)) }
        app.post("/api/docs/unlock") { ctx -> unlock(ctx, sessionOf(ctx)) }
        app.post("/api/docs/remove") { ctx -> remove(ctx, sessionOf(ctx)) }
        app.post("/api/docs/process") { ctx -> process(ctx, sessionOf(ctx)) }
        app.post("/api/docs/categorise") { ctx -> categorise(ctx, sessionOf(ctx)) }
        app.post("/api/docs/finish") { ctx -> finish(ctx, sessionOf(ctx)) }
        app.get("/api/docs/bundle") { ctx -> bundle(ctx, sessionOf(ctx)) }
        app.get("/api/docs/download/{key}") { ctx -> download(ctx, sessionOf(ctx)) }
        app.get("/api/docs/state") { ctx -> ctx.json(stateOf(sessionOf(ctx))) }
        app.get("/api/crm") { ctx -> ctx.json(crm(sessionOf(ctx))) }
    }

    // ── 1. upload ────────────────────────────────────────────────────────────────

    private fun upload(ctx: Context, session: Session) {
        val incoming = ctx.uploadedFiles("files")
        if (incoming.isEmpty()) throw IllegalArgumentException("No files were uploaded.")

        for (file in incoming) {
            val bytes = file.content().readBytes()
            val probe = PdfProbe.probe(bytes, file.filename(), passwords)
            if (session.docs.containsKey(probe.fileHash)) continue
            session.docs[probe.fileHash] = Doc(
                fileName = probe.fileName,
                bytes = bytes,
                state = probe.state,
                password = probe.password,
                note = probe.detail,
            )
        }
        session.run = null
        ctx.json(stateOf(session))
    }

    private fun unlock(ctx: Context, session: Session) {
        val hash = ctx.formParam("fileHash").orEmpty()
        val password = ctx.formParam("password").orEmpty()
        val remember = ctx.formParam("remember") != "false"
        val doc = session.docs[hash] ?: throw IllegalArgumentException("That file is no longer loaded.")

        if (PdfProbe.verify(doc.bytes, password, passwords, remember)) {
            doc.state = PdfProbe.State.UNLOCKED_FROM_STORE
            doc.password = password
            doc.note = if (remember) "Unlocked and remembered." else "Unlocked for this run."
            session.run = null
            ctx.json(mapOf("ok" to true) + stateOf(session))
        } else {
            ctx.json(
                mapOf(
                    "ok" to false,
                    "error" to "That password did not open ${doc.fileName}. Bank passwords are " +
                        "case sensitive and often combine name and date of birth.",
                ) + stateOf(session),
            )
        }
    }

    private fun remove(ctx: Context, session: Session) {
        session.docs.remove(ctx.formParam("fileHash").orEmpty())
        session.run = null
        ctx.json(stateOf(session))
    }

    // ── 2. process ───────────────────────────────────────────────────────────────

    private fun process(ctx: Context, session: Session) {
        if (session.docs.isEmpty()) throw IllegalArgumentException("Add some documents first.")

        val config = rules.config()
        val categorizer = config.toCategorizer()
        val investmentRules = config.toInvestmentRules()
        val run = Run()

        val invoices = mutableListOf<Invoice>()
        val transactions = mutableListOf<BankTxn>()

        for (doc in session.docs.values.sortedBy { it.fileName }) {
            if (doc.state == PdfProbe.State.NEEDS_PASSWORD) {
                run.skipped += mapOf("file" to doc.fileName, "why" to "Locked — enter its password.")
                continue
            }
            if (doc.state == PdfProbe.State.UNREADABLE) {
                run.skipped += mapOf("file" to doc.fileName, "why" to "Could not be opened. ${doc.note}")
                continue
            }
            when (val text = extractor.extract(doc.bytes, doc.fileName, listOfNotNull(doc.password))) {
                is JvmExtractor.Result.PasswordProblem -> {
                    doc.state = PdfProbe.State.NEEDS_PASSWORD
                    run.skipped += mapOf("file" to doc.fileName, "why" to "Needs a password.")
                }
                is JvmExtractor.Result.Failed ->
                    run.skipped += mapOf("file" to doc.fileName, "why" to text.reason)
                is JvmExtractor.Result.Text -> {
                    val statement = BankParser.parse(text.text, sourceOf(text.text))
                    if (statement.transactions.isNotEmpty()) {
                        transactions += statement.transactions
                        for (txn in statement.transactions.filter { it.isDebit }) {
                            classifyOutflow(txn, doc.fileName, categorizer, investmentRules, run)
                        }
                    } else {
                        val invoice = InvoiceParser.parse(text.text, doc.fileName)
                        if (invoice.date == null && invoice.matchAmount == null) {
                            run.skipped += mapOf(
                                "file" to doc.fileName,
                                "why" to "Opened, but no dated amounts were found in it.",
                            )
                        } else {
                            invoices += invoice
                        }
                    }
                }
            }
        }

        // The GST side reuses the same pipeline the bookkeeping screen always used, so the
        // registers get exactly the rows they always got.
        val result = Pipeline(CategoryRules.defaults()).process(
            Pipeline.Input(purchaseInvoices = invoices, transactions = transactions),
        )
        run.purchaseRows += result.purchaseRows
        run.salesRows += result.salesRows

        session.run = run
        ctx.json(stateOf(session))
    }

    /**
     * Place one outflow, or stop and ask.
     *
     * An investment is not an expense and is decided first. Everything else goes to the
     * categoriser, and anything it cannot settle — no rule matched, or two rules of equal
     * standing disagreed — becomes a question rather than a guess.
     */
    private fun classifyOutflow(
        txn: BankTxn,
        source: String,
        categorizer: com.gohil.bookkeeper.core.spend.SpendCategorizer,
        investmentRules: com.gohil.bookkeeper.core.spend.InvestmentRules,
        run: Run,
    ) {
        val description = txn.description.ifBlank { "(no description)" }
        investmentRules.match("$description ${txn.rawLine}")?.let { asset ->
            run.investments += InvestmentItem(
                txn.date, description, txn.amount, asset.type, asset.matchedOn, source,
            )
            return
        }

        val hit = categorizer.classify(txn)
        if (hit.category != SpendCategory.UNCATEGORISED && hit.confident) {
            run.spend += SpendItem(
                txn.date, description, txn.amount, hit.category, hit.matchedOn, true, source,
            )
            return
        }
        run.pending += Pending(
            id = UUID.randomUUID().toString(),
            date = txn.date,
            description = description,
            amount = txn.amount,
            source = source,
            suggestion = hit.category.takeIf { it != SpendCategory.UNCATEGORISED },
            why = if (hit.category == SpendCategory.UNCATEGORISED) {
                "No rule matched this merchant."
            } else {
                "Two categories matched, so it is not safe to pick one."
            },
        )
    }

    private fun sourceOf(text: String): StatementSource {
        val lower = text.lowercase()
        return if (CARD_MARKERS.count { it in lower } >= 2) StatementSource.CREDIT_CARD
        else StatementSource.BANK
    }

    // ── 3. answer the questions ──────────────────────────────────────────────────

    /**
     * Resolve one pending item with the user's answer.
     *
     * `remember` writes the merchant into the editable keyword file, so the same narration is
     * never asked about again. That is what stops the prompt being a treadmill: each answer
     * shrinks next month's queue.
     */
    private fun categorise(ctx: Context, session: Session) {
        val run = session.run ?: throw IllegalStateException("Process the documents first.")
        val id = ctx.formParam("id").orEmpty()
        val item = run.pending.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("That item has already been dealt with.")

        val choice = ctx.formParam("category").orEmpty()
        val category = runCatching { SpendCategory.valueOf(choice.uppercase()) }.getOrNull()
            ?: throw IllegalArgumentException("'$choice' is not a category.")
        if (category == SpendCategory.UNCATEGORISED) {
            throw IllegalArgumentException(
                "Pick a real category — leaving it uncategorised is what this step exists to avoid.",
            )
        }

        val keyword = ctx.formParam("keyword").orEmpty().trim().lowercase()
        if (keyword.isNotBlank()) rules.addKeyword(category, keyword)

        run.spend += SpendItem(
            date = item.date,
            merchant = item.description,
            amount = item.amount,
            category = category,
            matchedOn = if (keyword.isNotBlank()) "$keyword (you)" else "you",
            confident = true,
            source = item.source,
        )
        run.pending.remove(item)

        // A remembered keyword usually answers several queued items at once — the same
        // merchant appears all month. Clearing them here saves answering the same question
        // five times.
        if (keyword.isNotBlank()) {
            val alsoMatched = run.pending.filter { keyword in it.description.lowercase() }
            for (other in alsoMatched) {
                run.spend += SpendItem(
                    other.date, other.description, other.amount, category,
                    "$keyword (you)", true, other.source,
                )
                run.pending.remove(other)
            }
        }
        ctx.json(stateOf(session))
    }

    // ── 4. export, only once everything is answered ──────────────────────────────

    private fun finish(ctx: Context, session: Session) {
        val run = session.run ?: throw IllegalStateException("Process the documents first.")
        if (run.pending.isNotEmpty()) {
            ctx.status(409).json(
                mapOf(
                    "error" to "${run.pending.size} item(s) still need a category. " +
                        "Nothing is exported until every item is placed.",
                ) + stateOf(session),
            )
            return
        }

        val downloads = mutableListOf<Map<String, String>>()
        session.outputs.clear()

        // The registers, in the user's own file, at the tab for the month the documents cover.
        val month = monthOf(run)
        for ((type, rows) in listOf(
            RegisterType.PURCHASE to run.purchaseRows,
            RegisterType.SALES to run.salesRows,
        )) {
            if (rows.isEmpty()) continue
            val register = registers.get(type) ?: run {
                downloads += mapOf(
                    "name" to "",
                    "summary" to "${rows.size} ${type.name.lowercase()} row(s) were NOT written — " +
                        "no ${type.name.lowercase()} register is set up. Nothing was lost; " +
                        "add it on the setup page and process again.",
                    "key" to "",
                )
                null
            } ?: continue

            val tab = month?.let { RegisterStore.tabFor(it, register.sheetNames) }
                ?: register.sheetNames.lastOrNull()
                ?: throw IllegalStateException("${register.fileName} has no worksheets.")

            val appender = XlsxAppender.open(register.bytes)
            val outcome = appender.append(
                XlsxAppender.Request(sheetName = tab, rows = rows, registerType = type, createSheetIfMissing = true),
            )
            val bytes = appender.toByteArray()
            XlsxAppender.open(bytes)  // must reopen before it is offered as the new register

            val key = UUID.randomUUID().toString()
            val name = register.fileName.substringBeforeLast('.') + "-updated.xlsx"
            session.outputs[key] = Download(name, bytes, XLSX_TYPE)
            downloads += mapOf(
                "key" to key,
                "name" to name,
                "summary" to buildString {
                    append("${outcome.rowsWritten} row(s) into '${outcome.sheetName}', ")
                    append("SR NO ${outcome.firstSrNo} onwards.")
                    if (outcome.addedColumns.isNotEmpty()) {
                        append(" Added column(s): ${outcome.addedColumns.joinToString(", ")}.")
                    }
                    outcome.warnings.forEach { append(" $it") }
                },
            )
        }

        // The accountant's pack.
        val pack = CaExport.build(
            CaExport.Input(
                spend = SpendSummary.from(run.spend),
                investments = InvestmentSummary.from(run.investments),
                loans = emptyList(),
                salesRows = run.salesRows,
                purchaseRows = run.purchaseRows,
            ),
        )
        val packKey = UUID.randomUUID().toString()
        val packName = CaExport.fileName(
            CaExport.Input(
                spend = SpendSummary.from(run.spend),
                investments = InvestmentSummary.from(run.investments),
                loans = emptyList(),
                salesRows = run.salesRows,
                purchaseRows = run.purchaseRows,
            ),
        )
        session.outputs[packKey] = Download(packName, pack, XLSX_TYPE)
        downloads += mapOf(
            "key" to packKey,
            "name" to packName,
            "summary" to "Everything in one workbook for the CA.",
        )

        run.written = true
        ctx.json(mapOf("downloads" to downloads) + stateOf(session))
    }

    /** The month the documents are about, taken from the dates actually read out of them. */
    private fun monthOf(run: Run): YearMonth? {
        val dates = run.spend.map { it.date } + run.investments.map { it.date } +
            run.purchaseRows.mapNotNull { it.date } + run.salesRows.mapNotNull { it.date }
        if (dates.isEmpty()) return null
        // The commonest month, not the newest: one stray mis-parsed date should not decide
        // which tab a whole month of rows lands in.
        return dates.groupingBy { YearMonth.from(it) }.eachCount().maxByOrNull { it.value }?.key
    }

    private fun bundle(ctx: Context, session: Session) {
        val files = session.docs.values.sortedBy { it.fileName }.map { it.fileName to it.bytes }
        if (files.isEmpty()) throw IllegalStateException("There are no uploaded documents to bundle.")
        val month = session.run?.let { monthOf(it) }
        val base = "Documents-" + (month?.toString() ?: LocalDate.now().toString())
        val result = bundler.build(files, base)
        ctx.contentType(if (result.format == "rar") "application/vnd.rar" else "application/zip")
            .header("Content-Disposition", "attachment; filename=\"${result.fileName}\"")
            .header("X-Bundle-Note", result.note)
            .result(result.bytes)
    }

    private fun download(ctx: Context, session: Session) {
        val file = session.outputs[ctx.pathParam("key")]
            ?: throw IllegalStateException("That download has expired. Process the documents again.")
        ctx.contentType(file.contentType)
            .header("Content-Disposition", "attachment; filename=\"${file.name}\"")
            .result(file.bytes)
    }

    // ── views ────────────────────────────────────────────────────────────────────

    private fun stateOf(session: Session): Map<String, Any?> {
        val run = session.run
        return mapOf(
            "documents" to session.docs.entries.sortedBy { it.value.fileName }.map { (hash, doc) ->
                mapOf(
                    "fileHash" to hash,
                    "fileName" to doc.fileName,
                    "state" to doc.state.name,
                    "needsPassword" to (doc.state == PdfProbe.State.NEEDS_PASSWORD),
                    "note" to doc.note,
                )
            },
            "locked" to session.docs.values.count { it.state == PdfProbe.State.NEEDS_PASSWORD },
            "processed" to (run != null),
            "pending" to run?.pending.orEmpty().map {
                mapOf(
                    "id" to it.id,
                    "date" to it.date.toString(),
                    "description" to it.description,
                    "amount" to it.amount.toPlainString(),
                    "source" to it.source,
                    "suggestion" to it.suggestion?.name,
                    "suggestionLabel" to it.suggestion?.label,
                    "why" to it.why,
                )
            },
            "categories" to SpendCategory.entries.filter { it != SpendCategory.UNCATEGORISED }
                .map { mapOf("key" to it.name, "label" to it.label) },
            "counts" to mapOf(
                "spend" to run?.spend?.size.orZero(),
                "investments" to run?.investments?.size.orZero(),
                "purchaseRows" to run?.purchaseRows?.size.orZero(),
                "salesRows" to run?.salesRows?.size.orZero(),
                "pending" to run?.pending?.size.orZero(),
            ),
            "totals" to mapOf(
                "spend" to run?.spend.orEmpty().fold(BigDecimal.ZERO) { a, i -> a + i.amount }.toPlainString(),
                "investments" to run?.investments.orEmpty().fold(BigDecimal.ZERO) { a, i -> a + i.amount }.toPlainString(),
            ),
            "skipped" to run?.skipped.orEmpty(),
            "readyToExport" to (run != null && run.pending.isEmpty()),
            "written" to (run?.written ?: false),
            "registersConfigured" to registers.isConfigured(),
            "rarAvailable" to bundler.rarAvailable,
            "rulesWarning" to rules.lastError,
        )
    }

    private fun crm(session: Session): Map<String, Any> {
        val run = session.run
        val parties = Crm.build(run?.salesRows.orEmpty(), run?.purchaseRows.orEmpty())
        return mapOf(
            "parties" to parties.map {
                mapOf(
                    "name" to it.name,
                    "gstNo" to it.gstNo,
                    "role" to it.role.label,
                    "invoiceCount" to it.invoiceCount,
                    "totalBilled" to it.totalBilled.toPlainString(),
                    "totalTax" to it.totalTax.toPlainString(),
                    "averageInvoice" to it.averageInvoice.toPlainString(),
                    "firstSeen" to it.firstSeen?.toString(),
                    "lastSeen" to it.lastSeen?.toString(),
                    "gstTreatment" to it.gstTreatment,
                    "unpaidCount" to it.unpaidCount,
                )
            },
            "processed" to (run != null),
        )
    }

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        private const val XLSX_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private val CARD_MARKERS = listOf(
            "credit card", "card statement", "total amount due", "minimum amount due",
            "statement period", "available credit limit", "reward points", "card number",
        )
    }
}
