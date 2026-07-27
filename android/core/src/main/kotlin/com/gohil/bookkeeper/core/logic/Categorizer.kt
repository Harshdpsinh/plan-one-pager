package com.gohil.bookkeeper.core.logic

import com.gohil.bookkeeper.core.model.BankTxn
import com.gohil.bookkeeper.core.model.Category
import com.gohil.bookkeeper.core.model.GstType
import com.gohil.bookkeeper.core.model.PaymentMethod
import com.gohil.bookkeeper.core.model.StatementSource
import com.gohil.bookkeeper.core.rules.CategoryRules

/** Rule-based classification. No inference beyond the keyword lists the user can edit. */
class Categorizer(private val rules: CategoryRules) {

    data class CategoryDecision(val category: Category, val ambiguous: Boolean)

    /**
     * Personal vs Business for the purchase register.
     *
     * Everything unrecognised defaults to Business, per the documented rule, but a
     * transaction hitting a known-ambiguous keyword (a marketplace that sells both office
     * supplies and groceries) is flagged so a human decides rather than accepting the default.
     */
    fun categorize(vararg text: String?): CategoryDecision {
        val joined = text.filterNotNull().joinToString(" ")
        val explicit = rules.categoryFor(joined)
        if (explicit != null) {
            return CategoryDecision(explicit, ambiguous = false)
        }
        return CategoryDecision(Category.BUSINESS, ambiguous = rules.isAmbiguous(joined))
    }

    /**
     * RCM vs Direct for the sales register.
     *
     * Only insurers explicitly confirmed in the rules are marked RCM. An unknown insurer is
     * Direct, because assuming RCM would understate GST payable on a return.
     */
    fun gstType(partyName: String?): GstType =
        if (rules.isRcmInsurer(partyName)) GstType.RCM else GstType.DIRECT

    /** Infers how a payment was made from the statement narration. */
    fun paymentMethod(txn: BankTxn?): PaymentMethod {
        if (txn == null) return PaymentMethod.UNKNOWN
        val fromText = rules.paymentMethodFor(txn.description)
        if (fromText != PaymentMethod.UNKNOWN) return fromText
        // Anything on a card statement was paid by card, whatever the narration says.
        return when (txn.source) {
            StatementSource.CREDIT_CARD -> PaymentMethod.CREDIT_CARD
            StatementSource.BANK -> PaymentMethod.BANK_TRANSFER
        }
    }
}
