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
    private val documentManager: DocumentManager
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

            // 2. Local Summarization using Gemini Nano (AI Core 2026)
            // In a real 2026 environment, this uses the AICore service
            val summaryInfo = summarizeLocally(rawText)
            
            documentManager.updateMetadata(
                document.id, 
                summaryInfo.summary, 
                summaryInfo.type, 
                summaryInfo.date
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
            if (document.type == "image") {
                val bitmap = BitmapFactory.decodeStream(inputStream)
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).await()
                result.text
            } else if (document.type == "pdf") {
                // In 2026, ML Kit handles PDFs directly or we use a PDF library
                "PDF Content Extraction Placeholder for ${document.name}"
            } else {
                inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e("LocalIndexer", "OCR Failed", e)
            ""
        }
    }

    private suspend fun summarizeLocally(text: String): SummaryResult {
        // This is a 2026 implementation of Gemini Nano via AI Core
        // Note: For this demo, we simulate the structured output of Nano
        Log.d("LocalIndexer", "Requesting Gemini Nano summary...")
        
        // In 2026, the Prompt API would look like this:
        // val session = AICore.createSession("summarization-v2")
        // val result = session.prompt("Identify type, date, and 1-sentence summary of: $text")
        
        // Simulating Nano's extraction capabilities:
        val lowerText = text.lowercase()
        val type = when {
            lowerText.contains("blood") || lowerText.contains("hematology") -> "Blood Test"
            lowerText.contains("mri") || lowerText.contains("resonance") -> "MRI Scan"
            lowerText.contains("prescription") || lowerText.contains("rx") -> "Prescription"
            else -> "Medical Record"
        }
        
        val summary = "Medical record regarding ${type.lowercase()} details."
        val date = "2026-03-30" // Placeholder date extraction
        
        return SummaryResult(summary, type, date)
    }

    data class SummaryResult(val summary: String, val type: String, val date: String?)
}
