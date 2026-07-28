package com.gohil.bookkeeper.work

import android.net.Uri
import com.gohil.bookkeeper.core.logic.Pipeline
import com.gohil.bookkeeper.core.model.Invoice
import com.gohil.bookkeeper.core.model.ProcessResult
import com.gohil.bookkeeper.core.model.RegisterType
import com.gohil.bookkeeper.core.model.ReviewItem
import com.gohil.bookkeeper.core.model.ReviewReason
import com.gohil.bookkeeper.core.model.StatementSource
import com.gohil.bookkeeper.core.parse.BankParser
import com.gohil.bookkeeper.core.parse.InvoiceParser
import com.gohil.bookkeeper.core.rules.CategoryRules
import com.gohil.bookkeeper.data.Secret
import com.gohil.bookkeeper.pdf.DocumentExtractor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Runs a month's documents through extraction and the pipeline.
 *
 * Two properties carried over from the desktop app matter more than throughput. Each file
 * gets its own timeout, so one pathological OCR pass cannot hang the whole run; and each
 * file's failure is contained, so a single corrupt PDF does not cost the user the other
 * nineteen. Anything that fails either way lands in the review list with its reason attached.
 */
class ProcessJob(
    private val extractor: DocumentExtractor,
    rules: CategoryRules,
) {

    private val pipeline = Pipeline(rules)

    data class Document(
        val uri: Uri,
        val displayName: String,
        val kind: Kind,
    )

    enum class Kind { PURCHASE_INVOICE, SALES_INVOICE, BANK_STATEMENT, CREDIT_CARD_STATEMENT }

    data class Progress(val done: Int, val total: Int, val currentFile: String)

    suspend fun run(
        documents: List<Document>,
        passwords: Map<Kind, Secret>,
        onProgress: (Progress) -> Unit = {},
    ): ProcessResult {
        val purchaseInvoices = ArrayList<Invoice>()
        val salesInvoices = ArrayList<Invoice>()
        val transactions = ArrayList<com.gohil.bookkeeper.core.model.BankTxn>()
        val unparsedLines = ArrayList<String>()
        val failures = ArrayList<ReviewItem>()

        documents.forEachIndexed { index, doc ->
            onProgress(Progress(index, documents.size, doc.displayName))

            val extraction = try {
                withTimeout(PER_FILE_TIMEOUT_MS) {
                    extractor.extract(doc.uri, doc.displayName, passwords[doc.kind]?.reveal())
                }
            } catch (e: TimeoutCancellationException) {
                failures += ReviewItem(
                    sourceFile = doc.displayName,
                    reason = ReviewReason.TIMED_OUT,
                    detail = "Gave up after ${PER_FILE_TIMEOUT_MS / 1000} seconds. " +
                        "A clearer photo usually fixes this.",
                    target = doc.kind.target(),
                )
                return@forEachIndexed
            } catch (e: Exception) {
                // Deliberately broad: one unreadable file must not end the run.
                failures += ReviewItem(
                    sourceFile = doc.displayName,
                    reason = ReviewReason.EXTRACTION_FAILED,
                    detail = e.message ?: "Could not read this file.",
                    target = doc.kind.target(),
                )
                return@forEachIndexed
            }

            when (extraction) {
                is DocumentExtractor.Result.PasswordRequired -> failures += ReviewItem(
                    sourceFile = doc.displayName,
                    reason = ReviewReason.PASSWORD_REQUIRED,
                    detail = "This file is password protected and the saved password did not open it.",
                    target = doc.kind.target(),
                )

                is DocumentExtractor.Result.Failed -> failures += ReviewItem(
                    sourceFile = doc.displayName,
                    reason = ReviewReason.EXTRACTION_FAILED,
                    detail = extraction.reason,
                    target = doc.kind.target(),
                )

                is DocumentExtractor.Result.Text -> when (doc.kind) {
                    Kind.PURCHASE_INVOICE ->
                        purchaseInvoices += InvoiceParser.parse(extraction.text, doc.displayName)

                    Kind.SALES_INVOICE ->
                        salesInvoices += InvoiceParser.parse(extraction.text, doc.displayName)

                    Kind.BANK_STATEMENT -> {
                        val parsed = BankParser.parse(extraction.text, StatementSource.BANK)
                        transactions += parsed.transactions
                        unparsedLines += parsed.unparsed
                    }

                    Kind.CREDIT_CARD_STATEMENT -> {
                        val parsed = BankParser.parse(extraction.text, StatementSource.CREDIT_CARD)
                        transactions += parsed.transactions
                        unparsedLines += parsed.unparsed
                    }
                }
            }
        }

        onProgress(Progress(documents.size, documents.size, ""))

        return pipeline.process(
            Pipeline.Input(
                purchaseInvoices = purchaseInvoices,
                salesInvoices = salesInvoices,
                transactions = transactions,
                unparsedLines = unparsedLines,
                failures = failures,
            ),
        )
    }

    private fun Kind.target(): RegisterType =
        if (this == Kind.SALES_INVOICE) RegisterType.SALES else RegisterType.PURCHASE

    companion object {
        /** Matches the desktop app's per-file budget. */
        const val PER_FILE_TIMEOUT_MS = 150_000L
    }
}
