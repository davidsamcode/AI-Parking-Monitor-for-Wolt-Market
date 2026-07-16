package com.aipackingmonitor.ui.monitoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aipackingmonitor.data.AlertEventEntity
import com.aipackingmonitor.domain.model.MonitoringState
import com.aipackingmonitor.domain.model.NormalizedRect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MonitoringRoute(
    viewModel: MonitoringViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    KeepScreenAwake(uiState.monitoringEnabled)
    MonitoringScreen(
        uiState = uiState,
        onCameraPermissionChanged = viewModel::onCameraPermissionChanged,
        onDetection = viewModel::onDetection,
        onReferenceCaptured = viewModel::onReferenceCaptureResult,
        onCartReferenceCaptured = viewModel::onCartReferenceCaptureResult,
        onReferenceAvailabilityChanged = viewModel::onReferenceAvailabilityChanged,
        onCaptureReference = viewModel::requestReferenceCapture,
        onCaptureCartReference = viewModel::requestCartReferenceCapture,
        onToggleMonitoring = viewModel::toggleMonitoring,
        onDismissAlert = viewModel::dismissAlert,
        onMarkFeedback = viewModel::markFeedback,
        onEntryThresholdChanged = viewModel::updateEntryThreshold,
        onClearThresholdChanged = viewModel::updateClearThreshold,
        onAlertDelayChanged = viewModel::updateAlertDelay,
        onAlarmVolumeChanged = viewModel::updateAlarmVolume,
        onVibrationChanged = viewModel::updateVibrationEnabled,
        onStartAreaSetup = viewModel::startAreaSetup,
        onStartCartAreaSetup = viewModel::startCartAreaSetup,
        onCancelAreaSetup = viewModel::cancelAreaSetup,
        onDraftZoneChanged = viewModel::updateDraftZoneBounds,
        onSaveAreaSetup = viewModel::saveAreaSetup,
    )
}

