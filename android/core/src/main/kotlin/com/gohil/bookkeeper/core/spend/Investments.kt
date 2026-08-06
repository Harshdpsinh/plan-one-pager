package com.gohil.bookkeeper.core.spend

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Broad instrument type. Enough detail for a CA to know which schedule a figure belongs to. */
enum class InstrumentType(val label: String) {
    MUTUAL_FUND("Mutual fund / SIP"),
    EQUITY("Equity & broking"),
    LIFE_INSURANCE("Life insurance premium"),
    DEPOSIT("Fixed / recurring deposit"),
    RETIREMENT("PPF, NPS & EPF"),
    GOLD("Gold bonds"),
}

/** One outflow that bought an asset rather than paying for something consumed. */
data class InvestmentItem(
    val date: LocalDate,
    val description: String,
    val amount: BigDecimal,
    val type: InstrumentType,
    val matchedOn: String,
    val source: String,
)

data class InstrumentTotal(val type: InstrumentType, val total: BigDecimal, val count: Int)

/**
 * Splits investment outflows away from spending.
 *
 * Same reasoning as the loan book: a ₹10,000 SIP is not an expense. The money changed shape,
 * not owner, and charting it as spending would say this household consumed ₹10,000 it still
 * has. Leaving it in would also quietly inflate the expense figure a CA works from.
 *
 * Only debits ever reach here, which matters for this particular business: the owner
 * distributes mutual funds and advises on insurance, so "HDFC Life" and "SBI Mutual Fund"
 * appear on the statement as *commission credits* far more often than as premiums paid.
 * Those are inflows and never get classified.
 *
 * The keyword list is deliberately tight. An investment left in the expense list is visibly
 * wrong in a table a human reads; an expense wrongly pulled out of the expense list is money
 * that quietly stops appearing in the spending total.
 */
object InvestmentRules {

    data class Match(val type: InstrumentType, val matchedOn: String)

    private val RULES: List<Pair<InstrumentType, List<String>>> = listOf(
        InstrumentType.MUTUAL_FUND to listOf(
            "mutual fund", "sip instalment", "sip installment", "sip payment", "sip debit",
            "groww", "kuvera", "coin by zerodha", "bse star mf", "nse mfss", "mf purchase",
            "nippon india mf", "hdfc amc", "icici prudential mf", "sbi mutual", "axis mutual",
            "uti mutual", "mirae asset", "parag parikh", "quant mutual", "elss", "cams",
            "kfintech", "karvy mf", "folio",
            // Every SIP on the ICICI account settles through the BSE StAR MF clearing house,
            // so the fund's name never appears on the statement — only this. Without it the
            // month's SIPs were charted as ordinary spending.
            "indian clearing corp", "indian clearin",
        ),
        InstrumentType.EQUITY to listOf(
            "zerodha", "upstox", "angel one", "angel broking", "5paisa", "icici direct",
            "hdfc securities", "kotak securities", "motilal oswal", "sharekhan", "indmoney",
            "dhan broking", "demat", "cdsl", "nsdl", "trading account",
        ),
        InstrumentType.LIFE_INSURANCE to listOf(
            "lic premium", "lic of india", "life insurance premium", "hdfc life", "sbi life",
            "icici pru life", "max life", "tata aia", "bajaj allianz life", "policy premium",
        ),
        InstrumentType.DEPOSIT to listOf(
            "fixed deposit", "term deposit", "recurring deposit", "rd instalment",
            "rd installment", "auto sweep", "sweep in deposit",
        ),
        InstrumentType.RETIREMENT to listOf(
            "ppf", "national pension", "nps contribution", "epf", "sukanya samriddhi",
            "provident fund",
        ),
        InstrumentType.GOLD to listOf(
            "sovereign gold bond", "gold bond", "digital gold", "sgb subscription",
        ),
    )

    fun match(text: String): Match? {
        val lower = text.lowercase()
        // Longest keyword across all rules wins, so "sbi life" beats a shorter accidental hit.
        return RULES
            .flatMap { (type, keywords) -> keywords.filter { it in lower }.map { type to it } }
            .maxByOrNull { it.second.length }
            ?.let { Match(it.first, it.second) }
    }
}

/** The aggregates the Investments sheet and the dashboard tile both read. */
data class InvestmentSummary(
    val items: List<InvestmentItem>,
    val byType: List<InstrumentTotal>,
    val byMonth: List<MonthTotal>,
    val total: BigDecimal,
) {
    companion object {
        fun from(items: List<InvestmentItem>): InvestmentSummary = InvestmentSummary(
            items = items.sortedByDescending { it.date },
            byType = items.groupBy { it.type }
                .map { (type, rows) ->
                    InstrumentTotal(type, rows.fold(BigDecimal.ZERO) { a, r -> a + r.amount }, rows.size)
                }
                .sortedByDescending { it.total },
            byMonth = items.groupBy { YearMonth.from(it.date) }
                .map { (month, rows) ->
                    MonthTotal(month, rows.fold(BigDecimal.ZERO) { a, r -> a + r.amount }, rows.size)
                }
                .sortedBy { it.month },
            total = items.fold(BigDecimal.ZERO) { a, r -> a + r.amount },
        )
    }
}
