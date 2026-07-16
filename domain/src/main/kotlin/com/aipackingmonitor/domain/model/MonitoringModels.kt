package com.aipackingmonitor.domain.model

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f) { "left must be normalized" }
        require(top in 0f..1f) { "top must be normalized" }
        require(right in 0f..1f) { "right must be normalized" }
        require(bottom in 0f..1f) { "bottom must be normalized" }
        require(right > left) { "right must be greater than left" }
        require(bottom > top) { "bottom must be greater than top" }
    }

    val width: Float = right - left
    val height: Float = bottom - top

    companion object {
        val Full = NormalizedRect(0f, 0f, 1f, 1f)
    }
}

enum class ZoneType {
    PackingTable,
    Tote,
    DispatchShelf,
}

data class MonitoringZone(
    val id: String,
    val name: String,
    val type: ZoneType,
    val bounds: NormalizedRect,
    val occupancyThreshold: Float,
    val clearThreshold: Float,
    val stabilityDurationMs: Long,
)

enum class DetectionQuality {
    Valid,
    NoReference,
    TooDark,
    TooBlurred,
    LensCovered,
    ReferenceMisaligned,
    AnalyzerUnavailable,
}

data class DetectionResult(
    val zoneId: String,
    val occupancyScore: Float,
    val motionScore: Float,
    val largestChangedRegionScore: Float,
    val addedObjectScore: Float = 0f,
    val removedObjectScore: Float = 0f,
    val localVerifierConfidence: Float = 0f,
    val changeClassification: ChangeClassification = ChangeClassification.None,
    val isMotionStable: Boolean,
    val quality: DetectionQuality,
    val analysisLatencyMs: Long,
    val changedRegionBounds: NormalizedRect? = null,
) {
    init {
        require(occupancyScore in 0f..1f) { "occupancyScore must be in 0..1" }
        require(motionScore in 0f..1f) { "motionScore must be in 0..1" }
        require(largestChangedRegionScore in 0f..1f) {
            "largestChangedRegionScore must be in 0..1"
        }
        require(addedObjectScore in 0f..1f) { "addedObjectScore must be in 0..1" }
        require(removedObjectScore in 0f..1f) { "removedObjectScore must be in 0..1" }
        require(localVerifierConfidence in 0f..1f) { "localVerifierConfidence must be in 0..1" }
        require(analysisLatencyMs >= 0) { "analysisLatencyMs must be non-negative" }
    }

    val isUsable: Boolean = quality == DetectionQuality.Valid

    companion object {
    }
}

enum class ChangeClassification {
    None,
    AddedObject,
    RemovedReferenceObject,
    LightingChange,
    MixedChange,
}

enum class MonitoringState {
    NeedsReference,
    EmptyTable,
    PackingActive,
    BagRemovedCandidate,
    PostPackScan,
    ClearReset,
    LeftoverAlert,
    SystemUnavailable,
    Paused,
}

data class ClearancePolicy(
    val entryThreshold: Float = 0.07f,
    val exitThreshold: Float = 0.03f,
    val packingActivityThreshold: Float = 0.07f,
    val largeObjectThreshold: Float = 0.18f,
    val leftoverThreshold: Float = 0.045f,
    val stoppedDurationMs: Long = 3_000,
    val postPackScanDurationMs: Long = 1_000,
    val clearConfirmationMs: Long = 1_200,
    val staleFrameTimeoutMs: Long = 2_500,
) {
    init {
        require(entryThreshold in 0f..1f)
        require(exitThreshold in 0f..1f)
        require(entryThreshold > exitThreshold) {
            "entryThreshold must be greater than exitThreshold for hysteresis"
        }
        require(packingActivityThreshold in 0f..1f)
        require(largeObjectThreshold in 0f..1f)
        require(leftoverThreshold in 0f..1f)
        require(largeObjectThreshold > leftoverThreshold) {
            "largeObjectThreshold must be greater than leftoverThreshold"
        }
    }
}

data class MonitoringSnapshot(
    val state: MonitoringState,
    val zoneId: String,
    val occupancyScore: Float,
    val motionScore: Float,
    val largestChangedRegionScore: Float,
    val addedObjectScore: Float,
    val removedObjectScore: Float,
    val localVerifierConfidence: Float,
    val changeClassification: ChangeClassification,
    val changedRegionBounds: NormalizedRect?,
    val stateChangedAtMillis: Long,
    val packingStartedAtMillis: Long?,
    val quietSinceMillis: Long?,
    val scanStartedAtMillis: Long?,
    val clearSinceMillis: Long?,
    val alertStartedAtMillis: Long?,
    val alertDismissedForCurrentOccupancy: Boolean,
    val lastQuality: DetectionQuality,
    val alertMessage: String?,
) {
    companion object {
        fun initial(zoneId: String, nowMillis: Long): MonitoringSnapshot =
            MonitoringSnapshot(
                state = MonitoringState.NeedsReference,
                zoneId = zoneId,
                occupancyScore = 0f,
                motionScore = 0f,
                largestChangedRegionScore = 0f,
                addedObjectScore = 0f,
                removedObjectScore = 0f,
                localVerifierConfidence = 0f,
                changeClassification = ChangeClassification.None,
                changedRegionBounds = null,
                stateChangedAtMillis = nowMillis,
                packingStartedAtMillis = null,
                quietSinceMillis = null,
                scanStartedAtMillis = null,
                clearSinceMillis = null,
                alertStartedAtMillis = null,
                alertDismissedForCurrentOccupancy = false,
                lastQuality = DetectionQuality.NoReference,
                alertMessage = "Capture an empty reference before monitoring.",
            )
    }
}

data class AlertEvent(
    val id: String,
    val zoneId: String,
    val zoneName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val occupancyScore: Float,
    val timeToAlertMs: Long,
    val dismissed: Boolean,
    val markedCorrect: Boolean?,
)

data class PilotSummary(
    val sessions: Int,
    val totalAlerts: Int,
    val correctAlerts: Int,
    val falseAlerts: Int,
    val dismissedAlerts: Int,
    val averageResponseTimeMs: Long,
)
