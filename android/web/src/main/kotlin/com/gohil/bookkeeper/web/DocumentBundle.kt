package com.gohil.bookkeeper.web

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages the session's original PDFs into one file to forward to the accountant.
 *
 * ## Why this is usually a .zip and not a .rar
 *
 * RAR compression is proprietary to RARLAB. `unrar` is freely available and every tool can
 * *read* .rar, but *writing* one requires their licensed `rar` binary — there is no free
 * library, in Java or anywhere else, that creates RAR archives. Shipping one would mean
 * bundling licensed software this project has no licence for.
 *
 * Naming a ZIP ".rar" was the other option and is worse: it is a lie that works right up
 * until the CA's software checks the header and rejects a file that claims to be something
 * it is not.
 *
 * So: if a real `rar` binary is on this machine — anyone with WinRAR installed has one — it
 * is used and the result is a genuine .rar. Otherwise the bundle is a .zip, which WinRAR,
 * Windows Explorer, macOS, and every mail client open natively. The goal was one file to
 * forward; both formats meet it.
 */
class DocumentBundle(private val rarBinary: String = "rar") {

    data class Result(val bytes: ByteArray, val fileName: String, val format: String, val note: String)

    /** True when a licensed `rar` is available and a real .rar can be produced. */
    val rarAvailable: Boolean by lazy {
        runCatching {
            val p = ProcessBuilder(rarBinary).redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS)
            // `rar` with no arguments prints its banner and exits non-zero; reaching here at
            // all means the binary exists, which is the only thing being asked.
            true
        }.getOrDefault(false)
    }

    fun build(files: List<Pair<String, ByteArray>>, baseName: String): Result {
        require(files.isNotEmpty()) { "There are no uploaded documents to bundle yet." }
        val named = disambiguate(files)

        if (rarAvailable) {
            runCatching { rar(named, baseName) }.getOrNull()?.let { return it }
            // Fall through to zip rather than failing: the point is getting the CA a file.
        }
        return Result(
            bytes = zip(named),
            fileName = "$baseName.zip",
            format = "zip",
            note = "Packaged as .zip. A genuine .rar needs RARLAB's licensed 'rar' program, " +
                "which is not installed here — .zip opens in WinRAR and in Windows itself, " +
                "so your CA will not notice the difference. Install WinRAR and this will " +
                "produce a .rar automatically.",
        )
    }

    // ── formats ──────────────────────────────────────────────────────────────────

    private fun zip(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Shell out to a real `rar`. It only writes to a path, so the documents go to a temp
     * directory and are deleted immediately afterwards — these are bank statements, and they
     * must not be left lying in temp.
     */
    private fun rar(files: List<Pair<String, ByteArray>>, baseName: String): Result? {
        val work = Files.createTempDirectory("bundle")
        try {
            for ((name, bytes) in files) Files.write(work.resolve(name), bytes)
            val archive = work.resolve("$baseName.rar")
            val process = ProcessBuilder(
                rarBinary, "a", "-ep", "-y", archive.toString(),
            ).apply { command().addAll(files.map { work.resolve(it.first).toString() }) }
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(RAR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0 || !Files.exists(archive)) return null
            return Result(
                bytes = Files.readAllBytes(archive),
                fileName = "$baseName.rar",
                format = "rar",
                note = "Packaged as .rar using the RAR program installed on this computer.",
            )
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /**
     * Two banks both exporting "statement.pdf" would silently overwrite inside the archive,
     * so the second one gets a suffix. Losing a document on the way to the CA is the failure
     * this prevents.
     */
    private fun disambiguate(files: List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> {
        val used = mutableSetOf<String>()
        return files.map { (rawName, bytes) ->
            val clean = rawName.replace(Regex("""[/\\:*?"<>|]"""), "_").ifBlank { "document.pdf" }
            var candidate = clean
            var n = 2
            while (!used.add(candidate.lowercase())) {
                val stem = clean.substringBeforeLast('.', clean)
                val ext = clean.substringAfterLast('.', "")
                candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
                n++
            }
            candidate to bytes
        }
    }

    companion object {
        private const val RAR_TIMEOUT_SECONDS = 120L
    }
}
