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
import androidx.compose.runtime.remember
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
import com.aipackingmonitor.vision.FrameDifferencingDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

@Composable
fun CameraPreviewPanel(
    uiState: MonitoringUiState,
    onDetection: (DetectionResult) -> Unit,
    onReferenceCaptured: (Boolean) -> Unit,
    onReferenceAvailabilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black),
    ) {
        CameraFeed(
            zone = uiState.zone,
            referenceCaptureRequest = uiState.referenceCaptureRequest,
            onDetection = onDetection,
            onReferenceCaptured = onReferenceCaptured,
            onReferenceAvailabilityChanged = onReferenceAvailabilityChanged,
        )
        ZoneOverlay(
            zone = if (uiState.areaSetupActive) {
                uiState.zone.copy(bounds = uiState.draftZoneBounds)
            } else {
                uiState.zone
            },
            state = uiState.snapshot.state,
            setupActive = uiState.areaSetupActive,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CameraFeed(
    zone: MonitoringZone,
    referenceCaptureRequest: Long,
    onDetection: (DetectionResult) -> Unit,
    onReferenceCaptured: (Boolean) -> Unit,
    onReferenceAvailabilityChanged: (Boolean) -> Unit,
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
    val captureRequest = remember { AtomicLong(referenceCaptureRequest) }
    val handledCaptureRequest = remember { AtomicLong(referenceCaptureRequest) }
    val zoneRef = remember { AtomicReference(zone) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    LaunchedEffect(referenceCaptureRequest) {
        captureRequest.set(referenceCaptureRequest)
    }

    LaunchedEffect(detector) {
        onReferenceAvailabilityChanged(detector.hasReference(zoneRef.get()))
    }

    LaunchedEffect(zone) {
        zoneRef.set(zone)
        onReferenceAvailabilityChanged(detector.hasReference(zone))
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
                                val requested = captureRequest.get()
                                val zoneSnapshot = zoneRef.get()
                                if (requested > handledCaptureRequest.get()) {
                                    val captured = detector.captureReference(image, zoneSnapshot)
                                    handledCaptureRequest.set(requested)
                                    mainExecutor.execute {
                                        onReferenceCaptured(captured)
                                        onReferenceAvailabilityChanged(detector.hasReference(zoneSnapshot))
                                    }
                                } else {
                                    val detection = detector.detect(image, zoneSnapshot)
                                    mainExecutor.execute { onDetection(detection) }
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
                    analysis,
                )
            },
            mainExecutor,
        )

        onDispose {
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

@Composable
private fun ZoneOverlay(
    zone: MonitoringZone,
    state: MonitoringState,
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
    }
}
