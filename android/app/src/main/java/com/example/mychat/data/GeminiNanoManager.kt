package com.example.mychat.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026 Simulation of Gemini Nano (AI Core)
 * In a real environment, this would call the Android AICore services.
 */
@Singleton
class GeminiNanoManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun analyzeMedicalText(text: String): AnalysisResult {
        Log.d("GeminiNano", "Running local LLM inference (Simulated)...")
        
        // Simulating LLM's multi-dimensional extraction:
        // 1. Symptom/Main Topic Identification
        // 2. Clinical Outcome/Diagnosis
        // 3. Treatment/Plan
        
        val lowerText = text.lowercase()
        val lines = text.lines().filter { it.isNotBlank() }
        
        // Heuristic: Topic Scoring
        val scores = mutableMapOf<String, Int>()
        
        val topics = mapOf(
            "Cardiac" to listOf("heart", "chest", "cardiac", "ekg", "ecg", "troponin", "tightness", "palpitation"),
            "Respiratory" to listOf("lung", "breathing", "cough", "breath", "oxygen", "pneumonia", "asthma"),
            "Metabolic" to listOf("blood glucose", "a1c", "diabetes", "insulin", "cholesterol", "lipid", "triglyceride"),
            "Infectious" to listOf("fever", "infection", "antibiotic", "wbc", "bacteria", "viral"),
            "Neurological" to listOf("brain", "stroke", "headache", "mri", "neurology", "seizure"),
            "Musculoskeletal" to listOf("bone", "fracture", "pain", "injury", "trauma")
        )

        for ((topic, keywords) in topics) {
            var score = 0
            for (kw in keywords) {
                if (lowerText.contains(kw)) score += 1
            }
            scores[topic] = score
        }

        val primaryTopic = scores.maxByOrNull { it.value }?.key ?: "General Medical"
        
        // Heuristic: Diagnosis/Summary Line Extraction
        val summaryLine = extractSignificantLine(text, lowerText)
        
        val dateRegex = """(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})|([A-Z][a-z]{2,8}\s+\d{1,2},?\s+\d{4})""".toRegex()
        val date = dateRegex.find(text)?.value
        
        val finalSummary = when {
            primaryTopic == "Cardiac" && lowerText.contains("chest") -> {
                "Cardiac evaluation regarding chest symptoms and findings."
            }
            summaryLine.length > 20 -> summaryLine
            else -> "Medical record regarding $primaryTopic findings."
        }

        return AnalysisResult(
            summary = finalSummary,
            type = primaryTopic,
            date = date
        )
    }

    private fun extractSignificantLine(text: String, lowerText: String): String {
        // Find lines containing diagnosis or findings
        val lines = text.lines().filter { it.trim().length > 10 }
        
        // Priority 1: "Diagnosis:" or "Impression:"
        val diagnosisMarkers = listOf("diagnosis", "impression", "assessment", "conclusion", "plan")
        for (marker in diagnosisMarkers) {
            val markerLine = lines.find { it.lowercase().contains("$marker:") }
            if (markerLine != null) {
                return markerLine.substringAfter(":").trim()
            }
            val afterMarker = lines.find { it.lowercase().startsWith(marker) }
            if (afterMarker != null) return afterMarker.trim()
        }
        
        // Priority 2: Mention of symptoms or main findings
        val symptoms = listOf("pain", "tightness", "shortness", "fever", "nausea", "dizziness")
        for (s in symptoms) {
            val symptomLine = lines.find { it.lowercase().contains(s) }
            if (symptomLine != null) return symptomLine.trim()
        }

        return lines.firstOrNull() ?: "Summary of medical record."
    }

    data class AnalysisResult(val summary: String, val type: String, val date: String?)
}
