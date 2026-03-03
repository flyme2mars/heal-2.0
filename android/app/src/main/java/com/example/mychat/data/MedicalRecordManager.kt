package com.example.mychat.data

import android.content.Context
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import dagger.hilt.android.qualifiers.ApplicationContext
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.DateTimeType
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicalRecordManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fhirEngine = FhirEngineProvider.getInstance(context)

    suspend fun saveSamplePatient() {
        val patient = Patient().apply {
            id = "sample-patient-001"
            addName(HumanName().apply {
                addGiven("John")
                family = "Doe"
            })
            gender = Enumerations.AdministrativeGender.MALE
            birthDate = Date()
        }
        
        fhirEngine.create(patient)
    }

    suspend fun saveSampleVitals() {
        val weight = Observation().apply {
            id = "sample-weight-001"
            subject = org.hl7.fhir.r4.model.Reference("Patient/sample-patient-001")
            code = org.hl7.fhir.r4.model.CodeableConcept().apply {
                addCoding().apply {
                    system = "http://loinc.org"
                    code = "29463-7"
                    display = "Body weight"
                }
            }
            value = Quantity().apply {
                value = java.math.BigDecimal(75.5)
                unit = "kg"
                system = "http://unitsofmeasure.org"
                code = "kg"
            }
            effective = DateTimeType(Date())
            status = Observation.ObservationStatus.FINAL
        }
        
        fhirEngine.create(weight)
    }

    suspend fun getMedicalSummary(): String {
        return try {
            val patient = fhirEngine.get<Patient>("sample-patient-001")
            val name = patient.nameFirstRep.nameAsSingleString
            
            // Search for observations
            val observations = fhirEngine.search<Observation> {
                // Return all observations
            }
            
            val summary = StringBuilder()
            summary.append("Medical Records Summary (Local Vault):\n")
            summary.append("- Patient: ").append(name).append("\n")
            summary.append("- Records found: ").append(observations.size).append("\n")
            
            observations.forEach { obs ->
                val obsModel = obs.resource
                val display = obsModel.code.codingFirstRep.display ?: "Observation"
                val value = if (obsModel.hasValueQuantity()) {
                    "${obsModel.valueQuantity.value} ${obsModel.valueQuantity.unit}"
                } else {
                    "N/A"
                }
                summary.append("  - ").append(display).append(": ").append(value).append("\n")
            }
            summary.toString()
        } catch (e: Exception) {
            "No local medical records found. Click the sync button to load sample ABDM data."
        }
    }
}
