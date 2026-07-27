package com.gohil.bookkeeper.core.xlsx

import com.gohil.bookkeeper.core.model.Category
import com.gohil.bookkeeper.core.model.GstType
import com.gohil.bookkeeper.core.model.PaymentMethod
import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.model.RegisterType
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test

/**
 * Writes real .xlsx files to build/fixtures so they can be opened by an independent
 * implementation.
 *
 * The unit tests above read the output back with the same code that wrote it, which cannot
 * detect a structural mistake both halves agree on. `verify_fixtures.py` opens these with
 * openpyxl instead — a completely separate parser — and CI runs it on every push.
 */
class FixtureDumpTest {

    private val outDir = File("build/fixtures").apply { mkdirs() }

    @Test
    fun `dump fixtures for external validation`() {
        val purchase = TestWorkbooks.build(
            sheets = listOf(
                "May" to TestWorkbooks.sampleRows(2),
                "Jun" to TestWorkbooks.sampleRows(3),
            ),
        )
        File(outDir, "purchase_before.xlsx").writeBytes(purchase)

        val purchaseAppender = XlsxAppender.open(purchase)
        purchaseAppender.append(
            XlsxAppender.Request(
                sheetName = "Jun",
                registerType = RegisterType.PURCHASE,
                rows = listOf(
                    RegisterRow(
                        date = LocalDate.of(2025, 6, 12),
                        gstNo = "24AABCU9603R1ZX",
                        invoiceNo = "INV-9001",
                        name = "Acme Traders & Sons <Pvt> Ltd",
                        qty = "1",
                        ratePct = BigDecimal("18"),
                        hsn = "998311",
                        taxable = BigDecimal("2000.00"),
                        cgst = BigDecimal("180.00"),
                        sgst = BigDecimal("180.00"),
                        totalGrand = BigDecimal("2360.00"),
                        paidDate = LocalDate.of(2025, 6, 20),
                        paymentMethod = PaymentMethod.UPI,
                        category = Category.BUSINESS,
                    ),
                    // No taxable value: the amount must land directly in TOTAL GRAND with
                    // no SUM formula (the bug that used to write 0.00 here).
                    RegisterRow(
                        date = LocalDate.of(2025, 6, 5),
                        name = "ATM WDL CHARGES",
                        totalGrand = BigDecimal("450.00"),
                        paymentMethod = PaymentMethod.BANK_TRANSFER,
                        category = Category.BUSINESS,
                    ),
                    RegisterRow(
                        date = LocalDate.of(2025, 6, 18),
                        name = "SWIGGY ORDER",
                        totalGrand = BigDecimal("612.50"),
                        paymentMethod = PaymentMethod.CREDIT_CARD,
                        category = Category.PERSONAL,
                    ),
                ),
            ),
        )
        File(outDir, "purchase_after.xlsx").writeBytes(purchaseAppender.toByteArray())

        // A brand-new month tab, cloned from the previous one.
        val newTab = XlsxAppender.open(purchase)
        newTab.append(
            XlsxAppender.Request(
                sheetName = "Jul",
                registerType = RegisterType.PURCHASE,
                rows = listOf(
                    RegisterRow(
                        date = LocalDate.of(2025, 7, 3),
                        invoiceNo = "INV-9100",
                        name = "New Month Vendor",
                        taxable = BigDecimal("500.00"),
                        cgst = BigDecimal("45.00"),
                        sgst = BigDecimal("45.00"),
                        totalGrand = BigDecimal("590.00"),
                        paymentMethod = PaymentMethod.BANK_TRANSFER,
                        category = Category.BUSINESS,
                    ),
                ),
            ),
        )
        File(outDir, "purchase_newtab.xlsx").writeBytes(newTab.toByteArray())

        // Sales register: full month name on the tab, GST TYPE instead of CATEGORY.
        val sales = TestWorkbooks.build(sheets = listOf("June" to TestWorkbooks.sampleRows(2)))
        val salesAppender = XlsxAppender.open(sales)
        salesAppender.append(
            XlsxAppender.Request(
                sheetName = "June",
                registerType = RegisterType.SALES,
                rows = listOf(
                    RegisterRow(
                        date = LocalDate.of(2025, 6, 15),
                        invoiceNo = "COMM-441",
                        name = "Star Health and Allied Insurance",
                        taxable = BigDecimal("10000.00"),
                        totalGrand = BigDecimal("10000.00"),
                        paymentMethod = PaymentMethod.BANK_TRANSFER,
                        gstType = GstType.RCM,
                    ),
                    RegisterRow(
                        date = LocalDate.of(2025, 6, 22),
                        invoiceNo = "COMM-442",
                        name = "HDFC Life Insurance",
                        taxable = BigDecimal("5000.00"),
                        cgst = BigDecimal("450.00"),
                        sgst = BigDecimal("450.00"),
                        totalGrand = BigDecimal("5900.00"),
                        paymentMethod = PaymentMethod.BANK_TRANSFER,
                        gstType = GstType.DIRECT,
                    ),
                ),
            ),
        )
        File(outDir, "sales_after.xlsx").writeBytes(salesAppender.toByteArray())
    }
}
