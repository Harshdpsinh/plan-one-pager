package com.gohil.bookkeeper.web

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Telling a bank statement from a credit-card statement.
 *
 * This is not a cosmetic label. It decides how a row with no Dr/Cr marker is read, so a
 * wrong answer does not blur the result — it inverts it, and money received becomes money
 * spent.
 *
 * The excerpts below are shortened from the user's own July documents, keeping the phrases
 * each decision actually turns on.
 */
class StatementKindTest {

    private lateinit var dir: Path
    private lateinit var routes: SpendRoutes

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kind")
        routes = SpendRoutes(null, LoanRepository(dir.resolve("loans.json")))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a bank statement is not a card just because it warns you about card fraud`() {
        // The regression this test exists for. Every bank prints this sentence, it contains
        // "credit card" and "card number", and the old rule needed only two card words
        // anywhere in the document. On the July SBI statement that read 53 commission
        // receipts as 53 expenses and put Rs 1,14,898 of income into the spending chart.
        val sbi = """
            STATEMENT OF ACCOUNT
            Mr. HARSHDIPSINH PRADIPSINH GOHIL   State Bank of India
            Branch Code : 60455      Branch Name : SHASTRI NAGAR
            Account Number : 00000012345678      IFSC Code : SBIN0060455
            MICR Code : 364002001    Clear Balance : 2,51,958.48CR
            03/07/2026 03/07/2026 NEFT*SCBL26071234567*SCBLH18400 - - 834.43 2,92,769.81
            Please do not share your ATM, Debit/Credit Card number, PIN, OTP, Username or
            Password with anyone via email, SMS, phone call, or any other medium.
        """.trimIndent()

        assertFalse(routes.looksLikeCard(sbi), "this is a current account")
    }

    @Test
    fun `an account statement whose legend explains card transactions is still an account`() {
        val icici = """
            Statement of Transactions in Saving Account no. 001234567890 in INR
            S No. Transaction Date Cheque Number Transaction Remarks Withdrawal Deposit Balance
            1 01.07.2026 LIC OF INDIA BHAVNAGAR DIVISION AC
            Legends for transactions in your Account Statement
            PAVC - Pay any Visa credit card       CCWD - Cardless Cash Withdrawal
            Never share your OTP, CVV or passwords with anyone.
        """.trimIndent()

        assertFalse(routes.looksLikeCard(icici))
    }

    @Test
    fun `a card statement is a card`() {
        val sbiCard = """
            GSTIN of SBI Card : 06AAECS5981K1ZV
            *Total Amount Due ( ) 1,05,207.00      **Minimum Amount Due ( ) 2,104.00
            Credit Limit ( ) (including cash) 3,08,000.00      Cash Limit ( ) 92,000.00
            Available Credit Limit ( ) 2,02,792.71      Payment Due Date 10 Aug 2026
            REWARD SUMMARY
            23 Jun 26 UPI-BLINKIT COMMERCE PRE LIMITED 293.00 D
        """.trimIndent()

        assertTrue(routes.looksLikeCard(sbiCard))
    }

    @Test
    fun `a card that says almost nothing about itself is still a card`() {
        // CSB's statement carries hardly any labels at all — no "total amount due", no
        // limits, just a heading and a table. It wins two words to nothing, which is the
        // narrowest margin across the nine real statements, and it has to win: read as a
        // bank statement its 21 rows have no balance column to reason from and every one of
        // them goes to review.
        val csb = """
            14 JUN 2026 - 16 JUL 2026
            GOHIL HARSHDIPSINH PRADIPSINH
            Pay your bill now
            Rupay Transactions - 0677
            16 Jun 26 ASSPL Bangalore kaIN Rs. 500.00
            17 Jun 26 METRO BRANDS LIMITED BHAVNAGAR GJIN Rs. 9,990
        """.trimIndent()

        assertTrue(routes.looksLikeCard(csb))
    }

    @Test
    fun `a document that is neither is treated as an account`() {
        // An invoice or a receipt reaches here too. Bank is the safer default: it resolves
        // nothing it cannot prove, where card falls back to assuming a purchase.
        assertFalse(routes.looksLikeCard("TAX INVOICE\nSharma Enterprises\nTotal 5,310.00"))
    }
}
