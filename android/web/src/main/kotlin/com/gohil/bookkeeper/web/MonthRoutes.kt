package com.gohil.bookkeeper.web

import com.gohil.bookkeeper.core.logic.Pipeline
import com.gohil.bookkeeper.core.model.Invoice
import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.ProcessResult
import com.gohil.bookkeeper.core.model.StatementSource
import com.gohil.bookkeeper.core.parse.BankParser
import com.gohil.bookkeeper.core.parse.DocumentClassifier
import com.gohil.bookkeeper.core.parse.DocumentKind
import com.gohil.bookkeeper.core.parse.InvoiceParser
import com.gohil.bookkeeper.core.rules.CategoryRules
import com.gohil.bookkeeper.core.spend.CaExport
import com.gohil.bookkeeper.core.spend.Duplicates
import com.gohil.bookkeeper.core.spend.InvestmentItem
import com.gohil.bookkeeper.core.spend.InvestmentRules
import com.gohil.bookkeeper.core.spend.InvestmentSummary
import com.gohil.bookkeeper.core.spend.SpendCategorizer
import com.gohil.bookkeeper.core.spend.SpendCategory
import com.gohil.bookkeeper.core.spend.SpendItem
import com.gohil.bookkeeper.core.spend.SpendSummary
import io.javalin.Javalin
import io.javalin.http.Context
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The whole month on one page: drop the paperwork in, answer only what the app could not
 * work out, take away the accountant's workbook and the two document bundles.
 *
 * The two-screen split it replaces asked the user to sort his own documents into four boxes
 * before anything could start — and sorting them is exactly the job a computer does more
 * consistently than a person at the end of a month. Every document says what it is; see
 * [DocumentClassifier].
 *
 * Nothing here re-implements the engine. Classification, parsing, categorisation, duplicate
 * detection and the workbook all come from :core, so this screen and the GST screen can
 * never disagree about what a statement said.
 */
