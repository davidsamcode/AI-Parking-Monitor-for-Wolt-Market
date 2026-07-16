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
    val triggerReason: String?,
    val motionScore: Float?,
    val largestChangedRegionScore: Float?,
    val addedObjectScore: Float?,
    val removedObjectScore: Float?,
    val localVerifierDecision: String?,
    val localVerifierConfidence: Float?,
    val clearThreshold: Float?,
    val leftoverThreshold: Float?,
    val changedRegionLeft: Float?,
    val changedRegionTop: Float?,
    val changedRegionRight: Float?,
    val changedRegionBottom: Float?,
)
