package com.gohil.bookkeeper.core.parse

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvoiceParserTest {

    private val typicalInvoice = """
        SHARMA OFFICE SUPPLIES PVT LTD
        123 MG Road, Ahmedabad, Gujarat 380009
        GSTIN: 24AABCU9603R1ZM

        TAX INVOICE
        Invoice No: INV-2025-0412
        Date: 12/06/2025

        Description        HSN      Qty    Rate    Amount
        A4 Paper Ream      48201020  10    450.00  4500.00

        Taxable Value: 4500.00
        CGST @ 9%: 405.00
        SGST @ 9%: 405.00
        Grand Total: 5310.00
    """.trimIndent()

    @Test
    fun `extracts the standard invoice fields`() {
        val inv = InvoiceParser.parse(typicalInvoice, "INV-2025-0412.pdf")

        assertEquals(LocalDate.of(2025, 6, 12), inv.date)
        assertEquals("24AABCU9603R1ZM", inv.gstNo)
        assertEquals("INV-2025-0412", inv.invoiceNo)
        assertEquals("48201020", inv.hsn)
        assertEquals(BigDecimal("4500.00"), inv.taxable)
        assertEquals(BigDecimal("5310.00"), inv.totalGrand)
    }

    // ── bug #1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `bug 1 - a GST rate in the label does not swallow the amount`() {
        // The original pattern let the flexible label section match bare digits, so the
        // leading figures of the amount were consumed and "CGST @ 9%: 405.00" produced 5.00
        // (or 0.00). Wrong tax figures written into a GST register.
        val inv = InvoiceParser.parse(typicalInvoice, "x.pdf")
        assertEquals(BigDecimal("405.00"), inv.cgst)
        assertEquals(BigDecimal("405.00"), inv.sgst)
    }

    @Test
    fun `bug 1 - handles every common way the tax line is written`() {
        val variants = mapOf(
            "CGST: 450.00" to "450.00",
            "CGST 450.00" to "450.00",
            "CGST @ 9% 450.00" to "450.00",
            "CGST @9%: 450.00" to "450.00",
            "CGST(9%): 450.00" to "450.00",
            "CGST 9 % - 450.00" to "450.00",
            "CGST @ 2.5% : 1,25,450.00" to "125450.00",
        )
        for ((line, expected) in variants) {
            val inv = InvoiceParser.parse("Vendor Name Ltd\n$line", "x.pdf")
            assertEquals(BigDecimal(expected), inv.cgst, "failed on: $line")
        }
    }

    @Test
    fun `reads IGST separately from CGST and SGST`() {
        val inv = InvoiceParser.parse(
            "Interstate Vendor Ltd\nTaxable Value: 1000.00\nIGST @ 18%: 180.00\nTotal: 1180.00",
            "x.pdf",
        )
        assertEquals(BigDecimal("180.00"), inv.igst)
        assertNull(inv.cgst)
        assertNull(inv.sgst)
    }

    // ── bug #3 ───────────────────────────────────────────────────────────────────

    @Test
    fun `bug 3 - vendor name comes from the letterhead, not the filename`() {
        // This is the failure that mattered most: with a camera filename the old code used
        // "IMG_2034", which silently broke both RCM detection and categorisation.
        val inv = InvoiceParser.parse(typicalInvoice, "IMG_2034.pdf")
        assertEquals("SHARMA OFFICE SUPPLIES PVT LTD", inv.partyName)
        assertTrue(!inv.partyNameFromFilename)
    }

    @Test
    fun `bug 3 - skips GSTINs, dates and field labels when looking for the letterhead`() {
        val leadingNoise = """
            TAX INVOICE
            Date: 12/06/2025
            GSTIN: 24AABCU9603R1ZM
            ------------------------
            Kalpataru Insurance Advisors
            Invoice No: 77
            Total: 100.00
        """.trimIndent()
        val inv = InvoiceParser.parse(leadingNoise, "scan.pdf")
        assertEquals("Kalpataru Insurance Advisors", inv.partyName)
    }

    @Test
    fun `bug 3 - falls back to the filename but records that it did`() {
        val noLetterhead = "GSTIN: 24AABCU9603R1ZM\nInvoice No: 12\nTotal: 500.00"
        val inv = InvoiceParser.parse(noLetterhead, "Reliance Digital bill.pdf")
        assertEquals("Reliance Digital bill", inv.partyName)
        assertTrue(inv.partyNameFromFilename, "must be flagged so confidence drops")
    }

    @Test
    fun `bug 3 - a generic camera filename yields no name at all rather than a fake one`() {
        val inv = InvoiceParser.parse("GSTIN: 24AABCU9603R1ZM\nTotal: 500.00", "IMG_2034.pdf")
        assertNull(inv.partyName, "IMG_2034 is not a vendor name")
    }

    // ── dates ────────────────────────────────────────────────────────────────────

    @Test
    fun `reads the date formats Indian invoices actually use`() {
        val cases = mapOf(
            "Date: 05/06/2025" to LocalDate.of(2025, 6, 5),
            "Date: 05-06-2025" to LocalDate.of(2025, 6, 5),
            "Date: 05.06.2025" to LocalDate.of(2025, 6, 5),
            "Date: 5 Jun 2025" to LocalDate.of(2025, 6, 5),
            "Date: 05 June 2025" to LocalDate.of(2025, 6, 5),
            "Date: Jun 5, 2025" to LocalDate.of(2025, 6, 5),
            "Date: 2025-06-05" to LocalDate.of(2025, 6, 5),
            "Date: 05/06/25" to LocalDate.of(2025, 6, 5),
        )
        for ((text, expected) in cases) {
            assertEquals(expected, Patterns.findDate(text), "failed on: $text")
        }
    }

    @Test
    fun `treats an ambiguous numeric date as day-first`() {
        // Indian invoices are day-first; 05/06/2025 is 5 June, not 6 May.
        assertEquals(LocalDate.of(2025, 6, 5), Patterns.findDate("05/06/2025"))
        // But an impossible day-first reading is corrected.
        assertEquals(LocalDate.of(2025, 6, 25), Patterns.findDate("06/25/2025"))
    }

    // ── confidence ───────────────────────────────────────────────────────────────

    @Test
    fun `a fully read invoice is confident enough to write`() {
        val inv = InvoiceParser.parse(typicalInvoice, "x.pdf")
        assertTrue(inv.confidence() >= 0.70, "confidence was ${inv.confidence()}")
        assertTrue(inv.missingFields().isEmpty())
    }

    @Test
    fun `an invoice missing its total is not confident enough`() {
        val inv = InvoiceParser.parse("Some Vendor Ltd\nInvoice No: 12\nDate: 01/06/2025", "x.pdf")
        assertNull(inv.totalGrand)
        assertTrue(inv.confidence() < 0.70)
        assertTrue("TOTAL GRAND" in inv.missingFields())
    }

    @Test
    fun `derives the total from its components only when none is stated`() {
        val inv = InvoiceParser.parse(
            "Vendor Ltd\nTaxable Value: 1000.00\nCGST @ 9%: 90.00\nSGST @ 9%: 90.00",
            "x.pdf",
        )
        assertEquals(BigDecimal("1180.00"), inv.totalGrand)
    }

    @Test
    fun `prefers the final restated total when an invoice repeats it`() {
        val inv = InvoiceParser.parse(
            """
            Vendor Ltd
            Total: 1000.00
            Freight: 200.00
            Grand Total: 1200.00
            """.trimIndent(),
            "x.pdf",
        )
        assertEquals(BigDecimal("1200.00"), inv.totalGrand)
    }

    @Test
    fun `reads ungrouped amounts at full length`() {
        // Regression: the money pattern allowed its comma-grouped branch to match with zero
        // commas, which captured only the first three digits. Every four-figure ungrouped
        // amount was silently divided by ten — 1000.00 became 100.00 in the register.
        val cases = mapOf(
            "Total: 1000.00" to "1000.00",
            "Total: 9999.99" to "9999.99",
            "Total: 12345.67" to "12345.67",
            "Total: 1234567.89" to "1234567.89",
            "Total: 100.00" to "100.00",
            "Total: 1,25,450.00" to "125450.00",
            "Total: 12,345.67" to "12345.67",
        )
        for ((text, expected) in cases) {
            val inv = InvoiceParser.parse("Vendor Ltd\n$text", "x.pdf")
            assertEquals(BigDecimal(expected), inv.totalGrand, "failed on: $text")
        }
    }

    @Test
    fun `finds an HSN code printed as a table column`() {
        val inv = InvoiceParser.parse(typicalInvoice, "x.pdf")
        assertEquals("48201020", inv.hsn)
    }

    @Test
    fun `does not mistake a postal code for an HSN code`() {
        val addressOnly = """
            Sharma Supplies
            123 MG Road, Ahmedabad, Gujarat 380009
            Invoice No: 5
            Date: 01/06/2025
            Total: 500.00
        """.trimIndent()
        assertNull(InvoiceParser.parse(addressOnly, "x.pdf").hsn)
    }

    @Test
    fun `handles Indian digit grouping`() {
        assertEquals(BigDecimal("125450.00"), Patterns.money("1,25,450.00"))
        assertEquals(BigDecimal("1250.50"), Patterns.money("₹ 1,250.50"))
        assertEquals(BigDecimal("999.00"), Patterns.money("Rs. 999.00"))
    }

    @Test
    fun `returns an empty invoice rather than throwing on unreadable text`() {
        val inv = InvoiceParser.parse("", "empty.pdf")
        assertNotNull(inv)
        assertNull(inv.totalGrand)
        assertTrue(inv.confidence() < 0.70)
    }

    // ── what the first real commission invoices exposed ──────────────────────────
    //
    // A distributor's commission invoice is self-billed: he raises it, so he is its
    // supplier and the fund house is its recipient. That is the opposite way round from a
    // vendor bill, and reading it as one put his own GSTIN and his own name into the
    // counterparty columns of all twenty-one rows of a GST return.

    private val kfintechStyle = """
        TAX INVOICE
        Details of Supplier of Service
        Name Harshdipsinh Pradipsinh Gohil ( ARN-162262 )
        DAIRY ROAD VIJAYRAJNAGAR, BHAVNAGAR Date : 06/07/2026
        GUJARAT, 364003 Inv serial No. : AXTI/2026-27/003
        GSTIN/Unique ID 24BFKPG0156H1ZR
        Details of Recipient (Billed to)
        Name Axis Mutual Fund
        State Code 27
        GSTIN/Unique ID 27AACTA5925A1Z5
        Sr.No. Description of Services SAC Taxable Value CGST SGST IGST
        1 Distribution Commission for 997152 1740.72 0.00% 0.00 0.00% 0.00 18.00% 313.33
        sale of Mutual Fund products for the month of JUNE - 2026
        Total 1740.72 0.00 0.00 313.33
        Total Invoice Value (In figure) 2054.05
    """.trimIndent()

    private val camsStyle = """
        Harshdipsinh Pradipsinh Gohil
        GSTIN    : 24BFKPG0156H1ZR Distributor Code    : ARN-162262
        Tax Invoice
        Invoice No    :      BM/26-27/E/2
        Invoice Date     :          JUL 06,2026
        Aditya Birla Sun Life Mutual Fund GSTIN    :     27AAATB0102C1ZR
        Sl.No Description SAC Taxable CGST SGST IGST
        1 Distributor Commission for Sale 9971 988.38 0 0.00 0 0.00 18 177.91
        Total 988.38 0.00 0.00 177.91
        Total Invoice Value 1166.29
    """.trimIndent()

    @Test
    fun `a sales invoice takes the recipient, not the party who raised it`() {
        val inv = InvoiceParser.parse(kfintechStyle, "axis.pdf", InvoiceParser.Counterparty.RECIPIENT)
        assertEquals("27AACTA5925A1Z5", inv.gstNo)
        assertEquals("Axis Mutual Fund", inv.partyName)
    }

    @Test
    fun `the recipient's name can sit on the same line as its GSTIN`() {
        val inv = InvoiceParser.parse(camsStyle, "ABSL.pdf", InvoiceParser.Counterparty.RECIPIENT)
        assertEquals("27AAATB0102C1ZR", inv.gstNo)
        assertEquals("Aditya Birla Sun Life Mutual Fund", inv.partyName)
    }

    @Test
    fun `a purchase invoice still takes the supplier`() {
        val inv = InvoiceParser.parse(kfintechStyle, "axis.pdf")
        assertEquals("24BFKPG0156H1ZR", inv.gstNo, "the first GSTIN, as before")
    }

    @Test
    fun `the tax breakdown is read from the totals row when the labels are column headings`() {
        val inv = InvoiceParser.parse(kfintechStyle, "axis.pdf", InvoiceParser.Counterparty.RECIPIENT)
        assertEquals("1740.72", inv.taxable?.toPlainString())
        assertEquals("0.00", inv.cgst?.toPlainString())
        assertEquals("0.00", inv.sgst?.toPlainString())
        assertEquals("313.33", inv.igst?.toPlainString())
        assertEquals("2054.05", inv.totalGrand?.toPlainString(), "not the taxable value")
    }

    @Test
    fun `the labelled date beats a month named in the description`() {
        // "for the month of JUNE - 2026" reads as 20 June 2026 — the month name, then 20 and
        // 26 out of the year. Every one of these invoices carries that sentence.
        assertEquals(
            "2026-07-06",
            InvoiceParser.parse(kfintechStyle, "axis.pdf", InvoiceParser.Counterparty.RECIPIENT).date.toString(),
        )
    }

    @Test
    fun `an invoice number survives a qualifier and a doubled separator`() {
        assertEquals(
            "AXTI/2026-27/003",
            InvoiceParser.parse(kfintechStyle, "axis.pdf", InvoiceParser.Counterparty.RECIPIENT).invoiceNo,
        )
    }

    @Test
    fun `an empty invoice number field does not take the next line`() {
        // One real invoice leaves "Inv serial No. :" blank. A separator run that crossed the
        // line break captured the following label as the invoice number.
        val blank = kfintechStyle.replace("Inv serial No. : AXTI/2026-27/003", "Inv serial No. :")
        assertNull(InvoiceParser.parse(blank, "lic.pdf", InvoiceParser.Counterparty.RECIPIENT).invoiceNo)
    }

    @Test
    fun `the rate comes from the figures, not from the first percent sign on the page`() {
        // The two taxes that do not apply are printed as 0.00% before the one that does.
        val inv = InvoiceParser.parse(kfintechStyle, "axis.pdf", InvoiceParser.Counterparty.RECIPIENT)
        assertEquals("18", inv.ratePct?.toPlainString())
    }
}
