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
            "Cardiac" to listOf("heart", "chest", "cardiac", "ekg", "ecg", "troponin", "tightness", "palpitation", "atrial", "ventricular", "myocardial"),
            "Respiratory" to listOf("lung", "breathing", "cough", "breath", "oxygen", "pneumonia", "asthma", "bronchial", "respiratory"),
            "Metabolic" to listOf("blood glucose", "a1c", "diabetes", "insulin", "hba1c", "metabolic"),
            "Lipids" to listOf("cholesterol", "lipid", "triglyceride", "hdl", "ldl"),
            "Infectious" to listOf("fever", "infection", "antibiotic", "wbc", "bacteria", "viral", "sepsis"),
            "Neurological" to listOf("brain", "stroke", "headache", "mri", "neurology", "seizure", "cranial"),
            "Musculoskeletal" to listOf("bone", "fracture", "pain", "injury", "trauma", "joint", "muscle")
        )

        for ((topic, keywords) in topics) {
            var score = 0
            for (kw in keywords) {
                // Higher score for exact matches or multiple occurrences
                val count = lowerText.split(kw).size - 1
                if (count > 0) {
                    score += (count * 2).coerceAtMost(10)
                    // Bonus for keyword in the first 500 characters
                    if (lowerText.take(500).contains(kw)) score += 5
                }
            }
            scores[topic] = score
        }

        // 1.5. Check for Symptom/Reason for Visit
        val symptomMarkers = listOf("reason for visit", "chief complaint", "symptoms", "presenting with")
        var detectedSymptom = ""
        for (marker in symptomMarkers) {
            val index = lowerText.indexOf(marker)
            if (index != -1) {
                val start = index + marker.length
                val end = (start + 100).coerceAtMost(text.length)
                detectedSymptom = text.substring(start, end).split("\n", ".").firstOrNull { it.isNotBlank() }?.replace(":", "")?.trim() ?: ""
                if (detectedSymptom.isNotEmpty()) break
            }
        }

        val primaryTopic = scores.maxByOrNull { it.value }?.let { if (it.value > 0) it.key else null } ?: "Medical Document"
        
        // Heuristic: Diagnosis/Summary Line Extraction
        val summaryLine = extractSignificantLine(text, lowerText)
        
        val dateRegex = """(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})|([A-Z][a-z]{2,8}\s+\d{1,2},?\s+\d{4})""".toRegex()
        val date = dateRegex.find(text)?.value
        
        val finalSummary = when {
            detectedSymptom.isNotEmpty() && summaryLine.contains("Diagnosis", ignoreCase = true) -> {
                "Presented with $detectedSymptom. $summaryLine"
            }
            detectedSymptom.isNotEmpty() -> {
                "Medical evaluation for $detectedSymptom."
            }
            summaryLine.length > 30 -> summaryLine
            else -> "Medical record regarding $primaryTopic findings."
        }

        return AnalysisResult(
            summary = finalSummary.replace("""\s+""".toRegex(), " ").trim(),
            type = primaryTopic,
            date = date
        )
    }

    private fun extractSignificantLine(text: String, lowerText: String): String {
        // Find lines containing diagnosis or findings
        val lines = text.lines().map { it.trim() }.filter { it.length > 10 }
        
        // Priority 1: "Diagnosis:" or "Impression:"
        val diagnosisMarkers = listOf("diagnosis", "impression", "assessment", "conclusion", "clinical finding", "plan")
        for (marker in diagnosisMarkers) {
            val markerLine = lines.find { it.lowercase().contains("$marker:") }
            if (markerLine != null) {
                val content = markerLine.substringAfter(":").trim()
                if (content.length > 5) return "$marker: $content"
            }
            
            // Check the line immediately AFTER the marker if the marker is on its own line
            val exactMarkerIndex = lines.indexOfFirst { it.lowercase() == marker || it.lowercase() == "$marker:" }
            if (exactMarkerIndex != -1 && exactMarkerIndex < lines.size - 1) {
                val nextLine = lines[exactMarkerIndex + 1]
                if (nextLine.length > 10) return "$marker: $nextLine"
            }
        }
        
        // Priority 2: Mention of symptoms or main findings
        val symptoms = listOf("chest pain", "tightness", "shortness of breath", "palpitations", "dizziness")
        for (s in symptoms) {
            val symptomLine = lines.find { it.lowercase().contains(s) }
            if (symptomLine != null) return symptomLine
        }

        return lines.firstOrNull { it.length > 20 } ?: "Summary of medical record."
    }

    data class AnalysisResult(val summary: String, val type: String, val date: String?)
}
