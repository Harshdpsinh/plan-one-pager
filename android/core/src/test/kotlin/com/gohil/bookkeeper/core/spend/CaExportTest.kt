package com.gohil.bookkeeper.core.spend

import com.gohil.bookkeeper.core.model.Category
import com.gohil.bookkeeper.core.model.GstType
import com.gohil.bookkeeper.core.model.PaymentMethod
import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.xlsx.CellRef
import com.gohil.bookkeeper.core.xlsx.WorkbookIndex
import com.gohil.bookkeeper.core.xlsx.XlsxPackage
import com.gohil.bookkeeper.core.xlsx.XlsxWriter
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CaExportTest {

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private fun spendItem(day: Int, amount: String, category: SpendCategory, merchant: String) =
        SpendItem(
            date = LocalDate.of(2026, 6, day),
            merchant = merchant,
            amount = BigDecimal(amount),
            category = category,
            matchedOn = if (category == SpendCategory.UNCATEGORISED) null else "kw",
            confident = category != SpendCategory.UNCATEGORISED,
            source = "hdfc-june.pdf",
        )

    private fun input(): CaExport.Input = CaExport.Input(
        spend = SpendSummary.from(
            listOf(
                spendItem(5, "1240.00", SpendCategory.MEALS, "UPI/SWIGGY LTD"),
                spendItem(7, "3410.50", SpendCategory.TRAVEL, "IRCTC WEB BOOKING"),
                spendItem(9, "900.00", SpendCategory.UNCATEGORISED, "QWERTY ENTERPRISES"),
            ),
        ),
        investments = InvestmentSummary.from(
            listOf(
                InvestmentItem(
                    date = LocalDate.of(2026, 6, 1),
                    description = "SIP INSTALMENT PARAG PARIKH FLEXI",
                    amount = BigDecimal("10000.00"),
                    type = InstrumentType.MUTUAL_FUND,
                    matchedOn = "parag parikh",
                    source = "hdfc-june.pdf",
                ),
            ),
        ),
        loans = listOf(
            Loan(
                id = "l1",
                counterparty = "Ramesh Patel",
                direction = LoanDirection.GIVEN,
                principal = BigDecimal("50000"),
                annualRatePct = BigDecimal.ZERO,
                startDate = LocalDate.of(2026, 1, 10),
                termMonths = 10,
                repayments = listOf(Repayment(LocalDate.of(2026, 2, 10), BigDecimal("20000"))),
            ),
        ),
        salesRows = listOf(
            RegisterRow(
                date = LocalDate.of(2026, 6, 20),
                gstNo = "24AABCU9603R1ZM",
                invoiceNo = "SL-2026-08",
                name = "Star Health Insurance",
                taxable = BigDecimal("40000.00"),
                cgst = BigDecimal("3600.00"),
                sgst = BigDecimal("3600.00"),
                totalGrand = BigDecimal("47200.00"),
                paymentMethod = PaymentMethod.BANK_TRANSFER,
                gstType = GstType.RCM,
            ),
        ),
        purchaseRows = listOf(
            RegisterRow(
                date = LocalDate.of(2026, 6, 12),
                invoiceNo = "INV-9001",
                name = "Sharma Office Supplies",
                taxable = BigDecimal("2000.00"),
                cgst = BigDecimal("180.00"),
                sgst = BigDecimal("180.00"),
                totalGrand = BigDecimal("2360.00"),
                paymentMethod = PaymentMethod.UPI,
                category = Category.BUSINESS,
            ),
        ),
        generatedOn = LocalDate.of(2026, 7, 2),
    )

    private fun sheetText(bytes: ByteArray, tab: String): String {
        val pkg = XlsxPackage.read(bytes)
        val path = WorkbookIndex.from(pkg).findSheet(tab) ?: fail("no sheet named '$tab'")
        return pkg.text(path.path) ?: fail("sheet '$tab' has no content")
    }

    // ── tests ────────────────────────────────────────────────────────────────────

    @Test
    fun `produces one workbook with every sheet the accountant needs`() {
        val bytes = CaExport.build(input())
        val names = WorkbookIndex.from(XlsxPackage.read(bytes)).sheets.map { it.name }
        assertEquals(
            listOf(
                "Summary", "Expenses", "Expense by category", "Investments",
                "Sales (GST)", "Purchases (GST)", "Loans", "Cash flow",
            ),
            names,
        )
    }

    @Test
    fun `every part is well-formed XML`() {
        val pkg = XlsxPackage.read(CaExport.build(input()))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for (name in pkg.entryNames) {
            if (!name.endsWith(".xml") && !name.endsWith(".rels")) continue
            val content = pkg[name] ?: continue
            try {
                factory.newDocumentBuilder().parse(content.inputStream())
            } catch (e: Exception) {
                fail("part '$name' is not well-formed XML: ${e.message}")
            }
        }
    }

    @Test
    fun `investments are not counted as expenses anywhere`() {
        val input = input()
        assertEquals("5550.50", input.spend.total.toPlainString())
        assertEquals("10000.00", input.investments.total.toPlainString())

        val expenses = sheetText(CaExport.build(input), "Expenses")
        assertFalse(
            expenses.contains("PARAG PARIKH"),
            "a SIP must not appear in the expense sheet — the money was not consumed",
        )
        assertContains(sheetText(CaExport.build(input), "Investments"), "PARAG PARIKH")
    }

    @Test
    fun `the sales sheet carries the full GST breakdown`() {
        val sales = sheetText(CaExport.build(input()), "Sales (GST)")
        assertContains(sales, "24AABCU9603R1ZM")
        assertContains(sales, "SL-2026-08")
        assertContains(sales, "Star Health Insurance")
        assertContains(sales, "40000.00")
        assertContains(sales, "3600.00")
        assertContains(sales, "47200.00")
        assertContains(sales, "RCM")
    }

    @Test
    fun `a missing field stays blank rather than becoming a zero`() {
        // IGST is unset on the fixture rows. A zero there would be a wrong number in a filing.
        val sales = sheetText(CaExport.build(input()), "Sales (GST)")
        val row = Regex("""<row r="6"[^>]*>.*?</row>""").find(sales)?.value ?: fail("no data row")
        assertFalse(
            Regex("""<c r="H6"[^>]*>\s*<v>0""").containsMatchIn(row),
            "IGST was not read, so its cell must be empty: $row",
        )
    }

    @Test
    fun `flagged expenses sort to the bottom where they can be dealt with together`() {
        val expenses = sheetText(CaExport.build(input()), "Expenses")
        val uncategorised = expenses.indexOf("QWERTY ENTERPRISES")
        val swiggy = expenses.indexOf("SWIGGY")
        assertTrue(uncategorised > swiggy, "rows needing a decision belong at the end")
        assertContains(expenses, "Yes - no rule matched")
    }

    @Test
    fun `the summary names the period covered and separates the three kinds of money`() {
        val summary = sheetText(CaExport.build(input()), "Summary")
        assertContains(summary, "Jun 2026")
        assertContains(summary, "Expenses (money consumed)")
        assertContains(summary, "Investments (money moved into assets)")
        assertContains(summary, "Loans given")
        assertContains(summary, "Sales - GST charged".replace(" - ", " — "))
    }

    @Test
    fun `the filename names the period so it explains itself in an inbox`() {
        assertEquals("CA-Pack-Jun-2026.xlsx", CaExport.fileName(input()))
    }

    @Test
    fun `an empty GST side produces the sheet with an explanation rather than nothing`() {
        val bytes = CaExport.build(input().copy(salesRows = emptyList(), purchaseRows = emptyList()))
        val sales = sheetText(bytes, "Sales (GST)")
        assertContains(sales, "GST bookkeeping screen was not run")
    }

    @Test
    fun `works with no data at all`() {
        val bytes = CaExport.build(
            CaExport.Input(
                spend = SpendSummary.from(emptyList()),
                investments = InvestmentSummary.from(emptyList()),
                loans = emptyList(),
                generatedOn = LocalDate.of(2026, 7, 2),
            ),
        )
        assertEquals(8, WorkbookIndex.from(XlsxPackage.read(bytes)).sheets.size)
    }

    @Test
    fun `dates are written on Excel's own epoch`() {
        // 1899-12-30 is day zero. 2026-06-05 is 46178; being two days out here would look
        // plausible and go unnoticed, which is exactly why it is asserted.
        assertEquals(46178L, XlsxWriter.serial(LocalDate.of(2026, 6, 5)))
        assertEquals(1L, XlsxWriter.serial(LocalDate.of(1899, 12, 31)))
    }

    @Test
    fun `cash flow shows investing as an outflow that is not spending`() {
        val flow = sheetText(CaExport.build(input()), "Cash flow")
        assertContains(flow, "Expenses")
        assertContains(flow, "Invested")
        // June: 5,550.50 spent + 10,000 invested = 15,550.50 out.
        assertContains(flow, "15550.50")
    }

    @Test
    fun `dumps the workbook for the independent openpyxl check`() {
        val dir = File("build/fixtures").apply { mkdirs() }
        dir.resolve("ca_pack.xlsx").writeBytes(CaExport.build(input()))
    }

    @Test
    fun `control characters from OCR cannot make the workbook unopenable`() {
        val bytes = CaExport.build(
            input().copy(
                spend = SpendSummary.from(
                    listOf(spendItem(3, "10.00", SpendCategory.MEALS, "CAFE  BAD BYTES")),
                ),
            ),
        )
        // The guard is that build() validates; reaching here at all means it parsed.
        assertFalse(sheetText(bytes, "Expenses").contains(''))
    }

    @Test
    fun `a tab name Excel would reject is cleaned rather than written as-is`() {
        val bytes = XlsxWriter.build(
            listOf(
                XlsxWriter.Sheet("Sales/Purchases: 2026", listOf("A"), listOf(listOf(XlsxWriter.Cell.Text("x")))),
                XlsxWriter.Sheet("Sales/Purchases: 2026", listOf("A"), listOf(listOf(XlsxWriter.Cell.Text("y")))),
            ),
        )
        val names = WorkbookIndex.from(XlsxPackage.read(bytes)).sheets.map { it.name }
        assertEquals(2, names.distinct().size, "duplicate tab names make the file unopenable")
        assertTrue(names.none { it.contains('/') || it.contains(':') })
        assertTrue(names.all { it.length <= 31 })
    }

    @Test
    fun `column letters stay correct past Z`() {
        // The GST sheets are 13 columns wide, so M is the last one used; a bug in the letter
        // maths there would silently misplace the totals row.
        assertEquals("M", CellRef.columnName(12))
        assertEquals("AA", CellRef.columnName(26))
    }

    @Test
    fun `flags an expense that is also a GST purchase instead of silently dropping it`() {
        // The Sharma invoice is booked as an expense off the statement AND as a GST purchase
        // off the invoice, at the same date and total. Counting it twice overstates the month.
        val withOverlap = input().copy(
            spend = SpendSummary.from(
                listOf(
                    spendItem(5, "1240.00", SpendCategory.MEALS, "UPI/SWIGGY LTD"),
                    SpendItem(
                        date = LocalDate.of(2026, 6, 12),
                        merchant = "SHARMA OFFICE SUPPLIES",
                        amount = BigDecimal("2360.00"),
                        category = SpendCategory.OFFICE,
                        matchedOn = "office supplies",
                        confident = true,
                        source = "hdfc-june.pdf",
                    ),
                ),
            ),
        )
        val expenses = sheetText(CaExport.build(withOverlap), "Expenses")

        assertContains(expenses, "Also in Purchases (GST)")
        assertContains(expenses, "Yes - same date and amount")
        assertContains(expenses, "Count them ONCE")
        // Both rows survive — deleting one would lose a genuine repeat payment.
        assertContains(expenses, "SHARMA OFFICE SUPPLIES")
        assertContains(sheetText(CaExport.build(withOverlap), "Summary"), "Rows appearing in BOTH")
    }

    @Test
    fun `says nothing about overlap when there is none`() {
        val expenses = sheetText(CaExport.build(input()), "Expenses")
        assertContains(expenses, "Also in Purchases (GST)")
        assertFalse(expenses.contains("Count them ONCE"), "no overlap, so no warning")
        assertFalse(expenses.contains("Yes - same date and amount"))
    }
}
