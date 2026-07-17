package com.aipackingmonitor.vision

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.aipackingmonitor.domain.model.ChangeClassification
import com.aipackingmonitor.domain.model.DetectionQuality
import com.aipackingmonitor.domain.model.DetectionResult
import com.aipackingmonitor.domain.model.MonitoringZone
import com.aipackingmonitor.domain.model.NormalizedRect
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

class FrameDifferencingDetector(context: Context) {
    private val referenceStore = ReferenceStore(context.applicationContext)
    private val openCvAnalyzer = OpenCvChangeAnalyzer()
    private val tensorFlowLiteVerifier = TensorFlowLiteChangeVerifier(context.applicationContext)
    private val references = mutableMapOf<String, LumaReference?>()
    private val previousSamples = mutableMapOf<String, IntArray>()

    @Synchronized
    fun hasReference(zone: MonitoringZone): Boolean =
        referenceFor(zone)?.zoneBounds?.approximatelyEquals(zone.bounds) == true

    @Synchronized
    fun captureReference(image: ImageProxy, zone: MonitoringZone): Boolean {
        val samples = sampleLumaGrid(image, zone.bounds)
        if (samples.quality != DetectionQuality.Valid) return false
        val nextReference = LumaReference(
            width = image.width,
            height = image.height,
            zoneBounds = zone.bounds,
            samples = samples.values,
        )
        referenceStore.saveLumaReference(zone.id, nextReference)
        referenceStore.saveJpegReference(zone.id, image)
        references[zone.id] = nextReference
        previousSamples.remove(zone.id)
        return true
    }

    @Synchronized
    fun detect(image: ImageProxy, zone: MonitoringZone): DetectionResult {
        val started = System.nanoTime()
        val savedReference = referenceFor(zone)
        if (savedReference == null) {
            return result(
                zone = zone,
                occupancy = 0f,
                motion = 0f,
                largestChangedRegion = 0f,
                addedObjectScore = 0f,
                removedObjectScore = 0f,
                localVerifierConfidence = 0f,
                changeClassification = ChangeClassification.None,
                changedRegionBounds = null,
                stable = false,
                quality = DetectionQuality.NoReference,
                startedNanos = started,
            )
        }
        if (!savedReference.zoneBounds.approximatelyEquals(zone.bounds)) {
            return result(
                zone = zone,
                occupancy = 0f,
                motion = 0f,
                largestChangedRegion = 0f,
                addedObjectScore = 0f,
                removedObjectScore = 0f,
                localVerifierConfidence = 0f,
                changeClassification = ChangeClassification.None,
                changedRegionBounds = null,
                stable = false,
                quality = DetectionQuality.NoReference,
                startedNanos = started,
            )
        }

        val current = sampleLumaGrid(image, zone.bounds)
        if (current.quality != DetectionQuality.Valid) {
            return result(
                zone = zone,
                occupancy = 0f,
                motion = 0f,
                largestChangedRegion = 0f,
                addedObjectScore = 0f,
                removedObjectScore = 0f,
                localVerifierConfidence = 0f,
                changeClassification = ChangeClassification.None,
                changedRegionBounds = null,
                stable = false,
                quality = current.quality,
                startedNanos = started,
            )
        }

        val changeAnalysis = openCvAnalyzer.analyze(
            zoneBounds = zone.bounds,
            reference = savedReference.samples,
            current = current.values,
        ) ?: legacyChangeAnalysis(
            zoneBounds = zone.bounds,
            reference = savedReference.samples,
            current = current.values,
        )
        val occupancy = changeAnalysis.occupancy
        val largestChangedRegion = applyTensorFlowLiteSecondCheck(
            occupancy = occupancy,
            changedRegion = changeAnalysis.largestChangedRegion,
        )
        val previous = previousSamples[zone.id]
        val motionScore = if (previous == null) {
            0f
        } else {
            current.values.indices.count { index ->
                abs(current.values[index] - previous[index]) > MOTION_THRESHOLD
            }.toFloat() / current.values.size.toFloat()
        }
        previousSamples[zone.id] = current.values

        val quality = when {
            occupancy > 0.82f && motionScore < 0.03f -> DetectionQuality.ReferenceMisaligned
            else -> DetectionQuality.Valid
        }

        return result(
            zone = zone,
            occupancy = occupancy,
            motion = motionScore,
            largestChangedRegion = largestChangedRegion.score,
            addedObjectScore = largestChangedRegion.addedObjectScore,
            removedObjectScore = largestChangedRegion.removedObjectScore,
            localVerifierConfidence = largestChangedRegion.confidence,
            changeClassification = largestChangedRegion.classification,
            changedRegionBounds = largestChangedRegion.bounds,
            stable = motionScore < 0.045f,
            quality = quality,
            startedNanos = started,
        )
    }

