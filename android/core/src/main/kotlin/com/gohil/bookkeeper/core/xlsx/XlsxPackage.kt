package com.gohil.bookkeeper.core.xlsx

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An .xlsx file held as its raw zip entries.
 *
 * This is the foundation of the whole "never damage the user's workbook" guarantee. Every
 * part the app does not explicitly rewrite is carried across byte-for-byte, in its original
 * order, so charts, conditional formatting, frozen panes, print ranges, other months' tabs
 * and anything else the file happens to contain survive untouched — including features this
 * code knows nothing about.
 *
 * A full spreadsheet library would instead parse the workbook into its own object model and
 * re-serialise everything, which silently drops whatever that library does not model.
 */
class XlsxPackage private constructor(
    private val entries: LinkedHashMap<String, ByteArray>,
) {

    val entryNames: List<String> get() = entries.keys.toList()

    operator fun get(name: String): ByteArray? = entries[name]

    fun contains(name: String): Boolean = entries.containsKey(name)

    fun text(name: String): String? = entries[name]?.toString(Charsets.UTF_8)

    /** Replaces a part, keeping its position in the zip. Adds it at the end if new. */
    fun put(name: String, bytes: ByteArray) {
        entries[name] = bytes
    }

    fun putText(name: String, xml: String) = put(name, xml.toByteArray(Charsets.UTF_8))

    fun remove(name: String): Boolean = entries.remove(name) != null

    /**
     * Drops the formula dependency cache. Excel rebuilds it on open; a stale one after new
     * formulas are appended is a genuine "unreadable content" trigger.
     */
    fun invalidateCalcChain() {
        if (remove("xl/calcChain.xml")) {
            text("[Content_Types].xml")?.let { ct ->
                putText(
                    "[Content_Types].xml",
                    ct.replace(
                        Regex("""<Override[^>]*PartName="/xl/calcChain\.xml"[^>]*/>"""),
                        "",
                    ),
                )
            }
            text("xl/_rels/workbook.xml.rels")?.let { rels ->
                putText(
                    "xl/_rels/workbook.xml.rels",
                    rels.replace(
                        Regex("""<Relationship[^>]*Target="calcChain\.xml"[^>]*/>"""),
                        "",
                    ),
                )
            }
        }
    }

    fun write(out: OutputStream) {
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                // Deliberately re-deflating rather than copying raw: entry sizes change and
                // a mixed STORED/DEFLATED copy needs exact CRC bookkeeping to stay valid.
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    fun toByteArray(): ByteArray = ByteArrayOutputStream().also { write(it) }.toByteArray()

    companion object {
        /** Reads every zip entry into memory. Bookkeeping workbooks are well under a megabyte. */
        fun read(input: InputStream): XlsxPackage {
            val map = LinkedHashMap<String, ByteArray>()
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry: ZipEntry = zis.nextEntry ?: break
                    if (!entry.isDirectory) {
                        map[entry.name] = zis.readBytes()
                    }
                    zis.closeEntry()
                }
            }
            if (map.isEmpty()) throw XlsxException("File is not a readable .xlsx (no zip entries found).")
            if (!map.containsKey("xl/workbook.xml")) {
                throw XlsxException(
                    "File is not an .xlsx workbook (xl/workbook.xml is missing). " +
                        "Legacy .xls files are not supported — open it in Excel and save as .xlsx.",
                )
            }
            return XlsxPackage(map)
        }

        fun read(bytes: ByteArray): XlsxPackage = read(bytes.inputStream())
    }
}

class XlsxException(message: String, cause: Throwable? = null) : Exception(message, cause)
