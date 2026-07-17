package com.aipackingmonitor.data

import com.aipackingmonitor.domain.model.MonitoringZone
import com.aipackingmonitor.domain.model.NormalizedRect
import com.aipackingmonitor.domain.model.ZoneType

data class PilotSettings(
    val entryThreshold: Float = 0.07f,
    val clearThreshold: Float = 0.03f,
    val alertDelayMs: Long = 3_000,
    val alarmVolumePercent: Int = 80,
    val vibrationEnabled: Boolean = true,
    val auditVideoEnabled: Boolean = false,
    val zoneLeft: Float = 0.12f,
    val zoneTop: Float = 0.18f,
    val zoneRight: Float = 0.88f,
    val zoneBottom: Float = 0.82f,
    val cartZoneLeft: Float = 0.08f,
    val cartZoneTop: Float = 0.46f,
    val cartZoneRight: Float = 0.94f,
    val cartZoneBottom: Float = 0.96f,
    val additionalZones: List<ConfiguredMonitoringZone> = emptyList(),
)

data class ConfiguredMonitoringZone(
    val id: String,
    val name: String,
    val type: ZoneType,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun toMonitoringZone(
        occupancyThreshold: Float,
        clearThreshold: Float,
    ): MonitoringZone =
        MonitoringZone(
            id = id,
            name = name,
            type = type,
            bounds = NormalizedRect(left, top, right, bottom),
            occupancyThreshold = occupancyThreshold,
            clearThreshold = clearThreshold,
            stabilityDurationMs = 2_500,
        )
}
