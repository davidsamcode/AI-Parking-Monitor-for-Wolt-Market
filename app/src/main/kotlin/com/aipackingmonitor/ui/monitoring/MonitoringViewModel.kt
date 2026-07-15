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
    private var activeEventId: String? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                _uiState.update { current ->
                    val normalized = settings.normalized()
                    val savedBounds = normalized.zoneBounds()
                    current.copy(
                        settings = normalized,
                        zone = current.zone.copy(
                            bounds = savedBounds,
                            occupancyThreshold = normalized.entryThreshold,
                            clearThreshold = normalized.clearThreshold,
                        ),
                        draftZoneBounds = if (current.areaSetupActive) {
                            current.draftZoneBounds
                        } else {
                            savedBounds
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

    fun onReferenceAvailabilityChanged(available: Boolean) {
        if (!available) return

        val now = now()
        _uiState.update { current ->
            if (current.referenceReady) {
                current
            } else {
                current.copy(
                    snapshot = stateMachine.onReferenceCaptured(current.snapshot, now),
                    referenceReady = true,
                    lastMessage = "Saved empty reference loaded. Press Start when ready.",
                )
            }
        }
    }

    fun toggleMonitoring() {
        val current = _uiState.value
        val now = now()
        if (current.monitoringEnabled) {
            stopActiveAlert(now, dismissed = true, markedCorrect = null)
            _uiState.update {
                it.copy(
                    monitoringEnabled = false,
                    snapshot = stateMachine.onPaused(it.snapshot, now),
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
                lastMessage = "Monitoring resumed.",
            )
        }
    }

    fun onDetection(result: DetectionResult) {
        val current = _uiState.value
        if (!current.monitoringEnabled || !current.referenceReady) return

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

    fun dismissAlert() {
        val current = _uiState.value
        val eventId = activeEventId
        stopActiveAlert(now(), dismissed = true, markedCorrect = null)
        _uiState.update {
            it.copy(
                snapshot = stateMachine.dismissAlert(current.snapshot, now()),
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
                monitoringEnabled = false,
                draftZoneBounds = current.zone.bounds,
                snapshot = stateMachine.onPaused(current.snapshot, now()),
                lastMessage = "Adjust the crop to cover only the packing area, then save.",
            )
        }
    }

    fun cancelAreaSetup() {
        _uiState.update {
            it.copy(
                areaSetupActive = false,
                draftZoneBounds = it.zone.bounds,
                lastMessage = "Packing area setup cancelled.",
            )
        }
    }

    fun updateDraftZoneBounds(bounds: NormalizedRect) {
        _uiState.update {
            it.copy(
                draftZoneBounds = bounds,
                lastMessage = "Adjust the crop to cover only the packing area, then save.",
            )
        }
    }

    fun saveAreaSetup() {
        val bounds = _uiState.value.draftZoneBounds
        viewModelScope.launch {
            settingsRepository.updateZoneBounds(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
            )
            val now = now()
            _uiState.update {
                it.copy(
                    areaSetupActive = false,
                    referenceReady = false,
                    monitoringEnabled = false,
                    zone = it.zone.copy(bounds = bounds),
                    snapshot = MonitoringSnapshot.initial(it.zone.id, now),
                    lastMessage = "Packing area saved. Capture a new empty reference for this crop.",
                )
            }
        }
    }

    override fun onCleared() {
        alertLoopJob?.cancel()
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
            stopActiveAlert(now, dismissed = false, markedCorrect = null)
        }
    }

    private fun startAlert(
        snapshot: MonitoringSnapshot,
        zone: MonitoringZone,
        settings: PilotSettings,
        now: Long,
    ) {
        if (activeEventId != null) return

        val eventId = UUID.randomUUID().toString()
        activeEventId = eventId
        val timeToAlert = snapshot.scanStartedAtMillis?.let { now - it }
            ?: snapshot.packingStartedAtMillis?.let { now - it }
            ?: 0
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
                ),
            )
        }
        startAlertLoop(settings)
    }

    private fun startAlertLoop(settings: PilotSettings) {
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
        endedAtMillis: Long,
        dismissed: Boolean,
        markedCorrect: Boolean?,
    ) {
        alertLoopJob?.cancel()
        alertLoopJob = null
        alertController.stop()

        val eventId = activeEventId ?: return
        activeEventId = null
        viewModelScope.launch {
            alertEventRepository.finishAlert(
                id = eventId,
                endedAtMillis = endedAtMillis,
                dismissed = dismissed,
                markedCorrect = markedCorrect,
            )
        }
    }

    private fun PilotSettings.normalized(): PilotSettings {
        val clear = clearThreshold.coerceIn(0.005f, 0.2f)
        val entry = max(entryThreshold, clear + 0.005f).coerceIn(0.01f, 0.35f)
        val left = zoneLeft.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val top = zoneTop.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val right = zoneRight.coerceIn(left + MIN_ZONE_SIZE, 1f)
        val bottom = zoneBottom.coerceIn(top + MIN_ZONE_SIZE, 1f)
        return copy(
            entryThreshold = entry,
            clearThreshold = min(clear, entry - 0.005f),
            alertDelayMs = alertDelayMs.coerceIn(3_000, 60_000),
            alarmVolumePercent = alarmVolumePercent.coerceIn(10, 100),
            zoneLeft = left,
            zoneTop = top,
            zoneRight = right,
            zoneBottom = bottom,
        )
    }

    private fun PilotSettings.zoneBounds(): NormalizedRect {
        val left = zoneLeft.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val top = zoneTop.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val right = zoneRight.coerceIn(left + MIN_ZONE_SIZE, 1f)
        val bottom = zoneBottom.coerceIn(top + MIN_ZONE_SIZE, 1f)
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

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val MIN_ZONE_SIZE = 0.06f
    }
}
