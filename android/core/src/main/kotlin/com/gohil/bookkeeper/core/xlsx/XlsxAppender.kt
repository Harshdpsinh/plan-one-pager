package com.gohil.bookkeeper.core.xlsx

import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.model.RegisterType
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Appends rows to a month tab without disturbing anything already in the workbook.
 *
 * The guarantees this class is built to keep, in priority order:
 *  1. Existing rows and columns are never reordered, deleted or overwritten.
 *  2. New rows continue the sheet's own SR NO sequence.
 *  3. New rows inherit the formatting of the row above them, so the sheet stays consistent.
 *  4. Project-added columns are appended to the right, once, and reused thereafter.
 */
class XlsxAppender(private val pkg: XlsxPackage) {

    data class Request(
        val sheetName: String,
        val rows: List<RegisterRow>,
        val registerType: RegisterType,
        /**
         * When the tab does not exist, clone the layout of [templateSheetName] (or the
         * last sheet in the book) into a new tab rather than failing.
         */
        val createSheetIfMissing: Boolean = true,
        val templateSheetName: String? = null,
    )

    data class Result(
        val sheetName: String,
        val rowsWritten: Int,
        val firstRowNumber: Int,
        val lastRowNumber: Int,
        val firstSrNo: Int,
        val createdSheet: Boolean,
        val addedColumns: List<String>,
        val warnings: List<String>,
    )

    private val index = WorkbookIndex.from(pkg)

    fun sheetNames(): List<String> = index.sheetNames()