    private fun sampleLumaGrid(
        image: ImageProxy,
        rect: NormalizedRect,
    ): SampledLuma {
        val plane = image.planes.firstOrNull() ?: return SampledLuma.Empty
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val values = IntArray(GRID_WIDTH * GRID_HEIGHT)

        val startX = (image.width * rect.left).toInt().coerceIn(0, image.width - 1)
        val endX = (image.width * rect.right).toInt().coerceIn(startX + 1, image.width)
        val startY = (image.height * rect.top).toInt().coerceIn(0, image.height - 1)
        val endY = (image.height * rect.bottom).toInt().coerceIn(startY + 1, image.height)
        var sum = 0.0
        var sumSquares = 0.0

        for (gridY in 0 until GRID_HEIGHT) {
            val y = lerp(startY, endY - 1, gridY, GRID_HEIGHT)
            for (gridX in 0 until GRID_WIDTH) {
                val x = lerp(startX, endX - 1, gridX, GRID_WIDTH)
                val bufferIndex = y * rowStride + x * pixelStride
                val value = buffer.get(bufferIndex).toInt() and 0xFF
                val outputIndex = gridY * GRID_WIDTH + gridX
                values[outputIndex] = value
                sum += value
                sumSquares += value * value
            }
        }

        val count = values.size.toDouble()
        val mean = sum / count
        val variance = max(0.0, (sumSquares / count) - (mean * mean))
        val standardDeviation = sqrt(variance)

        val quality = when {
            mean < 18.0 -> DetectionQuality.TooDark
            standardDeviation < 2.5 -> DetectionQuality.LensCovered
            else -> DetectionQuality.Valid
        }

        return SampledLuma(values = values, quality = quality)
    }

    private fun result(
        zone: MonitoringZone,
        occupancy: Float,
        motion: Float,
        largestChangedRegion: Float,
        addedObjectScore: Float,
        removedObjectScore: Float,
        localVerifierConfidence: Float,
        changeClassification: ChangeClassification,
        changedRegionBounds: NormalizedRect?,
        stable: Boolean,
        quality: DetectionQuality,
        startedNanos: Long,
    ): DetectionResult =
        DetectionResult(
            zoneId = zone.id,
            occupancyScore = occupancy.coerceIn(0f, 1f),
            motionScore = motion.coerceIn(0f, 1f),
            largestChangedRegionScore = largestChangedRegion.coerceIn(0f, 1f),
            addedObjectScore = addedObjectScore.coerceIn(0f, 1f),
            removedObjectScore = removedObjectScore.coerceIn(0f, 1f),
            localVerifierConfidence = localVerifierConfidence.coerceIn(0f, 1f),
            changeClassification = changeClassification,
            isMotionStable = stable,
            quality = quality,
            analysisLatencyMs = (System.nanoTime() - startedNanos) / 1_000_000,
            changedRegionBounds = changedRegionBounds,
        )

    private fun legacyChangeAnalysis(
        zoneBounds: NormalizedRect,
        reference: IntArray,
        current: IntArray,
    ): ChangeAnalysis {
        val diffCount = current.indices.count { index ->
            abs(current[index] - reference[index]) > CHANGE_THRESHOLD
        }

        return ChangeAnalysis(
            occupancy = diffCount.toFloat() / current.size.toFloat(),
            largestChangedRegion = largestChangedRegion(
                zoneBounds = zoneBounds,
                reference = reference,
                current = current,
            ),
        )
    }

    private fun applyTensorFlowLiteSecondCheck(
        occupancy: Float,
        changedRegion: ChangedRegion,
    ): ChangedRegion {
        if (changedRegion.classification != ChangeClassification.AddedObject) return changedRegion

        val decision = tensorFlowLiteVerifier.verify(
            AiVerifierInput(
                occupancy = occupancy,
                regionScore = changedRegion.score,
                addedObjectScore = changedRegion.addedObjectScore,
                removedObjectScore = changedRegion.removedObjectScore,
                localConfidence = changedRegion.confidence,
                classification = changedRegion.classification,
            ),
        )
        if (!decision.available) return changedRegion

        return if (
            decision.classification == ChangeClassification.AddedObject &&
            decision.confidence >= TFLITE_CONFIRMATION_THRESHOLD
        ) {
            changedRegion.copy(
                addedObjectScore = max(changedRegion.addedObjectScore, decision.confidence),
                confidence = max(changedRegion.confidence, decision.confidence),
            )
        } else {
            changedRegion.copy(
                classification = if (decision.confidence >= TFLITE_CONFIRMATION_THRESHOLD) {
                    decision.classification
                } else {
                    ChangeClassification.MixedChange
                },
                addedObjectScore = min(changedRegion.addedObjectScore, decision.confidence),
                confidence = min(
                    changedRegion.confidence,
                    decision.confidence.coerceAtMost(TFLITE_CONFIRMATION_THRESHOLD - 0.01f),
                ),
            )
        }
    }

