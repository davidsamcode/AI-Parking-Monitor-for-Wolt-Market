package com.aipackingmonitor.ui.monitoring

import com.aipackingmonitor.data.AlertEventEntity
import com.aipackingmonitor.data.PilotSettings
import com.aipackingmonitor.domain.model.MonitoringSnapshot
import com.aipackingmonitor.domain.model.MonitoringZone
import com.aipackingmonitor.domain.model.NormalizedRect
import com.aipackingmonitor.domain.model.PilotSummary
import com.aipackingmonitor.domain.model.ZoneType

data class MonitoringUiState(
    val zone: MonitoringZone = DefaultPackingZone,
    val snapshot: MonitoringSnapshot = MonitoringSnapshot.initial(DefaultPackingZone.id, 0),
    val settings: PilotSettings = PilotSettings(),
    val summary: PilotSummary = PilotSummary(
        sessions = 0,
        totalAlerts = 0,
        correctAlerts = 0,
        falseAlerts = 0,
        dismissedAlerts = 0,
        averageResponseTimeMs = 0,
    ),
    val recentEvents: List<AlertEventEntity> = emptyList(),
    val cameraPermissionGranted: Boolean = false,
    val monitoringEnabled: Boolean = false,
    val referenceReady: Boolean = false,
    val referenceCaptureRequest: Long = 0,
    val areaSetupActive: Boolean = false,
    val draftZoneBounds: NormalizedRect = DefaultPackingZone.bounds,
    val awaitingFeedbackEventId: String? = null,
    val lastMessage: String? = null,
)

val DefaultPackingZone = MonitoringZone(
    id = "packing-table",
    name = "Packing table",
    type = ZoneType.PackingTable,
    bounds = NormalizedRect(
        left = 0.12f,
        top = 0.18f,
        right = 0.88f,
        bottom = 0.82f,
    ),
    occupancyThreshold = 0.07f,
    clearThreshold = 0.03f,
    stabilityDurationMs = 2_500,
)