    fun append(request: Request): Result {
        val warnings = ArrayList<String>()

        var createdSheet = false
        val entry = index.findSheet(request.sheetName) ?: run {
            if (!request.createSheetIfMissing) {
                throw XlsxException(
                    "Tab '${request.sheetName}' was not found. Available tabs: " +
                        index.sheetNames().joinToString(", "),
                )
            }
            val template = request.templateSheetName ?: index.sheets.last().name
            val templateSheet = SheetXml(
                pkg.text(index.findSheet(template)!!.path)
                    ?: throw XlsxException("Template tab '$template' is missing its worksheet part."),
            )
            val headerRow = detectHeaderRow(templateSheet, SharedStrings.from(pkg))
            createdSheet = true
            warnings += "Created new tab '${request.sheetName}' by copying the layout of '$template'."
            index.cloneSheet(template, request.sheetName, headerRow.number)
        }

        val shared = SharedStrings.from(pkg)
        val sheet = SheetXml(
            pkg.text(entry.path) ?: throw XlsxException("Worksheet part missing for '${request.sheetName}'."),
        )

        val headerRow = detectHeaderRow(sheet, shared)
        val columns = mapColumns(sheet, headerRow, shared).toMutableMap()

        // Ensure this project's extra columns exist, appending them to the right of
        // whatever the sheet already has. Never inserted, so existing column letters,
        // formulas and references elsewhere in the workbook stay valid.
        val addedNames = ArrayList<String>()
        val newHeaderCells = StringBuilder()
        // Start after the sheet's real width, not merely after the last column this code
        // recognises. A column the user maintains themselves — "Notes", "Remarks" — sits to
        // the right of TOTAL GRAND but is invisible to the header map, so counting only
        // recognised columns would emit a second N1 into a row that already has one. That is
        // a duplicate cell reference, and it puts payment data under somebody else's heading.
        val occupiedMax = sheet.rows().flatMap { it.cells }.maxOfOrNull { it.column } ?: -1
        var nextFreeCol = maxOf(columns.values.maxOrNull() ?: -1, occupiedMax) + 1
        val headerStyle = sheet.rows().firstOrNull { it.number == headerRow.number }
            ?.cells?.maxByOrNull { it.column }?.styleIndex

        for (col in Col.addedColumnsFor(request.registerType)) {
            if (columns.containsKey(col)) continue
            newHeaderCells.append(
                renderTextCell(CellRef.of(nextFreeCol, headerRow.number), headerStyle, col.header),
            )
            columns[col] = nextFreeCol
            addedNames += col.header
            nextFreeCol++
        }
        if (newHeaderCells.isNotEmpty()) {
            sheet.appendCellsToRow(headerRow.number, newHeaderCells.toString())
        }

        val dataRows = sheet.rows().filter { it.number > headerRow.number }
        val lastDataRow = dataRows.maxByOrNull { it.number }

        // Formatting template: whatever the last populated row uses, per column. This is why
        // appended rows keep the sheet's currency symbols, date formats and cell borders.
        val styleByCol: Map<Int, Int> =
            lastDataRow?.cells?.mapNotNull { c -> c.styleIndex?.let { c.column to it } }?.toMap()
                ?: emptyMap()

        // A column this project just created has no row above it to copy formatting from,
        // so it borrows from its natural counterpart. Without this, PAID DATE inherits the
        // General format and a date lands in the register as a raw serial like "45828".
        val styleDonor = mapOf(
            Col.PAID_DATE to Col.DATE,
            Col.PAYMENT_METHOD to Col.NAME,
            Col.CATEGORY to Col.NAME,
            Col.GST_TYPE to Col.NAME,
        )
        val styleOf: (Col) -> Int? = { col ->
            columns[col]?.let { styleByCol[it] }
                ?: styleDonor[col]?.let { donor -> columns[donor]?.let { styleByCol[it] } }
        }

        val firstSrNo = nextSrNo(sheet, dataRows, columns, shared)

        // Dates are stored either as numeric serials with a date format, or as literal text,
        // depending on how the sheet was originally built. Matching what is already there
        // keeps the column sortable and consistent.
        val datesAreSerial = lastDataRow
            ?.cellAt(columns[Col.DATE] ?: -1)
            ?.let { it.type == null || it.type == "n" }
            ?: true

        var rowNumber = (sheet.lastRowNumber()).coerceAtLeast(headerRow.number)
        val firstRowNumber = rowNumber + 1
        var srNo = firstSrNo
        var usedFormula = false

        val body = StringBuilder()
        for (row in request.rows) {
            rowNumber++
            val rendered = renderRow(
                row = row,
                rowNumber = rowNumber,
                srNo = srNo,
                columns = columns,
                styleByCol = styleByCol,
                styleOf = styleOf,
                datesAreSerial = datesAreSerial,
                date1904 = index.date1904,
            )
            body.append(rendered.xml)
            if (rendered.usedFormula) usedFormula = true
            srNo++
        }

        sheet.appendRowsXml(body.toString())
        sheet.updateDimension()
        pkg.putText(entry.path, sheet.xml)

        if (usedFormula) {
            // New formulas invalidate the cached dependency chain; Excel rebuilds it.
            pkg.invalidateCalcChain()
        }

        if (lastDataRow == null && !createdSheet) {
            warnings += "Tab '${request.sheetName}' had no existing data rows — " +
                "new rows use the sheet's default formatting."
        }

        return Result(
            sheetName = request.sheetName,
            rowsWritten = request.rows.size,
            firstRowNumber = firstRowNumber,
            lastRowNumber = rowNumber,
            firstSrNo = firstSrNo,
            createdSheet = createdSheet,
            addedColumns = addedNames,
            warnings = warnings,
        )
    }

    fun toByteArray(): ByteArray = pkg.toByteArray()

    // ── internals ────────────────────────────────────────────────────────────────

    /**
     * Finds the header row by scoring the first few rows against the known vocabulary,
     * rather than assuming row 1. Real workbooks often carry a title or blank row on top.
     */
    private fun detectHeaderRow(sheet: SheetXml, shared: SharedStrings): SheetXml.Row {
        val candidates = sheet.rows().sortedBy { it.number }.take(HEADER_SEARCH_ROWS)
        val best = candidates
            .map { row ->
                val score = row.cells.count { cell ->
                    sheet.cellText(cell, shared)?.let { Col.fromHeader(it) != null } == true
                }
                row to score
            }
            .maxByOrNull { it.second }
        if (best == null || best.second < MIN_HEADER_MATCHES) {
            throw XlsxException(
                "Could not find the header row (looked for SR NO / DATE / INVOICE NO / TOTAL GRAND " +
                    "in the first $HEADER_SEARCH_ROWS rows). Is this the right workbook?",
            )
        }
        return best.first
    }

