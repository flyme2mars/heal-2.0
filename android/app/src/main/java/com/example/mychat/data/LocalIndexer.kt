package com.example.mychat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import java.io.InputStream

@Singleton
class LocalIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentManager: DocumentManager,
    private val geminiNano: GeminiNanoManager
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun indexDocument(document: HealthDocument) {
        try {
            Log.d("LocalIndexer", "Starting indexing for ${document.name}")
            
            // 1. Extract Text using ML Kit
            val rawText = extractText(document)
            if (rawText.isBlank()) {
                documentManager.updateMetadata(document.id, "No readable text found.", "Unknown", null)
                return
            }
            
            documentManager.updateFullText(document.id, rawText)

            // 2. Local Summarization using Simulated Gemini Nano
            val analysis = geminiNano.analyzeMedicalText(rawText)
            
            documentManager.updateMetadata(
                document.id, 
                analysis.summary, 
                analysis.type, 
                analysis.date
            )
            
            Log.d("LocalIndexer", "Indexing complete for ${document.name}")
        } catch (e: Exception) {
            Log.e("LocalIndexer", "Indexing failed", e)
            documentManager.updateMetadata(document.id, "Indexing failed: ${e.message}", "Error", null)
        }
    }

    private suspend fun extractText(document: HealthDocument): String {
        return try {
            val inputStream = documentManager.getDocumentDecryptStream(document)
            when (document.type) {
                "image" -> {
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val result = recognizer.process(image).await()
                    result.text
                }
                "pdf" -> {
                    extractTextFromPdf(document)
                }
                else -> {
                    inputStream.bufferedReader().use { it.readText() }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalIndexer", "OCR Failed", e)
            ""
        }
    }

    private suspend fun extractTextFromPdf(document: HealthDocument): String {
        return try {
            val file = documentManager.getTempFile(document)
            val renderer = android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY))
            val textBuilder = StringBuilder()
            
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).await()
                textBuilder.append(result.text).append("\n")
                
                page.close()
            }
            renderer.close()
            file.delete()
            textBuilder.toString()
        } catch (e: Exception) {
            Log.e("LocalIndexer", "PDF extraction failed", e)
            "PDF extraction failed: ${e.message}"
        }
    }
}

