package com.aipackingmonitor.vision

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class FrameDifferencingDetector(context: Context) {
    private val referenceStore = ReferenceStore(context.applicationContext)
    private var reference: LumaReference? = referenceStore.loadLumaReference()
    private var previousSamples: IntArray? = null

    @Synchronized
    fun hasReference(zone: MonitoringZone): Boolean =
        reference?.zoneBounds?.approximatelyEquals(zone.bounds) == true

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
        referenceStore.saveLumaReference(nextReference)
        referenceStore.saveJpegReference(image)
        reference = nextReference
        previousSamples = null
        return true
    }

    @Synchronized
    fun detect(image: ImageProxy, zone: MonitoringZone): DetectionResult {
        val started = System.nanoTime()
        val savedReference = reference
        if (savedReference == null) {
            return result(
                zone = zone,
                occupancy = 0f,
                motion = 0f,
                largestChangedRegion = 0f,
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
                stable = false,
                quality = current.quality,
                startedNanos = started,
            )
        }

        val diffCount = current.values.indices.count { index ->
            abs(current.values[index] - savedReference.samples[index]) > CHANGE_THRESHOLD
        }
        val occupancy = diffCount.toFloat() / current.values.size.toFloat()
        val largestChangedRegion = largestChangedRegionScore(
            reference = savedReference.samples,
            current = current.values,
        )
        val previous = previousSamples
        val motionScore = if (previous == null) {
            0f
        } else {
            current.values.indices.count { index ->
                abs(current.values[index] - previous[index]) > MOTION_THRESHOLD
            }.toFloat() / current.values.size.toFloat()
        }
        previousSamples = current.values

        val quality = when {
            occupancy > 0.82f && motionScore < 0.03f -> DetectionQuality.ReferenceMisaligned
            else -> DetectionQuality.Valid
        }

        return result(
            zone = zone,
            occupancy = occupancy,
            motion = motionScore,
            largestChangedRegion = largestChangedRegion,
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
        stable: Boolean,
        quality: DetectionQuality,
        startedNanos: Long,
    ): DetectionResult =
        DetectionResult(
            zoneId = zone.id,
            occupancyScore = occupancy.coerceIn(0f, 1f),
            motionScore = motion.coerceIn(0f, 1f),
            largestChangedRegionScore = largestChangedRegion.coerceIn(0f, 1f),
            isMotionStable = stable,
            quality = quality,
            analysisLatencyMs = (System.nanoTime() - startedNanos) / 1_000_000,
        )

    private fun largestChangedRegionScore(
        reference: IntArray,
        current: IntArray,
    ): Float {
        val changed = BooleanArray(current.size) { index ->
            abs(current[index] - reference[index]) > CHANGE_THRESHOLD
        }
        val visited = BooleanArray(current.size)
        var largest = 0

        for (index in changed.indices) {
            if (!changed[index] || visited[index]) continue

            var size = 0
            var stackSize = 0
            val stack = IntArray(changed.size)
            stack[stackSize++] = index
            visited[index] = true

            while (stackSize > 0) {
                val currentIndex = stack[--stackSize]
                size++

                val x = currentIndex % GRID_WIDTH
                val y = currentIndex / GRID_WIDTH
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

            largest = max(largest, size)
        }

        return largest.toFloat() / current.size.toFloat()
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

    private companion object {
        const val LUMA_MAGIC = 0x4149504D
        const val LUMA_VERSION = 1
        const val GRID_WIDTH = 48
        const val GRID_HEIGHT = 36
        const val CHANGE_THRESHOLD = 28
        const val MOTION_THRESHOLD = 16
    }

    private class ReferenceStore(context: Context) {
        private val directory = File(context.filesDir, "references")
        private val lumaFile = File(directory, "reference-luma.bin")
        private val jpegFile = File(directory, "reference.jpg")

        fun loadLumaReference(): LumaReference? {
            if (!lumaFile.exists()) return null

            return runCatching {
                DataInputStream(BufferedInputStream(FileInputStream(lumaFile))).use { input ->
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

        fun saveLumaReference(reference: LumaReference) {
            directory.mkdirs()
            DataOutputStream(BufferedOutputStream(FileOutputStream(lumaFile))).use { output ->
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

        fun saveJpegReference(image: ImageProxy) {
            directory.mkdirs()
            runCatching {
                val nv21 = image.toNv21()
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                FileOutputStream(jpegFile).use { output ->
                    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 86, output)
                }
            }
        }

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
