package com.gohil.bookkeeper.web

import com.gohil.bookkeeper.core.spend.Loan
import com.gohil.bookkeeper.core.spend.LoanDirection
import com.gohil.bookkeeper.core.spend.Repayment
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The loan book on disk, as readable JSON.
 *
 * Deliberately plain text rather than a database: a loan to a family member is a record the
 * user may need in five years, possibly after this app is gone, and a file they can open in
 * Notepad survives that. It is also small enough that rewriting the whole file on every change
 * is simpler and safer than partial updates.
 *
 * Amounts are written as strings so BigDecimal survives the round trip exactly. A JSON number
 * would go through a double somewhere and turn ₹1,00,000 into 99999.99999999999.
 */
class LoanRepository(private val file: Path = PasswordStore.defaultDir().resolve("loans.json")) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var cache: MutableList<Loan>? = null

    @Synchronized
    fun all(): List<Loan> = loaded().toList()

    @Synchronized
    fun save(loan: Loan) {
        val list = loaded()
        val index = list.indexOfFirst { it.id == loan.id }
        if (index >= 0) list[index] = loan else list += loan
        persist(list)
    }

    @Synchronized
    fun delete(id: String) {
        val list = loaded()
        if (list.removeIf { it.id == id }) persist(list)
    }

    private fun loaded(): MutableList<Loan> {
        cache?.let { return it }
        val list = mutableListOf<Loan>()
        if (Files.exists(file)) {
            // A hand-edited file with one bad line should not lose the other loans, so each
            // entry is parsed independently and a broken one is skipped rather than fatal.
            runCatching { json.parseToJsonElement(Files.readString(file)).jsonArray }
                .getOrNull()
                ?.forEach { element -> runCatching { list += element.jsonObject.toLoan() }.getOrNull() }
        }
        cache = list
        return list
    }

    private fun persist(list: List<Loan>) {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(JsonArray.serializer(), list.toJsonArray()))
        cache = list.toMutableList()
    }

    private fun List<Loan>.toJsonArray(): JsonArray = buildJsonArray {
        for (loan in this@toJsonArray) {
            add(
                buildJsonObject {
                    put("id", loan.id)
                    put("counterparty", loan.counterparty)
                    put("direction", loan.direction.name)
                    put("principal", loan.principal.toPlainString())
                    put("annualRatePct", loan.annualRatePct.toPlainString())
                    put("startDate", loan.startDate.toString())
                    put("termMonths", loan.termMonths)
                    put("note", loan.note)
                    putJsonArray("repayments") {
                        for (repayment in loan.repayments) {
                            add(
                                buildJsonObject {
                                    put("date", repayment.date.toString())
                                    put("amount", repayment.amount.toPlainString())
                                    put("note", repayment.note)
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    private fun JsonObject.toLoan(): Loan = Loan(
        id = str("id"),
        counterparty = str("counterparty"),
        direction = LoanDirection.valueOf(str("direction").ifBlank { "GIVEN" }),
        principal = BigDecimal(str("principal").ifBlank { "0" }),
        annualRatePct = BigDecimal(str("annualRatePct").ifBlank { "0" }),
        startDate = LocalDate.parse(str("startDate")),
        termMonths = str("termMonths").toIntOrNull() ?: 12,
        note = str("note"),
        repayments = (this["repayments"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            Repayment(
                date = LocalDate.parse(o.str("date")),
                amount = BigDecimal(o.str("amount").ifBlank { "0" }),
                note = o.str("note"),
            )
        },
    )

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content.orEmpty()

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
        this ?: emptyList()
}