    private fun mapColumns(sheet: SheetXml, header: SheetXml.Row, shared: SharedStrings): Map<Col, Int> {
        val map = LinkedHashMap<Col, Int>()
        for (cell in header.cells.sortedBy { it.column }) {
            val text = sheet.cellText(cell, shared)?.trim().orEmpty()
            if (text.isEmpty()) continue
            val col = Col.fromHeader(text) ?: continue
            // First occurrence wins, so a stray duplicate header later in the row cannot
            // redirect writes away from the real column.
            map.putIfAbsent(col, cell.column)
        }
        return map
    }

    /** Continues the sheet's own SR NO sequence. Falls back to row position if unreadable. */
    private fun nextSrNo(
        sheet: SheetXml,
        dataRows: List<SheetXml.Row>,
        columns: Map<Col, Int>,
        shared: SharedStrings,
    ): Int {
        val srCol = columns[Col.SR_NO] ?: return dataRows.size + 1
        val maxSr = dataRows
            .mapNotNull { it.cellAt(srCol) }
            .mapNotNull { sheet.cellText(it, shared)?.trim()?.substringBefore('.')?.toIntOrNull() }
            .maxOrNull()
        return (maxSr ?: dataRows.size) + 1
    }

    private data class RenderedRow(val xml: String, val usedFormula: Boolean)

    private fun renderRow(
        row: RegisterRow,
        rowNumber: Int,
        srNo: Int,
        columns: Map<Col, Int>,
        styleByCol: Map<Int, Int>,
        styleOf: (Col) -> Int?,
        datesAreSerial: Boolean,
        date1904: Boolean,
    ): RenderedRow {
        val cells = sortedMapOf<Int, String>()
        var usedFormula = false

        fun putText(col: Col, value: String?) {
            val c = columns[col] ?: return
            cells[c] = renderTextCell(CellRef.of(c, rowNumber), styleOf(col), value)
        }

        fun putNumber(col: Col, value: BigDecimal?) {
            val c = columns[col] ?: return
            cells[c] = renderNumberCell(CellRef.of(c, rowNumber), styleOf(col), value)
        }

        fun putDate(col: Col, value: LocalDate?) {
            val c = columns[col] ?: return
            val ref = CellRef.of(c, rowNumber)
            val st = styleOf(col)
            cells[c] = when {
                value == null -> renderBlankCell(ref, st)
                datesAreSerial -> renderNumberCell(ref, st, BigDecimal(CellRef.toSerial(value, date1904)))
                else -> renderTextCell(ref, st, ISO_LIKE.format(value))
            }
        }

        columns[Col.SR_NO]?.let { c ->
            cells[c] = renderNumberCell(CellRef.of(c, rowNumber), styleByCol[c], BigDecimal(srNo))
        }
        putDate(Col.DATE, row.date)
        putText(Col.GST_NO, row.gstNo)
        putText(Col.INVOICE_NO, row.invoiceNo)
        putText(Col.NAME, row.name)
        putText(Col.QTY, row.qty)
        putNumber(Col.RATE_PCT, row.ratePct)
        putText(Col.HSN, row.hsn)
        putNumber(Col.TAXABLE, row.taxable)
        putNumber(Col.CGST, row.cgst)
        putNumber(Col.SGST, row.sgst)
        putNumber(Col.IGST, row.igst)

        // Bug #2, kept fixed: a SUM across the tax columns is only meaningful when the row
        // actually has a taxable value. Bank transactions have no tax breakdown, and summing
        // their blank cells used to write a zero into TOTAL GRAND.
        val grandCol = columns[Col.TOTAL_GRAND]
        if (grandCol != null) {
            val ref = CellRef.of(grandCol, rowNumber)
            val st = styleByCol[grandCol]
            if (row.useSumFormula) {
                val refs = Col.SUM_RANGE.mapNotNull { columns[it] }.sorted()
                if (refs.isNotEmpty()) {
                    val cached = listOf(row.taxable, row.cgst, row.sgst, row.igst)
                        .fold(BigDecimal.ZERO) { acc, v -> acc + (v ?: BigDecimal.ZERO) }
                    cells[grandCol] = renderFormulaCell(ref, st, sumExpr(refs, rowNumber), cached)
                    usedFormula = true
                } else {
                    cells[grandCol] = renderNumberCell(ref, st, row.totalGrand)
                }
            } else {
                cells[grandCol] = renderNumberCell(ref, st, row.totalGrand)
            }
        }

        putDate(Col.PAID_DATE, row.paidDate)
        putText(Col.PAYMENT_METHOD, row.paymentMethod.label.ifEmpty { null })
        putText(Col.CATEGORY, row.category?.label)
        putText(Col.GST_TYPE, row.gstType?.name)

        val xml = buildString {
            append("<row r=\"$rowNumber\">")
            cells.values.forEach(::append)
            append("</row>")
        }
        return RenderedRow(xml, usedFormula)
    }