    private fun largestChangedRegion(
        zoneBounds: NormalizedRect,
        reference: IntArray,
        current: IntArray,
    ): ChangedRegion {
        val changed = BooleanArray(current.size) { index ->
            abs(current[index] - reference[index]) > CHANGE_THRESHOLD
        }
        val visited = BooleanArray(current.size)
        var largest = 0
        var largestMinX = 0
        var largestMinY = 0
        var largestMaxX = 0
        var largestMaxY = 0
        var largestDarker = 0
        var largestBrighter = 0
        var largestRegionIndices = IntArray(0)

        for (index in changed.indices) {
            if (!changed[index] || visited[index]) continue

            var size = 0
            var stackSize = 0
            var minX = GRID_WIDTH
            var minY = GRID_HEIGHT
            var maxX = 0
            var maxY = 0
            var darker = 0
            var brighter = 0
            val stack = IntArray(changed.size)
            val componentIndices = IntArray(changed.size)
            stack[stackSize++] = index
            visited[index] = true

            while (stackSize > 0) {
                val currentIndex = stack[--stackSize]
                componentIndices[size] = currentIndex
                size++

                val x = currentIndex % GRID_WIDTH
                val y = currentIndex / GRID_WIDTH
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                val delta = current[currentIndex] - reference[currentIndex]
                if (delta < -CHANGE_THRESHOLD) {
                    darker++
                } else if (delta > CHANGE_THRESHOLD) {
                    brighter++
                }
                val neighbors = intArrayOf(
                    currentIndex - 1,
                    currentIndex + 1,
                    currentIndex - GRID_WIDTH,
                    currentIndex + GRID_WIDTH,
                )

                for (neighbor in neighbors) {
                    if (neighbor !in changed.indices || visited[neighbor] || !changed[neighbor]) continue
                    val neighborX = neighbor % GRID_WIDTH
                    val neighborY = neighbor / GRID_WIDTH
                    val adjacent = abs(neighborX - x) + abs(neighborY - y) == 1
                    if (!adjacent) continue

                    visited[neighbor] = true
                    stack[stackSize++] = neighbor
                }
            }

            if (size > largest) {
                largest = size
                largestMinX = minX
                largestMinY = minY
                largestMaxX = maxX
                largestMaxY = maxY
                largestDarker = darker
                largestBrighter = brighter
                largestRegionIndices = componentIndices.copyOf(size)
            }
        }

        if (largest == 0) {
            return emptyChangedRegion()
        }

        return changedRegionFromComponent(
            zoneBounds = zoneBounds,
            reference = reference,
            current = current,
            largest = largest,
            minX = largestMinX,
            minY = largestMinY,
            maxX = largestMaxX,
            maxY = largestMaxY,
            darker = largestDarker,
            brighter = largestBrighter,
            regionIndices = largestRegionIndices,
        )
    }

    private fun changedRegionFromComponent(
        zoneBounds: NormalizedRect,
        reference: IntArray,
        current: IntArray,
        largest: Int,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
        darker: Int,
        brighter: Int,
        regionIndices: IntArray,
    ): ChangedRegion {
        val left = zoneBounds.left + zoneBounds.width * (minX.toFloat() / GRID_WIDTH.toFloat())
        val top = zoneBounds.top + zoneBounds.height * (minY.toFloat() / GRID_HEIGHT.toFloat())
        val right = zoneBounds.left + zoneBounds.width * ((maxX + 1).toFloat() / GRID_WIDTH.toFloat())
        val bottom = zoneBounds.top + zoneBounds.height * ((maxY + 1).toFloat() / GRID_HEIGHT.toFloat())

        val score = largest.toFloat() / current.size.toFloat()
        val darkerShare = darker.toFloat() / largest.toFloat()
        val brighterShare = brighter.toFloat() / largest.toFloat()
        val contrast = measureRegionContrast(
            regionIndices = regionIndices,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
            reference = reference,
            current = current,
        )
        val verifier = classifyChange(
            regionScore = score,
            darkerShare = darkerShare,
            brighterShare = brighterShare,
            contrast = contrast,
        )

        return ChangedRegion(
            score = score,
            bounds = NormalizedRect(
                left = left.coerceIn(0f, 1f),
                top = top.coerceIn(0f, 1f),
                right = right.coerceIn(0f, 1f),
                bottom = bottom.coerceIn(0f, 1f),
            ),
            addedObjectScore = verifier.addedObjectScore,
            removedObjectScore = verifier.removedObjectScore,
            confidence = verifier.confidence,
            classification = verifier.classification,
        )
    }

