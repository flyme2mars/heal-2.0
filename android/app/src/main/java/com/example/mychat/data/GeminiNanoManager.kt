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
        
        val lowerText = text.lowercase()
        
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
                val count = lowerText.split(kw).size - 1
                if (count > 0) {
                    score += (count * 2).coerceAtMost(10)
                    if (lowerText.take(500).contains(kw)) score += 5
                }
            }
            scores[topic] = score
        }

        // Check for Symptom/Reason for Visit
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
        
        val summaryLine = extractSignificantLine(text, lowerText)
        
        val dateRegex = """(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})|([A-Z][a-z]{2,8}\s+\d{1,2},?\s+\d{4})""".toRegex()
        val date = dateRegex.find(text)?.value
        
        val finalSummary = when {
            summaryLine.contains("Diagnosis", ignoreCase = true) || summaryLine.contains("Impression", ignoreCase = true) -> {
                summaryLine
            }
            detectedSymptom.isNotEmpty() -> {
                "Medical evaluation for $detectedSymptom."
            }
            summaryLine.length > 20 -> summaryLine
            else -> "Medical record regarding $primaryTopic findings."
        }

        return AnalysisResult(
            summary = finalSummary.replace("""\s+""".toRegex(), " ").trim(),
            type = primaryTopic,
            date = date,
            tags = extractTags(primaryTopic, text, lowerText)
        )
    }

    private fun extractTags(topic: String, text: String, lowerText: String): List<String> {
        val tags = mutableSetOf<String>()
        tags.add(topic)
        
        val clinicalKeywords = mapOf(
            "Tachycardia" to listOf("tachycardia", "fast heart"),
            "Bradycardia" to listOf("bradycardia", "slow heart"),
            "Arrhythmia" to listOf("arrhythmia", "pac", "pvc", "irregular"),
            "Ischemia" to listOf("ischemia", "st elevation", "st depression"),
            "Normal" to listOf("normal sinus", "unremarkable"),
            "Hypertension" to listOf("hypertension", "high blood pressure"),
            "Hyperglycemia" to listOf("glucose", "hyperglycemia", "high sugar"),
            "Anemia" to listOf("anemia", "low hemoglobin", "hgb"),
            "Inflammation" to listOf("crp", "inflammation", "esr")
        )

        for ((tag, keywords) in clinicalKeywords) {
            if (keywords.any { lowerText.contains(it) }) {
                tags.add(tag)
            }
        }
        
        return tags.toList()
    }

    private fun extractSignificantLine(text: String, lowerText: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.length > 5 }
        val noiseKeywords = listOf("patient", "dob", "date of birth", "mrn", "gender", "sex", "age", "address", "physician", "provider", "facility", "location", "collected", "received", "reported")
        val clinicalLines = lines.filter { line ->
            val lowerLine = line.lowercase()
            !noiseKeywords.any { noise -> lowerLine.startsWith(noise) || (lowerLine.contains(noise) && lowerLine.contains(":")) }
        }

        val sectionMarkers = listOf("diagnosis", "impression", "assessment", "conclusion", "clinical finding", "plan", "results", "history of present illness")
        for (marker in sectionMarkers) {
            val markerIndex = clinicalLines.indexOfFirst { it.lowercase().contains("$marker:") }
            if (markerIndex != -1) {
                val line = clinicalLines[markerIndex]
                val content = line.substringAfter(":").trim()
                if (content.length > 10) return "$marker: $content"
                
                val paragraph = StringBuilder()
                var j = markerIndex + 1
                while (j < clinicalLines.size && j < markerIndex + 4) {
                    val nextLine = clinicalLines[j]
                    if (sectionMarkers.any { nextLine.lowercase().contains("$it:") }) break
                    paragraph.append(nextLine).append(" ")
                    j++
                }
                if (paragraph.length > 15) return "$marker: ${paragraph.toString().trim()}"
            }
        }
        
        val symptoms = listOf("chest pain", "tightness", "shortness of breath", "palpitations", "dizziness", "cough", "fever", "pain", "nausea")
        for (s in symptoms) {
            val symptomLine = clinicalLines.find { it.lowercase().contains(s) }
            if (symptomLine != null && symptomLine.length > 20) return symptomLine
        }

        return clinicalLines.firstOrNull { it.length > 40 || it.endsWith(".") } ?: clinicalLines.firstOrNull { it.length > 20 } ?: "Summary of medical record."
    }

    data class AnalysisResult(val summary: String, val type: String, val date: String?, val tags: List<String>)
}