    /** Contiguous tax columns get a range; a gap forces an explicit argument list. */
    private fun sumExpr(cols: List<Int>, rowNumber: Int): String {
        val contiguous = cols.zipWithNext().all { (a, b) -> b == a + 1 }
        return if (contiguous && cols.size > 1) {
            "SUM(${CellRef.of(cols.first(), rowNumber)}:${CellRef.of(cols.last(), rowNumber)})"
        } else {
            "SUM(${cols.joinToString(",") { CellRef.of(it, rowNumber) }})"
        }
    }

    private fun styleAttr(style: Int?): String = if (style != null) " s=\"$style\"" else ""

    private fun renderBlankCell(ref: String, style: Int?): String =
        """<c r="$ref"${styleAttr(style)}/>"""

    private fun renderTextCell(ref: String, style: Int?, value: String?): String {
        if (value.isNullOrEmpty()) return renderBlankCell(ref, style)
        // Inline strings keep xl/sharedStrings.xml — and every existing cell's index into
        // it — completely untouched.
        val preserve = if (value != value.trim()) """ xml:space="preserve"""" else ""
        return """<c r="$ref"${styleAttr(style)} t="inlineStr"><is><t$preserve>${value.xmlEscape()}</t></is></c>"""
    }

    private fun renderNumberCell(ref: String, style: Int?, value: BigDecimal?): String {
        if (value == null) return renderBlankCell(ref, style)
        return """<c r="$ref"${styleAttr(style)}><v>${plain(value)}</v></c>"""
    }

    private fun renderFormulaCell(ref: String, style: Int?, formula: String, cached: BigDecimal): String =
        """<c r="$ref"${styleAttr(style)}><f>${formula.xmlEscape()}</f><v>${plain(cached)}</v></c>"""

    /**
     * Plain decimal notation — Excel rejects the scientific form BigDecimal's toString may
     * produce for some values.
     *
     * The computed scale is written verbatim rather than normalised: 450.00 stays "450.00".
     * Excel treats that as the same number either way and formats it from the cell style,
     * but keeping the scale means the raw value still reads as money to anything that
     * inspects the file directly.
     */
    private fun plain(value: BigDecimal): String = value.toPlainString()

    companion object {
        private const val HEADER_SEARCH_ROWS = 10
        private const val MIN_HEADER_MATCHES = 3
        private val ISO_LIKE = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")

        fun open(bytes: ByteArray): XlsxAppender = XlsxAppender(XlsxPackage.read(bytes))
    }
}