    private fun emptyChangedRegion(): ChangedRegion =
        ChangedRegion(
            score = 0f,
            bounds = null,
            addedObjectScore = 0f,
            removedObjectScore = 0f,
            confidence = 0f,
            classification = ChangeClassification.None,
        )

    private fun classifyChange(
        regionScore: Float,
        darkerShare: Float,
        brighterShare: Float,
        contrast: RegionContrast,
    ): LocalVerifierResult {
        if (regionScore < MIN_VERIFIER_REGION_SCORE) {
            return LocalVerifierResult(
                classification = ChangeClassification.None,
                confidence = 0f,
                addedObjectScore = 0f,
                removedObjectScore = 0f,
            )
        }

        val regionConfidence = (regionScore / STRONG_VERIFIER_REGION_SCORE).coerceIn(0f, 1f)
        val contrastGap = contrast.currentContrast - contrast.referenceContrast
        val contrastConfidence = (abs(contrastGap) / STRONG_CONTRAST_GAP).coerceIn(0f, 1f)
        val contrastAddedScore = ((contrastGap - MIN_CONTRAST_GAP) / STRONG_CONTRAST_GAP)
            .coerceIn(0f, 1f)
        val contrastRemovedScore = ((-contrastGap - MIN_CONTRAST_GAP) / STRONG_CONTRAST_GAP)
            .coerceIn(0f, 1f)
        val addedScore = max(
            contrastAddedScore,
            (darkerShare * 0.50f + min(darkerShare, brighterShare) * 0.25f +
                contrast.currentContrast * 0.25f).coerceIn(0f, 1f),
        )
        val removedScore = max(
            contrastRemovedScore,
            (brighterShare * 0.55f + contrast.referenceContrast * 0.30f).coerceIn(0f, 1f),
        )
        val confidence = (0.36f + 0.44f * regionConfidence + 0.20f * contrastConfidence)
            .coerceIn(0f, 1f)
        val strongAddedByContrast =
            contrastGap >= MIN_CONTRAST_GAP && contrast.currentContrast >= MIN_OBJECT_CONTRAST
        val strongRemovedByContrast =
            -contrastGap >= MIN_CONTRAST_GAP && contrast.referenceContrast >= MIN_OBJECT_CONTRAST

        return when {
            strongAddedByContrast ->
                LocalVerifierResult(
                    classification = ChangeClassification.AddedObject,
                    confidence = confidence,
                    addedObjectScore = addedScore,
                    removedObjectScore = removedScore,
                )

            strongRemovedByContrast ->
                LocalVerifierResult(
                    classification = ChangeClassification.RemovedReferenceObject,
                    confidence = confidence,
                    addedObjectScore = addedScore,
                    removedObjectScore = removedScore,
                )

            regionScore >= BROAD_LIGHTING_REGION_SCORE && abs(contrastGap) < MIN_CONTRAST_GAP ->
                LocalVerifierResult(
                    classification = ChangeClassification.LightingChange,
                    confidence = confidence,
                    addedObjectScore = addedScore,
                    removedObjectScore = removedScore,
                )

            darkerShare >= 0.58f || (darkerShare >= 0.42f && brighterShare >= 0.18f) ->
                LocalVerifierResult(
                    classification = ChangeClassification.AddedObject,
                    confidence = confidence,
                    addedObjectScore = addedScore,
                    removedObjectScore = removedScore,
                )

            brighterShare >= 0.62f && contrast.referenceContrast >= contrast.currentContrast ->
                LocalVerifierResult(
                    classification = ChangeClassification.RemovedReferenceObject,
                    confidence = confidence,
                    addedObjectScore = addedScore,
                    removedObjectScore = removedScore,
                )

            regionScore >= BROAD_LIGHTING_REGION_SCORE ->
                LocalVerifierResult(
                    classification = ChangeClassification.LightingChange,
                    confidence = confidence,
                    addedObjectScore = addedScore,
                    removedObjectScore = removedScore,
                )

            else ->
                LocalVerifierResult(
                    classification = ChangeClassification.MixedChange,
                    confidence = confidence * 0.72f,
                    addedObjectScore = addedScore,
                    removedObjectScore = removedScore,
                )
        }
    }

