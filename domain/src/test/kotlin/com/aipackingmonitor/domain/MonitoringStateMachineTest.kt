package com.aipackingmonitor.domain

import com.aipackingmonitor.domain.model.ClearancePolicy
import com.aipackingmonitor.domain.model.DetectionQuality
import com.aipackingmonitor.domain.model.DetectionResult
import com.aipackingmonitor.domain.model.MonitoringSnapshot
import com.aipackingmonitor.domain.model.MonitoringState
import com.aipackingmonitor.domain.model.MonitoringZone
import com.aipackingmonitor.domain.model.NormalizedRect
import com.aipackingmonitor.domain.model.ZoneType
import kotlin.test.Test
import kotlin.test.assertEquals

class MonitoringStateMachineTest {
    private val zone = MonitoringZone(
        id = "packing-table",
        name = "Packing table",
        type = ZoneType.PackingTable,
        bounds = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f),
        occupancyThreshold = 0.07f,
        clearThreshold = 0.03f,
        stabilityDurationMs = 2_500,
    )

    private val policy = ClearancePolicy(
        stoppedDurationMs = 3_000,
        postPackScanDurationMs = 1_000,
    )
    private val stateMachine = MonitoringStateMachine(policy)

    @Test
    fun `packing activity never alerts directly`() {
        val ready = readyAt(1_000)

        val packing = stateMachine.reduce(
            previous = ready,
            zone = zone,
            detection = detection(score = 0.38f, motion = 0.12f, largestRegion = 0.24f, stable = false),
            nowMillis = 1_300,
        )
        val stillPacking = stateMachine.reduce(
            previous = packing,
            zone = zone,
            detection = detection(score = 0.31f, motion = 0.08f, largestRegion = 0.2f, stable = false),
            nowMillis = 9_300,
        )

        assertEquals(MonitoringState.PackingActive, packing.state)
        assertEquals(MonitoringState.PackingActive, stillPacking.state)
        assertEquals(null, stillPacking.alertStartedAtMillis)
    }

    @Test
    fun `bag removal waits three seconds before post pack scan`() {
        val packing = packingAt(1_300)
        val candidate = stateMachine.reduce(
            previous = packing,
            zone = zone,
            detection = detection(score = 0.06f, motion = 0.01f, largestRegion = 0.03f),
            nowMillis = 2_000,
        )
        val stillWaiting = stateMachine.reduce(
            previous = candidate,
            zone = zone,
            detection = detection(score = 0.055f, motion = 0.01f, largestRegion = 0.03f),
            nowMillis = 4_900,
        )
        val scanning = stateMachine.reduce(
            previous = stillWaiting,
            zone = zone,
            detection = detection(score = 0.055f, motion = 0.01f, largestRegion = 0.03f),
            nowMillis = 5_000,
        )

        assertEquals(MonitoringState.BagRemovedCandidate, candidate.state)
        assertEquals(MonitoringState.BagRemovedCandidate, stillWaiting.state)
        assertEquals(MonitoringState.PostPackScan, scanning.state)
    }

    @Test
    fun `post pack scan alerts only when leftover remains`() {
        val scanning = postPackScanAt(5_000)
        val changedRegion = NormalizedRect(0.3f, 0.3f, 0.45f, 0.45f)

        val alerting = stateMachine.reduce(
            previous = scanning,
            zone = zone,
            detection = detection(
                score = 0.08f,
                motion = 0.01f,
                largestRegion = 0.035f,
                changedRegion = changedRegion,
            ),
            nowMillis = 6_100,
        )

        assertEquals(MonitoringState.LeftoverAlert, alerting.state)
        assertEquals(6_100, alerting.alertStartedAtMillis)
        assertEquals(changedRegion, alerting.changedRegionBounds)
    }

    @Test
    fun `post pack scan alerts when a large item remains after motion stops`() {
        val scanning = postPackScanAt(5_000)

        val alerting = stateMachine.reduce(
            previous = scanning,
            zone = zone,
            detection = detection(score = 0.24f, motion = 0.01f, largestRegion = 0.2f),
            nowMillis = 6_100,
        )

        assertEquals(MonitoringState.LeftoverAlert, alerting.state)
    }

    @Test
    fun `leftover alert continues while leftover remains without movement`() {
        val scanning = postPackScanAt(5_000)
        val alerting = stateMachine.reduce(
            previous = scanning,
            zone = zone,
            detection = detection(score = 0.24f, motion = 0.01f, largestRegion = 0.2f),
            nowMillis = 6_100,
        )

        val stillAlerting = stateMachine.reduce(
            previous = alerting,
            zone = zone,
            detection = detection(score = 0.26f, motion = 0.01f, largestRegion = 0.22f),
            nowMillis = 7_000,
        )

        assertEquals(MonitoringState.LeftoverAlert, stillAlerting.state)
        assertEquals(alerting.alertStartedAtMillis, stillAlerting.alertStartedAtMillis)
    }

    @Test
    fun `leftover alert stops when movement starts so scan can restart`() {
        val scanning = postPackScanAt(5_000)
        val alerting = stateMachine.reduce(
            previous = scanning,
            zone = zone,
            detection = detection(score = 0.24f, motion = 0.01f, largestRegion = 0.2f),
            nowMillis = 6_100,
        )

        val packing = stateMachine.reduce(
            previous = alerting,
            zone = zone,
            detection = detection(score = 0.24f, motion = 0.09f, largestRegion = 0.2f, stable = false),
            nowMillis = 7_000,
        )

        assertEquals(MonitoringState.PackingActive, packing.state)
        assertEquals(null, packing.alertStartedAtMillis)
    }

    @Test
    fun `post pack scan resets when table matches reference`() {
        val scanning = postPackScanAt(5_000)

        val clear = stateMachine.reduce(
            previous = scanning,
            zone = zone,
            detection = detection(score = 0.015f, motion = 0.01f, largestRegion = 0.01f),
            nowMillis = 6_100,
        )

        assertEquals(MonitoringState.ClearReset, clear.state)
        assertEquals(null, clear.alertStartedAtMillis)
    }

    @Test
    fun `movement during scan returns to packing active`() {
        val scanning = postPackScanAt(5_000)

        val packing = stateMachine.reduce(
            previous = scanning,
            zone = zone,
            detection = detection(score = 0.16f, motion = 0.09f, largestRegion = 0.05f, stable = false),
            nowMillis = 5_500,
        )

        assertEquals(MonitoringState.PackingActive, packing.state)
    }

    @Test
    fun `invalid detector result fails visibly`() {
        val ready = readyAt(1_000)

        val unavailable = stateMachine.reduce(
            previous = ready,
            zone = zone,
            detection = detection(score = 0f, quality = DetectionQuality.LensCovered),
            nowMillis = 1_500,
        )

        assertEquals(MonitoringState.SystemUnavailable, unavailable.state)
    }

    private fun readyAt(now: Long): MonitoringSnapshot =
        stateMachine.onReferenceCaptured(
            MonitoringSnapshot.initial(zone.id, 0),
            nowMillis = now,
        )

    private fun packingAt(now: Long): MonitoringSnapshot =
        stateMachine.reduce(
            previous = readyAt(1_000),
            zone = zone,
            detection = detection(score = 0.38f, motion = 0.12f, largestRegion = 0.24f, stable = false),
            nowMillis = now,
        )

    private fun postPackScanAt(now: Long): MonitoringSnapshot {
        val packing = packingAt(1_300)
        val candidate = stateMachine.reduce(
            previous = packing,
            zone = zone,
            detection = detection(score = 0.06f, motion = 0.01f, largestRegion = 0.03f),
            nowMillis = now - policy.stoppedDurationMs,
        )
        return stateMachine.reduce(
            previous = candidate,
            zone = zone,
            detection = detection(score = 0.06f, motion = 0.01f, largestRegion = 0.03f),
            nowMillis = now,
        )
    }

    private fun detection(
        score: Float,
        motion: Float = 0.01f,
        largestRegion: Float = score,
        quality: DetectionQuality = DetectionQuality.Valid,
        stable: Boolean = true,
        changedRegion: NormalizedRect? = null,
    ): DetectionResult =
        DetectionResult(
            zoneId = zone.id,
            occupancyScore = score,
            motionScore = motion,
            largestChangedRegionScore = largestRegion,
            isMotionStable = stable,
            quality = quality,
            analysisLatencyMs = 12,
            changedRegionBounds = changedRegion,
        )
}
