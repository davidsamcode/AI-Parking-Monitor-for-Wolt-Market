package com.aipackingmonitor.ui.monitoring

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aipackingmonitor.domain.model.DetectionResult
import com.aipackingmonitor.domain.model.MonitoringState
import com.aipackingmonitor.domain.model.MonitoringZone
import com.aipackingmonitor.domain.model.NormalizedRect
import com.aipackingmonitor.vision.FrameDifferencingDetector
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

@Composable
fun CameraPermissionGate(
    granted: Boolean,
    onPermissionChanged: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = onPermissionChanged,
    )

    LaunchedEffect(Unit) {
        onPermissionChanged(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Camera access is required to monitor the packing zone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                modifier = Modifier.padding(top = 16.dp),
                shape = RoundedCornerShape(8.dp),
                onClick = { launcher.launch(Manifest.permission.CAMERA) },
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = "Allow camera",
                )
            }
        }
    }
}

private const val AUDIT_VIDEO_DIRECTORY = "audit-videos"

@Composable
fun CameraPreviewPanel(
    uiState: MonitoringUiState,
    onDetection: (DetectionResult) -> Unit,
    onReferenceCaptured: (String, Boolean) -> Unit,
    onReferenceAvailabilityChanged: (String, Boolean) -> Unit,
    onAuditRecordingStarted: (String, String, Long) -> Unit,
    onAuditRecordingFinalized: (String, Long, Long?, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoneStates = uiState.cameraZoneStates()
    Box(
        modifier = modifier
            .background(Color.Black),
    ) {
        CameraFeed(
            zoneRequests = zoneStates.map { state ->
                CameraZoneFrameState(
                    zone = state.zone,
                    referenceCaptureRequest = state.referenceCaptureRequest,
                )
            },
            onDetection = onDetection,
            onReferenceCaptured = onReferenceCaptured,
            onReferenceAvailabilityChanged = onReferenceAvailabilityChanged,
            auditRecordingRequest = uiState.auditRecordingRequest,
            onAuditRecordingStarted = onAuditRecordingStarted,
            onAuditRecordingFinalized = onAuditRecordingFinalized,
        )
        zoneStates.forEach { zoneState ->
            val setupActive = uiState.areaSetupActive &&
                uiState.areaSetupZoneId == zoneState.zone.id
            ZoneOverlay(
                zone = if (setupActive) {
                    zoneState.zone.copy(bounds = uiState.draftZoneBounds)
                } else {
                    zoneState.zone
                },
                state = zoneState.snapshot.state,
                suspectedRegion = if (zoneState.snapshot.state == MonitoringState.LeftoverAlert) {
                    zoneState.snapshot.changedRegionBounds
                } else {
                    null
                },
                setupActive = setupActive,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CameraFeed(
    zoneRequests: List<CameraZoneFrameState>,
    onDetection: (DetectionResult) -> Unit,
    onReferenceCaptured: (String, Boolean) -> Unit,
    onReferenceAvailabilityChanged: (String, Boolean) -> Unit,
    auditRecordingRequest: AuditRecordingRequest?,
    onAuditRecordingStarted: (String, String, Long) -> Unit,
    onAuditRecordingFinalized: (String, Long, Long?, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val detector = remember { FrameDifferencingDetector(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var videoCaptureReady by remember { mutableStateOf(false) }
    var activeAuditRecording by remember { mutableStateOf<ActiveAuditRecording?>(null) }
    val zoneRequestsRef = remember { AtomicReference(zoneRequests) }
    val handledCaptureRequests = remember { ConcurrentHashMap<String, Long>() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    LaunchedEffect(detector, zoneRequests) {
        zoneRequestsRef.set(zoneRequests)
        zoneRequests.forEach { request ->
            onReferenceAvailabilityChanged(
                request.zone.id,
                detector.hasReference(request.zone),
            )
        }
    }

    LaunchedEffect(auditRecordingRequest, videoCaptureReady) {
        if (!videoCaptureReady) return@LaunchedEffect

        val currentRecording = activeAuditRecording
        if (auditRecordingRequest == null) {
            currentRecording?.recording?.stop()
            activeAuditRecording = null
            return@LaunchedEffect
        }

        if (currentRecording?.id == auditRecordingRequest.id) return@LaunchedEffect

        currentRecording?.recording?.stop()
        activeAuditRecording = null

        val file = createAuditVideoFile(context.filesDir, auditRecordingRequest)
        val outputOptions = FileOutputOptions.Builder(file).build()
        runCatching {
            val recording = videoCapture.output
                .prepareRecording(context, outputOptions)
                .start(mainExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            onAuditRecordingStarted(
                                auditRecordingRequest.id,
                                file.absolutePath,
                                auditRecordingRequest.startedAtMillis,
                            )
                        }

                        is VideoRecordEvent.Finalize -> {
                            val failed = event.hasError()
                            if (failed) {
                                runCatching { file.delete() }
                            }
                            onAuditRecordingFinalized(
                                auditRecordingRequest.id,
                                System.currentTimeMillis(),
                                file.takeIf { it.exists() }?.length(),
                                failed,
                            )
                        }
                    }
                }
            activeAuditRecording = ActiveAuditRecording(
                id = auditRecordingRequest.id,
                file = file,
                recording = recording,
            )
        }.onFailure {
            onAuditRecordingFinalized(
                auditRecordingRequest.id,
                System.currentTimeMillis(),
                null,
                true,
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        imageAnalysis.setAnalyzer(cameraExecutor) { image ->
                            try {
                                val requests = zoneRequestsRef.get()
                                val pendingCapture = requests.firstOrNull { request ->
                                    request.referenceCaptureRequest >
                                        (handledCaptureRequests[request.zone.id] ?: 0L)
                                }
                                if (pendingCapture != null) {
                                    val captured = detector.captureReference(image, pendingCapture.zone)
                                    handledCaptureRequests[pendingCapture.zone.id] =
                                        pendingCapture.referenceCaptureRequest
                                    mainExecutor.execute {
                                        onReferenceCaptured(pendingCapture.zone.id, captured)
                                        onReferenceAvailabilityChanged(
                                            pendingCapture.zone.id,
                                            detector.hasReference(pendingCapture.zone),
                                        )
                                    }
                                } else {
                                    val detections = requests.map { request ->
                                        detector.detect(image, request.zone)
                                    }
                                    mainExecutor.execute {
                                        detections.forEach(onDetection)
                                    }
                                }
                            } finally {
                                image.close()
                            }
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture,
                    analysis,
                )
                videoCaptureReady = true
            },
            mainExecutor,
        )

        onDispose {
            activeAuditRecording?.recording?.stop()
            activeAuditRecording = null
            videoCaptureReady = false
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
            }
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { previewView },
    )
}

private data class ActiveAuditRecording(
    val id: String,
    val file: File,
    val recording: Recording,
)

private fun createAuditVideoFile(
    filesDir: File,
    request: AuditRecordingRequest,
): File {
    val directory = File(filesDir, AUDIT_VIDEO_DIRECTORY).apply { mkdirs() }
    val shortId = request.id.take(8)
    return File(directory, "session-${request.startedAtMillis}-$shortId.mp4")
}

private data class CameraZoneFrameState(
    val zone: MonitoringZone,
    val referenceCaptureRequest: Long,
)

private fun MonitoringUiState.cameraZoneStates(): List<MonitoredZoneUiState> =
    listOf(
        MonitoredZoneUiState(
            zone = zone,
            snapshot = snapshot,
            referenceReady = referenceReady,
            referenceCaptureRequest = referenceCaptureRequest,
            present = true,
        ),
        MonitoredZoneUiState(
            zone = cartZone,
            snapshot = cartSnapshot,
            referenceReady = cartReferenceReady,
            referenceCaptureRequest = cartReferenceCaptureRequest,
            present = cartPresent,
        ),
    ) + additionalZones

@Composable
private fun ZoneOverlay(
    zone: MonitoringZone,
    state: MonitoringState,
    suspectedRegion: NormalizedRect?,
    setupActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (setupActive) {
        Color(0xFF60A5FA)
    } else when (state) {
        MonitoringState.LeftoverAlert -> Color(0xFFE53935)
        MonitoringState.SystemUnavailable -> Color(0xFFF59E0B)
        MonitoringState.PackingActive,
        MonitoringState.BagRemovedCandidate,
        MonitoringState.PostPackScan -> Color(0xFFF59E0B)
        MonitoringState.EmptyTable,
        MonitoringState.ClearReset -> Color(0xFF22C55E)
        MonitoringState.NeedsReference,
        MonitoringState.Paused -> Color(0xFF60A5FA)
    }

    Canvas(modifier = modifier) {
        val left = size.width * zone.bounds.left
        val top = size.height * zone.bounds.top
        val rectSize = Size(
            width = size.width * zone.bounds.width,
            height = size.height * zone.bounds.height,
        )
        drawRect(
            color = color.copy(alpha = 0.16f),
            topLeft = Offset(left, top),
            size = rectSize,
        )
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = rectSize,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Square),
        )
        if (setupActive) {
            val handleRadius = 7.dp.toPx()
            drawCircle(color = color, radius = handleRadius, center = Offset(left, top))
            drawCircle(color = color, radius = handleRadius, center = Offset(left + rectSize.width, top))
            drawCircle(color = color, radius = handleRadius, center = Offset(left, top + rectSize.height))
            drawCircle(
                color = color,
                radius = handleRadius,
                center = Offset(left + rectSize.width, top + rectSize.height),
            )
        }

        if (suspectedRegion != null) {
            val regionLeft = size.width * suspectedRegion.left
            val regionTop = size.height * suspectedRegion.top
            val regionWidth = size.width * suspectedRegion.width
            val regionHeight = size.height * suspectedRegion.height
            val center = Offset(
                x = regionLeft + regionWidth / 2f,
                y = regionTop + regionHeight / 2f,
            )
            val radius = max(regionWidth, regionHeight) / 2f + 10.dp.toPx()
            val alertColor = Color(0xFFE53935)
            drawCircle(
                color = alertColor.copy(alpha = 0.14f),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = alertColor,
                radius = radius,
                center = center,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}
