package com.aipackingmonitor.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aipackingmonitor.data.AlertEventEntity
import com.aipackingmonitor.data.AlertEventRepository
import com.aipackingmonitor.data.PilotSettings
import com.aipackingmonitor.data.SettingsRepository
import com.aipackingmonitor.device.AlertController
import com.aipackingmonitor.domain.MonitoringStateMachine
import com.aipackingmonitor.domain.model.ClearancePolicy
import com.aipackingmonitor.domain.model.DetectionResult
import com.aipackingmonitor.domain.model.MonitoringSnapshot
import com.aipackingmonitor.domain.model.MonitoringState
import com.aipackingmonitor.domain.model.MonitoringZone
import com.aipackingmonitor.domain.model.NormalizedRect
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    private val stateMachine: MonitoringStateMachine,
    private val alertEventRepository: AlertEventRepository,
    private val settingsRepository: SettingsRepository,
    private val alertController: AlertController,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MonitoringUiState())
    val uiState = _uiState.asStateFlow()

    private var alertLoopJob: Job? = null
    private val activeEventIds = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                _uiState.update { current ->
                    val normalized = settings.normalized()
                    val savedBounds = normalized.zoneBounds()
                    val savedCartBounds = normalized.cartZoneBounds()
                    current.copy(
                        settings = normalized,
                        zone = current.zone.copy(
                            bounds = savedBounds,
                            occupancyThreshold = normalized.entryThreshold,
                            clearThreshold = normalized.clearThreshold,
                        ),
                        cartZone = current.cartZone.copy(
                            bounds = savedCartBounds,
                            occupancyThreshold = normalized.entryThreshold,
                            clearThreshold = normalized.clearThreshold,
                        ),
                        draftZoneBounds = if (current.areaSetupActive) {
                            current.draftZoneBounds
                        } else {
                            when (current.areaSetupTarget) {
                                AreaSetupTarget.Table -> savedBounds
                                AreaSetupTarget.Cart -> savedCartBounds
                            }
                        },
                    )
                }
            }
        }

        viewModelScope.launch {
            alertEventRepository.observeSummary().collectLatest { summary ->
                _uiState.update { it.copy(summary = summary) }
            }
        }

        viewModelScope.launch {
            alertEventRepository.observeEvents()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
                .collectLatest { events ->
                    _uiState.update { it.copy(recentEvents = events.take(5)) }
                }
        }
    }

    fun onCameraPermissionChanged(granted: Boolean) {
        _uiState.update { it.copy(cameraPermissionGranted = granted) }
    }

    fun requestReferenceCapture() {
        val current = _uiState.value
        if (!current.cameraPermissionGranted) {
            _uiState.update { it.copy(lastMessage = "Camera permission is required.") }
            return
        }
        _uiState.update {
            it.copy(
                referenceCaptureRequest = it.referenceCaptureRequest + 1,
                lastMessage = "Hold the station empty. Capturing the next stable frame.",
            )
        }
    }

    fun requestCartReferenceCapture() {
        val current = _uiState.value
        if (!current.cameraPermissionGranted) {
            _uiState.update { it.copy(lastMessage = "Camera permission is required.") }
            return
        }
        _uiState.update {
            it.copy(
                cartReferenceCaptureRequest = it.cartReferenceCaptureRequest + 1,
                lastMessage = "Park the empty cart in its marked spot. Capturing the next stable frame.",
            )
        }
    }

    fun onReferenceCaptureResult(success: Boolean) {
        val now = now()
        _uiState.update { current ->
            if (success) {
                current.copy(
                    snapshot = stateMachine.onReferenceCaptured(current.snapshot, now),
                    referenceReady = true,
                    monitoringEnabled = true,
                    lastMessage = "Empty reference captured. Monitoring is active.",
                )
            } else {
                current.copy(
                    referenceReady = false,
                    monitoringEnabled = false,
                    lastMessage = "Reference capture failed. Improve lighting and try again.",
                )
            }
        }
    }

    fun onCartReferenceCaptureResult(success: Boolean) {
        val now = now()
        _uiState.update { current ->
            if (success) {
                current.copy(
                    cartSnapshot = stateMachine.onReferenceCaptured(current.cartSnapshot, now),
                    cartReferenceReady = true,
                    cartPresent = true,
                    lastMessage = "Empty cart reference captured. Cart monitoring will run when the cart is parked.",
                )
            } else {
                current.copy(
                    cartReferenceReady = false,
                    cartPresent = false,
                    lastMessage = "Cart reference capture failed. Park the empty cart clearly and try again.",
                )
            }
        }
    }

    fun onReferenceAvailabilityChanged(zoneId: String, available: Boolean) {
        if (!available) return

        val now = now()
        _uiState.update { current ->
            when (zoneId) {
                current.zone.id -> {
                    if (current.referenceReady) {
                        current
                    } else {
                        current.copy(
                            snapshot = stateMachine.onReferenceCaptured(current.snapshot, now),
                            referenceReady = true,
                            lastMessage = "Saved empty table reference loaded. Press Start when ready.",
                        )
                    }
                }

                current.cartZone.id -> {
                    if (current.cartReferenceReady) {
                        current
                    } else {
                        current.copy(
                            cartSnapshot = stateMachine.onReferenceCaptured(current.cartSnapshot, now),
                            cartReferenceReady = true,
                            lastMessage = "Saved empty cart reference loaded.",
                        )
                    }
                }

                else -> current
            }
        }
    }

    fun onReferenceAvailabilityChanged(available: Boolean) {
        onReferenceAvailabilityChanged(_uiState.value.zone.id, available)
    }

    fun toggleMonitoring() {
        val current = _uiState.value
        val now = now()
        if (current.monitoringEnabled) {
            stopAllActiveAlerts(now, dismissed = true, markedCorrect = null)
            _uiState.update {
                it.copy(
                    monitoringEnabled = false,
                    snapshot = stateMachine.onPaused(it.snapshot, now),
                    cartSnapshot = stateMachine.onPaused(it.cartSnapshot, now),
                    lastMessage = "Monitoring paused.",
                )
            }
            return
        }

        if (!current.referenceReady) {
            _uiState.update { it.copy(lastMessage = "Capture an empty reference first.") }
            return
        }

        _uiState.update {
            it.copy(
                monitoringEnabled = true,
                snapshot = stateMachine.onReferenceCaptured(it.snapshot, now),
                cartSnapshot = if (it.cartReferenceReady) {
                    stateMachine.onReferenceCaptured(it.cartSnapshot, now)
                } else {
                    it.cartSnapshot
                },
                lastMessage = "Monitoring resumed.",
            )
        }
    }

    fun onDetection(result: DetectionResult) {
        val current = _uiState.value
        if (!current.monitoringEnabled) return

        when (result.zoneId) {
            current.zone.id -> onTableDetection(current, result)
            current.cartZone.id -> onCartDetection(current, result)
        }
    }

    private fun onTableDetection(current: MonitoringUiState, result: DetectionResult) {
        if (!current.referenceReady) return

        val now = now()
        val next = stateMachine.reduce(
            previous = current.snapshot,
            zone = current.zone,
            detection = result,
            nowMillis = now,
            policyOverride = current.settings.toPolicy(),
        )

        _uiState.update { it.copy(snapshot = next, lastMessage = next.alertMessage) }
        handleTransition(current.snapshot, next, current.zone, current.settings, now)
    }

    private fun onCartDetection(current: MonitoringUiState, result: DetectionResult) {
        if (!current.cartReferenceReady) return

        val now = now()
        val cartAway = isCartAway(result)
        val next = if (cartAway) {
            cartAwaySnapshot(
                previous = current.cartSnapshot,
                detection = result,
                nowMillis = now,
            )
        } else {
            stateMachine.reduce(
                previous = current.cartSnapshot,
                zone = current.cartZone,
                detection = result,
                nowMillis = now,
                policyOverride = current.settings.toPolicy(),
            )
        }

        _uiState.update {
            it.copy(
                cartSnapshot = next,
                cartPresent = !cartAway,
                lastMessage = if (cartAway) {
                    "Cart is away or not parked in its reference position. Cart zone ignored."
                } else {
                    next.alertMessage
                },
            )
        }
        handleTransition(current.cartSnapshot, next, current.cartZone, current.settings, now)
    }

    fun dismissAlert() {
        val current = _uiState.value
        val eventId = activeEventIds.values.firstOrNull()
        stopAllActiveAlerts(now(), dismissed = true, markedCorrect = null)
        _uiState.update {
            it.copy(
                snapshot = stateMachine.dismissAlert(current.snapshot, now()),
                cartSnapshot = stateMachine.dismissAlert(current.cartSnapshot, now()),
                awaitingFeedbackEventId = eventId,
                lastMessage = "Alert dismissed. Mark whether it was correct.",
            )
        }
    }

    fun markFeedback(correct: Boolean) {
        val eventId = _uiState.value.awaitingFeedbackEventId ?: return
        viewModelScope.launch {
            alertEventRepository.markFeedback(eventId, correct)
            _uiState.update {
                it.copy(
                    awaitingFeedbackEventId = null,
                    lastMessage = if (correct) "Feedback saved as correct." else "Feedback saved as false alarm.",
                )
            }
        }
    }

    fun updateEntryThreshold(value: Float) {
        viewModelScope.launch { settingsRepository.updateEntryThreshold(value) }
    }

    fun updateClearThreshold(value: Float) {
        viewModelScope.launch { settingsRepository.updateClearThreshold(value) }
    }

    fun updateAlertDelay(valueMs: Long) {
        viewModelScope.launch { settingsRepository.updateAlertDelay(valueMs) }
    }

    fun updateAlarmVolume(value: Int) {
        viewModelScope.launch { settingsRepository.updateAlarmVolume(value) }
    }

    fun updateVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateVibrationEnabled(enabled) }
    }

    fun startAreaSetup() {
        val current = _uiState.value
        _uiState.update {
            it.copy(
                areaSetupActive = true,
                areaSetupTarget = AreaSetupTarget.Table,
                monitoringEnabled = false,
                draftZoneBounds = current.zone.bounds,
                snapshot = stateMachine.onPaused(current.snapshot, now()),
                cartSnapshot = stateMachine.onPaused(current.cartSnapshot, now()),
                lastMessage = "Adjust the crop to cover only the packing table, then save.",
            )
        }
    }

    fun startCartAreaSetup() {
        val current = _uiState.value
        _uiState.update {
            it.copy(
                areaSetupActive = true,
                areaSetupTarget = AreaSetupTarget.Cart,
                monitoringEnabled = false,
                draftZoneBounds = current.cartZone.bounds,
                snapshot = stateMachine.onPaused(current.snapshot, now()),
                cartSnapshot = stateMachine.onPaused(current.cartSnapshot, now()),
                lastMessage = "Adjust the crop to cover the parked cart baskets, then save.",
            )
        }
    }

    fun cancelAreaSetup() {
        _uiState.update {
            it.copy(
                areaSetupActive = false,
                draftZoneBounds = when (it.areaSetupTarget) {
                    AreaSetupTarget.Table -> it.zone.bounds
                    AreaSetupTarget.Cart -> it.cartZone.bounds
                },
                lastMessage = "Area setup cancelled.",
            )
        }
    }

    fun updateDraftZoneBounds(bounds: NormalizedRect) {
        _uiState.update {
            it.copy(
                draftZoneBounds = bounds,
                lastMessage = when (it.areaSetupTarget) {
                    AreaSetupTarget.Table -> "Adjust the crop to cover only the packing table, then save."
                    AreaSetupTarget.Cart -> "Adjust the crop to cover the parked cart baskets, then save."
                },
            )
        }
    }

    fun saveAreaSetup() {
        val current = _uiState.value
        val bounds = current.draftZoneBounds
        val target = current.areaSetupTarget
        viewModelScope.launch {
            when (target) {
                AreaSetupTarget.Table -> settingsRepository.updateZoneBounds(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                )

                AreaSetupTarget.Cart -> settingsRepository.updateCartZoneBounds(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                )
            }
            val now = now()
            _uiState.update {
                when (target) {
                    AreaSetupTarget.Table -> it.copy(
                        areaSetupActive = false,
                        referenceReady = false,
                        monitoringEnabled = false,
                        zone = it.zone.copy(bounds = bounds),
                        snapshot = MonitoringSnapshot.initial(it.zone.id, now),
                        lastMessage = "Packing table area saved. Capture a new empty table reference.",
                    )

                    AreaSetupTarget.Cart -> it.copy(
                        areaSetupActive = false,
                        cartReferenceReady = false,
                        monitoringEnabled = false,
                        cartPresent = false,
                        cartZone = it.cartZone.copy(bounds = bounds),
                        cartSnapshot = MonitoringSnapshot.initial(it.cartZone.id, now),
                        lastMessage = "Cart area saved. Capture a new empty cart reference with the cart parked.",
                    )
                }
            }
        }
    }

    override fun onCleared() {
        alertLoopJob?.cancel()
        activeEventIds.clear()
        alertController.stop()
        super.onCleared()
    }

    private fun handleTransition(
        previous: MonitoringSnapshot,
        next: MonitoringSnapshot,
        zone: MonitoringZone,
        settings: PilotSettings,
        now: Long,
    ) {
        if (previous.state != MonitoringState.LeftoverAlert && next.state == MonitoringState.LeftoverAlert) {
            startAlert(next, zone, settings, now)
        }

        if (previous.state == MonitoringState.LeftoverAlert && next.state != MonitoringState.LeftoverAlert) {
            stopActiveAlert(zone.id, now, dismissed = false, markedCorrect = null)
        }
    }

    private fun startAlert(
        snapshot: MonitoringSnapshot,
        zone: MonitoringZone,
        settings: PilotSettings,
        now: Long,
    ) {
        if (activeEventIds.containsKey(zone.id)) return

        val eventId = UUID.randomUUID().toString()
        activeEventIds[zone.id] = eventId
        val timeToAlert = snapshot.scanStartedAtMillis?.let { now - it }
            ?: snapshot.packingStartedAtMillis?.let { now - it }
            ?: 0
        val policy = settings.toPolicy()
        val changedRegion = snapshot.changedRegionBounds
        viewModelScope.launch {
            alertEventRepository.recordAlertStart(
                AlertEventEntity(
                    id = eventId,
                    zoneId = zone.id,
                    zoneName = zone.name,
                    startedAtMillis = now,
                    endedAtMillis = null,
                    occupancyScore = snapshot.occupancyScore,
                    timeToAlertMs = timeToAlert,
                    dismissed = false,
                    markedCorrect = null,
                    triggerReason = alertReason(zone, snapshot, policy),
                    motionScore = snapshot.motionScore,
                    largestChangedRegionScore = snapshot.largestChangedRegionScore,
                    clearThreshold = policy.exitThreshold,
                    leftoverThreshold = policy.leftoverThreshold,
                    changedRegionLeft = changedRegion?.left,
                    changedRegionTop = changedRegion?.top,
                    changedRegionRight = changedRegion?.right,
                    changedRegionBottom = changedRegion?.bottom,
                ),
            )
        }
        ensureAlertLoop(settings)
    }

    private fun ensureAlertLoop(settings: PilotSettings) {
        if (alertLoopJob?.isActive == true) return
        alertLoopJob?.cancel()
        alertLoopJob = viewModelScope.launch {
            while (true) {
                alertController.pulse(
                    volumePercent = settings.alarmVolumePercent,
                    vibrate = settings.vibrationEnabled,
                )
                delay(900)
            }
        }
    }

    private fun stopActiveAlert(
        zoneId: String,
        endedAtMillis: Long,
        dismissed: Boolean,
        markedCorrect: Boolean?,
    ) {
        val eventId = activeEventIds.remove(zoneId) ?: return
        if (activeEventIds.isEmpty()) {
            alertLoopJob?.cancel()
            alertLoopJob = null
            alertController.stop()
        }
        viewModelScope.launch {
            alertEventRepository.finishAlert(
                id = eventId,
                endedAtMillis = endedAtMillis,
                dismissed = dismissed,
                markedCorrect = markedCorrect,
            )
        }
    }

    private fun stopAllActiveAlerts(
        endedAtMillis: Long,
        dismissed: Boolean,
        markedCorrect: Boolean?,
    ) {
        val eventIds = activeEventIds.values.toList()
        activeEventIds.clear()
        alertLoopJob?.cancel()
        alertLoopJob = null
        alertController.stop()

        eventIds.forEach { eventId ->
            viewModelScope.launch {
                alertEventRepository.finishAlert(
                    id = eventId,
                    endedAtMillis = endedAtMillis,
                    dismissed = dismissed,
                    markedCorrect = markedCorrect,
                )
            }
        }
    }

    private fun PilotSettings.normalized(): PilotSettings {
        val clear = clearThreshold.coerceIn(0.005f, 0.2f)
        val entry = max(entryThreshold, clear + 0.005f).coerceIn(0.01f, 0.35f)
        val left = zoneLeft.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val top = zoneTop.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val right = zoneRight.coerceIn(left + MIN_ZONE_SIZE, 1f)
        val bottom = zoneBottom.coerceIn(top + MIN_ZONE_SIZE, 1f)
        val cartLeft = cartZoneLeft.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val cartTop = cartZoneTop.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val cartRight = cartZoneRight.coerceIn(cartLeft + MIN_ZONE_SIZE, 1f)
        val cartBottom = cartZoneBottom.coerceIn(cartTop + MIN_ZONE_SIZE, 1f)
        return copy(
            entryThreshold = entry,
            clearThreshold = min(clear, entry - 0.005f),
            alertDelayMs = alertDelayMs.coerceIn(3_000, 60_000),
            alarmVolumePercent = alarmVolumePercent.coerceIn(10, 100),
            zoneLeft = left,
            zoneTop = top,
            zoneRight = right,
            zoneBottom = bottom,
            cartZoneLeft = cartLeft,
            cartZoneTop = cartTop,
            cartZoneRight = cartRight,
            cartZoneBottom = cartBottom,
        )
    }

    private fun PilotSettings.zoneBounds(): NormalizedRect {
        val left = zoneLeft.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val top = zoneTop.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val right = zoneRight.coerceIn(left + MIN_ZONE_SIZE, 1f)
        val bottom = zoneBottom.coerceIn(top + MIN_ZONE_SIZE, 1f)
        return NormalizedRect(left, top, right, bottom)
    }

    private fun PilotSettings.cartZoneBounds(): NormalizedRect {
        val left = cartZoneLeft.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val top = cartZoneTop.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val right = cartZoneRight.coerceIn(left + MIN_ZONE_SIZE, 1f)
        val bottom = cartZoneBottom.coerceIn(top + MIN_ZONE_SIZE, 1f)
        return NormalizedRect(left, top, right, bottom)
    }

    private fun PilotSettings.toPolicy(): ClearancePolicy {
        val normalized = normalized()
        return ClearancePolicy(
            entryThreshold = normalized.entryThreshold,
            exitThreshold = normalized.clearThreshold,
            packingActivityThreshold = normalized.entryThreshold,
            leftoverThreshold = max(normalized.clearThreshold + 0.015f, 0.04f),
            stoppedDurationMs = normalized.alertDelayMs,
        )
    }

    private fun alertReason(
        zone: MonitoringZone,
        snapshot: MonitoringSnapshot,
        policy: ClearancePolicy,
    ): String =
        String.format(
            Locale.US,
            "Stable %s differs from reference: occupancy %.1f%% >= leftover %.1f%%; largest region %.1f%%; motion %.1f%%.",
            zone.name.lowercase(Locale.US),
            snapshot.occupancyScore * 100f,
            policy.leftoverThreshold * 100f,
            snapshot.largestChangedRegionScore * 100f,
            snapshot.motionScore * 100f,
        )

    private fun isCartAway(detection: DetectionResult): Boolean =
        detection.quality == com.aipackingmonitor.domain.model.DetectionQuality.ReferenceMisaligned ||
            (
                detection.isMotionStable &&
                    detection.occupancyScore >= CART_AWAY_OCCUPANCY_THRESHOLD &&
                    detection.largestChangedRegionScore >= CART_AWAY_REGION_THRESHOLD
                )

    private fun cartAwaySnapshot(
        previous: MonitoringSnapshot,
        detection: DetectionResult,
        nowMillis: Long,
    ): MonitoringSnapshot =
        previous.copy(
            state = MonitoringState.SystemUnavailable,
            occupancyScore = detection.occupancyScore,
            motionScore = detection.motionScore,
            largestChangedRegionScore = detection.largestChangedRegionScore,
            changedRegionBounds = detection.changedRegionBounds,
            stateChangedAtMillis = if (previous.state == MonitoringState.SystemUnavailable) {
                previous.stateChangedAtMillis
            } else {
                nowMillis
            },
            packingStartedAtMillis = null,
            quietSinceMillis = null,
            scanStartedAtMillis = null,
            clearSinceMillis = null,
            alertStartedAtMillis = null,
            alertDismissedForCurrentOccupancy = false,
            lastQuality = detection.quality,
            alertMessage = "Cart away or not parked. Cart zone ignored.",
        )

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val MIN_ZONE_SIZE = 0.06f
        const val CART_AWAY_OCCUPANCY_THRESHOLD = 0.35f
        const val CART_AWAY_REGION_THRESHOLD = 0.28f
    }
}