    private fun measureRegionContrast(
        regionIndices: IntArray,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
        reference: IntArray,
        current: IntArray,
    ): RegionContrast {
        if (regionIndices.isEmpty()) return RegionContrast.Zero

        var currentInsideSum = 0.0
        var referenceInsideSum = 0.0
        regionIndices.forEach { index ->
            currentInsideSum += current[index]
            referenceInsideSum += reference[index]
        }

        var currentBorderSum = 0.0
        var referenceBorderSum = 0.0
        var borderCount = 0
        val ringLeft = (minX - CONTRAST_RING_SIZE).coerceAtLeast(0)
        val ringTop = (minY - CONTRAST_RING_SIZE).coerceAtLeast(0)
        val ringRight = (maxX + CONTRAST_RING_SIZE).coerceAtMost(GRID_WIDTH - 1)
        val ringBottom = (maxY + CONTRAST_RING_SIZE).coerceAtMost(GRID_HEIGHT - 1)

        for (y in ringTop..ringBottom) {
            for (x in ringLeft..ringRight) {
                val insideRegionBox = x in minX..maxX && y in minY..maxY
                if (insideRegionBox) continue

                val index = y * GRID_WIDTH + x
                currentBorderSum += current[index]
                referenceBorderSum += reference[index]
                borderCount++
            }
        }

        if (borderCount == 0) return RegionContrast.Zero

        val currentInsideMean = currentInsideSum / regionIndices.size.toDouble()
        val referenceInsideMean = referenceInsideSum / regionIndices.size.toDouble()
        val currentBorderMean = currentBorderSum / borderCount.toDouble()
        val referenceBorderMean = referenceBorderSum / borderCount.toDouble()

        return RegionContrast(
            currentContrast = (abs(currentInsideMean - currentBorderMean) / 255.0)
                .toFloat()
                .coerceIn(0f, 1f),
            referenceContrast = (abs(referenceInsideMean - referenceBorderMean) / 255.0)
                .toFloat()
                .coerceIn(0f, 1f),
        )
    }

    private fun lerp(start: Int, end: Int, index: Int, total: Int): Int {
        if (total <= 1) return start
        val fraction = index.toFloat() / (total - 1).toFloat()
        return min(end, start + ((end - start) * fraction).toInt())
    }

    private fun NormalizedRect.approximatelyEquals(other: NormalizedRect): Boolean =
        abs(left - other.left) < 0.001f &&
            abs(top - other.top) < 0.001f &&
            abs(right - other.right) < 0.001f &&
            abs(bottom - other.bottom) < 0.001f

    private fun referenceFor(zone: MonitoringZone): LumaReference? =
        references.getOrPut(zone.id) { referenceStore.loadLumaReference(zone.id) }

    private inner class OpenCvChangeAnalyzer {
        private var available: Boolean? = null

        fun analyze(
            zoneBounds: NormalizedRect,
            reference: IntArray,
            current: IntArray,
        ): ChangeAnalysis? {
            if (!isAvailable()) return null
            if (reference.size != current.size || current.size != GRID_WIDTH * GRID_HEIGHT) return null

            val referenceMat = Mat(GRID_HEIGHT, GRID_WIDTH, CvType.CV_8UC1)
            val currentMat = Mat(GRID_HEIGHT, GRID_WIDTH, CvType.CV_8UC1)
            val diffMat = Mat()
            val maskMat = Mat()
            val contourMask = Mat()
            val hierarchy = Mat()
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val contours = mutableListOf<MatOfPoint>()

            return try {
                referenceMat.put(0, 0, reference.toUnsignedByteArray())
                currentMat.put(0, 0, current.toUnsignedByteArray())

                org.opencv.core.Core.absdiff(currentMat, referenceMat, diffMat)
                Imgproc.threshold(diffMat, maskMat, CHANGE_THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY)
                Imgproc.morphologyEx(maskMat, maskMat, Imgproc.MORPH_OPEN, openKernel)
                Imgproc.morphologyEx(maskMat, maskMat, Imgproc.MORPH_CLOSE, closeKernel)

                val maskBytes = ByteArray(current.size)
                maskMat.get(0, 0, maskBytes)
                val changedCount = maskBytes.count { value -> (value.toInt() and 0xFF) != 0 }
                if (changedCount == 0) {
                    ChangeAnalysis(occupancy = 0f, largestChangedRegion = emptyChangedRegion())
                } else {
                    maskMat.copyTo(contourMask)
                    Imgproc.findContours(
                        contourMask,
                        contours,
                        hierarchy,
                        Imgproc.RETR_EXTERNAL,
                        Imgproc.CHAIN_APPROX_SIMPLE,
                    )
                    val largestComponent = largestOpenCvComponent(
                        maskBytes = maskBytes,
                        contours = contours,
                        reference = reference,
                        current = current,
                    )

                    ChangeAnalysis(
                        occupancy = changedCount.toFloat() / current.size.toFloat(),
                        largestChangedRegion = largestComponent?.let { component ->
                            changedRegionFromComponent(
                                zoneBounds = zoneBounds,
                                reference = reference,
                                current = current,
                                largest = component.size,
                                minX = component.minX,
                                minY = component.minY,
                                maxX = component.maxX,
                                maxY = component.maxY,
                                darker = component.darker,
                                brighter = component.brighter,
                                regionIndices = component.indices,
                            )
                        } ?: emptyChangedRegion(),
                    )
                }
            } catch (_: Throwable) {
                null
            } finally {
                referenceMat.release()
                currentMat.release()
                diffMat.release()
                maskMat.release()
                contourMask.release()
                hierarchy.release()
                openKernel.release()
                closeKernel.release()
                contours.forEach { contour -> contour.release() }
            }
        }

        private fun largestOpenCvComponent(
            maskBytes: ByteArray,
            contours: List<MatOfPoint>,
            reference: IntArray,
            current: IntArray,
        ): OpenCvComponent? {
            var largest: OpenCvComponent? = null

            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                var size = 0
                var minX = GRID_WIDTH
                var minY = GRID_HEIGHT
                var maxX = 0
                var maxY = 0
                var darker = 0
                var brighter = 0
                val indices = IntArray(maskBytes.size)

                val right = (rect.x + rect.width).coerceAtMost(GRID_WIDTH)
                val bottom = (rect.y + rect.height).coerceAtMost(GRID_HEIGHT)
                for (y in rect.y.coerceAtLeast(0) until bottom) {
                    for (x in rect.x.coerceAtLeast(0) until right) {
                        val index = y * GRID_WIDTH + x
                        if ((maskBytes[index].toInt() and 0xFF) == 0) continue

                        indices[size] = index
                        size++
                        minX = min(minX, x)
                        minY = min(minY, y)
                        maxX = max(maxX, x)
                        maxY = max(maxY, y)

                        val delta = current[index] - reference[index]
                        if (delta < -CHANGE_THRESHOLD) {
                            darker++
                        } else if (delta > CHANGE_THRESHOLD) {
                            brighter++
                        }
                    }
                }

                if (size > (largest?.size ?: 0)) {
                    largest = OpenCvComponent(
                        size = size,
                        minX = minX,
                        minY = minY,
                        maxX = maxX,
                        maxY = maxY,
                        darker = darker,
                        brighter = brighter,
                        indices = indices.copyOf(size),
                    )
                }
            }

            return largest?.takeIf { component -> component.size > 0 }
        }

