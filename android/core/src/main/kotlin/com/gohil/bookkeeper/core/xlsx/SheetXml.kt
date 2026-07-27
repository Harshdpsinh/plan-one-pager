package com.gohil.bookkeeper.core.xlsx

/**
 * An editable view over one worksheet part.
 *
 * Edits are splices into the original character stream, so anything outside the touched
 * range — column widths, merges, panes, autofilters, conditional formats — is preserved
 * exactly as written.
 */
internal class SheetXml(xmlText: String) {

    var xml: String = xmlText
        private set

    data class Cell(
        val ref: String,
        val column: Int,
        val styleIndex: Int?,
        val type: String?,
        val element: XmlScan.Element,
    )

    data class Row(
        val number: Int,
        val element: XmlScan.Element,
        val cells: List<Cell>,
    ) {
        fun cellAt(column: Int): Cell? = cells.firstOrNull { it.column == column }
    }

    /** The `<sheetData>` element, normalising the self-closing form so rows can be appended. */
    private fun sheetDataElement(): XmlScan.Element {
        var el = XmlScan.find(xml, "sheetData")
            ?: throw XlsxException("Worksheet has no <sheetData> element — file may be corrupt.")
        if (el.selfClosing) {
            xml = xml.substring(0, el.start) + "<sheetData></sheetData>" + xml.substring(el.end)
            el = XmlScan.find(xml, "sheetData")!!
        }
        return el
    }

    fun rows(): List<Row> {
        val sd = sheetDataElement()
        return XmlScan.findAll(xml, "row", sd.innerStart, sd.innerEnd).mapNotNull { rowEl ->
            val num = XmlScan.attr(rowEl.attrs, "r")?.toIntOrNull() ?: return@mapNotNull null
            val cells = XmlScan.findAll(xml, "c", rowEl.innerStart, rowEl.innerEnd).mapNotNull { c ->
                val ref = XmlScan.attr(c.attrs, "r") ?: return@mapNotNull null
                val col = CellRef.columnOf(ref) ?: return@mapNotNull null
                Cell(
                    ref = ref,
                    column = col,
                    styleIndex = XmlScan.attr(c.attrs, "s")?.toIntOrNull(),
                    type = XmlScan.attr(c.attrs, "t"),
                    element = c,
                )
            }
            Row(num, rowEl, cells)
        }
    }

    fun lastRowNumber(): Int = rows().maxOfOrNull { it.number } ?: 0

    /** Reads a cell's display text, resolving shared and inline strings. */
    fun cellText(cell: Cell, shared: SharedStrings): String? {
        val inner = cell.element.inner(xml)
        return when (cell.type) {
            "s" -> {
                val v = XmlScan.find(inner, "v")?.inner(inner)?.trim()?.toIntOrNull() ?: return null
                shared[v]
            }
            "inlineStr" -> {
                val isEl = XmlScan.find(inner, "is") ?: return null
                val sb = StringBuilder()
                for (t in XmlScan.findAll(inner, "t", isEl.innerStart, isEl.innerEnd)) {
                    sb.append(t.inner(inner).xmlUnescape())
                }
                sb.toString()
            }
            else -> XmlScan.find(inner, "v")?.inner(inner)?.xmlUnescape()?.trim()
        }
    }

    /** Appends pre-rendered `<row>` XML immediately before `</sheetData>`. */
    fun appendRowsXml(rowsXml: String) {
        if (rowsXml.isEmpty()) return
        val sd = sheetDataElement()
        xml = xml.substring(0, sd.innerEnd) + rowsXml + xml.substring(sd.innerEnd)
    }

    /**
     * Adds cells to the end of an existing row.
     *
     * Only ever used to place new header cells to the right of the existing headers, so
     * appending keeps cells in ascending column order as the format requires.
     */
    fun appendCellsToRow(rowNumber: Int, cellsXml: String) {
        if (cellsXml.isEmpty()) return
        val row = rows().firstOrNull { it.number == rowNumber }
            ?: throw XlsxException("Row $rowNumber not found while adding header cells.")
        if (row.element.selfClosing) {
            // `<row r="1"/>` — an empty row that must be reopened before cells can go in.
            val reopened = "<row${row.element.attrs}>$cellsXml</row>"
            xml = xml.substring(0, row.element.start) + reopened + xml.substring(row.element.end)
        } else {
            xml = xml.substring(0, row.element.innerEnd) + cellsXml + xml.substring(row.element.innerEnd)
        }
        clearRowSpans(rowNumber)
    }

    /**
     * Drops a row's `spans` hint after its width changes.
     *
     * `spans` is an optional rendering optimisation. A stale one is inconsistent with the
     * row's real contents, and removing it is safer than recomputing it.
     */
    private fun clearRowSpans(rowNumber: Int) {
        val row = rows().firstOrNull { it.number == rowNumber } ?: return
        if (XmlScan.attr(row.element.attrs, "spans") == null) return
        val cleaned = XmlScan.withoutAttr(row.element.attrs, "spans")
        val head = if (row.element.selfClosing) "<row$cleaned/>" else "<row$cleaned>"
        val headEnd = if (row.element.selfClosing) row.element.end else row.element.innerStart
        xml = xml.substring(0, row.element.start) + head + xml.substring(headEnd)
    }

    /** Rewrites `<dimension>` to span the sheet's real extent. Added if absent. */
    fun updateDimension() {
        val rows = rows()
        if (rows.isEmpty()) return
        val maxRow = rows.maxOf { it.number }
        val maxCol = rows.flatMap { it.cells }.maxOfOrNull { it.column } ?: 0
        val ref = "A1:${CellRef.of(maxCol, maxRow)}"
        val existing = XmlScan.find(xml, "dimension")
        if (existing != null) {
            xml = xml.substring(0, existing.start) +
                """<dimension ref="$ref"/>""" +
                xml.substring(existing.end)
        } else {
            val sd = XmlScan.find(xml, "sheetData") ?: return
            xml = xml.substring(0, sd.start) +
                """<dimension ref="$ref"/>""" +
                xml.substring(sd.start)
        }
    }

    /**
     * Strips every data row, keeping the header, and removes parts that depend on
     * relationships this sheet will not carry after being cloned into a new tab.
     */
    fun stripToHeader(headerRowNumber: Int) {
        val doomed = rows().filter { it.number != headerRowNumber }.sortedByDescending { it.element.start }
        for (row in doomed) {
            xml = xml.substring(0, row.element.start) + xml.substring(row.element.end)
        }
        // These reference r:id values in the source sheet's .rels, which is not cloned.
        // Leaving them behind produces the "unreadable content" repair prompt on open.
        for (tag in listOf("hyperlinks", "drawing", "legacyDrawing", "tableParts", "picture", "oleObjects")) {
            while (true) {
                val el = XmlScan.find(xml, tag) ?: break
                xml = xml.substring(0, el.start) + xml.substring(el.end)
            }
        }
        updateDimension()
    }
}
