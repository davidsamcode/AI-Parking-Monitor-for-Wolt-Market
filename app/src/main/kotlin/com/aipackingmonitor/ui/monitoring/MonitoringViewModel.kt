package com.aipackingmonitor.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aipackingmonitor.data.AlertEventEntity
import com.aipackingmonitor.data.AlertEventRepository
import com.aipackingmonitor.data.ConfiguredMonitoringZone
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
import com.aipackingmonitor.domain.model.ZoneType
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
                    val savedAdditionalZones = normalized.toAdditionalZoneStates(current.additionalZones)
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
                        additionalZones = savedAdditionalZones,
                        draftZoneBounds = if (current.areaSetupActive) {
                            current.draftZoneBounds
                        } else {
                            when (current.areaSetupTarget) {
                                AreaSetupTarget.Table -> savedBounds
                                AreaSetupTarget.Cart -> savedCartBounds
                                AreaSetupTarget.Additional ->
                                    savedAdditionalZones.firstOrNull {
                                        it.zone.id == current.areaSetupZoneId
                                    }?.zone?.bounds ?: savedBounds
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

    fun requestAdditionalReferenceCapture(zoneId: String) {
        val current = _uiState.value
        val zoneState = current.additionalZones.firstOrNull { it.zone.id == zoneId } ?: return
        if (!current.cameraPermissionGranted) {
            _uiState.update { it.copy(lastMessage = "Camera permission is required.") }
            return
        }
        _uiState.update {
            it.copy(
                additionalZones = it.additionalZones.map { state ->
                    if (state.zone.id == zoneId) {
                        state.copy(referenceCaptureRequest = state.referenceCaptureRequest + 1)
                    } else {
                        state
                    }
                },
                lastMessage = "Hold ${zoneState.zone.name.lowercase(Locale.US)} empty. Capturing the next stable frame.",
            )
        }
    }

    fun onReferenceCaptureResult(zoneId: String, success: Boolean) {
        val now = now()
        _uiState.update { current ->
            when (zoneId) {
                current.zone.id -> {
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

                current.cartZone.id -> {
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

                else -> {
                    val zoneState = current.additionalZones.firstOrNull { it.zone.id == zoneId }
                        ?: return@update current
                    current.copy(
                        additionalZones = current.additionalZones.map { state ->
                            if (state.zone.id == zoneId) {
                                state.copy(
                                    snapshot = if (success) {
                                        stateMachine.onReferenceCaptured(state.snapshot, now)
                                    } else {
                                        MonitoringSnapshot.initial(state.zone.id, now)
                                    },
                                    referenceReady = success,
                                    present = success || state.zone.type != ZoneType.Tote,
                                )
                            } else {
                                state
                            }
                        },
                        lastMessage = if (success) {
                            "Empty reference captured for ${zoneState.zone.name}."
                        } else {
                            "Reference capture failed for ${zoneState.zone.name}. Improve lighting and try again."
                        },
                    )
                }
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

                else -> {
                    val zoneState = current.additionalZones.firstOrNull { it.zone.id == zoneId }
                        ?: return@update current
                    if (zoneState.referenceReady) {
                        current
                    } else {
                        current.copy(
                            additionalZones = current.additionalZones.map { state ->
                                if (state.zone.id == zoneId) {
                                    state.copy(
                                        snapshot = stateMachine.onReferenceCaptured(state.snapshot, now),
                                        referenceReady = true,
                                        present = state.zone.type != ZoneType.Tote || state.present,
                                    )
                                } else {
                                    state
                                }
                            },
                            lastMessage = "Saved reference loaded for ${zoneState.zone.name}.",
                        )
                    }
                }
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
                    additionalZones = it.additionalZones.map { zoneState ->
                        zoneState.copy(
                            snapshot = stateMachine.onPaused(zoneState.snapshot, now),
                        )
                    },
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
                additionalZones = it.additionalZones.map { zoneState ->
                    if (zoneState.referenceReady) {
                        zoneState.copy(
                            snapshot = stateMachine.onReferenceCaptured(zoneState.snapshot, now),
                        )
                    } else {
                        zoneState
                    }
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
            else -> onAdditionalDetection(current, result)
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

    private fun onAdditionalDetection(current: MonitoringUiState, result: DetectionResult) {
        val zoneState = current.additionalZones.firstOrNull { it.zone.id == result.zoneId } ?: return
        if (!zoneState.referenceReady) return

        val now = now()
        val cartAway = zoneState.zone.type == ZoneType.Tote && isCartAway(result)
        val next = if (cartAway) {
            cartAwaySnapshot(
                previous = zoneState.snapshot,
                detection = result,
                nowMillis = now,
            )
        } else {
            stateMachine.reduce(
                previous = zoneState.snapshot,
                zone = zoneState.zone,
                detection = result,
                nowMillis = now,
                policyOverride = current.settings.toPolicy(),
            )
        }

        _uiState.update {
            it.copy(
                additionalZones = it.additionalZones.map { state ->
                    if (state.zone.id == result.zoneId) {
                        state.copy(
                            snapshot = next,
                            present = !cartAway,
                        )
                    } else {
                        state
                    }
                },
                lastMessage = if (cartAway) {
                    "${zoneState.zone.name} is away or not parked in its reference position. Zone ignored."
                } else {
                    next.alertMessage
                },
            )
        }
        handleTransition(zoneState.snapshot, next, zoneState.zone, current.settings, now)
    }

    fun dismissAlert() {
        val current = _uiState.value
        val eventId = activeEventIds.values.firstOrNull()
        stopAllActiveAlerts(now(), dismissed = true, markedCorrect = null)
        _uiState.update {
            it.copy(
                snapshot = stateMachine.dismissAlert(current.snapshot, now()),
                cartSnapshot = stateMachine.dismissAlert(current.cartSnapshot, now()),
                additionalZones = current.additionalZones.map { zoneState ->
                    zoneState.copy(
                        snapshot = stateMachine.dismissAlert(zoneState.snapshot, now()),
                    )
                },
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

    fun addAdditionalTableZone() {
        addAdditionalZone(ZoneType.PackingTable)
    }

    fun addAdditionalCartZone() {
        addAdditionalZone(ZoneType.Tote)
    }

    fun removeAdditionalZone(zoneId: String) {
        val current = _uiState.value
        val zoneState = current.additionalZones.firstOrNull { it.zone.id == zoneId } ?: return
        stopActiveAlert(zoneId, now(), dismissed = true, markedCorrect = null)
        viewModelScope.launch {
            settingsRepository.removeAdditionalZone(zoneId)
            _uiState.update {
                it.copy(
                    additionalZones = it.additionalZones.filterNot { state -> state.zone.id == zoneId },
                    areaSetupActive = if (it.areaSetupZoneId == zoneId) false else it.areaSetupActive,
                    areaSetupZoneId = if (it.areaSetupZoneId == zoneId) null else it.areaSetupZoneId,
                    lastMessage = "${zoneState.zone.name} removed.",
                )
            }
        }
    }

    private fun addAdditionalZone(type: ZoneType) {
        val current = _uiState.value
        if (current.additionalZones.size >= MAX_ADDITIONAL_ZONES) {
            _uiState.update {
                it.copy(lastMessage = "Maximum of $MAX_ADDITIONAL_ZONES additional zones reached.")
            }
            return
        }

        val sameTypeCount = current.additionalZones.count { it.zone.type == type } + 1
        val name = when (type) {
            ZoneType.PackingTable -> "Packing table ${sameTypeCount + 1}"
            ZoneType.Tote -> "Cart ${sameTypeCount + 1}"
            ZoneType.DispatchShelf -> "Zone ${sameTypeCount + 1}"
        }
        val sourceBounds = when (type) {
            ZoneType.PackingTable -> current.zone.bounds
            ZoneType.Tote -> current.cartZone.bounds
            ZoneType.DispatchShelf -> current.zone.bounds
        }
        val zone = ConfiguredMonitoringZone(
            id = "${type.name.lowercase(Locale.US)}-${UUID.randomUUID().toString().take(8)}",
            name = name,
            type = type,
            left = sourceBounds.left,
            top = sourceBounds.top,
            right = sourceBounds.right,
            bottom = sourceBounds.bottom,
        )

        viewModelScope.launch {
            settingsRepository.addAdditionalZone(zone)
            _uiState.update {
                it.copy(lastMessage = "$name added. Set its area and capture an empty reference.")
            }
        }
    }

    fun startAreaSetup() {
        val current = _uiState.value
        _uiState.update {
            it.copy(
                areaSetupActive = true,
                areaSetupTarget = AreaSetupTarget.Table,
                areaSetupZoneId = current.zone.id,
                monitoringEnabled = false,
                draftZoneBounds = current.zone.bounds,
                snapshot = stateMachine.onPaused(current.snapshot, now()),
                cartSnapshot = stateMachine.onPaused(current.cartSnapshot, now()),
                additionalZones = current.additionalZones.map { zoneState ->
                    zoneState.copy(
                        snapshot = stateMachine.onPaused(zoneState.snapshot, now()),
                    )
                },
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
                areaSetupZoneId = current.cartZone.id,
                monitoringEnabled = false,
                draftZoneBounds = current.cartZone.bounds,
                snapshot = stateMachine.onPaused(current.snapshot, now()),
                cartSnapshot = stateMachine.onPaused(current.cartSnapshot, now()),
                additionalZones = current.additionalZones.map { zoneState ->
                    zoneState.copy(
                        snapshot = stateMachine.onPaused(zoneState.snapshot, now()),
                    )
                },
                lastMessage = "Adjust the crop to cover the parked cart baskets, then save.",
            )
        }
    }

    fun startAdditionalAreaSetup(zoneId: String) {
        val current = _uiState.value
        val zoneState = current.additionalZones.firstOrNull { it.zone.id == zoneId } ?: return
        _uiState.update {
            it.copy(
                areaSetupActive = true,
                areaSetupTarget = AreaSetupTarget.Additional,
                areaSetupZoneId = zoneId,
                monitoringEnabled = false,
                draftZoneBounds = zoneState.zone.bounds,
                snapshot = stateMachine.onPaused(current.snapshot, now()),
                cartSnapshot = stateMachine.onPaused(current.cartSnapshot, now()),
                additionalZones = current.additionalZones.map { state ->
                    state.copy(
                        snapshot = stateMachine.onPaused(state.snapshot, now()),
                    )
                },
                lastMessage = "Adjust the crop for ${zoneState.zone.name}, then save.",
            )
        }
    }

    fun cancelAreaSetup() {
        _uiState.update {
            it.copy(
                areaSetupActive = false,
                areaSetupZoneId = null,
                draftZoneBounds = when (it.areaSetupTarget) {
                    AreaSetupTarget.Table -> it.zone.bounds
                    AreaSetupTarget.Cart -> it.cartZone.bounds
                    AreaSetupTarget.Additional ->
                        it.additionalZones.firstOrNull { zoneState ->
                            zoneState.zone.id == it.areaSetupZoneId
                        }?.zone?.bounds ?: it.zone.bounds
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
                    AreaSetupTarget.Additional -> "Adjust the crop for the selected zone, then save."
                },
            )
        }
    }

    fun saveAreaSetup() {
        val current = _uiState.value
        val bounds = current.draftZoneBounds
        val target = current.areaSetupTarget
        val targetZoneId = current.areaSetupZoneId
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

                AreaSetupTarget.Additional -> {
                    if (targetZoneId != null) {
                        settingsRepository.updateAdditionalZoneBounds(
                            zoneId = targetZoneId,
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                        )
                    }
                }
            }
            val now = now()
            _uiState.update {
                when (target) {
                    AreaSetupTarget.Table -> it.copy(
                        areaSetupActive = false,
                        areaSetupZoneId = null,
                        referenceReady = false,
                        monitoringEnabled = false,
                        zone = it.zone.copy(bounds = bounds),
                        snapshot = MonitoringSnapshot.initial(it.zone.id, now),
                        lastMessage = "Packing table area saved. Capture a new empty table reference.",
                    )

                    AreaSetupTarget.Cart -> it.copy(
                        areaSetupActive = false,
                        areaSetupZoneId = null,
                        cartReferenceReady = false,
                        monitoringEnabled = false,
                        cartPresent = false,
                        cartZone = it.cartZone.copy(bounds = bounds),
                        cartSnapshot = MonitoringSnapshot.initial(it.cartZone.id, now),
                        lastMessage = "Cart area saved. Capture a new empty cart reference with the cart parked.",
                    )

                    AreaSetupTarget.Additional -> {
                        val zoneState = it.additionalZones.firstOrNull { state ->
                            state.zone.id == targetZoneId
                        }
                        it.copy(
                            areaSetupActive = false,
                            areaSetupZoneId = null,
                            monitoringEnabled = false,
                            additionalZones = it.additionalZones.map { state ->
                                if (state.zone.id == targetZoneId) {
                                    state.copy(
                                        zone = state.zone.copy(bounds = bounds),
                                        snapshot = MonitoringSnapshot.initial(state.zone.id, now),
                                        referenceReady = false,
                                        present = state.zone.type != ZoneType.Tote,
                                    )
                                } else {
                                    state
                                }
                            },
                            lastMessage = "${zoneState?.zone?.name ?: "Zone"} area saved. Capture a new empty reference.",
                        )
                    }
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
                    addedObjectScore = snapshot.addedObjectScore,
                    removedObjectScore = snapshot.removedObjectScore,
                    localVerifierDecision = snapshot.changeClassification.name,
                    localVerifierConfidence = snapshot.localVerifierConfidence,
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
            additionalZones = additionalZones
                .map { it.normalizedAdditionalZone() }
                .take(MAX_ADDITIONAL_ZONES),
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

    private fun PilotSettings.toAdditionalZoneStates(
        existing: List<MonitoredZoneUiState>,
    ): List<MonitoredZoneUiState> =
        additionalZones.take(MAX_ADDITIONAL_ZONES).map { configuredZone ->
            val zone = configuredZone.normalizedAdditionalZone().toMonitoringZone(
                occupancyThreshold = entryThreshold,
                clearThreshold = clearThreshold,
            )
            val existingState = existing.firstOrNull { it.zone.id == zone.id }
            if (existingState != null) {
                existingState.copy(zone = zone)
            } else {
                MonitoredZoneUiState(
                    zone = zone,
                    snapshot = MonitoringSnapshot.initial(zone.id, 0),
                    referenceReady = false,
                    present = zone.type != ZoneType.Tote,
                )
            }
        }

    private fun ConfiguredMonitoringZone.normalizedAdditionalZone(): ConfiguredMonitoringZone {
        val left = left.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val top = top.coerceIn(0f, 1f - MIN_ZONE_SIZE)
        val right = right.coerceIn(left + MIN_ZONE_SIZE, 1f)
        val bottom = bottom.coerceIn(top + MIN_ZONE_SIZE, 1f)
        return copy(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
        )
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
        ) + String.format(
            Locale.US,
            " Local verifier: %s %.0f%% confidence, added %.0f%%, removed %.0f%%.",
            snapshot.changeClassification.name,
            snapshot.localVerifierConfidence * 100f,
            snapshot.addedObjectScore * 100f,
            snapshot.removedObjectScore * 100f,
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
            addedObjectScore = detection.addedObjectScore,
            removedObjectScore = detection.removedObjectScore,
            localVerifierConfidence = detection.localVerifierConfidence,
            changeClassification = detection.changeClassification,
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
        const val MAX_ADDITIONAL_ZONES = 4
        const val CART_AWAY_OCCUPANCY_THRESHOLD = 0.35f
        const val CART_AWAY_REGION_THRESHOLD = 0.28f
    }
}
