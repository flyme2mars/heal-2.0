package com.example.mychat.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val healthConnectClient by lazy {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    fun getSdkStatus(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }

    fun getHealthConnectSettingsIntent(): android.content.Intent {
        return android.content.Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
    }

    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    suspend fun fetchHealthSummary(): String {
        val client = healthConnectClient ?: return "Health Connect is not available on this device."
        if (!hasAllPermissions()) return "Permissions not granted to access health data."

        val now = ZonedDateTime.now()
        val todayStart = now.toLocalDate().atStartOfDay(now.zone).toInstant()
        val sevenDaysAgo = now.minusDays(7).toLocalDate().atStartOfDay(now.zone).toInstant()
        val endTime = Instant.now()

        return try {
            val stepsToday = fetchSteps(client, todayStart, endTime)
            val stepsLastWeek = fetchSteps(client, sevenDaysAgo, endTime)
            val heartRateToday = fetchHeartRate(client, todayStart, endTime)
            
            val summary = StringBuilder()
            summary.append("User Health Data Summary:\n")
            summary.append("- Steps Today: ").append(stepsToday ?: 0).append("\n")
            summary.append("- Steps (Past 7 Days): ").append(stepsLastWeek ?: "No data").append("\n")
            if (heartRateToday != null) {
                summary.append("- Avg Heart Rate Today: ").append(heartRateToday).append(" bpm\n")
            } else {
                summary.append("- Heart Rate: No data available (likely no wearable connected)\n")
            }
            summary.toString()
        } catch (e: Exception) {
            "Error fetching health data: ${e.localizedMessage}"
        }
    }

    private suspend fun fetchSteps(client: HealthConnectClient, startTime: Instant, endTime: Instant): Long? {
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
        )
        return response[StepsRecord.COUNT_TOTAL]
    }

    private suspend fun fetchHeartRate(client: HealthConnectClient, startTime: Instant, endTime: Instant): Long? {
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(HeartRateRecord.BPM_AVG),
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
        )
        return response[HeartRateRecord.BPM_AVG]
    }
}