class MonthRoutes(
    private val store: PasswordStore?,
    private val loans: LoanRepository,
    private val extractor: JvmExtractor = JvmExtractor(),
    private val categorizer: SpendCategorizer = SpendCategorizer(
        SpendCategorizer.defaults() + SpendRoutes.userRules(),
    ),
) {

    class Doc(
        val fileName: String,
        val bytes: ByteArray,
        @Volatile var state: PdfProbe.State,
        @Volatile var password: String? = null,
        @Volatile var note: String = "",
        @Volatile var text: String? = null,
        @Volatile var kind: DocumentKind? = null,
    )

    /** One line the user may need to answer for. */
    data class Decision(
        val key: String,
        val date: LocalDate,
        val description: String,
        val amount: BigDecimal,
        val source: String,
        val suggested: SpendCategory?,
        val matchedOn: String?,
    )

    class Session {
        val docs = ConcurrentHashMap<String, Doc>()

        /** Categories the user set on this month's lines, by [Decision.key]. */
        val chosen = ConcurrentHashMap<String, SpendCategory>()

        @Volatile var spend: SpendSummary? = null
        @Volatile var investments: InvestmentSummary? = null
        @Volatile var register: ProcessResult? = null
        @Volatile var skipped: List<Map<String, String>> = emptyList()
        @Volatile var decisions: List<Decision> = emptyList()
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    fun register(app: Javalin) {
        app.post("/api/month/upload") { ctx -> upload(ctx, sessionOf(ctx)) }
        app.post("/api/month/unlock") { ctx -> unlock(ctx, sessionOf(ctx)) }
        app.post("/api/month/remove") { ctx -> remove(ctx, sessionOf(ctx)) }
        app.post("/api/month/analyse") { ctx -> analyse(ctx, sessionOf(ctx)) }
        app.post("/api/month/categorise") { ctx -> categorise(ctx, sessionOf(ctx)) }
        app.get("/api/month/excel") { ctx -> excel(ctx, sessionOf(ctx)) }
        app.get("/api/month/bundle") { ctx -> bundle(ctx, sessionOf(ctx)) }
    }

    private fun sessionOf(ctx: Context): Session {
        val id = ctx.cookie(COOKIE) ?: java.util.UUID.randomUUID().toString().also {
            ctx.cookie(COOKIE, it)
        }
        return sessions.getOrPut(id) { Session() }
    }

    // ── taking the documents in ──────────────────────────────────────────────────

    private fun upload(ctx: Context, session: Session) {
        for (file in ctx.uploadedFiles("files")) {
            val bytes = file.content().readBytes()
            val probe = PdfProbe.probe(bytes, file.filename(), store)
            if (session.docs.containsKey(probe.fileHash)) continue
            session.docs[probe.fileHash] = Doc(
                fileName = probe.fileName,
                bytes = bytes,
                state = probe.state,
                password = probe.password,
                note = probe.detail,
            )
        }
        clearResults(session)
        ctx.json(fileList(session))
    }

    private fun unlock(ctx: Context, session: Session) {
        val hash = ctx.formParam("fileHash").orEmpty()
        val doc = session.docs[hash] ?: throw IllegalArgumentException("That file is no longer loaded.")
        val ok = PdfProbe.verify(doc.bytes, ctx.formParam("password").orEmpty(), store, remember = true)
        if (ok) {
            doc.state = PdfProbe.State.UNLOCKED_FROM_STORE
            doc.password = ctx.formParam("password")
            doc.note = "Unlocked, and remembered for next month."
            clearResults(session)
        }
        ctx.json(mapOf("ok" to ok, "files" to fileList(session)["files"]))
    }

    private fun remove(ctx: Context, session: Session) {
        session.docs.remove(ctx.formParam("fileHash").orEmpty())
        clearResults(session)
        ctx.json(fileList(session))
    }

    private fun clearResults(session: Session) {
        session.spend = null
        session.investments = null
        session.register = null
        session.decisions = emptyList()
    }

    private fun fileList(session: Session) = mapOf(
        "files" to session.docs.entries.sortedBy { it.value.fileName }.map { (hash, d) ->
            mapOf(
                "fileHash" to hash,
                "fileName" to d.fileName,
                "state" to d.state.name,
                "needsPassword" to (d.state == PdfProbe.State.NEEDS_PASSWORD),
                "kind" to (d.kind?.name ?: ""),
                "note" to d.note,
            )
        },
        "needsPassword" to session.docs.values.count { it.state == PdfProbe.State.NEEDS_PASSWORD },
    )

    // ── reading all of it, in one pass ───────────────────────────────────────────

    private fun analyse(ctx: Context, session: Session) {
        val skipped = mutableListOf<Map<String, String>>()

        // Text first, for everything, because the classifier needs to see the whole set
        // before it can tell which registration is ours.
        for (doc in session.docs.values) {
            doc.text = null
            doc.kind = null
            when (doc.state) {
                PdfProbe.State.NEEDS_PASSWORD ->
                    skipped += mapOf("file" to doc.fileName, "why" to "Still locked — enter its password.")
                PdfProbe.State.UNREADABLE ->
                    skipped += mapOf("file" to doc.fileName, "why" to "Could not be opened. ${doc.note}")
                else -> when (val out = extractor.extract(doc.bytes, doc.fileName, listOfNotNull(doc.password))) {
                    is JvmExtractor.Result.Text -> doc.text = out.text
                    is JvmExtractor.Result.PasswordProblem -> {
                        doc.state = PdfProbe.State.NEEDS_PASSWORD
                        skipped += mapOf("file" to doc.fileName, "why" to "Needs a password.")
                    }
                    is JvmExtractor.Result.Failed ->
                        skipped += mapOf("file" to doc.fileName, "why" to out.reason)
                }
            }
        }

        val texts = session.docs.values.mapNotNull { it.text }
        val ownGstin = DocumentClassifier.ownGstin(texts)
        for (doc in session.docs.values) {
            doc.kind = doc.text?.let { DocumentClassifier.classify(it, ownGstin) }
        }

        val purchases = mutableListOf<Invoice>()
        val sales = mutableListOf<Invoice>()
        val txns = mutableListOf<BankTxn>()
        val unparsed = mutableListOf<String>()
        val accepted = mutableListOf<Duplicates.Fingerprint>()

        for (doc in session.docs.values.sortedBy { it.fileName }) {
            val text = doc.text ?: continue
            when (doc.kind) {
                DocumentKind.PURCHASE_INVOICE ->
                    purchases += InvoiceParser.parse(text, doc.fileName)
                DocumentKind.SALES_INVOICE ->
                    sales += InvoiceParser.parse(text, doc.fileName, InvoiceParser.Counterparty.RECIPIENT)
                DocumentKind.BANK_STATEMENT, DocumentKind.CARD_STATEMENT -> {
                    val source = if (doc.kind == DocumentKind.CARD_STATEMENT) {
                        StatementSource.CREDIT_CARD
                    } else {
                        StatementSource.BANK
                    }
                    val parsed = BankParser.parse(text, source)
                    val print = Duplicates.Fingerprint(doc.fileName, parsed.transactions.map(Duplicates::key))
                    val copyOf = Duplicates.duplicateOf(print, accepted)
                    if (copyOf != null) {
                        skipped += mapOf(
                            "file" to doc.fileName,
                            "why" to "The same statement as ${copyOf.fileName} — left out so " +
                                "this month is not counted twice.",
                        )
                    } else {
                        txns += parsed.transactions
                        unparsed += parsed.unparsed
                        accepted += print
                    }
                }
                null -> Unit
            }
        }

        // The GST registers, from the same reading.
        session.register = Pipeline(CategoryRules.defaults()).process(
            Pipeline.Input(
                purchaseInvoices = purchases,
                salesInvoices = sales,
                transactions = txns,
                unparsedLines = unparsed,
                // Statements are here so invoices can be matched to the payment that
                // settled them. A card transaction with no tax invoice behind it belongs in
                // the spend analysis below, not in the register the accountant reads.
                statementLinesBecomeRows = false,
            ),
        )

        val spend = mutableListOf<SpendItem>()
        val invested = mutableListOf<InvestmentItem>()
        val decisions = mutableListOf<Decision>()

        for (txn in txns.filter { it.isDebit }) {
            val description = txn.description.ifBlank { "(no description)" }
            val key = Duplicates.key(txn) + "|" + description.take(40)
            val override = session.chosen[key]

            val asset = InvestmentRules.match("$description ${txn.rawLine}")
            if (override == SpendCategory.INVESTMENT || (override == null && asset != null)) {
                invested += InvestmentItem(
                    date = txn.date,
                    description = description,
                    amount = txn.amount,
                    type = asset?.type ?: com.gohil.bookkeeper.core.spend.InstrumentType.MUTUAL_FUND,
                    matchedOn = asset?.matchedOn ?: "you said so",
                    source = "statement",
                )
                continue
            }

            val hit = categorizer.classify(txn)
            val category = override ?: hit.category
            // Transfers are not spending at all: a card bill paid from the bank account is
            // settled by the card's own rows, and counting both charges the month twice.
            if (category == SpendCategory.TRANSFER) continue

            if (override == null && (category == SpendCategory.UNCATEGORISED || !hit.confident)) {
                decisions += Decision(
                    key = key,
                    date = txn.date,
                    description = description,
                    amount = txn.amount,
                    source = if (txn.source == StatementSource.CREDIT_CARD) "Credit card" else "Bank",
                    suggested = category.takeIf { it != SpendCategory.UNCATEGORISED },
                    matchedOn = hit.matchedOn,
                )
            }
            spend += SpendItem(
                date = txn.date,
                merchant = description,
                amount = txn.amount,
                category = category,
                matchedOn = if (override != null) "you said so" else hit.matchedOn,
                confident = override != null || hit.confident,
                source = "statement",
            )
        }

        session.spend = SpendSummary.from(spend)
        session.investments = InvestmentSummary.from(invested)
        session.skipped = skipped
        // Biggest first: the decisions worth making are the ones with money behind them.
        session.decisions = decisions.sortedByDescending { it.amount }

        ctx.json(payload(session))
    }

    private fun categorise(ctx: Context, session: Session) {
        val key = ctx.formParam("key").orEmpty()
        val name = ctx.formParam("category").orEmpty()
        val category = SpendCategory.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown category '$name'.")
        session.chosen[key] = category
        ctx.json(mapOf("ok" to true))
    }

    private fun payload(session: Session): Map<String, Any> {
        val spend = session.spend ?: SpendSummary.from(emptyList())
        val investments = session.investments ?: InvestmentSummary.from(emptyList())
        val register = session.register
        return mapOf(
            "files" to fileList(session)["files"]!!,
            "spendTotal" to spend.total.toPlainString(),
            "spendCount" to spend.items.size,
            "investedTotal" to investments.total.toPlainString(),
            "investedCount" to investments.items.size,
            "salesRows" to (register?.salesRows?.size ?: 0),
            "purchaseRows" to (register?.purchaseRows?.size ?: 0),
            "byCategory" to spend.byCategory.map {
                mapOf(
                    "key" to it.category.name,
                    "label" to it.category.label,
                    "slot" to it.category.paletteSlot,
                    "total" to it.total.toPlainString(),
                    "count" to it.count,
                )
            },
            "decisions" to session.decisions.map {
                mapOf(
                    "key" to it.key,
                    "date" to it.date.toString(),
                    "description" to it.description,
                    "amount" to it.amount.toPlainString(),
                    "source" to it.source,
                    "suggested" to (it.suggested?.name ?: ""),
                    "matchedOn" to (it.matchedOn ?: ""),
                )
            },
            "categories" to SpendCategory.entries
                .filter { it != SpendCategory.UNCATEGORISED }
                .map { mapOf("key" to it.name, "label" to it.label) },
            "skipped" to session.skipped,
        )
    }

    // ── what the accountant gets ─────────────────────────────────────────────────

    private fun excel(ctx: Context, session: Session) {
        val spend = session.spend ?: throw IllegalStateException("Read the documents first.")
        val input = CaExport.Input(
            spend = spend,
            investments = session.investments ?: InvestmentSummary.from(emptyList()),
            loans = loans.all(),
            salesRows = session.register?.salesRows.orEmpty(),
            purchaseRows = session.register?.purchaseRows.orEmpty(),
            generatedOn = LocalDate.now(),
        )
        ctx.header("Content-Disposition", "attachment; filename=\"${CaExport.fileName(input)}\"")
        ctx.contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        ctx.result(CaExport.build(input))
    }

    /**
     * The source documents, bundled the way they will be handed on.
     *
     * Two bundles rather than one, because they answer to different people: the sales
     * invoices support what was declared as income, and the purchase invoices with the
     * statements support what was claimed against it. Statements go in the purchase bundle
     * for that reason — they are the evidence a claimed expense was actually paid.
     */
    private fun bundle(ctx: Context, session: Session) {
        val sales = ctx.queryParam("kind") == "sales"
        val wanted = if (sales) {
            setOf(DocumentKind.SALES_INVOICE)
        } else {
            setOf(DocumentKind.PURCHASE_INVOICE, DocumentKind.BANK_STATEMENT, DocumentKind.CARD_STATEMENT)
        }
        val docs = session.docs.values.filter { it.kind in wanted }.sortedBy { it.fileName }
        if (docs.isEmpty()) throw IllegalStateException("No documents of that kind were read.")

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val used = HashSet<String>()
            for (doc in docs) {
                val folder = when (doc.kind) {
                    DocumentKind.SALES_INVOICE -> "sales-invoices"
                    DocumentKind.PURCHASE_INVOICE -> "purchase-invoices"
                    DocumentKind.BANK_STATEMENT -> "bank-statements"
                    else -> "credit-card-statements"
                }
                // Two banks both export "statement.pdf"; inside one zip that is a collision.
                var name = "$folder/${doc.fileName}"
                var n = 2
                while (!used.add(name)) {
                    name = "$folder/${doc.fileName.substringBeforeLast('.')}-$n." +
                        doc.fileName.substringAfterLast('.', "pdf")
                    n++
                }
                zip.putNextEntry(ZipEntry(name))
                zip.write(doc.bytes)
                zip.closeEntry()
            }
        }
        val period = session.spend?.byMonth?.lastOrNull()?.month?.toString() ?: "month"
        val label = if (sales) "sales-invoices" else "purchases-and-statements"
        ctx.header("Content-Disposition", "attachment; filename=\"$label-$period.zip\"")
        ctx.contentType("application/zip")
        ctx.result(out.toByteArray())
    }

    companion object {
        private const val COOKIE = "bk_month"
    }
}
