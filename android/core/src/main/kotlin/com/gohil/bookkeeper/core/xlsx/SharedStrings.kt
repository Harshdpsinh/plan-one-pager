package com.gohil.bookkeeper.core.xlsx

/**
 * Read-only view of xl/sharedStrings.xml.
 *
 * The appender never writes back to this part. New text cells are emitted as inline strings
 * instead, which keeps the shared string table — and every existing cell's index into it —
 * exactly as Excel left it. Rewriting the table is the single easiest way to corrupt an
 * otherwise healthy workbook, so the code simply never does it.
 */
internal class SharedStrings(private val values: List<String>) {

    operator fun get(index: Int): String? = values.getOrNull(index)

    val size: Int get() = values.size

    companion object {
        fun from(pkg: XlsxPackage): SharedStrings {
            val xml = pkg.text("xl/sharedStrings.xml") ?: return SharedStrings(emptyList())
            val out = ArrayList<String>()
            for (si in XmlScan.findAll(xml, "si")) {
                // A shared string may be split across formatting runs (<r><t>..</t></r>);
                // the cell's value is all of their text concatenated.
                val sb = StringBuilder()
                for (t in XmlScan.findAll(xml, "t", si.innerStart, si.innerEnd)) {
                    sb.append(t.inner(xml).xmlUnescape())
                }
                out.add(sb.toString())
            }
            return SharedStrings(out)
        }
    }
}
