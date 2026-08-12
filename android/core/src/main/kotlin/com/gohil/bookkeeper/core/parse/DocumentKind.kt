package com.gohil.bookkeeper.core.parse

/**
 * What kind of document was just uploaded.
 *
 * The point of this class is that the user should not have to say. Sorting a month's
 * paperwork into four boxes is work a person does badly and a computer does consistently:
 * the documents announce what they are, and mis-filing one is silent — a commission invoice
 * dropped into the purchase box books income as an expense.
 */
enum class DocumentKind {
    BANK_STATEMENT,
    CARD_STATEMENT,

    /** A bill from a supplier. Goes to the purchase register. */
    PURCHASE_INVOICE,

    /** A commission invoice raised by this business. Goes to the sales register. */
    SALES_INVOICE,
}

object DocumentClassifier {

    /**
     * A card statement talks about limits and amounts due; an account statement talks about
     * IFSC codes and withdrawals. Both mention cards, so neither vocabulary decides alone —
     * see the note on [classify] about the fraud warning every bank prints.
     */
    private val CARD_WORDS = listOf(
        "total amount due", "total payment due", "minimum amount due", "minimum payment due",
        "payment due date", "available credit limit", "credit limit", "cash limit",
        "reward points", "rupay", "card statement", "cardholder", "billing period",
        "statement date", "pay your bill",
    )

    private val BANK_WORDS = listOf(
        "statement of account", "statement of transactions", "account number", "account no",
        "ifsc", "micr", "branch code", "branch name", "closing balance", "opening balance",
        "withdrawal", "deposit", "cheque number", "value date", "narration",
    )

    /** Present on both kinds of statement and on neither kind of invoice. */
    private val STATEMENT_WORDS = CARD_WORDS + BANK_WORDS

    private val INVOICE_WORDS = listOf(
        "tax invoice", "invoice no", "invoice date", "bill of supply", "hsn", "sac",
        "taxable value", "place of supply", "reverse charge", "gstin",
    )

    /**
     * Classify one document, given the GSTIN of the business doing the bookkeeping.
     *
     * [ownGstin] is what separates a purchase from a sale, and it is the only thing that
     * can. Both are tax invoices naming two parties; the difference is which side this
     * business is on. A commission invoice is self-billed — the distributor raises it — so
     * his registration appears first, as the supplier. On a vendor's bill it appears second,
     * as the recipient. Pass null and every invoice is treated as a purchase, which is the
     * safer default: a purchase row is checked against a payment, a sales row is not.
     */
    fun classify(text: String, ownGstin: String?): DocumentKind {
        val lower = text.lowercase()

        val statementHits = STATEMENT_WORDS.count { it in lower }
        val invoiceHits = INVOICE_WORDS.count { it in lower }

        if (statementHits > invoiceHits) {
            return if (CARD_WORDS.count { it in lower } > BANK_WORDS.count { it in lower }) {
                DocumentKind.CARD_STATEMENT
            } else {
                DocumentKind.BANK_STATEMENT
            }
        }

        if (ownGstin == null) return DocumentKind.PURCHASE_INVOICE
        return whichSideAreWeOn(text, ownGstin) ?: DocumentKind.PURCHASE_INVOICE
    }

    /** Labels naming the party who raised the invoice. */
    private val SUPPLIER_LABELS =
        listOf("details of supplier", "supplier", "sold by", "seller", "from :", "issued by")

    /** Labels naming the party being charged. */
    private val RECIPIENT_LABELS = listOf(
        "billing address", "billed to", "bill to", "details of recipient", "recipient",
        "buyer", "customer", "ship to", "shipping address", "invoice to",
    )

    /** A commission invoice this business raises. No vendor bill says any of this. */
    private val COMMISSION_WORDS = listOf(
        "distribution commission", "distributor commission", "brokerage",
        "commission for sale of mutual fund",
    )

    /**
     * Which side of the invoice this business is on.
     *
     * Reading the label our own registration sits under, rather than its position on the
     * page. Position looked right on the first four invoices and then failed: Amazon prints
     * "Sold By" and "Billing Address" as two columns, and extraction emits the buyer's GSTIN
     * before the seller's, so a laptop this business *bought* was filed as something it sold.
     *
     * The nearest label preceding our GSTIN wins, because a tax invoice states each party's
     * role immediately above that party's details.
     */
    private fun whichSideAreWeOn(text: String, ownGstin: String): DocumentKind? {
        val lower = text.lowercase()
        val at = lower.indexOf(ownGstin.lowercase())
        if (at < 0) return null

        val before = lower.take(at)
        val supplier = SUPPLIER_LABELS.maxOf { before.lastIndexOf(it) }
        val recipient = RECIPIENT_LABELS.maxOf { before.lastIndexOf(it) }
        if (supplier >= 0 || recipient >= 0) {
            return if (supplier > recipient) DocumentKind.SALES_INVOICE else DocumentKind.PURCHASE_INVOICE
        }

        // Some issuers label neither side — the letterhead is the supplier and that is left
        // implicit. What they do say is what the money was for.
        if (COMMISSION_WORDS.any { it in lower }) return DocumentKind.SALES_INVOICE
        return null
    }

    /**
     * The bookkeeping business's own GSTIN, worked out from the documents themselves.
     *
     * It is the one registration that appears on nearly everything — every invoice this
     * business sends and every invoice it receives names it, while each counterparty appears
     * once. Deriving it beats asking: a setting typed once is a setting typed wrongly once,
     * and it would be wrong silently, on every row.
     *
     * Requires a majority rather than a plurality. With two documents from the same vendor
     * and none of our own, the vendor would otherwise win.
     */
    fun ownGstin(texts: Collection<String>): String? {
        if (texts.isEmpty()) return null
        val counts = HashMap<String, Int>()
        for (text in texts) {
            // Once per document: an invoice that repeats a GSTIN in a footer must not vote twice.
            Patterns.GSTIN.findAll(text)
                .map { it.groupValues[1].uppercase() }
                .toSet()
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val (best, seen) = counts.maxByOrNull { it.value }?.toPair() ?: return null
        return if (seen * 2 > texts.size) best else null
    }
}
