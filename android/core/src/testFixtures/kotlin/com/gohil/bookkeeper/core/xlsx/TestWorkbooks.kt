package com.gohil.bookkeeper.core.xlsx

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds realistic .xlsx fixtures in memory.
 *
 * These mirror the shape of the real registers: a shared string table, a styles part with
 * several distinct formats, more than one month tab, and the exact thirteen-column header
 * the accountant's workbooks use.
 */
object TestWorkbooks {

    val HEADERS = listOf(
        "SR NO", "DATE", "GST NO", "INVOICE NO", "NAME", "QTY", "RATE %",
        "HSN", "TAXABLE", "CGST", "SGST", "IGST", "TOTAL GRAND",
    )

    data class DataRow(
        val srNo: Int,
        val dateSerial: Long,
        val gstNo: String,
        val invoiceNo: String,
        val name: String,
        val taxable: String,
        val cgst: String,
        val sgst: String,
        val total: String,
        /**
         * What the RATE % column already holds. Defaults to the purchase register's
         * convention of a whole percentage; pass "0.18" for the sales register's, which
         * stores the fraction and shows it through a 0% number format.
         */
        val ratePct: String = "18",
    )

    /**
     * @param sheets tab name to its data rows, in workbook order.
     * @param extraHeaders columns already present beyond the standard thirteen.
     */
    fun build(
        sheets: List<Pair<String, List<DataRow>>>,
        extraHeaders: List<String> = emptyList(),
        includeCalcChain: Boolean = true,
    ): ByteArray {
        val allHeaders = HEADERS + extraHeaders
        // Shared strings: headers first, then every distinct text value in the data.
        val strings = LinkedHashMap<String, Int>()
        fun intern(s: String): Int = strings.getOrPut(s) { strings.size }
        allHeaders.forEach { intern(it) }
        sheets.forEach { (_, rows) ->
            rows.forEach { intern(it.gstNo); intern(it.invoiceNo); intern(it.name) }
        }

        val parts = LinkedHashMap<String, String>()

        parts["[Content_Types].xml"] = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            append(
                """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""",
            )
            sheets.forEachIndexed { i, _ ->
                append(
                    """<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""",
                )
            }
            append(
                """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""",
            )
            append(
                """<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>""",
            )
            if (includeCalcChain) {
                append(
                    """<Override PartName="/xl/calcChain.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml"/>""",
                )
            }
            append("</Types>")
        }

        parts["_rels/.rels"] =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

        parts["xl/workbook.xml"] = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
            append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
            append("""<sheets>""")
            sheets.forEachIndexed { i, (name, _) ->
                append("""<sheet name="$name" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
            }
            append("""</sheets></workbook>""")
        }

        parts["xl/_rels/workbook.xml.rels"] = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            sheets.forEachIndexed { i, _ ->
                append(
                    """<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""",
                )
            }
            val base = sheets.size
            append(
                """<Relationship Id="rId${base + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""",
            )
            append(
                """<Relationship Id="rId${base + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>""",
            )
            if (includeCalcChain) {
                append(
                    """<Relationship Id="rId${base + 3}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/calcChain" Target="calcChain.xml"/>""",
                )
            }
            append("""</Relationships>""")
        }

        parts["xl/sharedStrings.xml"] = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append(
                """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${strings.size}" uniqueCount="${strings.size}">""",
            )
            strings.keys.forEach { append("<si><t>${it.xmlEscapeForTest()}</t></si>") }
            append("</sst>")
        }

        // Style ids 0..7. 2 is the date format, 6 is the currency format — the appender is
        // expected to reuse whatever the row above it used, whatever those ids mean.
        parts["xl/styles.xml"] =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="1"><numFmt numFmtId="164" formatCode="dd\-mm\-yyyy"/></numFmts>""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="8">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="2" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """</cellXfs></styleSheet>"""

        if (includeCalcChain) {
            parts["xl/calcChain.xml"] =
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<calcChain xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                """<c r="M2" i="1"/></calcChain>"""
        }

        sheets.forEachIndexed { i, (_, rows) ->
            parts["xl/worksheets/sheet${i + 1}.xml"] = sheetXml(allHeaders, rows, strings)
        }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, text) in parts) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sheetXml(
        headers: List<String>,
        rows: List<DataRow>,
        strings: Map<String, Int>,
    ): String = buildString {
        val lastCol = CellRef.columnName(headers.size - 1)
        val lastRow = rows.size + 1
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("""<dimension ref="A1:$lastCol$lastRow"/>""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("""<sheetFormatPr defaultRowHeight="15"/>""")
        append("""<cols><col min="5" max="5" width="32" customWidth="1"/></cols>""")
        append("<sheetData>")

        append("""<row r="1" spans="1:${headers.size}">""")
        headers.forEachIndexed { c, h ->
            append("""<c r="${CellRef.columnName(c)}1" s="1" t="s"><v>${strings[h]}</v></c>""")
        }
        append("</row>")

        rows.forEachIndexed { idx, row ->
            val r = idx + 2
            append("""<row r="$r" spans="1:${headers.size}">""")
            append("""<c r="A$r" s="0"><v>${row.srNo}</v></c>""")
            append("""<c r="B$r" s="2"><v>${row.dateSerial}</v></c>""")
            append("""<c r="C$r" s="0" t="s"><v>${strings[row.gstNo]}</v></c>""")
            append("""<c r="D$r" s="0" t="s"><v>${strings[row.invoiceNo]}</v></c>""")
            append("""<c r="E$r" s="0" t="s"><v>${strings[row.name]}</v></c>""")
            append("""<c r="F$r" s="0"><v>1</v></c>""")
            append("""<c r="G$r" s="0"><v>${row.ratePct}</v></c>""")
            append("""<c r="H$r" s="0"/>""")
            append("""<c r="I$r" s="6"><v>${row.taxable}</v></c>""")
            append("""<c r="J$r" s="6"><v>${row.cgst}</v></c>""")
            append("""<c r="K$r" s="6"><v>${row.sgst}</v></c>""")
            append("""<c r="L$r" s="6"/>""")
            append("""<c r="M$r" s="6"><f>SUM(I$r:L$r)</f><v>${row.total}</v></c>""")
            append("</row>")
        }

        append("</sheetData>")
        append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
        append("</worksheet>")
    }

    private fun String.xmlEscapeForTest(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun sampleRows(count: Int, startSr: Int = 1): List<DataRow> = (0 until count).map { i ->
        DataRow(
            srNo = startSr + i,
            dateSerial = 45809L + i,
            gstNo = "24AABCU9603R1Z${i % 10}",
            invoiceNo = "INV-${1000 + i}",
            name = "Vendor ${('A' + i % 26)} & Co",
            taxable = "1000.00",
            cgst = "90.00",
            sgst = "90.00",
            total = "1180.00",
        )
    }
}
