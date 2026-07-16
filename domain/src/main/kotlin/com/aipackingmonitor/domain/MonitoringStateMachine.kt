package com.aipackingmonitor.domain

import com.aipackingmonitor.domain.model.ClearancePolicy
import com.aipackingmonitor.domain.model.ChangeClassification
import com.aipackingmonitor.domain.model.DetectionQuality
import com.aipackingmonitor.domain.model.DetectionResult
import com.aipackingmonitor.domain.model.MonitoringSnapshot
import com.aipackingmonitor.domain.model.MonitoringState
import com.aipackingmonitor.domain.model.MonitoringZone

class MonitoringStateMachine(
    private val policy: ClearancePolicy = ClearancePolicy(),
) {
    fun onReferenceCaptured(
        previous: MonitoringSnapshot,
        nowMillis: Long,
    ): MonitoringSnapshot =
        previous.transitionTo(
            state = MonitoringState.EmptyTable,
            nowMillis = nowMillis,
            detection = emptyDetection(previous.zoneId),
            packingStartedAtMillis = null,
            quietSinceMillis = nowMillis,
            scanStartedAtMillis = null,
            clearSinceMillis = nowMillis,
            alertStartedAtMillis = null,
            dismissed = false,
            message = "Empty reference ready. Waiting for packing activity.",
        )

    fun onPaused(
        previous: MonitoringSnapshot,
        nowMillis: Long,
    ): MonitoringSnapshot =
        previous.transitionTo(
            state = MonitoringState.Paused,
            nowMillis = nowMillis,
            detection = previous.toDetectionResult(),
            packingStartedAtMillis = previous.packingStartedAtMillis,
            quietSinceMillis = previous.quietSinceMillis,
            scanStartedAtMillis = previous.scanStartedAtMillis,
            clearSinceMillis = previous.clearSinceMillis,
            alertStartedAtMillis = null,
            dismissed = previous.alertDismissedForCurrentOccupancy,
            message = "Monitoring paused.",
        )

    fun dismissAlert(
        previous: MonitoringSnapshot,
        nowMillis: Long,
    ): MonitoringSnapshot {
        if (previous.state != MonitoringState.LeftoverAlert) return previous

        return previous.transitionTo(
            state = MonitoringState.PostPackScan,
            nowMillis = nowMillis,
            detection = previous.toDetectionResult(),
            packingStartedAtMillis = previous.packingStartedAtMillis,
            quietSinceMillis = previous.quietSinceMillis ?: nowMillis,
            scanStartedAtMillis = nowMillis,
            clearSinceMillis = null,
            alertStartedAtMillis = null,
            dismissed = true,
            message = "Alert dismissed. Scanning will continue until the zone matches the reference.",
        )
    }

    fun reduce(
        previous: MonitoringSnapshot,
        zone: MonitoringZone,
        detection: DetectionResult,
        nowMillis: Long,
        policyOverride: ClearancePolicy = policy,
    ): MonitoringSnapshot {
        if (previous.state == MonitoringState.Paused) return previous

        if (detection.zoneId != zone.id) {
            return unavailable(previous, detection, nowMillis, "Detection came from a different zone.")
        }

        if (!detection.isUsable) {
            return unavailable(previous, detection, nowMillis, qualityMessage(detection.quality))
        }

        return when (previous.state) {
            MonitoringState.NeedsReference -> previous.copy(
                occupancyScore = detection.occupancyScore,
                motionScore = detection.motionScore,
                largestChangedRegionScore = detection.largestChangedRegionScore,
                addedObjectScore = detection.addedObjectScore,
                removedObjectScore = detection.removedObjectScore,
                localVerifierConfidence = detection.localVerifierConfidence,
                changeClassification = detection.changeClassification,
                changedRegionBounds = detection.changedRegionBounds,
                lastQuality = detection.quality,
                alertMessage = "Capture an empty reference before monitoring.",
            )

            MonitoringState.EmptyTable,
            MonitoringState.ClearReset,
            MonitoringState.SystemUnavailable -> reduceReady(previous, detection, nowMillis, policyOverride)

            MonitoringState.PackingActive -> reducePackingActive(
                previous = previous,
                detection = detection,
                nowMillis = nowMillis,
                policy = policyOverride,
            )

            MonitoringState.BagRemovedCandidate -> reduceBagRemovedCandidate(
                previous = previous,
                detection = detection,
                nowMillis = nowMillis,
                policy = policyOverride,
            )

            MonitoringState.PostPackScan -> reducePostPackScan(
                previous = previous,
                detection = detection,
                nowMillis = nowMillis,
                policy = policyOverride,
            )

            MonitoringState.LeftoverAlert -> reduceLeftoverAlert(
                previous = previous,
                detection = detection,
                nowMillis = nowMillis,
                policy = policyOverride,
            )

            MonitoringState.Paused -> previous
        }
    }

    private fun reduceReady(
        previous: MonitoringSnapshot,
        detection: DetectionResult,
        nowMillis: Long,
        policy: ClearancePolicy,
    ): MonitoringSnapshot {
        if (isPackingActivity(detection, policy)) {
            return previous.transitionTo(
                state = MonitoringState.PackingActive,
                nowMillis = nowMillis,
                detection = detection,
                packingStartedAtMillis = previous.packingStartedAtMillis ?: nowMillis,
                quietSinceMillis = null,
                scanStartedAtMillis = null,
                clearSinceMillis = null,
                alertStartedAtMillis = null,
                dismissed = false,
                message = "Packing active. Alerts are suppressed.",
            )
        }

        return previous.transitionTo(
            state = MonitoringState.EmptyTable,
            nowMillis = if (previous.state == MonitoringState.EmptyTable) {
                previous.stateChangedAtMillis
            } else {
                nowMillis
            },
            detection = detection,
            packingStartedAtMillis = null,
            quietSinceMillis = if (detection.isMotionStable) previous.quietSinceMillis ?: nowMillis else null,
            scanStartedAtMillis = null,
            clearSinceMillis = if (isTableClear(detection, policy)) previous.clearSinceMillis ?: nowMillis else null,
            alertStartedAtMillis = null,
            dismissed = false,
            message = "Table ready. Waiting for packing activity.",
        )
    }

    private fun reducePackingActive(
        previous: MonitoringSnapshot,
        detection: DetectionResult,
        nowMillis: Long,
        policy: ClearancePolicy,
    ): MonitoringSnapshot {
        if (isActiveWorkStillHappening(detection)) {
            return previous.copyWithDetection(
                detection = detection,
                quietSinceMillis = null,
                alertMessage = "Packing active. Alerts are suppressed.",
            )
        }

        return previous.transitionTo(
            state = MonitoringState.BagRemovedCandidate,
            nowMillis = nowMillis,
            detection = detection,
            packingStartedAtMillis = previous.packingStartedAtMillis,
            quietSinceMillis = nowMillis,
            scanStartedAtMillis = null,
            clearSinceMillis = null,
            alertStartedAtMillis = null,
            dismissed = false,
            message = "Packing stopped. Waiting 3 seconds before scanning.",
        )
    }

    private fun reduceBagRemovedCandidate(
        previous: MonitoringSnapshot,
        detection: DetectionResult,
        nowMillis: Long,
        policy: ClearancePolicy,
    ): MonitoringSnapshot {
        if (isActiveWorkStillHappening(detection)) {
            return previous.transitionTo(
                state = MonitoringState.PackingActive,
                nowMillis = nowMillis,
                detection = detection,
                packingStartedAtMillis = previous.packingStartedAtMillis ?: nowMillis,
                quietSinceMillis = null,
                scanStartedAtMillis = null,
                clearSinceMillis = null,
                alertStartedAtMillis = null,
                dismissed = false,
                message = "Packing resumed. Alerts are suppressed.",
            )
        }

        val quietSince = previous.quietSinceMillis ?: nowMillis
        if (nowMillis - quietSince >= policy.stoppedDurationMs) {
            return previous.transitionTo(
                state = MonitoringState.PostPackScan,
                nowMillis = nowMillis,
                detection = detection,
                packingStartedAtMillis = previous.packingStartedAtMillis,
                quietSinceMillis = quietSince,
                scanStartedAtMillis = nowMillis,
                clearSinceMillis = null,
                alertStartedAtMillis = null,
                dismissed = false,
                message = "Scanning stable table against the empty reference.",
            )
        }

        return previous.copyWithDetection(
            detection = detection,
            quietSinceMillis = quietSince,
            alertMessage = "Packing stopped. Waiting 3 seconds before scanning.",
        )
    }

    private fun reducePostPackScan(
        previous: MonitoringSnapshot,
        detection: DetectionResult,
        nowMillis: Long,
        policy: ClearancePolicy,
    ): MonitoringSnapshot {
        if (isActiveWorkStillHappening(detection)) {
            return previous.transitionTo(
                state = MonitoringState.PackingActive,
                nowMillis = nowMillis,
                detection = detection,
                packingStartedAtMillis = previous.packingStartedAtMillis ?: nowMillis,
                quietSinceMillis = null,
                scanStartedAtMillis = null,
                clearSinceMillis = null,
                alertStartedAtMillis = null,
                dismissed = false,
                message = "Packing resumed. Alerts are suppressed.",
            )
        }

        val scanStarted = previous.scanStartedAtMillis ?: nowMillis
        if (nowMillis - scanStarted < policy.postPackScanDurationMs) {
            return previous.copyWithDetection(
                detection = detection,
                scanStartedAtMillis = scanStarted,
                alertMessage = "Scanning stable table against the empty reference.",
            )
        }

        return if (isLeftoverPresent(detection, policy)) {
            previous.transitionTo(
                state = MonitoringState.LeftoverAlert,
                nowMillis = nowMillis,
                detection = detection,
                packingStartedAtMillis = previous.packingStartedAtMillis,
                quietSinceMillis = previous.quietSinceMillis,
                scanStartedAtMillis = scanStarted,
                clearSinceMillis = null,
                alertStartedAtMillis = nowMillis,
                dismissed = false,
                message = "Possible leftover item after packing. Rules and local verifier agree.",
            )
        } else {
            previous.transitionTo(
                state = MonitoringState.ClearReset,
                nowMillis = nowMillis,
                detection = detection,
                packingStartedAtMillis = null,
                quietSinceMillis = nowMillis,
                scanStartedAtMillis = null,
                clearSinceMillis = nowMillis,
                alertStartedAtMillis = null,
                dismissed = false,
                message = clearOrVerifierMessage(detection, policy),
            )
        }
    }

    private fun reduceLeftoverAlert(
        previous: MonitoringSnapshot,
        detection: DetectionResult,
        nowMillis: Long,
        policy: ClearancePolicy,
    ): MonitoringSnapshot {
        if (isActiveWorkStillHappening(detection)) {
            return previous.transitionTo(
                state = MonitoringState.PackingActive,
                nowMillis = nowMillis,
                detection = detection,
                packingStartedAtMillis = nowMillis,
                quietSinceMillis = null,
                scanStartedAtMillis = null,
                clearSinceMillis = null,
                alertStartedAtMillis = null,
                dismissed = false,
                message = "Packing resumed. Alerts are suppressed.",
            )
        }

        val clearSince = if (isTableClear(detection, policy)) previous.clearSinceMillis ?: nowMillis else null
        val clearForLongEnough = clearSince != null && nowMillis - clearSince >= policy.clearConfirmationMs

        if (clearForLongEnough) {
            return previous.transitionTo(
                state = MonitoringState.ClearReset,
                nowMillis = nowMillis,
                detection = detection,
                packingStartedAtMillis = null,
                quietSinceMillis = nowMillis,
                scanStartedAtMillis = null,
                clearSinceMillis = nowMillis,
                alertStartedAtMillis = null,
                dismissed = false,
                message = "Leftover cleared. Ready for next packing.",
            )
        }

        return previous.copyWithDetection(
            detection = detection,
            clearSinceMillis = clearSince,
            alertMessage = "Possible leftover item after packing. Please check.",
        )
    }

    private fun isPackingActivity(detection: DetectionResult, policy: ClearancePolicy): Boolean =
        detection.motionScore >= MOTION_ACTIVITY_THRESHOLD ||
            detection.occupancyScore >= policy.packingActivityThreshold ||
            detection.largestChangedRegionScore >= policy.largeObjectThreshold

    private fun isActiveWorkStillHappening(detection: DetectionResult): Boolean =
        detection.motionScore >= MOTION_ACTIVITY_THRESHOLD

    private fun isTableClear(detection: DetectionResult, policy: ClearancePolicy): Boolean =
        detection.isMotionStable && detection.occupancyScore <= policy.exitThreshold

    private fun isLeftoverPresent(detection: DetectionResult, policy: ClearancePolicy): Boolean =
        detection.isMotionStable &&
            detection.occupancyScore >= policy.leftoverThreshold &&
            detection.changeClassification == ChangeClassification.AddedObject &&
            detection.localVerifierConfidence >= LOCAL_VERIFIER_CONFIDENCE_THRESHOLD

    private fun clearOrVerifierMessage(detection: DetectionResult, policy: ClearancePolicy): String =
        if (detection.occupancyScore >= policy.leftoverThreshold) {
            when (detection.changeClassification) {
                ChangeClassification.RemovedReferenceObject ->
                    "Change ignored: local verifier says a reference object was removed."
                ChangeClassification.LightingChange ->
                    "Change ignored: local verifier says this looks like lighting or shadow."
                ChangeClassification.MixedChange ->
                    "Change ignored: local verifier could not confirm an added object."
                ChangeClassification.None ->
                    "Change ignored: local verifier found no added object."
                ChangeClassification.AddedObject ->
                    "Change ignored: local verifier confidence is too low."
            }
        } else {
            "Table matches the empty reference. Ready for next packing."
        }

    private fun unavailable(
        previous: MonitoringSnapshot,
        detection: DetectionResult,
        nowMillis: Long,
        message: String,
    ): MonitoringSnapshot =
        previous.transitionTo(
            state = MonitoringState.SystemUnavailable,
            nowMillis = nowMillis,
            detection = detection,
            packingStartedAtMillis = null,
            quietSinceMillis = null,
            scanStartedAtMillis = null,
            clearSinceMillis = null,
            alertStartedAtMillis = null,
            dismissed = false,
            message = message,
        )

    private fun MonitoringSnapshot.transitionTo(
        state: MonitoringState,
        nowMillis: Long,
        detection: DetectionResult,
        packingStartedAtMillis: Long?,
        quietSinceMillis: Long?,
        scanStartedAtMillis: Long?,
        clearSinceMillis: Long?,
        alertStartedAtMillis: Long?,
        dismissed: Boolean,
        message: String?,
    ): MonitoringSnapshot =
        copy(
            state = state,
            occupancyScore = detection.occupancyScore,
            motionScore = detection.motionScore,
            largestChangedRegionScore = detection.largestChangedRegionScore,
            addedObjectScore = detection.addedObjectScore,
            removedObjectScore = detection.removedObjectScore,
            localVerifierConfidence = detection.localVerifierConfidence,
            changeClassification = detection.changeClassification,
            changedRegionBounds = detection.changedRegionBounds,
            stateChangedAtMillis = if (this.state == state) stateChangedAtMillis else nowMillis,
            packingStartedAtMillis = packingStartedAtMillis,
            quietSinceMillis = quietSinceMillis,
            scanStartedAtMillis = scanStartedAtMillis,
            clearSinceMillis = clearSinceMillis,
            alertStartedAtMillis = alertStartedAtMillis,
            alertDismissedForCurrentOccupancy = dismissed,
            lastQuality = detection.quality,
            alertMessage = message,
        )

    private fun MonitoringSnapshot.copyWithDetection(
        detection: DetectionResult,
        quietSinceMillis: Long? = this.quietSinceMillis,
        scanStartedAtMillis: Long? = this.scanStartedAtMillis,
        clearSinceMillis: Long? = this.clearSinceMillis,
        alertMessage: String? = this.alertMessage,
    ): MonitoringSnapshot =
        copy(
            occupancyScore = detection.occupancyScore,
            motionScore = detection.motionScore,
            largestChangedRegionScore = detection.largestChangedRegionScore,
            addedObjectScore = detection.addedObjectScore,
            removedObjectScore = detection.removedObjectScore,
            localVerifierConfidence = detection.localVerifierConfidence,
            changeClassification = detection.changeClassification,
            changedRegionBounds = detection.changedRegionBounds,
            quietSinceMillis = quietSinceMillis,
            scanStartedAtMillis = scanStartedAtMillis,
            clearSinceMillis = clearSinceMillis,
            lastQuality = detection.quality,
            alertMessage = alertMessage,
        )

    private fun MonitoringSnapshot.toDetectionResult(): DetectionResult =
        DetectionResult(
            zoneId = zoneId,
            occupancyScore = occupancyScore,
            motionScore = motionScore,
            largestChangedRegionScore = largestChangedRegionScore,
            addedObjectScore = addedObjectScore,
            removedObjectScore = removedObjectScore,
            localVerifierConfidence = localVerifierConfidence,
            changeClassification = changeClassification,
            changedRegionBounds = changedRegionBounds,
            isMotionStable = motionScore < MOTION_STABLE_THRESHOLD,
            quality = lastQuality,
            analysisLatencyMs = 0,
        )

    private fun qualityMessage(quality: DetectionQuality): String =
        when (quality) {
            DetectionQuality.Valid -> ""
            DetectionQuality.NoReference -> "Capture an empty reference before monitoring."
            DetectionQuality.TooDark -> "Camera image is too dark. Manual check required."
            DetectionQuality.TooBlurred -> "Camera image is too blurred. Manual check required."
            DetectionQuality.LensCovered -> "Camera lens appears covered. Manual check required."
            DetectionQuality.ReferenceMisaligned -> "Reference no longer matches the camera view. Recalibrate."
            DetectionQuality.AnalyzerUnavailable -> "Camera analyzer unavailable. Manual check required."
        }

    private fun emptyDetection(zoneId: String): DetectionResult =
        DetectionResult(
            zoneId = zoneId,
            occupancyScore = 0f,
            motionScore = 0f,
            largestChangedRegionScore = 0f,
            addedObjectScore = 0f,
            removedObjectScore = 0f,
            localVerifierConfidence = 0f,
            changeClassification = ChangeClassification.None,
            changedRegionBounds = null,
            isMotionStable = true,
            quality = DetectionQuality.Valid,
            analysisLatencyMs = 0,
        )

    private companion object {
        const val MOTION_STABLE_THRESHOLD = 0.045f
        const val MOTION_ACTIVITY_THRESHOLD = 0.055f
        const val LOCAL_VERIFIER_CONFIDENCE_THRESHOLD = 0.55f
    }
}
