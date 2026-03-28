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

    suspend fun updatePatient(firstName: String, lastName: String, gender: String) {
        val patient = Patient().apply {
            id = "user-patient-001"
            addName(HumanName().apply {
                addGiven(firstName)
                family = lastName
            })
            this.gender = when (gender.lowercase()) {
                "male" -> Enumerations.AdministrativeGender.MALE
                "female" -> Enumerations.AdministrativeGender.FEMALE
                else -> Enumerations.AdministrativeGender.OTHER
            }
        }
        
        try {
            fhirEngine.get<Patient>(patient.id)
            fhirEngine.update(patient)
        } catch (e: Exception) {
            fhirEngine.create(patient)
        }
    }

    suspend fun updateWeight(weightKg: Double) {
        val weight = Observation().apply {
            id = "user-weight-current"
            subject = org.hl7.fhir.r4.model.Reference("Patient/user-patient-001")
            code = org.hl7.fhir.r4.model.CodeableConcept().apply {
                addCoding().apply {
                    system = "http://loinc.org"
                    code = "29463-7"
                    display = "Body weight"
                }
            }
            value = Quantity().apply {
                value = java.math.BigDecimal(weightKg)
                unit = "kg"
                system = "http://unitsofmeasure.org"
                code = "kg"
            }
            effective = DateTimeType(Date())
            status = Observation.ObservationStatus.FINAL
        }
        
        try {
            fhirEngine.get<Observation>(weight.id)
            fhirEngine.update(weight)
        } catch (e: Exception) {
            fhirEngine.create(weight)
        }
    }

    suspend fun getPatientName(): String? {
        return try {
            val patient = fhirEngine.get<Patient>("user-patient-001")
            patient.nameFirstRep.nameAsSingleString
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getLatestWeight(): String? {
        return try {
            val obs = fhirEngine.get<Observation>("user-weight-current")
            if (obs.hasValueQuantity()) {
                obs.valueQuantity.value.toString()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getMedicalSummary(): String {
        return try {
            val patient = fhirEngine.get<Patient>("user-patient-001")
            val name = patient.nameFirstRep.nameAsSingleString
            
            val observations = fhirEngine.search<Observation> {
                // Return all observations
            }
            
            val summary = StringBuilder()
            summary.append("Medical Records Summary (Local Vault):\n")
            summary.append("- Patient: ").append(name).append("\n")
            
            val latestObs = observations
                .map { it.resource }
                .groupBy { it.code.codingFirstRep.code }
                .mapValues { entry -> entry.value.maxByOrNull { (it.effective as? DateTimeType)?.value ?: Date(0) } }

            summary.append("- Vital Signs:\n")
            latestObs.values.filterNotNull().forEach { obsModel ->
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
            "No local medical records found. Please enter your data manually in the Vault."
        }
    }
}
