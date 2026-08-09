package com.gohil.bookkeeper.core.xlsx

import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks that a workbook this code produced is one Excel will actually open.
 *
 * This exists because of a real failure: a sheet was written with its `<dimension>` out of
 * schema order, Excel refused the part, and the user was shown "we found a problem with some
 * content" and lost the appended rows to Excel's repair. Nothing in the pipeline noticed —
 * the output re-read cleanly through this project's own reader, and openpyxl accepted it too,
 * because both are more forgiving about element order than Excel is.
 *
 * So the check is deliberately stricter than "can I read it back". Refusing to save is a
 * recoverable annoyance; handing over a register that Excel silently repairs is not.
 */
object XlsxValidator {

    /**
     * Elements that may appear before `<sheetData>` in a worksheet, in the order the schema
     * requires. Anything out of sequence makes Excel reject the part.
     */
    private val WORKSHEET_SEQUENCE = listOf(
        "sheetPr",
        "dimension",
        "sheetViews",
        "sheetFormatPr",
        "cols",
        "sheetData",
        "sheetCalcPr",
        "sheetProtection",
        "protectedRanges",
        "scenarios",
        "autoFilter",
        "sortState",
        "dataConsolidate",
        "customSheetViews",
        "mergeCells",
        "phoneticPr",
        "conditionalFormatting",
        "dataValidations",
        "hyperlinks",
        "printOptions",
        "pageMargins",
        "pageSetup",
        "headerFooter",
    )

    /** @throws XlsxException with a description of the first problem found. */
    fun validate(pkg: XlsxPackage) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

        for (name in pkg.entryNames) {
            if (!name.endsWith(".xml") && !name.endsWith(".rels")) continue
            val bytes = pkg[name] ?: continue
            try {
                factory.newDocumentBuilder().parse(bytes.inputStream())
            } catch (e: Exception) {
                throw XlsxException("Refusing to save: '$name' is not valid XML (${e.message}).", e)
            }
        }

        for (name in pkg.entryNames) {
            if (!name.startsWith("xl/worksheets/") || !name.endsWith(".xml")) continue
            val xml = pkg.text(name) ?: continue
            checkWorksheetOrder(name, xml)
            checkNoDuplicateCellRefs(name, xml)
        }
    }

    fun validate(bytes: ByteArray) = validate(XlsxPackage.read(bytes))

    private fun checkWorksheetOrder(part: String, xml: String) {
        val seen = WORKSHEET_SEQUENCE.mapNotNull { tag ->
            Regex("""<$tag[\s/>]""").find(xml)?.range?.first?.let { tag to it }
        }
        val expected = seen.map { it.first }
        val actual = seen.sortedBy { it.second }.map { it.first }
        if (expected != actual) {
            throw XlsxException(
                "Refusing to save: worksheet '$part' has its elements out of the order Excel " +
                    "requires (found $actual). Excel would report this only as a content error.",
            )
        }
    }

    /**
     * Two cells with the same reference in one row, or rows whose cells run out of ascending
     * column order — both are things Excel treats as a damaged part.
     */
    private fun checkNoDuplicateCellRefs(part: String, xml: String) {
        val sheet = SheetXml(xml)
        for (row in sheet.rows()) {
            val refs = row.cells.map { it.ref }
            if (refs.size != refs.distinct().size) {
                val dupes = refs.groupBy { it }.filterValues { it.size > 1 }.keys
                throw XlsxException(
                    "Refusing to save: worksheet '$part' row ${row.number} has duplicate " +
                        "cell(s) $dupes.",
                )
            }
            val columns = row.cells.map { it.column }
            if (columns != columns.sorted()) {
                throw XlsxException(
                    "Refusing to save: worksheet '$part' row ${row.number} has cells out of " +
                        "column order.",
                )
            }
        }
    }
}