        @Suppress("DEPRECATION")
        private fun isAvailable(): Boolean {
            available?.let { return it }
            val initialized = try {
                OpenCVLoader.initDebug()
            } catch (_: Throwable) {
                false
            }
            available = initialized
            return initialized
        }

        private fun IntArray.toUnsignedByteArray(): ByteArray =
            ByteArray(size) { index -> this[index].coerceIn(0, 255).toByte() }
    }

    private class TensorFlowLiteChangeVerifier(context: Context) {
        private val interpreter: Interpreter? = loadInterpreter(context.applicationContext)
        private val inputFeatureCount: Int? = interpreter
            ?.getInputTensor(0)
            ?.shape()
            ?.flattenedFeatureCount()
        private val outputClassCount: Int? = interpreter
            ?.getOutputTensor(0)
            ?.shape()
            ?.flattenedFeatureCount()

        fun verify(input: AiVerifierInput): AiVerifierDecision {
            val activeInterpreter = interpreter ?: return AiVerifierDecision.Unavailable
            val featureCount = inputFeatureCount ?: return AiVerifierDecision.Unavailable
            val classCount = outputClassCount ?: return AiVerifierDecision.Unavailable
            if (featureCount !in 1..MAX_TFLITE_FEATURES) return AiVerifierDecision.Unavailable
            if (classCount !in 2..MAX_TFLITE_OUTPUT_CLASSES) return AiVerifierDecision.Unavailable
            if (activeInterpreter.getInputTensor(0).dataType() != DataType.FLOAT32) {
                return AiVerifierDecision.Unavailable
            }
            if (activeInterpreter.getOutputTensor(0).dataType() != DataType.FLOAT32) {
                return AiVerifierDecision.Unavailable
            }

            return try {
                val inputBuffer = ByteBuffer
                    .allocateDirect(featureCount * FLOAT_BYTES)
                    .order(ByteOrder.nativeOrder())
                input.toFeatureArray(featureCount).forEach { value -> inputBuffer.putFloat(value) }
                inputBuffer.rewind()

                val output = Array(1) { FloatArray(classCount) }
                activeInterpreter.run(inputBuffer, output)
                val probabilities = output.first()
                val bestIndex = probabilities.indices.maxBy { index -> probabilities[index] }

                AiVerifierDecision(
                    available = true,
                    classification = classForIndex(bestIndex, classCount),
                    confidence = probabilities[bestIndex].coerceIn(0f, 1f),
                )
            } catch (_: Throwable) {
                AiVerifierDecision.Unavailable
            }
        }

        private fun AiVerifierInput.toFeatureArray(featureCount: Int): FloatArray {
            val baseFeatures = floatArrayOf(
                occupancy,
                regionScore,
                addedObjectScore,
                removedObjectScore,
                localConfidence,
                if (classification == ChangeClassification.AddedObject) 1f else 0f,
                if (classification == ChangeClassification.RemovedReferenceObject) 1f else 0f,
                if (classification == ChangeClassification.LightingChange) 1f else 0f,
                if (classification == ChangeClassification.MixedChange) 1f else 0f,
            )
            return FloatArray(featureCount) { index ->
                baseFeatures.getOrElse(index) { 0f }
            }
        }

        private fun classForIndex(index: Int, classCount: Int): ChangeClassification =
            if (classCount == 4) {
                when (index) {
                    0 -> ChangeClassification.AddedObject
                    1 -> ChangeClassification.RemovedReferenceObject
                    2 -> ChangeClassification.LightingChange
                    else -> ChangeClassification.MixedChange
                }
            } else {
                when (index) {
                    1 -> ChangeClassification.AddedObject
                    2 -> ChangeClassification.RemovedReferenceObject
                    3 -> ChangeClassification.LightingChange
                    4 -> ChangeClassification.MixedChange
                    else -> ChangeClassification.None
                }
            }

        private fun loadInterpreter(context: Context): Interpreter? =
            loadModel(context)?.let { model ->
                try {
                    Interpreter(
                        model,
                        Interpreter.Options().apply { setNumThreads(TFLITE_THREAD_COUNT) },
                    )
                } catch (_: Throwable) {
                    null
                }
            }

        private fun loadModel(context: Context): MappedByteBuffer? =
            try {
                context.assets.openFd(TFLITE_MODEL_ASSET).use { descriptor ->
                    descriptor.mapReadOnly()
                }
            } catch (_: Throwable) {
                null
            }

        private fun AssetFileDescriptor.mapReadOnly(): MappedByteBuffer =
            FileInputStream(fileDescriptor).use { input ->
                input.channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                }
            }

        private fun IntArray.flattenedFeatureCount(): Int? {
            if (isEmpty()) return null
            val dimensions = if (size > 1) drop(1) else asList()
            if (dimensions.isEmpty() || dimensions.any { dimension -> dimension <= 0 }) return null
            return dimensions.fold(1) { total, dimension -> total * dimension }
        }
    }

    data class LumaReference(
        val width: Int,
        val height: Int,
        val zoneBounds: NormalizedRect,
        val samples: IntArray,
    )

    private data class SampledLuma(
        val values: IntArray,
        val quality: DetectionQuality,
    ) {
        companion object {
            val Empty = SampledLuma(IntArray(0), DetectionQuality.AnalyzerUnavailable)
        }
    }

    private data class ChangeAnalysis(
        val occupancy: Float,
        val largestChangedRegion: ChangedRegion,
    )

    private data class ChangedRegion(
        val score: Float,
        val bounds: NormalizedRect?,
        val addedObjectScore: Float,
        val removedObjectScore: Float,
        val confidence: Float,
        val classification: ChangeClassification,
    )

    private data class OpenCvComponent(
        val size: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val darker: Int,
        val brighter: Int,
        val indices: IntArray,
    )

    private data class AiVerifierInput(
        val occupancy: Float,
        val regionScore: Float,
        val addedObjectScore: Float,
        val removedObjectScore: Float,
        val localConfidence: Float,
        val classification: ChangeClassification,
    )

    private data class AiVerifierDecision(
        val available: Boolean,
        val classification: ChangeClassification,
        val confidence: Float,
    ) {
        companion object {
            val Unavailable = AiVerifierDecision(
                available = false,
                classification = ChangeClassification.None,
                confidence = 0f,
            )
        }
    }

    private data class LocalVerifierResult(
        val classification: ChangeClassification,
        val confidence: Float,
        val addedObjectScore: Float,
        val removedObjectScore: Float,
    )

    private data class RegionContrast(
        val currentContrast: Float,
        val referenceContrast: Float,
    ) {
        companion object {
            val Zero = RegionContrast(0f, 0f)
        }
    }

    private companion object {
        const val LUMA_MAGIC = 0x4149504D
        const val LUMA_VERSION = 1
        const val GRID_WIDTH = 48
        const val GRID_HEIGHT = 36
        const val CHANGE_THRESHOLD = 28
        const val MOTION_THRESHOLD = 16
        const val MIN_VERIFIER_REGION_SCORE = 0.012f
        const val STRONG_VERIFIER_REGION_SCORE = 0.06f
        const val BROAD_LIGHTING_REGION_SCORE = 0.24f
        const val CONTRAST_RING_SIZE = 2
        const val MIN_CONTRAST_GAP = 0.045f
        const val STRONG_CONTRAST_GAP = 0.16f
        const val MIN_OBJECT_CONTRAST = 0.055f
        const val DEFAULT_TABLE_ZONE_ID = "packing-table"
        const val TFLITE_MODEL_ASSET = "leftover_verifier.tflite"
        const val TFLITE_CONFIRMATION_THRESHOLD = 0.55f
        const val TFLITE_THREAD_COUNT = 2
        const val MAX_TFLITE_FEATURES = 64
        const val MAX_TFLITE_OUTPUT_CLASSES = 8
        const val FLOAT_BYTES = 4
    }

    private class ReferenceStore(context: Context) {
        private val directory = File(context.filesDir, "references")

        fun loadLumaReference(zoneId: String): LumaReference? {
            val lumaFile = lumaFile(zoneId)
            val fallbackFile = File(directory, "reference-luma.bin")
            val sourceFile = when {
                lumaFile.exists() -> lumaFile
                zoneId == DEFAULT_TABLE_ZONE_ID && fallbackFile.exists() -> fallbackFile
                else -> return null
            }

            return runCatching {
                DataInputStream(BufferedInputStream(FileInputStream(sourceFile))).use { input ->
                    val magic = input.readInt()
                    val version = input.readInt()
                    if (magic != LUMA_MAGIC || version != LUMA_VERSION) return null

                    val width = input.readInt()
                    val height = input.readInt()
                    val rect = NormalizedRect(
                        left = input.readFloat(),
                        top = input.readFloat(),
                        right = input.readFloat(),
                        bottom = input.readFloat(),
                    )
                    val count = input.readInt()
                    if (count != GRID_WIDTH * GRID_HEIGHT) return null

                    val samples = IntArray(count)
                    for (index in 0 until count) {
                        samples[index] = input.readUnsignedByte()
                    }

                    LumaReference(
                        width = width,
                        height = height,
                        zoneBounds = rect,
                        samples = samples,
                    )
                }
            }.getOrNull()
        }

        fun saveLumaReference(zoneId: String, reference: LumaReference) {
            directory.mkdirs()
            DataOutputStream(BufferedOutputStream(FileOutputStream(lumaFile(zoneId)))).use { output ->
                output.writeInt(LUMA_MAGIC)
                output.writeInt(LUMA_VERSION)
                output.writeInt(reference.width)
                output.writeInt(reference.height)
                output.writeFloat(reference.zoneBounds.left)
                output.writeFloat(reference.zoneBounds.top)
                output.writeFloat(reference.zoneBounds.right)
                output.writeFloat(reference.zoneBounds.bottom)
                output.writeInt(reference.samples.size)
                reference.samples.forEach { output.writeByte(it.coerceIn(0, 255)) }
            }
        }

        fun saveJpegReference(zoneId: String, image: ImageProxy) {
            directory.mkdirs()
            runCatching {
                val nv21 = image.toNv21()
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                FileOutputStream(jpegFile(zoneId)).use { output ->
                    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 86, output)
                }
            }
        }

        private fun lumaFile(zoneId: String): File =
            File(directory, "reference-luma-${zoneId.safeFileName()}.bin")

        private fun jpegFile(zoneId: String): File =
            File(directory, "reference-${zoneId.safeFileName()}.jpg")

        private fun String.safeFileName(): String =
            replace(Regex("[^A-Za-z0-9_.-]"), "_")

        private fun ImageProxy.toNv21(): ByteArray {
            val ySize = width * height
            val uvWidth = width / 2
            val uvHeight = height / 2
            val output = ByteArray(ySize + uvWidth * uvHeight * 2)

            copyPlane(
                plane = planes[0],
                output = output,
                offset = 0,
                outputStride = 1,
                planeWidth = width,
                planeHeight = height,
            )

            var outputIndex = ySize
            val uPlane = planes[1]
            val vPlane = planes[2]
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            for (row in 0 until uvHeight) {
                val uRowOffset = row * uPlane.rowStride
                val vRowOffset = row * vPlane.rowStride
                for (col in 0 until uvWidth) {
                    val uIndex = uRowOffset + col * uPlane.pixelStride
                    val vIndex = vRowOffset + col * vPlane.pixelStride
                    output[outputIndex++] = vBuffer.get(vIndex)
                    output[outputIndex++] = uBuffer.get(uIndex)
                }
            }

            return output
        }

        private fun copyPlane(
            plane: ImageProxy.PlaneProxy,
            output: ByteArray,
            offset: Int,
            outputStride: Int,
            planeWidth: Int,
            planeHeight: Int,
        ) {
            val buffer = plane.buffer
            var outputIndex = offset
            for (row in 0 until planeHeight) {
                val rowOffset = row * plane.rowStride
                for (col in 0 until planeWidth) {
                    val inputIndex = rowOffset + col * plane.pixelStride
                    output[outputIndex] = buffer.get(inputIndex)
                    outputIndex += outputStride
                }
            }
        }
    }
}
