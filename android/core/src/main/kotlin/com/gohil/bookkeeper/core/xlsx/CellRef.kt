package com.gohil.bookkeeper.core.xlsx

import java.time.LocalDate

/** Spreadsheet column/row reference helpers. Column indices are 0-based throughout. */
object CellRef {

    /** 0 -> "A", 25 -> "Z", 26 -> "AA". */
    fun columnName(index: Int): String {
        require(index >= 0) { "column index must be >= 0, was $index" }
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.append(('A' + (n % 26)))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.reverse().toString()
    }

    /** "A" -> 0, "AA" -> 26. Case-insensitive. */
    fun columnIndex(name: String): Int {
        require(name.isNotEmpty()) { "empty column name" }
        var result = 0
        for (ch in name) {
            val up = ch.uppercaseChar()
            require(up in 'A'..'Z') { "bad column name: $name" }
            result = result * 26 + (up - 'A' + 1)
        }
        return result - 1
    }

    /** "BC12" -> ("BC", 12). Returns null if the ref is malformed. */
    fun split(ref: String): Pair<String, Int>? {
        val m = REF.matchEntire(ref.trim()) ?: return null
        return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: return null)
    }

    fun columnOf(ref: String): Int? = split(ref)?.let { columnIndex(it.first) }

    fun rowOf(ref: String): Int? = split(ref)?.second

    fun of(columnIndex: Int, row: Int): String = columnName(columnIndex) + row

    private val REF = Regex("""\$?([A-Za-z]+)\$?(\d+)""")

    /**
     * Excel's day-zero is 1899-12-30, not 1900-01-01 — the offset absorbs Excel's
     * deliberate bug of treating 1900 as a leap year. Only correct for dates from
     * 1900-03-01 onward, which covers every date a bookkeeping register will ever hold.
     */
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    /** Alternate epoch used by workbooks with the 1904 date system set. */
    private val EXCEL_EPOCH_1904: LocalDate = LocalDate.of(1904, 1, 1)

    private fun epoch(date1904: Boolean) = if (date1904) EXCEL_EPOCH_1904 else EXCEL_EPOCH

    fun toSerial(date: LocalDate, date1904: Boolean = false): Long =
        date.toEpochDay() - epoch(date1904).toEpochDay()

    fun fromSerial(serial: Long, date1904: Boolean = false): LocalDate =
        epoch(date1904).plusDays(serial)
}

/** Escapes text for use inside an XML element or attribute. */
internal fun String.xmlEscape(): String {
    val sb = StringBuilder(length + 16)
    for (ch in this) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else ->
                // XML 1.0 forbids most control characters outright; they would make the
                // whole workbook unreadable, so they are dropped rather than encoded.
                if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') Unit else sb.append(ch)
        }
    }
    return sb.toString()
}

/** Reverses [xmlEscape] plus the numeric character references Excel sometimes emits. */
internal fun String.xmlUnescape(): String {
    if ('&' !in this) return this
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val ch = this[i]
        if (ch != '&') {
            sb.append(ch); i++; continue
        }
        val semi = indexOf(';', i)
        if (semi < 0 || semi - i > 10) {
            sb.append(ch); i++; continue
        }
        when (val entity = substring(i + 1, semi)) {
            "amp" -> sb.append('&')
            "lt" -> sb.append('<')
            "gt" -> sb.append('>')
            "quot" -> sb.append('"')
            "apos" -> sb.append('\'')
            else -> {
                val code = when {
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.drop(2).toIntOrNull(16)
                    entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                    else -> null
                }
                if (code != null) sb.appendCodePoint(code) else sb.append(substring(i, semi + 1))
            }
        }
        i = semi + 1
    }
    return sb.toString()
}
