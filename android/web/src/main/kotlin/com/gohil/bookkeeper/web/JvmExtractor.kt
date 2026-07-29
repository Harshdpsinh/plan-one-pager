package com.gohil.bookkeeper.web

import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Desktop counterpart of the Android app's document extractor.
 *
 * Same order of attempts: embedded PDF text first because it is exact, then recognition only
 * for pages that have none. The difference is the recogniser — there is no bundled on-device
 * model here, so it shells out to Tesseract if the machine has it and degrades to a review
 * item if not, rather than making Tesseract a hard requirement for text-based PDFs (which
 * are the overwhelming majority of bank statements and vendor invoices).
 */
class JvmExtractor(private val ocr: TesseractOcr = TesseractOcr()) {

    sealed interface Result {
        data class Text(val text: String, val usedOcr: Boolean) : Result

        /**
         * Encrypted, and nothing supplied opened it. Carries the underlying reason so the
         * user can tell "wrong password" from "this file's encryption is not supported",
         * which look identical from the outside but need completely different responses.
         */
        data class PasswordProblem(val triedCount: Int, val detail: String) : Result

        data class Failed(val reason: String) : Result
    }

    fun extract(bytes: ByteArray, fileName: String, passwords: List<String>): Result = try {
        if (fileName.endsWith(".pdf", ignoreCase = true)) {
            extractPdf(bytes, passwords)
        } else {
            extractImage(bytes, fileName)
        }
    } catch (e: Exception) {
        Result.Failed(e.message ?: e::class.java.simpleName)
    }

    private fun extractPdf(bytes: ByteArray, passwords: List<String>): Result {
        // Empty password first: most invoices are not encrypted, and it is also what an
        // encrypted file with a blank user password needs.
        val attempts = listOf("") + passwords.filter { it.isNotBlank() }
        var sawPasswordFailure = false
        var lastFailure: Exception? = null
        var document: PDDocument? = null

        for (candidate in attempts) {
            try {
                document = Loader.loadPDF(bytes, candidate)
                break
            } catch (e: Exception) {
                lastFailure = e
                if (isPasswordProblem(e)) sawPasswordFailure = true
                else return Result.Failed(e.message ?: "Could not read the PDF.")
            }
        }

        if (document == null) {
            // Report what actually went wrong. "None of your passwords worked" is the same
            // message whether the password was mistyped, the file uses an encryption this
            // cannot handle, or the PDF is damaged — and only the first of those is worth
            // the user retyping anything for.
            val detail = lastFailure?.let { "${it::class.java.simpleName}: ${it.message}" }
                ?: "no further detail"
            return if (sawPasswordFailure) {
                Result.PasswordProblem(
                    triedCount = attempts.size - 1,
                    detail = detail,
                )
            } else {
                Result.Failed("Could not read the PDF ($detail).")
            }
        }

        document.use { doc ->
            val embedded = runCatching {
                PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }.getOrDefault("")

            if (embedded.count { it.isLetterOrDigit() } >= MIN_EMBEDDED_CHARS) {
                return Result.Text(embedded, usedOcr = false)
            }

            if (!ocr.available) {
                return Result.Failed(
                    "This PDF is a scan with no text layer, and Tesseract OCR was not found " +
                        "on this machine. Install Tesseract and restart, or process this file " +
                        "on the phone app, which has recognition built in.",
                )
            }

            val sb = StringBuilder()
            val renderer = PDFRenderer(doc)
            for (page in 0 until minOf(doc.numberOfPages, MAX_PAGES)) {
                val image = renderer.renderImageWithDPI(page, RENDER_DPI)
                sb.append(ocr.recognize(image)).append('\n')
            }
            return Result.Text(sb.toString(), usedOcr = true)
        }
    }

    private fun extractImage(bytes: ByteArray, fileName: String): Result {
        if (!ocr.available) {
            return Result.Failed(
                "Reading a photo needs Tesseract OCR, which was not found on this machine. " +
                    "Install it and restart, or use the phone app for photographed bills.",
            )
        }
        val image = ImageIO.read(bytes.inputStream())
            ?: return Result.Failed("Could not decode the image '$fileName'.")
        val text = ocr.recognize(image)
        return if (text.isBlank()) Result.Failed("No text could be read from this image.")
        else Result.Text(text, usedOcr = true)
    }

    private fun isPasswordProblem(e: Exception): Boolean {
        val message = (e.message ?: "").lowercase()
        return "password" in message || "encrypt" in message || "decrypt" in message ||
            e::class.java.simpleName.contains("InvalidPassword", ignoreCase = true)
    }

    companion object {
        private const val RENDER_DPI = 200f
        private const val MAX_PAGES = 40
        private const val MIN_EMBEDDED_CHARS = 120
    }
}

/**
 * Optional OCR via a locally installed Tesseract binary.
 *
 * Shelling out rather than binding a native library keeps the server a plain JAR that runs
 * anywhere a JVM does, with no platform-specific artifacts to ship. The desktop app this
 * replaces already relied on a Tesseract install, so this is not a new requirement.
 */
class TesseractOcr(private val binary: String = "tesseract") {

    val available: Boolean by lazy {
        runCatching {
            val p = ProcessBuilder(binary, "--version")
                .redirectErrorStream(true)
                .start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    fun recognize(image: java.awt.image.BufferedImage): String {
        val input = File.createTempFile("ocr", ".png")
        val outBase = File.createTempFile("ocr-out", "")
        try {
            ImageIO.write(image, "png", input)
            val process = ProcessBuilder(binary, input.absolutePath, outBase.absolutePath, "--psm", "6")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(PER_PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ""
            }
            val produced = File(outBase.absolutePath + ".txt")
            return if (produced.exists()) produced.readText().also { produced.delete() } else ""
        } finally {
            // These hold rendered financial documents; they must not linger in temp.
            input.delete()
            outBase.delete()
        }
    }

    companion object {
        private const val PER_PAGE_TIMEOUT_SECONDS = 60L
    }
}
