package com.gohil.bookkeeper.core.xlsx

/**
 * Maps worksheet tab names to the zip parts that hold them.
 *
 * This indirection is what makes the project's awkward tab-naming quirk a non-issue: the
 * purchase workbook names its tabs `Jun` while the sales workbook names them `June`. Both
 * are looked up by their real name in xl/workbook.xml, so neither has to be guessed at.
 */
internal class WorkbookIndex(
    private val pkg: XlsxPackage,
    val sheets: List<SheetEntry>,
    /** Set when the workbook uses the 1904 date system (rare; classic Mac Excel default). */
    val date1904: Boolean,
) {

    data class SheetEntry(
        val name: String,
        val sheetId: Int,
        val relId: String,
        /** Full zip path, e.g. "xl/worksheets/sheet3.xml". */
        val path: String,
    )

    fun findSheet(name: String): SheetEntry? =
        sheets.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun sheetNames(): List<String> = sheets.map { it.name }

    /**
     * Creates a new tab by cloning [templateName]'s structure — headers, styles, column
     * widths — with all data rows removed.
     *
     * This is the most invasive operation in the codebase: it registers a new part in three
     * separate places, and getting any one of them wrong makes Excel refuse the file. The
     * caller is responsible for taking a backup first.
     *
     * @return the entry for the newly created sheet.
     */
    fun cloneSheet(templateName: String, newName: String, headerRowNumber: Int): SheetEntry {
        require(findSheet(newName) == null) { "Sheet '$newName' already exists." }
        val template = findSheet(templateName)
            ?: throw XlsxException("Cannot create tab '$newName': template tab '$templateName' not found.")
        val templateXml = pkg.text(template.path)
            ?: throw XlsxException("Template tab '$templateName' has no worksheet part.")

        val sheet = SheetXml(templateXml)
        sheet.stripToHeader(headerRowNumber)

        val newPath = nextFreeSheetPath()
        val newSheetId = (sheets.maxOfOrNull { it.sheetId } ?: 0) + 1
        val newRelId = nextFreeRelId()

        pkg.putText(newPath, sheet.xml)
        registerRelationship(newRelId, newPath)
        registerContentType(newPath)
        registerInWorkbook(newName, newSheetId, newRelId)

        return SheetEntry(newName, newSheetId, newRelId, newPath)
    }

    private fun nextFreeSheetPath(): String {
        var n = 1
        while (pkg.contains("xl/worksheets/sheet$n.xml")) n++
        return "xl/worksheets/sheet$n.xml"
    }

    private fun nextFreeRelId(): String {
        val rels = pkg.text(RELS_PATH) ?: throw XlsxException("Workbook relationships part is missing.")
        val used = Regex("""Id="rId(\d+)"""").findAll(rels).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        var n = 1
        while (n in used) n++
        return "rId$n"
    }

    private fun registerRelationship(relId: String, path: String) {
        val rels = pkg.text(RELS_PATH) ?: throw XlsxException("Workbook relationships part is missing.")
        val target = path.removePrefix("xl/")
        val entry = """<Relationship Id="$relId" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """ +
            """Target="$target"/>"""
        val close = rels.lastIndexOf("</Relationships>")
        if (close < 0) throw XlsxException("Workbook relationships part is malformed.")
        pkg.putText(RELS_PATH, rels.substring(0, close) + entry + rels.substring(close))
    }

    private fun registerContentType(path: String) {
        val ct = pkg.text(CONTENT_TYPES) ?: throw XlsxException("[Content_Types].xml is missing.")
        val entry = """<Override PartName="/$path" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        val close = ct.lastIndexOf("</Types>")
        if (close < 0) throw XlsxException("[Content_Types].xml is malformed.")
        pkg.putText(CONTENT_TYPES, ct.substring(0, close) + entry + ct.substring(close))
    }

    private fun registerInWorkbook(name: String, sheetId: Int, relId: String) {
        val wb = pkg.text(WORKBOOK) ?: throw XlsxException("xl/workbook.xml is missing.")
        val sheetsEl = XmlScan.find(wb, "sheets")
            ?: throw XlsxException("xl/workbook.xml has no <sheets> element.")
        val entry = """<sheet name="${name.xmlEscape()}" sheetId="$sheetId" r:id="$relId"/>"""
        val updated = if (sheetsEl.selfClosing) {
            wb.substring(0, sheetsEl.start) + "<sheets>$entry</sheets>" + wb.substring(sheetsEl.end)
        } else {
            wb.substring(0, sheetsEl.innerEnd) + entry + wb.substring(sheetsEl.innerEnd)
        }
        pkg.putText(WORKBOOK, updated)
    }

    companion object {
        private const val WORKBOOK = "xl/workbook.xml"
        private const val RELS_PATH = "xl/_rels/workbook.xml.rels"
        private const val CONTENT_TYPES = "[Content_Types].xml"

        fun from(pkg: XlsxPackage): WorkbookIndex {
            val wb = pkg.text(WORKBOOK) ?: throw XlsxException("xl/workbook.xml is missing.")
            val relsXml = pkg.text(RELS_PATH) ?: throw XlsxException("Workbook relationships part is missing.")

            val relTargets = HashMap<String, String>()
            for (rel in XmlScan.findAll(relsXml, "Relationship")) {
                val id = XmlScan.attr(rel.attrs, "Id") ?: continue
                val target = XmlScan.attr(rel.attrs, "Target") ?: continue
                relTargets[id] = normalisePath(target)
            }

            val sheets = ArrayList<SheetEntry>()
            val sheetsEl = XmlScan.find(wb, "sheets")
            if (sheetsEl != null && !sheetsEl.selfClosing) {
                for (s in XmlScan.findAll(wb, "sheet", sheetsEl.innerStart, sheetsEl.innerEnd)) {
                    val name = XmlScan.attr(s.attrs, "name") ?: continue
                    val relId = XmlScan.attr(s.attrs, "r:id")
                        ?: XmlScan.attr(s.attrs, "id")
                        ?: continue
                    val path = relTargets[relId] ?: continue
                    sheets.add(
                        SheetEntry(
                            name = name,
                            sheetId = XmlScan.attr(s.attrs, "sheetId")?.toIntOrNull() ?: (sheets.size + 1),
                            relId = relId,
                            path = path,
                        ),
                    )
                }
            }
            if (sheets.isEmpty()) throw XlsxException("Workbook contains no worksheets.")

            val date1904 = XmlScan.find(wb, "workbookPr")
                ?.let { XmlScan.attr(it.attrs, "date1904") }
                ?.let { it == "1" || it.equals("true", ignoreCase = true) }
                ?: false

            return WorkbookIndex(pkg, sheets, date1904)
        }

        /** Relationship targets are relative to xl/, and may be written absolute. */
        private fun normalisePath(target: String): String {
            val t = target.removePrefix("/")
            return when {
                target.startsWith("/") -> t
                t.startsWith("xl/") -> t
                else -> "xl/$t"
            }
        }
    }
}
