package com.gohil.bookkeeper.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.gohil.bookkeeper.core.xlsx.XlsxAppender
import com.gohil.bookkeeper.core.xlsx.XlsxException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads and rewrites the user's workbooks through the Storage Access Framework.
 *
 * The write path is deliberately paranoid. These are the accountant's registers and there is
 * no undo: the new workbook is built in memory, verified by reopening it, and a timestamped
 * backup of the original is written before a single byte of the original is touched. Any
 * failure before the final write leaves the original exactly as it was.
 */
class WorkbookStore(private val context: Context) {

    data class WriteOutcome(
        val result: XlsxAppender.Result,
        val backupName: String?,
    )

    fun read(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw XlsxException("Could not open the workbook. Re-pick the file and try again.")

    fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(context, uri)?.name ?: "workbook.xlsx"

    /**
     * Appends rows and writes the workbook back.
     *
     * @param backupFolder where to put the safety copy. When null — the user picked a file
     *   without granting access to its folder — the write still proceeds, but the caller is
     *   told no backup exists so it can say so.
     */
    fun append(
        uri: Uri,
        request: XlsxAppender.Request,
        backupFolder: Uri?,
    ): WriteOutcome {
        val original = read(uri)

        val appender = XlsxAppender.open(original)
        val result = appender.append(request)
        val updated = appender.toByteArray()

        // Reopen what we are about to write. A workbook that cannot be parsed back must
        // never reach the user's file, and this catches it while the original is still intact.
        verify(updated, request.sheetName, expectedNewRows = request.rows.size)

        val backupName = backupFolder?.let { writeBackup(it, uri, original) }

        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(updated)
            out.flush()
        } ?: throw XlsxException(
            "Could not write to the workbook. It may be open in another app, or stored " +
                "somewhere this app cannot modify.",
        )

        return WriteOutcome(result, backupName)
    }

    private fun verify(bytes: ByteArray, sheetName: String, expectedNewRows: Int) {
        val reopened = try {
            XlsxAppender.open(bytes)
        } catch (e: Exception) {
            throw XlsxException("Refusing to save: the updated workbook did not read back cleanly.", e)
        }
        if (sheetName !in reopened.sheetNames()) {
            throw XlsxException("Refusing to save: tab '$sheetName' is missing from the updated workbook.")
        }
        if (expectedNewRows > 0 && bytes.isEmpty()) {
            throw XlsxException("Refusing to save: the updated workbook is empty.")
        }
    }

    private fun writeBackup(folder: Uri, source: Uri, bytes: ByteArray): String? {
        val dir = DocumentFile.fromTreeUri(context, folder) ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = displayName(source).substringBeforeLast('.')
        val name = "$base.backup-$stamp.xlsx"
        val file = dir.createFile(MIME_XLSX, name) ?: return null
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) } ?: return null
        return name
    }

    companion object {
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
