package com.gohil.bookkeeper.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Gets text out of a document, whatever form it arrives in.
 *
 * Everything here is on-device. The order of attempts matters: embedded text is exact and
 * cheap, so OCR is only used when a page has none — which is what a phone photo or a scanned
 * bill looks like.
 */
class DocumentExtractor(
    private val context: Context,
    private val ocr: TextRecognizer = TextRecognizer(),
) {

    sealed interface Result {
        data class Text(val text: String, val usedOcr: Boolean) : Result
        /** The file is encrypted and the supplied password (if any) did not open it. */
        data object PasswordRequired : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun extract(uri: Uri, displayName: String, password: String? = null): Result = try {
        when {
            displayName.endsWith(".pdf", ignoreCase = true) -> extractPdf(uri, password)
            else -> extractImage(uri)
        }
    } catch (e: Exception) {
        Result.Failed(e.message ?: e::class.java.simpleName)
    }

    private suspend fun extractPdf(uri: Uri, password: String?): Result {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return Result.Failed("Could not open the file.")

        val document = try {
            PDDocument.load(bytes, password ?: "")
        } catch (e: Exception) {
            // PDFBox reports a wrong or missing password as a load failure; distinguishing it
            // lets the app prompt for a password instead of reporting the file as corrupt.
            return if (isPasswordProblem(e)) Result.PasswordRequired else {
                Result.Failed(e.message ?: "Could not read the PDF.")
            }
        }

        document.use { doc ->
            val embedded = runCatching {
                PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }.getOrDefault("")

            if (embedded.isTextual()) {
                return Result.Text(embedded, usedOcr = false)
            }

            // No embedded text: a scan. Render the pages and read them.
            return Result.Text(ocrPdfPages(doc, bytes, password), usedOcr = true)
        }
    }

    /**
     * Renders each page and runs recognition over it.
     *
     * Rendering goes through Android's own PdfRenderer, which is fast and hardware-backed,
     * rather than PDFBox's software renderer. PdfRenderer cannot open an encrypted file, so
     * an already-unlocked copy is written to app-private cache first and deleted immediately
     * afterwards — it holds decrypted financial data and must not outlive the extraction.
     */
    private suspend fun ocrPdfPages(doc: PDDocument, original: ByteArray, password: String?): String {
        val needsDecryptedCopy = doc.isEncrypted
        val temp = File.createTempFile("render", ".pdf", context.cacheDir)
        try {
            if (needsDecryptedCopy) {
                doc.isAllSecurityToBeRemoved = true
                doc.save(temp)
            } else {
                temp.writeBytes(original)
            }

            val sb = StringBuilder()
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in 0 until minOf(renderer.pageCount, MAX_PAGES)) {
                        renderer.openPage(i).use { page ->
                            val scale = RENDER_DPI / 72f
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            // White background: pages render with transparency, and a
                            // transparent background reads as black and defeats recognition.
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            sb.append(ocr.recognize(bitmap)).append('\n')
                            bitmap.recycle()
                        }
                    }
                }
            }
            return sb.toString()
        } finally {
            temp.delete()
        }
    }

    private suspend fun extractImage(uri: Uri): Result {
        val text = ocr.recognize(context, uri)
        return if (text.isBlank()) {
            Result.Failed("No text could be read from this image.")
        } else {
            Result.Text(text, usedOcr = true)
        }
    }

    /**
     * Distinguishes real text from the handful of stray characters a scanned page yields.
     *
     * A scan often carries a few glyphs from an embedded logo or a stamp, so a non-empty
     * result is not proof of a text layer.
     */
    private fun String.isTextual(): Boolean =
        count { it.isLetterOrDigit() } >= MIN_EMBEDDED_CHARS

    private fun isPasswordProblem(e: Exception): Boolean {
        val message = (e.message ?: "").lowercase()
        return "password" in message || "encrypt" in message || "decrypt" in message ||
            e::class.java.simpleName.contains("InvalidPassword", ignoreCase = true)
    }

    companion object {
        /** 200 DPI is the accuracy/speed sweet spot for text recognition on invoice scans. */
        private const val RENDER_DPI = 200f
        private const val MAX_PAGES = 40
        private const val MIN_EMBEDDED_CHARS = 120
    }
}