@Composable
private fun KeepScreenAwake(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        view.keepScreenOn = enabled
        onDispose {
            view.keepScreenOn = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitoringScreen(
    uiState: MonitoringUiState,
    onCameraPermissionChanged: (Boolean) -> Unit,
    onDetection: (com.aipackingmonitor.domain.model.DetectionResult) -> Unit,
    onReferenceCaptured: (Boolean) -> Unit,
    onCartReferenceCaptured: (Boolean) -> Unit,
    onReferenceAvailabilityChanged: (String, Boolean) -> Unit,
    onCaptureReference: () -> Unit,
    onCaptureCartReference: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onDismissAlert: () -> Unit,
    onMarkFeedback: (Boolean) -> Unit,
    onEntryThresholdChanged: (Float) -> Unit,
    onClearThresholdChanged: (Float) -> Unit,
    onAlertDelayChanged: (Long) -> Unit,
    onAlarmVolumeChanged: (Int) -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
    onStartAreaSetup: () -> Unit,
    onStartCartAreaSetup: () -> Unit,
    onCancelAreaSetup: () -> Unit,
    onDraftZoneChanged: (NormalizedRect) -> Unit,
    onSaveAreaSetup: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI Packing Monitor",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusBanner(uiState)
            CartStatusBanner(uiState)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                CameraPermissionGate(
                    granted = uiState.cameraPermissionGranted,
                    onPermissionChanged = onCameraPermissionChanged,
                ) {
                    CameraPreviewPanel(
                        uiState = uiState,
                        onDetection = onDetection,
                        onReferenceCaptured = onReferenceCaptured,
                        onCartReferenceCaptured = onCartReferenceCaptured,
                        onReferenceAvailabilityChanged = onReferenceAvailabilityChanged,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            ControlsPanel(
                uiState = uiState,
                onCaptureReference = onCaptureReference,
                onCaptureCartReference = onCaptureCartReference,
                onToggleMonitoring = onToggleMonitoring,
                onDismissAlert = onDismissAlert,
                onStartAreaSetup = onStartAreaSetup,
                onStartCartAreaSetup = onStartCartAreaSetup,
                onCancelAreaSetup = onCancelAreaSetup,
                onSaveAreaSetup = onSaveAreaSetup,
            )

            if (uiState.areaSetupActive) {
                AreaSetupPanel(
                    bounds = uiState.draftZoneBounds,
                    target = uiState.areaSetupTarget,
                    onBoundsChanged = onDraftZoneChanged,
                )
            }

            if (uiState.awaitingFeedbackEventId != null) {
                FeedbackPanel(onMarkFeedback = onMarkFeedback)
            }

            MetricsPanel(uiState)

            SettingsPanel(
                uiState = uiState,
                onEntryThresholdChanged = onEntryThresholdChanged,
                onClearThresholdChanged = onClearThresholdChanged,
                onAlertDelayChanged = onAlertDelayChanged,
                onAlarmVolumeChanged = onAlarmVolumeChanged,
                onVibrationChanged = onVibrationChanged,
            )

            EventHistory(events = uiState.recentEvents)
        }
    }
}

@Composable
private fun StatusBanner(uiState: MonitoringUiState) {
    val state = uiState.snapshot.state
    val color = statusColor(state)
    val label = when (state) {
        MonitoringState.NeedsReference -> "Needs reference"
        MonitoringState.EmptyTable -> "Table ready"
        MonitoringState.PackingActive -> "Packing active"
        MonitoringState.BagRemovedCandidate -> "Packing stopped"
        MonitoringState.PostPackScan -> "Scanning table"
        MonitoringState.ClearReset -> "Clear"
        MonitoringState.LeftoverAlert -> "Check packing table"
        MonitoringState.SystemUnavailable -> "Manual check required"
        MonitoringState.Paused -> "Paused"
    }
    val detail = uiState.lastMessage ?: when (state) {
        MonitoringState.EmptyTable,
        MonitoringState.ClearReset -> "Monitoring ${uiState.zone.name.lowercase(Locale.US)}."
        MonitoringState.PackingActive -> "Alerts are suppressed while staff are packing."
        MonitoringState.BagRemovedCandidate -> "Waiting for 3 seconds of stable table view."
        MonitoringState.PostPackScan -> "Comparing the stable view with the empty reference."
        MonitoringState.LeftoverAlert -> "Possible item left on the packing table."
        MonitoringState.SystemUnavailable -> "Camera health or reference alignment needs attention."
        else -> "Capture a clean empty table before the next pilot run."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (state == MonitoringState.LeftoverAlert) {
                    Icons.Default.Warning
                } else {
                    Icons.Default.Check
                },
                contentDescription = null,
                tint = color,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = percent(uiState.snapshot.occupancyScore),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

@Composable
private fun CartStatusBanner(uiState: MonitoringUiState) {
    val state = uiState.cartSnapshot.state
    val color = when {
        !uiState.cartReferenceReady -> Color(0xFF60A5FA)
        !uiState.cartPresent -> Color(0xFFF59E0B)
        else -> statusColor(state)
    }
    val label = when {
        !uiState.cartReferenceReady -> "Cart reference needed"
        !uiState.cartPresent -> "Cart away"
        state == MonitoringState.LeftoverAlert -> "Check cart"
        state == MonitoringState.PackingActive -> "Cart active"
        state == MonitoringState.PostPackScan -> "Scanning cart"
        else -> "Cart ready"
    }
    val detail = when {
        !uiState.cartReferenceReady -> "Capture the empty cart while it is parked in its marked position."
        !uiState.cartPresent -> "Cart zone is ignored until the cart returns to the reference position."
        state == MonitoringState.LeftoverAlert -> "Possible item left in the cart or basket."
        else -> "Cart occupancy ${percent(uiState.cartSnapshot.occupancyScore)}."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (state == MonitoringState.LeftoverAlert) {
                    Icons.Default.Warning
                } else {
                    Icons.Default.Check
                },
                contentDescription = null,
                tint = color,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsPanel(
    uiState: MonitoringUiState,
    onCaptureReference: () -> Unit,
    onCaptureCartReference: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onDismissAlert: () -> Unit,
    onStartAreaSetup: () -> Unit,
    onStartCartAreaSetup: () -> Unit,
    onCancelAreaSetup: () -> Unit,
    onSaveAreaSetup: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            shape = RoundedCornerShape(8.dp),
            onClick = onCaptureReference,
            enabled = uiState.cameraPermissionGranted && !uiState.areaSetupActive,
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null)
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = "Capture table",
            )
        }

        FilledTonalButton(
            shape = RoundedCornerShape(8.dp),
            onClick = onCaptureCartReference,
            enabled = uiState.cameraPermissionGranted && !uiState.areaSetupActive,
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null)
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = "Capture cart",
            )
        }

        FilledTonalButton(
            shape = RoundedCornerShape(8.dp),
            onClick = onToggleMonitoring,
            enabled = uiState.cameraPermissionGranted && !uiState.areaSetupActive,
        ) {
            Icon(
                imageVector = if (uiState.monitoringEnabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
            )
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = if (uiState.monitoringEnabled) "Pause" else "Start",
            )
        }

        if (uiState.snapshot.state == MonitoringState.LeftoverAlert ||
            uiState.cartSnapshot.state == MonitoringState.LeftoverAlert
        ) {
            OutlinedButton(
                shape = RoundedCornerShape(8.dp),
                onClick = onDismissAlert,
            ) {
                Icon(Icons.Default.NotificationsOff, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = "Dismiss",
                )
            }
        }

        if (uiState.areaSetupActive) {
            FilledTonalButton(
                shape = RoundedCornerShape(8.dp),
                onClick = onSaveAreaSetup,
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = "Save area",
                )
            }
            OutlinedButton(
                shape = RoundedCornerShape(8.dp),
                onClick = onCancelAreaSetup,
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = "Cancel",
                )
            }
        } else {
            OutlinedButton(
                shape = RoundedCornerShape(8.dp),
                onClick = onStartAreaSetup,
                enabled = uiState.cameraPermissionGranted,
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = "Set table",
                )
            }
            OutlinedButton(
                shape = RoundedCornerShape(8.dp),
                onClick = onStartCartAreaSetup,
                enabled = uiState.cameraPermissionGranted,
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = "Set cart",
                )
            }
        }
    }
}

