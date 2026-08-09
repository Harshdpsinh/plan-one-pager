package com.gohil.bookkeeper.core.xlsx

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a brand-new multi-sheet workbook.
 *
 * The opposite job to [XlsxAppender], and a much easier one: there is no existing file whose
 * unmodelled features must survive, because there is no existing file. That is why this can
 * write a whole package while the appender does zip surgery — the risk that justifies the
 * surgery does not exist here.
 *
 * Text is written as inline strings, so there is no shared string table to keep consistent.
 * Slightly larger output, one fewer part that can disagree with itself.
 */
object XlsxWriter {

    sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: BigDecimal) : Cell
        /** Rendered with the money format, right-aligned by Excel because it is numeric. */
        data class Money(val value: BigDecimal) : Cell
        data class Date(val value: LocalDate) : Cell
        data object Blank : Cell

        companion object {
            fun of(value: String?): Cell = if (value.isNullOrBlank()) Blank else Text(value)
            fun of(value: BigDecimal?): Cell = if (value == null) Blank else Money(value)
            fun of(value: LocalDate?): Cell = if (value == null) Blank else Date(value)
            fun count(value: Int): Cell = Number(BigDecimal(value))
        }
    }

    data class Sheet(
        val name: String,
        val headers: List<String>,
        val rows: List<List<Cell>>,
        /** Lines written above the header, for anything the reader needs told in words. */
        val notes: List<String> = emptyList(),
        /** A bold row appended at the bottom. Empty means no totals row. */
        val totals: List<Cell> = emptyList(),
        /** Zero-based column index to width in characters. */
        val widths: Map<Int, Int> = emptyMap(),
    )

    fun build(sheets: List<Sheet>): ByteArray {
        require(sheets.isNotEmpty()) { "A workbook needs at least one sheet." }
        val named = uniqueNames(sheets)
        val parts = LinkedHashMap<String, String>()

        parts["[Content_Types].xml"] = buildString {
            append(DECL)
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="$MAIN_TYPE"/>""")
            sheets.indices.forEach {
                append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="$SHEET_TYPE"/>""")
            }
            append("""<Override PartName="/xl/styles.xml" ContentType="$STYLES_TYPE"/>""")
            append("</Types>")
        }

        parts["_rels/.rels"] = DECL +
            """<Relationships xmlns="$REL_NS">""" +
            """<Relationship Id="rId1" Type="$REL_NS_DOC/officeDocument" Target="xl/workbook.xml"/>""" +
            "</Relationships>"

        parts["xl/workbook.xml"] = buildString {
            append(DECL)
            append("""<workbook xmlns="$MAIN_NS" xmlns:r="$REL_NS_DOC">""")
            append("<sheets>")
            named.forEachIndexed { i, name ->
                append("""<sheet name="${name.xmlAttr()}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
            }
            append("</sheets></workbook>")
        }

        parts["xl/_rels/workbook.xml.rels"] = buildString {
            append(DECL)
            append("""<Relationships xmlns="$REL_NS">""")
            sheets.indices.forEach {
                append(
                    """<Relationship Id="rId${it + 1}" Type="$REL_NS_DOC/worksheet" """ +
                        """Target="worksheets/sheet${it + 1}.xml"/>""",
                )
            }
            append(
                """<Relationship Id="rId${sheets.size + 1}" Type="$REL_NS_DOC/styles" Target="styles.xml"/>""",
            )
            append("</Relationships>")
        }

        parts["xl/styles.xml"] = STYLES
        sheets.forEachIndexed { i, sheet -> parts["xl/worksheets/sheet${i + 1}.xml"] = sheetXml(sheet) }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, text) in parts) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        val bytes = out.toByteArray()
        // The same gate the appender uses. A workbook that will not validate must never leave
        // this function — it is going to an accountant, and "Excel found a problem with some
        // content" is the failure this whole project already had once.
        XlsxValidator.validate(XlsxPackage.read(bytes))
        return bytes
    }

    // ── sheet ────────────────────────────────────────────────────────────────────

    private fun sheetXml(sheet: Sheet): String = buildString {
        val width = maxOf(
            sheet.headers.size,
            sheet.rows.maxOfOrNull { it.size } ?: 0,
            sheet.totals.size,
        )
        val headerRow = sheet.notes.size + if (sheet.notes.isEmpty()) 1 else 2
        val lastRow = headerRow + sheet.rows.size + if (sheet.totals.isEmpty()) 0 else 1

        append(DECL)
        append("""<worksheet xmlns="$MAIN_NS" xmlns:r="$REL_NS_DOC">""")
        // Element order is fixed by the schema; getting it wrong is what made Excel reject a
        // real register once, so it is written literally in sequence here.
        append("""<dimension ref="A1:${CellRef.columnName(maxOf(width, 1) - 1)}${maxOf(lastRow, 1)}"/>""")
        append("""<sheetViews><sheetView workbookViewId="0">""")
        append("""<pane ySplit="$headerRow" topLeftCell="A${headerRow + 1}" activePane="bottomLeft" state="frozen"/>""")
        append("""</sheetView></sheetViews>""")
        append("""<sheetFormatPr defaultRowHeight="15"/>""")
        if (sheet.widths.isNotEmpty()) {
            append("<cols>")
            sheet.widths.toSortedMap().forEach { (index, chars) ->
                append("""<col min="${index + 1}" max="${index + 1}" width="$chars" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")

        var r = 1
        sheet.notes.forEach { note ->
            append("""<row r="$r" spans="1:$width">""")
            append(cellXml("A$r", Cell.Text(note), S_NOTE))
            append("</row>")
            r++
        }
        if (sheet.notes.isNotEmpty()) r++ // one blank row between the notes and the table

        append("""<row r="$r" spans="1:$width">""")
        sheet.headers.forEachIndexed { c, header ->
            append(cellXml("${CellRef.columnName(c)}$r", Cell.Text(header), S_HEADER))
        }
        append("</row>")
        r++

        sheet.rows.forEach { row ->
            append("""<row r="$r" spans="1:$width">""")
            row.forEachIndexed { c, cell ->
                if (cell != Cell.Blank) append(cellXml("${CellRef.columnName(c)}$r", cell, styleFor(cell)))
            }
            append("</row>")
            r++
        }

        if (sheet.totals.isNotEmpty()) {
            append("""<row r="$r" spans="1:$width">""")
            sheet.totals.forEachIndexed { c, cell ->
                if (cell != Cell.Blank) {
                    append(cellXml("${CellRef.columnName(c)}$r", cell, boldOf(styleFor(cell))))
                }
            }
            append("</row>")
        }

        append("</sheetData>")
        append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
        append("</worksheet>")
    }

    private fun cellXml(ref: String, cell: Cell, style: Int): String = when (cell) {
        is Cell.Text -> """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">""" +
            "${cell.value.xmlText()}</t></is></c>"
        is Cell.Number -> """<c r="$ref" s="$style"><v>${cell.value.toPlainString()}</v></c>"""
        is Cell.Money -> """<c r="$ref" s="$style"><v>${cell.value.toPlainString()}</v></c>"""
        is Cell.Date -> """<c r="$ref" s="$style"><v>${serial(cell.value)}</v></c>"""
        Cell.Blank -> """<c r="$ref" s="$style"/>"""
    }

    private fun styleFor(cell: Cell): Int = when (cell) {
        is Cell.Money -> S_MONEY
        is Cell.Date -> S_DATE
        else -> S_NORMAL
    }

    private fun boldOf(style: Int): Int = when (style) {
        S_MONEY -> S_MONEY_BOLD
        else -> S_HEADER
    }

    /**
     * Excel's day zero is 1899-12-30, not 1900-01-01 — the extra two days absorb a leap-year
     * bug Lotus 1-2-3 had and Excel kept for compatibility. Getting this wrong shifts every
     * date by two days, which reads as plausible and is therefore worse than an obvious error.
     */
    internal fun serial(date: LocalDate): Long = ChronoUnit.DAYS.between(EPOCH, date)

    /**
     * Excel rejects `: \ / ? * [ ]` in a tab name, caps it at 31 characters, and refuses
     * duplicates. Silently truncating to a duplicate would produce an unopenable file, so
     * collisions get a numeric suffix.
     */
    private fun uniqueNames(sheets: List<Sheet>): List<String> {
        val used = mutableSetOf<String>()
        return sheets.map { sheet ->
            val cleaned = sheet.name.replace(Regex("""[:\\/?*\[\]]"""), " ").trim().take(31)
                .ifBlank { "Sheet" }
            var candidate = cleaned
            var n = 2
            while (!used.add(candidate.lowercase())) {
                val suffix = " ($n)"
                candidate = cleaned.take(31 - suffix.length) + suffix
                n++
            }
            candidate
        }
    }

    private fun String.xmlText(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            // Control characters are not legal in XML 1.0 at all, and OCR output does contain
            // them. One stray byte would make the whole workbook unopenable.
            .filter { it == '\t' || it == '\n' || it >= ' ' }

    private fun String.xmlAttr(): String = xmlText().replace("\"", "&quot;")

    // ── constants ────────────────────────────────────────────────────────────────

    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    private const val S_NORMAL = 0
    private const val S_HEADER = 1
    private const val S_DATE = 2
    private const val S_MONEY = 3
    private const val S_MONEY_BOLD = 4
    private const val S_NOTE = 5

    private const val DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val REL_NS_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val MAIN_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    private const val SHEET_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    private const val STYLES_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"

    /**
     * Six formats, in the order the S_* constants name them.
     *
     * Money is `#,##0.00` rather than the Indian lakh-grouped code. The grouping is cosmetic,
     * the digits are not, and the plain code is the one every version of Excel and every
     * reader agrees on — worth more than commas in the right places on a file going to a CA.
     */
    private val STYLES = DECL +
        """<styleSheet xmlns="$MAIN_NS">""" +
        """<numFmts count="2">""" +
        """<numFmt numFmtId="164" formatCode="dd\-mm\-yyyy"/>""" +
        """<numFmt numFmtId="165" formatCode="#,##0.00"/>""" +
        """</numFmts>""" +
        """<fonts count="3">""" +
        """<font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>""" +
        """<font><b/><sz val="11"/><color theme="1"/><name val="Calibri"/></font>""" +
        """<font><i/><sz val="10"/><color rgb="FF595959"/><name val="Calibri"/></font>""" +
        """</fonts>""" +
        """<fills count="3">""" +
        """<fill><patternFill patternType="none"/></fill>""" +
        """<fill><patternFill patternType="gray125"/></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor rgb="FFE8EDF2"/><bgColor indexed="64"/></patternFill></fill>""" +
        """</fills>""" +
        """<borders count="2">""" +
        """<border><left/><right/><top/><bottom/><diagonal/></border>""" +
        """<border><left/><right/><top/><bottom style="thin"><color rgb="FFB0B8C1"/></bottom><diagonal/></border>""" +
        """</borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="6">""" +
        """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>""" +
        """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="165" fontId="1" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1"/>""" +
        """<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
        """</cellXfs></styleSheet>"""
}
