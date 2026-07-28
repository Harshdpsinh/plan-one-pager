package com.gohil.bookkeeper.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device text recognition.
 *
 * Uses the bundled ML Kit model, which ships inside the APK: no network call, no Play
 * Services dependency, and no way for a document to leave the device during recognition.
 */
class TextRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bitmap: Bitmap): String =
        process(InputImage.fromBitmap(bitmap, 0))

    suspend fun recognize(context: Context, uri: Uri): String =
        process(InputImage.fromFilePath(context, uri))

    private suspend fun process(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    // Reading blocks line by line preserves the row structure that the bank
                    // statement parser depends on; the flat result would run columns together.
                    val text = buildString {
                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                append(line.text).append('\n')
                            }
                            append('\n')
                        }
                    }
                    if (cont.isActive) cont.resume(text)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    fun close() = recognizer.close()
}
