package com.gohil.bookkeeper.core.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Sorting a month's paperwork, so the user does not have to.
 *
 * Excerpts are shortened from the user's own July documents, keeping the phrases each
 * decision turns on.
 */
class DocumentClassifierTest {

    private val own = "24BFKPG0156H1ZR"

    private val bank = """
        STATEMENT OF ACCOUNT
        Branch Code : 60455      Branch Name : SHASTRI NAGAR
        Account Number : 00000012345678      IFSC Code : SBIN0060455
        MICR Code : 364002001    Closing Balance : 2,51,958.48CR
        03/07/2026 NEFT*SCBL26071234567*SCBLH18400 - - 834.43 2,92,769.81
        Please do not share your ATM, Debit/Credit Card number, PIN or OTP with anyone.
    """.trimIndent()

    private val card = """
        *Total Amount Due ( ) 1,05,207.00      **Minimum Amount Due ( ) 2,104.00
        Credit Limit ( ) 3,08,000.00      Cash Limit ( ) 92,000.00
        Available Credit Limit ( ) 2,02,792.71      Payment Due Date 10 Aug 2026
        REWARD SUMMARY
        23 Jun 26 UPI-BLINKIT COMMERCE PRE LIMITED 293.00 D
    """.trimIndent()

    /** Self-billed: this business is the supplier, so its GSTIN comes first. */
    private val commission = """
        TAX INVOICE
        Details of Supplier of Service
        Name Harshdipsinh Pradipsinh Gohil
        Date : 06/07/2026     Inv serial No. : AXTI/2026-27/003
        GSTIN/Unique ID 24BFKPG0156H1ZR
        Details of Recipient (Billed to)
        Name Axis Mutual Fund
        GSTIN/Unique ID 27AACTA5925A1Z5
        Sr.No. Description of Services SAC Taxable Value CGST SGST IGST
        Total 1740.72 0.00 0.00 313.33
    """.trimIndent()

    /** A vendor's bill: the vendor is the supplier, so this business comes second. */
    private val vendorBill = """
        TAX INVOICE
        CLICKTECH RETAIL PRIVATE LIMITED
        GSTIN 24AAJCC9783E1ZD
        Invoice No AMD2-660495     Invoice Date 05/07/2026
        Billed to Harshdipsinh Pradipsinh Gohil
        GSTIN 24BFKPG0156H1ZR
        HSN 84713010 Taxable Value 58465.26 CGST 5261.87 SGST 5261.81
    """.trimIndent()

    @Test
    fun `an account statement is not a card just because it warns about card fraud`() {
        assertEquals(DocumentKind.BANK_STATEMENT, DocumentClassifier.classify(bank, own))
    }

    @Test
    fun `a card statement is a card`() {
        assertEquals(DocumentKind.CARD_STATEMENT, DocumentClassifier.classify(card, own))
    }

    @Test
    fun `an invoice this business raised is a sale`() {
        assertEquals(DocumentKind.SALES_INVOICE, DocumentClassifier.classify(commission, own))
    }

    @Test
    fun `an invoice billed to this business is a purchase`() {
        assertEquals(DocumentKind.PURCHASE_INVOICE, DocumentClassifier.classify(vendorBill, own))
    }

    @Test
    fun `without our own GSTIN every invoice is treated as a purchase`() {
        // The safe default: a purchase row is matched against a payment before it is
        // written, a sales row is not, so a mis-filed purchase is the recoverable mistake.
        assertEquals(DocumentKind.PURCHASE_INVOICE, DocumentClassifier.classify(commission, null))
    }

    @Test
    fun `a two-column bill that prints the buyer first is still a purchase`() {
        // Amazon lays "Sold By" and "Billing Address" out as columns, and extraction emits
        // the buyer's registration before the seller's. Deciding by position filed a laptop
        // this business bought as something it sold.
        val amazon = """
            Tax Invoice/Bill of Supply/Cash Memo
            Sold By : Billing Address :
            Clicktech Retail Private Limited HARSHDIPSINH PRADIPSINH GOHIL
            Ahmedabad, GUJARAT, 382220 BHAVNAGAR, GJ, 364004
            GST Registration No: 24BFKPG0156H1ZR
            State/UT Code: 24
            PAN No: AAJCC9783E
            GST Registration No: 24AAJCC9783E1ZD
            Invoice Number : AMD2-660495     Invoice Date : 06.07.2026
        """.trimIndent()

        assertEquals(DocumentKind.PURCHASE_INVOICE, DocumentClassifier.classify(amazon, own))
    }

    @Test
    fun `an invoice that labels neither party is read from what it charges for`() {
        // CAMS prints the distributor's letterhead and leaves the roles implicit.
        val cams = """
            Harshdipsinh Pradipsinh Gohil
            GSTIN    : 24BFKPG0156H1ZR Distributor Code    : ARN-162262
            Tax Invoice
            Invoice No    :      BM/26-27/E/2
            Aditya Birla Sun Life Mutual Fund GSTIN    :     27AAATB0102C1ZR
            1 Distributor Commission for Sale of Mutual Fund Products 988.38
            Total Invoice Value 1166.29
        """.trimIndent()

        assertEquals(DocumentKind.SALES_INVOICE, DocumentClassifier.classify(cams, own))
    }

    @Test
    fun `our own GSTIN is the one that appears on nearly everything`() {
        assertEquals(own, DocumentClassifier.ownGstin(listOf(commission, vendorBill, bank)))
    }

    @Test
    fun `a repeated GSTIN in one document does not get to vote twice`() {
        val repeated = vendorBill + "\n" + vendorBill.lines().first { "24AAJCC9783E1ZD" in it }
        assertEquals(own, DocumentClassifier.ownGstin(listOf(repeated, commission)))
    }

    @Test
    fun `no majority means no answer`() {
        // Two bills from one vendor and nothing of ours: the vendor is on both, but that
        // makes it the vendor, not us. Guessing here would file every sale as a purchase.
        assertNull(DocumentClassifier.ownGstin(listOf("GSTIN 27AACTA5925A1Z5", "nothing here")))
    }
}
