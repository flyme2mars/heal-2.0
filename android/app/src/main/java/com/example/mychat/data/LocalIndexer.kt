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
            val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val textBuilder = StringBuilder()
            
            // 2026 Strategy: Render at high DPI for accurate OCR of medical text
            val scaleFactor = 3f // Increase resolution by 3x (approx 300 DPI)
            
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                
                val width = (page.width * scaleFactor).toInt()
                val height = (page.height * scaleFactor).toInt()
                
                // Create a high-resolution bitmap for this page
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // Fill with white background (important for OCR)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).await()
                
                if (result.text.isNotBlank()) {
                    textBuilder.append("--- Page ${i + 1} ---\n")
                    textBuilder.append(result.text).append("\n\n")
                }
                
                page.close()
                bitmap.recycle() // Free memory immediately
            }
            renderer.close()
            pfd.close()
            file.delete()
            
            val finalResult = textBuilder.toString()
            if (finalResult.isBlank()) "No text could be extracted from this PDF." else finalResult
        } catch (e: Exception) {
            Log.e("LocalIndexer", "PDF extraction failed", e)
            "PDF extraction failed: ${e.message}"
        }
    }
}

