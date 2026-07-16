package com.aipackingmonitor.data

data class PilotSettings(
    val entryThreshold: Float = 0.07f,
    val clearThreshold: Float = 0.03f,
    val alertDelayMs: Long = 3_000,
    val alarmVolumePercent: Int = 80,
    val vibrationEnabled: Boolean = true,
    val zoneLeft: Float = 0.12f,
    val zoneTop: Float = 0.18f,
    val zoneRight: Float = 0.88f,
    val zoneBottom: Float = 0.82f,
    val cartZoneLeft: Float = 0.08f,
    val cartZoneTop: Float = 0.46f,
    val cartZoneRight: Float = 0.94f,
    val cartZoneBottom: Float = 0.96f,
)
