package com.gohil.bookkeeper.core.logic

import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.Category
import com.gohil.bookkeeper.core.model.GstType
import com.gohil.bookkeeper.core.model.Invoice
import com.gohil.bookkeeper.core.model.PaymentMethod
import com.gohil.bookkeeper.core.model.ReviewReason
import com.gohil.bookkeeper.core.model.StatementSource
import com.gohil.bookkeeper.core.rules.CategoryRules
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineTest {

    private val rules = CategoryRules.defaults()
    private val pipeline = Pipeline(rules)

    private fun invoice(
        name: String = "Sharma Office Supplies",
        total: String = "5310.00",
        date: LocalDate = LocalDate.of(2025, 6, 12),
        file: String = "inv.pdf",
    ) = Invoice(
        sourceFile = file,
        date = date,
        gstNo = "24AABCU9603R1ZM",
        invoiceNo = "INV-001",
        partyName = name,
        taxable = BigDecimal("4500.00"),
        cgst = BigDecimal("405.00"),
        sgst = BigDecimal("405.00"),
        totalGrand = BigDecimal(total),
    )

    private fun debit(
        amount: String,
        date: LocalDate = LocalDate.of(2025, 6, 15),
        description: String = "UPI/SHARMA OFFICE/9876",
        source: StatementSource = StatementSource.BANK,
    ) = BankTxn(date, description, debit = BigDecimal(amount), source = source, rawLine = description)

    private fun credit(
        amount: String,
        date: LocalDate = LocalDate.of(2025, 6, 15),
        description: String = "NEFT CR UNKNOWN SENDER",
    ) = BankTxn(date, description, credit = BigDecimal(amount), source = StatementSource.BANK, rawLine = description)

    // ── matching ─────────────────────────────────────────────────────────────────

    @Test
    fun `a matched transaction fills in payment details instead of creating a second row`() {
        val result = pipeline.process(
            Pipeline.Input(
                purchaseInvoices = listOf(invoice()),
                transactions = listOf(debit("5310.00")),
            ),
        )

        assertEquals(1, result.purchaseRows.size, "must not duplicate the invoice as a bank row")
        val row = result.purchaseRows.single()
        assertEquals(LocalDate.of(2025, 6, 15), row.paidDate)
        assertEquals(PaymentMethod.UPI, row.paymentMethod)
        assertEquals(BigDecimal("4500.00"), row.taxable)
    }

    @Test
    fun `matches within two rupees and fifteen days`() {
        val inside = pipeline.process(
            Pipeline.Input(
                purchaseInvoices = listOf(invoice(total = "5310.00")),
                transactions = listOf(debit("5311.50", LocalDate.of(2025, 6, 26))),
            ),
        )
        assertEquals(1, inside.purchaseRows.size)
        assertEquals(LocalDate.of(2025, 6, 26), inside.purchaseRows.single().paidDate)

        val tooFar = pipeline.process(
            Pipeline.Input(
                purchaseInvoices = listOf(invoice(total = "5310.00")),
                transactions = listOf(debit("5311.50", LocalDate.of(2025, 7, 5))),
            ),
        )
        assertEquals(2, tooFar.purchaseRows.size, "28 days apart is not a match")

        val tooDifferent = pipeline.process(
            Pipeline.Input(
                purchaseInvoices = listOf(invoice(total = "5310.00")),
                transactions = listOf(debit("5400.00")),
            ),
        )
        assertEquals(2, tooDifferent.purchaseRows.size, "90 rupees apart is not a match")
    }

    @Test
    fun `pairs the closest amounts first so a near miss cannot steal an exact match`() {
        val result = pipeline.process(
            Pipeline.Input(
                purchaseInvoices = listOf(
                    invoice(total = "1000.00", file = "a.pdf"),
                    invoice(total = "1001.00", file = "b.pdf"),
                ),
                transactions = listOf(debit("1000.00"), debit("1001.00")),
            ),
        )
        assertEquals(2, result.purchaseRows.size)
        assertEquals(2, result.stats["matched"])
    }

    // ── routing rules ────────────────────────────────────────────────────────────

    @Test
    fun `an unmatched debit becomes its own row with the amount straight into TOTAL GRAND`() {
        val result = pipeline.process(
            Pipeline.Input(transactions = listOf(debit("450.00", description = "ATM WDL CHARGES"))),
        )

        val row = result.purchaseRows.single()
        assertEquals(BigDecimal("450.00"), row.totalGrand)
        assertNull(row.taxable, "a bank charge has no tax breakdown")
        assertNull(row.cgst)
        assertNull(row.gstNo)
        assertNull(row.invoiceNo)
        assertTrue(!row.useSumFormula, "so TOTAL GRAND is not overwritten by a SUM of blanks")
    }

    @Test
    fun `an unmatched credit is never auto-added to the sales register`() {
        // An unverified bank credit is not evidence of a GST invoice. Guessing one in would
        // misstate a GST filing, so it goes to review instead.
        val result = pipeline.process(Pipeline.Input(transactions = listOf(credit("25000.00"))))

        assertTrue(result.salesRows.isEmpty(), "must not invent a sale")
        assertTrue(result.purchaseRows.isEmpty(), "and must not book it as a purchase either")
        val item = result.review.single()
        assertEquals(ReviewReason.UNMATCHED_CREDIT, item.reason)
        assertNull(item.suggested, "there is nothing safe to suggest")
    }

    @Test
    fun `a credit matching a sales invoice fills in payment details rather than being flagged`() {
        val result = pipeline.process(
            Pipeline.Input(
                salesInvoices = listOf(invoice(name = "HDFC Life", total = "25000.00")),
                transactions = listOf(credit("25000.00")),
            ),
        )
        assertEquals(1, result.salesRows.size)
        assertTrue(result.review.none { it.reason == ReviewReason.UNMATCHED_CREDIT })
        assertEquals(LocalDate.of(2025, 6, 15), result.salesRows.single().paidDate)
    }

    // ── categorisation ───────────────────────────────────────────────────────────

    @Test
    fun `food delivery is personal and everything unrecognised defaults to business`() {
        val result = pipeline.process(
            Pipeline.Input(
                transactions = listOf(
                    debit("612.50", description = "SWIGGY BANGALORE"),
                    debit("1200.00", description = "AIRTEL BROADBAND OFFICE"),
                    debit("300.00", description = "SOME UNKNOWN VENDOR XYZ"),
                ),
            ),
        )
        val byName = result.purchaseRows.associateBy { it.name.orEmpty() }
        assertEquals(Category.PERSONAL, byName.entries.first { "SWIGGY" in it.key }.value.category)
        assertEquals(Category.BUSINESS, byName.entries.first { "AIRTEL" in it.key }.value.category)
        assertEquals(Category.BUSINESS, byName.entries.first { "UNKNOWN" in it.key }.value.category)
    }

    @Test
    fun `a genuinely ambiguous vendor is flagged rather than silently defaulted`() {
        val result = pipeline.process(
            Pipeline.Input(transactions = listOf(debit("2400.00", description = "AMAZON RETAIL IN"))),
        )
        assertTrue(result.purchaseRows.isEmpty(), "must not be written on a guess")
        assertEquals(ReviewReason.AMBIGUOUS_CATEGORY, result.review.single().reason)
        assertEquals(Category.BUSINESS, result.review.single().suggested?.category)
    }

    @Test
    fun `only confirmed RCM insurers are flagged RCM`() {
        val result = pipeline.process(
            Pipeline.Input(
                salesInvoices = listOf(
                    invoice(name = "Star Health and Allied Insurance Co Ltd", file = "a.pdf"),
                    invoice(name = "HDFC Life Insurance Co Ltd", file = "b.pdf"),
                    invoice(name = "Some New Insurer Ltd", file = "c.pdf"),
                ),
            ),
        )
        val byName = result.salesRows.associate { it.name!! to it.gstType }
        assertEquals(GstType.RCM, byName.entries.first { "Star Health" in it.key }.value)
        assertEquals(GstType.DIRECT, byName.entries.first { "HDFC" in it.key }.value)
        assertEquals(
            GstType.DIRECT,
            byName.entries.first { "New Insurer" in it.key }.value,
            "an unconfirmed insurer must not be assumed RCM — it changes the GST return",
        )
    }

    @Test
    fun `payment method is inferred from the narration and falls back to the statement source`() {
        val result = pipeline.process(
            Pipeline.Input(
                // Deliberately avoiding vendors on the ambiguous list here: those are held
                // back for review, which is correct but would make this test about the
                // category gate rather than about payment-method inference.
                transactions = listOf(
                    debit("100.00", description = "UPI/SHARMA OFFICE/1234"),
                    debit("200.00", description = "NEFT DR VENDOR PAYMENT"),
                    debit("300.00", description = "CHQ NO 445566 OFFICE RENT"),
                    debit("400.00", description = "SOMETHING UNRECOGNISED", source = StatementSource.CREDIT_CARD),
                ),
            ),
        )
        val methods = result.purchaseRows.associate { it.totalGrand!!.toPlainString() to it.paymentMethod }
        assertEquals(PaymentMethod.UPI, methods["100.00"])
        assertEquals(PaymentMethod.BANK_TRANSFER, methods["200.00"])
        assertEquals(PaymentMethod.CHEQUE, methods["300.00"])
        assertEquals(PaymentMethod.CREDIT_CARD, methods["400.00"], "card statement implies card")
    }

    // ── review gate ──────────────────────────────────────────────────────────────

    @Test
    fun `a low-confidence invoice is held back with its raw text for the user to read`() {
        val poor = Invoice(sourceFile = "blurry.jpg", partyName = "Something", rawText = "garbled ocr text")
        val result = pipeline.process(Pipeline.Input(purchaseInvoices = listOf(poor)))

        assertTrue(result.purchaseRows.isEmpty())
        val item = result.review.single()
        assertEquals(ReviewReason.LOW_CONFIDENCE, item.reason)
        assertEquals("garbled ocr text", item.rawText, "the user must see what the OCR saw")
        assertTrue("TOTAL GRAND" in item.detail)
    }

    @Test
    fun `an invoice with a total but no taxable value goes to review, not the register`() {
        // Airtel's bundled statement scores full confidence — date, number, party, GSTIN and a
        // printed total are all there — but the total is a net "amount payable" with no taxable
        // value beside it. Writing it would book a wrong number and three blank tax columns.
        val noTaxable = Invoice(
            sourceFile = "airtel.pdf",
            date = LocalDate.of(2026, 7, 27),
            gstNo = "24AAACB2894G1ZT",
            invoiceNo = "HF2724I002324467",
            partyName = "One Airtel Monthly Statement",
            totalGrand = BigDecimal("41.55"),
            rawText = "One Airtel Monthly Statement",
        )
        assertTrue(noTaxable.confidence() >= Pipeline.CONFIDENCE_THRESHOLD, "confident on everything but the tax table")
        val result = pipeline.process(Pipeline.Input(purchaseInvoices = listOf(noTaxable)))

        assertTrue(result.purchaseRows.isEmpty(), "a taxable-less invoice must not be booked")
        val item = result.review.single()
        assertEquals(ReviewReason.LOW_CONFIDENCE, item.reason)
        assertTrue("TAXABLE VALUE" in item.detail, "the review must say why")
    }

    @Test
    fun `statement lines that could not be resolved are surfaced, not dropped`() {
        val result = pipeline.process(Pipeline.Input(unparsedLines = listOf("12/06/2025 SOMETHING 500.00 700.00")))
        assertEquals(ReviewReason.PARSE_FAILED, result.review.single().reason)
    }

    @Test
    fun `reports what it did`() {
        val result = pipeline.process(
            Pipeline.Input(
                purchaseInvoices = listOf(invoice()),
                transactions = listOf(debit("5310.00"), debit("450.00", description = "BANK CHARGES"), credit("900.00")),
            ),
        )
        assertEquals(1, result.stats["matched"])
        assertEquals(2, result.stats["purchase_rows"])
        assertEquals(1, result.stats["unmatched_credits"])
    }
}
