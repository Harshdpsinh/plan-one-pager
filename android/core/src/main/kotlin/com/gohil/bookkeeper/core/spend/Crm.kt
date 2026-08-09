package com.gohil.bookkeeper.core.spend

import com.gohil.bookkeeper.core.model.RegisterRow
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Which side of the business a party sits on. */
enum class PartyRole(val label: String) {
    CUSTOMER("Customer"),
    SUPPLIER("Supplier"),
    /** Appears on both registers — an insurer that pays commission and also invoices you. */
    BOTH("Customer & supplier"),
}

/**
 * One party the business deals with, assembled from the invoices already processed.
 *
 * Not a contact book: nothing here is typed in by hand. Every field is something an invoice
 * actually said, which is what makes it trustworthy — and also what limits it. There is no
 * phone number or address because no invoice field reliably carries one.
 */
data class Party(
    val name: String,
    val gstNo: String?,
    val role: PartyRole,
    val invoiceCount: Int,
    val totalBilled: BigDecimal,
    val totalTax: BigDecimal,
    val firstSeen: LocalDate?,
    val lastSeen: LocalDate?,
    /** Sales rows only. Null when this party has never appeared on a sale. */
    val gstTreatment: String?,
    val unpaidCount: Int,
) {
    val averageInvoice: BigDecimal
        get() = if (invoiceCount == 0) BigDecimal.ZERO
        else totalBilled.divide(BigDecimal(invoiceCount), 2, RoundingMode.HALF_UP)
}

/**
 * Builds the client list from register rows.
 *
 * Parties are keyed by GST number when there is one, because that is the identifier that
 * actually identifies them: the same insurer appears as "Star Health", "Star Health and
 * Allied", and "STAR HEALTH INSURANCE CO" across three invoices, and merging those by name
 * would need fuzzy matching that could just as easily merge two genuinely different firms.
 * A GST number is exact. Rows without one fall back to a normalised name, which is the best
 * available and is not pretended to be more.
 */
object Crm {

    fun build(
        salesRows: List<RegisterRow>,
        purchaseRows: List<RegisterRow>,
    ): List<Party> {
        val buckets = LinkedHashMap<String, MutableList<Pair<RegisterRow, PartyRole>>>()

        fun add(row: RegisterRow, role: PartyRole) {
            val name = row.name?.trim().orEmpty()
            if (name.isBlank() && row.gstNo.isNullOrBlank()) return
            val key = row.gstNo?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                ?: name.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
            buckets.getOrPut(key) { mutableListOf() } += row to role
        }

        salesRows.forEach { add(it, PartyRole.CUSTOMER) }
        purchaseRows.forEach { add(it, PartyRole.SUPPLIER) }

        return buckets.values.map { entries ->
            val rows = entries.map { it.first }
            val roles = entries.map { it.second }.distinct()
            val dates = rows.mapNotNull { it.date }

            Party(
                // The longest spelling seen, on the theory that "Star Health and Allied
                // Insurance" is the real name and "Star Health" is the abbreviation.
                name = rows.mapNotNull { it.name?.trim() }.filter { it.isNotBlank() }
                    .maxByOrNull { it.length } ?: "(unnamed)",
                gstNo = rows.firstNotNullOfOrNull { it.gstNo?.trim()?.takeIf(String::isNotBlank) },
                role = if (roles.size > 1) PartyRole.BOTH else roles.first(),
                invoiceCount = rows.size,
                totalBilled = rows.sumOrZero { it.totalGrand },
                totalTax = rows.sumOrZero { it.cgst } + rows.sumOrZero { it.sgst } +
                    rows.sumOrZero { it.igst },
                firstSeen = dates.minOrNull(),
                lastSeen = dates.maxOrNull(),
                gstTreatment = rows.firstNotNullOfOrNull { it.gstType?.name },
                // A row with no paid date is an invoice with no payment matched to it. Worth
                // surfacing: on the sales side that is commission not yet received.
                unpaidCount = rows.count { it.paidDate == null },
            )
        }.sortedByDescending { it.totalBilled }
    }

    private fun <T> List<T>.sumOrZero(select: (T) -> BigDecimal?): BigDecimal =
        fold(BigDecimal.ZERO) { acc, item -> acc + (select(item) ?: BigDecimal.ZERO) }
}