@Composable
private fun AreaSetupPanel(
    bounds: NormalizedRect,
    target: AreaSetupTarget,
    onBoundsChanged: (NormalizedRect) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when (target) {
                    AreaSetupTarget.Table -> "Packing table crop"
                    AreaSetupTarget.Cart -> "Cart crop"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when (target) {
                    AreaSetupTarget.Table ->
                        "Adjust the blue rectangle so it covers only the table surface checked after packing."
                    AreaSetupTarget.Cart ->
                        "Adjust the blue rectangle so it covers the parked cart baskets, not the floor or shelves."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AreaSlider(
                label = "Left",
                value = bounds.left,
                onValueChange = {
                    onBoundsChanged(bounds.copySafe(left = it))
                },
            )
            AreaSlider(
                label = "Top",
                value = bounds.top,
                onValueChange = {
                    onBoundsChanged(bounds.copySafe(top = it))
                },
            )
            AreaSlider(
                label = "Right",
                value = bounds.right,
                onValueChange = {
                    onBoundsChanged(bounds.copySafe(right = it))
                },
            )
            AreaSlider(
                label = "Bottom",
                value = bounds.bottom,
                onValueChange = {
                    onBoundsChanged(bounds.copySafe(bottom = it))
                },
            )
        }
    }
}

@Composable
private fun AreaSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = percent(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Slider(
            value = value,
            valueRange = 0f..1f,
            onValueChange = onValueChange,
        )
    }
}

