package com.gohil.bookkeeper.core.xlsx

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A fixture shaped like the register the app is actually pointed at, rather than like the
 * tidy one the first tests were written against.
 *
 * The differences all turned out to matter:
 *  - three merged banner rows above the data, so the header is on row 4, not row 1
 *  - a `<mergeCells>` element
 *  - no `<dimension>`, which some producers omit
 *  - the sheet parts numbered so the target is not sheet1
 *  - tab names differing only by case ("Jun" and "june")
 */
object RealisticWorkbook {

    val HEADERS = listOf(
        "SR NO", "DATE", "GST NO", "INVOICE NO", "NAME", "QTY", "RATE %",
        "HSN", "TAXABLE", "CGST", "SGST", "IGST", "TOTAL GRAND",
    )

    /** @param omitDimension leave out `<dimension>` entirely, as some writers do. */
    fun build(
        tabs: List<String> = listOf("April", "May", "Jun", "july", "june"),
        headerRow: Int = 4,
        omitDimension: Boolean = true,
        dataRows: Int = 0,
    ): ByteArray {
        val parts = LinkedHashMap<String, String>()

        parts["[Content_Types].xml"] = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            tabs.indices.forEach {
                append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
            }
            append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
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
            append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            tabs.forEachIndexed { i, name ->
                append("""<sheet name="$name" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
            }
            append("""</sheets></workbook>""")
        }

        parts["xl/_rels/workbook.xml.rels"] = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            tabs.indices.forEach {
                append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
            }
            append("""<Relationship Id="rId${tabs.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            append("""</Relationships>""")
        }

        parts["xl/styles.xml"] =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="1"><numFmt numFmtId="164" formatCode="dd\-mm\-yyyy"/></numFmts>""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="14"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="2"><fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="8">""" +
            (0 until 8).joinToString("") {
                """<xf numFmtId="${if (it == 2) 164 else 0}" fontId="0" fillId="0" borderId="0" xfId="0"/>"""
            } +
            """</cellXfs></styleSheet>"""

        tabs.forEachIndexed { i, name ->
            parts["xl/worksheets/sheet${i + 1}.xml"] =
                sheetXml(name, headerRow, omitDimension, dataRows)
        }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((n, text) in parts) {
                zos.putNextEntry(ZipEntry(n))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sheetXml(
        tabName: String,
        headerRow: Int,
        omitDimension: Boolean,
        dataRows: Int,
    ): String = buildString {
        val lastCol = CellRef.columnName(HEADERS.size - 1)
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        if (!omitDimension) append("""<dimension ref="A1:$lastCol${headerRow + dataRows}"/>""")
        append("""<sheetViews><sheetView tabSelected="1" workbookViewId="0"><selection activeCell="A1" sqref="A1"/></sheetView></sheetViews>""")
        append("""<sheetFormatPr defaultRowHeight="15"/>""")
        append("""<cols><col min="5" max="5" width="32" customWidth="1"/></cols>""")
        append("<sheetData>")

        // Merged banner rows, exactly the shape that puts the header below row 1.
        listOf("Name", "Purchase Register", tabName).forEachIndexed { idx, banner ->
            val r = idx + 1
            append("""<row r="$r" spans="1:${HEADERS.size}" ht="21" customHeight="1">""")
            append("""<c r="A$r" s="1" t="inlineStr"><is><t>$banner</t></is></c>""")
            append("</row>")
        }

        append("""<row r="$headerRow" spans="1:${HEADERS.size}">""")
        HEADERS.forEachIndexed { c, h ->
            append("""<c r="${CellRef.columnName(c)}$headerRow" s="1" t="inlineStr"><is><t>$h</t></is></c>""")
        }
        append("</row>")

        for (d in 1..dataRows) {
            val r = headerRow + d
            append("""<row r="$r" spans="1:${HEADERS.size}">""")
            append("""<c r="A$r" s="0"><v>$d</v></c>""")
            append("""<c r="B$r" s="2"><v>${45809 + d}</v></c>""")
            append("""<c r="E$r" s="0" t="inlineStr"><is><t>Existing Vendor $d</t></is></c>""")
            append("""<c r="I$r" s="6"><v>1000.00</v></c>""")
            append("""<c r="M$r" s="6"><v>1180.00</v></c>""")
            append("</row>")
        }

        append("</sheetData>")
        // Merges over the banner rows, as a real register has.
        append("""<mergeCells count="3">""")
        append("""<mergeCell ref="A1:${lastCol}1"/><mergeCell ref="A2:${lastCol}2"/><mergeCell ref="A3:${lastCol}3"/>""")
        append("""</mergeCells>""")
        append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
        append("</worksheet>")
    }
}
