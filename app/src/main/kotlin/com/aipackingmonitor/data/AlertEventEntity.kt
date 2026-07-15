package com.aipackingmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_events")
data class AlertEventEntity(
    @PrimaryKey val id: String,
    val zoneId: String,
    val zoneName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val occupancyScore: Float,
    val timeToAlertMs: Long,
    val dismissed: Boolean,
    val markedCorrect: Boolean?,
)