@Composable
private fun FeedbackPanel(onMarkFeedback: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Was the warning correct?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    shape = RoundedCornerShape(8.dp),
                    onClick = { onMarkFeedback(true) },
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = "Correct",
                    )
                }
                OutlinedButton(
                    shape = RoundedCornerShape(8.dp),
                    onClick = { onMarkFeedback(false) },
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = "False alarm",
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsPanel(uiState: MonitoringUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Pilot metrics",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell("Alerts", uiState.summary.totalAlerts.toString(), Modifier.weight(1f))
                MetricCell("Correct", uiState.summary.correctAlerts.toString(), Modifier.weight(1f))
                MetricCell("False", uiState.summary.falseAlerts.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell("Dismissed", uiState.summary.dismissedAlerts.toString(), Modifier.weight(1f))
                MetricCell("Avg response", duration(uiState.summary.averageResponseTimeMs), Modifier.weight(1f))
                MetricCell("State", uiState.snapshot.state.name, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell("Verifier", uiState.snapshot.changeClassification.name, Modifier.weight(1f))
                MetricCell("Confidence", percent(uiState.snapshot.localVerifierConfidence), Modifier.weight(1f))
                MetricCell(
                    "Added/removed",
                    "${percent(uiState.snapshot.addedObjectScore)} / ${percent(uiState.snapshot.removedObjectScore)}",
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    uiState: MonitoringUiState,
    onEntryThresholdChanged: (Float) -> Unit,
    onClearThresholdChanged: (Float) -> Unit,
    onAlertDelayChanged: (Long) -> Unit,
    onAlarmVolumeChanged: (Int) -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = "Pilot settings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            SliderSetting(
                label = "Alert threshold",
                valueLabel = percent(uiState.settings.entryThreshold),
                value = uiState.settings.entryThreshold,
                valueRange = 0.03f..0.25f,
                onValueChange = onEntryThresholdChanged,
            )
            SliderSetting(
                label = "Clear threshold",
                valueLabel = percent(uiState.settings.clearThreshold),
                value = uiState.settings.clearThreshold,
                valueRange = 0.005f..0.12f,
                onValueChange = onClearThresholdChanged,
            )
            SliderSetting(
                label = "Post-pack scan wait",
                valueLabel = duration(uiState.settings.alertDelayMs),
                value = uiState.settings.alertDelayMs.toFloat(),
                valueRange = 3_000f..60_000f,
                onValueChange = { onAlertDelayChanged(it.toLong()) },
            )
            SliderSetting(
                label = "Alarm volume",
                valueLabel = "${uiState.settings.alarmVolumePercent}%",
                value = uiState.settings.alarmVolumePercent.toFloat(),
                valueRange = 10f..100f,
                onValueChange = { onAlarmVolumeChanged(it.toInt()) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                    text = "Vibration",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = uiState.settings.vibrationEnabled,
                    onCheckedChange = onVibrationChanged,
                )
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            valueRange = valueRange,
            onValueChange = onValueChange,
        )
    }
}

@Composable
private fun EventHistory(events: List<AlertEventEntity>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Recent alerts",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (events.isEmpty()) {
                Text(
                    text = "No pilot alerts recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                events.forEach { event ->
                    EventRow(event)
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun EventRow(event: AlertEventEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = when (event.markedCorrect) {
                true -> Color(0xFF22C55E).copy(alpha = 0.16f)
                false -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                null -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                text = percent(event.occupancyScore),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.zoneName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${time(event.startedAtMillis)} - response ${duration(event.timeToAlertMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            event.triggerReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            event.localVerifierDecision?.let { decision ->
                val confidence = event.localVerifierConfidence ?: 0f
                val added = event.addedObjectScore ?: 0f
                val removed = event.removedObjectScore ?: 0f
                Text(
                    text = "Verifier $decision ${percent(confidence)}; added ${percent(added)}, removed ${percent(removed)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusColor(state: MonitoringState): Color =
    when (state) {
        MonitoringState.LeftoverAlert -> Color(0xFFE53935)
        MonitoringState.SystemUnavailable -> Color(0xFFF59E0B)
        MonitoringState.PackingActive,
        MonitoringState.BagRemovedCandidate,
        MonitoringState.PostPackScan -> Color(0xFFF59E0B)
        MonitoringState.EmptyTable,
        MonitoringState.ClearReset -> Color(0xFF1F7A5C)
        MonitoringState.NeedsReference,
        MonitoringState.Paused -> Color(0xFF355C7D)
    }

private fun NormalizedRect.copySafe(
    left: Float = this.left,
    top: Float = this.top,
    right: Float = this.right,
    bottom: Float = this.bottom,
): NormalizedRect {
    val safeLeft = left.coerceIn(0f, 1f - MIN_ZONE_SIZE)
    val safeTop = top.coerceIn(0f, 1f - MIN_ZONE_SIZE)
    val safeRight = right.coerceIn(safeLeft + MIN_ZONE_SIZE, 1f)
    val safeBottom = bottom.coerceIn(safeTop + MIN_ZONE_SIZE, 1f)
    return NormalizedRect(
        left = safeLeft,
        top = safeTop,
        right = safeRight,
        bottom = safeBottom,
    )
}

private fun percent(value: Float): String =
    String.format(Locale.US, "%.1f%%", value * 100f)

private fun duration(ms: Long): String {
    if (ms <= 0) return "0s"
    return if (ms < 60_000) {
        String.format(Locale.US, "%.1fs", ms / 1000f)
    } else {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1_000
        "${minutes}m ${seconds}s"
    }
}

private fun time(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

private const val MIN_ZONE_SIZE = 0.06f
